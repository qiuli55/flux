import { describe, expect, it } from "vitest";

import {
  COMPOSER_TEXT_MAX,
  LARGE_PASTE_THRESHOLD,
  PASTE_MODE_OPTIONS,
  isImagePaste,
  isLargePaste,
  pastedFileName,
  revealPastedText,
} from "./pastePolicy";

const multiline = "x".repeat(LARGE_PASTE_THRESHOLD - 2) + "\n行";

describe("pastePolicy", () => {
  it("requires both the threshold and a newline in file mode", () => {
    expect(isLargePaste("x".repeat(9_999), "file")).toBe(false);
    expect(isLargePaste("x".repeat(LARGE_PASTE_THRESHOLD), "file")).toBe(false);
    expect(isLargePaste(multiline, "file")).toBe(true);
    expect(isLargePaste(multiline, "text")).toBe(false);
  });

  it("does not convert images", () => {
    const imageItem = { type: "image/png" } as DataTransferItem;
    const textItem = { type: "text/plain" } as DataTransferItem;
    expect(isImagePaste([imageItem])).toBe(true);
    expect(isImagePaste([textItem])).toBe(false);
    expect(isImagePaste(null)).toBe(false);
  });

  it("builds a safe file name from the first line", () => {
    expect(pastedFileName("周报：本周进展\n正文")).toBe("周报：本周进展.txt");
    expect(pastedFileName("  a/b\\c\n正文")).toBe("a b c.txt");
    expect(pastedFileName("\n \n")).toBe("粘贴.txt");
  });

  it("reveals back into the input and refuses to exceed the message limit", () => {
    expect(revealPastedText("我的问题", "正文")).toEqual({ text: "我的问题\n\n正文" });
    expect(revealPastedText("x".repeat(COMPOSER_TEXT_MAX), "y").error).toContain(
      `超过 ${COMPOSER_TEXT_MAX} 字上限`,
    );
  });

  it("offers exactly two modes and defaults to file", () => {
    expect(PASTE_MODE_OPTIONS.map((item) => item.label)).toEqual([
      "始终作为文件",
      "始终直接粘贴",
    ]);
  });
});
