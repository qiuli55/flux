/**
 * 本轮操作的事件日志。
 *
 * 每条事件同时喂给右侧「最近活动」（倒序、带角色）与底部「任务时间线」（顺序、带结果）。
 * 只记录本轮真实发生的用户操作与接口结果，不预置任何假数据。
 */

export type EventLevel = "info" | "ok" | "error" | "running";

export interface LogEvent {
  id: string;
  /** ISO 时间戳 */
  at: string;
  /** 触发者：用户 / Agent 名称 / 系统 */
  actor: string;
  /** 动作描述 */
  action: string;
  /** 结果描述 */
  result: string;
  level: EventLevel;
}

let sequence = 0;

/** 生成一条事件（时间戳取当前时刻） */
export function createEvent(
  actor: string,
  action: string,
  result: string,
  level: EventLevel = "info",
): LogEvent {
  sequence += 1;
  return {
    id: `${Date.now()}-${sequence}`,
    at: new Date().toISOString(),
    actor,
    action,
    result,
    level,
  };
}

/** 事件级别 → 徽章配色与文案 */
export const LEVEL_META: Record<EventLevel, { label: string; className: string; dot: string }> = {
  info: { label: "已执行", className: "text-info border-info/40 bg-info/10", dot: "bg-info" },
  ok: { label: "成功", className: "text-add border-add/40 bg-add/10", dot: "bg-add" },
  error: { label: "失败", className: "text-danger border-danger/40 bg-danger/10", dot: "bg-danger" },
  running: { label: "进行中", className: "text-accent border-accent/40 bg-accent/10", dot: "bg-accent animate-pulse" },
};