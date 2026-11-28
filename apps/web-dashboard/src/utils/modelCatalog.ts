/**
 * 模型选择器的目录：把 GET /dsh/models 映射成下拉项，并提供轻量变更通知。
 *
 * 设置页保存密钥、完成连通性检查后调用 notifyModelCatalogChanged()；
 * 仍挂载着的对话面板订阅后会重新拉取目录，就地更新「可用 / 不通」与原因，
 * 不需要卸载面板或重新打开选择器。用模块内订阅集合，不引入状态库、不轮询。
 */
import type { DshModels } from "../api/types";
import { modelOptionSuffix } from "./modelConnectivity";

/** 模型下拉的一项：value 编码 keyId::model（原生模型只有 model）。 */
export interface ModelChoice {
  value: string;
  label: string;
  keyId: string | null;
  /** 密钥库模型的最近一次探测状态；原生模型没有这项检查 */
  status?: string;
  reason?: string;
}

/**
 * 原生模型 + 密钥库模型。
 * 没有探测记录的模型不带「不通」后缀，也不伪造可用状态。
 */
export function buildModelChoices(data: DshModels): ModelChoice[] {
  return [
    ...data.models.map((model) => ({ value: model, label: model, keyId: null })),
    ...data.key_models.map((item) => ({
      value: `${item.key_id}::${item.model}`,
      label: `${item.model}${modelOptionSuffix(item)} · ${item.key_name}`,
      keyId: item.key_id,
      status: item.status,
      reason: item.reason,
    })),
  ];
}

const listeners = new Set<() => void>();

/** 密钥库保存 / 连通性检查完成后广播：让仍挂载的选择器重新拉目录。 */
export function notifyModelCatalogChanged(): void {
  listeners.forEach((fn) => fn());
}

/** 订阅目录变更；返回取消订阅函数。 */
export function subscribeModelCatalog(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
