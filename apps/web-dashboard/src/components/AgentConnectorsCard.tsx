/**
 * 「连接器」里的 Cursor / 谷歌 Antigravity 官方账号入口。
 *
 * 复用手机端同一套后端 API：GET /mcp-connectors 给出连接器目录，
 * POST /{cursor|antigravity}-account/login 在用户电脑上拉起官方 CLI 浏览器授权，
 * 客户端只负责打开授权页与轮询结果。这里不粘贴 API key / token，也不嵌官方网页 IDE。
 */
import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "../api/client";
import type { AgentCliLoginSession, McpConnector } from "../api/types";
import { toast } from "../app/toast";

type AgentKind = "cursor" | "antigravity";

const KIND_TITLES: Record<AgentKind, string> = {
  cursor: "Cursor",
  antigravity: "谷歌 / Antigravity",
};

const CONNECT_LABELS: Record<AgentKind, string> = {
  cursor: "连接 Cursor",
  antigravity: "连接谷歌 / Antigravity",
};

function isAgentKind(value: string): value is AgentKind {
  return value === "cursor" || value === "antigravity";
}

function statusLabel(status: string): string {
  if (status === "connected") return "已连接";
  if (status === "needs_reauth") return "需要重新连接";
  if (status === "error") return "连接异常";
  return "未连接";
}

export function AgentConnectorsCard({ onChanged }: { onChanged: () => void }) {
  const [items, setItems] = useState<McpConnector[]>([]);
  const [sessions, setSessions] = useState<Partial<Record<AgentKind, AgentCliLoginSession>>>({});
  const [busy, setBusy] = useState<AgentKind | null>(null);
  const [codes, setCodes] = useState<Partial<Record<AgentKind, string>>>({});
  const [error, setError] = useState<string | null>(null);
  const timers = useRef<Partial<Record<AgentKind, number>>>({});

  const load = useCallback(async () => {
    try {
      const page = await api.listMcpConnectors();
      setItems(page.items.filter((item) => isAgentKind(item.id)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "读取连接器失败");
    }
  }, []);

  useEffect(() => {
    void load();
    return () => {
      Object.values(timers.current).forEach((timer) => window.clearInterval(timer));
    };
  }, [load]);

  const stopPolling = (kind: AgentKind) => {
    const timer = timers.current[kind];
    if (timer !== undefined) window.clearInterval(timer);
    delete timers.current[kind];
  };

  const poll = (kind: AgentKind, loginId: string, attempt = 0) => {
    stopPolling(kind);
    timers.current[kind] = window.setInterval(async () => {
      if (attempt > 600) {
        stopPolling(kind);
        return;
      }
      attempt += 1;
      try {
        const next = await api.agentLoginState(kind, loginId);
        setSessions((current) => ({ ...current, [kind]: next }));
        if (next.status === "success") {
          stopPolling(kind);
          toast(`${KIND_TITLES[kind]} 已连接，可在 Agent 列表中选择`);
          await load();
          onChanged();
        } else if (next.status !== "starting" && next.status !== "pending") {
          stopPolling(kind);
          if (next.message) toast(next.message);
        }
      } catch {
        stopPolling(kind);
      }
    }, 2000);
  };

  const start = async (kind: AgentKind) => {
    setBusy(kind);
    setError(null);
    try {
      const session = await api.startAgentLogin(kind);
      setSessions((current) => ({ ...current, [kind]: session }));
      if (session.status === "starting" || session.status === "pending") {
        poll(kind, session.login_id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "启动官方授权失败");
    } finally {
      setBusy(null);
    }
  };

  const submitCode = async (kind: AgentKind, loginId: string) => {
    const code = (codes[kind] ?? "").trim();
    if (!code) {
      setError("请先填写官方页面给出的授权码");
      return;
    }
    setBusy(kind);
    setError(null);
    try {
      const session = await api.submitAgentLoginCode(kind, loginId, code);
      setSessions((current) => ({ ...current, [kind]: session }));
      setCodes((current) => ({ ...current, [kind]: "" }));
      if (session.status === "starting" || session.status === "pending") {
        poll(kind, session.login_id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "提交授权码失败");
    } finally {
      setBusy(null);
    }
  };

  const logout = async (kind: AgentKind) => {
    setBusy(kind);
    setError(null);
    try {
      await api.logoutAgentAccount(kind);
      setSessions((current) => ({ ...current, [kind]: undefined }));
      stopPolling(kind);
      await load();
      onChanged();
      toast(`已退出 ${KIND_TITLES[kind]} 账号`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "退出账号失败");
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="card side-card">
      <div className="side-head">
        <b>Agent 连接器</b>
        <button type="button" className="link" onClick={() => void load()}>
          刷新
        </button>
      </div>
      <p className="ext-agent-note">
        用你自己的账号连接 Cursor / 谷歌 Antigravity。授权在电脑上的官方 CLI 完成，
        Flux 只下发任务并接收结果。
      </p>
      {error ? <div className="empty-note">{error}</div> : null}
      <ul className="side-list">
        {items.map((item) => {
          const kind = item.id as AgentKind;
          const session = sessions[kind];
          const pending =
            session !== undefined &&
            (session.status === "starting" || session.status === "pending");
          const connected = item.status === "connected";
          return (
            <li key={item.id}>
              <div className="side-item">
                <span className="tm-ava a3">{KIND_TITLES[kind].slice(0, 2)}</span>
                <div className="si-main">
                  <b>{KIND_TITLES[kind]}</b>
                  <i>
                    {statusLabel(item.status)}
                    {item.error ? ` · ${item.error}` : ""}
                  </i>
                  {session?.url ? (
                    <a
                      className="btn btn-xs"
                      href={session.url}
                      target="_blank"
                      rel="noreferrer"
                    >
                      打开官方授权页
                    </a>
                  ) : null}
                  {session?.message ? (
                    <i style={{ color: session.status === "failed" ? "#e05a4f" : undefined }}>
                      {session.message}
                    </i>
                  ) : null}
                  {session?.needs_code && pending ? (
                    <div className="ext-agent-actions">
                      <input
                        value={codes[kind] ?? ""}
                        onChange={(event) =>
                          setCodes((current) => ({ ...current, [kind]: event.target.value }))
                        }
                        placeholder="官方页面给出的授权码"
                      />
                      <button
                        type="button"
                        className="btn btn-xs"
                        disabled={busy === kind}
                        onClick={() => void submitCode(kind, session.login_id)}
                      >
                        提交授权码
                      </button>
                    </div>
                  ) : null}
                </div>
                {connected ? (
                  <button
                    type="button"
                    className="btn btn-xs"
                    disabled={busy === kind}
                    onClick={() => void logout(kind)}
                  >
                    {busy === kind ? "退出中…" : "退出"}
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn btn-xs btn-primary"
                    disabled={busy === kind || pending}
                    onClick={() => void start(kind)}
                  >
                    {busy === kind ? "启动中…" : CONNECT_LABELS[kind]}
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
      <p className="ext-agent-note">
        需要这台电脑保持在线，并已安装官方 Cursor CLI（agent）或 Antigravity CLI（agy）。
        未安装时连接会给出明确提示，不会假装已连接。
      </p>
    </section>
  );
}
