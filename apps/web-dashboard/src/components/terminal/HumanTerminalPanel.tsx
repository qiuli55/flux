import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";

import { API_BASE, api } from "../../api/client";
import type { TerminalSession } from "../../api/types";
import "../../styles/human-terminal.css";
import "../../styles/human-terminal-xterm.css";

interface SearchMatch { row: number; start: number; length: number }
interface SearchOptions { caseSensitive: boolean; wholeWord: boolean; regex: boolean }
type ConnectionState = "connected" | "reconnecting" | "disconnected";
type SplitDirection = "horizontal" | "vertical";
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

function errorText(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function labelFor(session: TerminalSession, index: number): string {
  const root = session.workspace_root.split(/[\\/]/).filter(Boolean).pop();
  return root ? `${root} ${index + 1}` : `Terminal ${index + 1}`;
}
function websocketUrl(sessionId: string): string {
  const url = new URL(`${API_BASE}/terminal/pty/sessions/${encodeURIComponent(sessionId)}/ws`, window.location.origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}
function escapeRegExp(value: string): string { return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }
function findMatches(terminal: Terminal, query: string, options: SearchOptions): SearchMatch[] {
  if (!query) return [];
  const matches: SearchMatch[] = [];
  const flags = options.caseSensitive ? "g" : "gi";
  const source = options.regex ? query : escapeRegExp(query);
  const expression = new RegExp(options.wholeWord ? `\\b(?:${source})\\b` : source, flags);
  const buffer = terminal.buffer.active;
  for (let row = 0; row < buffer.length; row += 1) {
    const line = buffer.getLine(row)?.translateToString(true) ?? "";
    expression.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = expression.exec(line)) !== null) {
      if (match[0].length === 0) { expression.lastIndex += 1; continue; }
      matches.push({ row, start: match.index, length: match[0].length });
    }
  }
  return matches;
}
async function createHumanSession(): Promise<TerminalSession> {
  const response = await fetch(`${API_BASE}/terminal/pty/sessions`, { method: "POST", headers: { Accept: "application/json" } });
  const envelope = (await response.json()) as { success: boolean; message?: string; data: TerminalSession };
  if (!response.ok || !envelope.success) throw new Error(envelope.message || `创建终端失败（HTTP ${response.status}）`);
  return envelope.data;
}

export function HumanTerminalPanel() {
  const [host, setHost] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState(false);
  const [sessions, setSessions] = useState<TerminalSession[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [connectionState, setConnectionState] = useState<Record<string, ConnectionState>>({});
  const [splitDirection, setSplitDirection] = useState<SplitDirection | null>(null);
  const [splitSessions, setSplitSessions] = useState<string[]>([]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchOptions, setSearchOptions] = useState<SearchOptions>({ caseSensitive: false, wholeWord: false, regex: false });
  const [searchMatches, setSearchMatches] = useState<SearchMatch[]>([]);
  const [searchIndex, setSearchIndex] = useState(-1);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; sessionId: string; hasSelection: boolean } | null>(null);
  const hostsRef = useRef<Record<string, HTMLDivElement | null>>({});
  const runtimesRef = useRef<Record<string, TerminalRuntime>>({});
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const contextMenuRef = useRef<HTMLDivElement | null>(null);

  const userSessions = useMemo(() => sessions.filter((session) => session.run_id === null), [sessions]);
  const currentSession = userSessions.find((session) => session.id === sessionId) ?? null;
  const currentRuntime = sessionId ? runtimesRef.current[sessionId] : undefined;
  const visibleSessionIds = useMemo(
    () => splitDirection && splitSessions.length >= 2 ? splitSessions : sessionId ? [sessionId] : [],
    [sessionId, splitDirection, splitSessions],
  );

  const refreshSessions = useCallback(async () => {
    const users = (await api.listTerminalSessions()).filter((session) => session.run_id === null);
    setSessions(users);
    setSessionId((current) => current && users.some((session) => session.id === current) ? current : users[0]?.id ?? null);
    setSplitSessions((current) => current.filter((id) => users.some((session) => session.id === id)));
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

  const sendInput = useCallback((id: string, data: string) => {
    const runtime = runtimesRef.current[id];
    const socket = runtime?.socket;
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "input", data }));
  }, []);

  const copySelection = useCallback(async (id: string) => {
    const runtime = runtimesRef.current[id];
    if (!runtime || !runtime.terminal.hasSelection()) return false;
    const selection = runtime.terminal.getSelection();
    if (!selection) return false;
    try {
      await navigator.clipboard.writeText(selection);
      runtime.terminal.clearSelection();
      return true;
    } catch {
      setError("无法访问系统剪贴板，请检查浏览器权限");
      return false;
    }
  }, []);

  const pasteClipboard = useCallback(async (id: string) => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) sendInput(id, text);
    } catch {
      setError("无法读取系统剪贴板，请检查浏览器权限");
    }
  }, [sendInput]);

  const connectRuntime = useCallback((session: TerminalSession, runtime: TerminalRuntime) => {
    if (runtime.manualClose || runtime.socket) return;
    const socket = new WebSocket(websocketUrl(session.id));
    runtime.socket = socket;
    socket.onopen = () => {
      setConnectionState((previous) => ({ ...previous, [session.id]: "connected" }));
      setError(null);
      try {
        runtime.fit.fit();
        socket.send(JSON.stringify({ type: "resize", cols: runtime.terminal.cols, rows: runtime.terminal.rows }));
      } catch { /* Socket may close between open and initial resize. */ }
    };
    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(String(event.data)) as { type: string; data?: string; message?: string };
        if (message.type === "output" && message.data) runtime.terminal.write(message.data);
        if (message.type === "error") setError(message.message || "终端通信失败");
        if (message.type === "closed") void refreshSessions();
      } catch { setError("终端返回了无法解析的数据"); }
    };
    socket.onerror = () => {
      setConnectionState((previous) => ({ ...previous, [session.id]: "reconnecting" }));
      setError("PTY WebSocket 连接失败，正在重连…");
    };
    socket.onclose = () => {
      if (runtime.socket !== socket) return;
      runtime.socket = null;
      setConnectionState((previous) => ({ ...previous, [session.id]: runtime.manualClose ? "disconnected" : "reconnecting" }));
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
      cursorBlink: true, cursorStyle: "block", fontFamily: "var(--mono)", fontSize: 13, lineHeight: 1.15, scrollback: 5000,
      theme: { background: "#080a10", foreground: "#d9deea", cursor: "#7c5cff", selectionBackground: "rgba(124,92,255,.35)" },
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    const runtime = {} as TerminalRuntime;
    runtime.terminal = terminal; runtime.fit = fit; runtime.socket = null; runtime.resizeObserver = null; runtime.reconnectTimer = null; runtime.manualClose = false;
    runtime.dataDisposable = terminal.onData((data) => sendInput(session.id, data));
    runtime.resizeDisposable = terminal.onResize(({ cols, rows }) => {
      const socket = runtime.socket;
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "resize", cols, rows }));
    });
    terminal.attachCustomKeyEventHandler((event) => {
      const isMac = navigator.platform.toLowerCase().includes("mac");
      const modifier = isMac ? event.metaKey : event.ctrlKey;
      if (event.type !== "keydown") return true;
      if (modifier && event.shiftKey && event.key.toLowerCase() === "c") {
        void copySelection(session.id);
        return false;
      }
      if (modifier && event.shiftKey && event.key.toLowerCase() === "v") {
        void pasteClipboard(session.id);
        return false;
      }
      if (modifier && event.key.toLowerCase() === "c" && terminal.hasSelection()) {
        void copySelection(session.id);
        return false;
      }
      if (modifier && event.key.toLowerCase() === "v") {
        void pasteClipboard(session.id);
        return false;
      }
      return true;
    });
    runtimesRef.current[session.id] = runtime;
    return runtime;
  }, [copySelection, pasteClipboard, sendInput]);

  const createSession = useCallback(async () => {
    setError(null);
    try {
      const created = await createHumanSession();
      setSessions((previous) => [created, ...previous]);
      setSessionId(created.id);
    } catch (caught) { setError(errorText(caught)); }
  }, []);

  const stopSession = useCallback(async (id: string) => {
    const runtime = runtimesRef.current[id];
    if (runtime?.socket?.readyState === WebSocket.OPEN) { runtime.socket.send(JSON.stringify({ type: "stop", force: false })); return; }
    destroyRuntime(id);
    await refreshSessions();
  }, [destroyRuntime, refreshSessions]);

  const selectSession = useCallback((id: string) => {
    if (splitDirection && splitSessions.length >= 2 && !splitSessions.includes(id)) {
      setSplitSessions((previous) => previous.map((paneId) => paneId === sessionId ? id : paneId));
    }
    setSessionId(id);
  }, [sessionId, splitDirection, splitSessions]);

  const splitTerminal = useCallback(async (direction: SplitDirection) => {
    if (!sessionId || splitDirection) return;
    setError(null);
    try {
      const created = await createHumanSession();
      setSessions((previous) => [...previous, created]);
      setSplitSessions([sessionId, created.id]);
      setSplitDirection(direction);
      setSessionId(created.id);
    } catch (caught) {
      setError(errorText(caught));
    }
  }, [sessionId, splitDirection]);

  const closeSplitPane = useCallback(async (id: string) => {
    if (!splitDirection || splitSessions.length < 2) return;
    const remaining = splitSessions.filter((paneId) => paneId !== id);
    await stopSession(id);
    setSplitSessions([]);
    setSplitDirection(null);
    setSessionId(remaining[0] ?? null);
  }, [splitDirection, splitSessions, stopSession]);

  const closeSplit = useCallback(() => {
    if (!splitDirection) return;
    setSplitSessions([]);
    setSplitDirection(null);
  }, [splitDirection]);

  const closeSearch = useCallback(() => {
    setSearchOpen(false); setSearchMatches([]); setSearchIndex(-1); setSearchError(null);
    currentRuntime?.terminal.clearSelection();
    currentRuntime?.terminal.focus();
  }, [currentRuntime]);

  const openSearch = useCallback(() => {
    const runtime = sessionId ? runtimesRef.current[sessionId] : undefined;
    setSearchOpen(true);
    if (runtime) {
      const selection = runtime.terminal.getSelection();
      if (selection && !searchQuery) setSearchQuery(selection);
    }
    window.setTimeout(() => searchInputRef.current?.focus(), 0);
  }, [searchQuery, sessionId]);

  const runSearch = useCallback((direction: 1 | -1) => {
    const runtime = sessionId ? runtimesRef.current[sessionId] : undefined;
    if (!runtime || !searchQuery) return;
    let matches: SearchMatch[];
    try { matches = findMatches(runtime.terminal, searchQuery, searchOptions); }
    catch { setSearchError("无效的正则表达式"); setSearchMatches([]); setSearchIndex(-1); return; }
    setSearchError(null); setSearchMatches(matches);
    if (!matches.length) { setSearchIndex(-1); runtime.terminal.clearSelection(); return; }
    const next = searchIndex < 0 ? (direction > 0 ? 0 : matches.length - 1) : (searchIndex + direction + matches.length) % matches.length;
    const match = matches[next];
    if (!match) return;
    runtime.terminal.select(match.start, match.row, match.length);
    runtime.terminal.scrollToLine(Math.max(0, match.row - Math.floor(runtime.terminal.rows / 2)));
    setSearchIndex(next);
  }, [searchIndex, searchOptions, searchQuery, sessionId]);

  const openContextMenu = useCallback((event: React.MouseEvent, targetSessionId: string) => {
    event.preventDefault();
    const runtime = runtimesRef.current[targetSessionId];
    if (!runtime) return;
    const menuWidth = 190;
    const menuHeight = 148;
    setSessionId(targetSessionId);
    setContextMenu({
      x: Math.min(event.clientX, window.innerWidth - menuWidth - 8),
      y: Math.min(event.clientY, window.innerHeight - menuHeight - 8),
      sessionId: targetSessionId,
      hasSelection: runtime.terminal.hasSelection(),
    });
    runtime.terminal.focus();
  }, []);

  const closeContextMenu = useCallback(() => setContextMenu(null), []);

  useEffect(() => {
    if (!contextMenu) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!contextMenuRef.current?.contains(event.target as Node)) setContextMenu(null);
    };
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === "Escape") setContextMenu(null); };
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    return () => { window.removeEventListener("pointerdown", onPointerDown); window.removeEventListener("keydown", onKeyDown); };
  }, [contextMenu]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!active) return;
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "f") { event.preventDefault(); openSearch(); return; }
      if (!searchOpen) return;
      if (event.key === "Escape") { event.preventDefault(); closeSearch(); }
      else if (event.key === "Enter") { event.preventDefault(); runSearch(event.shiftKey ? -1 : 1); }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [active, closeSearch, openSearch, runSearch, searchOpen]);

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
    const button = document.createElement("button");
    const open = () => { setActive(true); button.classList.add("is-active"); };
    const closeFromSibling = () => { setActive(false); button.classList.remove("is-active"); setSearchOpen(false); setContextMenu(null); };
    button.type = "button"; button.className = "bp-tab human-terminal-trigger"; button.textContent = "终端"; button.addEventListener("click", open);
    const siblingTabs = Array.from(header.querySelectorAll<HTMLElement>(".bp-tab:not(.human-terminal-trigger)"));
    siblingTabs.forEach((tab) => tab.addEventListener("click", closeFromSibling));
    header.insertBefore(button, header.querySelector(".bp-gap"));
    return () => { button.removeEventListener("click", open); siblingTabs.forEach((tab) => tab.removeEventListener("click", closeFromSibling)); button.remove(); };
  }, [host]);

  useEffect(() => {
    if (!host) return;
    host.parentElement?.classList.toggle("has-human-terminal", active);
    return () => host.parentElement?.classList.remove("has-human-terminal");
  }, [host, active]);

  useEffect(() => {
    if (!host || !active) return;
    void refreshSessions().then((list) => { if (!list.length) void createSession(); }).catch((caught) => setError(errorText(caught)));
  }, [host, active, refreshSessions, createSession]);

  useEffect(() => {
    if (!active || !visibleSessionIds.length) return;
    const observedIds: string[] = [];
    visibleSessionIds.forEach((id) => {
      const session = userSessions.find((item) => item.id === id);
      if (!session) return;
      const runtime = ensureRuntime(session);
      const element = hostsRef.current[id];
      if (!element) return;
      if (!runtime.terminal.element) runtime.terminal.open(element);
      try { runtime.fit.fit(); } catch { /* pane may still be hidden during layout transition */ }
      runtime.resizeObserver?.disconnect();
      runtime.resizeObserver = new ResizeObserver(() => { try { runtime.fit.fit(); } catch { /* pane temporarily hidden */ } });
      runtime.resizeObserver.observe(element);
      connectRuntime(session, runtime);
      observedIds.push(id);
    });
    const activeRuntime = sessionId ? runtimesRef.current[sessionId] : undefined;
    activeRuntime?.terminal.focus();
    return () => observedIds.forEach((id) => runtimesRef.current[id]?.resizeObserver?.disconnect());
  }, [active, sessionId, userSessions, visibleSessionIds, ensureRuntime, connectRuntime]);

  useEffect(() => {
    if (!searchOpen) return;
    setSearchIndex(-1); setSearchMatches([]); setSearchError(null);
    window.setTimeout(() => searchInputRef.current?.focus(), 0);
  }, [searchOpen, sessionId]);

  useEffect(() => {
    if (!searchOpen) return;
    const timer = window.setTimeout(() => runSearch(1), 80);
    return () => window.clearTimeout(timer);
  }, [searchOpen, searchOptions, searchQuery]);

  useEffect(() => () => { Object.keys(runtimesRef.current).forEach(destroyRuntime); }, [destroyRuntime]);

  if (!host) return null;
  return createPortal(
    <div className={`human-terminal${active ? " is-active" : ""}`}>
      <div className="ht-toolbar">
        <div className="ht-tabs" role="tablist" aria-label="终端会话">
          {userSessions.map((session, index) => (
            <button type="button" role="tab" aria-selected={session.id === sessionId} key={session.id} className={`ht-tab${session.id === sessionId ? " is-active" : ""}`} onClick={() => setSessionId(session.id)} title={session.workspace_root}>
              <span className={`ht-dot${connectionState[session.id] === "connected" ? " is-live" : ""}`} />{labelFor(session, index)}
            </button>
          ))}
          <button type="button" className="ht-add" onClick={() => void createSession()} aria-label="新建终端">＋</button>
        </div>
        <div className="ht-actions">
          <span className="ht-cwd" title={currentSession?.workspace_root}>{currentSession?.workspace_root ?? ""}</span>
          <button type="button" className={`ht-tool${searchOpen ? " is-active" : ""}`} onClick={openSearch} title="搜索终端 (Ctrl/Cmd+F)">搜索</button>
          <button type="button" className="ht-tool" onClick={() => sessionId && runtimesRef.current[sessionId]?.terminal.clear()} title="清空">清空</button>
          <button type="button" className="ht-tool" onClick={() => sessionId && void stopSession(sessionId)} title="停止终端">停止</button>
          <button type="button" className={`ht-tool${splitDirection === "horizontal" ? " is-active" : ""}`} onClick={() => void splitTerminal("horizontal")} disabled={!sessionId || !!splitDirection} title="上下分屏">上下分屏</button>
          <button type="button" className={`ht-tool${splitDirection === "vertical" ? " is-active" : ""}`} onClick={() => void splitTerminal("vertical")} disabled={!sessionId || !!splitDirection} title="左右分屏">左右分屏</button>
          {splitDirection ? <button type="button" className="ht-tool" onClick={closeSplit} title="取消分屏">取消分屏</button> : null}
        </div>
      </div>
      {searchOpen ? (
        <div className="ht-search" role="search">
          <input ref={searchInputRef} value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); runSearch(event.shiftKey ? -1 : 1); } else if (event.key === "Escape") { event.preventDefault(); closeSearch(); } }} placeholder="搜索终端输出…" aria-label="搜索终端输出" />
          <button type="button" className={searchOptions.caseSensitive ? "is-active" : ""} onClick={() => setSearchOptions((value) => ({ ...value, caseSensitive: !value.caseSensitive }))} title="区分大小写">Aa</button>
          <button type="button" className={searchOptions.wholeWord ? "is-active" : ""} onClick={() => setSearchOptions((value) => ({ ...value, wholeWord: !value.wholeWord }))} title="全词匹配">ab</button>
          <button type="button" className={searchOptions.regex ? "is-active" : ""} onClick={() => setSearchOptions((value) => ({ ...value, regex: !value.regex }))} title="正则表达式">.*</button>
          <span className={`ht-search-count${searchError ? " is-error" : ""}`}>{searchError ?? (searchQuery ? (searchMatches.length ? `${searchIndex + 1}/${searchMatches.length}` : "无匹配") : "")}</span>
          <button type="button" onClick={() => runSearch(-1)} disabled={!searchQuery} title="上一个">↑</button>
          <button type="button" onClick={() => runSearch(1)} disabled={!searchQuery} title="下一个">↓</button>
          <button type="button" onClick={closeSearch} title="关闭搜索">×</button>
        </div>
      ) : null}
      <div className={`ht-body${splitDirection ? ` ht-split-${splitDirection}` : ""}`} role="tabpanel">
        {userSessions.map((session) => {
          const visible = visibleSessionIds.includes(session.id);
          const isFocused = session.id === sessionId;
          return (
            <div
              key={session.id}
              className={`ht-pane${visible ? " is-visible" : ""}${isFocused ? " is-focused" : ""}`}
              onMouseDown={() => selectSession(session.id)}
              onContextMenu={(event) => openContextMenu(event, session.id)}
              aria-hidden={!visible}
            >
              {splitDirection && visible ? (
                <div className="ht-pane-toolbar">
                  <span>{labelFor(session, userSessions.findIndex((item) => item.id === session.id))}</span>
                  <button type="button" onMouseDown={(event) => event.stopPropagation()} onClick={() => void closeSplitPane(session.id)} aria-label="关闭分屏">×</button>
                </div>
              ) : null}
              <div ref={(element) => { hostsRef.current[session.id] = element; }} className="ht-xterm-host" />
            </div>
          );
        })}
      </div>
      {contextMenu ? (
        <div ref={contextMenuRef} className="ht-context-menu" style={{ left: contextMenu.x, top: contextMenu.y }} role="menu">
          <button type="button" role="menuitem" disabled={!contextMenu.hasSelection} onClick={() => { void copySelection(contextMenu.sessionId); closeContextMenu(); }}>复制</button>
          <button type="button" role="menuitem" onClick={() => { void pasteClipboard(contextMenu.sessionId); closeContextMenu(); }}>粘贴</button>
          <div className="ht-context-separator" />
          <button type="button" role="menuitem" onClick={() => { runtimesRef.current[contextMenu.sessionId]?.terminal.selectAll(); closeContextMenu(); }}>全选</button>
          <button type="button" role="menuitem" onClick={() => { runtimesRef.current[contextMenu.sessionId]?.terminal.clearSelection(); closeContextMenu(); }}>取消选择</button>
        </div>
      ) : null}
      {error ? <div className="ht-error">{error}</div> : null}
      <div className="ht-statusbar">
        <span>{connectionState[sessionId ?? ""] === "connected" ? "● 已连接" : connectionState[sessionId ?? ""] === "reconnecting" ? "◌ 正在重连…" : "○ 未连接"}</span>
        <span>{currentSession?.workspace_root ?? ""}</span>
        <span>{splitDirection ? splitSessions.length + " 个终端分屏" : "PTY · WebSocket"}</span>
      </div>
    </div>,
    host,
  );
}
