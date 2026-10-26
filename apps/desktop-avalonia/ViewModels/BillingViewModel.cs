using System;
using System.Collections.ObjectModel;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>额度窗口行（web 端 WindowRow）：官方百分比 + 本地账本。</summary>
public sealed record BillingWindowView(
    string Label,
    string SinceText,
    string LocalText,
    string OfficialText,
    double Percent,
    bool HasPercent,
    IBrush Tone);

/// <summary>套餐卡（web 端 PlanCard）。</summary>
public sealed record BillingPlanView(
    string Label,
    string Vendor,
    string LevelText,
    IBrush LevelTone,
    string MetaText,
    string GateMessage,
    bool HasGateMessage,
    bool Enforce,
    bool Blocked,
    double GatePercent,
    bool HasGatePercent,
    string FallbackText,
    ObservableCollection<BillingWindowView> Windows);

/// <summary>官方价目行。</summary>
public sealed record BillingPriceRow(
    string Model,
    string Vendor,
    string PriceText,
    Uri? SourceUri,
    bool HasSource);

/// <summary>Run 用量明细行。</summary>
public sealed record BillingRunRow(string Model, string MetaText, string UsageText);

/// <summary>
/// 成本中心（web 端 BillingPage）：本地账本 + 官方额度快照 + 官方 API 价目 + Run 明细。
/// 默认只告警不拦截；「立即对账」才会真正请求外部额度接口 / 跑 arkcli。
/// </summary>
public sealed partial class BillingViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<FluxApiClient?> _api;

    public BillingViewModel(Func<FluxApiClient?> api) => _api = api;

    public ObservableCollection<BillingPlanView> Plans { get; } = new();

    public ObservableCollection<BillingPriceRow> Prices { get; } = new();

    public ObservableCollection<BillingRunRow> RunRows { get; } = new();

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _isSyncing;

    [ObservableProperty]
    private bool _isRunBusy;

    [ObservableProperty]
    private string _callCountText = "0";

    [ObservableProperty]
    private string _tokensText = "0";

    [ObservableProperty]
    private string _costText = "$0.00";

    [ObservableProperty]
    private string _gateText = "—";

    [ObservableProperty]
    private IBrush _gateTone = Muted;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoPlans))]
    private bool _hasPlans;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoPrices))]
    private bool _hasPrices;

    [ObservableProperty]
    private string _plansFileText = "";

    [ObservableProperty]
    private string _pricesMetaText = "";

    [ObservableProperty]
    private string _noticeText = "";

    [ObservableProperty]
    private string _runId = "";

    [ObservableProperty]
    private string _runSummaryText = "";

    [ObservableProperty]
    private string _runErrorText = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoRun))]
    [NotifyPropertyChangedFor(nameof(ShowRunEmpty))]
    private bool _hasRun;

    public bool HasNoPlans => !HasPlans;

    public bool HasNoPrices => !HasPrices;

    public bool HasNoRun => !HasRun;

    /// <summary>查到了 Run 但没有任何用量记录（与"还没查询"区分开）。</summary>
    public bool ShowRunEmpty => HasRun && RunRows.Count == 0;

    public bool HasRunError => !string.IsNullOrWhiteSpace(RunErrorText);

    public bool HasNotice => !string.IsNullOrWhiteSpace(NoticeText);

    /// <summary>拉额度视图与价目表；两个接口互不阻塞（价目失败只是不显示价目卡）。</summary>
    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var overview = await api.BillingPlansAsync().ConfigureAwait(true);
            var prices = await api.BillingPricesAsync().ConfigureAwait(true);
            ApplyOverview(overview?.Data, overview?.Message);
            ApplyPrices(prices?.Data);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private void ApplyOverview(BillingOverview? overview, string? message)
    {
        Plans.Clear();
        if (overview is null)
        {
            HasPlans = false;
            CallCountText = "0";
            TokensText = "0";
            CostText = "$0.00";
            GateText = "—";
            GateTone = Muted;
            NoticeText = message ?? "无法读取计费数据";
            return;
        }

        // 总览只统计「自然月」窗口（key=monthly），避免各窗口重复累加
        long tokens = 0;
        var records = 0;
        double cost = 0;
        foreach (var plan in overview.Plans)
        {
            var window = plan.Windows.FirstOrDefault(w => w.Key == "monthly")
                ?? plan.Windows.FirstOrDefault();
            if (window is null)
            {
                continue;
            }

            tokens += window.Local.TotalTokens;
            records += window.Local.Records;
            cost += window.Local.Cost;
        }

        CallCountText = records.ToString("N0", CultureInfo.InvariantCulture);
        TokensText = tokens.ToString("N0", CultureInfo.InvariantCulture);
        CostText = $"${cost:0.00}";
        GateText = overview.Enforce ? "开启" : "仅告警";
        GateTone = overview.Enforce ? Orange : Brand;
        PlansFileText = $"新增套餐（Codex / Claude / 任意订阅）写进 {overview.PlansFile ?? "~/.flux/billing-plans.json"} 即可生效；未登记前缀的模型只记账、不限额。";

        foreach (var plan in overview.Plans)
        {
            var (levelText, levelTone) = LevelOf(plan.Gate.Level);
            var windows = new ObservableCollection<BillingWindowView>(
                plan.Windows.Select(ToWindowView));
            Plans.Add(new BillingPlanView(
                plan.Label,
                plan.Vendor,
                levelText,
                levelTone,
                MetaOf(plan),
                plan.Gate.Message ?? "",
                !string.IsNullOrWhiteSpace(plan.Gate.Message),
                plan.Gate.Enforce,
                plan.Gate.Blocked,
                plan.Gate.Percent ?? 0,
                plan.Gate.Percent is not null,
                $"超额回退模型 {plan.FallbackModel ?? "未配置"}",
                windows));
        }

        HasPlans = Plans.Count > 0;
    }

    private void ApplyPrices(BillingPrices? prices)
    {
        Prices.Clear();
        if (prices is null)
        {
            HasPrices = false;
            return;
        }

        PricesMetaText = $"基准 {prices.AsOf} · 每 1M tokens · 人民币按 1 USD≈{prices.CnyPerUsd} 折算";
        foreach (var price in prices.Prices)
        {
            var text = $"入 {Price(price.Input)} · 缓存读 {Price(price.CacheRead)} · 缓存写 {Price(price.CacheWrite)} · 出 {Price(price.Output)} {price.Currency}/1M";
            if (price.OffPeakMultiplier is not null)
            {
                text += $" · 错峰 ×{price.OffPeakMultiplier}";
            }

            if (!string.IsNullOrWhiteSpace(price.Note))
            {
                text += $" · {price.Note}";
            }

            Prices.Add(new BillingPriceRow(
                price.Model,
                price.Vendor,
                text,
                Uri.TryCreate(price.Source, UriKind.Absolute, out var uri) ? uri : null,
                !string.IsNullOrWhiteSpace(price.Source)));
        }

        HasPrices = Prices.Count > 0;
    }

    private static BillingWindowView ToWindowView(BillingWindow window)
    {
        var official = window.Official;
        var officialText = "暂无官方快照";
        var hasPercent = false;
        double percent = 0;
        var tone = Muted;

        if (official is not null)
        {
            if (!string.IsNullOrEmpty(official.Error))
            {
                officialText = "对账失败";
            }
            else if (official.Percent is null)
            {
                officialText = "无百分比";
            }
            else
            {
                percent = official.Percent.Value;
                hasPercent = true;
                tone = percent >= 100 ? Danger : percent >= 80 ? Orange : Brand;
                officialText = string.IsNullOrWhiteSpace(official.ResetsAt)
                    ? $"{percent:0.0}%"
                    : $"{percent:0.0}% · 重置 {ShortTime(official.ResetsAt)}";
            }
        }

        var localText = $"本地 {window.Local.TotalTokens.ToString("N0", CultureInfo.InvariantCulture)} tok · {window.Local.Records} 次调用"
            + $" · 入 {window.Local.InputTokens.ToString("N0", CultureInfo.InvariantCulture)}"
            + $" · 出 {window.Local.OutputTokens.ToString("N0", CultureInfo.InvariantCulture)}"
            + $" · 缓存 {window.Local.CacheReadTokens.ToString("N0", CultureInfo.InvariantCulture)}";
        if (window.Local.Cost > 0)
        {
            localText += $" · ≈${window.Local.Cost:0.00}";
        }

        return new BillingWindowView(
            window.Label,
            $"自 {ShortTime(window.Since)}",
            localText,
            officialText,
            percent,
            hasPercent,
            tone);
    }

    private static string MetaOf(BillingPlan plan)
    {
        var prefixes = plan.ModelPrefixes is { Length: > 0 }
            ? string.Join(" / ", plan.ModelPrefixes)
            : plan.ModelPrefix ?? "—";
        var runtime = plan.RuntimeIds is { Length: > 0 }
            ? $" · 运行时 {string.Join(" / ", plan.RuntimeIds)}"
            : "";
        var source = string.IsNullOrWhiteSpace(plan.OfficialSource)
            ? " · 仅供记账，无限额"
            : $" · 对账来源 {SourceLabel(plan.OfficialSource)}";
        return $"模型前缀 {prefixes}{runtime}{source}";
    }

    private static (string Text, IBrush Tone) LevelOf(string level) => level switch
    {
        "ok" => ("额度正常", Brand),
        "warning" => ("接近上限", Orange),
        "exceeded" => ("已超额", Danger),
        "disabled" => ("未启用", Muted),
        _ => ("未对账", Muted),
    };

    private static string SourceLabel(string? source) => source switch
    {
        "opencode_go" => "OpenCode Go 官方接口",
        "arkcli" => "arkcli 对账",
        "cli" => "CLI 对账",
        "http" => "HTTP 额度接口",
        _ => "仅供记账",
    };

    private static string Price(double value)
        => Math.Round(value, 4).ToString("0.####", CultureInfo.InvariantCulture);

    partial void OnNoticeTextChanged(string value) => OnPropertyChanged(nameof(HasNotice));

    partial void OnRunErrorTextChanged(string value) => OnPropertyChanged(nameof(HasRunError));

    private static string ShortTime(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return "—";
        }

        return DateTimeOffset.TryParse(
            raw,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal,
            out var moment)
            ? moment.ToLocalTime().ToString("MM-dd HH:mm")
            : raw;
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    /// <summary>立即对账：让后端抓 Go 官方额度 / 跑 arkcli，再把最新快照读回来。</summary>
    [RelayCommand]
    private async Task Sync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsSyncing = true;
        try
        {
            var result = await api.BillingRefreshAsync().ConfigureAwait(true);
            if (result?.Data is null)
            {
                NoticeText = result?.Message ?? "对账失败";
                return;
            }

            NoticeText = string.Join("；", result.Data.Results.Select(item =>
                item.Error is { Length: > 0 }
                    ? $"{item.PlanKey}（{SourceLabel(item.Source)}）失败：{item.Error}"
                    : item.Refreshed
                        ? $"{item.PlanKey}（{SourceLabel(item.Source)}）已更新 {item.Rows} 个窗口"
                        : $"{item.PlanKey}（{SourceLabel(item.Source)}）无新数据"));
            await RefreshAsync().ConfigureAwait(true);
        }
        finally
        {
            IsSyncing = false;
        }
    }

    /// <summary>按 run_id 查这次执行每一步的 token 账（任务级归因核对）。</summary>
    [RelayCommand]
    private async Task LookupRun()
    {
        var api = _api();
        var runId = RunId.Trim();
        if (api is null || runId.Length == 0)
        {
            return;
        }

        IsRunBusy = true;
        RunErrorText = "";
        try
        {
            var result = await api.BillingRunAsync(runId).ConfigureAwait(true);
            var run = result?.Data;
            RunRows.Clear();
            if (run is null)
            {
                HasRun = false;
                RunSummaryText = "";
                RunErrorText = result?.Message ?? "查询失败";
                return;
            }

            var summary = $"合计 {run.Totals.TotalTokens.ToString("N0", CultureInfo.InvariantCulture)} tok · {run.Totals.Records} 次调用"
                + $" · 入 {run.Totals.InputTokens.ToString("N0", CultureInfo.InvariantCulture)}"
                + $" · 出 {run.Totals.OutputTokens.ToString("N0", CultureInfo.InvariantCulture)}";
            if (run.Totals.Cost > 0)
            {
                summary += $" · ≈${run.Totals.Cost:0.00}";
            }

            RunSummaryText = summary;
            foreach (var record in run.Records)
            {
                var usage = $"入 {record.InputTokens.ToString("N0", CultureInfo.InvariantCulture)} · 出 {record.OutputTokens.ToString("N0", CultureInfo.InvariantCulture)}"
                    + $" · 缓存 {record.CacheReadTokens.ToString("N0", CultureInfo.InvariantCulture)}";
                if (record.Cost is > 0)
                {
                    usage += $" · ${record.Cost:0.0000}";
                }

                var meta = ShortTime(record.OccurredAt) + (string.IsNullOrWhiteSpace(record.PlanKey) ? "" : $" · {record.PlanKey}");
                RunRows.Add(new BillingRunRow($"{record.Model} · {record.Provider}", meta, usage));
            }

            HasRun = true;
            OnPropertyChanged(nameof(ShowRunEmpty));
        }
        finally
        {
            IsRunBusy = false;
        }
    }
}
