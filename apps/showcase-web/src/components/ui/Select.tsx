/**
 * 自定义下拉选择器：点击展开、点击外部收起、选中项高亮。
 * 用真实 button + 绝对定位菜单实现，避免使用原生 select 的模板观感。
 */
import { useEffect, useRef, useState } from "react";
import { Icon } from "./Icon";

export interface Option<T extends string = string> {
  value: T;
  label: string;
  meta?: string;
}

interface Props<T extends string> {
  value: T;
  options: Option<T>[];
  onChange: (v: T) => void;
  placeholder?: string;
  up?: boolean;
  width?: number | string;
}

export function Select<T extends string>({ value, options, onChange, placeholder, up, width }: Props<T>) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const cur = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, [open]);

  return (
    <div ref={ref} className={["select", open && "open"].filter(Boolean).join(" ")} style={width ? { minWidth: width } : undefined}>
      <button type="button" className="select-trigger" aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <span className="ellipsis">{cur ? cur.label : placeholder ?? "请选择"}</span>
        <Icon name="chevdown" className="chev icon-sm" />
      </button>
      {open ? (
        <div className={["select-menu", up && "up"].filter(Boolean).join(" ")} role="listbox">
          {options.map((o) => (
            <button
              key={o.value}
              role="option"
              aria-selected={o.value === value}
              className={["select-opt", o.value === value && "active"].filter(Boolean).join(" ")}
              onClick={() => {
                onChange(o.value);
                setOpen(false);
              }}
            >
              <span>{o.label}</span>
              {o.meta ? <span className="meta">{o.meta}</span> : null}
              {o.value === value ? <Icon name="check" className="icon-sm" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
