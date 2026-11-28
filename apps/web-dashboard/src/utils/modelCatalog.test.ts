import { describe, expect, it } from "vitest";

import type { DshModels, KeyModelOption } from "../api/types";
import {
  buildModelChoices,
  notifyModelCatalogChanged,
  subscribeModelCatalog,
  type ModelChoice,
} from "./modelCatalog";
import { modelOptionTitle } from "./modelConnectivity";

function keyModel(overrides: Partial<KeyModelOption>): KeyModelOption {
  return {
    key_id: "key-1",
    key_name: "DeepSeek 官方",
    provider: "deepseek",
    provider_title: "DeepSeek",
    model: "deepseek-v4-pro",
    ...overrides,
  };
}

function catalog(item: KeyModelOption): DshModels {
  return { models: [], model: "", key_models: [item] };
}

function vaultChoice(choices: ModelChoice[]): ModelChoice {
  const choice = choices.find((item) => item.keyId !== null);
  if (!choice) throw new Error("missing vault choice");
  return choice;
}

describe("modelCatalog", () => {
  it("broadcasts only to current subscribers", () => {
    let first = 0;
    let second = 0;
    const unsubscribeFirst = subscribeModelCatalog(() => { first += 1; });
    const unsubscribeSecond = subscribeModelCatalog(() => { second += 1; });

    notifyModelCatalogChanged();
    expect(first).toBe(1);
    expect(second).toBe(1);

    unsubscribeFirst();
    notifyModelCatalogChanged();
    expect(first).toBe(1);
    expect(second).toBe(2);

    unsubscribeSecond();
    notifyModelCatalogChanged();
    expect(second).toBe(2);
  });

  it("does not mark unchecked models as unreachable", () => {
    const choices = buildModelChoices(catalog(keyModel({ status: "unchecked" })));
    const choice = vaultChoice(choices);

    expect(choice.label).toBe("deepseek-v4-pro · DeepSeek 官方");
    expect(choice.status).toBe("unchecked");
    expect(modelOptionTitle(choice)).toBe("");
  });

  it("refreshes the picker after a notification clears a stale unreachable mark", () => {
    // 模拟后端目录：第一次检查不通，修好后再检查变为可用。
    let source = catalog(
      keyModel({ status: "unreachable", reason: "模型名不存在或不可用" }),
    );
    let choices = buildModelChoices(source);
    const unsubscribe = subscribeModelCatalog(() => {
      choices = buildModelChoices(source);
    });

    expect(vaultChoice(choices).label).toContain(" · 不通");
    expect(modelOptionTitle(vaultChoice(choices))).toBe(
      "上次检查不通：模型名不存在或不可用",
    );

    // 后端状态已更新：不卸载选择器，只靠通知就地重建。
    source = catalog(keyModel({ status: "available" }));
    notifyModelCatalogChanged();

    expect(vaultChoice(choices).label).toBe("deepseek-v4-pro · DeepSeek 官方");
    expect(modelOptionTitle(vaultChoice(choices))).toBe("");

    unsubscribe();
  });

  it("keeps native models and vault models side by side", () => {
    const choices = buildModelChoices({
      models: ["native-model"],
      model: "native-model",
      key_models: [keyModel({})],
    });

    expect(choices.map((choice) => choice.value)).toEqual([
      "native-model",
      "key-1::deepseek-v4-pro",
    ]);
    expect(choices[0]?.keyId).toBeNull();
  });
});
