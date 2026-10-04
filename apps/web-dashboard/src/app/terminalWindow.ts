/**
 * Agent Terminal 窗口的打开入口。
 *
 * 与命令面板同一套 ref 模式：窗口组件挂载在 App 壳层（跨视图常驻），任何页面
 * （命令面板 / IDE 顶栏 / Solo）调用 openTerminalWindow() 即可打开它。
 * 窗口只是观察与控制客户端，关闭它不会停止 Agent（AGENT_TERMINAL_CONSOLE §11）。
 */
let opener: (() => void) | null = null;

export function setTerminalOpener(fn: (() => void) | null): void {
  opener = fn;
}

export function openTerminalWindow(): void {
  opener?.();
}
