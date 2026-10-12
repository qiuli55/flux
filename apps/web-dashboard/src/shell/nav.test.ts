/**
 * nav.ts 单测：导航清单与标签的一致性。
 *
 * 保证每个路由视图都在侧栏出现且仅出现一次，且都有中文标签与分组，
 * 避免新增视图时漏配导致侧栏/面包屑缺项。
 */
import { describe, expect, it } from "vitest";

import { NAV_GROUPS, VIEW_GROUP, VIEW_LABEL } from "./nav";
import { VIEWS } from "../app/router";

describe("nav 配置", () => {
  it("侧栏覆盖全部视图且不重复", () => {
    const ids = NAV_GROUPS.flatMap((group) => group.items.map((item) => item.id));
    expect([...ids].sort()).toEqual([...VIEWS].sort());
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("每个视图都有标签与分组", () => {
    for (const view of VIEWS) {
      expect(VIEW_LABEL[view]).toBeTruthy();
      expect(VIEW_GROUP[view]).toBeTruthy();
    }
  });
});
