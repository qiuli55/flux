/**
 * fileTree.ts 单测：图标键映射、改动标记优先级、父目录与祖先目录推导。
 *
 * 覆盖正常值 + 边界（空数组、路径不存在、根目录、多提案优先级、目录标记）。
 */
import { describe, expect, it } from "vitest";

import type { Change, ChangeStatus } from "../api/types";
import { ancestorDirs, fileIconKey, markerForDir, markerForPath, parentDir } from "./fileTree";

/** 只关心 file_path / status 的最小提案工厂 */
function change(filePath: string, status: ChangeStatus): Change {
  return {
    id: `${filePath}-${status}`,
    project_id: null,
    task_id: null,
    group_id: null,
    file_path: filePath,
    original_hash: "hash",
    original_content: "old",
    proposed_content: "new",
    diff: "",
    added_lines: 1,
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
  };
}

describe("fileIconKey", () => {
  it("目录固定返回 dir", () => {
    expect(fileIconKey("app", "dir")).toBe("dir");
  });

  it("按扩展名映射常用语言", () => {
    expect(fileIconKey("auth.py", "file")).toBe("python");
    expect(fileIconKey("App.tsx", "file")).toBe("tsx");
    expect(fileIconKey("main.ts", "file")).toBe("typescript");
    expect(fileIconKey("legacy.js", "file")).toBe("javascript");
    expect(fileIconKey("styles.css", "file")).toBe("stylesheet");
    expect(fileIconKey("index.html", "file")).toBe("html");
    expect(fileIconKey("icon.svg", "file")).toBe("svg");
    expect(fileIconKey("pyproject.toml", "file")).toBe("toml");
    expect(fileIconKey("ci.yml", "file")).toBe("yaml");
    expect(fileIconKey("package.json", "file")).toBe("json");
    expect(fileIconKey("README.md", "file")).toBe("markdown");
    expect(fileIconKey("run.sh", "file")).toBe("shell");
  });

  it("扩展名大小写不敏感", () => {
    expect(fileIconKey("AUTH.PY", "file")).toBe("python");
  });

  it("无扩展名回退通用文件图标", () => {
    expect(fileIconKey("Dockerfile", "file")).toBe("file");
    expect(fileIconKey("", "file")).toBe("file");
  });

  it("以点开头的无扩展名文件归为配置", () => {
    expect(fileIconKey(".gitignore", "file")).toBe("config");
    expect(fileIconKey(".env", "file")).toBe("config");
  });

  it("未知扩展名回退通用文件图标", () => {
    expect(fileIconKey("bundle.xyz", "file")).toBe("file");
  });
});

describe("parentDir", () => {
  it("取最后一段之前的部分", () => {
    expect(parentDir("app/auth.py")).toBe("app");
    expect(parentDir("app/v1/auth.py")).toBe("app/v1");
  });

  it("根目录与单段路径返回空串", () => {
    expect(parentDir("README.md")).toBe("");
    expect(parentDir("")).toBe("");
  });

  it("忽略目录尾部的斜杠", () => {
    expect(parentDir("app/")).toBe("");
    expect(parentDir("app/v1/")).toBe("app");
    expect(parentDir("app/v1/auth.py/")).toBe("app/v1");
  });
});

describe("ancestorDirs", () => {
  it("从根到父逐级列出目录", () => {
    expect(ancestorDirs("app/v1/auth.py")).toEqual(["app", "app/v1"]);
  });

  it("顶层文件没有祖先目录", () => {
    expect(ancestorDirs("README.md")).toEqual([]);
    expect(ancestorDirs("")).toEqual([]);
  });
});

describe("markerForPath", () => {
  it("没有匹配路径时返回 null", () => {
    expect(markerForPath([], "app/auth.py")).toBeNull();
    expect(markerForPath([change("app/main.py", "pending")], "app/auth.py")).toBeNull();
  });

  it("pending 与 accepted 都算待审阅标记", () => {
    expect(markerForPath([change("app/auth.py", "pending")], "app/auth.py")).toBe("pending");
    expect(markerForPath([change("app/auth.py", "accepted")], "app/auth.py")).toBe("pending");
  });

  it("applied 与 failed 各自成档", () => {
    expect(markerForPath([change("app/auth.py", "applied")], "app/auth.py")).toBe("applied");
    expect(markerForPath([change("app/auth.py", "failed")], "app/auth.py")).toBe("failed");
  });

  it("已拒绝不产生标记", () => {
    expect(markerForPath([change("app/auth.py", "rejected")], "app/auth.py")).toBeNull();
  });

  it("多提案按 failed > pending > applied 归一", () => {
    const proposals = [
      change("app/auth.py", "applied"),
      change("app/auth.py", "pending"),
      change("app/auth.py", "accepted"),
    ];
    expect(markerForPath(proposals, "app/auth.py")).toBe("pending");
    expect(markerForPath([...proposals, change("app/auth.py", "failed")], "app/auth.py")).toBe("failed");
  });
});

describe("markerForDir", () => {
  it("子孙文件有提案时目录也显示标记", () => {
    const proposals = [change("app/v1/auth.py", "pending")];
    expect(markerForDir(proposals, "app")).toBe("pending");
    expect(markerForDir(proposals, "app/v1")).toBe("pending");
  });

  it("路径不相干的目录不显示标记", () => {
    expect(markerForDir([change("tests/test_auth.py", "pending")], "app")).toBeNull();
    expect(markerForDir([], "app")).toBeNull();
  });

  it("多个子孙文件时按最高优先级归一", () => {
    const proposals = [
      change("app/auth.py", "applied"),
      change("app/v1/users.py", "pending"),
      change("app/v1/projects.py", "failed"),
    ];
    expect(markerForDir(proposals, "app")).toBe("failed");
    expect(markerForDir(proposals, "app/v1")).toBe("failed");
  });

  it("根目录路径不产生标记", () => {
    expect(markerForDir([change("app/auth.py", "pending")], "")).toBeNull();
  });
});
