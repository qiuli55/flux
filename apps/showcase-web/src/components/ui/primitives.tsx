/**
 * 基础原子组件：按钮、输入框、卡片、徽标、开关、头像、进度、分段控件等。
 * 所有组件均为无状态展示型（除受控开关/滑块），样式来自 ui.css 的 .btn/.input 等类。
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
export function Button({
  variant = "default",
  size = "md",
  icon,
  block,
  busy,
  className,
  children,
  ...rest
}: BtnProps) {
  const cls = [
    "btn",
    variant !== "default" && variant,
    size !== "md" && size,
    block && "block",
    !children && "icon-only",
    className,
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <button className={cls} aria-busy={busy} {...rest}>
      {busy ? <span className="spin icon icon-sm" /> : icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}

/* ---------------- 输入 ---------------- */
interface FieldProps {
  label?: string;
  required?: boolean;
  hint?: string;
  children: ReactNode;
}
/** 表单字段容器：标签 + 控件 + 提示。 */
export function Field({ label, required, hint, children }: FieldProps) {
  return (
    <div className="field">
      {label ? (
        <label>
          {label}
          {required ? <span className="req">*</span> : null}
        </label>
      ) : null}
      {children}
      {hint ? <span className="hint">{hint}</span> : null}
    </div>
  );
}

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  icon?: string;
  trail?: ReactNode;
}
/** 单行输入框，可选左置图标与尾部插槽。 */
export function Input({ icon, trail, className, ...rest }: InputProps) {
  const input = <input className={["input", icon && "with-icon", className].filter(Boolean).join(" ")} {...rest} />;
  if (!icon && !trail) return input;
  return (
    <div className="input-wrap">
      {icon ? <Icon name={icon} /> : null}
      {input}
      {trail ? <span className="trail">{trail}</span> : null}
    </div>
  );
}

/** 多行文本域。 */
export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className="textarea" {...props} />;
}

/* ---------------- 展示 ---------------- */
interface BadgeProps {
  tone?: "brand" | "green" | "orange" | "red" | "blue" | "default";
  dot?: boolean;
  children: ReactNode;
}
/** 状态徽标，tone 决定颜色语义。 */
export function Badge({ tone = "default", dot, children }: BadgeProps) {
  return (
    <span className={["badge", tone !== "default" && tone].filter(Boolean).join(" ")}>
      {dot ? <i className="dot" /> : null}
      {children}
    </span>
  );
}

interface CardProps {
  children: ReactNode;
  className?: string;
  pad?: boolean;
  hover?: boolean;
  style?: CSSProperties;
  onClick?: () => void;
}
/** 卡片容器。 */
export function Card({ children, className, pad, hover, style, onClick }: CardProps) {
  return (
    <div
      className={["card", pad && "pad", hover && "hover", className].filter(Boolean).join(" ")}
      style={style}
      onClick={onClick}
    >
      {children}
    </div>
  );
}

/** 进度条，value 取 0-100。 */
export function Progress({ value, thin }: { value: number; thin?: boolean }) {
  return (
    <div className={["progress", thin && "thin"].filter(Boolean).join(" ")}>
      <i style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  );
}

/** 圆形头像，按名字取首字母。tone 0/undefined 用品牌渐变，1/2 用备用渐变。 */
export function Avatar({ name, size, tone }: { name: string; size?: "sm" | "lg"; tone?: 0 | 1 | 2 }) {
  const cls = ["avatar", size, tone === 1 && "alt", tone === 2 && "alt2"].filter(Boolean).join(" ");
  return <span className={cls}>{name.slice(0, 1)}</span>;
}

/** 键位徽章。 */
export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>;
}

/** 开关（受控）。 */
export function Switch({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return <button type="button" className={["switch", on && "on"].filter(Boolean).join(" ")} aria-pressed={on} onClick={() => onChange(!on)} />;
}

/** 分段控件（受控）。 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? "active" : ""} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** 复选框。 */
export function Check({ label, checked, onChange }: { label: string; checked?: boolean; onChange?: (v: boolean) => void }) {
  return (
    <label className="check">
      <input type="checkbox" checked={checked} onChange={(e) => onChange?.(e.target.checked)} />
      {label}
    </label>
  );
}

/** 空状态占位。 */
export function Empty({ icon = "layers", title, desc }: { icon?: string; title: string; desc?: string }) {
  return (
    <div className="empty">
      <Icon name={icon} className="ic" />
      <div style={{ fontWeight: 600, color: "var(--text-2)" }}>{title}</div>
      {desc ? <div style={{ fontSize: 12 }}>{desc}</div> : null}
    </div>
  );
}

/** 悬停气泡提示。 */
export function Tip({ text, children }: { text: string; children: ReactNode }) {
  return (
    <span className="tip">
      {children}
      <span className="tip-body">{text}</span>
    </span>
  );
}

/** 统计卡片。 */
export function Stat({
  label,
  value,
  unit,
  delta,
  icon,
}: {
  label: string;
  value: string | number;
  unit?: string;
  delta?: { dir: "up" | "down"; text: string };
  icon?: string;
}) {
  return (
    <div className="stat">
      <div className="k">
        {icon ? <Icon name={icon} className="icon-sm" /> : null}
        {label}
      </div>
      <div className="v">
        {value}
        {unit ? <small>{unit}</small> : null}
      </div>
      {delta ? (
        <span className={["delta", delta.dir].join(" ")}>
          <Icon name={delta.dir === "up" ? "activity" : "activity"} className="icon-sm" />
          {delta.text}
        </span>
      ) : null}
    </div>
  );
}
