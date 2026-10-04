/**
 * Agent Terminal 窗口（AGENT_TERMINAL_CONSOLE §2 / §11 / §12）。
 *
 * 独立于主窗口的观察 + 控制客户端：Flux 自己执行命令，这里通过 SSE 实时看 AI 与用户的
 * 命令、stdout/stderr、状态与 exit code，并可 Stop / Force Stop。
 *
 * 关键约束：
 * - 不产生第二个 Agent、不复制执行逻辑：会话就是后端 TerminalSession（§6）；
 * - 关闭窗口只断开观察，不停 Agent；只有 Stop / Force Stop 才终止（§11）；
 * - 重开窗口接回最近的会话并按 seq 续读历史，断线重连由 Last-Event-ID 续读（§12）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent } from "react";

import { api, terminalStreamUrl } from "../../api/client";
import type { TerminalEvent, TerminalSession, TerminalSessionStatus } from "../../api/types";
import { setTerminalOpener } from "../../app/terminalWindow";

/** 前端保留的最大事件条数（§12：不要求无限历史，超出丢最早的） */
const MAX_EVENTS = 3000;

const STATUS_LABELS: Record<TerminalSessionStatus, string> = {
  active: "运行中",
  stopped: "已停止",
  closed: "已关闭",
};

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 追加事件：按 seq 去重、升序，超出上限丢最早的 */
function mergeEvent(previous: TerminalEvent[], next: TerminalEvent): TerminalEvent[] {
  if (previous.some((item) => item.seq === next.seq)) return previous;
  const merged = [...previous, next].sort((a, b) => a.seq - b.seq);
  return merged.length > MAX_EVENTS ? merged.slice(merged.length - MAX_EVENTS) : merged;
}

/** 输出事件：后端逐行产出，chunk 末尾带换行，渲染时去掉避免多出空行 */
function outputLines(chunk: string): string[] {
  return chunk.replace(/\n$/, "").split("\n");
}

function TerminalLine({ event }: { event: TerminalEvent }) {
  switch (event.kind) {
    case "terminal.session.created":
      return <div className="tw-line tw-dim">── 终端会话已创建</div>;
    case "terminal.command.started":
      return (
        <div className="tw-line tw-cmd">
          <span className={`tw-src ${event.source}`}>{event.source === "ai" ? "AI" : "USER"}</span>
          <span className="tw-prompt">$</span>
          <span className="tw-cmd-text">{event.command}</span>
        </div>
      );
    case "terminal.output":
      return (
        <>
          {outputLines(event.chunk ?? "").map((line, index) => (
            <div className="tw-line tw-out" key={`${event.seq}-${index}`}>
              {line}
            </div>
          ))}
        </>
      );
    case "terminal.command.finished":
      return <div className="tw-line tw-ok">↳ 命令结束 · exit code {event.exit_code}</div>;
    case "terminal.command.failed":
      return (
        <div className="tw-line tw-err">
          ↳ 命令失败 · exit code {event.exit_code ?? "未知"}
        </div>
      );
    case "terminal.stop.requested":
      return (
        <div className="tw-line tw-dim">
          ── 请求停止（{event.command === "force-stop" ? "强制停止" : "正常停止"}）
        </div>
      );
    case "terminal.process.terminated":
      return <div className="tw-line tw-dim">── 进程已终止</div>;
    case "terminal.session.closed":
      return <div className="tw-line tw-dim">── 会话已结束</div>;
    default:
      return <div className="tw-line tw-dim">{event.kind}</div>;
  }
}

export function TerminalWindow({ standalone = false }: { standalone?: boolean } = {}) {
  const [open, setOpen] = useState(standalone);
  const [sessions, setSessions] = useState<TerminalSession[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<TerminalEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [position, setPosition] = useState<{ x: number; y: number } | null>(null);

  const panelRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);

  const session = useMemo(
    () => sessions.find((item) => item.id === sessionId) ?? null,
    [sessions, sessionId],
  );
  const status = session?.status ?? null;
  const active = status === "active";

  /** 最近一条已开始、尚未结束的命令（§3.5 当前命令） */
  const currentCommand = useMemo(() => {
    let command: string | null = null;
    let source: TerminalEvent["source"] | null = null;
    for (const event of events) {
      if (event.kind === "terminal.command.started") {
        command = event.command;
        source = event.source;
      } else if (
        event.kind === "terminal.command.finished" ||
        event.kind === "terminal.command.failed"
      ) {
        command = null;
      }
    }
    return command ? { command, source } : null;
  }, [events]);

  /* 注册打开入口（跨视图常驻，由 App 挂载）；独立窗口模式下恒开，不需要入口 */
  useEffect(() => {
    if (standalone) return;
    setTerminalOpener(() => setOpen(true));
    return () => setTerminalOpener(null);
  }, [standalone]);

  const createSession = useCallback(async () => {
    try {
      setError(null);
      const created = await api.createTerminalSession();
      setSessions((previous) => [created, ...previous]);
      setSessionId(created.id);
    } catch (caught) {
      setError(errorText(caught));
    }
  }, []);

  const refreshSession = useCallback(async (id: string) => {
    try {
      const latest = await api.getTerminalSession(id);
      setSessions((previous) => previous.map((item) => (item.id === id ? latest : item)));
    } catch {
      // 状态刷新失败不打断观察：SSE 仍是权威的事件来源
    }
  }, []);

  /* 打开窗口：接回最近的会话；一个都没有时新开一个（§12） */
  useEffect(() => {
    if (!open || sessionId) return;
    let cancelled = false;
    void (async () => {
      try {
        const list = await api.listTerminalSessions();
        if (cancelled) return;
        setSessions(list);
        const reused = list.find((item) => item.status === "active") ?? list[0];
        if (reused) setSessionId(reused.id);
        else await createSession();
      } catch (caught) {
        if (!cancelled) setError(errorText(caught));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, sessionId, createSession]);

  /* SSE：先补历史再推实时（后端同一 seq 语义） */
  useEffect(() => {
    if (!open || !sessionId) return;
    setEvents([]);
    setConnected(false);
    stickRef.current = true;
    const source = new EventSource(terminalStreamUrl(sessionId, 0));
    const onEvent = (raw: MessageEvent<string>) => {
      let event: TerminalEvent;
      try {
        event = JSON.parse(raw.data) as TerminalEvent;
      } catch {
        return; // 坏帧忽略，不打断流
      }
      setEvents((previous) => mergeEvent(previous, event));
      if (event.kind === "terminal.session.closed") {
        source.close();
        setConnected(false);
        void refreshSession(sessionId);
      }
    };
    source.addEventListener("terminal.event", onEvent as EventListener);
    source.onopen = () => setConnected(true);
    // 断线由 EventSource 自动重连（带上 Last-Event-ID），这里只反映连接状态
    source.onerror = () => setConnected(false);
    return () => {
      source.removeEventListener("terminal.event", onEvent as EventListener);
      source.close();
    };
  }, [open, sessionId, refreshSession]);

  /* 贴底自动滚动：用户往上翻时不打断阅读 */
  useEffect(() => {
    const el = bodyRef.current;
    if (!el || !stickRef.current) return;
    el.scrollTop = el.scrollHeight;
  }, [events]);

  const onBodyScroll = () => {
    const el = bodyRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  };

  const runCommand = async () => {
    const command = input.trim();
    if (!command || !sessionId || !active) return;
    // 立刻清空输入：后端 POST /commands 要等命令跑完才返回，不能等到那时才反馈"已接受"
    setInput("");
    setBusy(true);
    setError(null);
    try {
      await api.runTerminalCommand(sessionId, command);
    } catch (caught) {
      setError(errorText(caught));
    } finally {
      setBusy(false);
    }
  };

  const stop = async (force: boolean) => {
    if (!sessionId || stopping) return;
    setStopping(true);
    setError(null);
    try {
      const stopped = await api.stopTerminalSession(sessionId, force);
      setSessions((previous) => previous.map((item) => (item.id === stopped.id ? stopped : item)));
    } catch (caught) {
      setError(errorText(caught));
    } finally {
      setStopping(false);
    }
  };

  const onHeaderDown = (event: ReactMouseEvent) => {
    const el = panelRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const offset = { x: event.clientX - rect.left, y: event.clientY - rect.top };
    const onMove = (move: MouseEvent) => {
      const maxX = window.innerWidth - el.offsetWidth;
      const maxY = window.innerHeight - el.offsetHeight;
      setPosition({
        x: Math.min(Math.max(0, move.clientX - offset.x), Math.max(0, maxX)),
        y: Math.min(Math.max(0, move.clientY - offset.y), Math.max(0, maxY)),
      });
    };
    const onUp = () => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  };

  if (!open) return null;

  return (
    <div
      className={`tw${standalone ? " is-standalone" : ""}`}
      ref={panelRef}
      style={
        standalone || !position
          ? undefined
          : { left: position.x, top: position.y, right: "auto", bottom: "auto" }
      }
      role="dialog"
      aria-label="Agent Terminal"
    >
      <div className="tw-head" onMouseDown={standalone ? undefined : onHeaderDown}>
        <span className="tw-dot" data-on={connected ? "1" : "0"} />
        <b>Agent Terminal</b>
        {session ? (
          <span className={`tw-status is-${status}`}>{status ? STATUS_LABELS[status] : ""}</span>
        ) : (
          <span className="tw-status">连接中…</span>
        )}
        {session?.run_id ? <span className="tw-run">Run {session.run_id.slice(0, 8)}</span> : null}
        <span className="tw-head-gap" />
        <button
          type="button"
          className="icon-btn sm"
          title="新建终端会话"
          onClick={() => void createSession()}
        >
          ＋
        </button>
        {standalone ? null : (
          <button
            type="button"
            className="icon-btn sm"
            title="关闭窗口（不会停止 Agent）"
            onClick={() => setOpen(false)}
          >
            ×
          </button>
        )}
      </div>

      <div className="tw-bar">
        <select
          className="tw-select"
          value={sessionId ?? ""}
          onChange={(event) => setSessionId(event.target.value || null)}
          title="切换终端会话（重开窗口接回历史）"
        >
          {sessions.length === 0 ? <option value="">（暂无会话）</option> : null}
          {sessions.map((item) => (
            <option key={item.id} value={item.id}>
              {item.id.slice(0, 8)} · {STATUS_LABELS[item.status]} · {item.workspace_root}
            </option>
          ))}
        </select>
        <span className="tw-bar-gap" />
        <button
          type="button"
          className="btn btn-ghost btn-xs"
          disabled={!sessionId || !active || stopping}
          onClick={() => void stop(false)}
        >
          Stop
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-xs tw-force"
          disabled={!sessionId || !active || stopping}
          onClick={() => void stop(true)}
        >
          Force Stop
        </button>
      </div>

      {currentCommand ? (
        <div className="tw-current">
          <span className={`tw-src ${currentCommand.source}`}>
            {currentCommand.source === "ai" ? "AI" : "USER"}
          </span>
          <span className="tw-cmd-text">{currentCommand.command}</span>
          <i>执行中…</i>
        </div>
      ) : null}

      <div className="tw-body" ref={bodyRef} onScroll={onBodyScroll}>
        {events.length === 0 ? (
          <div className="tw-empty">
            还没有终端事件。AI 执行命令或你在下方输入命令后，输出会实时出现在这里。
          </div>
        ) : (
          events.map((event) => <TerminalLine event={event} key={event.seq} />)
        )}
      </div>

      {error ? <div className="tw-error">{error}</div> : null}

      <form
        className="tw-input"
        onSubmit={(event) => {
          event.preventDefault();
          void runCommand();
        }}
      >
        <span className="tw-prompt">$</span>
        <input
          value={input}
          placeholder={active ? "输入命令（如 git status），回车执行" : "会话已结束，新建会话后可继续输入"}
          disabled={!active || busy}
          onChange={(event) => setInput(event.target.value)}
        />
        <button type="submit" className="btn btn-primary btn-xs" disabled={!active || busy || !input.trim()}>
          {busy ? "执行中…" : "执行"}
        </button>
      </form>
    </div>
  );
}
