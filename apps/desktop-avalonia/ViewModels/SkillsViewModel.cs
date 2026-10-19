using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>已导入能力卡片（web 端 CapCard）。</summary>
public sealed record SkillCardView(
    string Name,
    string KindLabel,
    string VersionText,
    string Source,
    string Kind,
    IBrush Tone,
    Geometry Icon,
    string UpdatedText);

/// <summary>扫描来源行（web 端扫描结果里的 sources）。</summary>
public sealed record ScanSourceView(string Status, string Path, string CountText, IBrush Tone);

/// <summary>扫描候选行（web 端 CandidateRow）。</summary>
public sealed record ScanCandidateView(
    string Name,
    string KindLabel,
    string Source,
    bool Blocked,
    string BlockedReason,
    bool CanImport,
    ICommand Import);

/// <summary>
/// Skills 页（web 端 SkillsPage）：能力（Agent 档案 / Skill 能力包 / Connector）的查看与导入。
/// 被安全拦截的候选展示拦截原因且不可导入。
/// </summary>
public sealed partial class SkillsViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<FluxApiClient?> _api;

    private List<ImportedCapability> _capabilities = [];

    public SkillsViewModel(Func<FluxApiClient?> api) => _api = api;

    public ObservableCollection<SkillCardView> Cards { get; } = new();

    public ObservableCollection<ScanSourceView> ScanSources { get; } = new();

    public ObservableCollection<ScanCandidateView> ScanCandidates { get; } = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAll))]
    [NotifyPropertyChangedFor(nameof(IsAgent))]
    [NotifyPropertyChangedFor(nameof(IsSkill))]
    [NotifyPropertyChangedFor(nameof(IsConnector))]
    private string _filter = "all";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _isScanning;

    [ObservableProperty]
    private bool _isReportOpen;

    [ObservableProperty]
    private string _countText = "共 0 项";

    [ObservableProperty]
    private string _statusText = "";

    [ObservableProperty]
    private string _totalBadge = "候选 0";

    [ObservableProperty]
    private string _okBadge = "可导入 0";

    [ObservableProperty]
    private string _blockedBadge = "已拦截 0";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoCards))]
    private bool _hasCards;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoCandidates))]
    private bool _hasCandidates;

    [ObservableProperty]
    private bool _hasSources;

    public bool HasNoCards => !HasCards;

    public bool HasNoCandidates => !HasCandidates;

    public bool IsAll => Filter == "all";

    public bool IsAgent => Filter == "agent";

    public bool IsSkill => Filter == "skill";

    public bool IsConnector => Filter == "connector";

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
            var caps = await api.ListCapabilitiesAsync().ConfigureAwait(true);
            _capabilities = caps?.Data ?? [];
            RebuildCards();
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    [RelayCommand]
    private void SelectFilter(string? filter)
    {
        Filter = filter ?? "all";
        RebuildCards();
    }

    /// <summary>扫描本机来源目录，结果放进浮层（与 web 端 Modal 同构）。</summary>
    [RelayCommand]
    private async Task Scan()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsScanning = true;
        StatusText = "";
        try
        {
            var report = (await api.ScanCapabilitiesAsync().ConfigureAwait(true))?.Data;
            if (report is null)
            {
                StatusText = "扫描失败：后端未响应 /capabilities/scan";
                return;
            }

            TotalBadge = $"候选 {report.Summary.Total}";
            OkBadge = $"可导入 {report.Summary.Ok}";
            BlockedBadge = $"已拦截 {report.Summary.Blocked}";

            ScanSources.Clear();
            foreach (var source in report.Sources)
            {
                ScanSources.Add(new ScanSourceView(
                    source.Status,
                    source.Path,
                    $"{source.Candidates} 项",
                    source.Status == "scanned" ? Brand : source.Status == "missing" ? Muted : Orange));
            }

            HasSources = ScanSources.Count > 0;

            ScanCandidates.Clear();
            foreach (var candidate in report.Candidates)
            {
                var blocked = candidate.Status == "blocked";
                ScanCandidates.Add(new ScanCandidateView(
                    candidate.Name,
                    KindLabel(candidate.Kind),
                    candidate.Source ?? "—",
                    blocked,
                    blocked && candidate.Findings.Length > 0
                        ? "拦截：" + string.Join("；", candidate.Findings.Select(f => f.Reason))
                        : "",
                    !blocked,
                    new RelayCommand(() => _ = ImportAsync(candidate))));
            }

            HasCandidates = ScanCandidates.Count > 0;
            IsReportOpen = true;
        }
        finally
        {
            IsScanning = false;
        }
    }

    [RelayCommand]
    private void CloseReport() => IsReportOpen = false;

    private async Task ImportAsync(CapabilityCandidate candidate)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var result = await api.ImportCapabilityAsync(candidate.Kind, candidate.Name).ConfigureAwait(true);
        StatusText = result?.Data is { } imported
            ? $"已导入「{imported.Name}」（{imported.Outcome}）"
            : "导入失败：后端未响应 /capabilities/import";

        await RefreshAsync().ConfigureAwait(true);
    }

    private void RebuildCards()
    {
        var shown = Filter == "all"
            ? _capabilities
            : _capabilities.Where(c => c.Kind == Filter).ToList();

        CountText = $"共 {shown.Count} 项";

        Cards.Clear();
        foreach (var cap in shown)
        {
            var (label, icon, tone) = KindMeta(cap.Kind);
            Cards.Add(new SkillCardView(
                cap.Name,
                label,
                string.IsNullOrWhiteSpace(cap.Version) ? label : $"{label} · v{cap.Version}",
                cap.Source ?? "—",
                cap.Kind,
                tone,
                IconPaths.GetGeometry(icon),
                ShortDate(cap.UpdatedAt)));
        }

        HasCards = Cards.Count > 0;
    }

    private static (string Label, string Icon, IBrush Tone) KindMeta(string kind) => kind switch
    {
        "agent" => ("Agent 档案", "bot", Brand),
        "skill" => ("Skill 能力包", "puzzle", Info),
        "connector" => ("Connector", "plug", Brand),
        _ => (kind, "package", Info),
    };

    private static string KindLabel(string kind) => KindMeta(kind).Label;

    /// <summary>日期只到天（web 端 toLocaleDateString）。</summary>
    private static string ShortDate(string? iso) =>
        DateTimeOffset.TryParse(iso, out var moment) ? moment.LocalDateTime.ToString("yyyy/M/d") : "—";
}
