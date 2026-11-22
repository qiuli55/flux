/**
 * 历史执行轨迹卡：挂在 kind=run_finished 的助手消息下方。
 *
 * 折叠头只显示计数与耗时；首次展开才懒加载
 * `GET /tasks/{taskId}/runs/{runId}/trace`，步骤行复用 StreamActivity 的渲染。
 */
import { useCallback, useMemo, useState } from "react";

import { api } from "../../api/client";
import type { RunTrace, RunTraceItem, TaskRunSummary } from "../../api/types";
import { StreamActivity } from "./StreamActivity";
import {
  formatDurationMs,
  normalizeToolStatus,
  type StreamErrorLine,
  type StreamReasoning,
  type StreamToolStep,
} from "./taskStream";

interface RunTraceCardProps {
  taskId: string;
  runId: string;
  summary?: TaskRunSummary;
}

interface TraceView {
  reasoning: StreamReasoning | null;
  tools: StreamToolStep[];
  errors: StreamErrorLine[];
}

function readPaths(item: RunTraceItem): string[] {
  return Array.isArray(item.paths) ? item.paths.filter((path) => typeof path === "string") : [];
}

/** 把 trace items 归并成执行过程卡需要的三段数据（思考 / 工具 / 错误） */
export function buildTraceView(items: RunTraceItem[]): TraceView {
  let thinking = "";
  const tools: StreamToolStep[] = [];
  const errors: StreamErrorLine[] = [];
  let errorId = 0;

  items.forEach((item, index) => {
    if (!item || typeof item !== "object") return;
    if (item.kind === "thinking") {
      if (item.text) thinking += item.text;
      return;
    }
    if (item.kind === "error") {
      errorId += 1;
      errors.push({ id: errorId, text: item.text || item.detail || "执行出错" });
      return;
    }
    if (item.kind !== "tool") return;

    const name = item.name || "tool";
    const callId = item.call_id || `${name}-${index}`;
    const status = normalizeToolStatus(item.status ?? "");
    const paths = readPaths(item);
    const existing = tools.findIndex((tool) => tool.callId === callId);
    const previous = existing >= 0 ? tools[existing] : undefined;
    if (previous) {
      tools[existing] = {
        ...previous,
        name: name || previous.name,
        detail: item.detail || previous.detail,
        output: item.output || previous.output,
        category: item.category || previous.category,
        status,
        paths: paths.length > 0 ? Array.from(new Set([...previous.paths, ...paths])) : previous.paths,
      };
      return;
    }
    tools.push({
      callId,
      name,
      detail: item.detail ?? "",
      output: item.output ?? "",
      status,
      category: item.category || "other",
      paths,
    });
  });

  return {
    reasoning: thinking
      ? { text: thinking, active: false, startedAtMs: null, endedAtMs: null }
      : null,
    tools,
    errors,
  };
}

type LoadState = "idle" | "loading" | "ready" | "error";

export function RunTraceCard({ taskId, runId, summary }: RunTraceCardProps) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<LoadState>("idle");
  const [trace, setTrace] = useState<RunTrace | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setState("loading");
    setError("");
    try {
      setTrace(await api.getRunTrace(taskId, runId));
      setState("ready");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
      setState("error");
    }
  }, [taskId, runId]);

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (state === "idle" || state === "error") void load();
  };

  const view = useMemo(
    () => (trace ? buildTraceView(trace.items ?? []) : null),
    [trace],
  );
  const commands = summary?.commands ?? trace?.commands ?? 0;
  const filesRead = summary?.files_read ?? trace?.files_read ?? 0;
  const filesChanged = summary?.files_changed ?? trace?.files_changed ?? 0;
  const duration = formatDurationMs(summary?.duration_ms ?? trace?.duration_ms);

  const counts: string[] = [];
  if (commands > 0) counts.push(`${commands} 条命令`);
  if (filesChanged > 0) counts.push(`改动 ${filesChanged} 文件`);
  if (filesRead > 0) counts.push(`读取 ${filesRead} 文件`);

  const hasSteps = Boolean(view && (view.reasoning || view.tools.length > 0 || view.errors.length > 0));

  return (
    <section className={`run-trace${open ? " is-open" : ""}`}>
      <button type="button" className="run-trace-head" onClick={toggle} aria-expanded={open}>
        <span className="run-trace-caret" aria-hidden="true">{open ? "▾" : "▸"}</span>
        <span className="run-trace-title">执行轨迹</span>
        {counts.length > 0 && <span className="run-trace-counts">{counts.join(" · ")}</span>}
        {duration && <span className="run-trace-duration">{duration}</span>}
      </button>

      {open && (
        <div className="run-trace-body">
          {state === "loading" && <div className="run-trace-hint">正在加载执行轨迹…</div>}
          {state === "error" && (
            <div className="run-trace-hint is-error">
              <span>{error || "执行轨迹加载失败"}</span>
              <button type="button" className="btn btn-xs btn-ghost" onClick={() => void load()}>
                重试
              </button>
            </div>
          )}
          {state === "ready" && !hasSteps && (
            <div className="run-trace-hint">这一轮没有留下可展示的执行步骤。</div>
          )}
          {state === "ready" && hasSteps && view && (
            <StreamActivity
              embedded
              reasoning={view.reasoning}
              tools={view.tools}
              errors={view.errors}
              active={false}
              startedAtMs={null}
              endedAtMs={null}
              summary={{
                commands,
                filesRead,
                filesChanged,
                durationMs: summary?.duration_ms ?? trace?.duration_ms,
              }}
            />
          )}
        </div>
      )}
    </section>
  );
}
