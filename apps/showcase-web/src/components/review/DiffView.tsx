/**
 * 统一 diff 的解析与渲染。
 *
 * 后端只存统一 diff 文本（virtual_changes.diff）与 added/removed_lines、hunks 计数，
 * 没有行级结构，因此由前端解析文本。支持 hunk 头、增/删/上下文行与换行标记。
 */
import type { ReactNode } from "react";

export interface DiffLine {
  t: "hunk" | "add" | "del" | "ctx";
  oldNo: string;
  newNo: string;
  text: string;
}

const HUNK = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/** 把 unified diff 文本解析成带行号的行列表；文件头与非 diff 行跳过。 */
export function parseUnified(diff: string): DiffLine[] {
  const out: DiffLine[] = [];
  let oldNo = 0;
  let newNo = 0;
  for (const raw of diff.split("\n")) {
    if (raw.startsWith("--- ") || raw.startsWith("+++ ") || raw.startsWith("\\")) continue;
    const m = HUNK.exec(raw);
    if (m) {
      oldNo = Number(m[1]);
      newNo = Number(m[2]);
      out.push({ t: "hunk", oldNo: "", newNo: "", text: raw });
      continue;
    }
    if (raw.startsWith("+")) {
      out.push({ t: "add", oldNo: "", newNo: String(newNo++), text: raw.slice(1) });
    } else if (raw.startsWith("-")) {
      out.push({ t: "del", oldNo: String(oldNo++), newNo: "", text: raw.slice(1) });
    } else {
      const text = raw.startsWith(" ") ? raw.slice(1) : raw;
      out.push({ t: "ctx", oldNo: String(oldNo++), newNo: String(newNo++), text });
    }
  }
  return out;
}

function gutter(l: DiffLine): string {
  if (l.t === "hunk") return "";
  return l.t === "del" ? l.oldNo : l.newNo;
}

/** 渲染一个统一 diff 正文（不含外框，外框由调用方决定）。 */
export function DiffBody({ diff }: { diff: string }): ReactNode {
  const lines = parseUnified(diff);
  return (
    <div className="diff-body">
      {lines.map((l, i) => (
        <div key={i} className={["dl", l.t].filter(Boolean).join(" ")}>
          <span className="g">{gutter(l)}</span>
          <span className="c">{l.text}</span>
        </div>
      ))}
    </div>
  );
}
