/**
 * editorState.ts 单测：提案选取优先级与三视图（原始代码 / AI 建议 / 我的修改）的模型推导。
 *
 * 覆盖正常值 + 边界（空数组、路径不存在、无提案、截断标记、草稿不保存）。
 */
import { describe, expect, it } from "vitest";

import type { Change, ChangeStatus, FileContent } from "../api/types";
import { buildEditorModel, pickProposal } from "./editorState";

function change(id: string, filePath: string, status: ChangeStatus): Change {
  return {
    id,
    project_id: null,
    task_id: null,
    group_id: null,
    file_path: filePath,
    kind: "modify",
    original_hash: "hash",
    original_content: `${id}-original`,
    proposed_content: `${id}-proposed`,
    diff: "",
    added_lines: 2,
    removed_lines: 1,
    hunks: 1,
    reason: null,
    summary: null,
    agent_source: null,
    status,
    backup_path: null,
    apply_error: null,
    expires_at: null,
    expired_reason: null,
    recovery_resolution: null,
  };
}

function file(content: string, truncated = false): FileContent {
  return { path: "app/auth.py", content, size: content.length, truncated };
}

describe("pickProposal", () => {
  it("没有提案时返回 null", () => {
    expect(pickProposal([], "app/auth.py")).toBeNull();
  });

  it("路径不存在时返回 null", () => {
    expect(pickProposal([change("a", "app/main.py", "pending")], "app/auth.py")).toBeNull();
  });

  it("优先级 pending > accepted > applied > 其它", () => {
    const pending = change("pending", "app/auth.py", "pending");
    const accepted = change("accepted", "app/auth.py", "accepted");
    const applied = change("applied", "app/auth.py", "applied");
    const rejected = change("rejected", "app/auth.py", "rejected");
    expect(pickProposal([applied, accepted, pending, rejected], "app/auth.py")?.id).toBe("pending");
    expect(pickProposal([applied, accepted, rejected], "app/auth.py")?.id).toBe("accepted");
    expect(pickProposal([applied, rejected], "app/auth.py")?.id).toBe("applied");
    expect(pickProposal([rejected], "app/auth.py")?.id).toBe("rejected");
  });

  it("同级多条取列表末尾（最新）的一条", () => {
    const proposals = [
      change("old", "app/auth.py", "pending"),
      change("new", "app/auth.py", "pending"),
    ];
    expect(pickProposal(proposals, "app/auth.py")?.id).toBe("new");
  });

  it("只挑选当前文件的提案", () => {
    const proposals = [
      change("other", "app/main.py", "pending"),
      change("mine", "app/auth.py", "applied"),
    ];
    expect(pickProposal(proposals, "app/auth.py")?.id).toBe("mine");
  });
});

describe("buildEditorModel", () => {
  it("原始代码视图：有提案时用提案的 original_content", () => {
    const proposal = change("c1", "app/auth.py", "pending");
    const model = buildEditorModel({
      viewMode: "original",
      fileContent: file("disk-content"),
      proposal,
      localDraft: null,
    });
    expect(model.text).toBe("c1-original");
    expect(model.badge).toBe("pending");
    expect(model.editable).toBe(false);
    expect(model.canSave).toBe(false);
    expect(model.notice).toBeNull();
  });

  it("原始代码视图：无提案时用 readFile 的内容", () => {
    const model = buildEditorModel({
      viewMode: "original",
      fileContent: file("disk-content"),
      proposal: null,
      localDraft: null,
    });
    expect(model.text).toBe("disk-content");
    expect(model.badge).toBeNull();
    expect(model.notice).toBeNull();
  });

  it("原始代码视图：文件被截断时必须提示", () => {
    const model = buildEditorModel({
      viewMode: "original",
      fileContent: file("head", true),
      proposal: null,
      localDraft: null,
    });
    expect(model.notice).toContain("256 KiB");
  });

  it("原始代码视图：还没读到文件内容时给出空文本而不是崩溃", () => {
    const model = buildEditorModel({
      viewMode: "original",
      fileContent: null,
      proposal: null,
      localDraft: null,
    });
    expect(model.text).toBe("");
    expect(model.notice).toBeNull();
  });

  it("AI 建议视图：展示 proposed_content", () => {
    const proposal = change("c2", "app/auth.py", "applied");
    const model = buildEditorModel({
      viewMode: "proposed",
      fileContent: null,
      proposal,
      localDraft: null,
    });
    expect(model.text).toBe("c2-proposed");
    expect(model.badge).toBe("applied");
    expect(model.editable).toBe(false);
  });

  it("AI 建议视图：无提案时给出禁用态与说明", () => {
    const model = buildEditorModel({
      viewMode: "proposed",
      fileContent: null,
      proposal: null,
      localDraft: null,
    });
    expect(model.text).toBe("");
    expect(model.editable).toBe(false);
    expect(model.notice).toContain("没有 AI 提案");
  });

  it("我的修改视图：可编辑但绝不显示为可保存", () => {
    const proposal = change("c3", "app/auth.py", "accepted");
    const model = buildEditorModel({
      viewMode: "draft",
      fileContent: null,
      proposal,
      localDraft: null,
    });
    expect(model.text).toBe("c3-proposed");
    expect(model.editable).toBe(true);
    expect(model.canSave).toBe(false);
    expect(model.notice).toContain("本地草稿");
    expect(model.notice).toContain("刷新后不保留");
  });

  it("我的修改视图：优先展示本地草稿", () => {
    const proposal = change("c4", "app/auth.py", "pending");
    const model = buildEditorModel({
      viewMode: "draft",
      fileContent: null,
      proposal,
      localDraft: "我的改动",
    });
    expect(model.text).toBe("我的改动");
  });

  it("我的修改视图：无提案时不可编辑且仍提示草稿不保存", () => {
    const model = buildEditorModel({
      viewMode: "draft",
      fileContent: null,
      proposal: null,
      localDraft: null,
    });
    expect(model.text).toBe("");
    expect(model.editable).toBe(false);
    expect(model.canSave).toBe(false);
    expect(model.notice).toContain("本地草稿");
  });
});
