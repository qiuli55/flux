/**
 * 系统诊断页：后端健康、就绪探针、DSH 运行时、本机 CLI 安装与 Agent 运行时的事实快照。
 * 数据来自 /health、/health/ready、/dsh/status、/installations、/agents，仅展示不做修改。
 */
import { useCallback, useEffect, useState } from "react";

import { api } from "../api/client";
import type { AgentHandle, DshStatus, HealthData, Installation, ReadyData } from "../api/types";
import { Icon } from "../shell/Icon";
import { Badge, Button, Card, Empty, PageHead, SectionTitle } from "../shell/ui";

type Tone = "default" | "brand" | "green" | "orange" | "red" | "blue";

const INSTALL_TONE: Record<string, Tone> = {
  READY: "green", CONNECTED: "brand", VERIFIED: "blue", DISCOVERED: "orange", NOT_INSTALLED: "default",
};

function HealthTile({ ok, label, value, desc }: { ok: boolean; label: string; value: string; desc?: string }) {
  return (
    <div className="fx-health-tile">
      <div className="fx-row fx-gap-2">
        <i className={["fx-pulse", ok ? "fx-ok" : "fx-warn"].filter(Boolean).join(" ")} />
        <span className="fx-muted" style={{ fontSize: 12 }}>{label}</span>
      </div>
      <div className="fx-v">{value}</div>
      {desc ? <div className="fx-d">{desc}</div> : null}
    </div>
  );
}

function InstallRow({ it }: { it: Installation }) {
  return (
    <div className="fx-set-row">
      <div className="fx-info">
        <div className="fx-t">{it.name} <span className="fx-dim">· {it.adapter}</span></div>
        <div className="fx-d">{it.path ?? "未发现可执行文件"}{it.version ? ` · ${it.version}` : ""}</div>
      </div>
      <div className="fx-ctl fx-row fx-gap-2">
        <Badge tone={it.auth_status === "ok" ? "green" : it.auth_status === "missing" ? "orange" : "default"}>鉴权 {it.auth_status}</Badge>
        <Badge tone={INSTALL_TONE[it.status] ?? "default"}>{it.status}</Badge>
      </div>
    </div>
  );
}

/* ---------------- 页面 ---------------- */
export function DiagnosticsPage() {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [ready, setReady] = useState<ReadyData | null>(null);
  const [dsh, setDsh] = useState<DshStatus | null>(null);
  const [installs, setInstalls] = useState<Installation[]>([]);
  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    const settled = await Promise.allSettled([
      api.health(), api.ready(), api.dshStatus(), api.listInstallations(), api.listAgents(),
    ]);
    if (settled[0].status === "fulfilled") setHealth(settled[0].value);
    if (settled[1].status === "fulfilled") setReady(settled[1].value);
    if (settled[2].status === "fulfilled") setDsh(settled[2].value);
    if (settled[3].status === "fulfilled") setInstalls(settled[3].value);
    if (settled[4].status === "fulfilled") setAgents(settled[4].value);
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <>
      <PageHead title="系统诊断" desc="后端健康、就绪状态、DSH 运行时与本机 CLI 安装的实时快照。">
        <Button variant="subtle" icon="refresh" onClick={load} busy={loading}>刷新</Button>
      </PageHead>

      <div className="fx-grid fx-c3" style={{ marginBottom: 16 }}>
        <HealthTile ok={health !== null} label="后端健康" value={health ? health.status : "未连接"} desc={health ? `${health.app} · ${health.env}` : "无法访问 /health"} />
        <HealthTile ok={Boolean(ready?.database)} label="数据库" value={ready?.database ? "已就绪" : "未就绪"} desc={ready ? `Agent ${ready.agents} 个 · Provider ${ready.providers.length} 个` : "无法访问 /health/ready"} />
        <HealthTile ok={Boolean(dsh?.enabled)} label="DSH 运行时" value={dsh?.enabled ? "已启用" : "已停用"} desc={dsh ? `${dsh.provider}/${dsh.model}` : "无法访问 /dsh/status"} />
      </div>

      <div className="fx-grid fx-c2">
        <Card pad>
          <SectionTitle>DSH 接线</SectionTitle>
          {dsh ? (
            <div className="fx-kv" style={{ marginTop: 12 }}>
              <span className="fx-k">Home</span><span className="fx-v fx-ellipsis">{dsh.home}</span>
              <span className="fx-k">Workspace</span><span className="fx-v fx-ellipsis">{dsh.workspace}</span>
              <span className="fx-k">Provider</span><span className="fx-v">{dsh.provider}</span>
              <span className="fx-k">Model</span><span className="fx-v">{dsh.model}</span>
              <span className="fx-k">MCP</span><span className="fx-v">{dsh.mcp.enabled ? "已启用" : "已停用"} · {dsh.mcp.server_name}</span>
              <span className="fx-k">MCP URL</span><span className="fx-v fx-ellipsis">{dsh.mcp.url}</span>
            </div>
          ) : <Empty icon="server" title="未获取到 DSH 状态" />}
          {ready ? (
            <div className="fx-row fx-wrap fx-gap-2" style={{ marginTop: 14 }}>
              {ready.providers.map((p) => <Badge key={p} tone="blue">{p}</Badge>)}
            </div>
          ) : null}
        </Card>

        <Card pad>
          <div className="fx-between" style={{ marginBottom: 6 }}>
            <SectionTitle>Agent 运行时</SectionTitle>
            <span className="fx-dim" style={{ fontSize: 11 }}>{agents.length} 个</span>
          </div>
          {agents.length === 0 ? <Empty icon="bot" title="没有注册的 Agent" /> : agents.map((a) => (
            <div className="fx-row fx-gap-3" key={a.id} style={{ padding: "8px 0", borderTop: "1px solid var(--fx-line-soft)" }}>
              <Icon name="bot" className="fx-icon-sm" />
              <span className="fx-grow fx-ellipsis">{a.spec.name}</span>
              <Badge tone={a.spec.runtime === "dsh" ? "brand" : "blue"}>{a.spec.runtime ?? "dsh"}</Badge>
              <Badge tone={a.state === "READY" ? "green" : "default"}>{a.state}</Badge>
            </div>
          ))}
        </Card>
      </div>

      <Card pad style={{ marginTop: 16 }}>
        <div className="fx-between" style={{ marginBottom: 6 }}>
          <SectionTitle>本机 CLI 安装</SectionTitle>
          <span className="fx-dim" style={{ fontSize: 11 }}>{installs.length} 个</span>
        </div>
        {installs.length === 0 ? <Empty icon="terminal" title="未发现本机 CLI Agent" /> : installs.map((it) => <InstallRow key={it.id} it={it} />)}
      </Card>
    </>
  );
}
