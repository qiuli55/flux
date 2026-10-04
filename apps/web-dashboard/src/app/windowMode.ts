/**
 * 桌面端（Electron）窗口模式。
 *
 * 主进程开两个 BrowserWindow：主窗口加载 index.html，Agent Terminal 独立窗口加载
 * index.html?fluxWindow=terminal。前端据此只渲染终端窗口，不再渲染 Solo/IDE
 * （AGENT_TERMINAL_CONSOLE §2：独立窗口只是观察与控制客户端）。
 * 浏览器直接访问时不带该参数，行为不变。
 */
export type WindowMode = "terminal" | null;

export function readWindowMode(): WindowMode {
  const mode = new URLSearchParams(window.location.search).get("fluxWindow");
  return mode === "terminal" ? "terminal" : null;
}
