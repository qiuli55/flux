/**
 * Flux · 视图壳。
 *
 * 两个视图（设计稿 v2）：
 * - /solo：Solo 任务执行中心（需求澄清 → 计划 → 执行 → 审核）
 * - /ide ：IDE 工作台（真实文件树 / 编辑器 / 变更 / Git / Agent）
 *
 * 命令面板（⌘K）与全局提示在壳层挂载，跨视图命令通过 IDE 意图队列传递。
 */
import { CommandPalette } from "./app/commands";
import { useView } from "./app/router";
import { ToastHost } from "./app/toast";
import { readWindowMode } from "./app/windowMode";
import { TerminalWindow } from "./components/terminal/TerminalWindow";
import { IdePage } from "./pages/IdePage";
import { SoloPage } from "./pages/SoloPage";

export default function App() {
  const [view, navigate] = useView();

  // 桌面端 Agent Terminal 独立窗口（Electron 第二个 BrowserWindow）：只渲染终端
  if (readWindowMode() === "terminal") {
    return (
      <>
        <TerminalWindow standalone />
        <ToastHost />
      </>
    );
  }

  return (
    <>
      {view === "solo" ? (
        <SoloPage onOpenWorkspace={() => navigate("ide")} />
      ) : (
        <IdePage onBackToSolo={() => navigate("solo")} />
      )}
      <CommandPalette view={view} navigate={navigate} />
      {/* Agent Terminal 独立窗口：跨视图常驻，关闭不停 Agent（AGENT_TERMINAL_CONSOLE §11） */}
      <TerminalWindow />
      <ToastHost />
    </>
  );
}