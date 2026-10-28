using System;
using System.Runtime.InteropServices;

namespace Flux.Desktop.Services;

/// <summary>
/// Linux 下给 WebKitGTK / WPE 的网络会话设置代理。
/// Avalonia 的 Linux WebView 环境参数没有代理开关，只能拿到原生 WebKitWebView 后直接调 WebKit API。
/// 调用方必须保证在 GTK/GLib 线程上执行（见 BrowserView 的 GtkInteropHelper.RunOnGlibThread）。
/// </summary>
internal static class LinuxWebKitProxy
{
    private static readonly string[] GtkCandidates =
    {
        "libwebkit2gtk-4.1.so.0",
        "libwebkit2gtk-4.1.so",
        "libwebkit2gtk-4.0.so.37",
        "libwebkit2gtk-4.0.so",
    };

    private static readonly string[] WpeCandidates =
    {
        "libWPEWebKit-2.0.so.0",
        "libWPEWebKit-2.0.so",
    };

    private static bool _loaded;
    private static Api? _api;
    private static string _loadError = "WebKit 库未加载";

    /// <summary>把代理配置应用到指定 WebKitWebView 的网络会话。</summary>
    public static (bool Ok, string Error) Apply(nint webKitWebView, BrowserProxyConfig config)
    {
        if (webKitWebView == nint.Zero)
        {
            return (false, "WebView 尚未完成初始化");
        }

        if (!OperatingSystem.IsLinux())
        {
            return (false, "仅 Linux 需要走 WebKit 代理设置");
        }

        var api = EnsureLoaded();
        if (api is null)
        {
            return (false, _loadError);
        }

        var settings = nint.Zero;
        try
        {
            if (config.Mode == BrowserProxyMode.Custom && BrowserProxy.ToWebKitProxyUri(config) is { } proxyUri)
            {
                settings = api.CreateProxySettings(proxyUri, nint.Zero);
                if (settings == nint.Zero)
                {
                    return (false, "WebKit 拒绝了该代理地址：" + proxyUri);
                }
            }

            var mode = BrowserProxy.ToWebKitProxyMode(config);

            // webkitgtk-6.0（GTK4）才有 WebKitNetworkSession；webkit2gtk-4.x 没有这个导出
            if (api.GetNetworkSession is not null && api.SetSessionProxySettings is not null)
            {
                var session = api.GetNetworkSession(webKitWebView);
                if (session == nint.Zero && api.GetDefaultSession is not null)
                {
                    session = api.GetDefaultSession();
                }

                if (session != nint.Zero)
                {
                    api.SetSessionProxySettings(session, mode, settings);
                    return (true, "");
                }
            }

            // webkit2gtk-4.x 的主路径：WebView → WebsiteDataManager → 代理
            if (api.GetWebsiteDataManager is not null && api.SetDataManagerProxySettings is not null)
            {
                var manager = api.GetWebsiteDataManager(webKitWebView);
                if (manager != nint.Zero)
                {
                    api.SetDataManagerProxySettings(manager, mode, settings);
                    return (true, "");
                }
            }

            // 再老一点的 WebKitGTK 只有 WebContext 接口（2.40 起已废弃但仍可用）
            if (api.GetContext is not null && api.SetContextProxySettings is not null)
            {
                var context = api.GetContext(webKitWebView);
                if (context != nint.Zero)
                {
                    api.SetContextProxySettings(context, mode, settings);
                    return (true, "");
                }
            }

            return (false, "当前 WebKit 版本没有可用的代理接口");
        }
        catch (Exception ex)
        {
            return (false, "设置 WebKit 代理失败：" + ex.Message);
        }
        finally
        {
            if (settings != nint.Zero)
            {
                try
                {
                    api.FreeProxySettings(settings);
                }
                catch
                {
                    // 释放失败不影响代理已生效
                }
            }
        }
    }

    private static Api? EnsureLoaded()
    {
        if (_loaded)
        {
            return _api;
        }

        _loaded = true;

        var library = TryLoad(GtkCandidates) ?? TryLoad(WpeCandidates);
        if (library is null)
        {
            _loadError = "未找到 WebKit 运行库（webkit2gtk / WPEWebKit）";
            return null;
        }

        var createProxySettings = Bind<CreateProxySettings>(library.Value, "webkit_network_proxy_settings_new");
        // WebKitNetworkProxySettings 是 boxed 类型，必须用 WebKit 自己的 free；用 g_object_unref 会直接段错误
        var freeProxySettings = Bind<FreeProxySettings>(library.Value, "webkit_network_proxy_settings_free");
        if (createProxySettings is null || freeProxySettings is null)
        {
            _loadError = "WebKit 缺少代理设置导出（需要 webkit2gtk 2.30+）";
            return null;
        }

        // 逐个导出单独解析：不同 WebKit 版本提供的接口集合不一样，缺一个不该拖垮整条链路
        var api = new Api
        {
            GetNetworkSession = Bind<GetNetworkSession>(library.Value, "webkit_web_view_get_network_session"),
            GetDefaultSession = Bind<GetDefaultSession>(library.Value, "webkit_network_session_get_default"),
            SetSessionProxySettings = Bind<SetProxySettings>(library.Value, "webkit_network_session_set_proxy_settings"),
            GetWebsiteDataManager = Bind<GetWebsiteDataManager>(library.Value, "webkit_web_view_get_website_data_manager"),
            SetDataManagerProxySettings = Bind<SetProxySettings>(library.Value, "webkit_website_data_manager_set_network_proxy_settings"),
            GetContext = Bind<GetContext>(library.Value, "webkit_web_view_get_context"),
            SetContextProxySettings = Bind<SetProxySettings>(library.Value, "webkit_web_context_set_network_proxy_settings"),
            CreateProxySettings = createProxySettings,
            FreeProxySettings = freeProxySettings,
        };

        if (api.SetSessionProxySettings is null && api.SetDataManagerProxySettings is null
            && api.SetContextProxySettings is null)
        {
            _loadError = "WebKit 缺少代理相关导出（需要 webkit2gtk 2.30+）";
            return null;
        }

        _api = api;
        return _api;
    }

    private static nint? TryLoad(string[] candidates)
    {
        foreach (var name in candidates)
        {
            if (NativeLibrary.TryLoad(name, out var handle) && handle != nint.Zero)
            {
                return handle;
            }
        }

        return null;
    }

    private static T? Bind<T>(nint library, string entryPoint) where T : Delegate
    {
        if (!NativeLibrary.TryGetExport(library, entryPoint, out var address) || address == nint.Zero)
        {
            return null;
        }

        return Marshal.GetDelegateForFunctionPointer<T>(address);
    }

    private sealed class Api
    {
        public GetNetworkSession? GetNetworkSession { get; init; }

        public GetDefaultSession? GetDefaultSession { get; init; }

        public SetProxySettings? SetSessionProxySettings { get; init; }

        public GetWebsiteDataManager? GetWebsiteDataManager { get; init; }

        public SetProxySettings? SetDataManagerProxySettings { get; init; }

        public GetContext? GetContext { get; init; }

        public SetProxySettings? SetContextProxySettings { get; init; }

        public required CreateProxySettings CreateProxySettings { get; init; }

        public required FreeProxySettings FreeProxySettings { get; init; }
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint GetNetworkSession(nint webView);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint GetDefaultSession();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint GetWebsiteDataManager(nint webView);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint GetContext(nint webView);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate nint CreateProxySettings(
        [MarshalAs(UnmanagedType.LPUTF8Str)] string? defaultProxyUri,
        nint ignoreHosts);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void SetProxySettings(nint target, int mode, nint settings);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void FreeProxySettings(nint settings);
}
