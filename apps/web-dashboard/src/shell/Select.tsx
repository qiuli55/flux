/**
 * 下拉选择器：受控组件，点击展开菜单，支持外部点击与 Esc 关闭、上下键导航。
 * 用于外壳内的项目/模型/主题等切换，样式类统一 fx-select-*。
 */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "./Icon";

export interface Option<T extends string> {
  value: T;
  label: string;
  meta?: string;
  icon?: ReactNode;
}

interface Props<T extends string> {
  value: T;
  options: Option<T>[];
  onChange: (v: T) => void;
  placeholder?: string;
  up?: boolean;
  width?: number | string;
}

/** 通用下拉选择器。 */
export function Select<T extends string>({ value, options, onChange, placeholder = "请选择", up, width }: Props<T>) {
  const [open, setOpen] = useState(false);
  const [hover, setHover] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const cur = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
      if (e.key === "ArrowDown") { e.preventDefault(); setHover((h) => Math.min(h + 1, options.length - 1)); }
      if (e.key === "ArrowUp") { e.preventDefault(); setHover((h) => Math.max(h - 1, 0)); }
      if (e.key === "Enter" && options[hover]) { onChange(options[hover].value); setOpen(false); }
    };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("mousedown", onDoc); document.removeEventListener("keydown", onKey); };
  }, [open, hover, options, onChange]);

  return (
    <div ref={box} className={["fx-select", open && "fx-open"].filter(Boolean).join(" ")} style={{ width }}>
      <button type="button" className="fx-select-trigger" onClick={() => setOpen((v) => !v)}>
        {cur?.icon}
        <span className="fx-ellipsis">{cur?.label ?? placeholder}</span>
        <Icon name="chevdown" className="fx-icon-sm fx-chev" />
      </button>
      {open ? (
        <div className={["fx-select-menu", up && "fx-up"].filter(Boolean).join(" ")}>
          {options.map((o, i) => (
            <button
              key={o.value}
              className={["fx-select-opt", o.value === value && "fx-active"].join(" ")}
              onMouseEnter={() => setHover(i)}
              onClick={() => { onChange(o.value); setOpen(false); }}
            >
              {o.icon}
              <span className="fx-ellipsis">{o.label}</span>
              {o.meta ? <span className="fx-meta">{o.meta}</span> : null}
              {o.value === value ? <Icon name="check" className="fx-icon-sm" style={{ color: "var(--fx-brand)" }} /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
