/**
 * IDE 工作台：Activity Bar + 侧栏 + 编辑器 + 底部工具 dock + 右侧 AI 对话。
 *
 * 变更审核已并入 IDE：侧栏「变更审核」浏览提案队列，选中后在编辑器内查看 diff，
 * 并执行接受 / 落盘(测试门禁) / 拒绝 / 回滚 / 崩溃恢复决策；不再单独占一级页面。
 * 版本控制、终端、问题、输出、落盘记录都在 IDE 内就近可达。
 */
import { useMemo, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import { Icon } from "../components/ui/Icon";
import { Avatar, Badge, Progress } from "../components/ui/primitives";
import { TerminalView, TestsView, LogsView, SearchView } from "./DevTools";
import { BatchesView } from "../components/review/BatchesView";
import { DiffReview } from "../components/review/DiffReview";
import { GitPanel } from "../components/review/GitPanel";
import { ReviewPanel } from "../components/review/ReviewPanel";
import { useReview } from "../components/review/useReview";
import { fileTree, tasks } from "../data/mock";
import type { ApplyBatch } from "../data/review";

type Activity = "files" | "search" | "review" | "git";
type View = { type: "code"; file: string } | { type: "diff"; id: string };

const OPEN_FILES = [
  { name: "ReviewPane.tsx", tone: "var(--brand)" },
  { name: "review.py", tone: "var(--orange)" },
  { name: "test_review_apply.py", tone: "var(--green)" },
];

const CODE = [
  "/**",
  " * 变更审核面板：逐行展示 diff 并允许接受/拒绝。",
  " */",
  "import { useMemo, useState } from 'react'",
  "import { UnifiedDiff } from './UnifiedDiff'",
  "",
  "export function ReviewPane({ files }: Props) {",
  "  const [picked, setPicked] = useState<Set<string>>(new Set())",
  "  const total = useMemo(() => files.reduce((n, f) => n + f.add + f.del, 0), [files])",
  "",
  "  return (",
  "    <section className='review'>",
  "      <header>共 {total} 行改动 · {picked.size} 个文件已选</header>",
  "      <UnifiedDiff files={files} picked={picked} onToggle={setPicked} />",
  "    </section>",
  "  )",
  "}",
];

/* ---------------- 最左：Activity Bar ---------------- */

function ActivityRail({ active, onPick, pending }: { active: Activity; onPick: (a: Activity) => void; pending: number }) {
  const items: { id: Activity; icon: string; label: string; badge?: number }[] = [
    { id: "files", icon: "files", label: "资源管理器" },
    { id: "search", icon: "search", label: "搜索" },
    { id: "review", icon: "diff", label: "变更审核", badge: pending },
    { id: "git", icon: "git", label: "版本控制" },
  ];
  return (
    <div className="act-bar">
      {items.map((it) => (
        <button
          key={it.id}
          className={["act-item", it.id === active && "active"].filter(Boolean).join(" ")}
          title={it.label}
          onClick={() => onPick(it.id)}
        >
          <Icon name={it.icon} />
          {it.badge ? <span className="act-badge">{it.badge}</span> : null}
        </button>
      ))}
      <span className="grow" />
      <Avatar name="Cato" size="sm" />
    </div>
  );
}

/* ---------------- 侧栏：文件树 ---------------- */

function visibleRows(collapsed: Set<number>): number[] {
  const out: number[] = [];
  let hide = Infinity;
  fileTree.forEach((r, i) => {
    if (r.depth >= hide) return;
    hide = Infinity;
    out.push(i);
    if (r.type === "dir" && collapsed.has(i)) hide = r.depth + 1;
  });
  return out;
}

function Explorer({ active, onPick }: { active: string; onPick: (n: string) => void }) {
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());
  const rows = useMemo(() => visibleRows(collapsed), [collapsed]);
  const toggle = (i: number) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(i)) next.delete(i);
      else next.add(i);
      return next;
    });
  return (
    <div className="ide-col">
      <div className="ide-colhead">
        工作区文件
        <span className="ide-badge">
          <i className="pulse-dot ok" /> 1284
        </span>
      </div>
      <div className="tree">
        {rows.map((i) => {
          const f = fileTree[i];
          const isDir = f.type === "dir";
          return (
            <div
              key={`${f.name}-${i}`}
              className={["tree-row", isDir && !collapsed.has(i) && "open", !isDir && f.name === active && "active"].filter(Boolean).join(" ")}
              style={{ paddingLeft: 8 + f.depth * 12 }}
              onClick={() => (isDir ? toggle(i) : onPick(f.name))}
            >
              <span className="chev">{isDir ? <Icon name="chevron" className="icon-sm" /> : null}</span>
              <span className="fi" style={{ color: isDir ? "var(--text-3)" : "var(--blue)" }}>
                <Icon name={isDir ? "folder" : "file"} className="icon-sm" />
              </span>
              <span className="nm">{f.name}</span>
              {!isDir && f.name.endsWith(".tsx") ? <span className="sfx">M</span> : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ---------------- 中间：编辑器 + dock ---------------- */

function CodeEditor() {
  return (
    <div className="code">
      <div className="code-gutter">
        {CODE.map((_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </div>
      <div className="code-body">
        {CODE.map((line, i) => (
          <span key={i} className={i === 8 ? "hl ln" : "ln"}>
            {line}
          </span>
        ))}
      </div>
    </div>
  );
}

function TabBar({ view, onPickFile, onClose }: { view: View; onPickFile: (f: string) => void; onClose: () => void }) {
  return (
    <div className="ide-tabs">
      {OPEN_FILES.map((f) => (
        <div
          key={f.name}
          className={["ide-tab", view.type === "code" && view.file === f.name && "active"].filter(Boolean).join(" ")}
          onClick={() => onPickFile(f.name)}
        >
          <span className="dot" style={{ background: f.tone }} />
          {f.name}
        </div>
      ))}
      {view.type === "diff" ? (
        <div className="ide-tab active rv-tab">
          <Icon name="diff" className="icon-sm" style={{ color: "var(--brand-3)" }} />
          变更审核 · Diff
          <Icon name="close" className="x icon-sm" onClick={onClose} />
        </div>
      ) : null}
      <span className="grow" />
      <span className="ide-tab-actions">
        <Badge tone="brand" dot>
          Cato 正在编辑
        </Badge>
      </span>
    </div>
  );
}

function SplitHandle({ height, onResize }: { height: number; onResize: (h: number) => void }) {
  const down = (e: ReactPointerEvent) => {
    e.preventDefault();
    const y0 = e.clientY;
    const h0 = height;
    const move = (ev: PointerEvent) => onResize(Math.max(120, Math.min(460, h0 - (ev.clientY - y0))));
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };
  return <div className="ide-split" role="separator" aria-orientation="horizontal" onPointerDown={down} />;
}

function Dock({ batches, onRollback }: { batches: ApplyBatch[]; onRollback: (id: string) => void }) {
  const [tab, setTab] = useState("term");
  const [open, setOpen] = useState(true);
  const [h, setH] = useState(210);
  const tabs = [
    { id: "term", label: "终端", icon: "terminal" },
    { id: "problems", label: "问题", icon: "debug" },
    { id: "output", label: "输出", icon: "activity" },
    { id: "batches", label: `落盘记录 ${batches.length}`, icon: "download" },
  ];
  return (
    <>
      {open ? <SplitHandle height={h} onResize={setH} /> : null}
      <div className="ide-dock" style={{ height: open ? h : 34 }}>
        <div className="ide-dock-tabs">
          {tabs.map((t) => (
            <button
              key={t.id}
              className={["docktab", t.id === tab && "active"].filter(Boolean).join(" ")}
              onClick={() => {
                setTab(t.id);
                setOpen(true);
              }}
            >
              <Icon name={t.icon} className="icon-sm" />
              {t.label}
            </button>
          ))}
          <span className="grow" />
          <button className="btn quiet icon-only sm" onClick={() => setOpen((v) => !v)} aria-label={open ? "折叠" : "展开"}>
            <Icon name={open ? "chevdown" : "chevron"} />
          </button>
        </div>
        {open ? (
          <div className="ide-dock-body">
            {tab === "term" ? <TerminalView /> : null}
            {tab === "problems" ? <TestsView /> : null}
            {tab === "output" ? <LogsView /> : null}
            {tab === "batches" ? <BatchesView batches={batches} onRollback={onRollback} /> : null}
          </div>
        ) : null}
      </div>
    </>
  );
}

/* ---------------- 右：AI 对话 ---------------- */

interface AiMsg {
  id: number;
  who: "user" | "agent";
  text: string;
}

const AI_SEED: AiMsg[] = [
  { id: 1, who: "agent", text: "变更集 #4821 生成了 5 条提案，都在侧栏「变更审核」里；应用前不落盘。" },
  { id: 2, who: "user", text: "先看 test_review_apply.py，确认覆盖了过期 diff 的 409。" },
  { id: 3, who: "agent", text: "已打开该文件 diff：新增 12 行，含 test_apply_rejects_stale_diff。可直接「接受并落盘」。" },
];

const CHIPS = ["解释这个 diff", "补一条测试", "写提交信息"];

function AiChat() {
  const [msgs, setMsgs] = useState<AiMsg[]>(AI_SEED);
  const [text, setText] = useState("");
  const running = tasks.find((t) => t.status === "running");
  const send = () => {
    const v = text.trim();
    if (!v) return;
    const id = Date.now();
    setMsgs((prev) => [...prev, { id, who: "user", text: v }]);
    setText("");
    window.setTimeout(
      () => setMsgs((prev) => [...prev, { id: id + 1, who: "agent", text: "收到。改动会在变更审核里变成提案，你逐条确认后再落盘。" }]),
      480,
    );
  };
  return (
    <div className="ai-panel">
      <div className="ai-head">
        <Avatar name="Cato" />
        <div className="grow">
          <div className="row gap-2">
            <span style={{ fontSize: 13, fontWeight: 650 }}>Cato</span>
            <span className="pulse-dot ok" />
          </div>
          <div className="dim" style={{ fontSize: 11 }}>
            架构与后端 · deepseek-flash
          </div>
        </div>
      </div>
      <div className="ai-status">
        <span className="row gap-2">
          <Icon name="activity" className="icon-sm" style={{ color: "var(--brand-3)" }} />
          {running ? running.title : "空闲"}
        </span>
        <Progress value={62} thin />
        <span className="dim" style={{ fontSize: 11 }}>
          第 3 / 4 步 · 3 文件上下文
        </span>
      </div>
      <div className="ai-stream">
        {msgs.map((m) => (
          <div key={m.id} className={["ai-msg", m.who].join(" ")}>
            {m.who === "agent" ? <Avatar name="Cato" size="sm" /> : <Avatar name="秋" tone={1} size="sm" />}
            <div className="ai-bubble">{m.text}</div>
          </div>
        ))}
      </div>
      <div className="ai-compose">
        <div className="ai-chips">
          {CHIPS.map((c) => (
            <button key={c} className="ai-chip" onClick={() => setText(c)}>
              {c}
            </button>
          ))}
        </div>
        <div className="ai-input">
          <textarea
            rows={1}
            placeholder="描述要改什么，或就当前 diff 提问…（⌘I）"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
          />
          <button className="btn primary icon-only sm" onClick={send} aria-label="发送">
            <Icon name="arrowright" />
          </button>
        </div>
      </div>
    </div>
  );
}

/* ---------------- 组装 ---------------- */

export function Ide() {
  const [activity, setActivity] = useState<Activity>("review");
  const [view, setView] = useState<View>({ type: "code", file: "ReviewPane.tsx" });
  const review = useReview();

  const pending = review.changes.filter((c) => c.status === "pending").length;
  const selected = view.type === "diff" ? review.changes.find((c) => c.id === view.id) : undefined;
  const recoverFor = selected ? review.recoveryItems.find((r) => r.change_id === selected.id) : undefined;

  const openDiff = (id: string) => setView({ type: "diff", id });

  return (
    <div className="ide">
      <ActivityRail active={activity} onPick={setActivity} pending={pending} />
      {activity === "files" ? (
        <Explorer active={view.type === "code" ? view.file : ""} onPick={(f) => setView({ type: "code", file: f })} />
      ) : null}
      {activity === "search" ? (
        <div className="ide-col">
          <SearchView />
        </div>
      ) : null}
      {activity === "review" ? (
        <div className="ide-col">
          <ReviewPanel changes={review.changes} selected={view.type === "diff" ? view.id : null} onPick={openDiff} onGroupAction={review.groupAction} />
        </div>
      ) : null}
      {activity === "git" ? (
        <div className="ide-col">
          <GitPanel />
        </div>
      ) : null}

      <div className="ide-center">
        <TabBar view={view} onPickFile={(f) => setView({ type: "code", file: f })} onClose={() => setView({ type: "code", file: "ReviewPane.tsx" })} />
        <div className="ide-editor">
          {view.type === "diff" && selected ? (
            <DiffReview
              change={selected}
              recovery={recoverFor}
              busy={review.busyIds.includes(selected.id)}
              onAccept={() => review.accept([selected.id])}
              onApply={() => review.apply([selected.id])}
              onReject={() => review.reject([selected.id])}
              onRollback={() => review.rollback(selected.id)}
              onResolveRecovery={(a) => review.resolveRecovery(selected.id, a)}
            />
          ) : (
            <CodeEditor />
          )}
        </div>
        <Dock batches={review.batches} onRollback={review.rollbackBatch} />
      </div>

      <AiChat />
    </div>
  );
}
