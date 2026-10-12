/**
 * soloIntent.ts 单测：意图队列的「先排队后取走」语义。
 *
 * 覆盖：已注册时立即执行、未注册时排队、注册时清空并补发队列、注销后重新排队。
 */
import { afterEach, describe, expect, it, vi } from "vitest";

import { queueSoloIntent, setSoloIntentHandler } from "./soloIntent";

afterEach(() => {
  setSoloIntentHandler(null);
});

describe("soloIntent", () => {
  it("已注册处理器时立即执行", () => {
    const seen: string[] = [];
    setSoloIntentHandler((intent) => seen.push(intent));
    queueSoloIntent("projects");
    expect(seen).toEqual(["projects"]);
  });

  it("未注册时排队，注册后补发并清空", () => {
    queueSoloIntent("projects");
    queueSoloIntent("agents");
    const handler = vi.fn();
    setSoloIntentHandler(handler);
    expect(handler.mock.calls.map((call) => call[0])).toEqual(["projects", "agents"]);
    // 队列已清空：再次注册不应重复补发
    const again = vi.fn();
    setSoloIntentHandler(again);
    expect(again).not.toHaveBeenCalled();
  });

  it("注销后重新进入排队流程", () => {
    setSoloIntentHandler(() => undefined);
    setSoloIntentHandler(null);
    const handler = vi.fn();
    queueSoloIntent("agents");
    setSoloIntentHandler(handler);
    expect(handler).toHaveBeenCalledWith("agents");
  });
});
