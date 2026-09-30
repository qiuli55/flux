/** 右栏 · 最近活动：本轮操作按时间倒序，每条带时间与角色。 */
import type { JSX } from "react";

import { LEVEL_META, type LogEvent } from "../data/events";
import { formatClock } from "../utils/format";
import { EmptyState, Panel, PanelHeader } from "./ui";

export function ActivityFeed({ events }: { events: LogEvent[] }): JSX.Element {
  const recent = [...events].reverse().slice(0, 20);

  return (
    <Panel className="min-h-0 flex-1">
      <PanelHeader title="最近活动" subtitle={`本轮共 ${events.length} 条`} />
      <div className="min-h-0 flex-1 overflow-y-auto p-2">
        {recent.length === 0 ? (
          <EmptyState>本轮还没有操作。登记项目、扫描、生成提案、批准、落盘、提交都会记录在这里。</EmptyState>
        ) : (
          <ol className="space-y-1.5">
            {recent.map((event) => {
              const meta = LEVEL_META[event.level];
              return (
                <li key={event.id} className="rounded-md border border-line-soft bg-surface-2 px-2 py-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${meta.dot}`} aria-hidden="true" />
                      <span className="truncate text-[11px] font-medium text-text">{event.actor}</span>
                    </span>
                    <span className="shrink-0 font-mono text-[10px] text-faint">{formatClock(event.at)}</span>
                  </div>
                  <p className="mt-1 break-words text-[11px] leading-relaxed text-muted">{event.action}</p>
                  <p className={`mt-0.5 break-words text-[11px] leading-relaxed ${event.level === "error" ? "text-danger" : "text-faint"}`}>
                    {event.result}
                  </p>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </Panel>
  );
}