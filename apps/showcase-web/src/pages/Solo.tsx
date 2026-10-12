/**
 * Solo 任务执行中心：需求澄清 → 计划 → 执行 → 结果的对话式界面。
 * 右上角「工具」下拉把版本控制 / 测试 / 日志 / 终端收进来（抽屉展示），
 * 这样它们不再各占一个一级页面，但需要时随手可达。
 */
import { useEffect, useRef, useState } from "react";
import { Icon } from "../components/ui/Icon";
import { Avatar, Badge, Button, Progress } from "../components/ui/primitives";
import { Drawer } from "../components/ui/overlay";
import { useToast } from "../app/Toast";
import { GitView, LogsView, TerminalView, TestsView } from "./DevTools";
import { agents, tasks } from "../data/mock";

type ToolId = "git" | "tests" | "logs" | "term";

const TOOLS: { id: ToolId; label: string; icon: string }[] = [
  { id: "git", label: "版本控制", icon: "git" },
  { id: "tests", label: "测试与调试", icon: "debug" },
  { id: "logs", label: "日志与诊断", icon: "activity" },
  { id: "term", label: "终端", icon: "terminal" },
];

function toolView(id: ToolId) {
  if (id === "git") return <GitView />;
  if (id === "tests") return <TestsView />;
  if (id === "logs") return <LogsView />;
  return (
    <div className="term-frame">
      <TerminalView />
    </div>
  );
}

/** 工具下拉：选中即在右侧抽屉展示对应视图。 */
function ToolsMenu({ onPick }: { onPick: (id: ToolId) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  return (
    <div className="tools-menu" ref={ref}>
      <button className="btn ghost sm" onClick={() => setOpen((v) => !v)} aria-haspopup="menu" aria-expanded={open}>
        <Icon name="terminal" />
        工具
        <Icon name="chevdown" className="icon-sm" />
      </button>
      {open ? (
        <div className="tools-menu-pop" role="menu">
          {TOOLS.map((t) => (
            <button
              key={t.id}
              className="select-opt"
              role="menuitem"
              onClick={() => {
                onPick(t.id);
                setOpen(false);
              }}
            >
              <span className="row gap-2">
                <Icon name={t.icon} className="icon-sm" />
                {t.label}
              </span>
              <Icon name="chevron" className="icon-sm" style={{ color: "var(--text-4)" }} />
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

interface Msg {
  id: number;
  who: "user" | "agent";
  text: string;
}

const INIT: Msg[] = [
  { id: 1, who: "user", text: "给变更审核加上逐行接受/拒绝，改动要能回滚，别直接覆盖文件。" },
  {
    id: 2,
    who: "agent",
    text: "收到。我先按你的约束拆成 4 步，并在 Virtual Workspace 里生成提案——应用前不会碰你的文件。需要我先确认两个点吗？",
  },
];

function PlanCard() {
  const steps = [
    { t: "读取 review 相关契约与现有组件", state: "done" },
    { t: "在 UnifiedDiff 上增加逐行选择状态", state: "done" },
    { t: "实现接受/拒绝的聚合与回滚入口", state: "active" },
    { t: "补 pytest 用例并跑回归基准", state: "wait" },
  ];
  return (
    <div className="plan anim-up">
      <div className="plan-head">
        <Icon name="layers" style={{ color: "var(--brand-3)" }} />
        执行计划 · 4 步
        <span className="grow" />
        <Badge tone="brand">2 / 4</Badge>
      </div>
      {steps.map((s, i) => (
        <div key={s.t} className={["plan-step", s.state].filter(Boolean).join(" ")}>
          <span className="idx">{s.state === "done" ? <Icon name="check" className="icon-sm" /> : i + 1}</span>
          <div className="grow">
            <div className="txt">{s.t}</div>
            {s.state === "active" ? (
              <div style={{ marginTop: 8 }}>
                <Progress value={62} thin />
              </div>
            ) : null}
          </div>
          {s.state === "active" ? (
            <Badge tone="brand" dot>
              进行中
            </Badge>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function Message({ m }: { m: Msg }) {
  return (
    <div className={["msg", m.who].join(" ")}>
      {m.who === "agent" ? <Avatar name="Cato" /> : <Avatar name="秋" tone={1} />}
      <div className="bubble">{m.text}</div>
    </div>
  );
}

function Side() {
  return (
    <aside className="solo-side">
      <div>
        <div className="side-block-title">当前任务</div>
        {tasks.slice(0, 4).map((t) => (
          <div className="mini-task" key={t.id}>
            <span className={["st", t.status === "running" ? "run" : t.status === "done" ? "done" : "wait"].join(" ")} />
            <div className="grow ellipsis" style={{ fontSize: 12 }}>
              {t.title}
            </div>
          </div>
        ))}
      </div>
      <div>
        <div className="side-block-title">参与 Agent</div>
        {agents.slice(0, 2).map((a) => (
          <div className="mini-task" key={a.id}>
            <Avatar name={a.name} tone={a.tone} size="sm" />
            <div className="grow">
              <div style={{ fontSize: 12, fontWeight: 550 }}>{a.name}</div>
              <div className="dim" style={{ fontSize: 10.5 }}>
                {a.role}
              </div>
            </div>
            <div className="pulse-dot ok" />
          </div>
        ))}
      </div>
      <div>
        <div className="side-block-title">约束与决策</div>
        <div className="col gap-2" style={{ fontSize: 11.5, color: "var(--text-3)" }}>
          <div className="row gap-2">
            <Icon name="check" className="icon-sm" style={{ color: "var(--green)" }} /> 永不直接覆盖文件
          </div>
          <div className="row gap-2">
            <Icon name="check" className="icon-sm" style={{ color: "var(--green)" }} /> 改动必须可回滚
          </div>
          <div className="row gap-2">
            <Icon name="check" className="icon-sm" style={{ color: "var(--green)" }} /> 补测试并跑回归
          </div>
        </div>
      </div>
    </aside>
  );
}

export function Solo() {
  const [msgs, setMsgs] = useState<Msg[]>(INIT);
  const [text, setText] = useState("");
  const [tool, setTool] = useState<ToolId | null>(null);
  const seq = useRef(100);
  const toast = useToast();

  const send = () => {
    const v = text.trim();
    if (!v) return;
    seq.current += 1;
    setMsgs((prev) => [...prev, { id: seq.current, who: "user", text: v }]);
    setText("");
    window.setTimeout(() => {
      seq.current += 1;
      setMsgs((prev) => [...prev, { id: seq.current, who: "agent", text: "好的，我把这条并入当前计划，稍后在变更审核里给你逐行确认。" }]);
    }, 500);
    toast("已加入当前任务上下文", "info");
  };

  const toolMeta = TOOLS.find((t) => t.id === tool);

  return (
    <div className="solo-wrap">
      <div className="solo-main">
        <div className="solo-top">
          <span className="solo-title">
            <Icon name="sparkles" className="icon-sm" style={{ color: "var(--brand-3)" }} />
            会话 · T-101 为变更审核引入逐行接受/拒绝
          </span>
          <span className="grow" />
          <ToolsMenu onPick={setTool} />
        </div>
        <div className="solo-stream">
          {msgs.map((m) => (
            <Message key={m.id} m={m} />
          ))}
          <PlanCard />
        </div>
        <div className="solo-composer">
          <div className="solo-input">
            <textarea
              rows={1}
              placeholder="描述需求，或追问 Agent…（Enter 发送）"
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
            />
            <Button variant="primary" icon="arrowright" onClick={send}>
              发送
            </Button>
          </div>
          <div className="row gap-3" style={{ marginTop: 8, fontSize: 11, color: "var(--text-4)" }}>
            <span className="row gap-1">
              <Icon name="shield" className="icon-sm" /> Virtual Workspace：应用前不落盘
            </span>
            <span className="row gap-1">
              <Icon name="cpu" className="icon-sm" /> deepseek-flash
            </span>
          </div>
        </div>
      </div>
      <Side />
      <Drawer open={tool !== null} onClose={() => setTool(null)} title={toolMeta?.label}>
        {tool ? toolView(tool) : null}
      </Drawer>
    </div>
  );
}
