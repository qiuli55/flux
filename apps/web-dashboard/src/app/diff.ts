/**
 * 统一 diff（unified diff）解析：把后端虚拟提案里的 diff 文本转成可渲染的行。
 *
 * 行号必须真实：旧行号只对未删除行递增，新行号只对未删除行（新增行也递增）递增，
 * 与 git diff 的口径一致；hunk 头单独成行展示，不做任何推断填充。
 */
export type DiffRowKind = "same" | "add" | "del" | "hunk";

export interface DiffRow {
  kind: DiffRowKind;
  /** 旧文件行号（新增行为空） */
  oldNo: string;
  /** 新文件行号（删除行为空） */
  newNo: string;
  /** 去掉 diff 前缀后的原文 */
  text: string;
}

/** 解析 hunk 头 `@@ -12,7 +12,9 @@`，取出新旧起始行号 */
function parseHunkHeader(line: string): { oldStart: number; newStart: number } | null {
  const match = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
  if (!match) return null;
  return { oldStart: Number(match[1]), newStart: Number(match[2]) };
}

export function parseUnifiedDiff(diff: string): DiffRow[] {
  const rows: DiffRow[] = [];
  let oldNo = 0;
  let newNo = 0;

  for (const raw of (diff ?? "").split("\n")) {
    if (
      raw.startsWith("diff --git") ||
      raw.startsWith("index ") ||
      raw.startsWith("--- ") ||
      raw.startsWith("+++ ") ||
      raw.startsWith("new file mode") ||
      raw.startsWith("deleted file mode") ||
      raw.startsWith("similarity index") ||
      raw.startsWith("rename ")
    ) {
      continue;
    }
    if (raw.startsWith("@@")) {
      const header = parseHunkHeader(raw);
      if (header) {
        oldNo = header.oldStart;
        newNo = header.newStart;
      }
      rows.push({ kind: "hunk", oldNo: "", newNo: "", text: raw });
      continue;
    }
    if (raw.startsWith("\\")) {
      // "\ No newline at end of file" —— 原样保留，不参与行号
      rows.push({ kind: "same", oldNo: "", newNo: "", text: raw });
      continue;
    }

    const marker = raw.charAt(0);
    const text = raw.slice(1);
    if (marker === "+") {
      rows.push({ kind: "add", oldNo: "", newNo: String(newNo), text });
      newNo += 1;
    } else if (marker === "-") {
      rows.push({ kind: "del", oldNo: String(oldNo), newNo: "", text });
      oldNo += 1;
    } else {
      rows.push({ kind: "same", oldNo: String(oldNo), newNo: String(newNo), text });
      oldNo += 1;
      newNo += 1;
    }
  }
  return rows;
}

/** 差量概览：从 diff 文本统计新增/删除行数（与后端 added_lines / removed_lines 同口径的兜底展示） */
export function diffStat(diff: string): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const raw of (diff ?? "").split("\n")) {
    if (raw.startsWith("+++") || raw.startsWith("---")) continue;
    if (raw.startsWith("+")) added += 1;
    else if (raw.startsWith("-")) removed += 1;
  }
  return { added, removed };
}

/**
 * 把统一 diff 铺回原文件，得到「整文件 + 增删标记」视图行：hunk 之外的原文件行按原样补齐，
 * hunk 内的新增行（add）与删除行（del）原样保留，顺序与阅读顺序一致。
 * 行号沿用 git 口径：新增行只有新行号（newNo），删除行只有旧行号（oldNo）。
 */
export function mergeDiffIntoOriginal(original: string, diff: string): DiffRow[] {
  const rows = parseUnifiedDiff(diff);
  const lines = original ? original.split("\n") : [];
  const out: DiffRow[] = [];
  let oldCursor = 1;
  let newCursor = 1;

  // 补齐原文件里 hunk 没覆盖到的行（这些行新旧一致）
  const fillTo = (oldNo: number) => {
    while (oldCursor < oldNo) {
      out.push({
        kind: "same",
        oldNo: String(oldCursor),
        newNo: String(newCursor),
        text: lines[oldCursor - 1] ?? "",
      });
      oldCursor += 1;
      newCursor += 1;
    }
  };

  for (const row of rows) {
    if (row.kind === "hunk") continue;
    if (row.kind === "add") {
      out.push(row);
      if (row.newNo) newCursor = Number(row.newNo) + 1;
      continue;
    }
    const oldNo = Number(row.oldNo);
    if (!Number.isFinite(oldNo) || oldNo < 1) {
      out.push(row);
      continue;
    }
    fillTo(oldNo);
    out.push(row);
    oldCursor = oldNo + 1;
    // 删除行不占新文件行号；未改动行要同步推进新行号
    if (row.kind === "same" && row.newNo) newCursor = Number(row.newNo) + 1;
  }

  while (oldCursor <= lines.length) {
    out.push({
      kind: "same",
      oldNo: String(oldCursor),
      newNo: String(newCursor),
      text: lines[oldCursor - 1] ?? "",
    });
    oldCursor += 1;
    newCursor += 1;
  }
  return out;
}