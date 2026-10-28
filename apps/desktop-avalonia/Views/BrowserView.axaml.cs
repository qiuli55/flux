using System;
using System.IO;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Platform;
using Avalonia.Threading;
using Avalonia.VisualTree;
using Avalonia.X11.Interop;
using Flux.Desktop.Services;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

/// <summary>
/// 内置浏览器页：创建 NativeWebView、下发代理与导航拦截。
/// 视图每次切页都会被 ViewLocator 重新创建，代理改动后重进本页即可生效。
/// </summary>
public partial class BrowserView : UserControl
{
    private NativeWebView? _webView;
    private BrowserViewModel? _viewModel;

    public BrowserView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => BindViewModel();
        AttachedToVisualTree += (_, _) => TryCreateWebView();
        DetachedFromVisualTree += (_, _) => DisposeWebView();
    }

    private void BindViewModel()
    {
        if (_viewModel is not null)
        {
            _viewModel.NavigateRequested -= OnNavigateRequested;
            _viewModel.ActionRequested -= OnActionRequested;
        }

        _viewModel = DataContext as BrowserViewModel;

        if (_viewModel is not null)
        {
            _viewModel.NavigateRequested += OnNavigateRequested;
            _viewModel.ActionRequested += OnActionRequested;
        }

        TryCreateWebView();
    }

    private void TryCreateWebView()
    {
        if (_webView is not null || _viewModel is null || !this.IsAttachedToVisualTree())
        {
            return;
        }

        var vm = _viewModel;

        if (!IsEngineAvailable(out var engineMessage))
        {
            vm.SetEngineMissing(engineMessage);
            return;
        }

        // 每次建 WebView 都重读一次落盘偏好（设置页改完代理后重进本页即生效）
        vm.RefreshProxy();

        var webView = new NativeWebView();
        // 环境参数（示例：WebView2 的启动参数）必须在控件加入可视树之前订阅
        webView.EnvironmentRequested += OnEnvironmentRequested;
        webView.AdapterCreated += OnAdapterCreated;
        webView.NavigationStarted += OnNavigationStarted;
        webView.NavigationCompleted += OnNavigationCompleted;
        webView.NewWindowRequested += OnNewWindowRequested;

        _webView = webView;
        WebHost.Children.Add(webView);
        webView.Navigate(new Uri(BrowserProxy.HomeUrl));
    }

    /// <summary>探测本机浏览器引擎；缺失时不创建 WebView，直接显示安装提示。</summary>
    private static bool IsEngineAvailable(out string message)
    {
        if (OperatingSystem.IsWindows())
        {
            var webView2 = WebViewAdapterInfo.GetAdapterInfo(WebViewAdapterType.WebView2);
            if (webView2.IsInstalled)
            {
                message = "";
                return true;
            }

            message = "未检测到 Microsoft Edge WebView2 运行时。请安装 WebView2 Runtime 后重新启动 Flux。";
            return false;
        }

        if (OperatingSystem.IsLinux())
        {
            var gtk = WebViewAdapterInfo.GetAdapterInfo(WebViewAdapterType.WebKitGtk);
            if (gtk.IsInstalled)
            {
                message = "";
                return true;
            }

            var wpe = WebViewAdapterInfo.GetAdapterInfo(WebViewAdapterType.WpeWebKit);
            if (wpe.IsInstalled)
            {
                message = "";
                return true;
            }

            message = "未检测到 WebKitGTK。请在终端执行：sudo apt install libgtk-3-0 libwebkit2gtk-4.1-0 libsoup-3.0-0";
            return false;
        }

        message = "内置浏览器目前支持 Windows 与 Linux，macOS 版本稍后接入。";
        return false;
    }

    private void OnEnvironmentRequested(object? sender, WebViewEnvironmentRequestedEventArgs e)
    {
        var profile = Path.Combine(AppPaths.DataDir, "browser");
        var proxy = _viewModel?.Proxy ?? new BrowserProxyConfig(BrowserProxyMode.System, "");

        switch (e)
        {
            case LinuxWpeWebViewEnvironmentRequestedEventArgs wpe:
                // 强制走 GTK：WPE 分支拿不到可直接设置代理的原生 WebKitWebView
                wpe.PreferWebKitGtkInstead = true;
                wpe.DataDirectory = Path.Combine(profile, "wpe");
                wpe.CacheDirectory = Path.Combine(profile, "wpe-cache");
                break;

            case GtkWebViewEnvironmentRequestedEventArgs gtk:
                gtk.BaseDataDirectory = Path.Combine(profile, "gtk");
                gtk.BaseCacheDirectory = Path.Combine(profile, "gtk-cache");
                break;

            case WindowsWebView2EnvironmentRequestedEventArgs win:
                win.UserDataFolder = profile;
                if (BrowserProxy.ToWebView2Arguments(proxy) is { } arguments)
                {
                    win.AdditionalBrowserArguments = arguments;
                }

                break;
        }
    }

    /// <summary>Linux 没有环境参数可设代理，adapter 就绪后按需给 WebKit 网络会话改设置。</summary>
    private void OnAdapterCreated(object? sender, WebViewAdapterEventArgs e)
    {
        if (!OperatingSystem.IsLinux() || _viewModel is null)
        {
            return;
        }

        var pointer = _webView?.TryGetPlatformHandle() switch
        {
            IGtkWebViewPlatformHandle gtk => gtk.WebKitWebView,
            ILinuxWpePlatformHandle wpe => wpe.WebKitWebView,
            _ => nint.Zero,
        };

        if (pointer == nint.Zero)
        {
            return;
        }

        var vm = _viewModel;
        _ = ApplyLinuxProxyAsync(pointer, vm);
    }

    private static async Task ApplyLinuxProxyAsync(nint webKitWebView, BrowserViewModel vm)
    {
        try
        {
            var proxy = vm.Proxy;
            var result = await GtkInteropHelper
                .RunOnGlibThread(() => LinuxWebKitProxy.Apply(webKitWebView, proxy))
                .ConfigureAwait(false);

            if (!result.Ok)
            {
                Dispatcher.UIThread.Post(() => vm.SetProxyWarning(result.Error));
            }
        }
        catch (Exception ex)
        {
            Dispatcher.UIThread.Post(() => vm.SetProxyWarning(ex.Message));
        }
    }

    private void OnNavigationStarted(object? sender, WebViewNavigationStartingEventArgs e)
    {
        var request = e.Request;
        if (request is null)
        {
            return;
        }

        if (string.Equals(request.Scheme, "about", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        if (BrowserProxy.IsBlocked(request))
        {
            e.Cancel = true;
            _viewModel?.ReportBlocked(request);
            return;
        }

        _viewModel?.ReportStarted(request);
    }

    private void OnNavigationCompleted(object? sender, WebViewNavigationCompletedEventArgs e)
    {
        var vm = _viewModel;
        if (vm is null)
        {
            return;
        }

        vm.ReportCompleted(e.Request, e.IsSuccess);
        vm.ReportHistory(_webView?.CanGoBack ?? false, _webView?.CanGoForward ?? false);
    }

    private void OnNewWindowRequested(object? sender, WebViewNewWindowRequestedEventArgs e)
    {
        var request = e.Request;
        // 单视图策略：新窗口一律在当前视图里打开
        e.Handled = true;

        if (request is null || _webView is null)
        {
            return;
        }

        if (string.Equals(request.Scheme, "about", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        if (BrowserProxy.IsBlocked(request))
        {
            _viewModel?.ReportBlocked(request);
            return;
        }

        _webView.Navigate(request);
    }

    private void OnNavigateRequested(Uri uri) => _webView?.Navigate(uri);

    private void OnActionRequested(BrowserAction action)
    {
        var webView = _webView;
        if (webView is null)
        {
            return;
        }

        switch (action)
        {
            case BrowserAction.Back:
                webView.GoBack();
                break;
            case BrowserAction.Forward:
                webView.GoForward();
                break;
            case BrowserAction.Refresh:
                webView.Refresh();
                break;
            case BrowserAction.Stop:
                webView.Stop();
                break;
            case BrowserAction.Home:
                webView.Navigate(new Uri(BrowserProxy.HomeUrl));
                break;
        }
    }

    private void OnAddressKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
        {
            return;
        }

        e.Handled = true;
        _viewModel?.SubmitCommand.Execute(null);
    }

    private void DisposeWebView()
    {
        var webView = _webView;
        _webView = null;

        if (webView is not null)
        {
            webView.EnvironmentRequested -= OnEnvironmentRequested;
            webView.AdapterCreated -= OnAdapterCreated;
            webView.NavigationStarted -= OnNavigationStarted;
            webView.NavigationCompleted -= OnNavigationCompleted;
            webView.NewWindowRequested -= OnNewWindowRequested;
            WebHost.Children.Remove(webView);
        }

        if (_viewModel is not null)
        {
            _viewModel.NavigateRequested -= OnNavigateRequested;
            _viewModel.ActionRequested -= OnActionRequested;
        }
    }
}
