/**
 * 开发工具视图：版本控制、终端、测试、日志、搜索。
 * 2026-10 起这些不再是独立一级页面，而是被 IDE 的底部 dock 与 Solo 的工具抽屉复用，
 * 因此这里只导出「视图」组件（不带 page-head），页面级外壳由调用方决定。
 */
import { useState } from "react";
import { Icon } from "../components/ui/Icon";
import { Badge, Button, Check, Input, Switch } from "../components/ui/primitives";
import { Select, type Option } from "../components/ui/Select";
import { commits, logLines, testCases } from "../data/mock";

/* ---------------- 版本控制 ---------------- */
/** Git 视图：分支条 + 提交历史 + 工作区改动。 */
export function GitView() {
  return (
    <div className="tool-pane">
      <div className="git-branch">
        <Icon name="branch" />
        <span style={{ fontWeight: 600 }}>main</span>
        <Badge tone="green">↑1 待推送</Badge>
        <span className="grow" />
        <span className="dim" style={{ fontSize: 12 }}>
          5 个文件已修改
        </span>
      </div>
      <div className="tool-split">
        <div>
          <div className="tool-caption">提交历史</div>
          {commits.map((c) => (
            <div className="commit" key={c.hash}>
              <div className="graph">
                <span className="node" />
                <span className="line-v" />
              </div>
              <div className="grow">
                <div className="row gap-2">
                  <span className="hash">{c.hash}</span>
                  <span className="dim" style={{ fontSize: 11 }}>
                    {c.author} · {c.time}
                  </span>
                </div>
                <div className="msg">{c.msg}</div>
              </div>
            </div>
          ))}
        </div>
        <div>
          <div className="tool-caption">工作区改动</div>
          {["ReviewPane.tsx", "review.py", "test_review_apply.py", "FLUX_UI_INFORMATION_ARCHITECTURE.md"].map((f) => (
            <div key={f} className="row gap-2" style={{ padding: "7px 0", fontSize: 12.5 }}>
              <Icon name="edit" className="icon-sm" style={{ color: "var(--orange)" }} />
              <span className="mono ellipsis grow" style={{ color: "var(--text-2)" }}>
                {f}
              </span>
              <Button variant="quiet" size="sm" icon="plus" />
              <Button variant="quiet" size="sm" icon="close" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ---------------- 终端 ---------------- */
/** 终端视图：Agent 与人工共享同一 PTY 会话的演示输出。 */
export function TerminalView() {
  return (
    <div className="term">
      <div>
        <span className="p">秋离@flux</span> <span className="dim2">~/workspace/flux</span> <span className="p">$</span>{" "}
        <span className="cmd">bash scripts/verify.sh</span>
      </div>
      <div className="dim2">[1/5] ruff check ............. 通过</div>
      <div className="dim2">[2/5] alembic upgrade head .... 通过</div>
      <div className="dim2">[3/5] pytest ................. 291 passed, 1 skipped</div>
      <div className="warn">[4/5] test_apply_rejects_stale_diff ... FAILED</div>
      <div className="err">      assert 200 == 409  # 期望 stale diff 返回 409</div>
      <div className="dim2">[5/5] export_openapi --check .. 通过</div>
      <div className="ok">✗ verify.sh：4 步通过，1 步失败（4.9s）</div>
      <div style={{ marginTop: 8 }}>
        <span className="p">Cato</span> <span className="dim2">（Agent）</span> <span className="p">$</span>{" "}
        <span className="cmd">grep -rn "body.stale" backend/</span>
      </div>
      <div className="dim2">backend/flux/api/v1/review.py:4:    if body.stale:</div>
      <div style={{ marginTop: 8 }}>
        <span className="p">秋离@flux</span> <span className="dim2">~/workspace/flux</span> <span className="p">$</span>{" "}
        <span className="cursor" />
      </div>
    </div>
  );
}

/* ---------------- 测试与调试 ---------------- */
/** 测试视图：通过/失败/耗时/覆盖率概览 + 用例列表。 */
export function TestsView() {
  const passed = testCases.filter((t) => t.state === "pass").length;
  const failed = testCases.filter((t) => t.state === "fail").length;
  return (
    <div className="tool-pane">
      <div className="test-stats">
        <span className="ts ok">
          <Icon name="check" className="icon-sm" /> 通过 <b>{passed}</b>
        </span>
        <span className="ts err">
          <Icon name="close" className="icon-sm" /> 失败 <b>{failed}</b>
        </span>
        <span className="ts">
          <Icon name="clock" className="icon-sm" /> 4.9s
        </span>
        <span className="ts">
          <Icon name="chart" className="icon-sm" /> 覆盖率 <b>87%</b>
        </span>
      </div>
      <div>
        {testCases.map((t) => (
          <div className="test-row" key={t.name}>
            <i className={["dotst", t.state].join(" ")} />
            <span className="name">{t.name}</span>
            {t.state === "fail" ? <Badge tone="red">断言失败</Badge> : null}
            {t.state === "skip" ? <Badge>已跳过</Badge> : null}
            <span className="dur">{t.dur}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ---------------- 日志与诊断 ---------------- */
const SRC_OPTS: Option[] = [
  { value: "all", label: "全部来源" },
  { value: "scheduler", label: "scheduler" },
  { value: "apply", label: "apply" },
  { value: "provider", label: "provider" },
  { value: "guard", label: "guard" },
];

/** 日志视图：按级别与来源过滤的运行时事件流。 */
export function LogsView() {
  const [level, setLevel] = useState("all");
  const [src, setSrc] = useState("all");
  const [live, setLive] = useState(true);
  const rows = logLines.filter((l) => (level === "all" || l.level === level) && (src === "all" || l.src === src));
  return (
    <div className="tool-pane">
      <div className="tool-filters">
        <Select
          value={level}
          onChange={setLevel}
          width={130}
          options={[
            { value: "all", label: "全部级别" },
            { value: "info", label: "INFO" },
            { value: "warn", label: "WARN" },
            { value: "error", label: "ERROR" },
            { value: "debug", label: "DEBUG" },
          ]}
        />
        <Select value={src} onChange={setSrc} width={140} options={SRC_OPTS} />
        <span className="row gap-2">
          <span className="dim" style={{ fontSize: 11.5 }}>
            实时
          </span>
          <Switch on={live} onChange={setLive} />
        </span>
        <span className="grow" />
        <span className="dim" style={{ fontSize: 11.5 }}>
          {rows.length} 条
        </span>
      </div>
      <div>
        {rows.map((l, i) => (
          <div className="log-line" key={i}>
            <span className="ts">{l.ts}</span>
            <span className={["log-level", l.level].join(" ")}>{l.level}</span>
            <span>
              <span className="dim">[{l.src}]</span> {l.msg}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ---------------- 全量搜索 ---------------- */
const HITS = [
  { file: "backend/flux/api/v1/review.py", line: "if body.stale: raise HTTPException(409, 'stale diff')", n: 6 },
  { file: "apps/web-dashboard/src/app/diff.ts", line: "export function toUnified(hunks: Hunk[]): string", n: 21 },
  { file: "backend/tests/test_review_apply.py", line: "def test_apply_rejects_stale_diff():", n: 44 },
];

/** 搜索视图：跨文件内容检索（用于抽屉/浮层）。 */
export function SearchView() {
  const [q, setQ] = useState("stale");
  const [caseSense, setCaseSense] = useState(false);
  const [regex, setRegex] = useState(true);
  const hits = HITS.filter((h) => h.line.toLowerCase().includes(q.toLowerCase()));
  return (
    <div className="tool-pane">
      <div className="row gap-3 wrap" style={{ marginBottom: 12 }}>
        <Input icon="search" placeholder="搜索内容…" value={q} onChange={(e) => setQ(e.target.value)} />
        <div className="row gap-3" style={{ flex: "none" }}>
          <Check label="区分大小写" checked={caseSense} onChange={setCaseSense} />
          <Check label="正则" checked={regex} onChange={setRegex} />
          <span className="badge">{hits.length} 处命中</span>
        </div>
      </div>
      {hits.map((h) => (
        <div className="search-result" key={h.file}>
          <Icon name="file" className="icon-sm" style={{ color: "var(--text-4)", marginTop: 2 }} />
          <div className="grow">
            <div className="path">
              {h.file}
              <span className="dim"> : {h.n}</span>
            </div>
            <div className="line">
              {h.line.split(q).map((part, i, arr) => (
                <span key={i}>
                  {part}
                  {i < arr.length - 1 ? <mark>{q}</mark> : null}
                </span>
              ))}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
