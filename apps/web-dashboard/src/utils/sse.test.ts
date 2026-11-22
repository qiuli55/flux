import { describe, expect, it } from "vitest";

import { createSseParser } from "./sse";

describe("createSseParser", () => {
  it("解析完整帧", () => {
    const parser = createSseParser();
    const frames = parser.push('id: 7\nevent: task.event\ndata: {"seq":7}\n\n');
    expect(frames).toEqual([{ event: "task.event", data: '{"seq":7}', id: "7" }]);
  });

  it("跨 chunk 保留残帧", () => {
    const parser = createSseParser();
    expect(parser.push('data: {"a"')).toEqual([]);
    expect(parser.push(':1}\n')).toEqual([]);
    const frames = parser.push("\n");
    expect(frames).toEqual([{ event: null, data: '{"a":1}', id: null }]);
  });

  it("处理被 chunk 截断的 CRLF", () => {
    const parser = createSseParser();
    expect(parser.push("data: one\r")).toEqual([]);
    const frames = parser.push("\n\r\ndata: two\r\n\r\n");
    expect(frames.map((frame) => frame.data)).toEqual(["one", "two"]);
  });

  it("拼接多行 data 并忽略心跳", () => {
    const parser = createSseParser();
    const frames = parser.push(": keep-alive\n\ndata: a\ndata: b\n\n");
    expect(frames).toEqual([{ event: null, data: "a\nb", id: null }]);
  });

  it("忽略坏帧但保留后续帧", () => {
    const parser = createSseParser();
    const frames = parser.push("event: ping\n\ndata: ok\n\n");
    expect(frames.map((frame) => frame.data)).toEqual(["ok"]);
  });

  it("flush 吐出没有空行收尾的最后一帧", () => {
    const parser = createSseParser();
    expect(parser.push("data: tail")).toEqual([]);
    expect(parser.flush()).toEqual([{ event: null, data: "tail", id: null }]);
  });
});
