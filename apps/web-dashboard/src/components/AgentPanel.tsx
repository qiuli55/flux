/** 右栏 · AI 团队：真实 Agent 列表（字段对齐 §13.3）与「装配 AI 团队」入口。 */
import type { JSX } from "react";

import type { AgentHandle } from "../api/types";
import { ROLE_LABELS, STATE_LABELS } from "../data/team";
import { Button, EmptyState, ErrorBanner, Panel, PanelHeader, Tag } from "./ui";

/** AgentState → 状态点配色 */
const STATE_DOTS: Record<string, string> = {
  CREATED: "bg-faint",
  INITIALIZING: "bg-info animate-pulse",
  READY: "bg-add",
  RUNNING: "bg-accent animate-pulse",
  WAITING_TOOL: "bg-warn",
  REVIEWING: "bg-info",
  COMPLETED: "bg-add",
  FAILED: "bg-danger",
  STOPPED: "bg-faint",
};

/** 一个 Agent 的紧凑卡片（§13.3：名称、角色、当前状态、模型、token、成本、状态点） */
function AgentCard({ agent }: { agent: AgentHandle }): JSX.Element {
  const stateLabel = STATE_LABELS[agent.state] ?? agent.state;
  return (
    <li className="rounded-md border border-line bg-surface-2 p-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-xs font-semibold text-text">{agent.spec.name}</p>
          <p className="truncate text-[11px] text-faint">
            {ROLE_LABELS[agent.spec.role] ?? agent.spec.role} · {agent.spec.role}
          </p>
        </div>
        <span className="flex shrink-0 items-center gap-1.5">
          <span className={`h-2 w-2 rounded-full ${STATE_DOTS[agent.state] ?? "bg-faint"}`} aria-hidden="true" />
          <span className="text-[11px] text-muted">{stateLabel}</span>
        </span>
      </div>

      {agent.spec.description ? (
        <p className="mt-1 break-words text-[11px] text-muted">{agent.spec.description}</p>
      ) : null}

      <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
        <div>
          <dt className="text-faint">模型</dt>
          <dd className="truncate text-text">
            {agent.spec.model_provider} / {agent.spec.model_name}
          </dd>
        </div>
        <div>
          <dt className="text-faint">已执行</dt>
          <dd className="text-text">{agent.execution_count} 次</dd>
        </div>
        <div className="col-span-2">
          <dt className="text-faint">Token 用量 / 成本</dt>
          <dd className="text-faint">后端 Agent 接口未提供该字段</dd>
        </div>
      </dl>

      {agent.spec.permissions.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-1">
          {agent.spec.permissions.map((permission) => (
            <Tag key={permission}>{permission}</Tag>
          ))}
        </div>
      ) : null}

      {agent.last_error ? (
        <p className="mt-2 break-words text-[11px] text-danger">最近错误：{agent.last_error}</p>
      ) : null}
    </li>
  );
}

export function AgentPanel({
  agents,
  loading,
  error,
  onDismissError,
  onRefresh,
  onAssemble,
  assembleBusy,
}: {
  agents: AgentHandle[];
  loading: boolean;
  error: string | null;
  onDismissError: () => void;
  onRefresh: () => void;
  onAssemble: () => void;
  assembleBusy: boolean;
}): JSX.Element {
  const online = agents.filter((agent) => agent.state !== "FAILED" && agent.state !== "STOPPED").length;

  return (
    <Panel className="min-h-0 flex-1">
      <PanelHeader
        title="AI 团队"
        subtitle={agents.length > 0 ? `${online}/${agents.length} 在线` : "尚未装配"}
        actions={
          <>
            <Button tone="ghost" onClick={onRefresh} busy={loading} title="重新拉取 Agent 列表">
              刷新
            </Button>
            <Button tone="primary" onClick={onAssemble} busy={assembleBusy} title="依次创建 4 个内置角色">
              装配 AI 团队
            </Button>
          </>
        }
      />

      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-3">
        {error ? <ErrorBanner message={error} onDismiss={onDismissError} /> : null}

        {agents.length === 0 ? (
          <EmptyState>
            {loading
              ? "正在拉取 Agent 列表…"
              : "GET /api/v1/agents 只返回用户创建过的 Agent。点「装配 AI 团队」创建 Tech Lead / Developer / Reviewer / Tester 四个内置角色。"}
          </EmptyState>
        ) : (
          <ul className="space-y-2">
            {agents.map((agent) => (
              <AgentCard key={agent.id} agent={agent} />
            ))}
          </ul>
        )}
      </div>
    </Panel>
  );
}

/** 左栏底部的团队运行脉冲卡（展示真实在线数与最新一条动态） */
export function TeamPulse({
  agents,
  latest,
}: {
  agents: AgentHandle[];
  latest: string | null;
}): JSX.Element {
  const online = agents.filter((agent) => agent.state !== "FAILED" && agent.state !== "STOPPED").length;
  const total = agents.length;
  return (
    <div className="shrink-0 rounded-lg border border-line bg-surface-1 p-3">
      <div className="flex items-center gap-2">
        <span className={`h-2 w-2 rounded-full ${total > 0 ? "bg-accent animate-pulse" : "bg-faint"}`} aria-hidden="true" />
        <p className="text-xs font-semibold text-text">
          {total > 0 ? "AI 工程团队运行中" : "AI 工程团队待装配"}
        </p>
      </div>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        {total > 0
          ? `${online}/${total} 个 Agent 在线。`
          : "尚未创建 Agent，点右栏「装配 AI 团队」创建内置角色。"}
      </p>
      {latest ? <p className="mt-1 truncate text-[11px] text-faint">最近：{latest}</p> : null}
      <div className="mt-2 h-1 overflow-hidden rounded-full bg-surface-3">
        <div
          className="h-full rounded-full bg-accent transition-all"
          style={{ width: total > 0 ? `${Math.round((online / total) * 100)}%` : "0%" }}
        />
      </div>
    </div>
  );
}