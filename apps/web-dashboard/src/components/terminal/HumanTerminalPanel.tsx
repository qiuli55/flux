import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { API_BASE, api } from "../../api/client";
import type { TerminalSession } from "../../api/types";

const SCROLLBACK_LIMIT = 256 * 1024;

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function labelFor(session: TerminalSession, index: number): string {
  const root = session.workspace_root.split("/").filter(Boolean).pop();
  return root ? `${root} ${index + 1}` : `Terminal ${index + 1}`;
}

function websocketUrl(sessionId: string): string {
  const url = new URL(
    `${API_BASE}/terminal/pty/sessions/${encodeURIComponent(sessionId)}/ws`,
    window.location.origin,
  );
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function controlSequence(event: KeyboardEvent): string | null {
  if (event.ctrlKey && event.key.length === 1) {
    const code = event.key.toLowerCase().charCodeAt(0);
    if (code >= 97 && code <= 122) return String.fromCharCode(code - 96);
  }
  switch (event.key) {
    case "Enter": return "\r";
    case "Backspace": return "\x7f";
    case "Tab": return "\t";
    case "Escape": return "\x1b";
    case "ArrowUp": return "\x1b[A";
    case "ArrowDown": return "\x1b[B";
    case "ArrowRight": return "\x1b[C";
    case "ArrowLeft": return "\x1b[D";
    case "Home": return "\x1b[H";
    case "End": return "\x1b[F";
    case "Delete": return "\x1b[3~";
    case "PageUp": return "\x1b[5~";
    case "PageDown": return "\x1b[6~";
    case "Insert": return "\x1b[2~";
    case "F1": return "\x1bOP";
    case "F2": return "\x1bOQ";
    case "F3": return "\x1bOR";
    case "F4": return "\x1bOS";
    case "F5": return "\x1b[15~";
    case "F6": return "\x1b[17~";
    case "F7": return "\x1b[18~";
    case "F8": return "\x1b[19~";
    case "F9": return "\x1b[20~";
    case "F10": return "\x1b[21~";
    case "F11": return "\x1b[23~";
    case "F12": return "\x1b[24~";
    default: return event.key.length === 1 ? event.key : null;
  }
}

async function createHumanSession(): Promise<TerminalSession> {
  const response = await fetch(`${API_BASE}/terminal/pty/sessions`, { method: "POST" });
  const envelope = (await response.json()) as { success: boolean; message?: string; data: TerminalSession };
  if (!response.ok || !envelope.success) {
    throw new Error(envelope.message || `创建终端失败（HTTP ${response.status}）`);
  }
  return envelope.data;
}

export function HumanTerminalPanel() {
  const [host, setHost] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState(false);
  const [sessions, setSessions] = useState<TerminalSession[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [output, setOutput] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const screenRef = useRef<HTMLDivElement>(null);
  const resizeRef = useRef<ResizeObserver | null>(null);

  const userSessions = useMemo(
    () => sessions.filter((session) => session.run_id === null),
    [sessions],
  );
  const currentSession = userSessions.find((session) => session.id === sessionId) ?? null;
  const running = currentSession?.status === "active";

  const refreshSessions = useCallback(async () => {
    const list = await api.listTerminalSessions();
    const users = list.filter((session) => session.run_id === null);
    setSessions(users);
    setSessionId((current) =>
      current && users.some((session) => session.id === current)
        ? current
        : users[0]?.id ?? null,
    );
    return users;
  }, []);

  const createSession = useCallback(async () => {
    setError(null);
    try {
      const created = await createHumanSession();
      setSessions((previous) => [created, ...previous]);
      setSessionId(created.id);
      setOutput((previous) => ({ ...previous, [created.id]: "" }));
    } catch (caught) {
      setError(errorText(caught));
    }
  }, []);

  useEffect(() => {
    const findHost = () => {
      setHost(document.querySelector<HTMLElement>(".ide-bottom .bp-body"));
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
      header.querySelector<HTMLElement>(".bp-tab")?.click();
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
    host.parentElement?.classList.toggle("has-human-terminal", active);
    return () => host.parentElement?.classList.remove("has-human-terminal");
  }, [host, active]);

  useEffect(() => {
    if (!host || !active) return;
    void refreshSessions().then((list) => {
      if (list.length === 0) void createSession();
    }).catch((caught) => setError(errorText(caught)));
  }, [host, active, refreshSessions, createSession]);

  useEffect(() => {
    if (!active || !sessionId) return;
    const socket = new WebSocket(websocketUrl(sessionId));
    socketRef.current = socket;
    setError(null);

    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(String(event.data)) as { type: string; data?: string; message?: string };
        if (message.type === "output" && message.data) {
          setOutput((previous) => {
            const next = `${previous[sessionId] ?? ""}${message.data}`;
            return { ...previous, [sessionId]: next.slice(-SCROLLBACK_LIMIT) };
          });
        } else if (message.type === "error") {
          setError(message.message || "终端通信失败");
        } else if (message.type === "closed") {
          void refreshSessions();
        }
      } catch {
        setError("终端返回了无法解析的数据");
      }
    };
    socket.onerror = () => setError("PTY WebSocket 连接失败");
    socket.onclose = () => {
      if (socketRef.current === socket) socketRef.current = null;
    };

    return () => {
      if (socketRef.current === socket) socketRef.current = null;
      socket.close();
    };
  }, [active, sessionId, refreshSessions]);

  useEffect(() => {
    const screen = screenRef.current;
    if (!screen) return;
    screen.scrollTop = screen.scrollHeight;
  }, [sessionId, output]);

  useEffect(() => {
    const screen = screenRef.current;
    if (!screen || !sessionId) return;
    const resize = () => {
      const socket = socketRef.current;
      if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const cols = Math.max(20, Math.min(240, Math.floor(screen.clientWidth / 8)));
      const rows = Math.max(5, Math.min(100, Math.floor(screen.clientHeight / 18)));
      socket.send(JSON.stringify({ type: "resize", cols, rows }));
    };
    resizeRef.current?.disconnect();
    resizeRef.current = new ResizeObserver(resize);
    resizeRef.current.observe(screen);
    resize();
    return () => resizeRef.current?.disconnect();
  }, [sessionId, active]);

  const sendKey = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (!running) return;
    const data = controlSequence(event.nativeEvent);
    if (!data || event.metaKey) return;
    event.preventDefault();
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "input", data }));
  };

  const stop = () => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "stop", force: false }));
    }
  };

  const clear = () => {
    if (!sessionId) return;
    setOutput((previous) => ({ ...previous, [sessionId]: "" }));
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
          <button type="button" className="ht-add" title="新建终端" onClick={() => void createSession()}>＋</button>
        </div>
        <div className="ht-actions">
          <span className="ht-cwd" title={currentSession?.workspace_root ?? ""}>
            {currentSession?.workspace_root ?? "未连接"}
          </span>
          <button type="button" className="icon-btn sm" title="清空终端显示" onClick={clear} disabled={!sessionId}>⌫</button>
          <button type="button" className="icon-btn sm" title="终止当前终端" onClick={stop} disabled={!running}>■</button>
          <button type="button" className="icon-btn sm" title="关闭终端面板" onClick={() => setActive(false)}>×</button>
        </div>
      </div>

      <div
        className="ht-body ht-pty-screen"
        ref={screenRef}
        tabIndex={0}
        onKeyDown={sendKey}
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) event.currentTarget.focus();
        }}
        role="textbox"
        aria-label="Flux Human Terminal"
      >
        <pre>{output[sessionId ?? ""] || (running ? "" : "终端已停止，点击 ＋ 新建终端")}</pre>
      </div>

      {error ? <div className="ht-error">{error}</div> : null}
      <div className="ht-input ht-pty-hint">
        <span className="ht-prompt">›</span>
        <span>点击终端后直接输入。Ctrl+C / Ctrl+D / Tab / 方向键会原样发送到 PTY。</span>
        <span className="ht-hint">PTY · WebSocket</span>
      </div>
    </div>,
    host,
  );
}
