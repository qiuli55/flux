/**
 * 命令面板（⌘K）：命令来自「全局命令」+ 当前页面注册的命令。
 *
 * 跨视图的命令（如「专注模式」由 IDE 页负责执行）通过 IDE 意图队列传递：
 * 先切到 IDE，待 IDE 页挂载后取走意图并执行，避免访问未挂载页面的状态。
 */
import { useEffect, useMemo, useRef, useState } from "react";

import type { View } from "./router";

/** IDE 页可被外部（命令面板）触发的意图 */
export type IdeIntent =
  | "focus"
  | "files"
  | "bottom"
  | "agent"
  | "git"
  | "changes"
  | "problems"
  | { kind: "open-file"; path: string };

let pendingIntent: IdeIntent | null = null;
let intentHandler: ((intent: IdeIntent) => void) | null = null;

/**
 * IDE 页注册意图处理器：注册时若已有排队意图会立即执行；卸载时传 null 注销。
 * 这样「切到 IDE 再执行」与「已在 IDE 时直接执行」走同一条路径。
 */
export function setIdeIntentHandler(handler: ((intent: IdeIntent) => void) | null): void {
  intentHandler = handler;
  if (!handler || !pendingIntent) return;
  const current = pendingIntent;
  pendingIntent = null;
  handler(current);
}

/** 派发一个 IDE 意图：IDE 页在场就直接执行，否则排队等它挂载后取走 */
export function queueIdeIntent(intent: IdeIntent): void {
  if (intentHandler) {
    intentHandler(intent);
    return;
  }
  pendingIntent = intent;
}

export interface Command {
  id: string;
  cat: string;
  name: string;
  /** 快捷键提示（仅展示） */
  key?: string;
  /** 归属视图：不在该视图时先切换过去，再由该页取走意图执行 */
  view?: View;
  run: () => void;
}

function matches(command: Command, query: string): boolean {
  if (!query) return true;
  return `${command.cat} ${command.name}`.toLowerCase().includes(query);
}

export function CommandPalette({
  view,
  navigate,
}: {
  view: View;
  navigate: (next: View) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const commands = useMemo<Command[]>(() => {
    const inIde = (intent: IdeIntent) => {
      queueIdeIntent(intent);
      navigate("ide");
    };
    return [
      { id: "go-solo", cat: "导航", name: "切换到 Solo · AI 任务执行中心", run: () => navigate("solo") },
      { id: "go-ide", cat: "导航", name: "切换到 IDE · 工作台", run: () => navigate("ide") },
      {
        id: "focus",
        cat: "视图",
        name: "专注模式 Focus Mode（隐藏所有面板）",
        key: "⌘\\",
        view: "ide",
        run: () => inIde("focus"),
      },
      {
        id: "files",
        cat: "视图",
        name: "折叠 / 展开文件资源管理器",
        key: "⌘B",
        view: "ide",
        run: () => inIde("files"),
      },
      {
        id: "bottom",
        cat: "视图",
        name: "显示 / 收起底部面板",
        key: "⌘J",
        view: "ide",
        run: () => inIde("bottom"),
      },
      { id: "agent", cat: "面板", name: "Agent 面板 收起 / 展开", view: "ide", run: () => inIde("agent") },
      { id: "git-panel", cat: "面板", name: "打开源代码管理（Git 面板）", view: "ide", run: () => inIde("git") },
      {
        id: "changes-tab",
        cat: "面板",
        name: "在编辑器打开变更 Diff（Changes Tab）",
        view: "ide",
        run: () => inIde("changes"),
      },
      {
        id: "problems",
        cat: "面板",
        name: "查看待处理问题（待审变更与落盘失败）",
        view: "ide",
        run: () => inIde("problems"),
      },
    ];
  }, [navigate]);

  const shown = useMemo(
    () => commands.filter((command) => matches(command, query.trim().toLowerCase())),
    [commands, query],
  );

  // 供页面（Solo 搜索框 / IDE 顶栏）打开面板
  useEffect(() => {
    openPaletteRef.current = () => {
      setQuery("");
      setSelected(0);
      setOpen(true);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    };
    return () => {
      openPaletteRef.current = null;
    };
  });

  // 全局快捷键：⌘K 开关命令面板（与设计稿一致）
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const mod = event.metaKey || event.ctrlKey;
      if (mod && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen((prev) => !prev);
        setQuery("");
        setSelected(0);
        window.setTimeout(() => inputRef.current?.focus(), 0);
      } else if (event.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const run = (command: Command | undefined) => {
    if (!command) return;
    setOpen(false);
    command.run();
  };

  if (!open) return null;

  return (
    <div
      className="cmdk-mask"
      onClick={(event) => {
        if (event.target === event.currentTarget) setOpen(false);
      }}
    >
      <div className="cmdk" role="dialog" aria-label="命令面板">
        <div className="cmdk-input">
          <span className="k">›</span>
          <input
            ref={inputRef}
            value={query}
            placeholder="输入命令，例如：切换页面 / 专注模式 / 打开 Git 面板"
            onChange={(event) => {
              setQuery(event.target.value);
              setSelected(0);
            }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown") {
                event.preventDefault();
                setSelected((prev) => Math.min(prev + 1, shown.length - 1));
              } else if (event.key === "ArrowUp") {
                event.preventDefault();
                setSelected((prev) => Math.max(prev - 1, 0));
              } else if (event.key === "Enter") {
                event.preventDefault();
                run(shown[selected]);
              }
            }}
          />
          <kbd>esc</kbd>
        </div>
        <ul className="cmdk-list">
          {shown.length === 0 ? (
            <li className="cmdk-item">
              <span className="ci-name">没有匹配的命令</span>
            </li>
          ) : (
            shown.map((command, index) => (
              <li
                key={command.id}
                className={`cmdk-item${index === selected ? " is-sel" : ""}`}
                onMouseEnter={() => setSelected(index)}
                onClick={() => run(command)}
              >
                <span className="ci-cat">{command.cat}</span>
                <span className="ci-name">{command.name}</span>
                {command.key ? (
                  <span className="ci-k">
                    <kbd>{command.key}</kbd>
                  </span>
                ) : null}
              </li>
            ))
          )}
        </ul>
        <div className="cmdk-foot">
          <span>当前视图：{view === "ide" ? "IDE 工作台" : "Solo 任务执行中心"}</span>
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> 选择
          </span>
          <span>
            <kbd>↵</kbd> 执行
          </span>
          <span>
            <kbd>esc</kbd> 关闭
          </span>
        </div>
      </div>
    </div>
  );
}

/** 页面用它打开命令面板（Solo 的搜索框与 IDE 顶栏都走这里） */
const openPaletteRef: { current: (() => void) | null } = { current: null };

export function openCommandPalette(): void {
  openPaletteRef.current?.();
}