/**
 * router.ts 单测：部署前缀推断、路径→视图解析、视图→路径生成。
 *
 * 重点覆盖新增视图（overview/tasks/skills/diagnostics/settings）在
 * 无前缀与带部署前缀两种部署下的解析，以及未知段回退到默认视图。
 */
import { describe, expect, it } from "vitest";

import { DEFAULT_VIEW, detectBase, pathFor, viewFromPath, VIEWS } from "./router";

const BASE = import.meta.env.BASE_URL;

describe("detectBase", () => {
  it("把已知视图段当作视图而非部署前缀", () => {
    for (const view of VIEWS) expect(detectBase(`/${view}`)).toBe(BASE);
  });

  it("把未知首段识别为部署前缀", () => {
    expect(detectBase("/flux-v2/ide")).toBe("/flux-v2/");
    expect(detectBase("/app/tasks")).toBe("/app/");
  });
});

describe("viewFromPath", () => {
  it("解析每个已知视图", () => {
    for (const view of VIEWS) expect(viewFromPath(`/${view}`)).toBe(view);
  });

  it("带部署前缀时仍能解析", () => {
    expect(viewFromPath("/flux-v2/skills")).toBe("skills");
    expect(viewFromPath("/flux-v2/diagnostics")).toBe("diagnostics");
  });

  it("未知或空路径回退到默认视图", () => {
    expect(viewFromPath("/")).toBe(DEFAULT_VIEW);
    expect(viewFromPath("/unknown")).toBe(DEFAULT_VIEW);
    expect(viewFromPath("/flux-v2/nope")).toBe(DEFAULT_VIEW);
  });
});

describe("pathFor", () => {
  it("保留当前部署前缀", () => {
    expect(pathFor("tasks", "/flux-v2/ide")).toBe("/flux-v2/tasks");
    expect(pathFor("settings", `/overview`)).toBe(`${BASE}settings`);
  });
});
