/**
 * 任务看板：按状态分组只读展示所有任务（后端不提供拖拽改状态接口，故不做拖拽）。
 * 数据来自 GET /api/v1/tasks；点击卡片进入 Solo 执行中心处理。
 */
import { useCallback, useEffect, useMemo, useState } from "react";

import { api } from "../api/client";
import type { Project, Task, TaskStatus } from "../api/types";
import type { View } from "../app/router";
import { Select } from "../shell/Select";
import { Badge, Button, Empty, PageHead } from "../shell/ui";

type Tone = "default" | "brand" | "green" | "orange" | "red" | "blue";

const MODE_LABEL: Record<Task["decision_mode"], string> = { auto: "自动决策", manual: "手动确认" };

const STATUS_META: Record<TaskStatus, { label: string; tone: Tone }> = {
  pending: { label: "待处理", tone: "default" },
  running: { label: "执行中", tone: "brand" },
  waiting_for_user_decision: { label: "待决策", tone: "orange" },
  completed: { label: "已完成", tone: "green" },
  failed: { label: "失败", tone: "red" },
  cancelled: { label: "已取消", tone: "default" },
};

const COLUMNS: { key: string; label: string; statuses: TaskStatus[] }[] = [
  { key: "todo", label: "待处理", statuses: ["pending"] },
  { key: "doing", label: "进行中", statuses: ["running", "waiting_for_user_decision"] },
  { key: "done", label: "已完成", statuses: ["completed"] },
  { key: "ended", label: "已结束", statuses: ["failed", "cancelled"] },
];

function relTime(iso: string): string {
  const min = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  return `${Math.floor(hour / 24)} 天前`;
}

/* ---------------- 任务卡 ---------------- */
function TaskCard({ task, onOpen }: { task: Task; onOpen: () => void }) {
  const meta = STATUS_META[task.status];
  return (
    <button className="fx-kb-card" onClick={onOpen}>
      <div className="fx-t">{task.description}</div>
      <div className="fx-row fx-wrap fx-gap-2">
        <Badge tone={meta.tone} dot>{meta.label}</Badge>
        <span className="fx-kb-tag">{MODE_LABEL[task.decision_mode]}</span>
      </div>
      <div className="fx-kb-foot">
        <span className="fx-kb-id">{task.id.slice(0, 8)}</span>
        {task.agent_id ? <span>· {task.agent_id}</span> : null}
        <span style={{ marginLeft: "auto" }}>{relTime(task.created_at)}</span>
      </div>
    </button>
  );
}

/* ---------------- 看板 ---------------- */
export function TasksPage({ navigate }: { navigate: (v: View) => void }) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [scope, setScope] = useState<string>("all");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    const settled = await Promise.allSettled([api.listTasks({ limit: 200 }), api.listProjects()]);
    if (settled[0].status === "fulfilled") setTasks(settled[0].value);
    if (settled[1].status === "fulfilled") setProjects(settled[1].value);
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  const scoped = useMemo(
    () => (scope === "all" ? tasks : tasks.filter((t) => t.project_id === scope)),
    [tasks, scope],
  );
  const cols = useMemo(
    () => COLUMNS.map((col) => ({ ...col, items: scoped.filter((t) => col.statuses.includes(t.status)) })),
    [scoped],
  );

  const scopeOptions = useMemo(
    () => [{ value: "all", label: "全部项目" }, ...projects.map((p) => ({ value: p.id, label: p.name }))],
    [projects],
  );

  return (
    <>
      <PageHead title="任务看板" desc="所有任务按状态分组展示。状态由执行流程推进，此处只读；进入 Solo 执行中心可推进任务。">
        <Button variant="subtle" icon="refresh" onClick={load} busy={loading}>刷新</Button>
        <Button variant="primary" icon="plus" onClick={() => navigate("solo")}>新建任务</Button>
      </PageHead>

      <div className="fx-toolbar">
        <Select value={scope} options={scopeOptions} onChange={setScope} width={200} />
        <span className="fx-dim" style={{ fontSize: 12 }}>共 {scoped.length} 个任务</span>
      </div>

      {scoped.length === 0 && !loading ? <Empty icon="layers" title="还没有任务" desc="点击「新建任务」开始" /> : (
        <div className="fx-kanban">
          {cols.map((col) => (
            <div className="fx-kb-col" key={col.key}>
              <div className="fx-kb-head">{col.label}<span className="fx-n">{col.items.length}</span></div>
              {col.items.length === 0 ? <div className="fx-dim" style={{ fontSize: 12, padding: "8px 2px" }}>暂无</div> : col.items.map((t) => (
                <TaskCard key={t.id} task={t} onOpen={() => navigate("solo")} />
              ))}
            </div>
          ))}
        </div>
      )}
    </>
  );
}
