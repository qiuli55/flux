/**
 * 概览页：工作区总览 —— 关键指标、执行流程说明、最近任务与待审变更。
 * 数据来自真实后端（projects / tasks / workspace changes / agents / dsh status）。
 */
import { useCallback, useEffect, useState, type CSSProperties } from "react";

import { api } from "../api/client";
import type { AgentHandle, Change, DshStatus, Project, Task } from "../api/types";
import type { View } from "../app/router";
import { Icon } from "../shell/Icon";
import { Badge, Button, Card, Empty, PageHead, SectionTitle, Stat } from "../shell/ui";

const FLOW = ["需求澄清", "可行性计划", "生成变更", "人工审核", "落盘 / Git"];

function relTime(iso: string | null): string {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60_000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  return `${Math.floor(hour / 24)} 天前`;
}

const TASK_TONE: Record<Task["status"], "default" | "brand" | "green" | "orange" | "red" | "blue"> = {
  pending: "default",
  running: "brand",
  waiting_for_user_decision: "orange",
  completed: "green",
  failed: "red",
  cancelled: "default",
};

/* ---------------- 概览页 ---------------- */
export function OverviewPage({ navigate }: { navigate: (v: View) => void }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [changes, setChanges] = useState<Change[]>([]);
  const [agents, setAgents] = useState<AgentHandle[]>([]);
  const [dsh, setDsh] = useState<DshStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    const settled = await Promise.allSettled([
      api.listProjects(), api.listTasks({ limit: 50 }), api.listChanges("pending"), api.listAgents(), api.dshStatus(),
    ]);
    if (settled[0].status === "fulfilled") setProjects(settled[0].value);
    if (settled[1].status === "fulfilled") setTasks(settled[1].value);
    if (settled[2].status === "fulfilled") setChanges(settled[2].value);
    if (settled[3].status === "fulfilled") setAgents(settled[3].value);
    if (settled[4].status === "fulfilled") setDsh(settled[4].value);
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  const running = tasks.filter((t) => t.status === "running").length;
  const waiting = tasks.filter((t) => t.status === "waiting_for_user_decision").length;
  const recent = [...tasks].sort((a, b) => b.created_at.localeCompare(a.created_at)).slice(0, 6);

  return (
    <>
      <PageHead title="概览" desc="工作区一目了然：把需求交给 AI，在真实文件树上生成变更，人工审核后落盘。">
        <Button variant="subtle" icon="refresh" onClick={load} busy={loading}>刷新</Button>
        <Button variant="primary" icon="sparkles" onClick={() => navigate("solo")}>新建任务</Button>
      </PageHead>

      <section className="fx-hero">
        <Badge tone="brand" dot>当前引擎 {dsh ? `${dsh.provider}/${dsh.model}` : "—"}</Badge>
        <h2>让 Flux 接管需求到落盘的全过程</h2>
        <p>
          用自然语言描述需求，Flux 会先<b>澄清需求</b>、产出<b>执行计划</b>，再在虚拟工作区生成 diff。
          所有改动都需你<b>审核通过</b>后才写入磁盘，并可随时回滚。
        </p>
        <div className="fx-quick">
          <button className="fx-q" onClick={() => navigate("solo")}><Icon name="sparkles" className="fx-icon-sm" />新建 Solo 任务</button>
          <button className="fx-q" onClick={() => navigate("ide")}><Icon name="code" className="fx-icon-sm" />打开 IDE 工作台</button>
          <button className="fx-q" onClick={() => navigate("tasks")}><Icon name="layers" className="fx-icon-sm" />查看任务看板</button>
          <button className="fx-q" onClick={() => navigate("skills")}><Icon name="puzzle" className="fx-icon-sm" />管理 Skills</button>
        </div>
      </section>

      <div className="fx-stats" style={{ marginBottom: 16 }}>
        <Stat label="项目" value={projects.length} icon="folder" />
        <Stat label="运行中任务" value={running} unit={`/ ${tasks.length}`} icon="activity" />
        <Stat label="待决策" value={waiting} icon="bell" />
        <Stat label="待审变更" value={changes.length} icon="diff" />
      </div>

      <Card pad style={{ marginBottom: 16 }}>
        <SectionTitle>执行流程</SectionTitle>
        <div className="fx-flow" style={{ marginTop: 12 }}>
          {FLOW.map((step, i) => (
            <span key={step} style={{ display: "contents" }}>
              <span className="fx-step"><i className="fx-node" />{step}</span>
              {i < FLOW.length - 1 ? <Icon name="arrowright" className="fx-icon-sm fx-dim" /> : null}
            </span>
          ))}
        </div>
      </Card>

      <div className="fx-grid fx-c2">
        <Card pad>
          <div className="fx-between" style={{ marginBottom: 8 }}>
            <SectionTitle>最近任务</SectionTitle>
            <Button variant="quiet" size="sm" onClick={() => navigate("tasks")}>全部</Button>
          </div>
          {recent.length === 0 ? <Empty icon="layers" title="还没有任务" desc="从「新建任务」开始" /> : recent.map((t) => (
            <button key={t.id} className="fx-row fx-gap-3" style={rowBtn} onClick={() => navigate(t.status === "running" ? "ide" : "tasks")}>
              <Badge tone={TASK_TONE[t.status]} dot>{t.status}</Badge>
              <span className="fx-grow fx-ellipsis" style={{ textAlign: "left" }}>{t.description}</span>
              <span className="fx-dim" style={{ fontSize: 11 }}>{relTime(t.created_at)}</span>
            </button>
          ))}
        </Card>

        <Card pad>
          <div className="fx-between" style={{ marginBottom: 8 }}>
            <SectionTitle>待审变更</SectionTitle>
            <Button variant="quiet" size="sm" onClick={() => navigate("ide")}>去审核</Button>
          </div>
          {changes.length === 0 ? <Empty icon="check" title="没有待审变更" desc="所有改动都已处理" /> : changes.slice(0, 6).map((c) => (
            <div key={c.id} className="fx-file-row">
              <Badge tone={c.kind === "delete" ? "red" : c.kind === "create" ? "green" : "blue"}>{c.kind}</Badge>
              <span className="fx-path">{c.file_path}</span>
              <span className="fx-dim" style={{ fontSize: 11 }}>
                <span style={{ color: "var(--fx-green)" }}>+{c.added_lines}</span> <span style={{ color: "var(--fx-red)" }}>-{c.removed_lines}</span>
              </span>
            </div>
          ))}
        </Card>
      </div>

      <Card pad style={{ marginTop: 16 }}>
        <SectionTitle>Agent 运行时</SectionTitle>
        <div className="fx-row fx-wrap fx-gap-2" style={{ marginTop: 10 }}>
          {agents.length === 0 ? <span className="fx-dim">没有注册的 Agent</span> : agents.map((a) => (
            <Badge key={a.id} tone={a.spec.runtime === "dsh" ? "brand" : "blue"}>
              {a.spec.name} · {a.spec.runtime ?? "dsh"} · {a.state}
            </Badge>
          ))}
        </div>
      </Card>
    </>
  );
}

const rowBtn: CSSProperties = {
  width: "100%", background: "none", border: 0, borderBottom: "1px solid var(--fx-line-soft)",
  padding: "9px 0", cursor: "pointer", color: "var(--fx-text-2)", fontSize: 12.5,
};
