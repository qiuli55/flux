// @vitest-environment jsdom
/**
 * CodeArea 组件交互测试：内联 ghost、下拉候选、Tab 接受、行首命令、AI 降级。
 *
 * 只覆盖「组件把补全逻辑接起来」的部分：候选生成与文本替换的规则在
 * completion.test.ts 里已单独覆盖，这里验证事件流与 DOM 产出的接线。
 */
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AI_DEBOUNCE_MS, type AiCompletionInput } from "../../utils/completion";
import { CodeArea } from "./CodeArea";

interface HarnessProps {
  initial?: string;
  kind?: "md" | "py" | "code";
  files?: string[];
  readOnly?: boolean;
  aiComplete?: (input: AiCompletionInput) => Promise<string | null>;
}

/** 受控宿主：把当前文本透出到 <output>，便于断言组件写回的内容 */
function Harness({ initial = "", kind = "code", files, readOnly, aiComplete }: HarnessProps) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <CodeArea
        path="a.ts"
        value={value}
        kind={kind}
        onChange={setValue}
        renderTokens={(text) => text}
        files={files}
        readOnly={readOnly}
        aiComplete={aiComplete}
      />
      <output data-testid="value">{value}</output>
    </>
  );
}

function editor(): HTMLTextAreaElement {
  return screen.getByLabelText("a.ts 编辑器") as HTMLTextAreaElement;
}

/** 模拟一次输入：同时给出新文本与落点光标 */
function type(next: string, caret = next.length) {
  fireEvent.change(editor(), {
    target: { value: next, selectionStart: caret, selectionEnd: caret },
  });
}

/** 推进 AI 防抖定时器并让 promise 回调落地 */
async function settleAi(ms = AI_DEBOUNCE_MS + 20) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("CodeArea 内联 ghost", () => {
  it("AI 结果作为 ghost 展示，Tab 接受后写入正文", async () => {
    const aiComplete = vi.fn(async () => "return 1;");
    render(<Harness aiComplete={aiComplete} />);

    type("function a() {");
    await settleAi();

    expect(aiComplete).toHaveBeenCalledTimes(1);
    expect(document.querySelector(".ca-ghost")?.textContent).toBe("return 1;");

    fireEvent.keyDown(editor(), { key: "Tab" });

    expect(screen.getByTestId("value").textContent).toBe("function a() {return 1;");
    expect(document.querySelector(".ca-ghost")).toBeNull();
  });

  it("未提供 aiComplete 时不产生 ghost", async () => {
    render(<Harness />);
    type("function a() {");
    await settleAi();
    expect(document.querySelector(".ca-ghost")).toBeNull();
  });

  it("aiComplete 抛错时静默降级", async () => {
    const aiComplete = vi.fn(async () => {
      throw new Error("offline");
    });
    render(<Harness aiComplete={aiComplete} />);

    type("function a() {");
    await settleAi();

    expect(document.querySelector(".ca-ghost")).toBeNull();
    expect(screen.getByTestId("value").textContent).toBe("function a() {");
  });
});

describe("CodeArea 下拉候选", () => {
  it("输入前缀弹出下拉，Enter 接受候选项", () => {
    render(<Harness />);
    type("con");

    expect(document.querySelector(".ca-menu")).not.toBeNull();
    const labels = document.querySelectorAll(".ca-item .ca-label");
    expect(labels[0]?.textContent).toBe("const");

    fireEvent.keyDown(editor(), { key: "Enter" });

    expect(screen.getByTestId("value").textContent).toBe("const");
    expect(document.querySelector(".ca-menu")).toBeNull();
  });

  it("无候选时 Tab 插入两空格缩进", () => {
    render(<Harness />);
    fireEvent.keyDown(editor(), { key: "Tab" });
    expect(screen.getByTestId("value").textContent).toBe("  ");
  });
});

describe("CodeArea 行首命令", () => {
  it("命令不进正文，改为触发一次带指令的 AI 请求", async () => {
    const aiComplete = vi.fn(async (_input: AiCompletionInput) => null);
    render(<Harness aiComplete={aiComplete} />);

    type("/ex");
    expect(document.querySelector(".ca-label")?.textContent).toBe("/explain");

    fireEvent.keyDown(editor(), { key: "Enter" });
    expect(screen.getByTestId("value").textContent).toBe("");

    await settleAi(20);

    expect(aiComplete).toHaveBeenCalledTimes(1);
    const input = aiComplete.mock.calls[0]?.[0];
    expect(input?.instruction).toContain("解释这段代码");
  });
});
