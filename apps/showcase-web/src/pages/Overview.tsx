/**
 * 概览页（Command Center）：展示工作区态势、快速入口、执行闭环图示与全局动态。
 */
import { Icon } from "../components/ui/Icon";
import { Avatar, Badge, Button, Card, Progress, Stat } from "../components/ui/primitives";
import { activities, agents, changes, tasks } from "../data/mock";

const FLOW = ["需求", "Agent", "计划", "执行", "变更审核", "代码"];

function QuickAction({ icon, label }: { icon: string; label: string }) {
  return (
    <button className="q">
      <Icon name={icon} />
      {label}
    </button>
  );
}

function Hero() {
  return (
    <div className="ov-hero anim-up">
      <Badge tone="brand" dot>
        工作区 flux · main 分支
      </Badge>
      <h2>欢迎回来，秋离</h2>
      <p>
        今天有 <b style={{ color: "var(--text)" }}>2 个任务</b>在运行、<b style={{ color: "var(--text)" }}>1 个变更集</b>
        待你审核。Flux 会先把一切改动变成提案，经你确认后才落到文件。
      </p>
      <div className="ov-quick">
        <QuickAction icon="sparkles" label="开始新任务" />
        <QuickAction icon="diff" label="审核变更" />
        <QuickAction icon="code" label="打开工作台" />
        <QuickAction icon="plug" label="接入连接器" />
      </div>
      <div className="ov-flow" style={{ marginTop: 20 }}>
        {FLOW.map((s, i) => (
          <span key={s} style={{ display: "flex", alignItems: "center", gap: 6 }}>
            <span className="step">
              <span className="node" />
              {s}
            </span>
            {i < FLOW.length - 1 ? <Icon name="arrowright" className="icon-sm" style={{ color: "var(--text-4)" }} /> : null}
          </span>
        ))}
      </div>
    </div>
  );
}

function Stats() {
  const running = tasks.filter((t) => t.status === "running").length;
  return (
    <div className="grid c4" style={{ marginBottom: 16 }}>
      <Stat label="运行中任务" value={running} icon="activity" delta={{ dir: "up", text: "+1 今日" }} />
      <Stat label="待审变更集" value={1} unit="个" icon="diff" delta={{ dir: "up", text: "5 个文件" }} />
      <Stat label="在线 Agent" value={agents.filter((a) => a.status === "running").length} unit={`/ ${agents.length}`} icon="bot" />
      <Stat label="本月成本" value="¥ 48.6" icon="chart" delta={{ dir: "down", text: "-12%" }} />
    </div>
  );
}

function ActivityCard() {
  return (
    <Card pad>
      <div className="between" style={{ marginBottom: 6 }}>
        <div className="section-title">最近动态</div>
        <button className="btn quiet sm">全部</button>
      </div>
      {activities.map((a) => (
        <div className="activity-item" key={a.text}>
          <Avatar name={a.who} tone={a.tone} size="sm" />
          <div className="grow">
            <div className="t">
              <b>{a.who}</b> {a.text}
            </div>
            <div className="time">{a.time}</div>
          </div>
        </div>
      ))}
    </Card>
  );
}

function AgentsCard() {
  return (
    <Card pad>
      <div className="between" style={{ marginBottom: 12 }}>
        <div className="section-title">Agent 态势</div>
        <button className="btn quiet sm">管理</button>
      </div>
      <div className="col gap-3">
        {agents.slice(0, 3).map((a) => (
          <div key={a.id} className="row gap-3">
            <Avatar name={a.name} tone={a.tone} />
            <div className="grow">
              <div className="row gap-2">
                <span style={{ fontSize: 12.5, fontWeight: 600 }}>{a.name}</span>
                <span className="dim" style={{ fontSize: 11 }}>
                  {a.role}
                </span>
              </div>
              <Progress value={a.success} thin />
            </div>
            <Badge tone={a.status === "running" ? "brand" : "default"} dot>
              {a.status === "running" ? "运行中" : "空闲"}
            </Badge>
          </div>
        ))}
      </div>
    </Card>
  );
}

function ChangesCard() {
  return (
    <Card pad>
      <div className="between" style={{ marginBottom: 12 }}>
        <div className="row gap-2">
          <Icon name="diff" style={{ color: "var(--brand-3)" }} />
          <div className="section-title">变更集 #4821</div>
        </div>
        <Badge tone="orange">待审核</Badge>
      </div>
      {changes.slice(0, 4).map((c) => (
        <div key={c.file} className="row gap-2" style={{ padding: "6px 0", fontSize: 12 }}>
          <Icon name="file" className="icon-sm" style={{ color: "var(--text-4)" }} />
          <span className="mono ellipsis grow" style={{ color: "var(--text-2)" }}>
            {c.file}
          </span>
          <span style={{ color: "var(--green)" }}>+{c.add}</span>
          <span style={{ color: "var(--red)" }}>-{c.del}</span>
        </div>
      ))}
      <Button variant="subtle" block icon="eye" style={{ marginTop: 12 }}>
        打开逐行审核
      </Button>
    </Card>
  );
}

export function Overview() {
  return (
    <>
      <Hero />
      <Stats />
      <div className="grid c2" style={{ alignItems: "start" }}>
        <ActivityCard />
        <div className="col gap-3" style={{ gap: 16 }}>
          <ChangesCard />
          <AgentsCard />
        </div>
      </div>
    </>
  );
}
