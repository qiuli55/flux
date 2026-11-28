import { describe, expect, it } from "vitest";

import {
  editFieldForReason,
  isAvailable,
  isUnreachable,
  modelOptionSuffix,
  modelOptionTitle,
  modelStatusLabel,
  modelStatusReason,
  probeSummaryText,
} from "./modelConnectivity";

describe("modelConnectivity", () => {
  it("does not pretend unchecked models are available or unreachable", () => {
    expect(isUnreachable(undefined)).toBe(false);
    expect(isAvailable(undefined)).toBe(false);
    expect(modelStatusLabel(undefined)).toBe("");
    expect(modelStatusReason(undefined)).toBe("");
    expect(modelStatusLabel({ status: "unchecked" })).toBe("");
  });

  it("maps confirmed results to short labels and reasons", () => {
    expect(modelStatusLabel({ status: "available", ok: true })).toBe("可用");
    expect(modelStatusLabel({ status: "unreachable", ok: false })).toBe("不通");
    expect(modelStatusReason({ status: "unreachable", reason: "模型名不存在或不可用" })).toBe(
      "模型名不存在或不可用",
    );
    // 原因缺失时也不能显示空白
    expect(modelStatusReason({ status: "unreachable" })).toBe("其他失败");
    expect(modelOptionSuffix({ status: "unreachable" })).toBe(" · 不通");
    expect(modelOptionSuffix({ status: "available" })).toBe("");
    expect(modelOptionTitle({ status: "unreachable", reason: "额度用尽" })).toBe(
      "上次检查不通：额度用尽",
    );
  });

  it("routes 去修改 to the existing form field that likely needs editing", () => {
    expect(editFieldForReason("invalid_key")).toBe("api-key");
    expect(editFieldForReason("model_unavailable")).toBe("models");
    expect(editFieldForReason("network_unreachable")).toBe("base-url");
    expect(editFieldForReason("quota_exhausted")).toBeNull();
    expect(editFieldForReason("other")).toBeNull();
  });

  it("summarizes per-model results without hiding failures", () => {
    expect(
      probeSummaryText({ models: [{ ok: true }, { ok: true }] }),
    ).toBe("全部 2 个模型连通正常");
    expect(
      probeSummaryText({ models: [{ ok: true }, { ok: false }] }),
    ).toBe("2 个模型中 1 个不通");
    expect(probeSummaryText({ skipped: true, models: [], error: "密钥已停用，未发起连通性校验" }))
      .toBe("密钥已停用，未发起连通性校验");
  });
});
