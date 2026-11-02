namespace Flux.Desktop.Models;

/// <summary>本地账本合计（/api/v1/billing/plans 的 windows[].local）。</summary>
public sealed record BillingTotals(
    int Records,
    long InputTokens,
    long OutputTokens,
    long CacheReadTokens,
    long CacheWriteTokens,
    long TotalTokens,
    double Cost);

/// <summary>官方额度快照（同一窗口最近一次采集结果）。</summary>
public sealed record BillingOfficial(
    double? Used,
    double? Quota,
    double? Percent,
    string? Unit,
    string? Status,
    string? ResetsAt,
    string? FetchedAt,
    string? Source,
    string? Error,
    // 快照归属账号指纹（空 = 本机未识别账号）。
    string? AccountKey = null);

/// <summary>一个额度窗口：本地账本 + 官方快照。</summary>
public sealed record BillingWindow(
    string Key,
    string Label,
    string Since,
    BillingTotals Local,
    BillingOfficial? Official);

/// <summary>闸门判定（默认只告警；enforce=true 且超额才拦截）。</summary>
public sealed record BillingGate(
    string? PlanKey,
    string Level,
    double? Percent,
    string? WindowKey,
    string? Message,
    bool Enforce,
    string? FallbackModel,
    bool Blocked,
    // 判定所依据的账号指纹（空 = 本机未识别账号）。
    string? AccountKey = null);

/// <summary>套餐下的单个账号视图（同一套餐换过 key 时会有多份）。</summary>
public sealed record BillingAccount(
    string Key,
    string Label,
    // 是否本机当前凭据对应的账号。
    bool IsCurrent,
    BillingGate Gate,
    BillingWindow[] Windows);

/// <summary>套餐视图（模型前缀 / runtime 归因 + 各窗口）。</summary>
public sealed record BillingPlan(
    string Key,
    string Label,
    string Vendor,
    string? ModelPrefix,
    string[]? ModelPrefixes,
    string[]? RuntimeIds,
    string? OfficialSource,
    string? FallbackModel,
    BillingGate Gate,
    BillingWindow[] Windows,
    // 本机当前账号指纹（Gate / Windows 都是它的口径）。
    string? AccountKey = null,
    // 该套餐出现过的所有账号（当前账号排第一）。
    BillingAccount[]? Accounts = null);

/// <summary>GET /api/v1/billing/plans 的 data。</summary>
public sealed record BillingOverview(
    bool Enabled,
    bool Enforce,
    double AlertPercent,
    double BlockPercent,
    string? PlansFile,
    BillingPlan[] Plans,
    // 官方快照刷新周期（秒）；超过两个周期前端可标记「可能过期」。
    int? RefreshSeconds = null);

/// <summary>一条官方价目（每 100 万 token；Currency 可能是 CNY）。</summary>
public sealed record BillingPrice(
    string Model,
    string Vendor,
    double Input,
    double Output,
    double CacheRead,
    double CacheWrite,
    string Currency,
    double? OffPeakMultiplier,
    string? Source,
    string? Note);

/// <summary>GET /api/v1/billing/prices 的 data。</summary>
public sealed record BillingPrices(string AsOf, double CnyPerUsd, BillingPrice[] Prices);

/// <summary>一条用量明细（usage_records）。</summary>
public sealed record BillingRunRecord(
    string Id,
    string Provider,
    string Model,
    string? PlanKey,
    long InputTokens,
    long OutputTokens,
    long CacheReadTokens,
    long CacheWriteTokens,
    long TotalTokens,
    double? Cost,
    string? OccurredAt);

/// <summary>GET /api/v1/billing/runs/{id} 的 data。</summary>
public sealed record BillingRunUsage(
    string RunId,
    BillingTotals Totals,
    BillingRunRecord[] Records);

/// <summary>一次官方对账的结果项。</summary>
public sealed record BillingRefreshItem(
    string PlanKey,
    string? Source,
    bool Refreshed,
    int Rows,
    string? Error,
    // 本次采集归属的本机账号指纹。
    string? AccountKey = null);

/// <summary>POST /api/v1/billing/refresh 的 data。</summary>
public sealed record BillingRefreshResult(BillingRefreshItem[] Results);
