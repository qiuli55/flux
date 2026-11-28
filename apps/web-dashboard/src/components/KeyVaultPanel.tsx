/**
 * 密钥库面板：加密保存供应商 API Key，供 Agent 的模型通道选用。
 *
 * 列表只展示掩码与元数据；明文只在新建/换 key 的请求体里出现，后端落库即加密。
 */
import { useCallback, useEffect, useState } from "react";

import { api, ApiError } from "../api/client";
import type {
  ApiKeyCreateRequest,
  ApiKeyEntry,
  ApiKeyUpdateRequest,
  KeyProbeResult,
} from "../api/types";
import { toast } from "../app/toast";
import { Modal } from "../shell/Overlay";
import { Badge, Button, Card, Field, Input, SectionTitle, Switch, Textarea } from "../shell/ui";
import { notifyModelCatalogChanged } from "../utils/modelCatalog";
import {
  focusProbeField,
  isUnreachable,
  modelStatusReason,
  probeSummaryText,
} from "../utils/modelConnectivity";

type Provider = "deepseek" | "openai" | "openai_compatible" | "anthropic";
type WireApi = "" | "chat" | "responses";

const PROVIDERS: { value: Provider; label: string; defaultUrl: string }[] = [
  { value: "deepseek", label: "DeepSeek", defaultUrl: "https://api.deepseek.com" },
  { value: "openai", label: "OpenAI", defaultUrl: "https://api.openai.com/v1" },
  { value: "openai_compatible", label: "OpenAI 兼容", defaultUrl: "" },
  { value: "anthropic", label: "Anthropic", defaultUrl: "https://api.anthropic.com" },
];

interface FormState {
  name: string;
  provider: Provider;
  base_url: string;
  api_key: string;
  modelsText: string;
  wire_api: WireApi;
  enabled: boolean;
}

const EMPTY_FORM: FormState = {
  name: "",
  provider: "deepseek",
  base_url: "",
  api_key: "",
  modelsText: "",
  wire_api: "",
  enabled: true,
};

function providerLabel(provider: string): string {
  return PROVIDERS.find((item) => item.value === provider)?.label ?? provider;
}

function splitModels(text: string): string[] {
  return [...new Set(text.split(/[\n,，]/).map((item) => item.trim()).filter(Boolean))];
}

type ProbeState =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "done"; result: KeyProbeResult }
  | { kind: "error"; message: string };

export function KeyVaultPanel() {
  const [keys, setKeys] = useState<ApiKeyEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ApiKeyEntry | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ApiKeyEntry | null>(null);
  const [savedId, setSavedId] = useState<string | null>(null);
  const [probe, setProbe] = useState<ProbeState>({ kind: "idle" });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setKeys(await api.listApiKeys());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "读取密钥库失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setSavedId(null);
    setProbe({ kind: "idle" });
    setFormOpen(true);
  };

  const openEdit = (entry: ApiKeyEntry) => {
    setEditing(entry);
    setSavedId(entry.id);
    setProbe({ kind: "idle" });
    setForm({
      name: entry.name,
      provider: entry.provider as Provider,
      base_url: entry.base_url,
      api_key: "",
      modelsText: entry.models.join("\n"),
      wire_api: (entry.wire_api as WireApi) || "",
      enabled: entry.enabled,
    });
    setFormOpen(true);
  };

  /** 保存成功后按模型做一次轻量连通检查；检查失败不推翻已保存的配置。 */
  const runProbe = useCallback(async (keyId: string) => {
    setProbe({ kind: "checking" });
    try {
      const result = await api.probeApiKey(keyId);
      setProbe({ kind: "done", result });
    } catch (err) {
      setProbe({
        kind: "error",
        message: err instanceof ApiError ? err.message : "后端无响应",
      });
    } finally {
      await load();
      // 检查成功或请求失败（配置仍在）都刷新选择器：拿到最新通/不通，
      // 或至少让新保存的模型以「未检查」出现，而不是停留在旧目录。
      notifyModelCatalogChanged();
    }
  }, [load]);

  const save = async () => {
    const name = form.name.trim();
    const models = splitModels(form.modelsText);
    if (!name) { toast("请填写密钥名称"); return; }
    if (models.length === 0) { toast("至少填写一个模型 id"); return; }
    if (!editing && !form.api_key.trim()) { toast("请填写 API Key"); return; }
    setSaving(true);
    try {
      let saved: ApiKeyEntry;
      if (editing) {
        const body: ApiKeyUpdateRequest = {
          name,
          provider: form.provider,
          base_url: form.base_url.trim(),
          models,
          wire_api: form.wire_api,
          enabled: form.enabled,
        };
        if (form.api_key.trim()) body.api_key = form.api_key.trim();
        saved = await api.updateApiKey(editing.id, body);
        toast("已更新密钥，正在检查连通性…");
      } else {
        const body: ApiKeyCreateRequest = {
          name,
          provider: form.provider,
          base_url: form.base_url.trim(),
          api_key: form.api_key.trim(),
          models,
          wire_api: form.wire_api,
          enabled: form.enabled,
        };
        saved = await api.createApiKey(body);
        toast("已保存密钥，正在检查连通性…");
      }
      // 保持在现有编辑表单里：下面列出各模型结果，可直接「去修改」再保存。
      setEditing(saved);
      setSavedId(saved.id);
      setForm((prev) => ({ ...prev, api_key: "" }));
      // 配置已落盘，先广播一次：即使这次探测较慢或失败，选择器也能看到新模型。
      notifyModelCatalogChanged();
      await runProbe(saved.id);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async (entry: ApiKeyEntry, enabled: boolean) => {
    try {
      await api.updateApiKey(entry.id, { enabled });
      setKeys((prev) => prev.map((item) => (item.id === entry.id ? { ...item, enabled } : item)));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "更新失败");
    }
  };

  const remove = async () => {
    if (!deleteTarget) return;
    try {
      await api.deleteApiKey(deleteTarget.id);
      toast("已删除密钥");
      setDeleteTarget(null);
      await load();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "删除失败");
    }
  };

  const isCompatible = form.provider === "openai_compatible";

  return (
    <>
      <Card pad>
        <div className="fx-set-group-head" style={{ display: "flex", alignItems: "flex-start", gap: 12 }}>
          <div style={{ flex: 1 }}>
            <SectionTitle>密钥库</SectionTitle>
            <div className="fx-section-sub">
              密钥加密保存，接口只回末 4 位掩码；Agent 的模型通道可以直接选用这里的模型。
              主密钥在服务器 ~/.flux/key-vault.key，请一并备份。
            </div>
          </div>
          <Button variant="primary" icon="plus" onClick={openCreate}>添加密钥</Button>
        </div>

        {error ? <div className="ag-error" style={{ marginTop: 12 }}>{error}</div> : null}

        {loading ? (
          <div className="fx-note" style={{ marginTop: 12 }}>正在读取密钥库…</div>
        ) : keys.length === 0 ? (
          <div className="fx-note" style={{ marginTop: 12 }}>
            还没有密钥。添加一条 DeepSeek / OpenAI / OpenAI 兼容 / Anthropic 的 key 后，
            它登记的模型会出现在对应 Agent 的模型下拉里。
          </div>
        ) : (
          <div className="fx-key-list" style={{ marginTop: 12 }}>
            {keys.map((entry) => (
              <div key={entry.id} className="fx-key-row">
                <div className="fx-key-main">
                  <div className="fx-key-name">
                    {entry.name}
                    <span style={{ marginLeft: 8 }}>
                      <Badge tone="blue">{providerLabel(entry.provider)}</Badge>
                      <span style={{ marginLeft: 6 }}>
                        <Badge tone={entry.enabled ? "green" : "default"}>
                          {entry.enabled ? "启用" : "停用"}
                        </Badge>
                      </span>
                      {entry.models.some((model) =>
                        isUnreachable(entry.model_connectivity?.[model]),
                      ) ? (
                        <span style={{ marginLeft: 6 }}>
                          <Badge tone="red">有模型不通</Badge>
                        </span>
                      ) : null}
                    </span>
                  </div>
                  <div className="fx-key-meta">
                    {entry.base_url || "默认地址"} · {entry.key_hint}
                  </div>
                  <div className="fx-key-models">
                    {entry.models.map((model) => {
                      const record = entry.model_connectivity?.[model];
                      const bad = isUnreachable(record);
                      const reason = modelStatusReason(record);
                      return (
                        <span
                          key={model}
                          className={`fx-key-model${bad ? " is-bad" : ""}`}
                          title={reason || undefined}
                        >
                          {model}
                          {bad ? " · 不通" : ""}
                        </span>
                      );
                    })}
                  </div>
                </div>
                <div className="fx-key-actions">
                  <Switch on={entry.enabled} onChange={(value) => void toggleEnabled(entry, value)} />
                  <Button size="sm" variant="ghost" icon="edit" onClick={() => openEdit(entry)}>
                    编辑
                  </Button>
                  <Button size="sm" variant="danger" icon="trash" onClick={() => setDeleteTarget(entry)}>
                    删除
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal
        open={formOpen}
        title={editing ? "编辑密钥" : "添加密钥"}
        onClose={() => setFormOpen(false)}
        large
        footer={
          <>
            <Button onClick={() => setFormOpen(false)}>{editing ? "关闭" : "取消"}</Button>
            {savedId ? (
              <Button
                variant="ghost"
                busy={probe.kind === "checking"}
                disabled={probe.kind === "checking"}
                onClick={() => void runProbe(savedId)}
              >
                重新检查
              </Button>
            ) : null}
            <Button
              variant="primary"
              busy={saving}
              disabled={saving || probe.kind === "checking"}
              onClick={() => void save()}
            >
              {editing ? "保存并检查" : "创建并检查"}
            </Button>
          </>
        }
      >
        <div style={{ display: "grid", gap: 12 }}>
          <Field label="名称" required hint="用于在模型下拉里区分 key，例如「DeepSeek 官方」">
            <Input
              id="key-name"
              value={form.name}
              maxLength={128}
              placeholder="DeepSeek 官方"
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </Field>

          <Field label="供应商" required>
            <select
              className="fx-input"
              value={form.provider}
              onChange={(event) => {
                const provider = event.target.value as Provider;
                const preset = PROVIDERS.find((item) => item.value === provider)?.defaultUrl ?? "";
                setForm({
                  ...form,
                  provider,
                  base_url: form.base_url.trim() ? form.base_url : preset,
                  wire_api: provider === "openai_compatible" ? form.wire_api : "",
                });
              }}
            >
              {PROVIDERS.map((item) => (
                <option key={item.value} value={item.value}>{item.label}</option>
              ))}
            </select>
          </Field>

          <Field
            label="Base URL"
            hint={
              isCompatible
                ? "兼容端点必须填写，例如 https://api.siliconflow.cn/v1"
                : "留空使用该供应商的默认地址"
            }
          >
            <Input
              id="key-base-url"
              value={form.base_url}
              placeholder={PROVIDERS.find((item) => item.value === form.provider)?.defaultUrl}
              onChange={(event) => setForm({ ...form, base_url: event.target.value })}
            />
          </Field>

          <Field
            label="API Key"
            required={!editing}
            hint={editing ? "留空表示不修改；填写则覆盖旧 key" : "只用于模型通道；落库即加密"}
          >
            <Input
              id="key-api-key"
              type="password"
              autoComplete="new-password"
              value={form.api_key}
              placeholder={editing ? "留空保持原 key" : "sk-..."}
              onChange={(event) => setForm({ ...form, api_key: event.target.value })}
            />
          </Field>

          <Field label="模型（每行一个）" required hint="模型 id 必须与供应商实际提供的一致">
            <Textarea
              id="key-models"
              rows={5}
              value={form.modelsText}
              placeholder={"deepseek-v4-pro\ndeepseek-flash"}
              onChange={(event) => setForm({ ...form, modelsText: event.target.value })}
            />
          </Field>

          {isCompatible ? (
            <Field label="wire_api" hint="codex 的请求协议；不确定时先用 chat">
              <select
                className="fx-input"
                value={form.wire_api}
                onChange={(event) => setForm({ ...form, wire_api: event.target.value as WireApi })}
              >
                <option value="">自动（chat）</option>
                <option value="chat">chat</option>
                <option value="responses">responses</option>
              </select>
            </Field>
          ) : null}

          <div className="fx-set-row" style={{ borderTop: 0, paddingTop: 0 }}>
            <div className="fx-info">
              <div className="fx-t">启用</div>
              <div className="fx-d">停用后不再出现在任何 Agent 的模型下拉里。</div>
            </div>
            <div className="fx-ctl">
              <Switch on={form.enabled} onChange={(value) => setForm({ ...form, enabled: value })} />
            </div>
          </div>

          {probe.kind !== "idle" ? (
            <div className="fx-probe">
              <div className="fx-probe-head">
                <strong>连通性检查</strong>
                {probe.kind === "done" ? (
                  <span className="fx-probe-summary">{probeSummaryText(probe.result)}</span>
                ) : probe.kind === "checking" ? (
                  <span className="fx-probe-summary">正在检查连通性…</span>
                ) : null}
              </div>

              {probe.kind === "checking" ? (
                <div className="fx-note">已保存，正在逐个模型校验端点、密钥与模型名…</div>
              ) : null}

              {probe.kind === "error" ? (
                <div className="ag-error">
                  已保存，但未能完成连通性检查：{probe.message}。配置已保留，可稍后点「重新检查」。
                </div>
              ) : null}

              {probe.kind === "done" ? (
                probe.result.models.length > 0 ? (
                  <div className="fx-probe-list">
                    {probe.result.models.map((item) => (
                      <div key={`${item.model}-${item.checked_at ?? ""}`} className="fx-probe-row">
                        <span className="fx-probe-model">{item.model || "（空模型名）"}</span>
                        <Badge tone={item.ok ? "green" : "red"}>{item.ok ? "可用" : "不通"}</Badge>
                        <span className="fx-probe-reason">
                          {item.ok
                            ? item.latency_ms != null
                              ? `${item.latency_ms} ms`
                              : ""
                            : item.reason || "其他失败"}
                        </span>
                        {!item.ok ? (
                          <Button
                            size="sm"
                            variant="ghost"
                            icon="edit"
                            onClick={() => focusProbeField(item.reason_code)}
                          >
                            去修改
                          </Button>
                        ) : null}
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="fx-note">{probeSummaryText(probe.result)}</div>
                )
              ) : null}
            </div>
          ) : null}
        </div>
      </Modal>

      <Modal
        open={deleteTarget !== null}
        title="删除密钥"
        onClose={() => setDeleteTarget(null)}
        footer={
          <>
            <Button onClick={() => setDeleteTarget(null)}>取消</Button>
            <Button variant="danger" onClick={() => void remove()}>确认删除</Button>
          </>
        }
      >
        <p style={{ margin: 0, color: "var(--fx-text-2)", fontSize: 13 }}>
          删除「{deleteTarget?.name}」后，引用它的任务会回到 Agent 默认模型；此操作不可撤销。
        </p>
      </Modal>
    </>
  );
}
