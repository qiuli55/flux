/** 底部 · AI 任务流时间线（时间戳 + 动作 + 结果）与本次统计（文件变更数、+/- 行合计）。 */
import type { JSX } from "react";

import type { ChangeStatus } from "../api/types";
import { LEVEL_META, type LogEvent } from "../data/events";
import { formatTime } from "../utils/format";
import { ChangeStatusBadge, EmptyState } from "./ui";

export interface RunStats {
  /** 本次会话涉及的变更文件数（按文件路径去重） */
  files: number;
  /** 新增行合计 */
  added: number;
  /** 删除行合计 */
  removed: number;
  /** 各状态计数 */
  byStatus: Record<ChangeStatus, number>;
}

const STATUS_ORDER: ChangeStatus[] = ["pending", "accepted", "applied", "rejected", "failed"];

export function TaskTimeline({ events, stats }: { events: LogEvent[]; stats: RunStats }): JSX.Element {
  return (
    <div className="flex min-h-0 flex-1 gap-3 p-3">
      <div className="flex min-h-0 flex-1 flex-col">
        <p className="shrink-0 text-[11px] text-faint">AI 任务流（本轮 {events.length} 步）</p>
        <div className="mt-1 min-h-0 flex-1 overflow-y-auto">
          {events.length === 0 ? (
            <EmptyState>还没有任务步骤。每次调用接口都会在这里按时间顺序留下一步。</EmptyState>
          ) : (
            <ol className="relative space-y-1.5 pl-4">
              <span className="absolute top-1 bottom-1 left-[3px] w-px bg-line" aria-hidden="true" />
              {events.map((event) => {
                const meta = LEVEL_META[event.level];
                return (
                  <li key={event.id} className="relative">
                    <span
                      className={`absolute top-1.5 -left-[15px] h-2 w-2 rounded-full ring-2 ring-surface-1 ${meta.dot}`}
                      aria-hidden="true"
                    />
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 rounded-md border border-line-soft bg-surface-2 px-2 py-1.5">
                      <span className="font-mono text-[10px] text-faint">{formatTime(event.at)}</span>
                      <span className="text-[11px] font-medium text-text">{event.actor}</span>
                      <span className="text-[11px] text-muted">{event.action}</span>
                      <span className={`rounded border px-1.5 py-0.5 text-[10px] ${meta.className}`}>
                        {meta.label}
                      </span>
                      <span
                        className={`w-full break-words text-[11px] ${event.level === "error" ? "text-danger" : "text-faint"}`}
                      >
                        {event.result}
                      </span>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </div>
      </div>

      <div className="flex w-[16rem] shrink-0 flex-col gap-2">
        <div className="rounded-md border border-line bg-surface-2 p-3">
          <p className="text-[11px] text-faint">本次统计</p>
          <p className="mt-1 text-lg font-semibold text-text">{stats.files} 个文件变更</p>
          <p className="mt-0.5 text-xs">
            <span className="text-add">+{stats.added}</span>
            <span className="text-faint"> / </span>
            <span className="text-del">-{stats.removed}</span>
            <span className="ml-1 text-faint">行</span>
          </p>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto rounded-md border border-line bg-surface-2 p-3">
          <p className="text-[11px] text-faint">变更状态分布</p>
          <ul className="mt-1.5 space-y-1.5">
            {STATUS_ORDER.map((status) => (
              <li key={status} className="flex items-center justify-between gap-2">
                <ChangeStatusBadge status={status} />
                <span className="text-xs text-text">{stats.byStatus[status]}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}