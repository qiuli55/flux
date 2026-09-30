/** Diff 查看器：把后端 unified diff 渲染成左右并排的红绿对照视图（可切回统一视图）。 */
import { useMemo, useState, type JSX } from "react";

import { classifyDiffLines, hasChanges, renderSideBySide, type DiffRow } from "../utils/diff";

function SideCell({
  no,
  text,
  tone,
}: {
  no: number | null;
  text: string | null;
  tone: "neutral" | "del" | "add";
}): JSX.Element {
  const isBlank = text === null;
  const bg = isBlank
    ? "bg-[repeating-linear-gradient(45deg,#0d141d_0,#0d141d_6px,#0a1017_6px,#0a1017_12px)]"
    : tone === "del"
      ? "bg-del/12"
      : tone === "add"
        ? "bg-add/12"
        : "";
  const textColor = tone === "del" ? "text-del" : tone === "add" ? "text-add" : "text-muted";
  return (
    <div className="flex min-w-0">
      <span className="w-10 shrink-0 select-none border-r border-line-soft pr-2 text-right text-[11px] leading-5 text-faint">
        {no ?? ""}
      </span>
      <span className={`min-w-0 flex-1 px-2 leading-5 whitespace-pre ${bg} ${textColor}`}>
        {isBlank ? "" : text.length === 0 ? " " : text}
      </span>
    </div>
  );
}

function Row({ row }: { row: DiffRow }): JSX.Element {
  if (row.kind === "hunk") {
    return (
      <div className="bg-surface-2 px-2 py-0.5 text-[11px] leading-5 text-info">{row.leftText}</div>
    );
  }
  const leftTone = row.kind === "change" ? "del" : "neutral";
  const rightTone = row.kind === "change" ? "add" : "neutral";
  return (
    <div className="grid grid-cols-2">
      <SideCell no={row.leftNo} text={row.leftText} tone={leftTone} />
      <SideCell no={row.rightNo} text={row.rightText} tone={rightTone} />
    </div>
  );
}

export function DiffViewer({
  diff,
  filePath,
  added,
  removed,
  hunks,
}: {
  diff: string;
  filePath: string;
  added: number;
  removed: number;
  hunks: number;
}): JSX.Element {
  const [mode, setMode] = useState<"split" | "unified">("split");
  const rows = useMemo(() => renderSideBySide(diff), [diff]);
  const lines = useMemo(() => classifyDiffLines(diff), [diff]);

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-md border border-line bg-surface-1">
      <div className="flex shrink-0 items-center justify-between gap-2 border-b border-line-soft px-3 py-1.5">
        <div className="flex min-w-0 items-center gap-2">
          <span className="truncate font-mono text-xs text-text">{filePath}</span>
          <span className="shrink-0 text-[11px] text-add">+{added}</span>
          <span className="shrink-0 text-[11px] text-del">-{removed}</span>
          <span className="shrink-0 text-[11px] text-faint">{hunks} 个改动块</span>
        </div>
        <div className="flex shrink-0 items-center gap-1 rounded border border-line bg-surface-2 p-0.5">
          {(
            [
              ["split", "并排对照"],
              ["unified", "统一 diff"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              onClick={() => setMode(value)}
              className={`rounded px-2 py-0.5 text-[11px] transition-colors ${
                mode === value ? "bg-surface-3 text-text" : "text-faint hover:text-muted"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto font-mono text-[12px]">
        {!diff ? (
          <p className="p-3 text-xs text-faint">后端未提供 diff 文本。</p>
        ) : mode === "unified" ? (
          <pre className="w-max min-w-full px-2 py-1 leading-5">
            {lines.map((line, index) => {
              const tone =
                line.kind === "del"
                  ? "text-del bg-del/12"
                  : line.kind === "add"
                    ? "text-add bg-add/12"
                    : line.kind === "hunk"
                      ? "text-info bg-surface-2"
                      : line.kind === "header"
                        ? "text-faint"
                        : "text-muted";
              const display =
                line.kind === "del"
                  ? `-${line.text}`
                  : line.kind === "add"
                    ? `+${line.text}`
                    : line.kind === "context"
                      ? ` ${line.text}`
                      : line.text;
              return (
                <span key={index} className={`block px-1 whitespace-pre ${tone}`}>
                  {display.length === 0 ? " " : display}
                </span>
              );
            })}
          </pre>
        ) : !hasChanges(rows) ? (
          <p className="p-3 text-xs text-faint">这份 diff 里没有可展示的行级改动。</p>
        ) : (
          <div className="w-max min-w-full">
            <div className="grid grid-cols-2 border-b border-line-soft bg-surface-2 text-[11px] text-faint">
              <span className="border-r border-line-soft px-2 py-0.5">旧版本（original_content）</span>
              <span className="px-2 py-0.5">新版本（proposed_content）</span>
            </div>
            {rows.map((row, index) => (
              <Row key={index} row={row} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}