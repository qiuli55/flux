/**
 * 应用入口壳：区分认证页与应用页。
 * - 认证页（login/register/forgot）：独立居中布局，登录后跳转概览。
 * - 应用页：Shell 包裹，⌘K 打开命令面板。
 */
import { useEffect, useState } from "react";
import { CommandPalette } from "./app/CommandPalette";
import { useRoute } from "./app/router";
import { ToastProvider } from "./app/Toast";
import { Shell } from "./components/layout/Shell";
import { AUTH_PAGES } from "./app/nav";
import { Forgot, Login, Register } from "./pages/Auth";
import { renderPage } from "./pages";

function AuthRouter({ id, navigate }: { id: string; navigate: (v: string) => void }) {
  const toApp = () => navigate("overview");
  if (id === "register") return <Register navigate={toApp} go={navigate} />;
  if (id === "forgot") return <Forgot go={navigate} />;
  return <Login navigate={toApp} go={navigate} />;
}

export default function App() {
  const [id, navigate] = useRoute();
  const [cmd, setCmd] = useState(false);
  const isAuth = (AUTH_PAGES as readonly string[]).includes(id);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setCmd((v) => !v);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <ToastProvider>
      {isAuth ? (
        <div key={id} className="view-enter">
          <AuthRouter id={id} navigate={navigate} />
        </div>
      ) : (
        <Shell current={id} navigate={navigate} onCommand={() => setCmd(true)}>
          <div key={id} className="view-enter">
            {renderPage(id, { navigate })}
          </div>
        </Shell>
      )}
      <CommandPalette open={cmd} onClose={() => setCmd(false)} navigate={navigate} />
    </ToastProvider>
  );
}
