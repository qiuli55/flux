/**
 * 中栏 · 打开的文件标签栏（空态时整条隐藏）。
 *
 * 标签上的 M 标记与文件浏览器同源（markerForPath），避免两处判断不一致。
 */
import type { JSX } from "react";

import type { Change } from "../api/types";
import { markerForPath, type ChangeMarker } from "../utils/fileTree";
import { FileIcon } from "./FileIcon";

const MARKER_TEXT: Record<ChangeMarker, { className: string; title: string }> = {
  failed: { className: "text-danger", title: "有落盘失败的提案" },
  pending: { className: "text-warn", title: "有待审阅提案" },
  applied: { className: "text-add", title: "已落盘" },
};

function baseName(path: string): string {
  const parts = path.split("/");
  return parts[parts.length - 1] ?? path;
}

export function FileTabs({
  openFiles,
  currentFilePath,
  changes,
  onSelect,
  onClose,
}: {
  openFiles: string[];
  currentFilePath: string | null;
  changes: Change[];
  onSelect: (path: string) => void;
  onClose: (path: string) => void;
}): JSX.Element | null {
  if (openFiles.length === 0) return null;

  return (
    <div className="flex shrink-0 items-center gap-1 overflow-x-auto rounded-lg border border-line bg-surface-1 px-1.5 py-1">
      {openFiles.map((path) => {
        const active = path === currentFilePath;
        const marker = markerForPath(changes, path);
        return (
          <span
            key={path}
            className={`flex shrink-0 items-center gap-1 rounded-md border px-2 py-1 text-[11px] transition-colors ${
              active ? "border-accent/50 bg-accent/10 text-text" : "border-line bg-surface-2 text-muted"
            }`}
          >
            <button
              type="button"
              onClick={() => onSelect(path)}
              title={path}
              className="flex items-center gap-1.5"
            >
              <FileIcon name={baseName(path)} kind="file" />
              <span className="font-mono">{baseName(path)}</span>
              {marker ? (
                <span className={`font-mono text-[10px] ${MARKER_TEXT[marker].className}`} title={MARKER_TEXT[marker].title}>
                  M
                </span>
              ) : null}
            </button>
            <button
              type="button"
              onClick={() => onClose(path)}
              aria-label={`关闭 ${path}`}
              className="rounded px-1 text-faint hover:bg-surface-3 hover:text-danger"
            >
              ×
            </button>
          </span>
        );
      })}
    </div>
  );
}
