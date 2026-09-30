/** 顶部品牌栏：品牌名 + 环境/健康状态（database 与已配置 providers）。 */
import type { JSX } from "react";

import type { HealthData, ReadyData } from "../api/types";
import { Button, Spinner } from "./ui";

export function TopBar({
  health,
  ready,
  readyError,
  loading,
  onRefresh,
}: {
  health: HealthData | null;
  ready: ReadyData | null;
  readyError: string | null;
  loading: boolean;
  onRefresh: () => void;
}): JSX.Element {
  const databaseOk = ready?.database === true;
  const statusLabel = readyError
    ? "未就绪"
    : databaseOk
      ? "运行中"
      : ready
        ? "降级运行"
        : "检查中";
  const statusTone = readyError
    ? "bg-danger"
    : databaseOk
      ? "bg-add"
      : ready
        ? "bg-warn"
        : "bg-faint";

  return (
    <header className="flex shrink-0 items-center justify-between gap-4 border-b border-line bg-surface-1 px-4 py-2">
      <div className="flex items-center gap-3">
        <span className="flex h-7 w-7 items-center justify-center rounded-md bg-accent/15 text-accent">
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" aria-hidden="true">
            <path d="M4 5h9l3 3-3 3H4V5Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
            <path d="M20 19H11l-3-3 3-3h9v6Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
          </svg>
        </span>
        <div className="leading-tight">
          <p className="text-sm font-semibold tracking-wide text-text">Flux</p>
          <p className="text-[11px] text-faint">AI Engineering OS · 最小 IDE</p>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-end gap-x-4 gap-y-1 text-xs">
        <div className="flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full ${statusTone}`} aria-hidden="true" />
          <span className="text-muted">
            {health ? `${health.app} · ${health.env}` : "后端未连接"}
          </span>
          <span className="text-faint">|</span>
          <span className="text-text">{statusLabel}</span>
        </div>

        <div className="flex items-center gap-1.5">
          <span className="text-faint">数据库</span>
          <span className={databaseOk ? "text-add" : "text-danger"}>
            {databaseOk ? "已连通" : ready ? "不可用" : "未知"}
          </span>
        </div>

        <div className="flex min-w-0 items-center gap-1.5">
          <span className="text-faint">模型供应商</span>
          {ready && ready.providers.length > 0 ? (
            <span className="flex flex-wrap items-center gap-1">
              {ready.providers.map((provider) => (
                <span
                  key={provider}
                  className="rounded border border-line bg-surface-2 px-1.5 py-0.5 text-[11px] text-muted"
                >
                  {provider}
                </span>
              ))}
            </span>
          ) : (
            <span className="text-danger">未配置</span>
          )}
        </div>

        <div className="flex items-center gap-1.5">
          <span className="text-faint">Agent</span>
          <span className="text-text">{ready ? ready.agents : "—"}</span>
        </div>

        <Button tone="ghost" onClick={onRefresh} busy={loading} title="重新检查后端健康状态">
          {loading ? <Spinner /> : null}
          刷新状态
        </Button>
      </div>

      {readyError ? (
        <span className="hidden max-w-[18rem] truncate text-[11px] text-danger lg:block" title={readyError}>
          {readyError}
        </span>
      ) : null}
    </header>
  );
}