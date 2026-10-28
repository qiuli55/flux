using System;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>浏览器工具栏动作，由视图翻译成 NativeWebView 调用。</summary>
public enum BrowserAction
{
    Back,
    Forward,
    Refresh,
    Stop,
    Home,
}

/// <summary>
/// 内置浏览器页：地址栏、导航状态、代理摘要与安全提示。
/// 真正的 WebView 在 BrowserView 里创建，本类只保存状态并通过事件下发指令。
/// </summary>
public sealed partial class BrowserViewModel : ViewModelBase
{
    private BrowserProxyConfig _proxy = new(BrowserProxyMode.System, "");

    public BrowserViewModel() => RefreshProxy();

    /// <summary>请求视图导航到目标地址。</summary>
    public event Action<Uri>? NavigateRequested;

    /// <summary>请求视图执行工具栏动作。</summary>
    public event Action<BrowserAction>? ActionRequested;

    public BrowserProxyConfig Proxy => _proxy;

    public string HomeUrl => BrowserProxy.HomeUrl;

    public Geometry BackIcon { get; } = IconPaths.GetGeometry("back");

    public Geometry ForwardIcon { get; } = IconPaths.GetGeometry("forward");

    public Geometry RefreshIcon { get; } = IconPaths.GetGeometry("refresh");

    public Geometry HomeIcon { get; } = IconPaths.GetGeometry("home");

    public Geometry GlobeIcon { get; } = IconPaths.GetGeometry("globe");

    public Geometry CloseIcon { get; } = IconPaths.GetGeometry("close");

    [ObservableProperty]
    private string _address = BrowserProxy.HomeUrl;

    [ObservableProperty]
    private string _proxySummary = "跟随系统代理";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStatus))]
    private string _statusText = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNotice))]
    private string _notice = "";

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _canGoBack;

    [ObservableProperty]
    private bool _canGoForward;

    [ObservableProperty]
    private bool _isEngineMissing;

    [ObservableProperty]
    private string _engineMessage = "";

    public bool HasStatus => StatusText.Length > 0;

    public bool HasNotice => Notice.Length > 0;

    /// <summary>每次进入浏览器页都重新读一遍落盘偏好，这样设置里改完代理即可生效。</summary>
    public void RefreshProxy()
    {
        var prefs = UiPrefs.Load();
        _proxy = BrowserProxy.FromPrefs(prefs.ProxyMode, prefs.ProxyUrl, out var error);
        ProxySummary = BrowserProxy.Describe(_proxy);
        OnPropertyChanged(nameof(Proxy));

        if (string.IsNullOrEmpty(error))
        {
            return;
        }

        ProxySummary += "（配置无效）";
        Notice = "浏览器代理配置无效，已回退为跟随系统：" + error;
    }

    [RelayCommand]
    private void Submit()
    {
        if (!BrowserProxy.TryResolveAddress(Address, out var uri))
        {
            Notice = "请输入有效的网址或搜索关键词";
            return;
        }

        Navigate(uri);
    }

    [RelayCommand]
    private void Back() => ActionRequested?.Invoke(BrowserAction.Back);

    [RelayCommand]
    private void Forward() => ActionRequested?.Invoke(BrowserAction.Forward);

    [RelayCommand]
    private void Refresh() => ActionRequested?.Invoke(BrowserAction.Refresh);

    [RelayCommand]
    private void Home() => ActionRequested?.Invoke(BrowserAction.Home);

    [RelayCommand]
    private void DismissNotice() => Notice = "";

    /// <summary>视图在创建 WebView 前探测引擎失败时调用。</summary>
    public void SetEngineMissing(string message)
    {
        IsEngineMissing = true;
        EngineMessage = message;
    }

    /// <summary>视图把导航开始事件回灌到状态栏。</summary>
    public void ReportStarted(Uri? uri)
    {
        IsLoading = true;
        StatusText = "";
        if (uri is not null)
        {
            Address = uri.ToString();
        }
    }

    /// <summary>视图把导航完成事件回灌到状态栏。</summary>
    public void ReportCompleted(Uri? uri, bool success)
    {
        IsLoading = false;
        if (uri is not null)
        {
            Address = uri.ToString();
        }

        StatusText = success ? "" : "页面加载失败，请检查网络或代理设置";
    }

    public void ReportHistory(bool canGoBack, bool canGoForward)
    {
        CanGoBack = canGoBack;
        CanGoForward = canGoForward;
    }

    /// <summary>命中本地 / 内网拦截时提示，不打开页面。</summary>
    public void ReportBlocked(Uri uri)
    {
        IsLoading = false;
        StatusText = "";
        Notice = "已阻止访问本地或内网地址：" + uri.Host;
    }

    /// <summary>Linux 端设置 WebKit 代理失败时的提示（不影响页面打开）。</summary>
    public void SetProxyWarning(string message)
    {
        Notice = "浏览器代理未生效：" + message;
    }

    private void Navigate(Uri uri)
    {
        if (BrowserProxy.IsBlocked(uri))
        {
            ReportBlocked(uri);
            return;
        }

        Notice = "";
        NavigateRequested?.Invoke(uri);
    }
}
