/**
 * 底部 dock · 变更清单：状态过滤 chips + 提案列表。
 *
 * 从原 ChangeReview 里抽出来的只是「列表部分」——选中、过滤、刷新仍然走 App 的既有
 * handler（handleSelectChange / setFilter / loadChanges），这里不碰任何请求逻辑。
 */
import type { JSX } from "react";

import type { Change, ChangeStatus } from "../api/types";
import { shortId } from "../utils/format";
import { Button, ChangeStatusBadge, ErrorBanner } from "./ui";

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: "", label: "全部" },
  { value: "pending", label: "待审阅" },
  { value: "accepted", label: "已批准" },
  { value: "applied", label: "已落盘" },
  { value: "rejected", label: "已拒绝" },
  { value: "failed", label: "落盘失败" },
];

function countByStatus(changes: Change[], status: ChangeStatus): number {
  return changes.filter((change) => change.status === status).length;
}

export function ChangeList({
  changes,
  filter,
  onFilterChange,
  listBusy,
  listError,
  onDismissListError,
  onReload,
  selectedId,
  onSelect,
}: {
  changes: Change[];
  filter: string;
  onFilterChange: (value: string) => void;
  listBusy: boolean;
  listError: string | null;
  onDismissListError: () => void;
  onReload: () => void;
  selectedId: string | null;
  onSelect: (changeId: string) => void;
}): JSX.Element {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-1 border-b border-line-soft px-3 py-1.5">
        {STATUS_FILTERS.map((item) => {
          const count = item.value === "" ? changes.length : countByStatus(changes, item.value as ChangeStatus);
          const active = filter === item.value;
          return (
            <button
              key={item.value || "all"}
              type="button"
              onClick={() => onFilterChange(item.value)}
              className={`rounded-full border px-2.5 py-0.5 text-[11px] transition-colors ${
                active ? "border-accent/50 bg-accent/15 text-accent" : "border-line bg-surface-2 text-muted hover:text-text"
              }`}
            >
              {item.label} {count}
            </button>
          );
        })}
        <Button tone="ghost" className="ml-auto" onClick={onReload} busy={listBusy} title="重新拉取变更列表">
          刷新列表
        </Button>
      </div>

      {listError ? (
        <div className="shrink-0 px-3 pt-2">
          <ErrorBanner message={listError} onDismiss={onDismissListError} />
        </div>
      ) : null}

      <ul className="min-h-0 flex-1 overflow-y-auto p-2">
        {changes.length === 0 ? (
          <li className="px-1 py-2 text-xs text-faint">当前过滤条件下没有变更。</li>
        ) : (
          changes.map((change) => {
            const active = change.id === selectedId;
            return (
              <li key={change.id} className="mb-1">
                <button
                  type="button"
                  onClick={() => onSelect(change.id)}
                  className={`flex w-full flex-wrap items-center gap-x-2 gap-y-1 rounded-md border px-2 py-1.5 text-left transition-colors ${
                    active ? "border-accent/50 bg-accent/10" : "border-line bg-surface-2 hover:bg-surface-3"
                  }`}
                >
                  <ChangeStatusBadge status={change.status} />
                  <span className="truncate font-mono text-[11px] text-text">{change.file_path}</span>
                  <span className="text-[10px] text-add">+{change.added_lines}</span>
                  <span className="text-[10px] text-del">-{change.removed_lines}</span>
                  <span className="text-[10px] text-faint">#{shortId(change.id)}</span>
                  {change.summary ? (
                    <span className="min-w-0 flex-1 truncate text-right text-[11px] text-faint">{change.summary}</span>
                  ) : null}
                </button>
              </li>
            );
          })
        )}
      </ul>
    </div>
  );
}
