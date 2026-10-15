/**
 * Flux · 视图壳。
 *
 * 全局外壳（fx 设计系统）承载 7 个视图：
 * - /overview  概览
 * - /tasks     任务看板（只读分组）
 * - /solo      Solo 任务执行中心（需求澄清 → 计划 → 执行 → 审核）
 * - /ide       IDE 工作台（真实文件树 / 编辑器 / 变更 / Git / Agent / 用户终端）
 * - /skills    能力（Agent 档案 / Skill / Connector）查看与导入
 * - /diagnostics 系统诊断
 * - /settings  设置
 *
 * 命令面板（⌘K）与全局提示在壳层挂载，跨视图命令通过 IDE 意图队列传递。
 */
import { AuthGate } from "./app/AuthGate";
import { CommandPalette } from "./app/commands";
import { useView } from "./app/router";
import { ToastHost } from "./app/toast";
import { readWindowMode } from "./app/windowMode";
import { HumanTerminalPanel } from "./components/terminal/HumanTerminalPanel";
import { TerminalWindow } from "./components/terminal/TerminalWindow";
import { ExplorerContextMenu } from "./components/explorer/ExplorerContextMenu";
import { WorkspacePicker } from "./components/workspace/WorkspacePicker";
import { Shell } from "./shell/Shell";

export default function App() {
  return (
    <AuthGate>
      <AppContent />
    </AuthGate>
  );
}

function AppContent() {
  const [view, navigate] = useView();

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
      <Shell view={view} navigate={navigate} />
      {view === "ide" ? <WorkspacePicker /> : null}
      {view === "ide" ? <ExplorerContextMenu /> : null}
      <CommandPalette view={view} navigate={navigate} />
      <TerminalWindow />
      {view === "ide" ? <HumanTerminalPanel /> : null}
      <ToastHost />
    </>
  );
}
