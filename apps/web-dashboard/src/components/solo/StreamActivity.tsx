/**
 * 执行过程卡：思考块 + 工具调用 + 错误行。
 * 运行中默认展开、思考默认展开；结束后自动收起，可手动再展开。
 * 通过 `embedded` 复用于历史执行轨迹卡（不带自己的标题行）。
 */
import { useEffect, useState } from "react";

import {
  formatElapsed,
  type StreamErrorLine,
  type StreamReasoning,
  type StreamToolStep,
  type StreamToolStatus,
} from "./taskStream";

export interface ActivitySummary {
  commands?: number;
  filesRead?: number;
  filesChanged?: number;
  durationMs?: number | null;
}

interface StreamActivityProps {
  reasoning: StreamReasoning | null;
  tools: StreamToolStep[];
  errors: StreamErrorLine[];
  active: boolean;
  startedAtMs: number | null;
  endedAtMs: number | null;
  summary?: ActivitySummary | null;
  label?: string | null;
  onOpenTrace?: () => void;
  embedded?: boolean;
}

const CATEGORY_ICON: Record<string, string> = {
  command: "⌘",
  read: "◫",
  write: "✎",
  other: "◆",
};

function countPaths(tools: StreamToolStep[]): number {
  const paths = new Set<string>();
  for (const tool of tools) {
    for (const path of tool.paths) paths.add(path);
  }
  return paths.size;
}

function summarize(tools: StreamToolStep[], summary?: ActivitySummary | null): string {
  const commands =
    summary?.commands ?? tools.filter((tool) => tool.category === "command").length;
  const reads = summary?.filesRead ?? countPaths(tools.filter((tool) => tool.category === "read"));
  const writes =
    summary?.filesChanged ?? countPaths(tools.filter((tool) => tool.category === "write"));
  const parts: string[] = [];
  if (commands > 0) parts.push(`${commands} 条命令`);
  if (writes > 0) parts.push(`改动 ${writes} 文件`);
  if (reads > 0) parts.push(`读取 ${reads} 文件`);
  if (parts.length === 0 && tools.length > 0) parts.push(`${tools.length} 个步骤`);
  return parts.join(" · ");
}

function StatusGlyph({ status }: { status: StreamToolStatus }) {
  if (status === "running") return <i className="stream-spin" aria-label="运行中" />;
  if (status === "failed") return <span className="stream-status-fail" aria-label="失败">✗</span>;
  return <span className="stream-status-done" aria-label="完成">✓</span>;
}

function ToolRow({ tool }: { tool: StreamToolStep }) {
  const [open, setOpen] = useState(false);
  const hasBody = Boolean(tool.output || tool.detail || tool.paths.length > 0);
  const icon = CATEGORY_ICON[tool.category] ?? CATEGORY_ICON.other;

  return (
    <div className={`stream-tool${open ? " is-open" : ""} is-${tool.category || "other"}`}>
      <button
        type="button"
        className="stream-tool-head"
        onClick={() => hasBody && setOpen((value) => !value)}
        aria-expanded={hasBody ? open : undefined}
        aria-disabled={hasBody ? undefined : true}
      >
        <span className="stream-tool-icon" aria-hidden="true">{icon}</span>
        <span className="stream-tool-name">{tool.name}</span>
        {tool.detail && <span className="stream-tool-detail">{tool.detail}</span>}
        <span className="stream-tool-state">
          <StatusGlyph status={tool.status} />
        </span>
      </button>
      {open && hasBody && (
        <div className="stream-tool-body">
          {tool.paths.length > 0 && (
            <div className="stream-tool-paths">
              {tool.paths.map((path) => (
                <code key={path}>{path}</code>
              ))}
            </div>
          )}
          {tool.output && <pre className="stream-tool-output">{tool.output}</pre>}
        </div>
      )}
    </div>
  );
}

export function StreamActivity({
  reasoning,
  tools,
  errors,
  active,
  startedAtMs,
  endedAtMs,
  summary,
  label,
  onOpenTrace,
  embedded = false,
}: StreamActivityProps) {
  const [open, setOpen] = useState(active || embedded);
  const [thinkOpen, setThinkOpen] = useState(active || embedded);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [active]);

  useEffect(() => {
    if (active) {
      setOpen(true);
      setThinkOpen(true);
    } else if (!embedded) {
      setOpen(false);
      setThinkOpen(false);
    }
  }, [active, embedded]);

  const hasAny = Boolean(reasoning?.text || tools.length > 0 || errors.length > 0 || summary);
  if (!hasAny) return null;

  const elapsed = formatElapsed(startedAtMs, endedAtMs, now);
  const reasoningElapsed = reasoning
    ? formatElapsed(reasoning.startedAtMs, reasoning.endedAtMs, now)
    : "";
  const summaryText = summarize(tools, summary);

  return (
    <section className={`stream-activity${open ? " is-open" : ""}${embedded ? " is-embedded" : ""}`}>
      {!embedded && (
        <div className="stream-activity-head">
          <button
            type="button"
            className="stream-activity-toggle"
            onClick={() => setOpen((value) => !value)}
            aria-expanded={open}
          >
            <span className="stream-activity-caret" aria-hidden="true">{open ? "▾" : "▸"}</span>
            <span className="stream-activity-title">执行过程</span>
          </button>
          {summaryText && <span className="stream-activity-summary">{summaryText}</span>}
          {active ? (
            <span className="stream-activity-live">
              <i className="stream-spin" aria-hidden="true" />
              {label || "进行中"}
              {elapsed ? ` · ${elapsed}` : ""}
            </span>
          ) : (
            elapsed && <span className="stream-activity-meta">{elapsed}</span>
          )}
          {onOpenTrace && (
            <button type="button" className="stream-activity-trace" onClick={onOpenTrace}>
              查看轨迹
            </button>
          )}
        </div>
      )}

      {open && (
        <div className="stream-activity-body">
          {reasoning?.text && (
            <div className={`stream-think${reasoning.active ? " is-active" : ""}`}>
              <button
                type="button"
                className="stream-think-head"
                onClick={() => setThinkOpen((value) => !value)}
                aria-expanded={thinkOpen}
              >
                <span className="stream-think-label">
                  {reasoning.active ? "思考中…" : "思考"}
                  {reasoningElapsed ? ` ${reasoningElapsed}` : ""}
                </span>
                <span className="stream-think-caret" aria-hidden="true">
                  {thinkOpen ? "▾" : "▸"}
                </span>
              </button>
              {thinkOpen && <div className="stream-think-body">{reasoning.text}</div>}
            </div>
          )}

          {tools.length > 0 && (
            <div className="stream-tools">
              {tools.map((tool) => (
                <ToolRow key={tool.callId} tool={tool} />
              ))}
            </div>
          )}

          {errors.length > 0 && (
            <div className="stream-errors">
              {errors.map((error) => (
                <div key={error.id} className="stream-error">
                  <span aria-hidden="true">✗</span>
                  <span>{error.text}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
