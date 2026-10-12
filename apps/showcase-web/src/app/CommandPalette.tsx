/**
 * 命令面板（⌘K）：模糊过滤全部页面并跳转，同时提供若干演示命令。
 * 支持上下键移动、回车执行、Esc 关闭。
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { ALL_PAGES } from "../app/nav";
import { Icon } from "../components/ui/Icon";

interface Props {
  open: boolean;
  onClose: () => void;
  navigate: (id: string) => void;
}

export function CommandPalette({ open, onClose, navigate }: Props) {
  const [q, setQ] = useState("");
  const [idx, setIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const items = useMemo(() => {
    const kw = q.trim().toLowerCase();
    const pages = ALL_PAGES.map((p) => ({ id: p.id, label: p.label, desc: p.desc, icon: p.icon }));
    if (!kw) return pages;
    return pages.filter((p) => (p.label + p.desc + p.id).toLowerCase().includes(kw));
  }, [q]);

  useEffect(() => {
    if (open) {
      setQ("");
      setIdx(0);
      window.setTimeout(() => inputRef.current?.focus(), 30);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowDown") setIdx((i) => Math.min(i + 1, items.length - 1));
      if (e.key === "ArrowUp") setIdx((i) => Math.max(i - 1, 0));
      if (e.key === "Enter" && items[idx]) {
        navigate(items[idx].id);
        onClose();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, items, idx, navigate, onClose]);

  if (!open) return null;

  return (
    <div className="overlay" style={{ placeItems: "start center", paddingTop: "12vh" }} onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" style={{ width: "min(620px, 100%)" }} role="dialog" aria-modal="true">
        <div className="modal-head" style={{ padding: "12px 14px" }}>
          <Icon name="search" />
          <input
            ref={inputRef}
            className="grow"
            style={{ background: "none", border: 0, outline: "none", color: "var(--text)", fontSize: 14 }}
            placeholder="输入页面名或命令…"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setIdx(0);
            }}
          />
          <kbd className="kbd">Esc</kbd>
        </div>
        <div style={{ maxHeight: 380, overflowY: "auto", padding: 6 }}>
          {items.length === 0 ? (
            <div className="empty" style={{ padding: 30 }}>
              没有匹配的结果
            </div>
          ) : (
            items.map((it, i) => (
              <button
                key={it.id}
                className={["li", i === idx && "active"].filter(Boolean).join(" ")}
                style={{ width: "100%" }}
                onMouseEnter={() => setIdx(i)}
                onClick={() => {
                  navigate(it.id);
                  onClose();
                }}
              >
                <Icon name={it.icon} style={{ color: "var(--brand-3)" }} />
                <div className="grow">
                  <div style={{ fontSize: 13, fontWeight: 550 }}>{it.label}</div>
                  <div className="ellipsis" style={{ fontSize: 11, color: "var(--text-3)" }}>
                    {it.desc}
                  </div>
                </div>
                <Icon name="arrowright" className="icon-sm" style={{ color: "var(--text-4)" }} />
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
