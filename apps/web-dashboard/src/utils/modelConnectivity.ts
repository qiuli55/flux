/**
 * 模型连通状态的展示映射。
 *
 * 后端只在真正探测过后才写 `available` / `unreachable`；没有记录的模型是
 * `unchecked`，界面不能把它显示成可用或不通。三处（密钥库列表、探测结果、
 * 模型下拉）共用这里的判断，避免各写一套口径。
 */
import type { KeyProbeModel, ModelConnectivity } from "../api/types";

export type ConnectivityLike =
  | Pick<ModelConnectivity, "status" | "ok" | "reason" | "reason_code">
  | Pick<KeyProbeModel, "status" | "ok" | "reason" | "reason_code">
  | null
  | undefined;

/** 只有确认过的失败才算「不通」。 */
export function isUnreachable(record: ConnectivityLike): boolean {
  if (!record) return false;
  if (record.status === "unreachable") return true;
  return record.status !== "available" && record.ok === false;
}

/** 只有确认过的成功才算「可用」。 */
export function isAvailable(record: ConnectivityLike): boolean {
  if (!record) return false;
  if (record.status === "available") return true;
  return record.status !== "unreachable" && record.ok === true;
}

/** 短标记：可用 / 不通；尚未检查过的模型返回空串（不显示任何标记）。 */
export function modelStatusLabel(record: ConnectivityLike): string {
  if (isUnreachable(record)) return "不通";
  if (isAvailable(record)) return "可用";
  return "";
}

/** 不通时给人看的原因；可用或未检查返回空串。 */
export function modelStatusReason(record: ConnectivityLike): string {
  if (!isUnreachable(record)) return "";
  return record?.reason?.trim() || "其他失败";
}

/** 选择器里的一行：label 后缀 + 悬停提示；未检查不伪造状态。 */
export function modelOptionSuffix(record: ConnectivityLike): string {
  return isUnreachable(record) ? " · 不通" : "";
}

export function modelOptionTitle(record: ConnectivityLike): string {
  const reason = modelStatusReason(record);
  return reason ? `上次检查不通：${reason}` : "";
}

export type ProbeEditField = "api-key" | "models" | "base-url" | "name";

/** 根据原因把编辑焦点放到最可能需要改的字段；额度/其他失败没有特定字段。 */
export function editFieldForReason(reasonCode: string | undefined): ProbeEditField | null {
  switch (reasonCode) {
    case "invalid_key":
      return "api-key";
    case "model_unavailable":
      return "models";
    case "network_unreachable":
      return "base-url";
    default:
      return null;
  }
}

export const PROBE_FIELD_IDS: Record<ProbeEditField, string> = {
  "api-key": "key-api-key",
  models: "key-models",
  "base-url": "key-base-url",
  name: "key-name",
};

/**
 * 聚焦到探测结果里「去修改」指向的现有表单字段（不新开页面）。
 * 额度 / 其他失败没有单一字段可改，也把焦点送回表单，避免按钮看起来没反应。
 */
export function focusProbeField(reasonCode: string | undefined): void {
  const field = editFieldForReason(reasonCode);
  const node = document.getElementById(field ? PROBE_FIELD_IDS[field] : PROBE_FIELD_IDS.name);
  node?.focus();
  node?.scrollIntoView({ block: "center", behavior: "smooth" });
}

/** 一段结果的汇总句；主展示仍然是逐模型列表。 */
export function probeSummaryText(result: {
  skipped?: boolean;
  models: { ok: boolean }[];
  error?: string;
  ok?: boolean;
}): string {
  if (result.skipped) return result.error || "未发起连通性检查";
  const total = result.models.length;
  if (total === 0) return result.error || "没有可检查的模型";
  const failed = result.models.filter((item) => !item.ok).length;
  if (failed === 0) return `全部 ${total} 个模型连通正常`;
  return `${total} 个模型中 ${failed} 个不通`;
}
