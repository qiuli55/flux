/**
 * 左栏 · 工作区文件：真正的文件资源管理器。
 *
 * 目录懒加载（点开才请求 depth=1 并缓存），文件可选中并加入需求上下文；
 * 改动标记只用已经拿到的提案列表推断，绝不为了标点去递归预加载子孙目录。
 *
 * 注意：工作区根由后端 FLUX_WORKSPACE_ROOT 决定，project_id 仅做存在性校验，
 * 所以标题是「工作区文件」，切换项目不会换根目录。
 */
import { useCallback, useEffect, useMemo, useState, type JSX } from "react";

import { api } from "../api/client";
import type { Change, FileEntry, FileTree } from "../api/types";
import { MAX_CONTEXT_FILES } from "../data/context";
import { ancestorDirs, markerForDir, markerForPath, type ChangeMarker } from "../utils/fileTree";
import { FileIcon } from "./FileIcon";
import { Button, EmptyState, ErrorBanner, Panel, PanelHeader, Spinner } from "./ui";

const MARKER_DOT: Record<ChangeMarker, string> = {
  failed: "bg-danger",
  pending: "bg-warn",
  applied: "bg-add",
};

/** 悬停说明：黄点必须写清有几条待审阅提案 */
function markerTitle(marker: ChangeMarker, count: number): string {
  if (marker === "failed") return "有落盘失败的提案";
  if (marker === "pending") return `${count} 条待审阅提案`;
  return "已落盘";
}

/** 该行（文件或目录）下待审阅提案数 */
function pendingCount(changes: Change[], path: string, kind: "dir" | "file"): number {
  return changes.filter((change) => {
    const under = kind === "file" ? change.file_path === path : ancestorDirs(change.file_path).includes(path);
    return under && (change.status === "pending" || change.status === "accepted");
  }).length;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function FileExplorer({
  projectId,
  changes,
  currentFilePath,
  onOpenFile,
  contextPaths,
  onAddPath,
  onRemovePath,
}: {
  projectId: string | null;
  changes: Change[];
  currentFilePath: string | null;
  onOpenFile: (path: string) => void;
  contextPaths: string[];
  onAddPath: (path: string) => void;
  onRemovePath: (path: string) => void;
}): JSX.Element {
  // 已请求过的目录 → 子项；"" 表示工作区根
  const [cache, setCache] = useState<Record<string, FileTree>>({});
  const [expanded, setExpanded] = useState<string[]>([]);
  const [loading, setLoading] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const loadDir = useCallback(
    async (dir: string) => {
      if (!projectId) return;
      setLoading((prev) => (prev.includes(dir) ? prev : [...prev, dir]));
      try {
        // 每次只展开一层：目录层级由用户点击驱动，避免一次性拉整棵树
        const tree = await api.listFiles(projectId, dir ? { path: dir, depth: 1 } : { depth: 1 });
        setCache((prev) => ({ ...prev, [dir]: tree }));
        setError(null);
      } catch (caught) {
        setError(errorText(caught));
      } finally {
        setLoading((prev) => prev.filter((item) => item !== dir));
      }
    },
    [projectId],
  );

  // 换项目 / 点刷新：清空缓存重新拉根目录
  const [reloadToken, setReloadToken] = useState(0);
  useEffect(() => {
    setCache({});
    setExpanded([]);
    setError(null);
    if (projectId) void loadDir("");
  }, [projectId, reloadToken, loadDir]);

  const root = cache[""];
  const rootLoading = loading.includes("");
  const truncated = useMemo(() => Object.values(cache).some((tree) => tree.truncated), [cache]);

  function toggleDir(path: string) {
    setExpanded((prev) => (prev.includes(path) ? prev.filter((item) => item !== path) : [...prev, path]));
    // 已缓存的目录二次展开不再请求；已在飞行中的请求也不重复发
    if (!cache[path] && !loading.includes(path)) void loadDir(path);
  }

  function renderRow(entry: FileEntry, depth: number): JSX.Element {
    const isDir = entry.kind === "dir";
    const isOpen = expanded.includes(entry.path);
    const marker = isDir ? markerForDir(changes, entry.path) : markerForPath(changes, entry.path);
    const active = !isDir && entry.path === currentFilePath;
    const inContext = contextPaths.includes(entry.path);
    const contextFull = contextPaths.length >= MAX_CONTEXT_FILES;
    const pending = marker === "pending" ? pendingCount(changes, entry.path, entry.kind) : 0;
    const indent = 6 + depth * 12;

    return (
      <div key={entry.path} className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => (isDir ? toggleDir(entry.path) : onOpenFile(entry.path))}
          title={entry.path}
          style={{ paddingLeft: indent }}
          className={`flex min-w-0 flex-1 items-center gap-1.5 rounded px-1 py-1 text-left text-xs transition-colors ${
            active ? "bg-accent/15 text-text" : "text-muted hover:bg-surface-2 hover:text-text"
          }`}
        >
          {isDir ? (
            <svg
              viewBox="0 0 24 24"
              className={`h-3 w-3 shrink-0 text-faint transition-transform ${isOpen ? "rotate-90" : ""}`}
              fill="none"
              aria-hidden="true"
            >
              <path d="M9 6l6 6-6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          ) : (
            <span className="w-3 shrink-0" aria-hidden="true" />
          )}
          <FileIcon name={entry.name} kind={entry.kind} />
          <span className={`min-w-0 flex-1 truncate ${entry.kind === "dir" ? "font-medium" : "font-mono"}`}>
            {entry.name}
          </span>
          {loading.includes(entry.path) ? <Spinner className="h-3 w-3" /> : null}
          {marker ? (
            <span
              className={`h-1.5 w-1.5 shrink-0 rounded-full ${MARKER_DOT[marker]}`}
              title={markerTitle(marker, pending)}
              aria-label={markerTitle(marker, pending)}
            />
          ) : null}
        </button>

        {!isDir ? (
          <button
            type="button"
            onClick={() => (inContext ? onRemovePath(entry.path) : onAddPath(entry.path))}
            disabled={!inContext && contextFull}
            title={
              inContext
                ? "从本次需求上下文移除"
                : contextFull
                  ? `上下文最多 ${MAX_CONTEXT_FILES} 个文件，请先移除一个`
                  : "加入本次需求上下文"
            }
            className={`shrink-0 rounded border px-1.5 py-0.5 text-[10px] transition-colors ${
              inContext
                ? "border-add/40 bg-add/10 text-add"
                : "border-line bg-surface-2 text-faint hover:text-text disabled:opacity-40"
            }`}
          >
            {inContext ? "已加入" : "加入"}
          </button>
        ) : null}
      </div>
    );
  }

  /** 递归渲染某个目录下已缓存的内容（未缓存就不渲染，等请求回来） */
  function renderDir(dir: string, depth: number): JSX.Element[] {
    const tree = cache[dir];
    const rows: JSX.Element[] = [];
    if (!tree) {
      if (loading.includes(dir)) {
        rows.push(
          <p key={`${dir}--loading`} style={{ paddingLeft: 6 + depth * 12 }} className="py-1 text-[11px] text-faint">
            正在加载…
          </p>,
        );
      }
      return rows;
    }
    if (tree.entries.length === 0) {
      rows.push(
        <p key={`${dir}--empty`} style={{ paddingLeft: 6 + depth * 12 }} className="py-1 text-[11px] text-faint">
          空目录
        </p>,
      );
      return rows;
    }
    for (const entry of tree.entries) {
      rows.push(renderRow(entry, depth));
      if (entry.kind === "dir" && expanded.includes(entry.path)) {
        rows.push(...renderDir(entry.path, depth + 1));
      }
    }
    return rows;
  }

  const subtitle = root ? `${root.root} · 根目录 ${root.entries.length} 项` : "工作区根目录尚未加载";

  return (
    <Panel className="flex-1">
      <PanelHeader
        title="工作区文件"
        subtitle={subtitle}
        actions={
          <Button
            tone="ghost"
            busy={rootLoading}
            onClick={() => setReloadToken((token) => token + 1)}
            title="清空缓存并重新拉取工作区根目录"
            disabled={!projectId}
          >
            刷新
          </Button>
        }
      />

      <div className="min-h-0 flex-1 overflow-y-auto p-2">
        {error ? (
          <div className="mb-2">
            <ErrorBanner message={error} onDismiss={() => setError(null)} />
          </div>
        ) : null}
        {truncated ? (
          <p className="mb-2 rounded border border-warn/40 bg-warn/10 px-2 py-1 text-[11px] text-warn">
            已达 2000 条上限，仅显示部分。
          </p>
        ) : null}

        {!projectId ? (
          <EmptyState>
            还没有选中项目。先到左栏「项目」tab 登记或选择项目——文件接口只校验项目是否存在，工作区根由后端配置。
          </EmptyState>
        ) : rootLoading && !root ? (
          <EmptyState>正在读取工作区根目录…</EmptyState>
        ) : !root ? (
          <EmptyState>还没拿到工作区文件列表。点右上角「刷新」重试。</EmptyState>
        ) : (
          <div>{renderDir("", 0)}</div>
        )}
      </div>
    </Panel>
  );
}
