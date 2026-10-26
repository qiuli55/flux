using System.Collections.Generic;
using System.Windows.Input;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// 侧栏导航条目：一个可点击的目标（模式、工具或平台页面）。
/// 选中态由 <see cref="IsActive"/> 承载，这样无论鼠标点击还是键盘切换都只有一处真源。
/// </summary>
public sealed partial class NavItem(string key, string label, string group, ViewModelBase page, string icon)
    : ObservableObject
{
    public string Key { get; } = key;

    public string Label { get; } = label;

    /// <summary>面包屑前一段（web 端 VIEW_GROUP：核心 / 平台）。</summary>
    public string Group { get; } = group;

    public ViewModelBase Page { get; } = page;

    /// <summary>图标名，见 <see cref="IconPaths"/>。</summary>
    public string Icon { get; } = icon;

    /// <summary>图标几何，XAML 直接给 Path.Data 用。</summary>
    public Geometry IconGeometry { get; } = IconPaths.GetGeometry(icon);

    /// <summary>点击后交给主视图模型切换（命令由 MainWindowViewModel 注入）。</summary>
    public ICommand? ActivateCommand { get; init; }

    /// <summary>该条目当前是否为选中项（外壳据此画 2px 青绿指示条与高亮底色）。</summary>
    [ObservableProperty]
    private bool _isActive;

    /// <summary>侧栏收起（68px）时只画图标，不画文字。</summary>
    [ObservableProperty]
    private bool _isCompact;

    public override string ToString() => Label;
}

/// <summary>
/// 线条图标库：与 web 端 shell/Icon.tsx 的 PATHS 一一对应（24×24 viewBox，描边 1.8）。
/// 只搬外壳用得到的这些，避免在原生侧引入图标字体依赖。
/// </summary>
public static class IconPaths
{
    private static readonly Dictionary<string, string> Paths = new()
    {
        ["home"] = "M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z",
        ["grid"] = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z",
        ["files"] = "M4 4h6l2 2h8v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z",
        ["search"] = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4",
        ["git"] = "M12 3v18M7 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM7 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 8l-5 5-5-1",
        ["terminal"] = "M5 7l5 5-5 5M13 17h6",
        ["debug"] = "M12 3a7 7 0 0 0-7 7v3a7 7 0 0 0 14 0v-3a7 7 0 0 0-7-7zM5 12H3M21 12h-2M12 3V1M12 23v-2",
        ["puzzle"] = "M10 4a2 2 0 1 1 4 0v2h3a1 1 0 0 1 1 1v3h2a2 2 0 1 1 0 4h-2v3a1 1 0 0 1-1 1h-3v-2a2 2 0 1 0-4 0v2H6a1 1 0 0 1-1-1v-3H3a2 2 0 1 1 0-4h2V7a1 1 0 0 1 1-1h4z",
        ["bot"] = "M5 8h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM12 4v4M9 13h.01M15 13h.01M9 16h6",
        ["sparkles"] = "M12 4l1.6 4.4L18 10l-4.4 1.6L12 16l-1.6-4.4L6 10l4.4-1.6zM18 15l.8 2.2L21 18l-2.2.8L18 21l-.8-2.2L15 18l2.2-.8z",
        ["layers"] = "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5",
        ["settings"] = "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14 3h-4l-.6 2.7a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2L10 21h4l.6-2.7a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z",
        ["chevron"] = "M9 6l6 6-6 6",
        ["code"] = "M9 8l-4 4 4 4M15 8l4 4-4 4",
        ["folder"] = "M4 6h5l2 2h9v10H4z",
        ["plug"] = "M9 3v5M15 3v5M7 8h10v3a5 5 0 0 1-10 0zM12 16v5",
        ["package"] = "M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9",
        ["download"] = "M12 4v10M8 11l4 4 4-4M5 20h14",
        ["activity"] = "M3 12h4l2-6 4 12 2-6h6",
        ["credit"] = "M3 6h18v12H3zM3 10h18M6 15h4",
        ["refresh"] = "M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4",
        ["diff"] = "M6 4v16M18 4v16M9 8h6M12 5v6M9 17h6",
        ["check"] = "M5 13l4 4L19 7",
        ["user"] = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM5 20a7 7 0 0 1 14 0",
    };

    private const string Fallback = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z";

    private static readonly Dictionary<string, Geometry> Cache = new();

    /// <summary>取图标几何（带缓存，几何对象可跨控件复用）。</summary>
    public static Geometry GetGeometry(string name)
    {
        if (Cache.TryGetValue(name, out var cached))
        {
            return cached;
        }

        var data = Paths.TryGetValue(name, out var path) ? path : Fallback;
        var geometry = StreamGeometry.Parse(data);
        Cache[name] = geometry;
        return geometry;
    }
}
