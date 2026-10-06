import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";

import { API_BASE, api } from "../../api/client";
import type { TerminalSession } from "../../api/types";
import "../../styles/human-terminal.css";
import "../../styles/human-terminal-xterm.css";

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function labelFor(session: TerminalSession, index: number): string {
  const root = session.workspace_root.split(/[\\/]/).filter(Boolean).pop();
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

async function createHumanSession(): Promise<TerminalSession> {
  const response = await fetch(`${API_BASE}/terminal/pty/sessions`, {
    method: "POST",
    headers: { Accept: "application/json" },
  });
  const envelope = (await response.json()) as {
    success: boolean;
    message?: string;
    data: TerminalSession;
  };
  if (!response.ok || !envelope.success) {
    throw new Error(envelope.message || `创建终端失败（HTTP ${response.status}）`);
  }
  return envelope.data;
}

interface TerminalRuntime {
  terminal: Terminal;
  fit: FitAddon;
  socket: WebSocket | null;
  resizeObserver: ResizeObserver | null;
  dataDisposable: { dispose: () => void };
  resizeDisposable: { dispose: () => void };
  reconnectTimer: number | null;
  manualClose: boolean;
}

export function HumanTerminalPanel() {
  const [host, setHost] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState(false);
  const [sessions, setSessions] = useState<TerminalSession[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState<Record<string, boolean>>({});
  const hostsRef = useRef<Record<string, HTMLDivElement | null>>({});
  const runtimesRef = useRef<Record<string, TerminalRuntime>>({});

  const userSessions = useMemo(() => sessions.filter((session) => session.run_id === null), [sessions]);
  const currentSession = userSessions.find((session) => session.id === sessionId) ?? null;

  const refreshSessions = useCallback(async () => {
    const list = await api.listTerminalSessions();
    const users = list.filter((session) => session.run_id === null);
    setSessions(users);
    setSessionId((current) =>
      current && users.some((session) => session.id === current) ? current : users[0]?.id ?? null,
    );
    return users;
  }, []);

  const destroyRuntime = useCallback((id: string) => {
    const runtime = runtimesRef.current[id];
    if (!runtime) return;
    runtime.manualClose = true;
    if (runtime.reconnectTimer !== null) window.clearTimeout(runtime.reconnectTimer);
    runtime.resizeObserver?.disconnect();
    runtime.dataDisposable.dispose();
    runtime.resizeDisposable.dispose();
    runtime.socket?.close();
    runtime.terminal.dispose();
    delete runtimesRef.current[id];
  }, []);

  const connectRuntime = useCallback((session: TerminalSession, runtime: TerminalRuntime) => {
    if (runtime.manualClose || runtime.socket) return;
    const socket = new WebSocket(websocketUrl(session.id));
    runtime.socket = socket;

    socket.onopen = () => {
      setConnected((previous) => ({ ...previous, [session.id]: true }));
      try {
        runtime.fit.fit();
        socket.send(JSON.stringify({ type: "resize", cols: runtime.terminal.cols, rows: runtime.terminal.rows }));
      } catch {
        // Socket may close between open and the initial resize.
      }
    };

    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(String(event.data)) as { type: string; data?: string; message?: string };
        if (message.type === "output" && message.data) runtime.terminal.write(message.data);
        if (message.type === "error") setError(message.message || "终端通信失败");
        if (message.type === "closed") void refreshSessions();
      } catch {
        setError("终端返回了无法解析的数据");
      }
    };

    socket.onerror = () => {
      setConnected((previous) => ({ ...previous, [session.id]: false }));
      setError("PTY WebSocket 连接失败");
    };

    socket.onclose = () => {
      if (runtime.socket !== socket) return;
      runtime.socket = null;
      setConnected((previous) => ({ ...previous, [session.id]: false }));
      if (!runtime.manualClose && runtime.reconnectTimer === null) {
        runtime.reconnectTimer = window.setTimeout(() => {
          runtime.reconnectTimer = null;
          connectRuntime(session, runtime);
        }, 1000);
      }
    };
  }, [refreshSessions]);

  const ensureRuntime = useCallback((session: TerminalSession): TerminalRuntime => {
    const existing = runtimesRef.current[session.id];
    if (existing) return existing;

    const terminal = new Terminal({
      cursorBlink: true,
      cursorStyle: "block",
      fontFamily: "var(--mono)",
      fontSize: 13,
      lineHeight: 1.15,
      scrollback: 5000,
      theme: {
        background: "#080a10",
        foreground: "#d9deea",
        cursor: "#7c5cff",
        selectionBackground: "rgba(124,92,255,.35)",
      },
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    const runtime = {} as TerminalRuntime;
    runtime.terminal = terminal;
    runtime.fit = fit;
    runtime.socket = null;
    runtime.resizeObserver = null;
    runtime.reconnectTimer = null;
    runtime.manualClose = false;
    runtime.dataDisposable = terminal.onData((data) => {
      const socket = runtime.socket;
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "input", data }));
    });
    runtime.resizeDisposable = terminal.onResize(({ cols, rows }) => {
      const socket = runtime.socket;
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "resize", cols, rows }));
    });
    runtimesRef.current[session.id] = runtime;
    return runtime;
  }, []);

  const createSession = useCallback(async () => {
    setError(null);
    try {
      const created = await createHumanSession();
      setSessions((previous) => [created, ...previous]);
      setSessionId(created.id);
    } catch (caught) {
      setError(errorText(caught));
    }
  }, []);

  const stopSession = useCallback(async (id: string) => {
    const runtime = runtimesRef.current[id];
    if (runtime?.socket?.readyState === WebSocket.OPEN) {
      runtime.socket.send(JSON.stringify({ type: "stop", force: false }));
      return;
    }
    destroyRuntime(id);
    await refreshSessions();
  }, [destroyRuntime, refreshSessions]);

  useEffect(() => {
    const findHost = () => setHost(document.querySelector<HTMLElement>(".ide-bottom .bp-body"));
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
      setActive(true);
      button.classList.add("is-active");
    };
    const closeFromSibling = () => {
      setActive(false);
      button.classList.remove("is-active");
    };
    const button = document.createElement("button");
    button.type = "button";
    button.className = "bp-tab human-terminal-trigger";
    button.textContent = "终端";
    button.addEventListener("click", open);

    const siblingTabs = Array.from(header.querySelectorAll<HTMLElement>(".bp-tab:not(.human-terminal-trigger)"));
    siblingTabs.forEach((tab) => tab.addEventListener("click", closeFromSibling));
    header.insertBefore(button, header.querySelector(".bp-gap"));

    return () => {
      button.removeEventListener("click", open);
      siblingTabs.forEach((tab) => tab.removeEventListener("click", closeFromSibling));
      button.remove();
    };
  }, [host]);

  useEffect(() => {
    if (!host) return;
    host.parentElement?.classList.toggle("has-human-terminal", active);
    return () => host.parentElement?.classList.remove("has-human-terminal");
  }, [host, active]);

  useEffect(() => {
    if (!host || !active) return;
    void refreshSessions()
      .then((list) => {
        if (list.length === 0) void createSession();
      })
      .catch((caught) => setError(errorText(caught)));
  }, [host, active, refreshSessions, createSession]);

  useEffect(() => {
    if (!active || !sessionId) return;
    const session = userSessions.find((item) => item.id === sessionId);
    if (!session) return;
    const runtime = ensureRuntime(session);
    const element = hostsRef.current[session.id];
    if (!element) return;

    if (!element.contains(runtime.terminal.element)) runtime.terminal.open(element);
    runtime.fit.fit();
    runtime.resizeObserver?.disconnect();
    runtime.resizeObserver = new ResizeObserver(() => {
      try {
        runtime.fit.fit();
      } catch {
        // Element may be temporarily hidden while the bottom panel changes tabs.
      }
    });
    runtime.resizeObserver.observe(element);
    connectRuntime(session, runtime);
    runtime.terminal.focus();

    return () => runtime.resizeObserver?.disconnect();
  }, [active, sessionId, userSessions, ensureRuntime, connectRuntime]);

  useEffect(() => () => {
    Object.keys(runtimesRef.current).forEach(destroyRuntime);
  }, [destroyRuntime]);

  if (!host) return null;

  return createPortal(
    <div className={`human-terminal${active ? " is-active" : ""}`}>
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
              <span className={`ht-dot${connected[session.id] ? " is-live" : ""}`} />
              {labelFor(session, index)}
            </button>
          ))}
          <button type="button" className="ht-add" onClick={() => void createSession()} aria-label="新建终端">
            ＋
          </button>
        </div>
        <div className="ht-actions">
          <span className="ht-cwd" title={currentSession?.workspace_root}>{currentSession?.workspace_root ?? ""}</span>
          <button type="button" className="ht-tool" onClick={() => sessionId && runtimesRef.current[sessionId]?.terminal.clear()} title="清空">清空</button>
          <button type="button" className="ht-tool" onClick={() => sessionId && void stopSession(sessionId)} title="停止终端">停止</button>
        </div>
      </div>
      <div className="ht-body" role="tabpanel">
        {userSessions.map((session) => (
          <div
            key={session.id}
            ref={(element) => { hostsRef.current[session.id] = element; }}
            className={`ht-xterm-host${session.id === sessionId ? " is-active" : ""}`}
            aria-hidden={session.id !== sessionId}
          />
        ))}
      </div>
      {error ? <div className="ht-error">{error}</div> : null}
      <div className="ht-statusbar">
        <span>{connected[sessionId ?? ""] ? "● 已连接" : "○ 连接中…"}</span>
        <span>{currentSession?.workspace_root ?? ""}</span>
        <span>PTY · WebSocket</span>
      </div>
    </div>,
    host,
  );
}
