/**
 * 把后端给的 unified diff 文本解析成「左右并排对照」的行模型。
 *
 * 后端 diff 由标准库 difflib 产出（diff_engine.py）：文件头 a/<path> / b/<path>，
 * 变更块以 @@ -旧起始,行数 +新起始,行数 @@ 分段，行首 ` ` / `-` / `+` 标记。
 * 这里按块把连续的删除行与新增行配对，缺失的一侧留空，得到并排视图。
 */

export type DiffRowKind = "context" | "change" | "hunk";

export interface DiffRow {
  kind: DiffRowKind;
  /** 旧文件行号（无对应行为 null） */
  leftNo: number | null;
  /** 旧文件文本（该侧为空时 null） */
  leftText: string | null;
  /** 新文件行号 */
  rightNo: number | null;
  /** 新文件文本 */
  rightText: string | null;
}

const HUNK_PATTERN = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/** 解析 unified diff；空 diff 返回空数组。 */
export function renderSideBySide(unified: string): DiffRow[] {
  const rows: DiffRow[] = [];
  if (!unified) return rows;

  const lines = unified.replace(/\n$/, "").split("\n");
  let leftNo = 0;
  let rightNo = 0;
  let index = 0;

  while (index < lines.length) {
    const line = lines[index] ?? "";

    // 文件头：跳过
    if (line.startsWith("---") || line.startsWith("+++")) {
      index += 1;
      continue;
    }
    // "\ No newline at end of file"：跳过
    if (line.startsWith("\\")) {
      index += 1;
      continue;
    }
    // 变更块头：重置两侧行号
    const hunk = HUNK_PATTERN.exec(line);
    if (hunk) {
      leftNo = Number(hunk[1]);
      rightNo = Number(hunk[2]);
      rows.push({ kind: "hunk", leftNo: null, leftText: line, rightNo: null, rightText: null });
      index += 1;
      continue;
    }

    // 连续的删除行 + 新增行 → 配对成并排的"改动"行
    if (line.startsWith("-")) {
      const removed: string[] = [];
      while (index < lines.length && (lines[index] ?? "").startsWith("-") && !(lines[index] ?? "").startsWith("---")) {
        removed.push((lines[index] ?? "").slice(1));
        index += 1;
      }
      const added: string[] = [];
      while (index < lines.length && (lines[index] ?? "").startsWith("+") && !(lines[index] ?? "").startsWith("+++")) {
        added.push((lines[index] ?? "").slice(1));
        index += 1;
      }
      const span = Math.max(removed.length, added.length);
      for (let offset = 0; offset < span; offset += 1) {
        const hasLeft = offset < removed.length;
        const hasRight = offset < added.length;
        rows.push({
          kind: "change",
          leftNo: hasLeft ? leftNo++ : null,
          leftText: hasLeft ? (removed[offset] ?? "") : null,
          rightNo: hasRight ? rightNo++ : null,
          rightText: hasRight ? (added[offset] ?? "") : null,
        });
      }
      continue;
    }

    // 单独的新增行
    if (line.startsWith("+")) {
      rows.push({
        kind: "change",
        leftNo: null,
        leftText: null,
        rightNo: rightNo++,
        rightText: line.slice(1),
      });
      index += 1;
      continue;
    }

    // 上下文行（行首为空格；difflib 对空行可能给出空串）
    const text = line.startsWith(" ") ? line.slice(1) : line;
    rows.push({
      kind: "context",
      leftNo: leftNo++,
      leftText: text,
      rightNo: rightNo++,
      rightText: text,
    });
    index += 1;
  }

  return rows;
}

/** 并排视图里是否含任何改动（用于空态判断） */
export function hasChanges(rows: DiffRow[]): boolean {
  return rows.some((row) => row.kind === "change");
}