/**
 * 全局外壳的基础原子组件：按钮、输入、卡片、徽标、开关、头像、进度、分段控件等。
 * 从 showcase 设计稿移植，类名统一 fx- 前缀以隔离 design-v2；均无状态（开关/滑块受控）。
 */
import type { ButtonHTMLAttributes, CSSProperties, InputHTMLAttributes, ReactNode, TextareaHTMLAttributes } from "react";
import { Icon } from "./Icon";

/* ---------------- 按钮 ---------------- */
type Variant = "default" | "primary" | "ghost" | "subtle" | "danger" | "quiet";

interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: "sm" | "md" | "lg";
  icon?: string;
  block?: boolean;
  busy?: boolean;
}

/** 通用按钮：variant 决定视觉层级，icon 为左置图标名。 */
export function Button({ variant = "default", size = "md", icon, block, busy, className, children, ...rest }: BtnProps) {
  const cls = [
    "fx-btn",
    variant !== "default" && `fx-${variant}`,
    size !== "md" && `fx-${size}`,
    block && "fx-block",
    !children && "fx-icon-only",
    className,
  ].filter(Boolean).join(" ");
  return (
    <button className={cls} aria-busy={busy} {...rest}>
      {busy ? <Icon name="refresh" className="fx-spin" /> : icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}

/* ---------------- 输入 ---------------- */
interface FieldProps { label?: string; required?: boolean; hint?: string; children: ReactNode }

/** 表单字段容器：标签 + 控件 + 提示。 */
export function Field({ label, required, hint, children }: FieldProps) {
  return (
    <div className="fx-field">
      {label ? <label>{label}{required ? <span className="fx-req">*</span> : null}</label> : null}
      {children}
      {hint ? <span className="fx-hint">{hint}</span> : null}
    </div>
  );
}

interface InputProps extends InputHTMLAttributes<HTMLInputElement> { icon?: string; trail?: ReactNode }

/** 单行输入框，可选左置图标与尾部插槽。 */
export function Input({ icon, trail, className, ...rest }: InputProps) {
  const input = <input className={["fx-input", className].filter(Boolean).join(" ")} {...rest} />;
  if (!icon && !trail) return input;
  return (
    <div className="fx-input-wrap">
      {icon ? <Icon name={icon} /> : null}
      {input}
      {trail ? <span className="fx-trail">{trail}</span> : null}
    </div>
  );
}

/** 多行文本域。 */
export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className="fx-textarea" {...props} />;
}

/* ---------------- 展示 ---------------- */
interface BadgeProps { tone?: "brand" | "green" | "orange" | "red" | "blue" | "default"; dot?: boolean; children: ReactNode }

/** 状态徽标，tone 决定颜色语义。 */
export function Badge({ tone = "default", dot, children }: BadgeProps) {
  return (
    <span className={["fx-badge", tone !== "default" && `fx-${tone}`].filter(Boolean).join(" ")}>
      {dot ? <i className="fx-dot" /> : null}
      {children}
    </span>
  );
}

interface CardProps { children: ReactNode; className?: string; pad?: boolean; hover?: boolean; style?: CSSProperties; onClick?: () => void }

/** 卡片容器。 */
export function Card({ children, className, pad, hover, style, onClick }: CardProps) {
  return (
    <div className={["fx-card", pad && "fx-pad", hover && "fx-hover", className].filter(Boolean).join(" ")} style={style} onClick={onClick}>
      {children}
    </div>
  );
}

/** 进度条，value 取 0-100。 */
export function Progress({ value, thin }: { value: number; thin?: boolean }) {
  return (
    <div className={["fx-progress", thin && "fx-thin"].filter(Boolean).join(" ")}>
      <i style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  );
}

/** 圆形头像，按名字取首字母；tone 决定备用渐变。 */
export function Avatar({ name, size, tone }: { name: string; size?: "sm" | "lg"; tone?: 0 | 1 | 2 }) {
  const cls = ["fx-avatar", size && `fx-${size}`, tone === 1 && "fx-alt", tone === 2 && "fx-alt2"].filter(Boolean).join(" ");
  return <span className={cls}>{(name || "?").slice(0, 1)}</span>;
}

/** 键位徽章。 */
export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="fx-kbd">{children}</kbd>;
}

/** 开关（受控）。 */
export function Switch({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return <button type="button" className={["fx-switch", on && "fx-on"].filter(Boolean).join(" ")} aria-pressed={on} onClick={() => onChange(!on)} />;
}

/** 分段控件（受控）。 */
export function Segmented<T extends string>({ value, options, onChange }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div className="fx-segmented">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? "fx-active" : ""} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}

/** 空状态占位。 */
export function Empty({ icon = "layers", title, desc }: { icon?: string; title: string; desc?: string }) {
  return (
    <div className="fx-empty">
      <Icon name={icon} />
      <div style={{ fontWeight: 600, color: "var(--fx-text-2)" }}>{title}</div>
      {desc ? <div style={{ fontSize: 12 }}>{desc}</div> : null}
    </div>
  );
}

/** 悬停气泡提示。 */
export function Tip({ text, children }: { text: string; children: ReactNode }) {
  return <span className="fx-tip">{children}<span className="fx-tip-body">{text}</span></span>;
}

/** 统计卡片。 */
export function Stat({ label, value, unit, delta, icon }: { label: string; value: string | number; unit?: string; delta?: { dir: "up" | "down"; text: string }; icon?: string }) {
  return (
    <div className="fx-stat">
      <div className="fx-k">{icon ? <Icon name={icon} className="fx-icon-sm" /> : null}{label}</div>
      <div className="fx-v">{value}{unit ? <small>{unit}</small> : null}</div>
      {delta ? <span className={["fx-delta", `fx-${delta.dir}`].join(" ")}><Icon name="activity" className="fx-icon-sm" />{delta.text}</span> : null}
    </div>
  );
}

/** 页面头部：标题 + 描述 + 右侧操作区。 */
export function PageHead({ title, desc, children }: { title: string; desc?: string; children?: ReactNode }) {
  return (
    <div className="fx-page-head">
      <div>
        <h1 className="fx-page-title">{title}</h1>
        {desc ? <p className="fx-page-desc">{desc}</p> : null}
      </div>
      {children ? <div className="fx-page-tools">{children}</div> : null}
    </div>
  );
}

/** 区块标题。 */
export function SectionTitle({ children }: { children: ReactNode }) {
  return <div className="fx-section-title">{children}</div>;
}
