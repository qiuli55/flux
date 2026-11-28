import { describe, expect, it } from "vitest";

import type { AgentSpec } from "../../api/types";
import {
  ALL_MODELS,
  buildChannels,
  buildModelChoices,
  choicesForChannel,
  contextWindowLabel,
  modelDetailLine,
  thinkingLevelOf,
} from "./modelCatalog";

const spec: AgentSpec = {
  name: "codex-agent",
  role: "developer",
  model_provider: "openai",
  model_name: "gpt-5.5",
  description: "",
  skills: [],
  tools: [],
  permissions: [],
  runtime: "codex",
  models: ["gpt-5.5"],
  model: "gpt-5.5",
  key_models: [],
  model_channels: [
    {
      id: "subscription:acc-1",
      kind: "subscription",
      label: "Codex · user@example.com",
      vendor: "OpenAI",
      account_id: "acc-1",
      key_id: "",
      detail: "user@example.com",
      enabled: true,
    },
    {
      id: "key:k1",
      kind: "key",
      label: "密钥库 · 我的 key",
      vendor: "OpenAI",
      account_id: "",
      key_id: "k1",
      detail: "••••abcd",
      enabled: true,
    },
  ],
  model_catalog: [
    {
      id: "gpt-5.5",
      label: "gpt-5.5",
      channel_id: "subscription:acc-1",
      channel_kind: "subscription",
      channel_label: "Codex · user@example.com",
      account_id: "acc-1",
      key_id: "",
      provider: "openai",
      context_window: 400_000,
      capabilities: ["思考", "图片输入", "工具调用"],
      thinking: {
        kind: "levels",
        levels: ["minimal", "low", "medium", "high"],
        labels: ["极简", "低", "中", "高"],
        default: "medium",
        can_disable: true,
      },
    },
    {
      id: "deepseek-chat",
      label: "deepseek-chat",
      channel_id: "key:k1",
      channel_kind: "key",
      channel_label: "密钥库 · 我的 key",
      account_id: "",
      key_id: "k1",
      provider: "deepseek",
      context_window: 128_000,
      capabilities: ["思考", "工具调用"],
      thinking: {
        kind: "toggle",
        levels: ["on", "off"],
        labels: ["开", "关"],
        default: "on",
        can_disable: true,
      },
    },
  ],
};

describe("modelCatalog", () => {
  it("uses the current Agent's model_catalog, not a global DSH list", () => {
    const choices = buildModelChoices(spec);
    expect(choices.map((item) => item.model)).toEqual(["gpt-5.5", "deepseek-chat"]);
    expect(choices.some((item) => item.model.includes("claude"))).toBe(false);
  });

  it("keeps subscription accounts and each key as separate channels", () => {
    const channels = buildChannels(spec);
    expect(channels.map((item) => item.id)).toEqual(["subscription:acc-1", "key:k1"]);
    const choices = buildModelChoices(spec);
    expect(choicesForChannel(choices, "subscription:acc-1").map((item) => item.model)).toEqual([
      "gpt-5.5",
    ]);
    expect(choicesForChannel(choices, "key:k1").map((item) => item.model)).toEqual([
      "deepseek-chat",
    ]);
    // 「全部」仍只含当前 Agent 的通道
    expect(choicesForChannel(choices, ALL_MODELS)).toHaveLength(2);
  });

  it("renders honest context/capability metadata and thinking defaults", () => {
    const choices = buildModelChoices(spec);
    expect(contextWindowLabel(400_000)).toBe("400k ctx");
    expect(contextWindowLabel(null)).toBeNull();
    expect(modelDetailLine(choices[0]!)).toContain("思考");
    expect(modelDetailLine(choices[0]!)).toContain("400k ctx");
    expect(thinkingLevelOf(choices[0]!, null)).toBe("medium");
    expect(thinkingLevelOf(choices[1]!, "off")).toBe("off");
  });

  it("falls back to old fields when model_catalog is absent", () => {
    const legacy: AgentSpec = {
      ...spec,
      model_channels: undefined,
      model_catalog: undefined,
      key_models: [
        {
          key_id: "k9",
          key_name: "旧 key",
          provider: "openai",
          provider_title: "OpenAI",
          model: "gpt-5.5",
        },
      ],
    };
    const choices = buildModelChoices(legacy);
    expect(choices.map((item) => item.value)).toEqual(["gpt-5.5", "k9::gpt-5.5"]);
    expect(choices[1]!.keyId).toBe("k9");
  });
});
