/**
 * completion.ts 单测：偏移工具、四类候选生成、插入应用、AI 提示词与结果清洗。
 *
 * 覆盖正常值 + 边界（空前缀、词已输完、光标在行首、字符串外、多行缩进、围栏清洗）。
 */
import { describe, expect, it } from "vitest";

import {
  applyCompletion,
  buildCompletion,
  buildCompletionMessages,
  cleanCompletion,
  collectWords,
  completionLineCount,
  ghostLine,
  insideString,
  leadingIndent,
  lineEnd,
  lineStart,
  wordRange,
} from "./completion";

describe("偏移工具", () => {
  const text = "const a = 1;\nconst b = 2;\nlet c;";

  it("lineStart 落在换行之后", () => {
    expect(lineStart(text, 0)).toBe(0);
    expect(lineStart(text, 15)).toBe(13);
    expect(lineStart(text, 13)).toBe(13);
  });

  it("lineEnd 落在换行之前", () => {
    expect(lineEnd(text, 0)).toBe(12);
    expect(lineEnd(text, 13)).toBe(25);
    expect(lineEnd(text, 26)).toBe(text.length);
  });

  it("wordRange 覆盖整个词（含光标之后的尾巴）", () => {
    //              0123456
    const line = "const foobar;";
    expect(wordRange(line, 8)).toEqual({ start: 6, end: 12 });
    expect(wordRange(line, 6)).toEqual({ start: 6, end: 12 });
    expect(wordRange(line, 12)).toEqual({ start: 6, end: 12 });
  });

  it("wordRange 在非词位置返回空区间", () => {
    expect(wordRange("a + b", 2)).toEqual({ start: 2, end: 2 });
  });

  it("leadingIndent 取当前行前导空白", () => {
    expect(leadingIndent("  foo", 5)).toBe("  ");
    expect(leadingIndent("const x = fo", 12)).toBe("");
    expect(leadingIndent("a\n\tbar", 6)).toBe("\t");
  });

  it("insideString 只认未闭合的引号", () => {
    expect(insideString('from "./ut', 9)).toBe(true);
    expect(insideString('from "./utils";', 15)).toBe(false);
    expect(insideString("const a = 1;", 11)).toBe(false);
    expect(insideString('const s = "a\\"b";', 17)).toBe(false);
  });
});

describe("collectWords", () => {
  it("按出现次数降序，忽略单字符", () => {
    const words = collectWords("alpha beta alpha a beta alpha");
    expect(words[0]).toEqual({ word: "alpha", count: 3 });
    expect(words.map((entry) => entry.word)).toEqual(["alpha", "beta"]);
  });
});

describe("buildCompletion · word", () => {
  it("关键字与前缀匹配时返回候选", () => {
    const state = buildCompletion({ text: "ret", caret: 3, kind: "code" });
    expect(state?.mode).toBe("word");
    expect(state?.prefix).toBe("ret");
    expect(state?.items.map((item) => item.label)).toContain("return");
  });

  it("本文档标识符也进候选", () => {
    const text = "const myHelper = 1;\nmyHel";
    const state = buildCompletion({ text, caret: text.length, kind: "code" });
    expect(state?.items.map((item) => item.label)).toContain("myHelper");
  });

  it("片段候选带多行 body", () => {
    const state = buildCompletion({ text: "f", caret: 1, kind: "py" });
    const snippet = state?.items.find((item) => item.label === "for");
    expect(snippet?.kind).toBe("snippet");
    expect(snippet?.insertText).toContain("\n");
  });

  it("空前缀不提示", () => {
    expect(buildCompletion({ text: "", caret: 0, kind: "code" })).toBeNull();
  });

  it("词已经输完（无更长候选）时返回 null", () => {
    expect(buildCompletion({ text: "return", caret: 6, kind: "code" })).toBeNull();
  });

  it("替换区间覆盖整词，光标的尾巴也被吃掉", () => {
    const state = buildCompletion({ text: "retX", caret: 3, kind: "code" });
    expect(state?.start).toBe(0);
    expect(state?.end).toBe(4);
  });

  it("markdown 只给本文档词，不给关键字", () => {
    const state = buildCompletion({ text: "const co", caret: 8, kind: "md" });
    expect(state?.items.every((item) => item.kind === "word")).toBe(true);
  });
});

describe("buildCompletion · command", () => {
  it("行首斜杠触发命令补全", () => {
    const state = buildCompletion({ text: "/ex", caret: 3, kind: "code" });
    expect(state?.mode).toBe("command");
    expect(state?.prefix).toBe("/ex");
    expect(state?.items.map((item) => item.label)).toEqual(["/explain"]);
    expect(state?.items[0]?.insertText).toBe("");
  });

  it("前置空白也算行首", () => {
    const state = buildCompletion({ text: "    /", caret: 5, kind: "code" });
    expect(state?.mode).toBe("command");
    expect(state?.start).toBe(4);
    expect(state?.items.length).toBeGreaterThan(1);
  });

  it("斜杠后跟空格即结束命令输入", () => {
    expect(buildCompletion({ text: "/explain ", caret: 9, kind: "code" })?.mode).not.toBe("command");
  });

  it("行中间的斜杠不算命令", () => {
    expect(buildCompletion({ text: "a /ex", caret: 5, kind: "code" })?.mode).not.toBe("command");
  });
});

describe("buildCompletion · path", () => {
  const files = ["src/utils/completion.ts", "src/utils/diff.ts", "src/main.tsx", "README.md"];

  it("字符串里的相对路径按工作区文件补全", () => {
    const text = 'import { x } from "./src/utils/com';
    const state = buildCompletion({ text, caret: text.length, kind: "code", files });
    expect(state?.mode).toBe("path");
    expect(state?.items[0]?.insertText).toBe("./src/utils/completion.ts");
  });

  it("无斜杠但以点开头也提示", () => {
    const text = 'require("./README';
    const state = buildCompletion({ text, caret: text.length, kind: "code", files });
    expect(state?.mode).toBe("path");
  });

  it("字符串外不提示路径", () => {
    const text = "const p = util";
    expect(buildCompletion({ text, caret: text.length, kind: "code", files })?.mode).not.toBe("path");
  });

  it("没有工作区文件时退化成词补全", () => {
    const text = 'from "./utils/com';
    expect(buildCompletion({ text, caret: text.length, kind: "code" })?.mode).not.toBe("path");
  });
});

describe("applyCompletion", () => {
  it("单行候选直接替换前缀区间", () => {
    const text = "ret";
    const state = buildCompletion({ text, caret: 3, kind: "code" });
    const item = state!.items.find((entry) => entry.label === "return")!;
    const applied = applyCompletion(text, state!, item);
    expect(applied.text).toBe("return");
    expect(applied.caret).toBe(6);
  });

  it("多行片段按当前行缩进补齐", () => {
    const text = "  i";
    const state = buildCompletion({ text, caret: 3, kind: "code" });
    const item = state!.items.find((entry) => entry.label === "if")!;
    const applied = applyCompletion(text, state!, item);
    expect(applied.text).toBe("  if (condition) {\n    \n  }");
    expect(applied.caret).toBe(applied.text.length);
  });
});

describe("AI 提示词与清洗", () => {
  it("messages 带文件、语言、任务与 <CURSOR>", () => {
    const messages = buildCompletionMessages({
      path: "src/a.py",
      kind: "py",
      text: "def run():\n    x = 1\n",
      caret: "def run():\n    x = 1\n".length,
    });
    expect(messages).toHaveLength(2);
    expect(messages[0]?.role).toBe("system");
    expect(messages[1]?.content).toContain("src/a.py");
    expect(messages[1]?.content).toContain("Python");
    expect(messages[1]?.content).toContain("<CURSOR>");
  });

  it("自定义指令替换默认任务描述", () => {
    const messages = buildCompletionMessages({
      path: "a.ts",
      kind: "code",
      text: "const a = 1;",
      caret: 4,
      instruction: "解释这段代码",
    });
    expect(messages[1]?.content).toContain("解释这段代码");
    expect(messages[1]?.content).not.toContain("补全 <CURSOR> 处的代码。");
  });

  it("清洗 Markdown 围栏与行尾空白", () => {
    expect(cleanCompletion("```python\nx = 1  \n```")).toBe("x = 1");
    expect(cleanCompletion("y = 2")).toBe("y = 2");
    expect(cleanCompletion("")).toBe("");
  });

  it("ghostLine 只取第一行，completionLineCount 数总行数", () => {
    const multi = "foo()\nbar()\nbaz()";
    expect(ghostLine(multi)).toBe("foo()");
    expect(completionLineCount(multi)).toBe(3);
    expect(completionLineCount("")).toBe(0);
  });
});
