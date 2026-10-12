/**
 * 浮层组件：Modal（居中对话框）与 Drawer（右侧抽屉）。
 * 通过 Portal 挂到 body，Esc 关闭、点击遮罩关闭；头部/底部插槽按需传入。
 */
import { useEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Icon } from "./Icon";

function useEsc(onClose: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [active, onClose]);
}

interface ModalProps {
  open: boolean;
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  large?: boolean;
}

/** 居中模态框。 */
export function Modal({ open, title, onClose, children, footer, large }: ModalProps) {
  useEsc(onClose, open);
  if (!open) return null;
  return createPortal(
    <div className="fx-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={["fx-modal", large && "fx-lg"].filter(Boolean).join(" ")} role="dialog" aria-modal="true">
        <div className="fx-modal-head">
          <div className="fx-section-title">{title}</div>
          <button className="fx-iconbtn" onClick={onClose} aria-label="关闭"><Icon name="close" /></button>
        </div>
        <div className="fx-modal-body">{children}</div>
        {footer ? <div className="fx-modal-foot">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

interface DrawerProps {
  open: boolean;
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

/** 右侧抽屉。 */
export function Drawer({ open, title, onClose, children, footer }: DrawerProps) {
  useEsc(onClose, open);
  if (!open) return null;
  return createPortal(
    <div className="fx-overlay" style={{ display: "flex", justifyContent: "flex-end", padding: 0 }} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="fx-drawer" role="dialog" aria-modal="true">
        <div className="fx-modal-head">
          <div className="fx-section-title">{title}</div>
          <button className="fx-iconbtn" onClick={onClose} aria-label="关闭"><Icon name="close" /></button>
        </div>
        <div className="fx-modal-body">{children}</div>
        {footer ? <div className="fx-modal-foot">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  );
}
