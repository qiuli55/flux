/**
 * Agent 档案网格：展示各 Agent 的角色、模型、状态、能力标签与成功率。
 * 2026-10 起它作为「设置」里的一个分组使用，不再是一级页面。
 */
import { Avatar, Badge, Button, Card, Progress } from "../components/ui/primitives";
import { agents } from "../data/mock";

const statusText: Record<string, { t: string; tone: "brand" | "green" | "default" }> = {
  running: { t: "运行中", tone: "brand" },
  idle: { t: "空闲", tone: "green" },
  offline: { t: "离线", tone: "default" },
};

function AgentCard({ a }: { a: (typeof agents)[number] }) {
  const s = statusText[a.status];
  return (
    <Card pad hover className="anim-up">
      <div className="row gap-3" style={{ marginBottom: 12 }}>
        <Avatar name={a.name} tone={a.tone} size="lg" />
        <div className="grow">
          <div className="row gap-2">
            <span style={{ fontSize: 15, fontWeight: 700 }}>{a.name}</span>
            <Badge tone={s.tone} dot>
              {s.t}
            </Badge>
          </div>
          <div className="dim" style={{ fontSize: 11.5, marginTop: 2 }}>
            {a.role} · {a.model}
          </div>
        </div>
      </div>
      <div className="row gap-1 wrap" style={{ marginBottom: 12 }}>
        {a.skills.map((k) => (
          <span className="chip" key={k}>
            {k}
          </span>
        ))}
      </div>
      <div className="row gap-3" style={{ fontSize: 11.5, color: "var(--text-3)", marginBottom: 10 }}>
        <span>
          任务 <b style={{ color: "var(--text)" }}>{a.tasks}</b>
        </span>
        <span>
          成功率 <b style={{ color: "var(--green)" }}>{a.success}%</b>
        </span>
      </div>
      <Progress value={a.success} thin />
      <Button variant="ghost" block icon="settings" style={{ marginTop: 14 }}>
        配置
      </Button>
    </Card>
  );
}

/** Agent 卡片网格（设置分组复用）。 */
export function AgentsGrid() {
  return (
    <div className="grid auto">
      {agents.map((a) => (
        <AgentCard key={a.id} a={a} />
      ))}
    </div>
  );
}
