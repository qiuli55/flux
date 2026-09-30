/**
 * diff.ts 单测：覆盖文件头判定、hunk 内 --/++ 合法行、行号语义、
 * 统一视图与并排视图「改动行集合」一致，以及空 diff / meta 行跳过。
 */
import { describe, expect, it } from "vitest";

import { classifyDiffLines, renderSideBySide } from "./diff";

/** 统一视图的改动行集合（以 classifyDiffLines 的 del/add 为基准） */
function changedTexts(unified: string): string[] {
  return classifyDiffLines(unified)
    .filter((line) => line.kind === "del" || line.kind === "add")
    .map((line) => line.text);
}

/** 并排视图的改动行集合（展开 change 行的 leftText / rightText） */
function sideChangedTexts(unified: string): string[] {
  return renderSideBySide(unified)
    .filter((row) => row.kind === "change")
    .flatMap((row) => [row.leftText, row.rightText])
    .filter((text): text is string => text !== null);
}

const NORMAL_DIFF = [
  "--- a/app/auth.py",
  "+++ b/app/auth.py",
  "@@ -1,2 +1,2 @@",
  " def login(user):",
  "-    return False",
  "+    return check_password(user)",
].join("\n");

describe("classifyDiffLines", () => {
  it("marks file headers only before entering a hunk", () => {
    const lines = classifyDiffLines(NORMAL_DIFF);
    expect(lines[0]).toEqual({ kind: "header", text: "--- a/app/auth.py" });
    expect(lines[1]).toEqual({ kind: "header", text: "+++ b/app/auth.py" });
    expect(lines[2]).toEqual({ kind: "hunk", text: "@@ -1,2 +1,2 @@" });
  });

  it("keeps hunk lines starting with -- and ++ as del and add", () => {
    const unified = [
      "--- a/a.py",
      "+++ b/a.py",
      "@@ -1,2 +1,2 @@",
      "--- comment",
      "-return 0",
      "+++ count",
      "+return 1",
    ].join("\n");
    expect(classifyDiffLines(unified)).toEqual([
      { kind: "header", text: "--- a/a.py" },
      { kind: "header", text: "+++ b/a.py" },
      { kind: "hunk", text: "@@ -1,2 +1,2 @@" },
      { kind: "del", text: "-- comment" },
      { kind: "del", text: "return 0" },
      { kind: "add", text: "++ count" },
      { kind: "add", text: "return 1" },
    ]);
  });

  it("returns an empty array for an empty diff", () => {
    expect(classifyDiffLines("")).toEqual([]);
  });
});

describe("renderSideBySide", () => {
  it("renders a normal unified diff with correct line numbers", () => {
    const rows = renderSideBySide(NORMAL_DIFF);
    expect(rows).toEqual([
      { kind: "hunk", leftNo: null, leftText: "@@ -1,2 +1,2 @@", rightNo: null, rightText: null },
      { kind: "context", leftNo: 1, leftText: "def login(user):", rightNo: 1, rightText: "def login(user):" },
      {
        kind: "change",
        leftNo: 2,
        leftText: "    return False",
        rightNo: 2,
        rightText: "    return check_password(user)",
      },
    ]);
  });

  it("does not swallow lines whose content starts with -- or ++", () => {
    const unified = [
      "--- a/a.py",
      "+++ b/a.py",
      "@@ -1,2 +1,2 @@",
      "--- comment",
      "-return 0",
      "+++ count",
      "+return 1",
    ].join("\n");
    const rows = renderSideBySide(unified);
    const changes = rows.filter((row) => row.kind === "change");
    expect(changes).toHaveLength(2);
    expect(changes[0]).toEqual({
      kind: "change",
      leftNo: 1,
      leftText: "-- comment",
      rightNo: 1,
      rightText: "++ count",
    });
    expect(changes[1]).toEqual({
      kind: "change",
      leftNo: 2,
      leftText: "return 0",
      rightNo: 2,
      rightText: "return 1",
    });
  });

  it("keeps line numbers correct when deleting 3 lines and adding 1", () => {
    const unified = [
      "--- a/a.py",
      "+++ b/a.py",
      "@@ -1,5 +1,3 @@",
      " a",
      "-b",
      "-c",
      "-d",
      "+X",
      " e",
    ].join("\n");
    const rows = renderSideBySide(unified);
    expect(rows).toEqual([
      { kind: "hunk", leftNo: null, leftText: "@@ -1,5 +1,3 @@", rightNo: null, rightText: null },
      { kind: "context", leftNo: 1, leftText: "a", rightNo: 1, rightText: "a" },
      { kind: "change", leftNo: 2, leftText: "b", rightNo: 2, rightText: "X" },
      { kind: "change", leftNo: 3, leftText: "c", rightNo: null, rightText: null },
      { kind: "change", leftNo: 4, leftText: "d", rightNo: null, rightText: null },
      { kind: "context", leftNo: 5, leftText: "e", rightNo: 3, rightText: "e" },
    ]);
  });

  it("skips the \\ No newline at end of file line", () => {
    const unified = [
      "--- a/a.py",
      "+++ b/a.py",
      "@@ -1,1 +1,1 @@",
      "-a",
      "+b",
      "\\ No newline at end of file",
    ].join("\n");
    const rows = renderSideBySide(unified);
    expect(rows.filter((row) => row.kind === "change")).toHaveLength(1);
    expect(rows.some((row) => row.leftText?.includes("No newline"))).toBe(false);
  });

  it("returns an empty array for an empty diff", () => {
    expect(renderSideBySide("")).toEqual([]);
  });
});

describe("split and unified views agree on changed lines", () => {
  it.each([
    ["normal", NORMAL_DIFF],
    [
      "dashes and pluses",
      ["--- a/a.py", "+++ b/a.py", "@@ -1,2 +1,2 @@", "--- comment", "-return 0", "+++ count", "+return 1"].join(
        "\n",
      ),
    ],
    [
      "delete 3 add 1",
      ["--- a/a.py", "+++ b/a.py", "@@ -1,5 +1,3 @@", " a", "-b", "-c", "-d", "+X", " e"].join("\n"),
    ],
  ])("%s produces the same changed line set in both views", (_name, unified) => {
    expect([...sideChangedTexts(unified)].sort()).toEqual([...changedTexts(unified)].sort());
  });
});
