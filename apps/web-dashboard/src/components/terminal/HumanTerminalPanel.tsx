import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { api, terminalStreamUrl } from "../../api/client";
import type { TerminalEvent, TerminalSession } from "../../api/types";

const MAX_EVENTS = 3000;

function mergeEvent(previous: TerminalEvent[], next: TerminalEvent): TerminalEvent[] {
  if (previous.some((event) => event.seq === next.seq)) return previous;
  const merged = [...previous, next].sort((a, b) => a.seq - b.seq);
  return merged.length > MAX_EVENTS ? merged.slice(-MAX_EVENTS) : merged;
}

function outputLines(chunk: string): string[] {
  return chunk.replace(/\n$/, "").split("\n");
}

function labelFor(session: TerminalSession, index: number): string {
  const root = session.workspace_root.split("/").filter(Boolean).pop();
  return root ? `${root} ${index + 1}` : `Terminal ${index + 1}`;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Flux 人用集成终端。
 *
 * 与 Agent Terminal 完全隔离：只创建 run_id=null 的用户会话，不复用 Agent 会话。
 * 当前后端终端协议以「命令 + SSE 输出」为基础，因此这里先做到 VS Code 风格的
 * Panel / tabs / scrollback / new terminal / kill / clear / 快捷键体验；真正的
 * PTY 原始按键、交互式程序（vim/top）需要后端升级为 websocket PTY 后再接入。
 */
export function HumanTerminalPanel() {
  const [host, setHost] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState(false);
  const [sessions, setSessions] = useState<TerminalSession[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<Record<string, TerminalEvent[]>>({});
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);

  const userSessions = useMemo(() => sessions.filter((session) => session.run_id === null), [sessions]);
  const currentEvents = sessionId ? events[sessionId] ?? [] : [];
  const currentSession = userSessions.find((session) => session.id === sessionId) ?? null;
  const running = currentSession?.status === "active";

  const refreshSessions = useCallback(async () => {
    const list = await api.listTerminalSessions();
    const users = list.filter((session) => session.run_id === null);
    setSessions(users);
    setSessionId((current) => current && users.some((session) => session.id === current) ? current : users[0]?.id ?? null);
    return users;
  }, []);

  const createSession = useCallback(async () => {
    setError(null);
    try {
      const created = await api.createTerminalSession();
      setSessions((previous) => [created, ...previous]);
      setSessionId(created.id);
      setEvents((previous) => ({ ...previous, [created.id]: [] }));
    } catch (caught) {
      setError(errorText(caught));
    }
  }, []);

  useEffect(() => {
    const findHost = () => {
      const next = document.querySelector<HTMLElement>(".ide-bottom .bp-body");
      setHost(next);
    };
    findHost();
    const observer = new MutationObserver(findHost);
    observer.observe(document.body, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!host) return;
    const header = host.parentElement?.querySelector<HTMLElement>(".bp-head");
    if (!header) return;
    const open = () => {
      const firstTab = header.querySelector<HTMLElement>(".bp-tab");
      firstTab?.click();
      setActive(true);
    };
    const button = document.createElement("button");
    button.type = "button";
    button.className = "bp-tab human-terminal-trigger";
    button.textContent = "终端";
    button.addEventListener("click", open);
    header.insertBefore(button, header.querySelector(".bp-gap"));
    return () => button.remove();
  }, [host]);

  useEffect(() => {
    if (!host) return;
    const bottom = host.parentElement;
    bottom?.classList.toggle("has-human-terminal", active);
    return () => bottom?.classList.remove("has-human-terminal");
  }, [host, active]);

  useEffect(() => {
    if (!host || !active) return;
    void refreshSessions().then((list) => {
      if (list.length === 0) void createSession();
    }).catch((caught) => setError(errorText(caught)));
  }, [host, active, refreshSessions, createSession]);

  useEffect(() => {
    if (!active || !sessionId) return;
    setEvents((previous) => ({ ...previous, [sessionId]: [] }));
    const source = new EventSource(terminalStreamUrl(sessionId, 0));
    const onEvent = (raw: MessageEvent<string>) => {
      try {
        const event = JSON.parse(raw.data) as TerminalEvent;
        setEvents((previous) => ({
          ...previous,
          [sessionId]: mergeEvent(previous[sessionId] ?? [], event),
        }));
        if (event.kind === "terminal.session.closed") void refreshSessions();
      } catch {
        // Ignore malformed frames without breaking the stream.
      }
    };
    source.addEventListener("terminal.event", onEvent as EventListener);
    source.onerror = () => setError("终端连接中断，浏览器会自动重连");
    return () => {
      source.removeEventListener("terminal.event", onEvent as EventListener);
      source.close();
    };
  }, [active, sessionId, refreshSessions]);

  useEffect(() => {
    const body = bodyRef.current;
    if (!body || !stickRef.current) return;
    body.scrollTop = body.scrollHeight;
  }, [currentEvents]);

  const runCommand = async () => {
    const command = input.trim();
    if (!command || !sessionId || !running || busy) return;
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

  const kill = async () => {
    if (!sessionId || !running) return;
    try {
      await api.stopTerminalSession(sessionId, false);
      await refreshSessions();
    } catch (caught) {
      setError(errorText(caught));
    }
  };

  const clear = () => {
    if (!sessionId) return;
    setEvents((previous) => ({ ...previous, [sessionId]: [] }));
  };

  if (!host) return null;

  return createPortal(
    <div className="human-terminal">
      <div className="ht-toolbar">
        <div className="ht-tabs" role="tablist" aria-label="终端会话">
          {userSessions.map((session, index) => (
            <button
              type="button"
              role="tab"
              aria-selected={session.id === sessionId}
              key={session.id}
              className={`ht-tab${session.id === sessionId ? " is-active" : ""}`}
              onClick={() => setSessionId(session.id)}
              title={session.workspace_root}
            >
              <span className={`ht-dot${session.status === "active" ? " is-live" : ""}`} />
              {labelFor(session, index)}
              {session.status !== "active" ? <span className="ht-state">{session.status}</span> : null}
            </button>
          ))}
          <button type="button" className="ht-add" title="新建终端" onClick={() => void createSession()}>
            ＋
          </button>
        </div>
        <div className="ht-actions">
          <span className="ht-cwd" title={currentSession?.workspace_root ?? ""}>
            {currentSession?.workspace_root ?? "未连接"}
          </span>
          <button type="button" className="icon-btn sm" title="清空终端" onClick={clear} disabled={!sessionId}>⌫</button>
          <button type="button" className="icon-btn sm" title="终止当前终端" onClick={() => void kill()} disabled={!running}>■</button>
          <button type="button" className="icon-btn sm" title="关闭终端面板" onClick={() => setActive(false)}>×</button>
        </div>
      </div>

      <div
        className="ht-body"
        ref={bodyRef}
        onScroll={() => {
          const body = bodyRef.current;
          if (body) stickRef.current = body.scrollHeight - body.scrollTop - body.clientHeight < 40;
        }}
      >
        {currentEvents.length === 0 ? (
          <div className="ht-empty">在这里运行 Git、pnpm、npm、python、docker 等命令。</div>
        ) : (
          currentEvents.map((event) => {
            if (event.kind === "terminal.command.started") {
              return (
                <div className="ht-line ht-command" key={event.id}>
                  <span className="ht-prompt">$</span><span>{event.command}</span>
                </div>
              );
            }
            if (event.kind === "terminal.output") {
              return outputLines(event.chunk ?? "").map((line, index) => (
                <div className="ht-line ht-output" key={`${event.id}-${index}`}>{line || " "}</div>
              ));
            }
            if (event.kind === "terminal.command.finished" || event.kind === "terminal.command.failed") {
              const failed = event.kind === "terminal.command.failed" || event.exit_code !== 0;
              return (
                <div className={`ht-line ht-exit${failed ? " is-error" : ""}`} key={event.id}>
                  {failed ? "✕" : "✓"} exit code {event.exit_code ?? "unknown"}
                </div>
              );
            }
            return null;
          })
        )}
      </div>

      {error ? <div className="ht-error">{error}</div> : null}
      <form
        className="ht-input"
        onSubmit={(event) => {
          event.preventDefault();
          void runCommand();
        }}
      >
        <span className="ht-prompt">$</span>
        <input
          autoFocus
          value={input}
          disabled={!running || busy}
          placeholder={running ? "输入命令，Enter 执行" : "终端已停止，点击 ＋ 新建终端"}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setInput("");
          }}
        />
        <span className="ht-hint">Enter 执行 · Esc 清空</span>
      </form>
    </div>,
    host,
  );
}
