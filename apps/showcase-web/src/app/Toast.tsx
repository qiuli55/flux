/**
 * 全局轻提示（Toast）：提供 useToast() 在任意页面弹出成功/失败/信息提示。
 * Provider 挂在应用根部，Host 负责渲染右下角堆栈。
 */
import { createContext, useCallback, useContext, useState } from "react";
import type { ReactNode } from "react";
import { Icon } from "../components/ui/Icon";

type Kind = "ok" | "err" | "info";
interface Item {
  id: number;
  kind: Kind;
  text: string;
}

const Ctx = createContext<(text: string, kind?: Kind) => void>(() => {});
export const useToast = () => useContext(Ctx);

const ICON: Record<Kind, string> = { ok: "check", err: "close", info: "sparkles" };

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Item[]>([]);

  const push = useCallback((text: string, kind: Kind = "ok") => {
    const id = Date.now() + Math.random();
    setItems((prev) => [...prev, { id, kind, text }]);
    window.setTimeout(() => setItems((prev) => prev.filter((i) => i.id !== id)), 3200);
  }, []);

  return (
    <Ctx.Provider value={push}>
      {children}
      <div className="toast-host">
        {items.map((i) => (
          <div key={i.id} className={["toast", i.kind].join(" ")}>
            <Icon name={ICON[i.kind]} />
            <span>{i.text}</span>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}
