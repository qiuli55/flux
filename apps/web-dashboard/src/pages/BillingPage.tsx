/**
 * 成本中心：把本地账本（每次模型调用的 token 用量）与官方额度快照并排展示。
 *
 * 三个口径：
 * - 本地账本：usage_records 逐次调用累加，能做任务级归因（唯一来源：Flux 自己记的账）；
 * - 官方快照：OpenCode Go 的 /zen/go/v1/usage 与 arkcli 对账回来的百分比，只表示套餐总消耗；
 * - 闸门：默认仅告警（FLUX_BILLING_ENFORCE=false 时不拦截任务）。
 *
 * 数据来自 /billing/plans（refresh=true 顺带抓官方快照）、/billing/usage、/billing/runs/{id}。
 */
import { useCallback, useEffect, useState } from "react";

import { api, ApiError } from "../api/client";
import type { BillingGate, BillingLevel, BillingOfficialWindow, BillingOverview, BillingPlan, BillingPrice, BillingPrices, BillingRunUsage, BillingWindow } from "../api/types";
import { Icon } from "../shell/Icon";
import { Badge, Button, Card, Empty, Input, PageHead, Progress, SectionTitle, Stat } from "../shell/ui";

type Tone = "brand" | "green" | "orange" | "red" | "blue" | "default";

const LEVEL_TONE: Record<BillingLevel, Tone> = {
  ok: "green", warning: "orange", exceeded: "red", unknown: "default", disabled: "default",
};

const LEVEL_LABEL: Record<BillingLevel, string> = {
  ok: "额度正常", warning: "接近上限", exceeded: "已超额", unknown: "未对账", disabled: "未启用",
};

const SOURCE_LABEL: Record<string, string> = {
  opencode_go: "OpenCode Go 官方接口",
  arkcli: "arkcli 对账",
  cli: "CLI 对账",
  http: "HTTP 额度接口",
};

/** token 数按千分位展示，避免长数字难读。 */
function fmtNum(value: number | null | undefined): string {
  if (value === null || value === undefined) return "-";
  return value.toLocaleString("zh-CN");
}

function fmtPct(value: number | null | undefined): string {
  return value === null || value === undefined ? "-" : `${value.toFixed(1)}%`;
}

/** 单价最多留 4 位小数，去掉尾随 0（0.0036 / 2 / 0.5）。 */
function fmtPrice(value: number): string {
  return Number(value.toFixed(4)).toString();
}

function fmtTime(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function fmtSince(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
}

/** 官方窗口：有快照就显示百分比与重置时间，没有就明确写"无官方快照"，不要让空格子看起来像 0%。 */
function OfficialLine({ official }: { official: BillingOfficialWindow | null }) {
  if (!official) return <span className="fx-dim">暂无官方快照</span>;
  if (official.error) return (
    <span className="fx-row fx-gap-2 fx-wrap" style={{ maxWidth: "60%" }}>
      <Badge tone="orange" dot>对账失败</Badge>
      <span className="fx-dim fx-ellipsis" style={{ fontSize: 11 }} title={official.error}>{official.error}</span>
    </span>
  );
  return (
    <span className="fx-row fx-gap-2">
      <span style={{ fontVariantNumeric: "tabular-nums" }}>{fmtPct(official.percent)}</span>
      {official.quota !== null ? <span className="fx-dim">配额 {fmtNum(official.quota)} {official.unit === "percent" ? "" : official.unit}</span> : null}
      {official.resets_at ? <span className="fx-dim">重置 {fmtTime(official.resets_at)}</span> : null}
    </span>
  );
}

function WindowRow({ window }: { window: BillingWindow }) {
  const percent = window.official?.percent ?? 0;
  return (
    <div style={{ padding: "10px 0", borderTop: "1px solid var(--fx-line-soft)" }}>
      <div className="fx-between">
        <span style={{ fontSize: 13 }}>
          {window.label}
          <span className="fx-dim" style={{ marginLeft: 8, fontSize: 11 }}>自 {fmtSince(window.since)}</span>
        </span>
        <OfficialLine official={window.official} />
      </div>
      {window.official ? <div style={{ marginTop: 8 }}><Progress value={percent} thin /></div> : null}
      <div className="fx-row fx-wrap fx-gap-3" style={{ marginTop: 6, fontSize: 11 }}>
        <span className="fx-dim">本地账本</span>
        <span>{fmtNum(window.local.total_tokens)} tok</span>
        <span className="fx-dim">{window.local.records} 次调用</span>
        <span className="fx-dim">入 {fmtNum(window.local.input_tokens)} · 出 {fmtNum(window.local.output_tokens)} · 缓存 {fmtNum(window.local.cache_read_tokens)}</span>
        {window.local.cost > 0 ? <span>≈ ${window.local.cost.toFixed(2)}</span> : null}
      </div>
    </div>
  );
}

function GateLine({ gate }: { gate: BillingGate }) {
  return (
    <div className="fx-row fx-wrap fx-gap-2" style={{ marginTop: 4 }}>
      <Badge tone={LEVEL_TONE[gate.level]} dot>{LEVEL_LABEL[gate.level]}</Badge>
      <Badge tone={gate.enforce ? "orange" : "default"}>{gate.enforce ? "闸门开启" : "仅告警"}</Badge>
      {gate.percent !== null ? <span className="fx-dim" style={{ fontSize: 11 }}>关键窗口 {fmtPct(gate.percent)}</span> : null}
      <span className="fx-dim fx-grow fx-ellipsis" style={{ fontSize: 11 }}>{gate.message}</span>
    </div>
  );
}

function PlanCard({ plan }: { plan: BillingPlan }) {
  return (
    <Card pad>
      <div className="fx-between">
        <div>
          <div className="fx-row fx-gap-2">
            <Icon name="credit" className="fx-icon-sm" />
            <span style={{ fontWeight: 600 }}>{plan.label}</span>
            <Badge tone="blue">{plan.vendor}</Badge>
          </div>
          <div className="fx-dim" style={{ fontSize: 11, marginTop: 4 }}>
            模型前缀 <code>{plan.model_prefix || "—"}</code>
            {plan.runtime_ids?.length ? ` · 运行时 ${plan.runtime_ids.join(" / ")}` : ""}
            {plan.official_source ? ` · 对账来源 ${SOURCE_LABEL[plan.official_source] ?? plan.official_source}` : " · 仅供记账，无限额"}
          </div>
        </div>
        <div className="fx-ctl fx-row fx-gap-2">
          <Badge tone={LEVEL_TONE[plan.gate.level]} dot>{plan.gate.level}</Badge>
          {plan.gate.blocked ? <Badge tone="red">已拦截</Badge> : null}
        </div>
      </div>
      <GateLine gate={plan.gate} />
      {plan.gate.percent !== null && plan.gate.percent >= 0 ? (
        <div style={{ marginTop: 8 }}><Progress value={plan.gate.percent} /></div>
      ) : null}
      <div style={{ marginTop: 6 }}>
        {plan.windows.map((window) => <WindowRow key={window.key} window={window} />)}
      </div>
      <div className="fx-dim" style={{ fontSize: 11, marginTop: 6 }}>超额回退模型 {plan.fallback_model || "未配置"}</div>
    </Card>
  );
}

/** 汇总所有套餐在"月"窗口的本地账本，给出一个总览数字（各套餐窗口不重叠，可安全相加）。 */
function totalsOf(overview: BillingOverview | null) {
  let tokens = 0;
  let records = 0;
  let cost = 0;
  for (const plan of overview?.plans ?? []) {
    const window = plan.windows.find((item) => item.key === "monthly") ?? plan.windows[0];
    if (!window) continue;
    tokens += window.local.total_tokens;
    records += window.local.records;
    cost += window.local.cost;
  }
  return { tokens, records, cost };
}

function RunUsage({ run }: { run: BillingRunUsage }) {
  return (
    <div style={{ marginTop: 12 }}>
      <div className="fx-row fx-wrap fx-gap-3" style={{ fontSize: 12 }}>
        <span>合计 <b>{fmtNum(run.totals.total_tokens)}</b> tok</span>
        <span className="fx-dim">入 {fmtNum(run.totals.input_tokens)} · 出 {fmtNum(run.totals.output_tokens)} · 缓存读 {fmtNum(run.totals.cache_read_tokens)}</span>
        <span className="fx-dim">{run.totals.records} 次调用</span>
        {run.totals.cost > 0 ? <span className="fx-dim">成本 {run.totals.cost.toFixed(4)}</span> : null}
      </div>
      {run.records.length === 0 ? <Empty icon="database" title="这个 Run 还没有用量记录" /> : run.records.map((record) => (
        <div className="fx-set-row" key={record.id}>
          <div className="fx-info">
            <div className="fx-t">{record.model} <span className="fx-dim">· {record.provider}</span></div>
            <div className="fx-d">{fmtTime(record.occurred_at)}{record.plan_key ? ` · ${record.plan_key}` : ""}</div>
          </div>
          <div className="fx-ctl fx-dim" style={{ fontSize: 11 }}>
            入 {fmtNum(record.input_tokens)} · 出 {fmtNum(record.output_tokens)} · 缓存 {fmtNum(record.cache_read_tokens)}
          </div>
        </div>
      ))}
    </div>
  );
}

/** 官方价目表：按厂商分组，单位每 100 万 token；CNY 条目显示原币种，落账时按汇率折算。 */
function PriceBookCard({ data }: { data: BillingPrices }) {
  const groups = new Map<string, BillingPrice[]>();
  for (const price of data.prices) {
    const vendor = price.vendor || "其他";
    const bucket = groups.get(vendor) ?? [];
    bucket.push(price);
    groups.set(vendor, bucket);
  }
  return (
    <Card pad style={{ marginTop: 16 }}>
      <div className="fx-between">
        <SectionTitle>官方 API 价目</SectionTitle>
        <span className="fx-dim" style={{ fontSize: 11 }}>基准 {data.as_of} · 每 1M tokens</span>
      </div>
      <div className="fx-dim" style={{ fontSize: 11, marginTop: 4 }}>
        runtime 未上报 cost 的调用按此表估算；人民币按 1 USD ≈ {data.cny_per_usd} CNY 折算。
      </div>
      {[...groups.entries()].map(([vendor, items]) => (
        <div key={vendor} style={{ marginTop: 12 }}>
          <div className="fx-dim" style={{ fontSize: 11, letterSpacing: "0.04em" }}>{vendor}</div>
          {items.map((price) => (
            <div className="fx-set-row" key={`${vendor}-${price.model}`}>
              <div className="fx-info">
                <div className="fx-t">{price.model}</div>
                <div className="fx-d">
                  入 {fmtPrice(price.input)} · 缓存读 {fmtPrice(price.cache_read)} · 缓存写 {fmtPrice(price.cache_write)} · 出 {fmtPrice(price.output)} {price.currency}/1M
                  {price.off_peak_multiplier !== null ? ` · 错峰 ×${price.off_peak_multiplier}` : ""}
                  {price.note ? ` · ${price.note}` : ""}
                </div>
              </div>
              <div className="fx-ctl">
                {price.source ? (
                  <a href={price.source} target="_blank" rel="noreferrer" style={{ color: "var(--tn-accent)", fontSize: 11 }}>
                    官方定价
                  </a>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      ))}
    </Card>
  );
}

export function BillingPage() {
  const [overview, setOverview] = useState<BillingOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [runId, setRunId] = useState("");
  const [run, setRun] = useState<BillingRunUsage | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [prices, setPrices] = useState<BillingPrices | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    const settled = await Promise.allSettled([api.billingPlans(), api.billingPrices()]);
    if (settled[0].status === "fulfilled") {
      setOverview(settled[0].value);
      setError(null);
    } else {
      setError(settled[0].reason instanceof ApiError ? settled[0].reason.message : String(settled[0].reason));
    }
    // 价目表失败不影响额度视图：没拿到就整块不渲染
    if (settled[1].status === "fulfilled") setPrices(settled[1].value);
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  /** 主动对账：后端抓 Go 官方额度 / 跑 arkcli，返回每个套餐的结果。 */
  const sync = async () => {
    setSyncing(true);
    setNotice(null);
    try {
      const result = await api.billingRefresh();
      const lines = result.results.map((item) => {
        const source = SOURCE_LABEL[item.source] ?? item.source;
        if (item.error) return `${item.plan_key}（${source}）失败：${item.error}`;
        return item.refreshed ? `${item.plan_key}（${source}）已更新 ${item.rows} 个窗口` : `${item.plan_key}（${source}）无新数据`;
      });
      setNotice(lines.join("；") || "没有可对账的套餐");
      setOverview(await api.billingPlans());
      setError(null);
    } catch (err) {
      setNotice(err instanceof ApiError ? err.message : String(err));
    } finally {
      setSyncing(false);
    }
  };

  const searchRun = async () => {
    const id = runId.trim();
    if (!id) return;
    setRunBusy(true);
    try {
      setRun(await api.billingRunUsage(id));
      setError(null);
    } catch (err) {
      setRun(null);
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setRunBusy(false);
    }
  };

  const totals = totalsOf(overview);
  const enforce = overview?.enforce ?? false;

  return (
    <>
      <PageHead title="成本中心" desc="本地账本逐次记账，官方接口按套餐对账；默认仅告警，不拦截任务。">
        <Button variant="subtle" icon="refresh" onClick={load} busy={loading}>刷新</Button>
        <Button icon="globe" onClick={sync} busy={syncing}>立即对账</Button>
      </PageHead>

      {error ? <div style={{ color: "var(--tn-danger)", fontSize: 12, marginBottom: 12 }}>{error}</div> : null}
      {notice ? <div style={{ color: "var(--tn-accent)", fontSize: 12, marginBottom: 12 }}>{notice}</div> : null}

      <div className="fx-stats" style={{ marginBottom: 16 }}>
        <Stat label="本月调用" value={fmtNum(totals.records)} unit="次" icon="activity" />
        <Stat label="本月 Tokens" value={fmtNum(totals.tokens)} icon="chart" />
        <Stat label="本月估算成本" value={`$${totals.cost.toFixed(2)}`} icon="credit" />
        <Stat label="闸门" value={enforce ? "开启" : "仅告警"} icon="shield" />
      </div>

      {loading && overview === null ? <Empty icon="clock" title="正在读取计费数据" /> : null}
      {overview !== null && overview.plans.length === 0 ? <Empty icon="credit" title="还没有配置任何套餐" /> : null}

      <div className="fx-grid fx-auto">
        {(overview?.plans ?? []).map((plan) => <PlanCard key={plan.key} plan={plan} />)}
      </div>

      {prices !== null ? <PriceBookCard data={prices} /> : null}

      <Card pad style={{ marginTop: 16 }}>
        <SectionTitle>Run 用量明细</SectionTitle>
        <div className="fx-dim" style={{ fontSize: 11, marginTop: 4 }}>按 run_id 查这次执行每一步的 token 账，用于核对任务级归因。</div>
        <div className="fx-row fx-gap-2" style={{ marginTop: 12 }}>
          <Input style={{ flex: 1 }} value={runId} placeholder="输入 run_id" onChange={(event) => setRunId(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void searchRun(); }} />
          <Button icon="search" onClick={searchRun} busy={runBusy}>查询</Button>
        </div>
        {run ? <RunUsage run={run} /> : null}
      </Card>

      {overview !== null ? (
        <div className="fx-dim" style={{ fontSize: 11, marginTop: 12 }}>
          告警阈值 {overview.alert_percent}% · 拦截阈值 {overview.block_percent}% · 计费总开关 {overview.enabled ? "已启用" : "已关闭"}
          <br />
          新增套餐（Codex / Claude / 任意订阅）写进 {overview.plans_file || "~/.flux/billing-plans.json"} 即可生效；未登记前缀的模型只记账、不限额。
        </div>
      ) : null}
    </>
  );
}
