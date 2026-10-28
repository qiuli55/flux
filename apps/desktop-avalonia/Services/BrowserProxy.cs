using System;
using System.Globalization;
using System.Net;
using System.Net.Sockets;

namespace Flux.Desktop.Services;

/// <summary>内置浏览器的代理模式。只作用于浏览器引擎，不影响 FluxApiClient 与后端子进程。</summary>
public enum BrowserProxyMode
{
    /// <summary>跟随系统代理（Windows 交给 WebView2，Linux 交给 WebKit 的 DEFAULT）。</summary>
    System = 0,

    /// <summary>直连，不读系统代理。</summary>
    Direct = 1,

    /// <summary>自定义 http / socks5 代理。</summary>
    Custom = 2,
}

/// <summary>浏览器代理配置：<see cref="ProxyUrl"/> 只在 Custom 下有值，且已归一化为 scheme://host:port。</summary>
public sealed record BrowserProxyConfig(BrowserProxyMode Mode, string ProxyUrl);

/// <summary>
/// 内置浏览器的纯逻辑：地址解析、代理归一化、私网拦截、各平台代理参数。
/// 不碰 UI 也不碰原生句柄，方便单测。
/// </summary>
public static class BrowserProxy
{
    /// <summary>浏览器首页（国内可直连，避免首屏就因为网络打不开）。</summary>
    public const string HomeUrl = "https://www.bing.com";

    private const string SearchPrefix = "https://www.bing.com/search?q=";

    public static BrowserProxyMode ParseMode(string? text) => text?.Trim().ToLowerInvariant() switch
    {
        "direct" => BrowserProxyMode.Direct,
        "custom" => BrowserProxyMode.Custom,
        _ => BrowserProxyMode.System,
    };

    /// <summary>把落盘的偏好转成配置；自定义地址非法时回退跟随系统，并通过 error 说明原因。</summary>
    public static BrowserProxyConfig FromPrefs(string? mode, string? url, out string? error)
    {
        error = null;
        var parsed = ParseMode(mode);
        if (parsed != BrowserProxyMode.Custom)
        {
            return new BrowserProxyConfig(parsed, "");
        }

        if (TryNormalizeProxy(url, out var normalized, out var reason))
        {
            return new BrowserProxyConfig(BrowserProxyMode.Custom, normalized);
        }

        error = reason;
        return new BrowserProxyConfig(BrowserProxyMode.System, "");
    }

    /// <summary>代理地址归一化：允许省略 http://，只支持 http 与 socks5，且必须写端口。</summary>
    public static bool TryNormalizeProxy(string? input, out string normalized, out string error)
    {
        normalized = "";
        error = "";

        var text = (input ?? "").Trim();
        if (text.Length == 0)
        {
            error = "请填写代理地址，例如 127.0.0.1:7890";
            return false;
        }

        var scheme = "http";
        var split = text.IndexOf("://", StringComparison.Ordinal);
        if (split >= 0)
        {
            scheme = text[..split].ToLowerInvariant();
            text = text[(split + 3)..];
        }

        text = text.TrimEnd('/');
        if (scheme != "http" && scheme != "socks5")
        {
            error = "仅支持 http:// 或 socks5:// 代理";
            return false;
        }

        if (text.Length == 0 || text.IndexOfAny(new[] { '/', '?', '#' }) >= 0
            || text.IndexOfAny(new[] { ' ', '\t' }) >= 0)
        {
            error = "代理地址只需 host:port，不要带路径";
            return false;
        }

        string host;
        string portText;
        var lastColon = text.LastIndexOf(':');
        if (text.StartsWith('['))
        {
            var close = text.IndexOf(']');
            if (close < 0)
            {
                error = "IPv6 地址缺少 ]";
                return false;
            }

            host = text[..(close + 1)];
            if (lastColon <= close)
            {
                error = "请填写代理端口，例如 [::1]:7890";
                return false;
            }

            portText = text[(lastColon + 1)..];
        }
        else
        {
            if (lastColon <= 0)
            {
                error = "请填写代理端口，例如 127.0.0.1:7890";
                return false;
            }

            host = text[..lastColon];
            portText = text[(lastColon + 1)..];
        }

        if (host.Length == 0 || !int.TryParse(portText, NumberStyles.None, CultureInfo.InvariantCulture, out var port)
            || port is < 1 or > 65535)
        {
            error = "代理端口需在 1-65535 之间";
            return false;
        }

        normalized = scheme + "://" + host + ":" + port.ToString(CultureInfo.InvariantCulture);
        return true;
    }

    /// <summary>地址栏输入 → 目标 URL：完整 URL 直接放行；像域名/IP 的补协议；其余走搜索引擎。</summary>
    public static bool TryResolveAddress(string? input, out Uri address)
    {
        address = null!;
        var text = (input ?? "").Trim();
        if (text.Length == 0)
        {
            return false;
        }

        if (text.Contains("://", StringComparison.Ordinal))
        {
            return Uri.TryCreate(text, UriKind.Absolute, out var absolute)
                && IsHttpScheme(absolute)
                && Assign(absolute, out address);
        }

        if (text.IndexOfAny(new[] { ' ', '\t' }) < 0 && LooksLikeHost(text, out var scheme))
        {
            var candidate = scheme + "://" + text;
            return Uri.TryCreate(candidate, UriKind.Absolute, out var absolute)
                && Assign(absolute, out address);
        }

        address = new Uri(SearchPrefix + Uri.EscapeDataString(text));
        return true;
    }

    /// <summary>
    /// 安全策略：只允许 http(s)，且禁止访问回环 / 私网 / 链路本地 / 内网主机名，
    /// 避免内置浏览器被网页当成访问 127.0.0.1:8801 后端的跳板。
    /// </summary>
    public static bool IsBlocked(Uri uri)
    {
        if (!uri.IsAbsoluteUri || !IsHttpScheme(uri))
        {
            return true;
        }

        var host = uri.Host.Trim('[', ']').TrimEnd('.');
        if (host.Length == 0)
        {
            return true;
        }

        if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
            || host.EndsWith(".localhost", StringComparison.OrdinalIgnoreCase)
            || host.EndsWith(".local", StringComparison.OrdinalIgnoreCase)
            || host.EndsWith(".internal", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        if (IPAddress.TryParse(host, out var ip))
        {
            return IsBlockedAddress(ip);
        }

        // 不含点号的主机名通常是内网机器名（router / nas / mypc），一律拦掉
        return host.IndexOf('.') < 0;
    }

    /// <summary>WebView2 的启动参数；返回 null 表示不干预（跟随系统）。</summary>
    public static string? ToWebView2Arguments(BrowserProxyConfig config) => config.Mode switch
    {
        BrowserProxyMode.Direct => "--no-proxy-server",
        BrowserProxyMode.Custom => "--proxy-server=" + config.ProxyUrl,
        _ => null,
    };

    /// <summary>WebKit 的代理模式：0 = 跟随系统，1 = 直连，2 = 自定义。</summary>
    public static int ToWebKitProxyMode(BrowserProxyConfig config) => (int)config.Mode;

    /// <summary>WebKit 的代理 URI；WebKit 只认 http:// 与 socks://。</summary>
    public static string? ToWebKitProxyUri(BrowserProxyConfig config)
    {
        if (config.Mode != BrowserProxyMode.Custom || config.ProxyUrl.Length == 0)
        {
            return null;
        }

        const string socks5 = "socks5://";
        return config.ProxyUrl.StartsWith(socks5, StringComparison.OrdinalIgnoreCase)
            ? "socks://" + config.ProxyUrl[socks5.Length..]
            : config.ProxyUrl;
    }

    /// <summary>给界面显示的一句话说明。</summary>
    public static string Describe(BrowserProxyConfig config) => config.Mode switch
    {
        BrowserProxyMode.Direct => "直连 · 不使用代理",
        BrowserProxyMode.Custom => "自定义 · " + config.ProxyUrl,
        _ => "跟随系统代理",
    };

    private static bool IsHttpScheme(Uri uri) =>
        uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps;

    private static bool Assign(Uri value, out Uri address)
    {
        address = value;
        return true;
    }

    private static bool LooksLikeHost(string text, out string scheme)
    {
        scheme = "https";

        var end = text.IndexOfAny(new[] { '/', '?', '#' });
        var authority = end < 0 ? text : text[..end];
        if (authority.Length == 0)
        {
            return false;
        }

        var host = authority;
        var at = host.LastIndexOf('@');
        if (at >= 0)
        {
            host = host[(at + 1)..];
        }

        if (!host.StartsWith('['))
        {
            var colon = host.LastIndexOf(':');
            if (colon >= 0)
            {
                host = host[..colon];
            }
        }

        if (host.Length == 0)
        {
            return false;
        }

        if (IPAddress.TryParse(host.Trim('[', ']'), out _))
        {
            scheme = "http";
            return true;
        }

        if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
            || host.EndsWith(".local", StringComparison.OrdinalIgnoreCase))
        {
            scheme = "http";
            return true;
        }

        return host.IndexOf('.') > 0 && host.IndexOf('.') < host.Length - 1;
    }

    private static bool IsBlockedAddress(IPAddress ip)
    {
        if (IPAddress.IsLoopback(ip))
        {
            return true;
        }

        if (ip.IsIPv4MappedToIPv6)
        {
            return IsBlockedAddress(ip.MapToIPv4());
        }

        switch (ip.AddressFamily)
        {
            case AddressFamily.InterNetwork:
            {
                var b = ip.GetAddressBytes();
                return b[0] == 0
                    || b[0] == 10
                    || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                    || (b[0] == 192 && b[1] == 168)
                    || (b[0] == 169 && b[1] == 254)
                    || b[0] >= 224;
            }
            case AddressFamily.InterNetworkV6:
            {
                var b = ip.GetAddressBytes();
                var linkLocal = b[0] == 0xFE && (b[1] & 0xC0) == 0x80;
                var uniqueLocal = (b[0] & 0xFE) == 0xFC;
                return linkLocal || uniqueLocal || ip.IsIPv6Multicast;
            }
            default:
                return true;
        }
    }
}
