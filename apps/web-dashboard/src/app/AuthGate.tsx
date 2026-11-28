/**
 * 登录路由守卫：没登录就把浏览器送去账号云登录页。
 *
 * 闭环是「账号云签发一次性 code → 回跳带 code → 本机换令牌」；
 * 后端没配账号云时直接放行，本地开发不受影响（判定见 `api/auth.ts`）。
 */
import { useEffect, useState, type ReactNode } from "react";
import { bootstrapAuth, buildLoginUrl, getBootstrapError, type AuthBootstrap } from "../api/auth";

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

  if (outcome === "error") {
    // 兑换失败 / 账号不属于本实例：留在原地展示后端的明确错误，不再自动跳登录页。
    return (
      <div
        role="alert"
        style={{
          display: "flex",
          minHeight: "100vh",
          alignItems: "center",
          justifyContent: "center",
          padding: 24,
        }}
      >
        <div style={{ maxWidth: 480, textAlign: "center" }}>
          <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>无法登录 Flux</div>
          <p style={{ margin: "0 0 20px", color: "var(--fx-text-2)", lineHeight: 1.6 }}>
            {getBootstrapError() ?? "当前账号无法登录本实例。"}
          </p>
          <button
            type="button"
            onClick={() => window.location.replace(buildLoginUrl(window.location.href))}
          >
            重新登录
          </button>
        </div>
      </div>
    );
  }

  // 还在检查，或已经在跳登录页的路上。
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
