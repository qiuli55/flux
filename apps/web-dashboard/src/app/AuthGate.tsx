/**
 * 登录路由守卫：没登录就把浏览器送去账号云登录页。
 *
 * 闭环是「账号云签发一次性 code → 回跳带 code → 本机换令牌」；
 * 后端没配账号云时直接放行，本地开发不受影响（判定见 `api/auth.ts`）。
 */
import { useEffect, useState, type ReactNode } from "react";
import { bootstrapAuth, type AuthBootstrap } from "../api/auth";

export function AuthGate({ children }: { children: ReactNode }) {
  const [outcome, setOutcome] = useState<AuthBootstrap | null>(null);

  useEffect(() => {
    let alive = true;
    void bootstrapAuth().then((result) => {
      if (alive) setOutcome(result);
    });
    return () => {
      alive = false;
    };
  }, []);

  if (outcome === "authenticated" || outcome === "no-cloud") return <>{children}</>;

  // 剩余两种情况都停在占位：一项是还在检查，另一项已经在跳登录页的路上。
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: "flex",
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        opacity: 0.6,
      }}
    >
      正在检查登录状态…
    </div>
  );
}
