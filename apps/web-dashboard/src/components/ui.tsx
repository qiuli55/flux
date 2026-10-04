/** 通用展示组件：面板、按钮、状态徽章、错误提示等（全站复用）。 */
import type { JSX, ReactNode } from "react";

import type { ChangeStatus } from "../api/types";

/** 面板外壳：统一边框、圆角、底色 */
export function Panel({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}): JSX.Element {
  return (
    <section
      className={`flex min-h-0 flex-col rounded-lg border border-line bg-surface-1 ${className}`}
    >
      {children}
    </section>
  );
}

/** 面板标题栏 */
export function PanelHeader({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}): JSX.Element {
  return (
    <header className="flex shrink-0 items-center justify-between gap-3 border-b border-line-soft px-3 py-2">
      <div className="min-w-0">
        <h2 className="truncate text-sm font-semibold text-text">{title}</h2>
        {subtitle ? <p className="mt-0.5 truncate text-xs text-faint">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  );
}

type ButtonTone = "primary" | "default" | "danger" | "ghost";

const BUTTON_TONES: Record<ButtonTone, string> = {
  primary: "bg-accent text-accent-ink hover:bg-accent/85 border-transparent",
  default: "bg-surface-3 text-text hover:bg-surface-2 border-line",
  danger: "bg-transparent text-danger hover:bg-danger/10 border-danger/40",
  ghost: "bg-transparent text-muted hover:text-text hover:bg-surface-2 border-transparent",
};

/** 按钮：忙碌时禁用并显示加载文案 */
export function Button({
  children,
  onClick,
  disabled = false,
  busy = false,
  tone = "default",
  title,
  className = "",
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  busy?: boolean;
  tone?: ButtonTone;
  title?: string;
  className?: string;
  /** 放在 <form> 里当作提交按钮时必须显式传 "submit"（默认 button 不会触发表单提交） */
  type?: "button" | "submit";
}): JSX.Element {
  const isDisabled = disabled || busy;
  return (
    <button
      type={type}
      title={title}
      onClick={onClick}
      disabled={isDisabled}
      className={`inline-flex items-center justify-center gap-1.5 rounded-md border px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-45 ${BUTTON_TONES[tone]} ${className}`}
    >
      {busy ? <Spinner /> : null}
      {children}
    </button>
  );
}

/** 加载指示器（手写 SVG，不引图标库） */
export function Spinner({ className = "" }: { className?: string }): JSX.Element {
  return (
    <svg className={`h-3.5 w-3.5 animate-spin ${className}`} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" strokeOpacity="0.25" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/** 变更状态 → 中文标签（下拉框等纯文本场景也复用同一套文案） */
export const CHANGE_STATUS_LABELS: Record<ChangeStatus, string> = {
  pending: "待审阅",
  accepted: "已批准",
  rejected: "已拒绝",
  applied: "已落盘",
  failed: "落盘失败",
  expired: "已失效",
  rolled_back: "已回滚",
};

/** 变更状态徽章（主规格 §7.2 状态机；expired 为 P0-02 的失效终态，rolled_back 为 P1-2 回滚终态） */
const CHANGE_STATUS_META: Record<ChangeStatus, { label: string; className: string }> = {
  pending: { label: CHANGE_STATUS_LABELS.pending, className: "text-warn border-warn/40 bg-warn/10" },
  accepted: { label: CHANGE_STATUS_LABELS.accepted, className: "text-info border-info/40 bg-info/10" },
  rejected: { label: CHANGE_STATUS_LABELS.rejected, className: "text-faint border-line bg-surface-2" },
  applied: { label: CHANGE_STATUS_LABELS.applied, className: "text-add border-add/40 bg-add/10" },
  failed: { label: CHANGE_STATUS_LABELS.failed, className: "text-danger border-danger/40 bg-danger/10" },
  expired: { label: CHANGE_STATUS_LABELS.expired, className: "text-faint border-line bg-surface-2" },
  rolled_back: { label: CHANGE_STATUS_LABELS.rolled_back, className: "text-faint border-line bg-surface-2" },
};

export function ChangeStatusBadge({ status }: { status: ChangeStatus }): JSX.Element {
  const meta = CHANGE_STATUS_META[status];
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-medium ${meta.className}`}
    >
      {meta.label}
    </span>
  );
}

/** 通用小徽章 */
export function Tag({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}): JSX.Element {
  return (
    <span
      className={`inline-flex items-center rounded border border-line bg-surface-2 px-1.5 py-0.5 text-[11px] text-muted ${className}`}
    >
      {children}
    </span>
  );
}

/** 错误提示条：直接展示后端 message 原文 */
export function ErrorBanner({
  message,
  onDismiss,
}: {
  message: string;
  onDismiss?: () => void;
}): JSX.Element {
  return (
    <div className="flex items-start gap-2 rounded-md border border-danger/40 bg-danger/10 px-2.5 py-1.5 text-xs text-danger">
      <svg viewBox="0 0 24 24" className="mt-0.5 h-3.5 w-3.5 shrink-0" fill="none" aria-hidden="true">
        <path
          d="M12 8v5m0 3.5h.01M10.3 3.9 2.5 18a1.8 1.8 0 0 0 1.6 2.7h15.8A1.8 1.8 0 0 0 21.5 18L13.7 3.9a1.8 1.8 0 0 0-3.4 0Z"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span className="min-w-0 flex-1 break-words whitespace-pre-wrap">{message}</span>
      {onDismiss ? (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 rounded px-1 text-danger/80 hover:bg-danger/20 hover:text-danger"
          aria-label="关闭提示"
        >
          ×
        </button>
      ) : null}
    </div>
  );
}

/** 空态占位（说明"为什么现在是空的"，不是假数据） */
export function EmptyState({ children }: { children: ReactNode }): JSX.Element {
  return (
    <div className="flex flex-1 items-center justify-center px-4 py-6 text-center text-xs leading-relaxed text-faint">
      <p className="max-w-[22rem]">{children}</p>
    </div>
  );
}

/** 「键 : 值」小网格项 */
export function MetaField({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}): JSX.Element {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-faint">{label}</dt>
      <dd className="mt-0.5 truncate text-xs text-text">{children}</dd>
    </div>
  );
}

/** 文段（用于 summary / reason 这类可能为空的文本） */
export function Paragraph({ label, text }: { label: string; text: string | null }): JSX.Element {
  return (
    <div>
      <p className="text-[11px] text-faint">{label}</p>
      <p className="mt-0.5 text-xs leading-relaxed break-words whitespace-pre-wrap text-muted">
        {text && text.trim() ? text : "（后端未提供）"}
      </p>
    </div>
  );
}
