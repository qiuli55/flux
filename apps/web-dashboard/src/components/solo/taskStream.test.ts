import { describe, expect, it } from "vitest";

import type { TaskMessage, TaskStreamEvent } from "../../api/types";
import {
  TaskStreamReducer,
  formatElapsed,
  mergeMessages,
  reconnectDelayMs,
} from "./taskStream";

function event(seq: number, type: string, data: Record<string, unknown> = {}): TaskStreamEvent {
  return { seq, type, data };
}

function message(id: string, seq: number, content = ""): TaskMessage {
  return {
    id,
    task_id: "task-1",
    seq,
    role: "assistant",
    kind: "text",
    content,
    payload: {},
    created_at: "2026-01-01T00:00:00Z",
  } as TaskMessage;
}

describe("TaskStreamReducer", () => {
  it("按 seq 去重，重复事件不重复追加正文", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    reducer.apply(event(2, "assistant_delta", { text: "hello" }));
    reducer.flush();
    expect(reducer.snapshot().liveText).toBe("hello");

    expect(reducer.apply(event(2, "assistant_delta", { text: "hello" }))).toBe("ignored");
    reducer.flush();
    expect(reducer.snapshot().liveText).toBe("hello");
  });

  it("批量缓冲正文，flush 后一次性提交", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    expect(reducer.apply(event(2, "assistant_delta", { text: "a" }))).toBe("buffered");
    expect(reducer.apply(event(3, "assistant_delta", { text: "b" }))).toBe("buffered");
    expect(reducer.snapshot().liveText).toBe("");
    reducer.flush();
    expect(reducer.snapshot().liveText).toBe("ab");
    expect(reducer.snapshot().liveActive).toBe(true);
  });

  it("把连续 thinking 合并成一个思考块", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "run_started", { run_id: "r1", runtime: "claude-code" }));
    reducer.apply(event(2, "run_event", { kind: "thinking", text: "先看 " }));
    reducer.apply(event(3, "run_event", { kind: "thinking", text: "目录" }));
    reducer.flush();
    const snapshot = reducer.snapshot();
    expect(snapshot.reasoning?.text).toBe("先看 目录");
    expect(snapshot.reasoning?.active).toBe(true);
    expect(snapshot.runId).toBe("r1");
  });

  it("工具按 call_id 合并状态与输出", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "run_started", { run_id: "r1" }));
    reducer.apply(
      event(2, "run_event", {
        kind: "tool",
        call_id: "c1",
        name: "bash",
        detail: "ls",
        status: "started",
        category: "command",
      }),
    );
    let tools = reducer.snapshot().tools;
    expect(tools).toHaveLength(1);
    expect(tools[0]?.status).toBe("running");

    reducer.apply(
      event(3, "run_event", {
        kind: "tool",
        call_id: "c1",
        name: "bash",
        detail: "ls",
        status: "completed",
        output: "a\nb",
      }),
    );
    tools = reducer.snapshot().tools;
    expect(tools).toHaveLength(1);
    expect(tools[0]?.status).toBe("done");
    expect(tools[0]?.output).toBe("a\nb");
  });

  it("run_status 保留正文，run_finished 才清空 live", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    reducer.apply(event(2, "assistant_delta", { text: "完成内容" }));
    reducer.flush();
    reducer.apply(event(3, "run_status", { status: "completed" }));
    expect(reducer.snapshot().liveText).toBe("完成内容");
    expect(reducer.snapshot().liveActive).toBe(false);

    reducer.apply(event(4, "run_finished", { status: "completed", run_id: "r1" }));
    expect(reducer.snapshot().liveText).toBe("");
    expect(reducer.snapshot().terminalError).toBeNull();
  });

  it("assistant_message 结束后清空 live 并保留已完成内容", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    reducer.apply(event(2, "assistant_delta", { text: "回答" }));
    reducer.flush();
    reducer.apply(event(3, "assistant_message", { message: message("m1", 3, "回答") }));
    expect(reducer.snapshot().liveText).toBe("");
    expect(reducer.snapshot().liveActive).toBe(false);
  });

  it("超长正文只保留尾部并带折叠标记", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    reducer.apply(event(2, "assistant_delta", { text: "x".repeat(20_100) }));
    reducer.flush();
    const text = reducer.snapshot().liveText;
    expect(text.startsWith("…（前文已折叠）")).toBe(true);
    expect(text.length).toBeLessThanOrEqual(20_000 + "…（前文已折叠）\n".length);
    expect(text.endsWith("x")).toBe(true);
  });

  it("user_message 清空上一轮的实时状态", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "run_started", { run_id: "r1" }));
    reducer.apply(event(2, "run_event", { kind: "tool", call_id: "c1", name: "bash", status: "started" }));
    reducer.apply(event(3, "user_message", { message: message("m1", 3, "继续") }));
    const snapshot = reducer.snapshot();
    expect(snapshot.liveText).toBe("");
    expect(snapshot.tools).toEqual([]);
    expect(snapshot.reasoning).toBeNull();
    expect(snapshot.runId).toBeNull();
  });

  it("连接失败进入终态并保留正文", () => {
    const reducer = new TaskStreamReducer(() => 1_000);
    reducer.apply(event(1, "assistant_start"));
    reducer.apply(event(2, "assistant_delta", { text: "半句" }));
    reducer.flush();
    reducer.onDisconnected();
    reducer.onDisconnected();
    expect(reducer.snapshot().retryAttempt).toBe(2);
    reducer.onTerminal("实时连接失败");
    expect(reducer.snapshot().connection).toBe("failed");
    expect(reducer.snapshot().liveText).toBe("半句");
  });
});

describe("mergeMessages", () => {
  it("按 id 去重并按 seq 排序", () => {
    const merged = mergeMessages(
      [message("a", 1, "old"), message("c", 3)],
      [message("a", 1, "new"), message("b", 2)],
    );
    expect(merged.map((item) => item.id)).toEqual(["a", "b", "c"]);
    expect(merged[0]?.content).toBe("new");
  });
});

describe("reconnectDelayMs", () => {
  it("按 1/2/4/8/15 秒退避并封顶", () => {
    expect([1, 2, 3, 4, 5, 9].map(reconnectDelayMs)).toEqual([
      1_000, 2_000, 4_000, 8_000, 15_000, 15_000,
    ]);
  });
});

describe("formatElapsed", () => {
  it("格式化秒与分钟", () => {
    expect(formatElapsed(1_000, null, 1_000 + 12_000)).toBe("12s");
    expect(formatElapsed(0, 65_000, 999_999)).toBe("1m 05s");
    expect(formatElapsed(null, null, 0)).toBe("");
  });
});
