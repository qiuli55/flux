/**
 * 工作区文件树的纯函数：图标键、改动标记优先级、目录路径推导。
 *
 * 这些逻辑与 React 无关，单独抽出来是为了能直接在 vitest（无 DOM 环境）里覆盖，
 * 也让 FileExplorer / 标签栏共用同一套判定，不会出现两处标记规则不一致。
 */
import type { Change, ChangeStatus } from "../api/types";

/** 改动标记三档：落盘失败（红）/ 待审阅（黄）/ 已落盘（绿） */
export type ChangeMarker = "failed" | "pending" | "applied";

/** 标记优先级：failed > pending（含 accepted）> applied */
const MARKER_RANK: Record<ChangeMarker, number> = { failed: 3, pending: 2, applied: 1 };

/** 单个提案状态 → 标记；已拒绝既非待处理也没落盘，不产生标记 */
function markerOfStatus(status: ChangeStatus): ChangeMarker | null {
  if (status === "failed") return "failed";
  if (status === "pending" || status === "accepted") return "pending";
  if (status === "applied") return "applied";
  return null;
}

/** 取一组标记里优先级最高的那个 */
function strongest(markers: (ChangeMarker | null)[]): ChangeMarker | null {
  let best: ChangeMarker | null = null;
  for (const marker of markers) {
    if (!marker) continue;
    if (!best || MARKER_RANK[marker] > MARKER_RANK[best]) best = marker;
  }
  return best;
}

/** 去掉尾部斜杠：后端给的是 POSIX 相对路径，展示层偶尔会带上目录分隔符 */
function normalize(path: string): string {
  return path.replace(/\/+$/, "");
}

/** 相对路径的父目录；根或单段路径返回 ""。 */
export function parentDir(path: string): string {
  const trimmed = normalize(path);
  const index = trimmed.lastIndexOf("/");
  return index <= 0 ? "" : trimmed.slice(0, index);
}

/** 从根到父的各级目录，如 "a/b/c.py" → ["a", "a/b"]；根目录返回 []。 */
export function ancestorDirs(path: string): string[] {
  const dirs: string[] = [];
  let current = parentDir(path);
  while (current) {
    dirs.unshift(current);
    current = parentDir(current);
  }
  return dirs;
}

/** 某个文件的改动标记（同路径多条提案时按优先级归一）。 */
export function markerForPath(changes: Change[], path: string): ChangeMarker | null {
  const target = normalize(path);
  const matched = changes.filter((change) => normalize(change.file_path) === target);
  return strongest(matched.map((change) => markerOfStatus(change.status)));
}

/**
 * 某个目录的改动标记：只看已加载到的提案路径里以它为祖先的文件。
 * 不做递归预加载、不发额外请求——没加载到子孙文件时自然就没有标记。
 */
export function markerForDir(changes: Change[], dir: string): ChangeMarker | null {
  const target = normalize(dir);
  if (!target) return null;
  const matched = changes.filter((change) => ancestorDirs(change.file_path).includes(target));
  return strongest(matched.map((change) => markerOfStatus(change.status)));
}

/** 扩展名 → 图标键（前端手写 SVG 用，不引图标库） */
const EXTENSION_KEYS: Record<string, string> = {
  ts: "typescript",
  tsx: "tsx",
  js: "javascript",
  jsx: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  py: "python",
  css: "stylesheet",
  scss: "stylesheet",
  less: "stylesheet",
  html: "html",
  htm: "html",
  svg: "svg",
  json: "json",
  yml: "yaml",
  yaml: "yaml",
  toml: "toml",
  ini: "toml",
  cfg: "toml",
  md: "markdown",
  markdown: "markdown",
  sh: "shell",
  bash: "shell",
  zsh: "shell",
  sql: "database",
  txt: "text",
  lock: "lock",
};

/**
 * 文件名 → 图标键。目录固定 "dir"；以点开头又没有扩展名的（.gitignore / .env）
 * 归到 "config"；其余未知扩展名回退通用 "file"。
 */
export function fileIconKey(name: string, kind: "dir" | "file"): string {
  if (kind === "dir") return "dir";
  const dot = name.lastIndexOf(".");
  if (dot <= 0) {
    return name.startsWith(".") && name.length > 1 ? "config" : "file";
  }
  const extension = name.slice(dot + 1).toLowerCase();
  return EXTENSION_KEYS[extension] ?? "file";
}
