/**
 * 任务流（SSE）归约器：把后端 task.event 归约成聊天面板可直接渲染的快照。
 *
 * 与手机端 StreamReducer 保持同一套状态语义，并按 Web 的落库方式做了两点调整：
 * - `run_status` 只结束“活跃”状态，暂不清正文，等 `run_finished` 与持久化消息一起切换，
 *   避免执行结束后正文先消失再出现的闪烁；
 * - 正文/思考都先进入缓冲，由调用方按约 50ms 批量 flush，避免每个 token 触发 React 重渲染。
 */
import type { TaskMessage, TaskStreamEvent } from "../../api/types";

const MAX_LIVE_TEXT = 20_000;
const MAX_REASONING = 8_000;
const TRUNCATED_MARK = "…（前文已折叠）\n";

export type StreamConnection = "idle" | "connecting" | "live" | "reconnecting" | "failed";
export type StreamToolStatus = "running" | "done" | "failed";
export type StreamApplyResult = "committed" | "buffered" | "ignored";

export interface StreamReasoning {
  text: string;
  active: boolean;
  /** 历史轨迹没有逐段计时，允许为 null（不再显示耗时） */
  startedAtMs: number | null;
  endedAtMs: number | null;
}

export interface StreamToolStep {
  callId: string;
  name: string;
  detail: string;
  output: string;
  status: StreamToolStatus;
  category: string;
  paths: string[];
}

export interface StreamErrorLine {
  id: number;
  text: string;
}

export interface TaskStreamSnapshot {
  connection: StreamConnection;
  liveText: string;
  liveLabel: string | null;
  liveActive: boolean;
  reasoning: StreamReasoning | null;
  tools: StreamToolStep[];
  errorLines: StreamErrorLine[];
  terminalError: string | null;
  cancelled: boolean;
  retryAttempt: number;
  lastSeq: number;
  runId: string | null;
  startedAtMs: number | null;
  endedAtMs: number | null;
  revision: number;
}

const RECONNECT_DELAYS = [1_000, 2_000, 4_000, 8_000, 15_000];

export function reconnectDelayMs(attempt: number): number {
  if (attempt <= 0) return RECONNECT_DELAYS[0] ?? 1_000;
  return RECONNECT_DELAYS[Math.min(attempt, RECONNECT_DELAYS.length) - 1] ?? 15_000;
}

function initialSnapshot(): TaskStreamSnapshot {
  return {
    connection: "idle",
    liveText: "",
    liveLabel: null,
    liveActive: false,
    reasoning: null,
    tools: [],
    errorLines: [],
    terminalError: null,
    cancelled: false,
    retryAttempt: 0,
    lastSeq: 0,
    runId: null,
    startedAtMs: null,
    endedAtMs: null,
    revision: 0,
  };
}

function appendLimited(previous: string, addition: string, limit: number): string {
  if (!addition) return previous;
  const bare = previous.startsWith(TRUNCATED_MARK) ? previous.slice(TRUNCATED_MARK.length) : previous;
  const next = bare + addition;
  if (next.length <= limit) return next;
  return TRUNCATED_MARK + next.slice(next.length - limit);
}

function readString(data: Record<string, unknown>, key: string): string {
  const value = data[key];
  return typeof value === "string" ? value : "";
}

function readPaths(data: Record<string, unknown>): string[] {
  const value = data.paths;
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string");
}

export function normalizeToolStatus(raw: string): StreamToolStatus {
  if (/fail|error|cancel|abort/i.test(raw)) return "failed";
  if (/complete|done|success|ok|finish/i.test(raw)) return "done";
  return "running";
}

function finishReasoning(
  reasoning: StreamReasoning | null,
  at: number,
): StreamReasoning | null {
  if (!reasoning) return null;
  if (!reasoning.active && reasoning.endedAtMs !== null) return reasoning;
  return { ...reasoning, active: false, endedAtMs: reasoning.endedAtMs ?? at };
}

function finishRunningTools(tools: StreamToolStep[], failed = false): StreamToolStep[] {
  if (tools.length === 0) return tools;
  let changed = false;
  const next = tools.map((tool) => {
    if (tool.status !== "running") return tool;
    changed = true;
    return { ...tool, status: failed ? ("failed" as const) : ("done" as const) };
  });
  return changed ? next : tools;
}

export class TaskStreamReducer {
  private state: TaskStreamSnapshot = initialSnapshot();
  private pendingText = "";
  private pendingReasoning = "";
  private lastSeq = 0;
  private errorSeq = 0;
  private readonly now: () => number;

  constructor(now: () => number = Date.now) {
    this.now = now;
  }

  snapshot(): TaskStreamSnapshot {
    return this.state;
  }

  reset(): TaskStreamSnapshot {
    this.pendingText = "";
    this.pendingReasoning = "";
    this.lastSeq = 0;
    this.errorSeq = 0;
    this.state = initialSnapshot();
    return this.state;
  }

  apply(event: TaskStreamEvent): StreamApplyResult {
    const seq = typeof event.seq === "number" ? event.seq : 0;
    if (seq > 0) {
      if (seq <= this.lastSeq) return "ignored";
      this.lastSeq = seq;
    }
    const data = (event.data ?? {}) as Record<string, unknown>;
    const type = event.type;

    switch (type) {
      case "user_message": {
        this.pendingText = "";
        this.pendingReasoning = "";
        return this.commitApply({
          liveText: "",
          liveLabel: null,
          liveActive: false,
          reasoning: null,
          tools: [],
          errorLines: [],
          terminalError: null,
          cancelled: false,
          runId: null,
          startedAtMs: null,
          endedAtMs: null,
        });
      }
      case "assistant_start": {
        this.pendingText = "";
        this.pendingReasoning = "";
        this.errorSeq = 0;
        return this.commitApply({
          liveText: "",
          liveLabel: "正在输入…",
          liveActive: true,
          reasoning: null,
          tools: [],
          errorLines: [],
          terminalError: null,
          cancelled: false,
          runId: null,
          startedAtMs: this.now(),
          endedAtMs: null,
        });
      }
      case "assistant_delta": {
        const addition = readString(data, "text");
        if (!addition) return "ignored";
        this.pendingText += addition;
        if (!this.state.liveActive) {
          return this.commitApply({
            liveActive: true,
            liveLabel: "正在输入…",
            startedAtMs: this.state.startedAtMs ?? this.now(),
            endedAtMs: null,
            terminalError: null,
          });
        }
        return "buffered";
      }
      case "assistant_message": {
        this.pendingText = "";
        this.pendingReasoning = "";
        return this.commitApply({
          liveText: "",
          liveLabel: null,
          liveActive: false,
          reasoning: finishReasoning(this.state.reasoning, this.now()),
          tools: finishRunningTools(this.state.tools),
          terminalError: null,
          endedAtMs: this.state.endedAtMs ?? this.now(),
        });
      }
      case "assistant_error": {
        this.pendingText = "";
        this.pendingReasoning = "";
        return this.commitApply({
          liveLabel: null,
          liveActive: false,
          reasoning: finishReasoning(this.state.reasoning, this.now()),
          tools: finishRunningTools(this.state.tools, true),
          terminalError: readString(data, "error") || "助手调用失败",
          endedAtMs: this.now(),
        });
      }
      case "run_started": {
        this.pendingText = "";
        this.pendingReasoning = "";
        this.errorSeq = 0;
        const runtime = readString(data, "runtime");
        return this.commitApply({
          liveText: "",
          liveLabel: runtime ? `${runtime} 执行中` : "执行中…",
          liveActive: true,
          reasoning: null,
          tools: [],
          errorLines: [],
          terminalError: null,
          cancelled: false,
          runId: readString(data, "run_id") || null,
          startedAtMs: this.now(),
          endedAtMs: null,
        });
      }
      case "run_event": {
        return this.applyRunEvent(data);
      }
      case "run_status": {
        const status = readString(data, "status").toLowerCase();
        const failed = status === "failed" || status === "error";
        this.flushPending();
        return this.commitApply({
          liveActive: false,
          liveLabel: null,
          reasoning: finishReasoning(this.state.reasoning, this.now()),
          tools: finishRunningTools(this.state.tools, failed),
          terminalError: failed
            ? readString(data, "error") || "执行失败"
            : this.state.terminalError,
          endedAtMs: this.state.endedAtMs ?? this.now(),
        });
      }
      case "run_finished": {
        // 终态由落库的 run_finished 消息接管：清掉实时气泡的全部内容，
        // 思考/工具/错误改从消息下方的执行轨迹卡回看，避免与持久化消息重复。
        this.pendingText = "";
        this.pendingReasoning = "";
        this.errorSeq = 0;
        return this.commitApply({
          liveText: "",
          liveLabel: null,
          liveActive: false,
          reasoning: null,
          tools: [],
          errorLines: [],
          terminalError: null,
          endedAtMs: this.state.endedAtMs ?? this.now(),
          runId: readString(data, "run_id") || this.state.runId,
        });
      }
      case "run_retry": {
        return this.commitApply({
          liveActive: true,
          liveLabel: readString(data, "message") || "上游限流，正在重试…",
          startedAtMs: this.state.startedAtMs ?? this.now(),
          terminalError: null,
        });
      }
      default:
        return "ignored";
    }
  }

  flush(): TaskStreamSnapshot {
    if (!this.pendingText && !this.pendingReasoning) return this.state;
    const patch: Partial<TaskStreamSnapshot> = {};
    if (this.pendingText) {
      patch.liveText = appendLimited(this.state.liveText, this.pendingText, MAX_LIVE_TEXT);
      this.pendingText = "";
    }
    if (this.pendingReasoning) {
      const base: StreamReasoning =
        this.state.reasoning ?? {
          text: "",
          active: true,
          startedAtMs: this.now(),
          endedAtMs: null,
        };
      patch.reasoning = {
        ...base,
        text: appendLimited(base.text, this.pendingReasoning, MAX_REASONING),
      };
      this.pendingReasoning = "";
    }
    return this.commit(patch);
  }

  onConnecting(): TaskStreamSnapshot {
    return this.commit({ connection: "connecting" });
  }

  onOpen(): TaskStreamSnapshot {
    return this.commit({ connection: "live", retryAttempt: 0, terminalError: null });
  }

  onDisconnected(): TaskStreamSnapshot {
    return this.commit({
      connection: "reconnecting",
      retryAttempt: this.state.retryAttempt + 1,
    });
  }

  onTerminal(message: string): TaskStreamSnapshot {
    return this.commit({ connection: "failed", terminalError: message });
  }

  retry(): TaskStreamSnapshot {
    return this.commit({ connection: "connecting", retryAttempt: 0, terminalError: null });
  }

  markCancelled(): TaskStreamSnapshot {
    this.pendingText = "";
    this.pendingReasoning = "";
    return this.commit({
      liveActive: false,
      liveLabel: null,
      cancelled: true,
      reasoning: finishReasoning(this.state.reasoning, this.now()),
      tools: finishRunningTools(this.state.tools),
      endedAtMs: this.now(),
    });
  }

  stop(): void {
    this.pendingText = "";
    this.pendingReasoning = "";
  }

  private applyRunEvent(data: Record<string, unknown>): StreamApplyResult {
    const kind = readString(data, "kind");
    if (kind === "thinking") {
      const addition = readString(data, "text");
      if (!addition) return "ignored";
      this.pendingReasoning += addition;
      const reasoning = this.state.reasoning;
      if (!reasoning) {
        return this.commitApply({
          liveActive: true,
          liveLabel: "正在思考…",
          reasoning: {
            text: "",
            active: true,
            startedAtMs: this.now(),
            endedAtMs: null,
          },
          startedAtMs: this.state.startedAtMs ?? this.now(),
          endedAtMs: null,
        });
      }
      if (!reasoning.active) {
        return this.commitApply({
          liveActive: true,
          liveLabel: "正在思考…",
          reasoning: { ...reasoning, active: true, endedAtMs: null },
          startedAtMs: this.state.startedAtMs ?? this.now(),
          endedAtMs: null,
        });
      }
      return "buffered";
    }

    if (kind === "text") {
      const addition = readString(data, "text");
      if (!addition) return "ignored";
      this.pendingText += addition;
      if (!this.state.liveActive) {
        return this.commitApply({
          liveActive: true,
          liveLabel: "正在输出…",
          startedAtMs: this.state.startedAtMs ?? this.now(),
          endedAtMs: null,
        });
      }
      return "buffered";
    }

    if (kind === "tool") {
      this.flushPending();
      const name = readString(data, "name") || "tool";
      const callId =
        readString(data, "call_id") ||
        readString(data, "callId") ||
        `${name}-${this.state.tools.length}`;
      const detail = readString(data, "detail");
      const output = readString(data, "output");
      const category = readString(data, "category") || "other";
      const status = normalizeToolStatus(readString(data, "status"));
      const paths = readPaths(data);
      const index = this.state.tools.findIndex((tool) => tool.callId === callId);
      const existing = index >= 0 ? this.state.tools[index] : undefined;
      let tools: StreamToolStep[];
      if (existing) {
        const merged: StreamToolStep = {
          ...existing,
          name: name || existing.name,
          detail: detail || existing.detail,
          output: output || existing.output,
          category: category || existing.category,
          status,
          paths: paths.length > 0 ? Array.from(new Set([...existing.paths, ...paths])) : existing.paths,
        };
        tools = this.state.tools.slice();
        tools[index] = merged;
      } else {
        tools = [
          ...this.state.tools,
          {
            callId,
            name,
            detail,
            output,
            category,
            status,
            paths,
          },
        ];
      }
      return this.commitApply({
        tools,
        liveActive: true,
        liveLabel: this.state.liveLabel ?? "正在执行…",
        startedAtMs: this.state.startedAtMs ?? this.now(),
        endedAtMs: null,
      });
    }

    if (kind === "error") {
      this.flushPending();
      const text = readString(data, "text") || readString(data, "detail") || "执行出错";
      this.errorSeq += 1;
      return this.commitApply({
        errorLines: [...this.state.errorLines, { id: this.errorSeq, text }],
        reasoning: finishReasoning(this.state.reasoning, this.now()),
        tools: finishRunningTools(this.state.tools, true),
        liveLabel: "执行出错",
        endedAtMs: this.state.endedAtMs ?? this.now(),
      });
    }

    if (kind === "status") {
      return this.commitApply({
        liveLabel: readString(data, "text") || this.state.liveLabel,
      });
    }

    return "ignored";
  }

  private flushPending(): void {
    this.flush();
  }

  private commit(patch: Partial<TaskStreamSnapshot>): TaskStreamSnapshot {
    this.state = {
      ...this.state,
      ...patch,
      lastSeq: this.lastSeq,
      revision: this.state.revision + 1,
    };
    return this.state;
  }

  /** apply/applyRunEvent 专用：提交补丁并报告事件已消费 */
  private commitApply(patch: Partial<TaskStreamSnapshot>): StreamApplyResult {
    this.commit(patch);
    return "committed";
  }
}

/**
 * 合并任务消息：按 id 去重、按 seq 升序。用于 REST 返回值与 SSE 事件同时到达的场景。
 */
export function mergeMessages(previous: TaskMessage[], incoming: TaskMessage[]): TaskMessage[] {
  if (incoming.length === 0) return previous;
  const map = new Map<string, TaskMessage>();
  for (const message of previous) map.set(message.id, message);
  for (const message of incoming) {
    if (!message?.id) continue;
    const existing = map.get(message.id);
    map.set(message.id, existing ? { ...existing, ...message } : message);
  }
  const merged = Array.from(map.values());
  merged.sort((a, b) => a.seq - b.seq);
  return merged;
}

export function hasStreamActivity(snapshot: TaskStreamSnapshot): boolean {
  return Boolean(
    snapshot.liveText ||
      snapshot.reasoning?.text ||
      snapshot.tools.length > 0 ||
      snapshot.errorLines.length > 0 ||
      snapshot.terminalError,
  );
}

export function formatDurationMs(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(ms)) return "";
  const totalSeconds = Math.max(0, Math.round(ms / 1000));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${String(seconds).padStart(2, "0")}s`;
}

export function formatElapsed(startedAtMs: number | null, endedAtMs: number | null, nowMs: number): string {
  if (startedAtMs === null) return "";
  return formatDurationMs((endedAtMs ?? nowMs) - startedAtMs);
}
