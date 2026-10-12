/**
 * 浮层组件：模态对话框与右侧抽屉。
 * 均监听 Esc 关闭并锁定背景滚动，点击遮罩关闭（可关）。
 */
import { useEffect } from "react";
import type { ReactNode } from "react";
import { Icon } from "./Icon";

function useEsc(onClose: () => void, open: boolean) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);
}

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  size?: "md" | "lg";
  footer?: ReactNode;
  children: ReactNode;
}
/** 居中模态框。 */
export function Modal({ open, onClose, title, size = "md", footer, children }: ModalProps) {
  useEsc(onClose, open);
  if (!open) return null;
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={["modal", size === "lg" && "lg"].filter(Boolean).join(" ")} role="dialog" aria-modal="true">
        {title ? (
          <div className="modal-head">
            <div className="section-title">{title}</div>
            <button className="btn quiet icon-only sm" onClick={onClose} aria-label="关闭">
              <Icon name="close" />
            </button>
          </div>
        ) : null}
        <div className="modal-body">{children}</div>
        {footer ? <div className="modal-foot">{footer}</div> : null}
      </div>
    </div>
  );
}

interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
  footer?: ReactNode;
}
/** 右侧抽屉。 */
export function Drawer({ open, onClose, title, children, footer }: DrawerProps) {
  useEsc(onClose, open);
  if (!open) return null;
  return (
    <div className="overlay" style={{ placeItems: "stretch", justifyContent: "flex-end" }} onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="drawer" role="dialog" aria-modal="true">
        <div className="modal-head">
          <div className="section-title">{title}</div>
          <button className="btn quiet icon-only sm" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </div>
        <div className="modal-body grow" style={{ overflowY: "auto" }}>
          {children}
        </div>
        {footer ? <div className="modal-foot">{footer}</div> : null}
      </div>
    </div>
  );
}
