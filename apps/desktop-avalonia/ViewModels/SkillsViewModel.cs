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

/// <summary>
/// 连接器 · 飞书单聊 / 个人微信（官方微信 ClawBot）卡片。
///
/// 两条连接都跑在 Flux 常驻服务端；这里只展示状态、扫码（半块字符二维码）与断开。
/// </summary>
public sealed partial class ImConnectorView : ObservableObject
{
    private readonly SkillsViewModel _owner;

    public ImConnectorView(string id, SkillsViewModel owner)
    {
        Id = id;
        _owner = owner;
        ConnectCommand = new AsyncRelayCommand(() => _owner.ConnectImAsync(this));
        DisconnectCommand = new AsyncRelayCommand(() => _owner.DisconnectImAsync(this));
        SubmitCodeCommand = new AsyncRelayCommand(() => _owner.SubmitImCodeAsync(this));
        OpenUrlCommand = new RelayCommand(OpenUrl);
    }

    public string Id { get; }

    public string Title => Id == "feishu" ? "飞书" : "个人微信";

    public bool IsWechat => Id == "wechat";

    public string ConnectLabel => Id == "feishu" ? "连接飞书" : "连接个人微信";

    [ObservableProperty]
    private string _statusText = "未连接";

    [ObservableProperty]
    private string _detail = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasMessage))]
    private string _message = "";

    public bool HasMessage => !string.IsNullOrWhiteSpace(Message);

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasQr))]
    private string _qrText = "";

    public bool HasQr => !string.IsNullOrWhiteSpace(QrText);

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasBackupUrl))]
    private string _backupUrl = "";

    public bool HasBackupUrl => !string.IsNullOrWhiteSpace(BackupUrl);

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

    public ICommand ConnectCommand { get; }

    public ICommand DisconnectCommand { get; }

    public ICommand SubmitCodeCommand { get; }

    public ICommand OpenUrlCommand { get; }

    public void Apply(ImConnectorStatus status, WechatLoginSession? login = null)
    {
        // 飞书长连接已启动但还没收到第一条消息时，按钮应显示「断开」而不是重复「连接」。
        IsConnected = status.Connected || (Id == "feishu" && status.WaitingForMessage);
        Message = status.Message;
        if (Id == "feishu")
        {
            StatusText = status.Connected
                ? "已连接 · 飞书单聊"
                : status.WaitingForMessage
                    ? "等待你在飞书发第一条消息"
                    : !status.Configured
                        ? "未配置飞书应用"
                        : status.NeedsReauth
                            ? "连接异常，需要处理"
                            : "未连接";
            Detail =
                "在飞书开放平台创建企业自建应用，开启「长连接接收事件」并订阅 im.message.receive_v1，" +
                "只处理单聊。服务端需配置 FLUX_FEISHU_IM_APP_ID / FLUX_FEISHU_IM_APP_SECRET。";
            QrText = "";
            BackupUrl = "";
            NeedsCode = false;
            return;
        }

        StatusText = status.Connected
            ? status.HasContextToken
                ? "已连接 · 微信 ClawBot"
                : "已连接 · 请先在 ClawBot 会话里发一句话"
            : status.NeedsReauth
                ? "登录已过期，需要重新扫码"
                : login is not null && login.State is not ("failed" or "confirmed")
                    ? "等待扫码确认"
                    : "未连接";
        Detail =
            "需要 iOS 微信 8.0.70+ 或 Android 8.0.69+，并在「我 → 设置 → 插件」启用「微信 ClawBot」。" +
            "这两条是常见前提，官方 README 并未写死这些版本号。" +
            "绑定成功后请先在会话里发一句话；没有 context_token 时回复发不出去。" +
            "同一个微信号不要再绑定到另一个后端，否则消息可能被分流。" +
            "微信只在你最近互动后允许主动消息；推不出去时会保留，下次对话补发。";
        Apply(login);
        if (status.PendingNotices > 0)
        {
            Message = $"{Message} 有 {status.PendingNotices} 条提醒等你下次开口后补发。";
        }
    }

    public void Apply(WechatLoginSession? login)
    {
        if (login is null)
        {
            QrText = "";
            BackupUrl = "";
            NeedsCode = false;
            LoginId = "";
            return;
        }
        LoginId = login.LoginId;
        Message = string.IsNullOrWhiteSpace(login.Message) ? Message : login.Message;
        BackupUrl = login.BackupUrl ?? "";
        NeedsCode = login.NeedsVerifyCode;
        QrText = RenderQr(login.QrcodeMatrix);
        if (login.State == "confirmed")
        {
            IsConnected = true;
            StatusText = "已连接 · 请先在微信 ClawBot 会话里发一句话";
            NeedsCode = false;
            QrText = "";
        }
        else if (login.State == "failed")
        {
            IsConnected = false;
            StatusText = "绑定失败，请重新扫码";
            NeedsCode = false;
            QrText = "";
        }
    }

    /// <summary>把 0/1 矩阵压成半块字符，直接放进等宽 TextBlock 就能扫。</summary>
    private static string RenderQr(string[] rows)
    {
        if (rows.Length == 0 || rows[0].Length == 0)
        {
            return "";
        }

        const int quiet = 2;
        var width = rows[0].Length + quiet * 2;
        var builder = new System.Text.StringBuilder();
        for (var y = -quiet; y < rows.Length + quiet; y += 2)
        {
            for (var x = -quiet; x < width - quiet; x += 1)
            {
                var top = Bit(rows, x, y);
                var bottom = Bit(rows, x, y + 1);
                builder.Append(top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ');
            }
            builder.Append('\n');
        }
        return builder.ToString();
    }

    private static bool Bit(string[] rows, int x, int y)
    {
        if (y < 0 || y >= rows.Length || x < 0 || x >= rows[y].Length)
        {
            return false;
        }
        return rows[y][x] == '1';
    }

    private void OpenUrl()
    {
        if (string.IsNullOrWhiteSpace(BackupUrl))
        {
            return;
        }
        try
        {
            Process.Start(new ProcessStartInfo(BackupUrl) { UseShellExecute = true });
        }
        catch (Exception)
        {
            _owner.StatusText = "打开备份链接失败，请复制链接到微信里打开。";
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

    /// <summary>「连接器」里的飞书 / 个人微信入口。</summary>
    public ObservableCollection<ImConnectorView> ImConnectors { get; } = new();

    private readonly Dictionary<string, ImConnectorView> _imViews = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoImConnectors))]
    private bool _hasImConnectors;

    public bool HasNoImConnectors => !HasImConnectors;

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
            await RefreshImConnectorsAsync().ConfigureAwait(true);
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

    private async Task RefreshImConnectorsAsync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var result = await api.ImConnectorsAsync().ConfigureAwait(true);
        if (result?.Data is not { } overview)
        {
            if (!string.IsNullOrWhiteSpace(result?.Message))
            {
                StatusText = result.Message;
            }
            return;
        }

        EnsureImView("feishu", overview.Feishu, null);
        EnsureImView("wechat", overview.Wechat, overview.Wechat.PendingLogin);
        HasImConnectors = ImConnectors.Count > 0;

        var pending = overview.Wechat.PendingLogin;
        if (pending is not null && pending.State is "pending" or "scaned" or "need_verifycode")
        {
            _ = PollWechatLoginAsync(_imViews["wechat"], pending.LoginId);
        }
    }

    private void EnsureImView(string id, ImConnectorStatus status, WechatLoginSession? login)
    {
        if (!_imViews.TryGetValue(id, out var view))
        {
            view = new ImConnectorView(id, this);
            _imViews[id] = view;
            ImConnectors.Add(view);
        }
        view.Apply(status, login);
    }

    public async Task ConnectImAsync(ImConnectorView view)
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
            if (view.Id == "feishu")
            {
                var result = await api.ConnectFeishuAsync().ConfigureAwait(true);
                if (result?.Data is { } status)
                {
                    view.Apply(status);
                    StatusText = "飞书长连接已启动，请在飞书里给机器人发第一条单聊消息";
                }
                else
                {
                    view.Message = result?.Message ?? "启动飞书连接失败。";
                }
                view.IsBusy = false;
                return;
            }

            var login = (await api.ConnectWechatAsync().ConfigureAwait(true))?.Data;
            if (login is null)
            {
                view.Message = "申请微信绑定二维码失败。";
                view.IsBusy = false;
                return;
            }
            view.Apply((await api.ImConnectorsAsync().ConfigureAwait(true))?.Data?.Wechat
                ?? new ImConnectorStatus("wechat", true, true, false, false, false, false, false, false, 0, null, null, "等待扫码确认…", new ImConnectorCapabilities(true, true, false, null)),
                login);
            _ = PollWechatLoginAsync(view, login.LoginId);
        }
        catch (Exception exc)
        {
            view.Message = exc.Message;
            view.IsBusy = false;
        }
    }

    private async Task PollWechatLoginAsync(ImConnectorView view, string loginId)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }
        view.IsBusy = true;
        for (var attempt = 0; attempt < 160; attempt += 1)
        {
            await Task.Delay(TimeSpan.FromSeconds(2)).ConfigureAwait(true);
            var login = (await api.WechatLoginStatusAsync(loginId).ConfigureAwait(true))?.Data;
            if (login is null)
            {
                view.IsBusy = false;
                return;
            }
            view.Apply(login);
            if (login.State == "confirmed")
            {
                view.IsBusy = false;
                StatusText = "个人微信已连接；请先在微信 ClawBot 会话里发一句话";
                await RefreshImConnectorsAsync().ConfigureAwait(true);
                return;
            }
            if (login.State == "failed")
            {
                view.IsBusy = false;
                StatusText = login.Message;
                return;
            }
        }
        view.IsBusy = false;
        view.Message = "扫码绑定超时，请重新获取二维码。";
    }

    public async Task SubmitImCodeAsync(ImConnectorView view)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(view.Code) || string.IsNullOrWhiteSpace(view.LoginId))
        {
            view.Message = "请先填写手机微信显示的数字。";
            return;
        }

        view.IsBusy = true;
        try
        {
            var login = (await api.SubmitWechatCodeAsync(view.LoginId, view.Code).ConfigureAwait(true))?.Data;
            if (login is null)
            {
                view.Message = "提交数字失败。";
                view.IsBusy = false;
                return;
            }
            view.Code = "";
            view.Apply(login);
            _ = PollWechatLoginAsync(view, login.LoginId);
        }
        catch (Exception exc)
        {
            view.Message = exc.Message;
            view.IsBusy = false;
        }
    }

    public async Task DisconnectImAsync(ImConnectorView view)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        view.IsBusy = true;
        try
        {
            if (view.Id == "feishu")
            {
                var result = await api.DisconnectFeishuAsync().ConfigureAwait(true);
                if (result?.Data is { } status)
                {
                    view.Apply(status);
                }
                StatusText = "已断开飞书单聊";
            }
            else
            {
                var result = await api.DisconnectWechatAsync().ConfigureAwait(true);
                if (result?.Data is { } status)
                {
                    view.Apply(status, null);
                }
                StatusText = "已断开个人微信";
            }
            await RefreshImConnectorsAsync().ConfigureAwait(true);
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
