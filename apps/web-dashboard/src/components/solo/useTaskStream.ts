/**
 * 任务对话 SSE：fetch + ReadableStream（浏览器 EventSource 不能带 Authorization 头）。
 *
 * 职责：
 * - 从 `after_seq` 续传，按 seq 去重，断线指数退避重连，超时看门狗；
 * - 约 50ms 批量 flush 正文/思考，避免逐 token 触发整棵聊天树重渲染；
 * - 401 续期一次；StrictMode 双挂载安全；
 * - 把持久化消息与终态事件通过回调交给页面（页面只做消息合并与任务刷新）。
 */
import { useCallback, useEffect, useRef, useState } from "react";

import { getAuth, refreshAuth } from "../../api/auth";
import { authHeaders, taskStreamUrl } from "../../api/client";
import type { TaskMessage, TaskStreamEvent } from "../../api/types";
import { createSseParser, type SseFrame } from "../../utils/sse";
import {
  TaskStreamReducer,
  reconnectDelayMs,
  type TaskStreamSnapshot,
} from "./taskStream";

const MAX_RECONNECT_ATTEMPTS = 8;
const WATCHDOG_MS = 60_000;
const WATCHDOG_TICK_MS = 15_000;
const FLUSH_MS = 50;

export interface TaskStreamCallbacks {
  /** user_message / assistant_message / run_finished 带出的已落库消息 */
  onPersistedMessage?: (message: TaskMessage) => void;
  /** run_status / run_finished：页面据此刷新任务、改动与消息 */
  onRunSettled?: (event: TaskStreamEvent) => void;
}

export interface TaskStreamHandle {
  snapshot: TaskStreamSnapshot;
  retry: () => void;
}

export function useTaskStream(
  taskId: string | null,
  callbacks: TaskStreamCallbacks = {},
): TaskStreamHandle {
  const reducerRef = useRef<TaskStreamReducer>(new TaskStreamReducer());
  const [snapshot, setSnapshot] = useState<TaskStreamSnapshot>(() => reducerRef.current.snapshot());
  const [retryNonce, setRetryNonce] = useState(0);
  const lastSeqRef = useRef(0);
  const taskRef = useRef<string | null>(null);
  const callbacksRef = useRef(callbacks);
  callbacksRef.current = callbacks;

  const retry = useCallback(() => setRetryNonce((value) => value + 1), []);

  useEffect(() => {
    const sameTask = taskRef.current === taskId;
    taskRef.current = taskId;

    if (!taskId) {
      reducerRef.current.reset();
      lastSeqRef.current = 0;
      setSnapshot(reducerRef.current.snapshot());
      return;
    }

    if (!sameTask) {
      reducerRef.current = new TaskStreamReducer();
      lastSeqRef.current = 0;
    }
    const reducer = reducerRef.current;
    let lastPushed = reducer.snapshot();
    if (sameTask) lastPushed = reducer.retry();
    setSnapshot(lastPushed);

    let alive = true;
    let generation = 0;
    let retryTimer: number | null = null;
    let flushTimer: number | null = null;
    let watchdogTimer: number | null = null;
    let controller: AbortController | null = null;
    let lastByteAt = Date.now();

    const push = (next: TaskStreamSnapshot) => {
      if (!alive) return;
      lastPushed = next;
      setSnapshot(next);
    };

    const scheduleRetry = (delay: number) => {
      if (!alive) return;
      if (retryTimer !== null) window.clearTimeout(retryTimer);
      retryTimer = window.setTimeout(() => {
        retryTimer = null;
        void connect();
      }, delay);
    };

    const drop = (reason: string) => {
      if (!alive) return;
      generation += 1;
      controller?.abort();
      controller = null;
      const next = reducer.onDisconnected();
      push(next);
      if (next.retryAttempt > MAX_RECONNECT_ATTEMPTS) {
        push(reducer.onTerminal(`实时连接失败：${reason}`));
        return;
      }
      scheduleRetry(reconnectDelayMs(next.retryAttempt));
    };

    const handleFrames = (frames: SseFrame[]) => {
      for (const frame of frames) {
        if (!alive) return;
        let event: TaskStreamEvent;
        try {
          event = JSON.parse(frame.data) as TaskStreamEvent;
        } catch {
          continue;
        }
        if (!event || typeof event.type !== "string" || typeof event.seq !== "number") continue;
        if (event.seq > lastSeqRef.current) lastSeqRef.current = event.seq;

        const result = reducer.apply(event);
        if (result === "committed") push(reducer.snapshot());

        if (
          event.type === "user_message" ||
          event.type === "assistant_message" ||
          event.type === "run_finished"
        ) {
          const message = event.data?.message as TaskMessage | undefined;
          if (message) callbacksRef.current.onPersistedMessage?.(message);
        }
        if (event.type === "run_status" || event.type === "run_finished") {
          callbacksRef.current.onRunSettled?.(event);
        }
      }
    };

    const connect = async (): Promise<void> => {
      if (!alive) return;
      const myGeneration = ++generation;
      const seq = lastSeqRef.current;
      const url = taskStreamUrl(taskId, seq);
      const signalRef = { current: null as AbortController | null };

      push(reducer.onConnecting());
      lastByteAt = Date.now();

      try {
        controller = new AbortController();
        signalRef.current = controller;
        const signal = controller.signal;

        let response = await fetch(url, {
          headers: authHeaders({ Accept: "text/event-stream", "Cache-Control": "no-cache" }),
          signal,
          cache: "no-store",
        });
        if (response.status === 401 && getAuth()) {
          const renewed = await refreshAuth();
          if (renewed) {
            response = await fetch(url, {
              headers: authHeaders({ Accept: "text/event-stream", "Cache-Control": "no-cache" }),
              signal,
              cache: "no-store",
            });
          }
        }
        if (!response.ok || !response.body) {
          throw new Error(`实时连接不可用（HTTP ${response.status}）`);
        }
        if (!alive || myGeneration !== generation) return;

        push(reducer.onOpen());

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parser = createSseParser();
        while (alive && myGeneration === generation) {
          const { done, value } = await reader.read();
          if (done) break;
          lastByteAt = Date.now();
          handleFrames(parser.push(decoder.decode(value, { stream: true })));
        }
        if (!alive || myGeneration !== generation) return;
        handleFrames(parser.flush());
        drop("连接已断开");
      } catch (error) {
        if (!alive || myGeneration !== generation) return;
        if (signalRef.current?.signal.aborted) return;
        const message = error instanceof Error && error.message ? error.message : "连接失败";
        drop(message);
      }
    };

    flushTimer = window.setInterval(() => {
      const next = reducer.flush();
      if (next !== lastPushed) push(next);
    }, FLUSH_MS);

    watchdogTimer = window.setInterval(() => {
      if (!alive) return;
      if (Date.now() - lastByteAt > WATCHDOG_MS) drop("连接超时");
    }, WATCHDOG_TICK_MS);

    void connect();

    return () => {
      alive = false;
      generation += 1;
      if (flushTimer !== null) window.clearInterval(flushTimer);
      if (retryTimer !== null) window.clearTimeout(retryTimer);
      if (watchdogTimer !== null) window.clearInterval(watchdogTimer);
      controller?.abort();
      // 保留已缓冲的正文：StrictMode 重挂载会复用同一个 reducer 并从 lastSeq 续传
      reducer.flush();
    };
  }, [taskId, retryNonce]);

  return { snapshot, retry };
}
