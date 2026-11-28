/**
 * 选模数据源（Web / 桌面同口径）。
 *
 * 权威来源是后端 `GET /agents` 里该 Agent 的 `spec.model_catalog`：
 * 只含**当前 Agent** 的通道（订阅账号 + 兼容密钥 + 原生），每条带来源与思考能力。
 * 不再以 DSH 模型目录当权威；DSH 目录仅在老服务端没有 model_catalog 时兜底。
 */
import type { AgentSpec, CatalogModel, ModelChannel } from "../../api/types";

export const ALL_MODELS = "all";

export interface ModelChoice {
  /** 稳定值：keyId::model / accountId::model / model */
  value: string;
  model: string;
  label: string;
  keyId: string | null;
  accountId: string | null;
  channelId: string;
  channelKind: "subscription" | "key" | "native";
  channelLabel: string;
  contextWindow: number | null;
  capabilities: string[];
  thinking: CatalogModel["thinking"];
}

function choiceOf(item: CatalogModel): ModelChoice {
  const accountId = item.account_id || null;
  const keyId = item.key_id || null;
  return {
    value: keyId ? `${keyId}::${item.id}` : accountId ? `${accountId}::${item.id}` : item.id,
    model: item.id,
    label: item.label || item.id,
    keyId,
    accountId,
    channelId: item.channel_id,
    channelKind: item.channel_kind || "native",
    channelLabel: item.channel_label || "",
    contextWindow: item.context_window ?? null,
    capabilities: item.capabilities ?? [],
    thinking: item.thinking,
  };
}

/** 该 Agent 的模型清单；没有 model_catalog 时用旧字段兜底。 */
export function buildModelChoices(spec: AgentSpec | null | undefined): ModelChoice[] {
  if (!spec) return [];
  if (spec.model_catalog && spec.model_catalog.length > 0) {
    return spec.model_catalog.map(choiceOf);
  }
  const choices: ModelChoice[] = [];
  for (const model of spec.models ?? []) {
    choices.push({
      value: model,
      model,
      label: model,
      keyId: null,
      accountId: null,
      channelId: "native",
      channelKind: "native",
      channelLabel: "本机默认",
      contextWindow: null,
      capabilities: [],
      thinking: { kind: "none", levels: [], labels: [], default: "", can_disable: false },
    });
  }
  for (const item of spec.key_models ?? []) {
    choices.push({
      value: `${item.key_id}::${item.model}`,
      model: item.model,
      label: item.model,
      keyId: item.key_id,
      accountId: null,
      channelId: `key:${item.key_id}`,
      channelKind: "key",
      channelLabel: `密钥库 · ${item.key_name}`,
      contextWindow: null,
      capabilities: [],
      thinking: { kind: "none", levels: [], labels: [], default: "", can_disable: false },
    });
  }
  return choices;
}

/** 分栏 chips：「全部」+ 每个订阅账号 + 每把兼容密钥（只属于当前 Agent）。 */
export function buildChannels(spec: AgentSpec | null | undefined): ModelChannel[] {
  return spec?.model_channels ?? [];
}

/** 按分栏筛选；「全部」仍只含当前 Agent。 */
export function choicesForChannel(choices: ModelChoice[], channelId: string): ModelChoice[] {
  if (!channelId || channelId === ALL_MODELS) return choices;
  return choices.filter((choice) => choice.channelId === channelId);
}

/** 上下文长度的展示串；未知返回 null，不写假数据。 */
export function contextWindowLabel(window: number | null): string | null {
  if (!window || window <= 0) return null;
  if (window >= 1_000_000 && window % 1_000_000 === 0) return `${window / 1_000_000}M ctx`;
  if (window >= 1_000 && window % 1_000 === 0) return `${window / 1_000}k ctx`;
  return `${window} ctx`;
}

/** 列表副行：来源 · 上下文 · 能力标签；没有的项不写。 */
export function modelDetailLine(choice: ModelChoice): string {
  return [
    choice.channelLabel,
    contextWindowLabel(choice.contextWindow),
    ...choice.capabilities,
  ]
    .filter((part): part is string => Boolean(part))
    .join(" · ");
}

/** 思考档：任务未存时落到模型默认档。 */
export function thinkingLevelOf(choice: ModelChoice, stored?: string | null): string {
  return (stored ?? "").trim() || choice.thinking.default;
}
