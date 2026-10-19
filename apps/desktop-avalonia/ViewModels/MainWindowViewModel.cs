using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Media;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

public enum ShellState
{
    Booting,
    LoginRequired,
    Ready,
    Failed,
}

/// <summary>
/// 主窗口状态机：启动后端 → 云登录闸门 → 工作台外壳。
/// 外壳结构对齐 web 端 shell/Shell.tsx：左侧 68/248px 可收起 Rail、顶栏、内容区、状态栏。
/// </summary>
public partial class MainWindowViewModel : ViewModelBase
{
    private const string GroupCore = "核心";
    private const string GroupPlatform = "平台";

    private readonly LoopbackCodeListener _listener = new();
    private readonly BackendHost _backend = new();

    private FluxApiClient? _api;
    private CloudAuthService? _auth;
    private DispatcherTimer? _healthTimer;
    private NavItem? _accountNavItem;
    private string? _loginUrl;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsBooting))]
    [NotifyPropertyChangedFor(nameof(IsShellVisible))]
    [NotifyPropertyChangedFor(nameof(IsGateVisible))]
    [NotifyPropertyChangedFor(nameof(IsFailed))]
    private ShellState _state = ShellState.Booting;

    [ObservableProperty]
    private string _statusMessage = "正在启动本地服务…";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasError))]
    private string _errorMessage = "";

    [ObservableProperty]
    private bool _isBusy;

    /// <summary>状态栏后端项文案（对齐 web 的「后端正常 · env」）。</summary>
    [ObservableProperty]
    private string _backendText = "后端未连接";

    /// <summary>引擎胶囊文案；为空表示后端未上报 DSH 状态，顶栏与状态栏都不显示该块。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasEngine))]
    private string _engineText = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasWorkspace))]
    private string _workspaceText = "";

    [ObservableProperty]
    private bool _isHealthOk;

    [ObservableProperty]
    private string _breadcrumbGroup = GroupPlatform;

    [ObservableProperty]
    private string _breadcrumb = "概览";

    [ObservableProperty]
    private ViewModelBase? _currentPage;

    /// <summary>侧栏是否展开为 248px（web 端 prefs.wide，默认收起为 68px 图标态）。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsRailCompact))]
    [NotifyPropertyChangedFor(nameof(RailWidth))]
    [NotifyPropertyChangedFor(nameof(RailToggleTip))]
    [NotifyPropertyChangedFor(nameof(RailToggleIconGeometry))]
    private bool _isRailWide;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsIdeMode))]
    private bool _isSoloMode = true;

    [ObservableProperty]
    private bool _isPaletteOpen;

    [ObservableProperty]
    private string _paletteQuery = "";

    [ObservableProperty]
    private NavItem? _paletteSelected;

    [ObservableProperty]
    private bool _hasPaletteResults;

    public MainWindowViewModel()
    {
        Account = new AccountViewModel
        {
            LoginCommand = LoginCommand,
            LogoutCommand = LogoutCommand,
        };
        _accountNavItem = new NavItem("account", "账户", GroupPlatform, Account, "user")
        {
            ActivateCommand = ActivateCommand,
        };

        BuildModes();
        BuildPlatform();
        BuildTools();

        // 落地页 = 概览（工作页）；账户入口在左栏底部，不占用导航分组。
        Activate(NavItems[0]);
    }

    public AccountViewModel Account { get; }

    /// <summary>模式切换：Solo / IDE（web 端 NAV_MODES）。</summary>
    public ObservableCollection<NavItem> ModeItems { get; } = new();

    /// <summary>当前模式专属的工具组（web 端 modeTools：Solo 入口 / IDE 面板）。</summary>
    public ObservableCollection<NavItem> ToolItems { get; } = new();

    /// <summary>平台层页面（web 端 NAV_GROUPS）。</summary>
    public ObservableCollection<NavItem> NavItems { get; } = new();

    /// <summary>命令面板候选结果。</summary>
    public ObservableCollection<NavItem> PaletteResults { get; } = new();

    public bool IsBooting => State == ShellState.Booting;

    public bool IsShellVisible => State == ShellState.Ready;

    public bool IsGateVisible => State == ShellState.LoginRequired;

    public bool IsFailed => State == ShellState.Failed;

    public bool IsIdeMode => !IsSoloMode;

    public bool IsRailCompact => !IsRailWide;

    /// <summary>侧栏宽度：对齐 web 端 --tn-rail(68px) / --tn-rail-wide(248px)。</summary>
    public double RailWidth => IsRailWide ? 248 : 68;

    public string RailToggleTip => IsRailWide ? "收起侧栏" : "展开侧栏";

    public Geometry RailToggleIconGeometry => IconPaths.GetGeometry(IsRailWide ? "chevron" : "grid");

    public Geometry SearchIconGeometry { get; } = IconPaths.GetGeometry("search");

    public bool HasError => !string.IsNullOrWhiteSpace(ErrorMessage);

    public bool HasEngine => !string.IsNullOrWhiteSpace(EngineText);

    public bool HasWorkspace => !string.IsNullOrWhiteSpace(WorkspaceText);

    /// <summary>当前模式下的工具组标题（web 端「Solo 入口」/「IDE 面板」）。</summary>
    public string ToolGroupLabel => IsSoloMode ? "Solo 入口" : "IDE 面板";

    public async Task StartAsync()
    {
        try
        {
            ErrorMessage = "";
            State = ShellState.Booting;
            StatusMessage = $"正在启动本地服务（{AppPaths.PythonExecutable}）…";

            await _backend.StartAsync(_listener.CallbackUrl).ConfigureAwait(true);

            _api = new FluxApiClient(_backend.BaseUrl);
            _auth = new CloudAuthService(_api, _listener);

            SyncAccount();
            await RefreshSessionAsync().ConfigureAwait(true);
            StartHealthTimer();

            // 首屏的 Activate 发生在后端拉起之前，这里补一次拉取，否则落地页会一直停在初始空值
            RefreshCurrentPage();
        }
        catch (Exception ex)
        {
            State = ShellState.Failed;
            ErrorMessage = ex.Message;
        }
    }

    private async Task RefreshSessionAsync()
    {
        if (_api is null)
        {
            return;
        }

        StatusMessage = "正在读取账号状态…";
        var session = (await _api.GetCloudSessionAsync().ConfigureAwait(true))?.Data;
        if (session is null)
        {
            State = ShellState.Failed;
            ErrorMessage = "本地后端未响应 /api/v1/cloud/session。";
            return;
        }

        _loginUrl = session.LoginUrl;
        ApplyUser(session.User);
        Account.IsAuthenticated = session.Authenticated;

        if (session.Configured && !session.Authenticated)
        {
            State = ShellState.LoginRequired;
            StatusMessage = "请使用 Flux 云账号登录后继续。";
            return;
        }

        State = ShellState.Ready;
        StatusMessage = "就绪";
        SyncAccount();
    }

    private void ApplyUser(CloudUser? user)
    {
        if (user is null)
        {
            Account.Username = "本地工作区";
            Account.Subtitle = "单用户 · 共享 Token";
            Account.AvatarText = "本";
            Account.UserIdText = "—";
            return;
        }

        Account.Username = user.Username;
        Account.Subtitle = "Flux 云账号";
        Account.AvatarText = string.IsNullOrEmpty(user.Username) ? "F" : user.Username[..1].ToUpperInvariant();
        Account.UserIdText = user.Id;
    }

    private void SyncAccount()
    {
        Account.WorkspaceRoot = Environment.GetEnvironmentVariable("FLUX_WORKSPACE_ROOT")
            ?? System.IO.Path.Combine(AppPaths.DataDir, "workspace");
        Account.DataDir = AppPaths.DataDir;
        Account.BackendUrl = _backend.BaseUrl;
        Account.CallbackUrl = _listener.CallbackUrl;
    }

    private async Task PollHealthAsync()
    {
        if (_api is null)
        {
            return;
        }

        var health = (await _api.HealthAsync().ConfigureAwait(true))?.Data;
        if (health is not null && string.Equals(health.Status, "ok", StringComparison.OrdinalIgnoreCase))
        {
            IsHealthOk = true;
            BackendText = $"后端正常 · {health.Env}";
        }
        else
        {
            IsHealthOk = false;
            BackendText = "后端未连接";
        }

        var dsh = (await _api.DshStatusAsync().ConfigureAwait(true))?.Data;
        if (dsh is null)
        {
            EngineText = "";
            WorkspaceText = "";
            return;
        }

        EngineText = string.IsNullOrWhiteSpace(dsh.Provider) && string.IsNullOrWhiteSpace(dsh.Model)
            ? ""
            : $"引擎 {dsh.Provider}/{dsh.Model}";
        WorkspaceText = dsh.Workspace ?? "";
    }

    private void StartHealthTimer()
    {
        _healthTimer ??= new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        _healthTimer.Tick -= OnHealthTick;
        _healthTimer.Tick += OnHealthTick;
        _healthTimer.Start();
        _ = PollHealthAsync();
    }

    private async void OnHealthTick(object? sender, EventArgs e) => await PollHealthAsync();

    private void BuildModes()
    {
        var solo = Placeholder(
            "Solo 执行",
            "把需求交给 AI：澄清需求 → 可行性计划 → 生成变更 → 人工审核 → 落盘 / Git。Solo 任务台将在 M3 实现。");
        var ide = Placeholder(
            "IDE 工作台",
            "文件资源管理器、搜索、源代码管理、运行和调试、扩展。IDE 工作台将在 M4 实现。");

        ModeItems.Add(new NavItem("solo", "SOLO", GroupCore, solo, "sparkles") { ActivateCommand = ActivateCommand });
        ModeItems.Add(new NavItem("ide", "IDE", GroupCore, ide, "code") { ActivateCommand = ActivateCommand });
    }

    private void BuildPlatform()
    {
        NavItems.Add(new NavItem("overview", "概览", GroupPlatform, new OverviewViewModel(() => _api, NavigateTo), "home") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("tasks", "任务", GroupPlatform, Placeholder("任务", "任务看板将在 M2 实现。"), "layers") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("skills", "Skills", GroupPlatform, Placeholder("Skills", "Skills 管理将在 M2 实现。"), "puzzle") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("diagnostics", "系统诊断", GroupPlatform, Placeholder("系统诊断", "系统诊断将在 M2 实现。"), "activity") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("settings", "设置", GroupPlatform, Placeholder("设置", "设置页将在 M2 实现。"), "settings") { ActivateCommand = ActivateCommand });
    }

    private void BuildTools()
    {
        ToolItems.Clear();
        if (IsSoloMode)
        {
            ToolItems.Add(new NavItem("projects", "项目列表", GroupCore, Placeholder("项目列表", "Solo 项目列表将在 M3 实现。"), "folder") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("open-ide", "工作区 · 打开 IDE", GroupCore, Placeholder("工作区", "工作区与 IDE 切换将在 M3 / M4 实现。"), "code") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("terminal", "Agent Terminal", GroupCore, Placeholder("Agent Terminal", "Agent 终端将在 M3 实现。"), "terminal") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("agents", "Agent 面板", GroupCore, Placeholder("Agent 面板", "Agent 面板将在 M3 实现。"), "bot") { ActivateCommand = ActivateCommand });
        }
        else
        {
            ToolItems.Add(new NavItem("files", "文件资源管理器", GroupCore, Placeholder("文件资源管理器", "IDE 文件树将在 M4 实现。"), "files") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("search", "搜索", GroupCore, Placeholder("搜索", "全局搜索将在 M4 实现。"), "search") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("scm", "源代码管理", GroupCore, Placeholder("源代码管理", "Git 集成将在 M4 实现。"), "git") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("run", "运行和调试", GroupCore, Placeholder("运行和调试", "调试能力将在 M4 实现。"), "debug") { ActivateCommand = ActivateCommand });
            ToolItems.Add(new NavItem("extensions", "扩展", GroupCore, Placeholder("扩展", "扩展市场将在 M4 实现。"), "grid") { ActivateCommand = ActivateCommand });
        }

        OnPropertyChanged(nameof(ToolGroupLabel));
        ApplyRailCompact();
    }

    private static PlaceholderPageViewModel Placeholder(string title, string subtitle) => new(title, subtitle);

    partial void OnIsSoloModeChanged(bool value)
    {
        BuildTools();
        Activate(value ? ModeItems[0] : ModeItems[1]);
    }

    /// <summary>切换选中项：清掉所有条目的选中态，只留目标项，并同步内容区与面包屑。</summary>
    [RelayCommand]
    private void Activate(NavItem? item)
    {
        if (item is null)
        {
            return;
        }

        foreach (var candidate in ModeItems)
        {
            candidate.IsActive = ReferenceEquals(candidate, item);
        }

        foreach (var candidate in ToolItems)
        {
            candidate.IsActive = ReferenceEquals(candidate, item);
        }

        foreach (var candidate in NavItems)
        {
            candidate.IsActive = ReferenceEquals(candidate, item);
        }

        CurrentPage = item.Page;
        BreadcrumbGroup = item.Group;
        Breadcrumb = item.Label;

        // 概览是真实数据页，每次进入都重新拉一次
        RefreshCurrentPage();
    }

    /// <summary>当前页若是真实数据页则触发一次拉取（后端未就绪时页面自己会跳过）。</summary>
    private void RefreshCurrentPage()
    {
        if (CurrentPage is OverviewViewModel overview)
        {
            _ = overview.RefreshAsync();
        }
    }

    /// <summary>按 key 跳转（供页面内的快捷入口使用）：solo / ide 走模式，其余走导航条目。</summary>
    private void NavigateTo(string key)
    {
        switch (key)
        {
            case "solo":
                IsSoloMode = true;
                Activate(ModeItems[0]);
                return;
            case "ide":
                IsSoloMode = false;
                Activate(ModeItems[1]);
                return;
            case "account":
                Activate(_accountNavItem);
                return;
        }

        var target = NavItems.FirstOrDefault(n => n.Key == key)
            ?? ToolItems.FirstOrDefault(n => n.Key == key);

        if (target is not null)
        {
            Activate(target);
        }
    }

    [RelayCommand]
    private void SwitchMode(string? mode) =>
        IsSoloMode = !string.Equals(mode, "IDE", StringComparison.OrdinalIgnoreCase);

    [RelayCommand]
    private void ToggleRailWide() => IsRailWide = !IsRailWide;

    partial void OnIsRailWideChanged(bool value) => ApplyRailCompact();

    [RelayCommand]
    private void OpenPalette()
    {
        PaletteQuery = "";
        IsPaletteOpen = true;
        RefreshPalette();
    }

    [RelayCommand]
    private void ClosePalette() => IsPaletteOpen = false;

    [RelayCommand]
    private void RunPaletteSelection()
    {
        if (PaletteSelected is null)
        {
            return;
        }

        Activate(PaletteSelected);
        IsPaletteOpen = false;
    }

    partial void OnPaletteQueryChanged(string value) => RefreshPalette();

    private void RefreshPalette()
    {
        var query = PaletteQuery.Trim();
        var matches = new List<NavItem>();

        foreach (var item in CollectNavigable())
        {
            if (query.Length == 0
                || item.Label.Contains(query, StringComparison.OrdinalIgnoreCase)
                || item.Key.Contains(query, StringComparison.OrdinalIgnoreCase))
            {
                matches.Add(item);
            }
        }

        PaletteResults.Clear();
        foreach (var match in matches)
        {
            PaletteResults.Add(match);
        }

        HasPaletteResults = PaletteResults.Count > 0;
        PaletteSelected = HasPaletteResults ? PaletteResults[0] : null;
    }

    private IEnumerable<NavItem> CollectRailItems()
    {
        foreach (var item in ModeItems)
        {
            yield return item;
        }

        foreach (var item in ToolItems)
        {
            yield return item;
        }

        foreach (var item in NavItems)
        {
            yield return item;
        }
    }

    private IEnumerable<NavItem> CollectNavigable()
    {
        foreach (var item in CollectRailItems())
        {
            yield return item;
        }

        if (_accountNavItem is not null)
        {
            yield return _accountNavItem;
        }
    }

    /// <summary>把当前侧栏宽度同步到每个条目（收起态只画图标）。</summary>
    private void ApplyRailCompact()
    {
        var compact = IsRailCompact;
        foreach (var item in CollectRailItems())
        {
            item.IsCompact = compact;
        }
    }

    [RelayCommand]
    private async Task LoginAsync()
    {
        if (_auth is null)
        {
            return;
        }

        try
        {
            IsBusy = true;
            StatusMessage = "正在获取登录地址…";

            // login_url 里的 state 有 10 分钟有效期，每次登录都必须重新取一次，
            // 否则重试时会复用已过期的旧地址，导致账号云判定 state 无效。
            await RefreshSessionAsync().ConfigureAwait(true);

            if (string.IsNullOrWhiteSpace(_loginUrl))
            {
                State = ShellState.Failed;
                ErrorMessage = "后端未提供登录地址，请检查云配置（FLUX_CLOUD_BASE_URL / FLUX_CLOUD_SECRET_KEY）。";
                return;
            }

            StatusMessage = "已打开浏览器，请在 10 分钟内完成登录…";
            var outcome = await _auth.LoginAsync(_loginUrl!).ConfigureAwait(true);

            if (outcome.Tokens is null || !outcome.Tokens.Authenticated)
            {
                StatusMessage = outcome.Error ?? "登录未完成，请重试。";
                return;
            }

            ApplyUser(outcome.Tokens.User);
            Account.IsAuthenticated = true;
            await RefreshSessionAsync().ConfigureAwait(true);
        }
        catch (Exception ex)
        {
            StatusMessage = "登录失败：" + ex.Message;
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task LogoutAsync()
    {
        if (_auth is null)
        {
            return;
        }

        await _auth.LogoutAsync().ConfigureAwait(true);
        Account.IsAuthenticated = false;
        ApplyUser(null);
        SyncAccount();
        await RefreshSessionAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RetryAsync()
    {
        if (_api is null)
        {
            await StartAsync().ConfigureAwait(true);
            return;
        }

        await RefreshSessionAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void OpenAccount() => Activate(_accountNavItem);

    public void Shutdown()
    {
        _healthTimer?.Stop();
        _backend.Dispose();
        _listener.Dispose();
        _api?.Dispose();
    }
}
