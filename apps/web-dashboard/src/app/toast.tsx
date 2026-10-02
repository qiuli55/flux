/**
 * 全局提示（与设计稿 v2 的 toast 一致：底部居中，自动消失）。
 *
 * 做成模块级单例，任意组件都能像设计稿里那样直接调用 toast("…")。
 * 提示分两类（F-02）：info 2 秒自动消失；error 带图标、强对比配色并停留 4.5 秒，
 * 错误不靠文案单独区分。
 */
import { useEffect, useState } from "react";

export type ToastKind = "info" | "error";

type Listener = (message: string, kind: ToastKind) => void;

let listener: Listener | null = null;
let timer: number | undefined;

/** 弹一条提示；同一时刻只显示最新一条 */
export function toast(message: string, kind: ToastKind = "info"): void {
  if (!listener) return;
  listener(message, kind);
  window.clearTimeout(timer);
  timer = window.setTimeout(() => listener?.("", "info"), kind === "error" ? 4500 : 2000);
}

export function ToastHost() {
  const [state, setState] = useState<{ message: string; kind: ToastKind }>({
    message: "",
    kind: "info",
  });

  useEffect(() => {
    listener = (message, kind) => setState({ message, kind });
    return () => {
      listener = null;
    };
  }, []);

  const visible = state.message !== "";
  return (
    <div
      className={`toast${visible ? "" : " is-hidden"}${visible && state.kind === "error" ? " is-error" : ""}`}
      role="status"
      aria-live="polite"
    >
      {visible && state.kind === "error" ? <span className="toast-ic">!</span> : null}
      <span className="toast-tx">{state.message}</span>
    </div>
  );
}