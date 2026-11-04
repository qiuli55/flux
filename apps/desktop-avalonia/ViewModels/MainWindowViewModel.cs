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

    /// <summary>轻提示文案：Ready 态下状态栏不可见，用它给操作回执（如打开 IDE）。</summary>
    [ObservableProperty]
    private string _toast = "";

    [ObservableProperty]
    private bool _isToastVisible;

    private int _toastGeneration;

    /// <summary>弹 2.6 秒轻提示；连续调用只保留最后一次。</summary>
    private async void ShowToast(string message)
    {
        Toast = message;
        IsToastVisible = true;
        var generation = ++_toastGeneration;
        await Task.Delay(2600);
        if (generation == _toastGeneration)
        {
            IsToastVisible = false;
        }
    }

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
    [NotifyPropertyChangedFor(nameof(ShowShellStatusBar))]
    private ViewModelBase? _currentPage;

    /// <summary>侧栏是否展开为 248px（web 端 prefs.wide，默认收起为 68px 图标态）。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsRailCompact))]
    [NotifyPropertyChangedFor(nameof(RailWidth))]
    [NotifyPropertyChangedFor(nameof(RailToggleTip))]
    [NotifyPropertyChangedFor(nameof(RailToggleIconGeometry))]
    private bool _isRailWide;

    /// <summary>显示底部状态栏（web 端 prefs.statusbar）。</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowShellStatusBar))]
    private bool _isStatusBarVisible = true;

    /// <summary>减少动效：关掉状态栏脉冲动画（web 端 prefs.reduceMotion）。</summary>
    [ObservableProperty]
    private bool _isReduceMotion;

    /// <summary>内置浏览器代理模式：system / direct / custom（与 prefs.json 同步）。</summary>
    [ObservableProperty]
    private string _browserProxyMode = "system";

    /// <summary>内置浏览器自定义代理地址（仅 custom 模式使用）。</summary>
    [ObservableProperty]
    private string _browserProxyUrl = "";

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
        // 界面偏好先读盘再建导航，避免首帧闪一下再切换
        var prefs = UiPrefs.Load();
        _isRailWide = prefs.RailWide;
        _isStatusBarVisible = prefs.StatusBar;
        _isReduceMotion = prefs.ReduceMotion;
        _browserProxyMode = prefs.ProxyMode;
        _browserProxyUrl = prefs.ProxyUrl;

        Account = new AccountViewModel
        {
            LoginCommand = LoginCommand,
            LogoutCommand = LogoutCommand,
        };
        _accountNavItem = new NavItem("account", "账户", GroupPlatform, Account, "user")
        {
            ActivateCommand = ActivateCommand,
        };

        BuildPlatform();
        BuildTools();

        // 落地页 = 概览（工作页）；账户入口在左栏底部，不占用导航分组。
        Activate(NavItems[0]);
    }

    public AccountViewModel Account { get; }

    /// <summary>Solo 工作台入口与工具组（原生 IDE 已移除，改用独立 IDE 窗口）。</summary>
    public ObservableCollection<NavItem> ToolItems { get; } = new();

    /// <summary>平台层页面（web 端 NAV_GROUPS）。</summary>
    public ObservableCollection<NavItem> NavItems { get; } = new();

    /// <summary>命令面板候选结果。</summary>
    public ObservableCollection<NavItem> PaletteResults { get; } = new();

    public bool IsBooting => State == ShellState.Booting;

    public bool IsShellVisible => State == ShellState.Ready;

    public bool IsGateVisible => State == ShellState.LoginRequired;

    public bool IsFailed => State == ShellState.Failed;

    public bool ShowShellStatusBar => IsStatusBarVisible;

    public bool IsRailCompact => !IsRailWide;

    /// <summary>侧栏宽度：对齐 web 端 --tn-rail(68px) / --tn-rail-wide(248px)。</summary>
    public double RailWidth => IsRailWide ? 248 : 68;

    public string RailToggleTip => IsRailWide ? "收起侧栏" : "展开侧栏";

    public Geometry RailToggleIconGeometry => IconPaths.GetGeometry(IsRailWide ? "chevron" : "grid");

    public Geometry SearchIconGeometry { get; } = IconPaths.GetGeometry("search");

    public Geometry IdeIconGeometry { get; } = IconPaths.GetGeometry("code");

    /// <summary>本机是否装了独立的 Flux IDE，顶栏按钮据此给出提示。</summary>
    public bool IsIdeAvailable => IdeLauncher.IsAvailable;

    public string IdeButtonTip => IsIdeAvailable
        ? "打开独立的 Flux IDE 窗口"
        : "未找到 Flux IDE：请解压到 ~/.flux/ide，或用 FLUX_IDE_PATH 指定可执行文件";

    public bool HasError => !string.IsNullOrWhiteSpace(ErrorMessage);

    public bool HasEngine => !string.IsNullOrWhiteSpace(EngineText);

    public bool HasWorkspace => !string.IsNullOrWhiteSpace(WorkspaceText);

    /// <summary>侧栏工具组标题。</summary>
    public string ToolGroupLabel => "Solo 入口";

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

            // 后端就绪后再拉起独立 IDE 窗口：端口是随机的，需要把地址下发给 IDE 里的扩展。
            // 没装 IDE 就跳过，不影响主流程。
            if (IdeLauncher.IsAvailable)
            {
                IdeLauncher.Launch(out _, _backend.BaseUrl);
            }

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

    private void BuildPlatform()
    {
        NavItems.Add(new NavItem("overview", "概览", GroupPlatform, new OverviewViewModel(() => _api, NavigateTo), "home") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("tasks", "任务", GroupPlatform, new TasksViewModel(() => _api, NavigateTo), "layers") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("assistant", "助理", GroupPlatform, new AssistantViewModel(() => _api), "bot") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("bots", "Bot", GroupPlatform, new BotsViewModel(() => _api), "cpu") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("skills", "Skills", GroupPlatform, new SkillsViewModel(() => _api), "puzzle") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("designs", "设计", GroupPlatform, new DesignsViewModel(() => _api), "design") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("browser", "浏览器", GroupPlatform, new BrowserViewModel(), "globe") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("billing", "成本中心", GroupPlatform, new BillingViewModel(() => _api), "credit") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("diagnostics", "系统诊断", GroupPlatform, new DiagnosticsViewModel(() => _api), "activity") { ActivateCommand = ActivateCommand });
        NavItems.Add(new NavItem("settings", "设置", GroupPlatform, new SettingsViewModel(() => _api, this), "settings") { ActivateCommand = ActivateCommand });
    }

    private void BuildTools()
    {
        ToolItems.Add(new NavItem("solo", "SOLO", GroupCore, new SoloViewModel(() => _api, NavigateTo), "sparkles") { ActivateCommand = ActivateCommand });
        ToolItems.Add(new NavItem("projects", "项目列表", GroupCore, new ProjectsViewModel(() => _api, NavigateTo), "folder") { ActivateCommand = ActivateCommand });
        ToolItems.Add(new NavItem("terminal", "Agent Terminal", GroupCore, new TerminalViewModel(() => _api), "terminal") { ActivateCommand = ActivateCommand });
        ToolItems.Add(new NavItem("agents", "Agent 面板", GroupCore, new AgentsViewModel(() => _api), "bot") { ActivateCommand = ActivateCommand });

        ApplyRailCompact();
    }

    /// <summary>切换选中项：清掉所有条目的选中态，只留目标项，并同步内容区与面包屑。</summary>
    [RelayCommand]
    private void Activate(NavItem? item)
    {
        if (item is null)
        {
            return;
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
        switch (CurrentPage)
        {
            case SoloViewModel solo:
                _ = solo.RefreshAsync();
                break;
            case ProjectsViewModel projects:
                _ = projects.RefreshAsync();
                break;
            case AgentsViewModel agents:
                _ = agents.RefreshAsync();
                break;
            case TerminalViewModel terminal:
                _ = terminal.RefreshAsync();
                break;
            case OverviewViewModel overview:
                _ = overview.RefreshAsync();
                break;
            case TasksViewModel tasks:
                _ = tasks.RefreshAsync();
                break;
            case AssistantViewModel assistant:
                _ = assistant.RefreshAsync();
                break;
            case BotsViewModel bots:
                _ = bots.RefreshAsync();
                break;
            case SkillsViewModel skills:
                _ = skills.RefreshAsync();
                break;
            case DesignsViewModel designs:
                _ = designs.RefreshAsync();
                break;
            case DiagnosticsViewModel diagnostics:
                _ = diagnostics.RefreshAsync();
                break;
            case BillingViewModel billing:
                _ = billing.RefreshAsync();
                break;
            case SettingsViewModel settings:
                _ = settings.RefreshAsync();
                break;
        }
    }

    /// <summary>按 key 跳转（供页面内的快捷入口使用）：ide 拉起独立 IDE 窗口，其余走导航条目。</summary>
    private void NavigateTo(string key)
    {
        switch (key)
        {
            case "ide":
                OpenIdeWindow();
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

    /// <summary>打开独立的 Flux IDE 窗口（VSCodium 打包产物）；已在运行则切到前台。</summary>
    [RelayCommand]
    private void OpenIdeWindow()
    {
        AppLog.Info($"打开 IDE · available={IdeLauncher.IsAvailable} · state={State}");
        var ok = IdeLauncher.Launch(out var message, _api is null ? null : _backend.BaseUrl);
        StatusMessage = message;
        ShowToast(message);
        if (!ok)
        {
            AppLog.Warn($"打开 IDE 未成功 · {message}");
        }
    }

    [RelayCommand]
    private void ToggleRailWide() => IsRailWide = !IsRailWide;

    partial void OnIsRailWideChanged(bool value)
    {
        ApplyRailCompact();
        SavePrefs();
    }

    partial void OnIsStatusBarVisibleChanged(bool value) => SavePrefs();

    partial void OnIsReduceMotionChanged(bool value) => SavePrefs();

    partial void OnBrowserProxyModeChanged(string value) => SavePrefs();

    partial void OnBrowserProxyUrlChanged(string value) => SavePrefs();

    /// <summary>把界面偏好恢复默认（设置页的「重置」用）。</summary>
    [RelayCommand]
    public void ResetPrefs()
    {
        IsRailWide = UiPrefs.Defaults.RailWide;
        IsStatusBarVisible = UiPrefs.Defaults.StatusBar;
        IsReduceMotion = UiPrefs.Defaults.ReduceMotion;
        BrowserProxyMode = UiPrefs.Defaults.ProxyMode;
        BrowserProxyUrl = UiPrefs.Defaults.ProxyUrl;
    }

    private void SavePrefs() =>
        UiPrefs.Save(new UiPrefsData(IsRailWide, IsStatusBarVisible, IsReduceMotion)
        {
            ProxyMode = BrowserProxyMode,
            ProxyUrl = BrowserProxyUrl,
        });

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

    /// <summary>点击命令面板条目：切换页面并收起面板（键盘 Enter 走 RunPaletteSelection）。</summary>
    [RelayCommand]
    private void RunPaletteItem(NavItem? item)
    {
        if (item is null)
        {
            return;
        }

        Activate(item);
        IsPaletteOpen = false;
    }

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
