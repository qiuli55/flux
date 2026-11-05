/**
 * nav.ts 单测：导航清单与标签的一致性。
 *
 * 保证每个一级路由视图都在「模式切换 + 侧栏」中出现且仅出现一次，且都有中文标签与分组，
 * 避免新增视图时漏配导致入口/面包屑缺项。二级视图（如从 Bot 模式进入的群聊）不进侧栏。
 */
import { describe, expect, it } from "vitest";

import { NAV_GROUPS, NAV_MODES, VIEW_GROUP, VIEW_LABEL } from "./nav";
import type { View } from "../app/router";
import { VIEWS } from "../app/router";

/** 从其他页面进入的二级视图，不应出现在侧栏。 */
const NESTED_VIEWS: readonly View[] = ["groups"];

describe("nav 配置", () => {
  it("模式切换 + 侧栏覆盖全部一级视图且不重复", () => {
    const ids = [
      ...NAV_MODES.map((item) => item.id),
      ...NAV_GROUPS.flatMap((group) => group.items.map((item) => item.id)),
    ];
    const topLevel = VIEWS.filter((view) => !NESTED_VIEWS.includes(view));
    expect([...ids].sort()).toEqual([...topLevel].sort());
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("每个视图都有标签与分组", () => {
    for (const view of VIEWS) {
      expect(VIEW_LABEL[view]).toBeTruthy();
      expect(VIEW_GROUP[view]).toBeTruthy();
    }
  });
});
