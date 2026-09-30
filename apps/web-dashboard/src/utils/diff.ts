/**
 * 把后端给的 unified diff 文本解析成「左右并排对照」的行模型。
 *
 * 后端 diff 由标准库 difflib 产出（diff_engine.py）：文件头 a/<path> / b/<path>，
 * 变更块以 @@ -旧起始,行数 +新起始,行数 @@ 分段，行首 ` ` / `-` / `+` 标记。
 *
 * 解析统一先走 classifyDiffLines()：用显式 inHunk 状态判定文件头，只有尚未进入
 * hunk 时的 --- / +++ 才是文件头；进入 hunk 后行首前缀就只是内容标记，内容本身
 * 以 -- / ++ 开头的合法代码行照常按删除 / 新增处理，不会被误吞。
 * 统一视图与并排视图共用同一份分类结果，保证「改动行集合」判定完全一致。
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

/** unified diff 里一行的类别：header=文件头，meta="\ No newline..." 之类无关行 */
export type UnifiedKind = "header" | "hunk" | "meta" | "del" | "add" | "context";

export interface UnifiedLine {
  kind: UnifiedKind;
  /** del/add 去掉行首标记、context 去掉前导空格后的正文；header/hunk/meta 保留整行 */
  text: string;
}

const HUNK_PATTERN = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

/**
 * 把 unified diff 逐行分类。空 diff 返回空数组。
 *
 * 只有尚未进入 hunk 时的 --- / +++ 才是文件头；进入 hunk 后前缀只是内容标记，
 * 因此 `-- 注释` / `++ 计数` 这类行会被正确归为 del / add，而不是被当成文件头跳过。
 */
export function classifyDiffLines(unified: string): UnifiedLine[] {
  const out: UnifiedLine[] = [];
  if (!unified) return out;

  const lines = unified.replace(/\n$/, "").split("\n");
  let inHunk = false;

  for (const line of lines) {
    if (!inHunk) {
      if (line.startsWith("---") || line.startsWith("+++")) {
        out.push({ kind: "header", text: line });
      } else if (HUNK_PATTERN.test(line)) {
        inHunk = true;
        out.push({ kind: "hunk", text: line });
      } else {
        out.push({ kind: "meta", text: line });
      }
      continue;
    }

    if (line.startsWith("\\")) {
      out.push({ kind: "meta", text: line });
    } else if (HUNK_PATTERN.test(line)) {
      out.push({ kind: "hunk", text: line });
    } else if (line.startsWith("+")) {
      out.push({ kind: "add", text: line.slice(1) });
    } else if (line.startsWith("-")) {
      out.push({ kind: "del", text: line.slice(1) });
    } else if (line.startsWith(" ")) {
      out.push({ kind: "context", text: line.slice(1) });
    } else {
      out.push({ kind: "context", text: line });
    }
  }

  return out;
}

/** 解析 unified diff 为并排行模型；空 diff 返回空数组。 */
export function renderSideBySide(unified: string): DiffRow[] {
  const rows: DiffRow[] = [];
  const lines = classifyDiffLines(unified);
  if (lines.length === 0) return rows;

  let leftNo = 0;
  let rightNo = 0;
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    if (!line) break;

    if (line.kind === "hunk") {
      const match = HUNK_PATTERN.exec(line.text);
      leftNo = Number(match?.[1] ?? leftNo);
      rightNo = Number(match?.[2] ?? rightNo);
      rows.push({ kind: "hunk", leftNo: null, leftText: line.text, rightNo: null, rightText: null });
      i += 1;
      continue;
    }

    if (line.kind === "del" || line.kind === "add") {
      const removed: string[] = [];
      while (i < lines.length && lines[i]?.kind === "del") {
        removed.push(lines[i]?.text ?? "");
        i += 1;
      }
      const added: string[] = [];
      while (i < lines.length && lines[i]?.kind === "add") {
        added.push(lines[i]?.text ?? "");
        i += 1;
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

    if (line.kind === "context") {
      rows.push({
        kind: "context",
        leftNo: leftNo++,
        leftText: line.text,
        rightNo: rightNo++,
        rightText: line.text,
      });
      i += 1;
      continue;
    }

    // header / meta 不产生任何 diff 行
    i += 1;
  }

  return rows;
}

/** 并排视图里是否含任何改动（用于空态判断） */
export function hasChanges(rows: DiffRow[]): boolean {
  return rows.some((row) => row.kind === "change");
}
