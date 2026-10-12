/**
 * 任务看板：按状态分列，卡片支持 HTML5 拖拽在列间流转（本地 state）。
 */
import { useState } from "react";
import type { DragEvent } from "react";
import { Icon } from "../components/ui/Icon";
import { Badge } from "../components/ui/primitives";
import { useToast } from "../app/Toast";
import { tasks as seed, type Task } from "../data/mock";

const COLS: { id: Task["status"]; label: string }[] = [
  { id: "backlog", label: "待办" },
  { id: "planning", label: "计划中" },
  { id: "running", label: "执行中" },
  { id: "done", label: "已完成" },
];

const TONE: Record<Task["priority"], "red" | "orange" | "default"> = { P0: "red", P1: "orange", P2: "default" };

function KbCard({ t, onDrag }: { t: Task; onDrag: (e: DragEvent, id: string) => void }) {
  const [drag, setDrag] = useState(false);
  return (
    <div
      className={["kb-card", drag && "dragging"].filter(Boolean).join(" ")}
      draggable
      onDragStart={(e) => {
        setDrag(true);
        onDrag(e, t.id);
      }}
      onDragEnd={() => setDrag(false)}
    >
      <div className="row gap-2" style={{ marginBottom: 8 }}>
        <span className="mono dim" style={{ fontSize: 10.5 }}>
          {t.id}
        </span>
        <Badge tone={TONE[t.priority]}>{t.priority}</Badge>
      </div>
      <div className="t">{t.title}</div>
      <div className="row gap-1 wrap">
        {t.tags.map((g) => (
          <span className="kb-tag" key={g}>
            {g}
          </span>
        ))}
      </div>
      <div className="foot">
        <span className="avatar sm">{t.agent.slice(0, 1)}</span>
        <span className="dim" style={{ fontSize: 11 }}>
          {t.agent}
        </span>
        <span className="grow" />
        {t.comments > 0 ? (
          <span className="dim row gap-1" style={{ fontSize: 11 }}>
            <Icon name="mail" className="icon-sm" /> {t.comments}
          </span>
        ) : null}
      </div>
    </div>
  );
}

export function Tasks() {
  const [list, setList] = useState<Task[]>(seed);
  const [over, setOver] = useState<string | null>(null);
  const toast = useToast();

  const drop = (status: Task["status"]) => {
    const id = window.__dragId;
    if (!id) return;
    setList((prev) => prev.map((t) => (t.id === id ? { ...t, status } : t)));
    toast(`任务 ${id} 已流转到「${COLS.find((c) => c.id === status)?.label}」`);
    window.__dragId = null;
    setOver(null);
  };

  return (
    <div className="kanban">
      {COLS.map((col) => {
        const items = list.filter((t) => t.status === col.id);
        return (
          <div
            key={col.id}
            className="kb-col"
            style={over === col.id ? { borderColor: "var(--brand-line)", background: "var(--panel)" } : undefined}
            onDragOver={(e) => {
              e.preventDefault();
              setOver(col.id);
            }}
            onDragLeave={() => setOver((o) => (o === col.id ? null : o))}
            onDrop={() => drop(col.id)}
          >
            <div className="kb-col-head">
              <span className="pulse-dot" style={{ background: col.id === "running" ? "var(--brand)" : "var(--text-4)" }} />
              {col.label}
              <span className="n">{items.length}</span>
            </div>
            {items.map((t) => (
              <KbCard key={t.id} t={t} onDrag={(_e, id) => (window.__dragId = id)} />
            ))}
          </div>
        );
      })}
    </div>
  );
}

declare global {
  interface Window {
    __dragId?: string | null;
  }
}
