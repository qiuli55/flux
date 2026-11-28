using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
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

/// <summary>
/// Cursor / 谷歌 Antigravity 官方账号连接卡片。
///
/// 授权在用户电脑上的官方 CLI 里完成；桌面端只打开授权链接并轮询结果，
/// 不粘贴 API key / token，也不嵌官方网页 IDE。
/// </summary>
public sealed partial class AgentConnectorView : ObservableObject
{
    private readonly SkillsViewModel _owner;

    public AgentConnectorView(string id, string title, SkillsViewModel owner)
    {
        Id = id;
        Title = title;
        _owner = owner;
        ConnectCommand = new AsyncRelayCommand(() => _owner.StartAgentLoginAsync(this));
        LogoutCommand = new AsyncRelayCommand(() => _owner.LogoutAgentAsync(this));
        SubmitCodeCommand = new AsyncRelayCommand(() => _owner.SubmitAgentCodeAsync(this));
        OpenUrlCommand = new RelayCommand(OpenUrl);
    }

    public string Id { get; }

    public string Title { get; }

    [ObservableProperty]
    private string _statusText = "未连接";

    [ObservableProperty]
    private string _detail = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasMessage))]
    private string _message = "";

    public bool HasMessage => !string.IsNullOrWhiteSpace(Message);

    [ObservableProperty]
    private string _url = "";

    [ObservableProperty]
    private bool _hasUrl;

    [ObservableProperty]
    private bool _needsCode;

    [ObservableProperty]
    private string _code = "";

    [ObservableProperty]
    private string _loginId = "";

    [ObservableProperty]
    private bool _isConnected;

    [ObservableProperty]
    private bool _isBusy;

    public string ConnectLabel => Id == "cursor" ? "连接 Cursor" : "连接谷歌 / Antigravity";

    public ICommand ConnectCommand { get; }

    public ICommand LogoutCommand { get; }

    public ICommand SubmitCodeCommand { get; }

    public ICommand OpenUrlCommand { get; }

    public void Apply(AgentCliAccountStatus status)
    {
        IsConnected = status.LoggedIn;
        StatusText = status.LoggedIn
            ? "已连接 · 官方 CLI 登录态"
            : status.BinaryFound
                ? "未连接"
                : "未连接 · 未安装官方 CLI";
        Detail = status.BinaryFound
            ? "需要这台电脑保持在线；Flux 会在这里拉起官方 CLI 完成授权。"
            : Id == "cursor"
                ? "这台电脑上还没有找到官方 Cursor CLI（agent）。请先在电脑上安装，再回来连接。"
                : "这台电脑上还没有找到官方 Antigravity CLI（agy）。请先在电脑上安装，再回来连接。";
        if (!status.LoggedIn)
        {
            Url = "";
            HasUrl = false;
        }
    }

    public void Apply(AgentCliLoginSession session)
    {
        LoginId = session.LoginId;
        Url = session.Url;
        HasUrl = !string.IsNullOrWhiteSpace(session.Url);
        NeedsCode = session.NeedsCode && IsPending(session.Status);
        Message = session.Message;
        IsBusy = IsPending(session.Status);
        if (session.Status == "success")
        {
            IsConnected = true;
            StatusText = "已连接 · 官方 CLI 登录态";
            NeedsCode = false;
            HasUrl = false;
        }
    }

    public void ClearSession()
    {
        Url = "";
        HasUrl = false;
        NeedsCode = false;
        Message = "";
        Code = "";
    }

    private static bool IsPending(string status) => status is "starting" or "pending";

    private void OpenUrl()
    {
        var url = Url;
        if (string.IsNullOrWhiteSpace(url))
        {
            return;
        }
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception)
        {
            _owner.StatusText = "打开授权页失败，请复制链接到浏览器打开。";
        }
    }
}

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

    /// <summary>「连接器」里的 Cursor / 谷歌 Antigravity 官方账号入口。</summary>
    public ObservableCollection<AgentConnectorView> AgentConnectors { get; } = new();

    private readonly Dictionary<string, AgentConnectorView> _agentViews = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoAgentConnectors))]
    private bool _hasAgentConnectors;

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

    public bool HasNoAgentConnectors => !HasAgentConnectors;

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
            await RefreshAgentConnectorsAsync().ConfigureAwait(true);
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

    private async Task RefreshAgentConnectorsAsync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var page = await api.ListMcpConnectorsAsync().ConfigureAwait(true);
        var items = (page?.Data?.Items ?? []).Where(item => item.Id is "cursor" or "antigravity");
        foreach (var item in items)
        {
            if (!_agentViews.TryGetValue(item.Id, out var view))
            {
                view = new AgentConnectorView(
                    item.Id,
                    item.Id == "cursor" ? "Cursor" : "谷歌 / Antigravity",
                    this);
                _agentViews[item.Id] = view;
                AgentConnectors.Add(view);
            }

            var status = item.Id == "cursor"
                ? (await api.CursorAccountAsync().ConfigureAwait(true))?.Data
                : (await api.AntigravityAccountAsync().ConfigureAwait(true))?.Data;
            if (status is not null)
            {
                view.Apply(status);
            }
        }

        HasAgentConnectors = AgentConnectors.Count > 0;
    }

    public async Task StartAgentLoginAsync(AgentConnectorView view)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        view.IsBusy = true;
        view.Message = "";
        try
        {
            var result = await api.StartAgentLoginAsync(view.Id).ConfigureAwait(true);
            if (result?.Data is not { } session)
            {
                view.Message = result?.Message ?? "启动官方授权失败。";
                view.IsBusy = false;
                return;
            }

            view.Apply(session);
            if (!string.IsNullOrWhiteSpace(session.Url))
            {
                view.OpenUrlCommand.Execute(null);
            }
            if (session.Status is "starting" or "pending")
            {
                _ = PollAgentLoginAsync(view, session.LoginId);
            }
        }
        catch (Exception exc)
        {
            view.Message = exc.Message;
            view.IsBusy = false;
        }
    }

    private async Task PollAgentLoginAsync(AgentConnectorView view, string loginId)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        for (var attempt = 0; attempt < 600; attempt += 1)
        {
            await Task.Delay(TimeSpan.FromSeconds(2)).ConfigureAwait(true);
            var result = await api.AgentLoginStateAsync(view.Id, loginId).ConfigureAwait(true);
            if (result?.Data is not { } session)
            {
                view.IsBusy = false;
                return;
            }

            view.Apply(session);
            if (session.Status == "success")
            {
                StatusText = $"{view.Title} 已连接，可在 Agent 列表中选择";
                await RefreshAgentConnectorsAsync().ConfigureAwait(true);
                return;
            }
            if (session.Status is not ("starting" or "pending"))
            {
                StatusText = session.Message;
                return;
            }
        }
        view.IsBusy = false;
        view.Message = "登录超时，请重新发起。";
    }

    public async Task SubmitAgentCodeAsync(AgentConnectorView view)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(view.Code))
        {
            view.Message = "请先填写官方页面给出的授权码。";
            return;
        }

        view.IsBusy = true;
        try
        {
            var result = await api.SubmitAgentLoginCodeAsync(view.Id, view.LoginId, view.Code).ConfigureAwait(true);
            // 登录会话 id 只在待处理会话上；后端会按最新的 pending 会话校验。
            if (result?.Data is { } session)
            {
                view.Apply(session);
                view.Code = "";
                if (session.Status is "starting" or "pending")
                {
                    _ = PollAgentLoginAsync(view, session.LoginId);
                }
            }
            else
            {
                view.Message = result?.Message ?? "提交授权码失败。";
                view.IsBusy = false;
            }
        }
        catch (Exception exc)
        {
            view.Message = exc.Message;
            view.IsBusy = false;
        }
    }

    public async Task LogoutAgentAsync(AgentConnectorView view)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        view.IsBusy = true;
        try
        {
            var result = await api.LogoutAgentAccountAsync(view.Id).ConfigureAwait(true);
            if (result?.Data is { } logout)
            {
                view.Apply(logout.Status);
                view.ClearSession();
                StatusText = $"已退出 {view.Title} 账号";
            }
            await RefreshAgentConnectorsAsync().ConfigureAwait(true);
        }
        catch (Exception exc)
        {
            view.Message = exc.Message;
        }
        finally
        {
            view.IsBusy = false;
        }
    }

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
