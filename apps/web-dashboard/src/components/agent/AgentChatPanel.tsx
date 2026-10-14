/**
 * 与 Agent 的增量对话面板（可复用）。
 *
 * 每条指令 = 一次真实的 DSH Run：Agent 在同一个工作区里直接改码，改动以提案进入「变更」；
 * session_id 复用即同一会话。面板自己按 storageKey 分开保存会话 id 与对话记录（localStorage）。
 *
 * 两个使用场景共用同一实现：
 * - IDE 右栏「一点一点改代码」
 * - Solo 中栏「单 Agent」模式
 */
import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

import { ApiError, api } from "../../api/client";
import type { AgentHandle, DshRun, DshStatus } from "../../api/types";

/** 对话里的一条：用户指令，或 Agent 的回执 */
export interface AgentTurn {
  id: string;
  role: "user" | "agent";
  text: string;
  status: "running" | "done" | "failed" | "stopped";
  runId?: string;
  durationMs?: number | null;
}

/** Run 是否已到终态（后端用字符串返回状态，统一按小写比对待） */
function isRunSettled(run: DshRun): boolean {
  return !["pending", "starting", "running"].includes(String(run.status).toLowerCase());
}

/** Run 是否成功结束 */
function isRunOk(run: DshRun): boolean {
  return String(run.status).toLowerCase() === "completed";
}

/** 这次 Run 要展示的文本：优先终态总结，其次累积的助手片段，最后才是错误原因 */
function runText(run: DshRun): string {
  if (run.final_response.trim()) return run.final_response.trim();
  const joined = run.events
    .filter((event) => event.event_type === "assistant/message" && event.text)
    .map((event) => String(event.text))
    .join("\n")
    .trim();
  if (joined) return joined;
  return run.error ? `执行失败：${run.error}` : "";
}

/** 读回某个命名空间上次的对话；上次没收尾的「执行中」在重载后不可能还在跑，按已停止落定 */
function readChat(storageKey: string): AgentTurn[] {
  try {
    const stored = JSON.parse(window.localStorage.getItem(`${storageKey}.chat`) ?? "[]") as unknown;
    if (!Array.isArray(stored)) return [];
    return stored
      .filter(
        (item): item is AgentTurn =>
          typeof item === "object" && item !== null && "role" in item && "text" in item,
      )
      .map((item) => (item.status === "running" ? { ...item, status: "stopped" as const } : item));
  } catch {
    return [];
  }
}

interface Props {
  /** localStorage 命名空间：会话 id 与对话记录按它分开存（如 flux.solo.agent.<taskId>） */
  storageKey: string;
  /** 执行者：null / 缺省 = 内置 DSH；切换不会清空已有会话 */
  agentId?: string | null;
  /** 传了 agents 才渲染顶部的执行者下拉 */
  agents?: AgentHandle[];
  onAgentChange?: (agentId: string | null) => void;
  /** 运行时状态：用于顶部标签与输入框可用性 */
  dsh: DshStatus | null;
  /** 空态说明（无对话时显示） */
  emptyNote: ReactNode;
  placeholder: string;
  /** 输入框下方的提示（如当前任务 id） */
  composeHint: ReactNode;
  /** 一轮 Run 落定后回调（刷新改动 / 任务等）；用 ref 持有，不会引起重复轮询 */
  onSettled?: (runId: string | null) => void;
  /** 输入框上方插槽（改动汇总条等） */
  aboveCompose?: ReactNode;
}

export function AgentChatPanel({
  storageKey,
  agentId = null,
  agents,
  onAgentChange,
  dsh,
  emptyNote,
  placeholder,
  composeHint,
  onSettled,
  aboveCompose,
}: Props) {
  const [turns, setTurns] = useState<AgentTurn[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [runId, setRunId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sessionRef = useRef<string>("");
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const settledRef = useRef(onSettled);
  settledRef.current = onSettled;

  const dshReady = Boolean(dsh?.enabled);

  /* 切换命名空间：读回该会话的对话与上次会话 id（复用即同一会话） */
  useEffect(() => {
    setTurns(readChat(storageKey));
    sessionRef.current = window.localStorage.getItem(`${storageKey}.session`) ?? "";
    setDraft("");
    setError(null);
    setBusy(false);
    setRunId(null);
  }, [storageKey]);

  /* 就地留存（只留最近 40 条，避免 localStorage 无限增长） */
  useEffect(() => {
    window.localStorage.setItem(`${storageKey}.chat`, JSON.stringify(turns.slice(-40)));
  }, [turns, storageKey]);

  /* 新消息自动贴底 */
  useEffect(() => {
    const node = scrollRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [turns]);

  /** 下发一条指令：起一次 DSH Run */
  const send = useCallback(async () => {
    const text = draft.trim();
    if (!text || busy) return;
    if (!dshReady) {
      setError("DSH 运行时未启用，无法下发指令");
      return;
    }
    const stamp = Date.now();
    const replyId = `a-${stamp}`;
    setTurns((prev) => [
      ...prev,
      { id: `u-${stamp}`, role: "user", text, status: "done" },
      { id: replyId, role: "agent", text: "", status: "running" },
    ]);
    setDraft("");
    setError(null);
    setBusy(true);
    try {
      const run = await api.startDshRun(text, sessionRef.current || undefined, agentId);
      sessionRef.current = run.session_id;
      window.localStorage.setItem(`${storageKey}.session`, run.session_id);
      setRunId(run.run_id);
      setTurns((prev) =>
        prev.map((turn) => (turn.id === replyId ? { ...turn, runId: run.run_id } : turn)),
      );
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "指令下发失败";
      setError(message);
      setBusy(false);
      setTurns((prev) =>
        prev.map((turn) =>
          turn.id === replyId ? { ...turn, text: message, status: "failed" } : turn,
        ),
      );
    }
  }, [draft, busy, dshReady, agentId, storageKey]);

  /** 停止当前 Run（后端会先把进程树清干净再返回） */
  const stop = useCallback(async () => {
    if (!runId) return;
    try {
      await api.interruptDshRun(runId);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "停止失败");
    }
  }, [runId]);

  const clear = useCallback(() => {
    setTurns([]);
    setError(null);
  }, []);

  /* 轮询当前 Run：进度写在对话里；终态后回调上层刷新 */
  useEffect(() => {
    if (!runId) return;
    let alive = true;
    const tick = async () => {
      try {
        const run = await api.getDshRun(runId);
        if (!alive) return;
        const settled = isRunSettled(run);
        const text = runText(run);
        setTurns((prev) =>
          prev.map((turn) =>
            turn.runId === run.run_id
              ? {
                  ...turn,
                  text: text || turn.text,
                  status: settled ? (isRunOk(run) ? "done" : "failed") : "running",
                  durationMs: run.duration_seconds,
                }
              : turn,
          ),
        );
        if (settled) {
          setRunId(null);
          setBusy(false);
          settledRef.current?.(run.run_id);
        }
      } catch (err) {
        if (!alive) return;
        setRunId(null);
        setBusy(false);
        setError(err instanceof ApiError ? err.message : "无法获取 Run 状态");
      }
    };
    void tick();
    const timer = window.setInterval(() => { void tick(); }, 1000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [runId]);

  return (
    <div className="agent-panel">
      {agents && onAgentChange ? (
        <div className="agent-pick">
          <span className="ap-label">执行 Agent</span>
          <select
            className="ap-select"
            value={agentId ?? ""}
            disabled={busy}
            onChange={(event) => onAgentChange(event.target.value || null)}
          >
            <option value="">内置 DSH（默认）</option>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.spec.name}
                {agent.spec.runtime && agent.spec.runtime !== "dsh" ? ` · ${agent.spec.runtime}` : ""}
              </option>
            ))}
          </select>
          <span className="ap-model">{dsh?.enabled ? `${dsh.provider}/${dsh.model}` : "DSH 未启用"}</span>
        </div>
      ) : null}

      <div className="ag-chat" ref={scrollRef}>
        <div className="ag-chat-head">
          <span>增量改码</span>
          <span className="ag-chat-sub">
            {dsh?.enabled ? `${dsh.provider}/${dsh.model}` : "DSH 运行时未启用"}
          </span>
          {turns.length > 0 ? (
            <button type="button" className="link" onClick={clear}>
              清空
            </button>
          ) : null}
        </div>
        {turns.length === 0 ? (
          <div className="ag-note">{emptyNote}</div>
        ) : (
          turns.map((turn) => (
            <div key={turn.id} className={`ag-turn is-${turn.role}`}>
              <div className="ag-turn-meta">
                {turn.role === "user" ? "你" : "Agent"}
                {turn.status === "running" ? " · 执行中…" : null}
                {turn.status === "failed" ? " · 失败" : null}
                {turn.status === "stopped" ? " · 已停止" : null}
                {turn.status === "done" && turn.durationMs ? ` · ${turn.durationMs.toFixed(1)}s` : null}
              </div>
              <div className="ag-bubble">{turn.text || (turn.status === "running" ? "正在执行…" : "")}</div>
            </div>
          ))
        )}
      </div>

      {error ? <div className="ag-error">{error}</div> : null}

      {aboveCompose}

      <div className="ag-compose">
        <textarea
          className="ag-input"
          value={draft}
          placeholder={dshReady ? placeholder : "DSH 运行时未启用，无法下发指令"}
          disabled={!dshReady || busy}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              void send();
            }
          }}
        />
        <div className="ag-compose-foot">
          <span className="ag-compose-hint">{composeHint}</span>
          {busy && runId ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void stop()}>
              停止
            </button>
          ) : null}
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={!dshReady || busy || !draft.trim()}
            onClick={() => void send()}
          >
            发送
          </button>
        </div>
      </div>
    </div>
  );
}
