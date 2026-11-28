using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Media.Immutable;
using Avalonia.Media.TextFormatting;
using Avalonia.Platform;
using Flux.Desktop.Models;

namespace Flux.Desktop.Views;

/// <summary>
/// 设计稿画布：桌面端预览 + 编辑。
///
/// 渲染规则与移动端 <c>DesignSpec.kt</c> / <c>DesignPainter.kt</c>、后端
/// <c>backend/flux/core/design_render.py</c> 完全一致：默认圆角、默认填充、文本排版、
/// 导航 / 开关 / 滑杆几何都按同一份契约来，三端看同一份 JSON 必须得到同一张图。
/// 想"调好看"请先改契约，不要只改这里。
///
/// <see cref="IsEditing"/> 打开时进入编辑态：滚轮绕指针缩放、空格 / 中键平移、
/// 点选 / 框选、8 向手柄 + 旋转、吸附参考线、Ctrl+Z / Ctrl+Shift+Z 撤销重做。
/// 关闭时保留原来的可交互预览（按钮切屏 / 滑杆 / 开关 / 导航）。
/// </summary>
public sealed class DesignCanvas : Control
{
    // 设计稿里的颜色都是固定色，不跟随 App 深浅色主题（否则网页预览与桌面端会长得不一样）。
    private static readonly Color Accent = Color.Parse("#0891B2");
    private static readonly Color Ink = Color.Parse("#0F172A");
    private static readonly Color ShellTop = Color.Parse("#F4F6F9");
    private static readonly Color SwitchOff = Color.Parse("#E2E8F0");
    private static readonly Color InputFill = Color.Parse("#F8FAFC");

    private const double DeviceRadius = 26;
    private const double DefaultFontSize = 13;
    private const double DefaultLineHeight = 1.35;
    private const double DefaultNavFontSize = 10.5;
    private const double DefaultInputPad = 12;
    private const int MaxTextLines = 3;
    private const int TextLinesLimit = 64;

    /// <summary>autoFit 下限与步长，对齐网页 `fitTexts()`（最低 60%、每次 0.5px）。</summary>
    private const double AutoFitMinScale = .6;
    private const double AutoFitStep = .5;

    private const double CardShadowOffset = 6;
    private const double CardShadowAlpha = .05;
    private const double InputPlaceholderAlpha = .5;

    /// <summary>阴影缺省色：`rgba(0,0,0,.25)`，对齐后端 `DEFAULT_SHADOW` / 手机 `DEFAULT_SHADOW`。</summary>
    private static readonly Color DefaultShadow = Color.FromArgb(64, 0, 0, 0);

    /// <summary>会裁掉溢出子节点的类型，对齐后端编译层 `CLIP_TYPES`。</summary>
    private static readonly HashSet<string> ClipTypes = new(StringComparer.Ordinal)
    {
        "text", "button", "pill", "input", "nav", "badge", "chip", "avatar",
    };

    /// <summary>文字强制居中的类型，对齐手机 `CENTER_TEXT_TYPES` 与网页 `.t-*` 样式。</summary>
    private static readonly HashSet<string> CenterTextTypes = new(StringComparer.Ordinal)
    {
        "button", "pill", "nav", "badge", "chip", "avatar",
    };

    /// <summary>描边单边选择里认得的边名。</summary>
    private static readonly HashSet<string> StrokeSides = new(StringComparer.Ordinal)
    {
        "top", "right", "bottom", "left",
    };

    /// <summary>演示态里可点/可拖的类型，对齐手机 `DesignDemoPreview`。</summary>
    private static readonly HashSet<string> InteractiveTypes = new(StringComparer.Ordinal)
    {
        "button", "pill", "switch", "slider", "nav",
    };

    /// <summary>画板名归一：去掉标点与空白，只留字母数字（含中文）。</summary>
    private static readonly Regex NormRe = new(@"[^\p{L}\p{N}]", RegexOptions.Compiled);

    private const int TextCacheLimit = 400;
    private const int MeasureCacheLimit = 800;

    /// <summary>文字排版缓存：键是影响排版的全部输入，命中后直接复用 TextLayout。</summary>
    private static readonly Dictionary<TextKey, TextEntry> TextCache = new();

    /// <summary>autoFit 量宽缓存（同一文本会按 0.5 步长反复量，命中率很高）。</summary>
    private static readonly Dictionary<MeasureKey, double> MeasureCache = new();

    /// <summary>
    /// 字族枚举 → 候选字体名，与后端 `design_render._FONT_STACKS` 的栈顺序一致：
    /// 三端都取「本机装了的第一个候选」，因此在同一台机器上落点相同。
    /// </summary>
    private static readonly Dictionary<string, string[]> FontStacks = new(StringComparer.Ordinal)
    {
        ["system"] = new[]
        {
            "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", "Arial",
        },
        ["noto-sans-sc"] = new[]
        {
            "Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "Microsoft YaHei",
        },
        ["noto-serif-sc"] = new[]
        {
            "Noto Serif SC", "Noto Serif CJK SC", "Source Han Serif SC", "Songti SC", "SimSun",
        },
        ["inter"] = new[] { "Inter", "Helvetica Neue", "Arial" },
        ["mono"] = new[] { "JetBrains Mono", "SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono" },
    };

    /// <summary>别名归一，与后端 `design_compile.font_family` 逐条一致。</summary>
    private static readonly Dictionary<string, string> FamilyAliases = new(StringComparer.Ordinal)
    {
        ["system"] = "system",
        [""] = "system",
        ["sans"] = "system",
        ["sans-serif"] = "system",
        ["noto-sans"] = "noto-sans-sc",
        ["noto-sans-sc"] = "noto-sans-sc",
        ["source-han-sans"] = "noto-sans-sc",
        ["serif"] = "noto-serif-sc",
        ["noto-serif"] = "noto-serif-sc",
        ["noto-serif-sc"] = "noto-serif-sc",
        ["source-han-serif"] = "noto-serif-sc",
        ["inter"] = "inter",
        ["mono"] = "mono",
        ["jetbrains-mono"] = "mono",
        ["monospace"] = "mono",
    };

    private static readonly Dictionary<string, FontFamily> FamilyCache = new(StringComparer.Ordinal);

    private static readonly Regex HexRe = new(
        @"^#([0-9a-fA-F]{3,8})$",
        RegexOptions.Compiled);

    private static readonly Regex RgbRe = new(
        @"^rgba?\(([^)]*)\)$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex HslRe = new(
        @"^hsla?\(([^)]*)\)$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex TokenRe = new(
        @"#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\)",
        RegexOptions.Compiled);

    private static readonly Regex ArgSplitRe = new(@"[,\s/]+", RegexOptions.Compiled);

    private static readonly Regex GradientRe = new(
        @"^(linear|radial)-gradient\((.*)\)$",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.Compiled);

    private static readonly Regex AngleRe = new(
        @"^(-?\d+(?:\.\d+)?)(deg|turn|rad)?$",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex OffsetRe = new(
        @"^(-?\d+(?:\.\d+)?)%$",
        RegexOptions.Compiled);

    private static readonly Regex RadialHeadRe = new(
        @"^(circle|ellipse|closest|farthest)",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>方向词 → CSS 角度（0deg 向上、90deg 向右、顺时针）。</summary>
    private static readonly Dictionary<string, double> DirectionAngles = new(StringComparer.Ordinal)
    {
        ["to top"] = 0,
        ["to top right"] = 45,
        ["to right"] = 90,
        ["to bottom right"] = 135,
        ["to bottom"] = 180,
        ["to bottom left"] = 225,
        ["to left"] = 270,
        ["to top left"] = 315,
    };

    /// <summary>径向圆心位置关键字。</summary>
    private static readonly Dictionary<string, double> PositionKeywords = new(StringComparer.Ordinal)
    {
        ["left"] = 0,
        ["top"] = 0,
        ["center"] = .5,
        ["right"] = 1,
        ["bottom"] = 1,
    };

    /// <summary>
    /// CSS 标准具名色（含 transparent），与后端 `design_tokens.NAMED_COLORS` 完全一致。
    /// 用「名字=rrggbb」紧凑串保存，避免 150 行字典字面量出现抄写错误。
    /// </summary>
    private const string NamedSource = """

        aliceblue=f0f8ff,antiquewhite=faebd7,aqua=00ffff,aquamarine=7fffd4,azure=f0ffff,beige=f5f5dc,
        bisque=ffe4c4,black=000000,blanchedalmond=ffebcd,blue=0000ff,blueviolet=8a2be2,brown=a52a2a,
        burlywood=deb887,cadetblue=5f9ea0,chartreuse=7fff00,chocolate=d2691e,coral=ff7f50,
        cornflowerblue=6495ed,cornsilk=fff8dc,crimson=dc143c,cyan=00ffff,darkblue=00008b,
        darkcyan=008b8b,darkgoldenrod=b8860b,darkgray=a9a9a9,darkgreen=006400,darkgrey=a9a9a9,
        darkkhaki=bdb76b,darkmagenta=8b008b,darkolivegreen=556b2f,darkorange=ff8c00,
        darkorchid=9932cc,darkred=8b0000,darksalmon=e9967a,darkseagreen=8fbc8f,
        darkslateblue=483d8b,darkslategray=2f4f4f,darkslategrey=2f4f4f,darkturquoise=00ced1,
        darkviolet=9400d3,deeppink=ff1493,deepskyblue=00bfff,dimgray=696969,dimgrey=696969,
        dodgerblue=1e90ff,firebrick=b22222,floralwhite=fffaf0,forestgreen=228b22,fuchsia=ff00ff,
        gainsboro=dcdcdc,ghostwhite=f8f8ff,gold=ffd700,goldenrod=daa520,gray=808080,green=008000,
        greenyellow=adff2f,grey=808080,honeydew=f0fff0,hotpink=ff69b4,indianred=cd5c5c,
        indigo=4b0082,ivory=fffff0,khaki=f0e68c,lavender=e6e6fa,lavenderblush=fff0f5,
        lawngreen=7cfc00,lemonchiffon=fffacd,lightblue=add8e6,lightcoral=f08080,lightcyan=e0ffff,
        lightgoldenrodyellow=fafad2,lightgray=d3d3d3,lightgreen=90ee90,lightgrey=d3d3d3,
        lightpink=ffb6c1,lightsalmon=ffa07a,lightseagreen=20b2aa,lightskyblue=87cefa,
        lightslategray=778899,lightslategrey=778899,lightsteelblue=b0c4de,lightyellow=ffffe0,
        lime=00ff00,limegreen=32cd32,linen=faf0e6,magenta=ff00ff,maroon=800000,
        mediumaquamarine=66cdaa,mediumblue=0000cd,mediumorchid=ba55d3,mediumpurple=9370db,
        mediumseagreen=3cb371,mediumslateblue=7b68ee,mediumspringgreen=00fa9a,
        mediumturquoise=48d1cc,mediumvioletred=c71585,midnightblue=191970,mintcream=f5fffa,
        mistyrose=ffe4e1,moccasin=ffe4b5,navajowhite=ffdead,navy=000080,oldlace=fdf5e6,
        olive=808000,olivedrab=6b8e23,orange=ffa500,orangered=ff4500,orchid=da70d6,
        palegoldenrod=eee8aa,palegreen=98fb98,paleturquoise=afeeee,palevioletred=db7093,
        papayawhip=ffefd5,peachpuff=ffdab9,peru=cd853f,pink=ffc0cb,plum=dda0dd,
        powderblue=b0e0e6,purple=800080,rebeccapurple=663399,red=ff0000,rosybrown=bc8f8f,
        royalblue=4169e1,saddlebrown=8b4513,salmon=fa8072,sandybrown=f4a460,seagreen=2e8b57,
        seashell=fff5ee,sienna=a0522d,silver=c0c0c0,skyblue=87ceeb,slateblue=6a5acd,
        slategray=708090,slategrey=708090,snow=fffafa,springgreen=00ff7f,steelblue=4682b4,
        tan=d2b48c,teal=008080,thistle=d8bfd8,tomato=ff6347,turquoise=40e0d0,violet=ee82ee,
        wheat=f5deb3,white=ffffff,whitesmoke=f5f5f5,yellow=ffff00,yellowgreen=9acd32,
        transparent=00000000

        """;

    private static readonly Dictionary<string, string> Named =
        string.Concat(NamedSource.Where(ch => !char.IsWhiteSpace(ch)))
            .Split(',', StringSplitOptions.RemoveEmptyEntries)
            .Select(item => item.Split('='))
            .Where(pair => pair.Length == 2)
            .ToDictionary(pair => pair[0], pair => "#" + pair[1], StringComparer.Ordinal);

    public static readonly StyledProperty<DesignDocument?> DocumentProperty =
        AvaloniaProperty.Register<DesignCanvas, DesignDocument?>(nameof(Document));

    public static readonly StyledProperty<int> ScreenIndexProperty =
        AvaloniaProperty.Register<DesignCanvas, int>(nameof(ScreenIndex));

    public static readonly StyledProperty<DesignPreview?> PreviewProperty =
        AvaloniaProperty.Register<DesignCanvas, DesignPreview?>(nameof(Preview));

    public static readonly StyledProperty<bool> IsThumbnailProperty =
        AvaloniaProperty.Register<DesignCanvas, bool>(nameof(IsThumbnail));

    /// <summary>AI 预览稿：非空时画它而不是已保存的 Document，确认前不写回真源。</summary>
    public static readonly StyledProperty<DesignDocument?> PreviewDocumentProperty =
        AvaloniaProperty.Register<DesignCanvas, DesignDocument?>(nameof(PreviewDocument));

    /// <summary>AI 变更高亮（画板绝对坐标），与预览稿一起显示。</summary>
    public static readonly StyledProperty<IReadOnlyList<DesignHighlight>?> HighlightsProperty =
        AvaloniaProperty.Register<DesignCanvas, IReadOnlyList<DesignHighlight>?>(nameof(Highlights));

    /// <summary>AI 请求进行中：在画布上圈出作用范围，而不是只有按钮转圈。</summary>
    public static readonly StyledProperty<bool> IsAiBusyProperty =
        AvaloniaProperty.Register<DesignCanvas, bool>(nameof(IsAiBusy));

    /// <summary>编辑态开关：关闭时是可交互预览，打开时是可选 / 可拖 / 可缩放的设计画布。</summary>
    public static readonly StyledProperty<bool> IsEditingProperty =
        AvaloniaProperty.Register<DesignCanvas, bool>(nameof(IsEditing));

    static DesignCanvas()
    {
        AffectsRender<DesignCanvas>(
            DocumentProperty,
            ScreenIndexProperty,
            PreviewProperty,
            IsThumbnailProperty,
            PreviewDocumentProperty,
            HighlightsProperty,
            IsAiBusyProperty,
            IsEditingProperty);
    }

    public DesignCanvas()
    {
        Focusable = true;
        ClipToBounds = true;
    }

    public DesignDocument? Document
    {
        get => GetValue(DocumentProperty);
        set => SetValue(DocumentProperty, value);
    }

    public int ScreenIndex
    {
        get => GetValue(ScreenIndexProperty);
        set => SetValue(ScreenIndexProperty, value);
    }

    public DesignPreview? Preview
    {
        get => GetValue(PreviewProperty);
        set => SetValue(PreviewProperty, value);
    }

    public bool IsThumbnail
    {
        get => GetValue(IsThumbnailProperty);
        set => SetValue(IsThumbnailProperty, value);
    }

    public DesignDocument? PreviewDocument
    {
        get => GetValue(PreviewDocumentProperty);
        set => SetValue(PreviewDocumentProperty, value);
    }

    public IReadOnlyList<DesignHighlight>? Highlights
    {
        get => GetValue(HighlightsProperty);
        set => SetValue(HighlightsProperty, value);
    }

    public bool IsAiBusy
    {
        get => GetValue(IsAiBusyProperty);
        set => SetValue(IsAiBusyProperty, value);
    }

    public bool IsEditing
    {
        get => GetValue(IsEditingProperty);
        set => SetValue(IsEditingProperty, value);
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var width = double.IsInfinity(availableSize.Width) ? 320 : availableSize.Width;
        var height = double.IsInfinity(availableSize.Height) ? 640 : availableSize.Height;
        return new Size(width, height);
    }

    public override void Render(DrawingContext context)
    {
        var bounds = Bounds;
        if (bounds.Width <= 0 || bounds.Height <= 0 || ProbeLayout(bounds) is not { } frame)
        {
            return;
        }

        var bg = SingleColor(frame.Bg, ShellTop);
        // 编辑态用真实节点（不做演示态的滑杆 / 开关替换）；预览态保留可交互效果。
        var nodes = IsEditing ? frame.Nodes : ApplyDemoState(frame.Nodes);

        using (context.PushTransform(Matrix.CreateScale(frame.Scale, frame.Scale) * Matrix.CreateTranslation(frame.OffsetX, frame.OffsetY)))
        {
            if (!IsThumbnail)
            {
                DrawDeviceShadow(context, frame.Width, frame.Height, DeviceRadius);
            }

            using (context.PushClip(new RoundedRect(new Rect(0, 0, frame.Width, frame.Height), DeviceRadius)))
            {
                context.FillRectangle(new ImmutableSolidColorBrush(bg), new Rect(0, 0, frame.Width, frame.Height));
                if (IsThumbnail && nodes.Count == 0)
                {
                    DrawEmptyThumbnail(context, frame.Width, frame.Height);
                }
                else
                {
                    foreach (var node in nodes)
                    {
                        DrawNode(context, node, 0, 0, 1d, frame.Scale);
                    }
                }

                if (!IsThumbnail && Highlights is { Count: > 0 } highlights)
                {
                    DrawHighlights(context, highlights);
                }

                if (!IsThumbnail && IsAiBusy)
                {
                    DrawAiScope(context, frame.Width, frame.Height);
                }
            }

            // 选中框与参考线画在设备裁剪之外：贴边手柄不会被圆角切掉。
            if (Editable)
            {
                DrawEditingOverlay(context, frame, nodes);
            }
        }
    }

    /// <summary>编辑态叠加层：多选/单选框、8 向手柄 + 旋转柄、吸附参考线、框选矩形。</summary>
    private void DrawEditingOverlay(DrawingContext context, CanvasFrame frame, List<DesignNode> nodes)
    {
        if (frame.Scale <= 0) return;
        var scale = frame.Scale;
        var stroke = 1.5 / scale;
        var accent = new ImmutableSolidColorBrush(Accent);
        var accentPen = new Pen(accent, stroke);
        var flat = DesignCanvasInteract.Flatten(nodes);

        foreach (var id in _selectedIds)
        {
            var node = DesignCanvasInteract.FindById(flat, id);
            if (node is null) continue;
            var cx = node.CenterX;
            var cy = node.CenterY;
            var matrix = Matrix.Identity;
            if (Math.Abs(node.Rotation) > .01)
            {
                matrix = Matrix.CreateTranslation(-cx, -cy)
                    * Matrix.CreateRotation(node.Rotation * Math.PI / 180)
                    * Matrix.CreateTranslation(cx, cy);
            }

            using (context.PushTransform(matrix))
            {
                context.DrawRectangle(null, accentPen, new Rect(node.X, node.Y, node.W, node.H));
                if (_selectedIds.Count == 1)
                {
                    var rotateOffset = 26 / scale;
                    var radius = DesignCanvasInteract.HandleRadius(4.5 / scale, node.Frame);
                    var white = new ImmutableSolidColorBrush(Colors.White);
                    foreach (var (handle, point) in DesignCanvasInteract.HandlePositions(node, rotateOffset))
                    {
                        var drawRadius = handle == DesignCanvasInteract.Handle.Rotate ? radius * .8 : radius;
                        context.DrawEllipse(white, accentPen, point, drawRadius, drawRadius);
                    }
                    context.DrawLine(
                        new Pen(new ImmutableSolidColorBrush(Color.FromArgb(140, Accent.R, Accent.G, Accent.B)), stroke),
                        new Point(node.CenterX, node.Y),
                        new Point(node.CenterX, node.Y - rotateOffset));
                }
            }
        }

        var guideBrush = new ImmutableSolidColorBrush(Color.FromArgb(190, Accent.R, Accent.G, Accent.B));
        var guidePen = new Pen(guideBrush, 1 / scale);
        foreach (var x in _guides.Vertical)
        {
            context.DrawLine(guidePen, new Point(x, 0), new Point(x, frame.Height));
        }
        foreach (var y in _guides.Horizontal)
        {
            context.DrawLine(guidePen, new Point(0, y), new Point(frame.Width, y));
        }
        foreach (var gap in _guides.Gaps)
        {
            context.DrawLine(guidePen, gap.Start, gap.End);
            var horizontal = Math.Abs(gap.Start.Y - gap.End.Y) < .01;
            var tick = 3 / scale;
            if (horizontal)
            {
                context.DrawLine(guidePen, new Point(gap.Start.X, gap.Start.Y - tick), new Point(gap.Start.X, gap.Start.Y + tick));
                context.DrawLine(guidePen, new Point(gap.End.X, gap.End.Y - tick), new Point(gap.End.X, gap.End.Y + tick));
            }
            else
            {
                context.DrawLine(guidePen, new Point(gap.Start.X - tick, gap.Start.Y), new Point(gap.Start.X + tick, gap.Start.Y));
                context.DrawLine(guidePen, new Point(gap.End.X - tick, gap.End.Y), new Point(gap.End.X + tick, gap.End.Y));
            }
        }

        if (_marquee is { } marquee)
        {
            context.DrawRectangle(
                new ImmutableSolidColorBrush(Color.FromArgb(26, Accent.R, Accent.G, Accent.B)),
                new Pen(guideBrush, stroke),
                marquee);
        }
    }

    /// <summary>列表缩略图没有预览节点时的占位：两张错开的页面轮廓，与手机封面一致。</summary>
    private static void DrawEmptyThumbnail(DrawingContext context, double width, double height)
    {
        var color = Color.FromArgb(56, 0, 0, 0);
        var w = width * .38;
        var h = height * .28;
        var cx = width / 2;
        var cy = height / 2;
        context.DrawRectangle(
            new ImmutableSolidColorBrush(Color.FromArgb(20, 0, 0, 0)),
            null,
            new RoundedRect(new Rect(cx - w / 2 + 4, cy - h / 2 - 3, w, h), 4));
        context.DrawRectangle(
            new ImmutableSolidColorBrush(Color.FromArgb(32, 0, 0, 0)),
            new Pen(new ImmutableSolidColorBrush(color), 1),
            new RoundedRect(new Rect(cx - w / 2 - 4, cy - h / 2 + 3, w, h), 4));
    }

    /// <summary>AI 请求进行中：整屏虚线框 + 顶部浅色底，明确「AI 在看这一块」。</summary>
    private static void DrawAiScope(DrawingContext context, double width, double height)
    {
        var fill = new ImmutableSolidColorBrush(Color.FromArgb(12, Accent.R, Accent.G, Accent.B));
        var pen = new Pen(
            new ImmutableSolidColorBrush(Color.FromArgb(220, Accent.R, Accent.G, Accent.B)),
            2,
            new DashStyle(new double[] { 4, 3 }, 0));
        context.DrawRectangle(fill, pen, new Rect(0, 0, width, height), DeviceRadius, DeviceRadius);
    }

    /// <summary>AI 变更高亮：改 / 增 / 删 / 调层序各一种颜色，虚线框 + 半透明底。</summary>
    private static void DrawHighlights(DrawingContext context, IReadOnlyList<DesignHighlight> highlights)
    {
        foreach (var highlight in highlights)
        {
            if (highlight.W <= 0 || highlight.H <= 0)
            {
                continue;
            }

            var color = highlight.Kind switch
            {
                "added" => Color.Parse("#10B981"),
                "removed" => Color.Parse("#E11D48"),
                "reordered" => Color.Parse("#64748B"),
                _ => Accent,
            };
            var fill = new ImmutableSolidColorBrush(Color.FromArgb(26, color.R, color.G, color.B));
            var pen = new Pen(
                new ImmutableSolidColorBrush(color),
                2,
                new DashStyle(new double[] { 3, 2 }, 0));
            context.DrawRectangle(
                fill,
                pen,
                new Rect(highlight.X, highlight.Y, highlight.W, highlight.H),
                8,
                8);
        }
    }

    /// <summary>画板尺寸 → 屏幕变换。渲染与命中测试共用同一套缩放/居中换算，避免两处漂移。</summary>
    private readonly record struct CanvasFrame(
        double Width,
        double Height,
        List<DesignNode> Nodes,
        double Scale,
        double OffsetX,
        double OffsetY,
        string? Bg);

    private CanvasFrame? ProbeLayout(Rect bounds)
    {
        double width;
        double height;
        List<DesignNode> nodes;
        string? rawBg;
        var thumbnail = IsThumbnail;

        if (thumbnail)
        {
            width = Preview?.W is > 0 ? Preview!.W!.Value : 390;
            height = Preview?.H is > 0 ? Preview!.H!.Value : 844;
            nodes = Preview?.Nodes ?? new List<DesignNode>();
            rawBg = Preview?.Bg ?? Document?.Bg;
        }
        else
        {
            // AI 预览时画预览稿；确认前 Document（已保存内容）不变。
            var active = PreviewDocument ?? Document;
            var screens = active?.Screens;
            var index = ScreenIndex;
            if (screens is null || index < 0 || index >= screens.Count)
            {
                return null;
            }

            var screen = screens[index];
            width = screen.W is > 0 ? screen.W!.Value : 390;
            height = screen.H is > 0 ? screen.H!.Value : 844;
            nodes = screen.Nodes ?? new List<DesignNode>();
            // 与手机端 / 后端一致：画板底色只看文档级 bg，忽略 screen.bg。
            rawBg = active?.Bg;
        }

        if (width <= 0 || height <= 0)
        {
            return null;
        }

        var padding = thumbnail ? 0d : 24d;
        var availableW = Math.Max(bounds.Width - padding * 2, 1);
        var availableH = Math.Max(bounds.Height - padding * 2, 1);
        var zoom = thumbnail ? 1d : _zoom;
        var scale = Math.Min(availableW / width, availableH / height) * zoom;
        // 缩放绕指针 / 双指重心：origin 由「居中原点 + 用户平移」共同决定。
        var offsetX = (bounds.Width - width * scale) / 2 + (thumbnail ? 0 : _panX);
        var offsetY = (bounds.Height - height * scale) / 2 + (thumbnail ? 0 : _panY);
        return new CanvasFrame(width, height, nodes, scale, offsetX, offsetY, rawBg);
    }

    /// <summary>与 <see cref="ProbeLayout"/> 完全一致的相机，供手势与命中测试使用。</summary>
    private DesignCanvasInteract.DesignCamera Camera(Rect bounds, CanvasFrame frame) => new(
        bounds.Width,
        bounds.Height,
        frame.Width,
        frame.Height,
        _zoom,
        _panX,
        _panY);

    // ---------- 编辑态 ----------

    /// <summary>一次完整手势开始（拖 / 缩 / 转）时触发一次：宿主用它压撤销栈。</summary>
    public event EventHandler? EditStarted;

    /// <summary>手势结束：把选中节点的最终 X / Y / W / H / 旋转交回宿主写进真源。</summary>
    public event EventHandler<IReadOnlyList<DesignFrameChange>>? FramesCommitted;

    /// <summary>删除键：交出当前选中的图层 id。</summary>
    public event EventHandler<IReadOnlyList<string>>? DeleteRequested;

    /// <summary>选中集合变化：宿主用它刷新右侧「选中图层」面板。</summary>
    public event EventHandler<IReadOnlyList<string>>? SelectionChanged;

    public event EventHandler? UndoRequested;

    public event EventHandler? RedoRequested;

    private enum CanvasGesture { None, Pending, Move, Resize, Rotate, Pan, Marquee }

    private readonly HashSet<string> _selectedIds = new(StringComparer.Ordinal);
    private double _zoom = 1;
    private double _panX;
    private double _panY;
    private CanvasGesture _gesture = CanvasGesture.None;
    private Point _pressPoint;
    private Point _pressDesign;
    private Point _lastPoint;
    private DesignCanvasInteract.FlatNode? _pressNode;
    private DesignCanvasInteract.Handle? _pressHandle;
    private DesignCanvasInteract.Frame _pressFrame;
    private DesignCanvasInteract.DesignCamera _pressCamera;
    private List<DesignCanvasInteract.FlatNode> _pressFlat = new();
    private double _pressRotation;
    private double _rotationBase;
    private readonly List<DesignCanvasInteract.FlatNode> _dragNodes = new();
    private readonly Dictionary<string, (double X, double Y)> _dragStart = new(StringComparer.Ordinal);
    private readonly List<Rect> _snapTargets = new();
    private DesignCanvasInteract.SnapResult _guides = DesignCanvasInteract.SnapResult.None;
    private Rect? _marquee;
    private bool _spaceHeld;
    private bool _editStarted;
    private string? _lastDocumentId;

    private bool Editable =>
        IsEditing && !IsThumbnail && PreviewDocument is null && Document is not null;

    // ---------- 演示态交互 ----------

    private readonly Dictionary<string, double> _sliders = new(StringComparer.Ordinal);
    private readonly Dictionary<string, bool> _switches = new(StringComparer.Ordinal);
    private readonly Dictionary<string, int> _navs = new(StringComparer.Ordinal);
    private DesignNode? _dragSlider;
    private DesignNode? _pressedNode;
    private Point _pressedAt;
    private StandardCursorType _cursorType = StandardCursorType.Arrow;

    /// <summary>演示态点按钮/导航时请求切屏；由宿主页改 `ActiveScreenIndex`。</summary>
    public event EventHandler<int>? ScreenRequested;

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == DocumentProperty)
        {
            _sliders.Clear();
            _switches.Clear();
            _navs.Clear();
            _dragSlider = null;
            _pressedNode = null;
            // 只有切换到另一份设计稿才重置相机；自己提交的手势更新同 id 文档时要保持视口。
            var id = Document?.Id;
            if (!string.Equals(id, _lastDocumentId, StringComparison.Ordinal))
            {
                _zoom = 1;
                _panX = 0;
                _panY = 0;
                _selectedIds.Clear();
                _gesture = CanvasGesture.None;
                _guides = DesignCanvasInteract.SnapResult.None;
                _marquee = null;
            }
            _lastDocumentId = id;
            SyncSelection();
        }
        else if (change.Property == ScreenIndexProperty)
        {
            _zoom = 1;
            _panX = 0;
            _panY = 0;
            _selectedIds.Clear();
            _gesture = CanvasGesture.None;
            _guides = DesignCanvasInteract.SnapResult.None;
            _marquee = null;
            InvalidateVisual();
        }
        else if (change.Property == IsEditingProperty && !IsEditing)
        {
            _selectedIds.Clear();
            _gesture = CanvasGesture.None;
            _guides = DesignCanvasInteract.SnapResult.None;
            _marquee = null;
            NotifySelection();
            InvalidateVisual();
        }
        else if (change.Property == IsEditingProperty)
        {
            InvalidateVisual();
        }
    }

    /// <summary>文档被宿主替换后，丢掉已经不存在的选中项。</summary>
    private void SyncSelection()
    {
        if (ProbeLayout(Bounds) is not { } frame)
        {
            _selectedIds.Clear();
            return;
        }
        var flat = DesignCanvasInteract.Flatten(frame.Nodes);
        _selectedIds.RemoveWhere(id => DesignCanvasInteract.FindById(flat, id) is null);
        NotifySelection();
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (IsThumbnail || PreviewDocument is not null || Document is null || ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        var position = e.GetPosition(this);
        if (Editable)
        {
            PressEditing(e, frame, position);
            return;
        }

        // ---------- 预览态：点按钮 / 开关 / 滑杆 / 导航 ----------
        var point = DesignPoint(position, frame);
        if (HitTest(frame.Nodes, point, default, 1d) is not { } node ||
            !InteractiveTypes.Contains(TypeOf(node)))
        {
            return;
        }

        _pressedNode = node;
        _pressedAt = position;
        if (TypeOf(node) == "slider" && node.Id is { Length: > 0 } sliderId)
        {
            _dragSlider = node;
            _sliders[sliderId] = SliderValue(node, point.X);
            InvalidateVisual();
        }

        e.Pointer.Capture(this);
        e.Handled = true;
    }

    /// <summary>编辑态按下：中键 / 空格 = 平移，左键 = 选图层或抓手柄。</summary>
    private void PressEditing(PointerPressedEventArgs e, CanvasFrame frame, Point position)
    {
        Focus();
        var properties = e.GetCurrentPoint(this).Properties;
        var camera = Camera(Bounds, frame);
        _lastPoint = position;
        _pressPoint = position;
        _pressCamera = camera;
        _pressDesign = camera.ToDesign(position);
        _pressFlat = DesignCanvasInteract.Flatten(frame.Nodes);
        _marquee = null;
        _guides = DesignCanvasInteract.SnapResult.None;
        _editStarted = false;
        _dragNodes.Clear();

        if (properties.IsMiddleButtonPressed || _spaceHeld)
        {
            _gesture = CanvasGesture.Pan;
            _pressNode = null;
            _pressHandle = null;
            e.Pointer.Capture(this);
            e.Handled = true;
            Cursor = new Cursor(StandardCursorType.SizeAll);
            return;
        }

        if (!properties.IsLeftButtonPressed)
        {
            return;
        }

        var single = _selectedIds.Count == 1
            ? DesignCanvasInteract.FindById(_pressFlat, _selectedIds.First())
            : null;
        var rotateOffset = 26 / Math.Max(camera.Scale, 1e-4);
        var hotRadius = single is null
            ? 0
            : DesignCanvasInteract.HandleRadius(22 / Math.Max(camera.Scale, 1e-4), single.Frame);
        var handle = single is null
            ? null
            : DesignCanvasInteract.HitHandle(single, _pressDesign, hotRadius, rotateOffset);
        var hit = DesignCanvasInteract.HitTest(_pressFlat, _pressDesign);
        _pressNode = hit;
        _pressHandle = handle;

        var shift = e.KeyModifiers.HasFlag(KeyModifiers.Shift);
        if (handle is null && hit is not null)
        {
            if (shift)
            {
                // Shift 点击：叠加 / 取消选中
                if (!_selectedIds.Remove(hit.Id)) _selectedIds.Add(hit.Id);
            }
            else if (!_selectedIds.Contains(hit.Id))
            {
                _selectedIds.Clear();
                _selectedIds.Add(hit.Id);
            }
        }
        else if (handle is null && hit is null && !shift)
        {
            _selectedIds.Clear();
        }

        if (single is not null)
        {
            _pressFrame = single.Frame;
            _pressRotation = single.Rotation;
        }
        else if (hit is not null)
        {
            _pressFrame = hit.Frame;
            _pressRotation = hit.Rotation;
        }
        else
        {
            _pressFrame = default;
            _pressRotation = 0;
        }

        _gesture = CanvasGesture.Pending;
        e.Pointer.Capture(this);
        e.Handled = true;
        NotifySelection();
        InvalidateVisual();
    }

    private void NotifySelection() =>
        SelectionChanged?.Invoke(this, _selectedIds.ToList());

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        if (IsThumbnail || PreviewDocument is not null || Document is null || ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        var position = e.GetPosition(this);
        if (Editable)
        {
            MoveEditing(e, frame, position);
            return;
        }

        var point = DesignPoint(position, frame);
        if (_dragSlider is { Id: { Length: > 0 } id } slider)
        {
            _sliders[id] = SliderValue(slider, point.X);
            InvalidateVisual();
            return;
        }

        UpdateCursor(HitTest(frame.Nodes, point, default, 1d));
    }

    private void MoveEditing(PointerEventArgs e, CanvasFrame frame, Point position)
    {
        var camera = _pressCamera.Scale > 0 ? _pressCamera : Camera(Bounds, frame);
        var scale = Math.Max(camera.Scale, 1e-4);
        var design = camera.ToDesign(position);
        var slop = 4d;
        var shift = e.KeyModifiers.HasFlag(KeyModifiers.Shift);

        switch (_gesture)
        {
            case CanvasGesture.Pending:
            {
                if (!DesignCanvasInteract.DragExceeded(_pressPoint, position, slop))
                {
                    return;
                }
                if (_pressHandle is { } handle &&
                    DesignCanvasInteract.FindById(_pressFlat, _selectedIds.FirstOrDefault() ?? "") is { } single)
                {
                    _dragNodes.Clear();
                    _dragNodes.Add(single);
                    _dragStart[single.Id] = (single.LocalX, single.LocalY);
                    _snapTargets.Clear();
                    _snapTargets.AddRange(SnapTargets(_pressFlat, _selectedIds));
                    _pressFrame = single.Frame;
                    _pressRotation = single.Rotation;
                    _rotationBase = single.Rotation;
                    _gesture = handle == DesignCanvasInteract.Handle.Rotate
                        ? CanvasGesture.Rotate
                        : CanvasGesture.Resize;
                    FireEditStarted();
                }
                else if (_selectedIds.Count > 0)
                {
                    CollectTopLevel(_pressFlat, _selectedIds, false, _dragNodes);
                    _dragStart.Clear();
                    foreach (var node in _dragNodes)
                    {
                        _dragStart[node.Id] = (node.LocalX, node.LocalY);
                    }
                    _snapTargets.Clear();
                    _snapTargets.AddRange(SnapTargets(_pressFlat, _selectedIds));
                    _gesture = CanvasGesture.Move;
                    FireEditStarted();
                }
                else
                {
                    _gesture = CanvasGesture.Marquee;
                }
                break;
            }
            case CanvasGesture.Pan:
            {
                _panX += position.X - _lastPoint.X;
                _panY += position.Y - _lastPoint.Y;
                _lastPoint = position;
                InvalidateVisual();
                return;
            }
            case CanvasGesture.Move:
            {
                if (_dragNodes.Count == 0) break;
                var minX = _dragNodes.Min(node => node.Frame.X);
                var maxX = _dragNodes.Max(node => node.Frame.Right);
                var minY = _dragNodes.Min(node => node.Frame.Y);
                var maxY = _dragNodes.Max(node => node.Frame.Bottom);
                var totalX = design.X - _pressDesign.X;
                var totalY = design.Y - _pressDesign.Y;
                totalX = Math.Clamp(totalX, -minX, Math.Max(frame.Width - maxX, -minX));
                totalY = Math.Clamp(totalY, -minY, Math.Max(frame.Height - maxY, -minY));

                var union = new Rect(minX + totalX, minY + totalY, maxX - minX, maxY - minY);
                var snap = DesignCanvasInteract.SnapMove(union, _snapTargets, 6 / scale);
                totalX = Math.Clamp(totalX + snap.Dx, -minX, Math.Max(frame.Width - maxX, -minX));
                totalY = Math.Clamp(totalY + snap.Dy, -minY, Math.Max(frame.Height - maxY, -minY));
                foreach (var node in _dragNodes)
                {
                    if (_dragStart.TryGetValue(node.Id, out var start))
                    {
                        node.Node.X = start.X + totalX;
                        node.Node.Y = start.Y + totalY;
                    }
                }
                _guides = snap;
                InvalidateVisual();
                break;
            }
            case CanvasGesture.Resize:
            {
                if (_dragNodes.Count != 1 ||
                    DesignCanvasInteract.FindById(_pressFlat, _dragNodes[0].Id) is not { } node)
                {
                    break;
                }
                var next = DesignCanvasInteract.ResizeFrame(
                    _pressFrame,
                    _pressRotation,
                    _pressHandle ?? DesignCanvasInteract.Handle.BottomRight,
                    design,
                    12 / scale,
                    shift);
                var snapped = DesignCanvasInteract.SnapResize(
                    next,
                    _pressRotation,
                    _pressHandle ?? DesignCanvasInteract.Handle.BottomRight,
                    _snapTargets,
                    6 / scale,
                    12 / scale);
                node.Node.X = snapped.Frame.X - node.OriginX;
                node.Node.Y = snapped.Frame.Y - node.OriginY;
                node.Node.W = snapped.Frame.W;
                node.Node.H = snapped.Frame.H;
                _guides = new DesignCanvasInteract.SnapResult(
                    0, 0, snapped.Vertical, snapped.Horizontal, Array.Empty<DesignCanvasInteract.GapGuide>());
                InvalidateVisual();
                break;
            }
            case CanvasGesture.Rotate:
            {
                if (_dragNodes.Count != 1 ||
                    DesignCanvasInteract.FindById(_pressFlat, _dragNodes[0].Id) is not { } node)
                {
                    break;
                }
                var center = new Point(_pressFrame.CenterX, _pressFrame.CenterY);
                var delta = DesignCanvasInteract.RotationDegrees(center, _pressDesign, design);
                var raw = _rotationBase + delta;
                // Shift = 强制 15° 档；否则靠近直角 / 15° 时吸附。
                node.Node.Rotation = shift
                    ? DesignCanvasInteract.ConstrainRotation(raw, 15)
                    : DesignCanvasInteract.SnapRotation(raw);
                InvalidateVisual();
                break;
            }
            case CanvasGesture.Marquee:
            {
                _marquee = new Rect(
                    Math.Min(_pressDesign.X, design.X),
                    Math.Min(_pressDesign.Y, design.Y),
                    Math.Abs(design.X - _pressDesign.X),
                    Math.Abs(design.Y - _pressDesign.Y));
                var ids = DesignCanvasInteract.MarqueeSelection(_pressFlat, _marquee.Value);
                _selectedIds.Clear();
                _selectedIds.UnionWith(ids);
                InvalidateVisual();
                break;
            }
        }

        _lastPoint = position;
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (Editable)
        {
            ReleaseEditing(e);
            return;
        }

        var pressed = _pressedNode;
        _pressedNode = null;
        _dragSlider = null;
        e.Pointer.Capture(null);

        if (pressed is null || IsThumbnail || PreviewDocument is not null || Document is null ||
            ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        // 按下与抬起相隔超过 8px 视为拖动，不触发点击（滑杆已在移动事件里跟手）。
        var position = e.GetPosition(this);
        if (Math.Abs(position.X - _pressedAt.X) > 8 || Math.Abs(position.Y - _pressedAt.Y) > 8)
        {
            return;
        }

        var point = DesignPoint(position, frame);
        switch (TypeOf(pressed))
        {
            case "button":
            case "pill":
                RequestNextScreen(pressed.Text ?? pressed.Name);
                break;

            case "switch" when pressed.Id is { Length: > 0 } id:
            {
                var current = _switches.TryGetValue(id, out var saved) ? saved : pressed.On == true;
                _switches[id] = !current;
                break;
            }

            case "nav":
                SetNav(pressed, point.X);
                return;
        }

        InvalidateVisual();
    }

    /// <summary>编辑态松手：一次手势只提交一次几何，宿主据此压一格撤销。</summary>
    private void ReleaseEditing(PointerReleasedEventArgs e)
    {
        // 先取快照并把手势标记清掉：Capture(null) 可能同步触发 CaptureLost。
        var changes = CollectFrameChanges();
        _gesture = CanvasGesture.None;
        e.Pointer.Capture(null);
        if (changes.Count > 0)
        {
            FramesCommitted?.Invoke(this, changes);
        }

        _dragNodes.Clear();
        _dragStart.Clear();
        _snapTargets.Clear();
        _guides = DesignCanvasInteract.SnapResult.None;
        _marquee = null;
        _lastPoint = default;
        Cursor = new Cursor(StandardCursorType.Arrow);
        NotifySelection();
        InvalidateVisual();
    }

    private void FireEditStarted()
    {
        if (_editStarted) return;
        _editStarted = true;
        EditStarted?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>只保留最上层的选中节点：父子同时选中时，父移动已经带着子一起走。</summary>
    private static void CollectTopLevel(
        IReadOnlyList<DesignCanvasInteract.FlatNode> nodes,
        HashSet<string> selected,
        bool ancestorSelected,
        List<DesignCanvasInteract.FlatNode> output)
    {
        foreach (var node in nodes)
        {
            var isSelected = selected.Contains(node.Id);
            if (isSelected && !ancestorSelected)
            {
                output.Add(node);
            }
            CollectTopLevel(node.Children, selected, ancestorSelected || isSelected, output);
        }
    }

    /// <summary>吸附目标：除选中节点及其子树之外的所有可见图层。</summary>
    private static IEnumerable<Rect> SnapTargets(
        IReadOnlyList<DesignCanvasInteract.FlatNode> nodes,
        HashSet<string> selected)
    {
        var excluded = new HashSet<string>(StringComparer.Ordinal);
        void Collect(DesignCanvasInteract.FlatNode node, bool underSelected)
        {
            var isSelected = selected.Contains(node.Id);
            if (underSelected || isSelected)
            {
                excluded.Add(node.Id);
            }
            foreach (var child in node.Children)
            {
                Collect(child, underSelected || isSelected);
            }
        }
        foreach (var node in nodes)
        {
            Collect(node, false);
        }
        return DesignCanvasInteract.FlattenAll(nodes)
            .Where(node => !excluded.Contains(node.Id) && node.Alpha > .05 && node.Type != "decor")
            .Select(node => node.Bounds);
    }

    /// <summary>手势进行中拿到 / 丢掉指针捕获时，几何仍要提交，不能把半截拖动留在画布上。</summary>
    private List<DesignFrameChange> CollectFrameChanges()
    {
        if (_gesture is not (CanvasGesture.Move or CanvasGesture.Resize or CanvasGesture.Rotate))
        {
            return new List<DesignFrameChange>();
        }
        return _dragNodes
            .Where(node => node.Id.Length > 0)
            .Select(node => new DesignFrameChange(
                node.Id,
                node.LocalX,
                node.LocalY,
                node.Node.W ?? node.W,
                node.Node.H ?? node.H,
                node.Rotation))
            .ToList();
    }

    protected override void OnPointerCaptureLost(PointerCaptureLostEventArgs e)
    {
        var changes = CollectFrameChanges();
        base.OnPointerCaptureLost(e);
        _dragSlider = null;
        _pressedNode = null;
        _gesture = CanvasGesture.None;
        _dragNodes.Clear();
        _snapTargets.Clear();
        _guides = DesignCanvasInteract.SnapResult.None;
        _marquee = null;
        if (changes.Count > 0)
        {
            FramesCommitted?.Invoke(this, changes);
        }
    }

    /// <summary>
    /// 滚轮平移（双指滚动），Ctrl / Cmd + 滚轮绕指针缩放。
    /// 触控板双指捏合在 Avalonia 里通常映射成 Ctrl+滚轮，正好落在缩放分支。
    /// </summary>
    protected override void OnPointerWheelChanged(PointerWheelEventArgs e)
    {
        base.OnPointerWheelChanged(e);
        if (IsThumbnail || ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        var zoomModifier = e.KeyModifiers.HasFlag(KeyModifiers.Control) ||
            e.KeyModifiers.HasFlag(KeyModifiers.Meta);
        if (Editable && !zoomModifier)
        {
            _panX += e.Delta.X * 42;
            _panY += e.Delta.Y * 42;
        }
        else
        {
            var camera = Camera(Bounds, frame);
            var next = camera.ZoomAround(e.GetPosition(this), Math.Pow(1.18, e.Delta.Y));
            _zoom = next.Zoom;
            _panX = next.PanX;
            _panY = next.PanY;
        }
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (!Editable) return;

        var ctrl = e.KeyModifiers.HasFlag(KeyModifiers.Control) ||
            e.KeyModifiers.HasFlag(KeyModifiers.Meta);
        var shift = e.KeyModifiers.HasFlag(KeyModifiers.Shift);
        switch (e.Key)
        {
            case Key.Z when ctrl:
                if (shift) RedoRequested?.Invoke(this, EventArgs.Empty);
                else UndoRequested?.Invoke(this, EventArgs.Empty);
                e.Handled = true;
                return;
            case Key.Y when ctrl:
                RedoRequested?.Invoke(this, EventArgs.Empty);
                e.Handled = true;
                return;
            case Key.Space:
                _spaceHeld = true;
                Cursor = new Cursor(StandardCursorType.SizeAll);
                e.Handled = true;
                return;
            case Key.Escape:
                _selectedIds.Clear();
                _guides = DesignCanvasInteract.SnapResult.None;
                _marquee = null;
                NotifySelection();
                InvalidateVisual();
                e.Handled = true;
                return;
            case Key.Delete:
            case Key.Back:
                if (_selectedIds.Count > 0)
                {
                    DeleteRequested?.Invoke(this, _selectedIds.ToList());
                    e.Handled = true;
                }
                return;
            case Key.D0 or Key.NumPad0 when ctrl:
                _zoom = 1;
                _panX = 0;
                _panY = 0;
                InvalidateVisual();
                e.Handled = true;
                return;
        }
    }

    protected override void OnKeyUp(KeyEventArgs e)
    {
        base.OnKeyUp(e);
        if (e.Key == Key.Space)
        {
            _spaceHeld = false;
            if (_gesture != CanvasGesture.Pan)
            {
                Cursor = new Cursor(StandardCursorType.Arrow);
            }
        }
    }

    protected override void OnPointerCaptureLost(PointerCaptureLostEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        _dragSlider = null;
        _pressedNode = null;
    }

    private static Point DesignPoint(Point point, CanvasFrame frame) =>
        new((point.X - frame.OffsetX) / frame.Scale, (point.Y - frame.OffsetY) / frame.Scale);

    /// <summary>与手机 `hitTestNode` 同口径：倒序、先子后父、跳过装饰与透明节点。</summary>
    private static DesignNode? HitTest(List<DesignNode>? nodes, Point point, Point origin, double parentAlpha)
    {
        if (nodes is null)
        {
            return null;
        }

        for (var i = nodes.Count - 1; i >= 0; i--)
        {
            var node = nodes[i];
            if (node.Hidden == true)
            {
                continue;
            }

            var at = new Point(origin.X + (node.X ?? 0), origin.Y + (node.Y ?? 0));
            var alpha = parentAlpha * Math.Clamp(node.Opacity ?? 1d, 0, 1);
            if (HitTest(node.Children, point, at, alpha) is { } child)
            {
                return child;
            }

            if (alpha <= .05 || TypeOf(node) == "decor")
            {
                continue;
            }

            var w = node.W ?? 0;
            var h = node.H ?? 0;
            if (w > 0 && h > 0 &&
                point.X >= at.X && point.X <= at.X + w &&
                point.Y >= at.Y && point.Y <= at.Y + h)
            {
                return node;
            }
        }

        return null;
    }

    private void UpdateCursor(DesignNode? node)
    {
        var hand = node is not null && InteractiveTypes.Contains(TypeOf(node));
        var type = hand ? StandardCursorType.Hand : StandardCursorType.Arrow;
        if (_cursorType == type)
        {
            return;
        }

        _cursorType = type;
        Cursor = new Cursor(type);
    }

    private static double SliderValue(DesignNode node, double designX) =>
        Math.Clamp((designX - (node.X ?? 0)) / Math.Max(node.W ?? 0, 1), 0, 1);

    private void SetNav(DesignNode node, double designX)
    {
        var items = NavItems(node);
        var cell = Math.Max((node.W ?? 0) / items.Count, 1);
        var active = Math.Clamp((int)Math.Round((designX - (node.X ?? 0)) / cell), 0, items.Count - 1);
        if (node.Id is { Length: > 0 } id)
        {
            _navs[id] = active;
        }

        RequestNextScreen(items[active]);
        InvalidateVisual();
    }

    private void RequestNextScreen(string? text)
    {
        var screens = Document?.Screens;
        if (screens is null || screens.Count == 0)
        {
            return;
        }

        if (DemoNextScreen(text, screens, ScreenIndex) is { } index && index != ScreenIndex)
        {
            ScreenRequested?.Invoke(this, index);
        }
    }

    /// <summary>与手机 `demoNextScreen` 一致：返回类文字回上一屏，命中画板名/尾名/ID 跳转，否则下一屏。</summary>
    private static int? DemoNextScreen(string? text, List<DesignScreen> screens, int current)
    {
        var raw = text?.Trim() ?? "";
        if (raw.Length > 0)
        {
            if (raw.Contains("返回", StringComparison.Ordinal) ||
                raw.Contains("取消", StringComparison.Ordinal) ||
                raw.Contains("关闭", StringComparison.Ordinal) ||
                raw.Contains("退出", StringComparison.Ordinal))
            {
                return current - 1 >= 0 ? current - 1 : null;
            }

            var norm = DemoNorm(raw);
            if (norm.Length > 0)
            {
                for (var i = 0; i < screens.Count; i++)
                {
                    if (i == current)
                    {
                        continue;
                    }

                    var name = screens[i].Name ?? "";
                    var whole = DemoNorm(name);
                    var tailEnd = name.IndexOf('·');
                    var tail = DemoNorm(tailEnd >= 0 ? name[(tailEnd + 1)..] : name);
                    var id = DemoNorm(screens[i].Id ?? "");
                    if ((tail.Length > 0 && (tail.Contains(norm, StringComparison.Ordinal) || norm.Contains(tail, StringComparison.Ordinal))) ||
                        id.Contains(norm, StringComparison.Ordinal) ||
                        whole.Contains(norm, StringComparison.Ordinal))
                    {
                        return i;
                    }
                }
            }
        }

        return current + 1 < screens.Count ? current + 1 : null;
    }

    private static string DemoNorm(string text) => NormRe.Replace(text.ToLowerInvariant(), "");

    /// <summary>把演示态（滑杆/开关/导航）叠回节点树；只复制受影响的节点。</summary>
    private List<DesignNode> ApplyDemoState(List<DesignNode> nodes)
    {
        if (_sliders.Count == 0 && _switches.Count == 0 && _navs.Count == 0)
        {
            return nodes;
        }

        var result = new List<DesignNode>(nodes.Count);
        foreach (var node in nodes)
        {
            result.Add(ApplyDemoNode(node));
        }

        return result;
    }

    private DesignNode ApplyDemoNode(DesignNode node)
    {
        DesignNode? copy = null;
        DesignNode Target() => copy ??= node.ShallowCopy();

        if (node.Id is { Length: > 0 } id)
        {
            switch (TypeOf(node))
            {
                case "slider" when _sliders.TryGetValue(id, out var value):
                    Target().Value = value;
                    break;
                case "switch" when _switches.TryGetValue(id, out var on):
                    Target().On = on;
                    break;
                case "nav" when _navs.TryGetValue(id, out var active):
                    Target().Active = active;
                    break;
            }
        }

        if (node.Children is { Count: > 0 } children)
        {
            List<DesignNode>? replaced = null;
            for (var i = 0; i < children.Count; i++)
            {
                var next = ApplyDemoNode(children[i]);
                if (!ReferenceEquals(next, children[i]))
                {
                    replaced ??= new List<DesignNode>(children);
                    replaced[i] = next;
                }
            }

            if (replaced is not null)
            {
                Target().Children = replaced;
            }
        }

        return copy ?? node;
    }

    /// <summary>
    /// 设备投影 `0 26px 60px rgba(0,0,0,.55)`：交给 Skia 的 BoxShadow 做真实高斯模糊。
    /// blur 半径与 CSS 同口径（标准差 = blur / 2），不再用扩张矩形叠层。
    /// </summary>
    private static void DrawDeviceShadow(DrawingContext context, double width, double height, double radius)
    {
        var shadow = new BoxShadow
        {
            OffsetX = 0,
            OffsetY = 26,
            Blur = 60,
            Spread = 0,
            Color = Color.FromArgb(140, 0, 0, 0),
            IsInset = false,
        };
        context.DrawRectangle(
            Brushes.Transparent,
            null,
            new RoundedRect(new Rect(0, 0, width, height), radius),
            new BoxShadows(shadow));
    }

    private static void DrawNode(
        DrawingContext context,
        DesignNode node,
        double originX,
        double originY,
        double parentAlpha,
        double scale,
        bool skipBlur = false)
    {
        if (node.Hidden == true)
        {
            return;
        }

        var opacity = Math.Clamp(node.Opacity ?? 1d, 0, 1);
        var alpha = parentAlpha * opacity;
        if (alpha <= 0.01)
        {
            return;
        }

        var w = node.W ?? 0;
        var h = node.H ?? 0;
        if (w <= 0 || h <= 0)
        {
            return;
        }

        var type = TypeOf(node);
        var x = originX + (node.X ?? 0);
        var y = originY + (node.Y ?? 0);
        var rotation = node.Rotation ?? 0;

        // 绕节点中心旋转，子节点跟着一起转（与 Web 的 transform:rotate / Compose rotate 一致）。
        var matrix = Matrix.Identity;
        if (Math.Abs(rotation) > 0.01)
        {
            var cx = x + w / 2;
            var cy = y + h / 2;
            matrix = Matrix.CreateTranslation(-cx, -cy)
                * Matrix.CreateRotation(rotation * Math.PI / 180)
                * Matrix.CreateTranslation(cx, cy);
        }

        // 节点级 `filter: blur()`：整棵子树离屏后做像素高斯，而不是给边缘描虚边。
        if (!skipBlur && node.Blur is > .0001)
        {
            DrawBlurredNode(context, node, originX, originY, scale);
            return;
        }

        using (context.PushOpacity(opacity))
        using (context.PushTransform(matrix))
        {
            DrawNodeContent(context, node, type, x, y, w, h, alpha, scale);
        }
    }

    /// <summary>节点自身 + 子层（不含本节点的透明度 / 模糊包装，供普通与离屏路径复用）。</summary>
    private static void DrawNodeContent(
        DrawingContext context,
        DesignNode node,
        string type,
        double x,
        double y,
        double w,
        double h,
        double alpha,
        double scale)
    {
        DrawNodeCore(context, node, type, x, y, w, h, scale);

        if (node.Children is not { Count: > 0 })
        {
            return;
        }

        if (ClipTypes.Contains(type))
        {
            // 网页里这类节点是 `overflow:hidden`，子层裁剪到父框圆角内。
            var corners = CornersOf(node, type, w, h);
            using (PushShapeClip(context, IsEllipse(type, w, h), corners, new Rect(x, y, w, h)))
            {
                foreach (var child in node.Children)
                {
                    DrawNode(context, child, x, y, alpha, scale);
                }
            }
        }
        else
        {
            foreach (var child in node.Children)
            {
                DrawNode(context, child, x, y, alpha, scale);
            }
        }
    }

    // ---------- 真实模糊 ----------

    /// <summary>离屏模糊位图缓存：几何不变时平移/缩放只重贴图，不重复做高斯。</summary>
    private static readonly Dictionary<string, WriteableBitmap> BlurCache = new(StringComparer.Ordinal);

    /// <summary>
    /// 节点级 `filter: blur()`：整棵子树画进 RenderTargetBitmap，做 3 遍盒式模糊
    /// （近似高斯，σ = blur），再按原位置贴回。旋转与自身透明度一并烘进位图。
    /// </summary>
    private static void DrawBlurredNode(
        DrawingContext context,
        DesignNode node,
        double originX,
        double originY,
        double scale)
    {
        var x = originX + (node.X ?? 0);
        var y = originY + (node.Y ?? 0);
        var blur = Math.Max(node.Blur ?? 0, 0);
        var bounds = SubtreeBounds(node, x, y);
        var pad = DesignRenderMath.LayerBlurPadding(blur);
        var left = Math.Floor(bounds.Left - pad);
        var top = Math.Floor(bounds.Top - pad);
        var right = Math.Ceiling(bounds.Right + pad);
        var bottom = Math.Ceiling(bounds.Bottom + pad);
        var wPx = Math.Max(1, (int)(right - left));
        var hPx = Math.Max(1, (int)(bottom - top));

        // 异常大的图层不做离屏模糊，避免一次分配几十 MB；退化为不模糊。
        if ((long)wPx * hPx > 8_000_000L)
        {
            DrawNode(context, node, originX, originY, 1d, scale, skipBlur: true);
            return;
        }

        using var target = new RenderTargetBitmap(new PixelSize(wPx, hPx), new Vector(96, 96));
        using (var targetContext = target.CreateDrawingContext())
        {
            using (targetContext.PushTransform(Matrix.CreateTranslation(-left, -top)))
            {
                DrawNode(targetContext, node, originX, originY, 1d, scale, skipBlur: true);
            }
        }

        var pixels = new int[wPx * hPx];
        CopyPixels(target, pixels, wPx, hPx);
        DesignRenderMath.BlurArgb(pixels, wPx, hPx, DesignRenderMath.LayerSigma(blur));
        var bitmap = WriteBitmap(pixels, wPx, hPx);
        context.DrawImage(bitmap, new Rect(left, top, wPx, hPx));
    }

    /// <summary>子树未旋转时的包围盒 + 阴影 / 文字效果外扩；最后按本节点旋转取 AABB。</summary>
    private static Rect SubtreeBounds(DesignNode node, double x, double y)
    {
        var w = node.W ?? 0;
        var h = node.H ?? 0;
        var left = x;
        var top = y;
        var right = x + w;
        var bottom = y + h;
        var type = TypeOf(node);

        foreach (var spec in ShadowSpecs(node, type))
        {
            var pad = DesignRenderMath.BlurPadding(spec.Blur);
            left = Math.Min(left, x + spec.Dx - pad);
            top = Math.Min(top, y + spec.Dy - pad);
            right = Math.Max(right, x + w + spec.Dx + pad);
            bottom = Math.Max(bottom, y + h + spec.Dy + pad);
        }

        var fxPad = TextEffectPadding(node);
        if (fxPad > 0)
        {
            left -= fxPad;
            top -= fxPad;
            right += fxPad;
            bottom += fxPad;
        }

        if (node.Children is { Count: > 0 })
        {
            foreach (var child in node.Children)
            {
                var childBounds = SubtreeBounds(child, x + (child.X ?? 0), y + (child.Y ?? 0));
                left = Math.Min(left, childBounds.Left);
                top = Math.Min(top, childBounds.Top);
                right = Math.Max(right, childBounds.Right);
                bottom = Math.Max(bottom, childBounds.Bottom);
            }
        }

        var bounds = new Rect(left, top, right - left, bottom - top);
        var rotation = node.Rotation ?? 0;
        if (Math.Abs(rotation) <= .01)
        {
            return bounds;
        }

        return RotateBounds(bounds, x + w / 2, y + h / 2, rotation);
    }

    /// <summary>文字发光/投影的最大外扩（模糊 3σ + 偏移）。</summary>
    private static double TextEffectPadding(DesignNode node)
    {
        var pad = 0d;
        foreach (var effect in new[] { node.TextShadow, node.TextGlow })
        {
            if (effect is null)
            {
                continue;
            }

            var blur = Math.Max(effect.Blur ?? effect.Radius ?? 0, 0);
            var offset = Math.Max(Math.Abs(effect.Dx ?? 0), Math.Abs(effect.Dy ?? 0));
            pad = Math.Max(pad, DesignRenderMath.BlurPadding(blur) + offset);
        }

        return pad;
    }

    private static Rect RotateBounds(Rect rect, double cx, double cy, double degrees)
    {
        var radians = degrees * Math.PI / 180;
        var cos = Math.Cos(radians);
        var sin = Math.Sin(radians);
        var minX = double.MaxValue;
        var minY = double.MaxValue;
        var maxX = double.MinValue;
        var maxY = double.MinValue;
        foreach (var (px, py) in new[]
                 {
                     (rect.Left, rect.Top),
                     (rect.Right, rect.Top),
                     (rect.Right, rect.Bottom),
                     (rect.Left, rect.Bottom),
                 })
        {
            var dx = px - cx;
            var dy = py - cy;
            var rx = cx + dx * cos - dy * sin;
            var ry = cy + dx * sin + dy * cos;
            minX = Math.Min(minX, rx);
            minY = Math.Min(minY, ry);
            maxX = Math.Max(maxX, rx);
            maxY = Math.Max(maxY, ry);
        }

        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    /// <summary>
    /// 任意几何的离屏高斯模糊：先画到透明 RenderTargetBitmap，再像素级模糊。
    /// [offsetX] / [offsetY] 是几何局部原点在父坐标系里的位置。
    /// </summary>
    private static void DrawBlurredGeometry(
        DrawingContext context,
        Geometry geometry,
        Color color,
        double blur,
        double offsetX,
        double offsetY,
        string cacheKey)
    {
        if (blur <= .0001 || color.A == 0)
        {
            return;
        }

        var image = BlurredGeometryImage(geometry, color, blur, cacheKey);
        if (image is null)
        {
            return;
        }

        var bounds = geometry.Bounds;
        var pad = DesignRenderMath.BlurPadding(blur);
        var left = Math.Floor(bounds.Left - pad);
        var top = Math.Floor(bounds.Top - pad);
        context.DrawImage(
            image,
            new Rect(offsetX + left, offsetY + top, image.Size.Width, image.Size.Height));
    }

    private static WriteableBitmap? BlurredGeometryImage(
        Geometry geometry,
        Color color,
        double blur,
        string cacheKey)
    {
        if (BlurCache.TryGetValue(cacheKey, out var cached))
        {
            return cached;
        }

        var bounds = geometry.Bounds;
        var pad = DesignRenderMath.BlurPadding(blur);
        var left = Math.Floor(bounds.Left - pad);
        var top = Math.Floor(bounds.Top - pad);
        var right = Math.Ceiling(bounds.Right + pad);
        var bottom = Math.Ceiling(bounds.Bottom + pad);
        var wPx = Math.Max(1, (int)(right - left));
        var hPx = Math.Max(1, (int)(bottom - top));
        if ((long)wPx * hPx > 8_000_000L)
        {
            return null;
        }

        using var target = new RenderTargetBitmap(new PixelSize(wPx, hPx), new Vector(96, 96));
        using (var targetContext = target.CreateDrawingContext())
        {
            using (targetContext.PushTransform(Matrix.CreateTranslation(-left, -top)))
            {
                targetContext.DrawGeometry(new ImmutableSolidColorBrush(color), null, geometry);
            }
        }

        var pixels = new int[wPx * hPx];
        CopyPixels(target, pixels, wPx, hPx);
        DesignRenderMath.BlurArgb(pixels, wPx, hPx, DesignRenderMath.ShadowSigma(blur));
        var bitmap = WriteBitmap(pixels, wPx, hPx);
        if (BlurCache.Count > 96)
        {
            // 不在这里 Dispose：当前帧可能还有绘制指令引用旧贴图，交给 GC 回收更安全。
            BlurCache.Clear();
        }

        BlurCache[cacheKey] = bitmap;
        return bitmap;
    }

    private static void CopyPixels(RenderTargetBitmap target, int[] pixels, int w, int h)
    {
        var handle = GCHandle.Alloc(pixels, GCHandleType.Pinned);
        try
        {
            target.CopyPixels(
                new PixelRect(0, 0, w, h),
                handle.AddrOfPinnedObject(),
                pixels.Length * 4,
                w * 4);
        }
        finally
        {
            handle.Free();
        }
    }

    private static WriteableBitmap WriteBitmap(int[] pixels, int w, int h)
    {
        var bitmap = new WriteableBitmap(
            new PixelSize(w, h),
            new Vector(96, 96),
            PixelFormat.Bgra8888,
            AlphaFormat.Premul);
        using var buffer = bitmap.Lock();
        for (var y = 0; y < h; y++)
        {
            // Marshal.Copy 的 length 是元素个数（每个 int = 4 字节），一行正好 w 个。
            Marshal.Copy(pixels, y * w, IntPtr.Add(buffer.Address, y * buffer.RowBytes), w);
        }

        return bitmap;
    }

    private static void DrawNodeCore(
        DrawingContext context,
        DesignNode node,
        string type,
        double x,
        double y,
        double w,
        double h,
        double scale)
    {
        var ellipse = IsEllipse(type, w, h);
        var corners = CornersOf(node, type, w, h);

        // CSS 合成顺序：外阴影 → 背景 → 内阴影 → 内容/子节点 → 边框 → 文字。
        DrawNodeShadows(context, node, type, ellipse, corners, x, y, w, h);

        switch (type)
        {
            case "slider":
                DrawSlider(context, node, x, y, w, h);
                break;
            case "switch":
                DrawSwitch(context, node, x, y, w, h, ellipse, corners);
                break;
            case "progress":
                DrawProgress(context, node, x, y, w, h, ellipse, corners);
                break;
            default:
                // 纯文本且没给 fill 的节点不画底色；显式 none/transparent 同样不画。
                // 旧设计稿把文字色存在 fill（无 color）时，fill 是文字色而不是底色。
                var legacyInk = LegacyTextInk(node, type);
                var bareText = type == "text"
                    && (string.IsNullOrWhiteSpace(node.Fill) || legacyInk != null);
                if (!bareText && !IsNoneFill(node.Fill))
                {
                    var brush = FillBrush(node.Fill, type, w, h);
                    var geometry = ShapeGeometry(ellipse, corners, x, y, w, h, 0);
                    if (geometry != null)
                    {
                        context.DrawGeometry(brush, null, geometry);
                    }
                }

                break;
        }

        DrawInsetShadows(context, node, ellipse, corners, x, y, w, h);
        DrawNodeStroke(context, node, ellipse, corners, x, y, w, h);

        if (type == "nav")
        {
            DrawNav(context, node, x, y, w, h);
        }
        else
        {
            DrawNodeText(context, node, type, x, y, w, h, scale);
        }
    }

    // ---------- 节点外形 / 效果 ----------

    /// <summary>四角圆角，单位与节点一致；对齐手机 `DesignSpec.Corners` / 后端 `radius_of`。</summary>
    private readonly record struct Corners(double Tl, double Tr, double Br, double Bl);

    /// <summary>椭圆节点：circle / avatar，以及等宽的 decor。</summary>
    private static bool IsEllipse(string type, double w, double h) =>
        type is "circle" or "avatar" || (type == "decor" && Math.Abs(w - h) < .01);

    /// <summary>
    /// 四角圆角：显式值优先，负值按 0，上限 `min(w, h) / 2`；只给了部分角时，
    /// 未给的角回落类型默认圆角（与手机 `cornersOf` 一致）。
    /// </summary>
    private static Corners CornersOf(DesignNode node, string type, double w, double h)
    {
        var limit = Math.Max(Math.Min(w, h) / 2, 0);
        var fallback = Math.Clamp(DefaultRadius(type, w, h), 0, limit);
        var raw = node.Radius;
        if (raw is null)
        {
            return new Corners(fallback, fallback, fallback, fallback);
        }

        double Corner(double? value) => Math.Clamp(value ?? fallback, 0, limit);
        return new Corners(Corner(raw.Tl), Corner(raw.Tr), Corner(raw.Br), Corner(raw.Bl));
    }

    /// <summary>裁剪到节点外形：椭圆用椭圆，其余用四角圆角矩形。</summary>
    private static IDisposable PushShapeClip(DrawingContext context, bool ellipse, Corners corners, Rect rect) =>
        ellipse
            ? context.PushGeometryClip(new EllipseGeometry(rect))
            : context.PushClip(new RoundedRect(rect, corners.Tl, corners.Tr, corners.Br, corners.Bl));

    /// <summary>
    /// 节点外形路径。`delta` 给阴影环用：外扩时圆角同步加 spread（CSS `border-radius + spread`），
    /// 内缩（inset 孔洞）时传负值。
    /// </summary>
    private static Geometry? ShapeGeometry(
        bool ellipse,
        Corners corners,
        double x,
        double y,
        double w,
        double h,
        double delta)
    {
        if (w <= 0 || h <= 0)
        {
            return null;
        }

        if (ellipse)
        {
            return new EllipseGeometry(new Rect(x, y, w, h));
        }

        var limit = Math.Min(w, h) / 2;
        double Corner(double value) => Math.Clamp(value + delta, 0, limit);
        var tl = Corner(corners.Tl);
        var tr = Corner(corners.Tr);
        var br = Corner(corners.Br);
        var bl = Corner(corners.Bl);

        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            ctx.BeginFigure(new Point(x + tl, y), true);
            ctx.LineTo(new Point(x + w - tr, y));
            if (tr > 0)
            {
                ctx.ArcTo(new Point(x + w, y + tr), new Size(tr, tr), 0, false, SweepDirection.Clockwise);
            }

            ctx.LineTo(new Point(x + w, y + h - br));
            if (br > 0)
            {
                ctx.ArcTo(new Point(x + w - br, y + h), new Size(br, br), 0, false, SweepDirection.Clockwise);
            }

            ctx.LineTo(new Point(x + bl, y + h));
            if (bl > 0)
            {
                ctx.ArcTo(new Point(x, y + h - bl), new Size(bl, bl), 0, false, SweepDirection.Clockwise);
            }

            ctx.LineTo(new Point(x, y + tl));
            if (tl > 0)
            {
                ctx.ArcTo(new Point(x + tl, y), new Size(tl, tl), 0, false, SweepDirection.Clockwise);
            }

            ctx.EndFigure(true);
        }

        return geometry;
    }

    /// <summary>
    /// 一个阴影：真实高斯模糊参数。[Blur] 是 CSS box-shadow 的模糊半径
    /// （标准差 = blur / 2），0 表示硬阴影。
    /// </summary>
    private readonly record struct ShadowSpec(double Dx, double Dy, double Blur, Color Color, bool Inset);

    /// <summary>外阴影；card 无显式 shadow 时补默认投影（dy=6 / blur=0）。</summary>
    private static List<ShadowSpec> ShadowSpecs(DesignNode node, string type)
    {
        if (node.Shadow is null && type == "card")
        {
            return new List<ShadowSpec>
            {
                new(
                    0,
                    CardShadowOffset,
                    0,
                    Color.FromArgb((byte)Math.Round(CardShadowAlpha * 255), 0, 0, 0),
                    false),
            };
        }

        return ShadowSpecsOf(node.Shadow, false).Where(spec => !spec.Inset).ToList();
    }

    /// <summary>内阴影：`insetShadow` 恒在盒内，`shadow` 里 inset=true 的层也算内阴影。</summary>
    private static List<ShadowSpec> InsetShadowSpecs(DesignNode node) =>
        ShadowSpecsOf(node.Shadow, false)
            .Concat(ShadowSpecsOf(node.InsetShadow, true))
            .Where(spec => spec.Inset)
            .ToList();

    private static List<ShadowSpec> ShadowSpecsOf(DesignShadow? shadow, bool forceInset)
    {
        if (shadow is null)
        {
            return new List<ShadowSpec>();
        }

        var color = SingleColor(shadow.Color, DefaultShadow);
        if (color.A / 255d <= .004)
        {
            return new List<ShadowSpec>();
        }

        return new List<ShadowSpec>
        {
            new(
                shadow.Dx,
                shadow.Dy,
                Math.Max(shadow.Blur, 0),
                color,
                forceInset || shadow.Inset),
        };
    }

    /// <summary>
    /// 外阴影：矩形/圆角矩形交给 Skia BoxShadow 做真实模糊；椭圆走离屏像素高斯。
    /// blur=0 直接画偏移实心形状，保持硬边锐利。
    /// </summary>
    private static void DrawNodeShadows(
        DrawingContext context,
        DesignNode node,
        string type,
        bool ellipse,
        Corners corners,
        double x,
        double y,
        double w,
        double h)
    {
        foreach (var spec in ShadowSpecs(node, type))
        {
            if (spec.Color.A == 0)
            {
                continue;
            }

            if (spec.Blur <= .0001)
            {
                var geometry = ShapeGeometry(ellipse, corners, x + spec.Dx, y + spec.Dy, w, h, 0);
                if (geometry is not null)
                {
                    context.DrawGeometry(new ImmutableSolidColorBrush(spec.Color), null, geometry);
                }

                continue;
            }

            if (!ellipse)
            {
                var shadow = new BoxShadow
                {
                    OffsetX = spec.Dx,
                    OffsetY = spec.Dy,
                    Blur = spec.Blur,
                    Spread = 0,
                    Color = spec.Color,
                    IsInset = false,
                };
                context.DrawRectangle(
                    Brushes.Transparent,
                    null,
                    new RoundedRect(new Rect(x, y, w, h), corners.Tl, corners.Tr, corners.Br, corners.Bl),
                    new BoxShadows(shadow));
                continue;
            }

            var local = ShapeGeometry(true, corners, 0, 0, w, h, 0);
            if (local is null)
            {
                continue;
            }

            DrawBlurredGeometry(
                context,
                local,
                spec.Color,
                spec.Blur,
                x + spec.Dx,
                y + spec.Dy,
                $"shadow|{node.Id}|{w:F2}|{h:F2}|{spec.Blur:F2}|{spec.Color}");
        }
    }

    /// <summary>
    /// 内阴影：矩形用 Skia 的 inset BoxShadow（真实模糊）；椭圆用「大矩形 − 偏移洞」
    /// 的环形几何做离屏模糊，再裁剪到外形内。blur=0 时仍是硬边内投影。
    /// </summary>
    private static void DrawInsetShadows(
        DrawingContext context,
        DesignNode node,
        bool ellipse,
        Corners corners,
        double x,
        double y,
        double w,
        double h)
    {
        var specs = InsetShadowSpecs(node);
        if (specs.Count == 0)
        {
            return;
        }

        var outer = ShapeGeometry(ellipse, corners, x, y, w, h, 0);
        if (outer is null)
        {
            return;
        }

        using (context.PushGeometryClip(outer))
        {
            foreach (var spec in specs)
            {
                if (!ellipse && spec.Blur > .0001)
                {
                    var shadow = new BoxShadow
                    {
                        OffsetX = spec.Dx,
                        OffsetY = spec.Dy,
                        Blur = spec.Blur,
                        Spread = 0,
                        Color = spec.Color,
                        IsInset = true,
                    };
                    context.DrawRectangle(
                        Brushes.Transparent,
                        null,
                        new RoundedRect(new Rect(x, y, w, h), corners.Tl, corners.Tr, corners.Br, corners.Bl),
                        new BoxShadows(shadow));
                    continue;
                }

                var hole = ShapeGeometry(ellipse, corners, x + spec.Dx, y + spec.Dy, w, h, 0);
                if (hole is null)
                {
                    context.DrawGeometry(new ImmutableSolidColorBrush(spec.Color), null, outer);
                    continue;
                }

                if (spec.Blur <= .0001)
                {
                    context.DrawGeometry(
                        new ImmutableSolidColorBrush(spec.Color),
                        null,
                        new CombinedGeometry(GeometryCombineMode.Exclude, outer, hole));
                    continue;
                }

                // 模糊版：外框用一块「足够大」的矩形，只有洞的边缘被模糊；
                // 随后被外框 clip 掉，因此贴边处仍是实色（与 CSS inset 阴影一致）。
                var pad = DesignRenderMath.BlurPadding(spec.Blur) + 2;
                var localOuter = new RectangleGeometry(new Rect(-pad, -pad, w + pad * 2, h + pad * 2));
                var localHole = ShapeGeometry(
                    true,
                    corners,
                    spec.Dx,
                    spec.Dy,
                    w,
                    h,
                    0);
                if (localHole is null)
                {
                    continue;
                }

                var ring = new CombinedGeometry(GeometryCombineMode.Exclude, localOuter, localHole);
                DrawBlurredGeometry(
                    context,
                    ring,
                    spec.Color,
                    spec.Blur,
                    x,
                    y,
                    $"inset|{node.Id}|{w:F2}|{h:F2}|{spec.Dx:F2}|{spec.Dy:F2}|{spec.Blur:F2}|{spec.Color}");
            }
        }
    }

    /// <summary>节点描边；`Sides` 为 null 表示四边都画，`Dash` 为真即虚线。</summary>
    private readonly record struct StrokeSpec(Color Color, double Width, bool Dash, HashSet<string>? Sides)
    {
        public bool Has(string side) => Sides is null || Sides.Contains(side);
    }

    private static StrokeSpec? StrokeOf(DesignNode node)
    {
        var raw = node.Stroke;
        if (raw is null || ParseColor(raw.Color) is not { } color)
        {
            return null;
        }

        var width = Math.Max(raw.Width, 0);
        if (width <= 0)
        {
            return null;
        }

        HashSet<string>? sides = null;
        if (raw.Sides is { Count: > 0 })
        {
            var set = new HashSet<string>(StringComparer.Ordinal);
            foreach (var side in raw.Sides)
            {
                var value = side.Trim().ToLowerInvariant();
                if (StrokeSides.Contains(value))
                {
                    set.Add(value);
                }
            }

            if (set.Count > 0)
            {
                sides = set;
            }
        }

        return new StrokeSpec(color, width, raw.Dash is { Count: > 0 }, sides);
    }

    /// <summary>
    /// 节点描边：网页 `border` 恒在盒内，线宽的一半内缩；`dash` 统一成 `[w×3, w×2]`；
    /// `sides` 只画列出的边（先裁剪到圆角外形，避免直角边溢出圆角）。
    /// </summary>
    private static void DrawNodeStroke(
        DrawingContext context,
        DesignNode node,
        bool ellipse,
        Corners corners,
        double x,
        double y,
        double w,
        double h)
    {
        if (StrokeOf(node) is not { } spec)
        {
            return;
        }

        var half = spec.Width / 2;
        var brush = new ImmutableSolidColorBrush(spec.Color);
        // Avalonia 的虚线长度单位是线宽倍数，[3,2] 即 w×3 / w×2，与手机 PathEffect 同义。
        IPen pen = spec.Dash
            ? new Pen(brush, spec.Width, new DashStyle(new double[] { 3, 2 }, 0))
            : new ImmutablePen(brush, spec.Width);

        if (ellipse)
        {
            var innerW = w - spec.Width;
            var innerH = h - spec.Width;
            if (innerW <= 0 || innerH <= 0)
            {
                return;
            }

            context.DrawEllipse(null, pen, new Rect(x + half, y + half, innerW, innerH));
            return;
        }

        if (spec.Sides is null)
        {
            var geometry = ShapeGeometry(false, corners, x + half, y + half, w - spec.Width, h - spec.Width, -half);
            if (geometry is not null)
            {
                context.DrawGeometry(null, pen, geometry);
            }

            return;
        }

        var clip = ShapeGeometry(false, corners, x, y, w, h, 0);
        if (clip is null)
        {
            return;
        }

        using (context.PushGeometryClip(clip))
        {
            if (spec.Has("top"))
            {
                context.DrawLine(pen, new Point(x, y + half), new Point(x + w, y + half));
            }

            if (spec.Has("bottom"))
            {
                context.DrawLine(pen, new Point(x, y + h - half), new Point(x + w, y + h - half));
            }

            if (spec.Has("left"))
            {
                context.DrawLine(pen, new Point(x + half, y), new Point(x + half, y + h));
            }

            if (spec.Has("right"))
            {
                context.DrawLine(pen, new Point(x + w - half, y), new Point(x + w - half, y + h));
            }
        }
    }

    /// <summary>进度条：轨道铺满 + 前景按 value 裁宽，圆角继承节点（与 `.pg-*` 一致）。</summary>
    private static void DrawProgress(
        DrawingContext context,
        DesignNode node,
        double x,
        double y,
        double w,
        double h,
        bool ellipse,
        Corners corners)
    {
        var track = ShapeGeometry(ellipse, corners, x, y, w, h, 0);
        if (track is null)
        {
            return;
        }

        context.DrawGeometry(
            new ImmutableSolidColorBrush(SingleColor(node.Track, SwitchOff)),
            null,
            track);

        var value = Math.Clamp(node.Value ?? .5, 0, 1);
        if (value <= 0)
        {
            return;
        }

        var fillWidth = Math.Min(w * value, w);
        var brush = new ImmutableSolidColorBrush(SingleColor(node.Fill, DefaultFill("progress")));
        if (ellipse)
        {
            context.DrawEllipse(brush, null, new Rect(x, y, fillWidth, h));
            return;
        }

        var fill = ShapeGeometry(false, corners, x, y, fillWidth, h, 0);
        if (fill is not null)
        {
            context.DrawGeometry(brush, null, fill);
        }
    }

    private static void DrawNodeText(
        DrawingContext context,
        DesignNode node,
        string type,
        double x,
        double y,
        double w,
        double h,
        double scale)
    {
        var text = node.Text ?? "";
        if (text.Trim().Length == 0)
        {
            return;
        }

        var size = node.FontSize ?? DefaultFontSize;
        if (size <= 0)
        {
            return;
        }

        var inset = type == "input" ? Math.Max(node.PadLeft ?? DefaultInputPad, 0) : 0;
        var boxWidth = Math.Max(w - inset, 1);
        var alignment = CenterTextTypes.Contains(type)
            ? TextAlignment.Center
            : node.Align switch
            {
                "center" => TextAlignment.Center,
                "right" => TextAlignment.Right,
                _ => TextAlignment.Left,
            };

        var color = LegacyTextInk(node, type) ?? SingleColor(node.Color, DefaultTextColor(type));
        var weight = (FontWeight)(ushort)Math.Clamp(node.FontWeight ?? 400, 1, 1000);
        var typeface = new Typeface(
            FontFamilyFor(node),
            node.Italic == true ? FontStyle.Italic : FontStyle.Normal,
            weight);
        var decorations = DecorationOf(node);
        var solidBrush = new ImmutableSolidColorBrush(color);
        var ratio = node.LineHeight ?? DefaultLineHeight;
        var letterSpacing = node.LetterSpacing ?? 0;
        var autoFit = node.AutoFit == true;
        var maxLines = autoFit ? 1 : Math.Clamp(node.MaxLines ?? MaxTextLines, 1, TextLinesLimit);
        if (autoFit)
        {
            size = FitSize(text, typeface, solidBrush, alignment, boxWidth, ratio, letterSpacing, size);
        }

        TextLayout Build(IBrush paint) => new(
            text,
            typeface,
            size,
            paint,
            alignment,
            autoFit ? TextWrapping.NoWrap : TextWrapping.Wrap,
            TextTrimming.CharacterEllipsis,
            decorations,
            FlowDirection.LeftToRight,
            boxWidth,
            double.PositiveInfinity,
            ratio * size,
            letterSpacing,
            maxLines,
            null);

        var key = new TextKey(
            text,
            typeface.FontFamily.Name,
            typeface.Style,
            weight,
            color,
            alignment,
            !autoFit,
            size,
            ratio,
            letterSpacing,
            maxLines,
            node.Underline == true,
            node.Strikethrough == true,
            boxWidth,
            node.TextFill ?? "");
        if (!TextCache.TryGetValue(key, out var entry))
        {
            if (TextCache.Count >= TextCacheLimit)
            {
                TextCache.Clear();
            }

            var solid = Build(solidBrush);
            // 文字渐变按排版盒尺寸生成；单色标退化为纯色（与后端单色标渐变一致）。
            TextLayout? gradient = null;
            if (ParseGradient(node.TextFill) is { } textGradient && solid.Width > 0 && solid.Height > 0)
            {
                gradient = Build(BuildGradientBrush(textGradient, solid.Width, solid.Height));
            }

            entry = new TextEntry(solid, gradient);
            TextCache[key] = entry;
        }

        var layout = entry.Gradient ?? entry.Solid;

        var top = y + (h - layout.Height) / 2;
        // 整数倍缩放时把文字原点吸附到像素网格；任意缩放保留亚像素定位，避免平移抖动。
        var origin = new Point(
            DesignRenderMath.SnapToPixel(x + inset, scale),
            DesignRenderMath.SnapToPixel(top, scale));
        var effects = TextEffectsOf(node);
        var stroke = TextStrokeOf(node);

        using (context.PushClip(new Rect(x, y, w, h)))
        {
            // Web 的 `input::placeholder{opacity:.5}`：透明度只在绘制期乘一次，
            // 不并进颜色，避免与节点自身 alpha 相乘两次。
            var alpha = type == "input" ? InputPlaceholderAlpha : 1d;

            void Paint()
            {
                if (effects.Count == 0 && stroke == null)
                {
                    layout.Draw(context, origin);
                    return;
                }

                // 与手机端 `drawTextNode` 同序：投影 → 发光 → 描边 → 填充。
                if (effects.Count > 0)
                {
                    // 效果用局部字形轮廓，位图可按「文字 + 字号 + 颜色 + blur」缓存，
                    // 节点移动时只改贴图位置，不重做模糊。
                    var local = TextGeometry(
                        text,
                        typeface,
                        size,
                        alignment,
                        boxWidth,
                        ratio,
                        node,
                        autoFit,
                        decorations,
                        new Point(0, 0));
                    if (local != null)
                    {
                        foreach (var effect in effects)
                        {
                            if (effect.Blur <= .0001)
                            {
                                using (context.PushTransform(
                                           Matrix.CreateTranslation(origin.X + effect.Dx, origin.Y + effect.Dy)))
                                {
                                    context.DrawGeometry(
                                        new ImmutableSolidColorBrush(effect.Color),
                                        null,
                                        local);
                                }

                                continue;
                            }

                            DrawBlurredGeometry(
                                context,
                                local,
                                effect.Color,
                                effect.Blur,
                                origin.X + effect.Dx,
                                origin.Y + effect.Dy,
                                TextEffectKey(
                                    node,
                                    text,
                                    typeface,
                                    size,
                                    ratio,
                                    letterSpacing,
                                    maxLines,
                                    boxWidth,
                                    alignment,
                                    autoFit,
                                    effect));
                        }
                    }
                }

                if (stroke is { } line)
                {
                    var geometry = TextGeometry(
                        text,
                        typeface,
                        size,
                        alignment,
                        boxWidth,
                        ratio,
                        node,
                        autoFit,
                        decorations,
                        origin);
                    if (geometry != null)
                    {
                        context.DrawGeometry(
                            null,
                            new ImmutablePen(new ImmutableSolidColorBrush(line.Color), line.Width),
                            geometry);
                    }
                }

                layout.Draw(context, origin);
            }

            if (alpha < 1)
            {
                using (context.PushOpacity(alpha))
                {
                    Paint();
                }
            }
            else
            {
                Paint();
            }
        }
    }

    /// <summary>
    /// 一个文字效果：真实高斯模糊参数。[Blur] 是 CSS text-shadow 的 blur 半径
    /// （标准差 = blur / 2），0 表示硬阴影。
    /// </summary>
    private readonly record struct TextEffectSpec(double Dx, double Dy, double Blur, Color Color);

    /// <summary>投影在下、发光在上，与网页 / 手机同序。</summary>
    private static List<TextEffectSpec> TextEffectsOf(DesignNode node) =>
        EffectsOf(node.TextShadow, false).Concat(EffectsOf(node.TextGlow, true)).ToList();

    private static List<TextEffectSpec> EffectsOf(DesignTextEffect? effect, bool glow)
    {
        if (effect == null || ParseColor(effect.Color) is not { } baseColor)
        {
            // 颜色解析失败整段丢弃，对齐编译层 `_compile_fx`。
            return new List<TextEffectSpec>();
        }

        var alpha = Math.Clamp(baseColor.A / 255d * Math.Clamp(effect.Intensity ?? 1, 0, 1), 0, 1);
        if (alpha <= .004)
        {
            return new List<TextEffectSpec>();
        }

        // blur 缺省时回退到 radius（设计稿里发光习惯写 radius），与 `_compile_fx` 一致。
        var blur = Math.Max(effect.Blur ?? effect.Radius ?? 0, 0);
        var dx = glow ? 0 : effect.Dx ?? 0;
        var dy = glow ? 0 : effect.Dy ?? 0;
        var color = Color.FromArgb((byte)Math.Round(alpha * 255), baseColor.R, baseColor.G, baseColor.B);
        return new List<TextEffectSpec> { new(dx, dy, blur, color) };
    }

    private static string TextEffectKey(
        DesignNode node,
        string text,
        Typeface typeface,
        double size,
        double ratio,
        double letterSpacing,
        int maxLines,
        double boxWidth,
        TextAlignment alignment,
        bool autoFit,
        TextEffectSpec effect) =>
        $"{node.Id}|{text}|{typeface.FontFamily.Name}|{typeface.Style}|{typeface.Weight}|" +
        $"{size:F2}|{ratio:F2}|{letterSpacing:F2}|{maxLines}|{boxWidth:F2}|" +
        $"{alignment}|{autoFit}|{node.Underline == true}|{node.Strikethrough == true}|" +
        $"{effect.Blur:F2}|{effect.Color}";

    /// <summary>
    /// 字形轮廓：文字描边与发光/投影都基于它。`FormattedText` 不支持 letterSpacing，
    /// 带字距的艺术字轮廓会有微小水平偏差（已知限制）。
    /// </summary>
    private static Geometry? TextGeometry(
        string text,
        Typeface typeface,
        double size,
        TextAlignment alignment,
        double boxWidth,
        double lineHeight,
        DesignNode node,
        bool autoFit,
        TextDecorationCollection? decorations,
        Point origin)
    {
        var formatted = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            typeface,
            size,
            Brushes.Black)
        {
            TextAlignment = alignment,
            MaxTextWidth = boxWidth,
            LineHeight = lineHeight * size,
            MaxLineCount = autoFit ? 1 : Math.Clamp(node.MaxLines ?? MaxTextLines, 1, TextLinesLimit),
            Trimming = TextTrimming.CharacterEllipsis,
        };
        if (decorations != null)
        {
            formatted.SetTextDecorations(decorations);
        }

        return formatted.BuildGeometry(origin);
    }
    /// <summary>描边宽度：outside 时翻倍（与网页 `-webkit-text-stroke` 居中描边对齐），inside 保持原值。</summary>
    private static (double Width, Color Color)? TextStrokeOf(DesignNode node)
    {
        if (node.TextStroke is not { } raw || ParseColor(raw.Color) is not { } color)
        {
            return null;
        }

        var width = Math.Max(raw.Width ?? 1, 0);
        if (width <= 0)
        {
            return null;
        }

        var inside = string.Equals(raw.Align?.Trim(), "inside", StringComparison.OrdinalIgnoreCase);
        return (inside ? width : width * 2, color);
    }

    // ---------- 渐变 ----------

    private readonly record struct GradientStopSpec(Color Color, double Offset);

    /// <summary>CSS 渐变解析结果；`Angle` 与 CSS 同义（0deg 向上、90deg 向右、顺时针）。</summary>
    private sealed record GradientSpec(
        string Kind,
        double Angle,
        double Cx,
        double Cy,
        IReadOnlyList<GradientStopSpec> Stops);

    /// <summary>解析 `linear-gradient(...)` / `radial-gradient(circle at x y, ...)`；失败返回 null。</summary>
    private static GradientSpec? ParseGradient(string? raw)
    {
        var value = raw?.Trim() ?? "";
        if (value.Length == 0)
        {
            return null;
        }

        var match = GradientRe.Match(value);
        if (!match.Success)
        {
            return null;
        }

        var kind = match.Groups[1].Value.ToLowerInvariant();
        var segments = SplitTopLevel(match.Groups[2].Value);
        if (segments.Count == 0)
        {
            return null;
        }

        var angle = 180d;
        var cx = .5d;
        var cy = .5d;
        if (kind == "linear")
        {
            var head = segments[0].Trim().ToLowerInvariant();
            if (AngleValue(head) is { } degrees)
            {
                angle = degrees;
                segments.RemoveAt(0);
            }
            else if (DirectionAngles.TryGetValue(head, out var direction))
            {
                angle = direction;
                segments.RemoveAt(0);
            }
        }
        else if (segments.Count > 0)
        {
            var head = segments[0].Trim().ToLowerInvariant();
            if (head.Contains("at", StringComparison.Ordinal) || RadialHeadRe.IsMatch(head))
            {
                if (RadialCenter(head) is { } center)
                {
                    cx = center.X;
                    cy = center.Y;
                }

                segments.RemoveAt(0);
            }
        }

        var rawStops = new List<(Color Color, double? Offset)>();
        foreach (var segment in segments)
        {
            var tokens = segment.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (tokens.Length == 0 || ParseColor(tokens[0]) is not { } stopColor)
            {
                continue;
            }

            double? offset = null;
            for (var index = 1; index < tokens.Length; index++)
            {
                var offsetMatch = OffsetRe.Match(tokens[index]);
                if (offsetMatch.Success &&
                    double.TryParse(
                        offsetMatch.Groups[1].Value,
                        NumberStyles.Float,
                        CultureInfo.InvariantCulture,
                        out var number))
                {
                    offset = Math.Clamp(number / 100, 0, 1);
                    break;
                }
            }

            rawStops.Add((stopColor, offset));
        }

        return rawStops.Count == 0
            ? null
            : new GradientSpec(kind, angle, cx, cy, ResolveStops(rawStops));
    }

    /// <summary>未标位置的色标按 CSS 规则在相邻已标位置之间均分，与后端 `parse_gradient` 同算法。</summary>
    private static List<GradientStopSpec> ResolveStops(List<(Color Color, double? Offset)> raw)
    {
        var offsets = raw.Select(stop => stop.Offset).ToList();
        var firstFixed = offsets.FindIndex(offset => offset is not null);
        if (firstFixed < 0)
        {
            var count = raw.Count;
            return raw.Select(
                    (stop, index) => new GradientStopSpec(
                        stop.Color,
                        count > 1 ? index / (double)(count - 1) : 0))
                .ToList();
        }

        for (var index = 0; index < firstFixed; index++)
        {
            offsets[index] = 0;
        }

        var lastFixed = offsets.FindLastIndex(offset => offset is not null);
        for (var index = lastFixed + 1; index < offsets.Count; index++)
        {
            offsets[index] = 1;
        }

        var cursor = firstFixed;
        while (cursor <= lastFixed)
        {
            if (offsets[cursor] is null)
            {
                var next = cursor;
                while (next <= lastFixed && offsets[next] is null)
                {
                    next++;
                }

                var start = offsets[cursor - 1] ?? 0;
                var end = offsets[next] ?? 0;
                var step = (end - start) / (next - cursor + 1);
                for (var fill = cursor; fill < next; fill++)
                {
                    offsets[fill] = start + step * (fill - cursor + 1);
                }

                cursor = next;
            }

            cursor++;
        }

        return raw.Select(
                (stop, index) => new GradientStopSpec(
                    stop.Color,
                    Math.Clamp(offsets[index] ?? 0, 0, 1)))
            .ToList();
    }

    private static List<string> SplitTopLevel(string value)
    {
        var parts = new List<string>();
        var current = new StringBuilder();
        var depth = 0;
        foreach (var ch in value)
        {
            if (ch == '(')
            {
                depth++;
            }
            else if (ch == ')' && depth > 0)
            {
                depth--;
            }

            if (ch == ',' && depth == 0)
            {
                if (!string.IsNullOrWhiteSpace(current.ToString()))
                {
                    parts.Add(current.ToString().Trim());
                }

                current.Clear();
                continue;
            }

            current.Append(ch);
        }

        if (!string.IsNullOrWhiteSpace(current.ToString()))
        {
            parts.Add(current.ToString().Trim());
        }

        return parts;
    }

    private static double? AngleValue(string token)
    {
        var match = AngleRe.Match(token);
        if (!match.Success ||
            !double.TryParse(
                match.Groups[1].Value,
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out var value))
        {
            return null;
        }

        var degrees = match.Groups[2].Value.ToLowerInvariant() switch
        {
            "turn" => value * 360,
            "rad" => value * 180 / Math.PI,
            _ => value,
        };
        return ((degrees % 360) + 360) % 360;
    }

    private static double? PositionValue(string token)
    {
        var value = token.Trim().ToLowerInvariant();
        if (PositionKeywords.TryGetValue(value, out var keyword))
        {
            return keyword;
        }

        if (!value.EndsWith('%') ||
            !double.TryParse(
                value[..^1],
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out var percent))
        {
            return null;
        }

        return Math.Clamp(percent / 100, 0, 1);
    }

    /// <summary>`circle at 30% 70%` → (0.3, 0.7)；单值 top/bottom 定 y，其余定 x。</summary>
    private static (double X, double Y)? RadialCenter(string head)
    {
        var parts = Regex.Split(head, @"\bat\b", RegexOptions.IgnoreCase);
        if (parts.Length < 2)
        {
            return null;
        }

        var tokens = parts[1].Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (tokens.Length == 0)
        {
            return null;
        }

        if (tokens.Length == 1)
        {
            var token = tokens[0].ToLowerInvariant();
            if (PositionValue(token) is not { } single)
            {
                return null;
            }

            return token is "top" or "bottom" ? (.5, single) : (single, .5);
        }

        if (PositionValue(tokens[0]) is not { } x || PositionValue(tokens[1]) is not { } y)
        {
            return null;
        }

        return (x, y);
    }

    /// <summary>
    /// 按绘制尺寸生成渐变画刷（节点填充与文字渐变共用）；单色标复制成两份，
    /// 色标按 offset 排序（CSS 允许乱序书写）。几何与手机 `DesignGradientBrush` 同口径。
    /// </summary>
    private static IBrush BuildGradientBrush(GradientSpec gradient, double w, double h)
    {
        if (w <= 0 || h <= 0)
        {
            return new ImmutableSolidColorBrush(gradient.Stops[0].Color);
        }

        var stops = gradient.Stops.Count == 1
            ? new[] { gradient.Stops[0], gradient.Stops[0] }
            : gradient.Stops.OrderBy(stop => stop.Offset).ToArray();
        var collection = new GradientStops();
        foreach (var stop in stops)
        {
            collection.Add(new GradientStop(stop.Color, stop.Offset));
        }

        if (gradient.Kind == "radial")
        {
            var cx = w * gradient.Cx;
            var cy = h * gradient.Cy;
            var radius = Math.Max(
                Math.Max(
                    Math.Sqrt(cx * cx + cy * cy),
                    Math.Sqrt((w - cx) * (w - cx) + cy * cy)),
                Math.Max(
                    Math.Sqrt(cx * cx + (h - cy) * (h - cy)),
                    Math.Sqrt((w - cx) * (w - cx) + (h - cy) * (h - cy))));
            radius = Math.Max(radius, .01);
            return new RadialGradientBrush
            {
                Center = new RelativePoint(gradient.Cx, gradient.Cy, RelativeUnit.Relative),
                GradientOrigin = new RelativePoint(gradient.Cx, gradient.Cy, RelativeUnit.Relative),
                RadiusX = new RelativeScalar(radius / w, RelativeUnit.Relative),
                RadiusY = new RelativeScalar(radius / h, RelativeUnit.Relative),
                GradientStops = collection,
            };
        }

        // CSS 渐变线过中心、按角度方向；长度 = |w·sin| + |h·cos|。
        var radians = gradient.Angle * Math.PI / 180;
        var dx = Math.Sin(radians);
        var dy = -Math.Cos(radians);
        var half = (Math.Abs(w * dx) + Math.Abs(h * dy)) / 2;
        return new LinearGradientBrush
        {
            StartPoint = new RelativePoint(.5 - dx * half / w, .5 - dy * half / h, RelativeUnit.Relative),
            EndPoint = new RelativePoint(.5 + dx * half / w, .5 + dy * half / h, RelativeUnit.Relative),
            GradientStops = collection,
        };
    }

    private static TextDecorationCollection? DecorationOf(DesignNode node)
    {
        var parts = new List<string>(2);
        if (node.Underline == true)
        {
            parts.Add("underline");
        }

        if (node.Strikethrough == true)
        {
            parts.Add("strikethrough");
        }

        return parts.Count == 0 ? null : TextDecorationCollection.Parse(string.Join(" ", parts));
    }

    /// <summary>排版缓存键：影响 TextLayout 结果的全部输入（含渐变原文与盒宽）。</summary>
    private readonly record struct TextKey(
        string Text,
        string Family,
        FontStyle Style,
        FontWeight Weight,
        Color Color,
        TextAlignment Align,
        bool Wrap,
        double Size,
        double LineHeight,
        double LetterSpacing,
        int MaxLines,
        bool Underline,
        bool Strikethrough,
        double Width,
        string Gradient);

    private sealed record TextEntry(TextLayout Solid, TextLayout? Gradient);

    private readonly record struct MeasureKey(
        string Text,
        string Family,
        FontStyle Style,
        FontWeight Weight,
        double Size,
        double LineHeight,
        double LetterSpacing);

    private static List<string> NavItems(DesignNode node)
    {
        var items = (node.Items ?? new List<string>())
            .Where(item => !string.IsNullOrWhiteSpace(item))
            .ToList();
        return items.Count > 0 ? items : new List<string> { "首页", "任务", "我的" };
    }

    private static void DrawNav(
        DrawingContext context,
        DesignNode node,
        double x,
        double y,
        double w,
        double h)
    {
        var items = NavItems(node);

        var size = node.FontSize ?? DefaultNavFontSize;
        if (size <= 0)
        {
            return;
        }

        var active = Math.Clamp(node.Active ?? 0, 0, items.Count - 1);
        var cell = Math.Max(w / items.Count, 1);
        var barBrush = new ImmutableSolidColorBrush(WithAlpha(Accent, .9));

        for (var i = 0; i < items.Count; i++)
        {
            var on = i == active;
            var layout = new TextLayout(
                items[i],
                new Typeface(FontFamily.Default, FontStyle.Normal, (FontWeight)(ushort)(on ? 600 : 400)),
                size,
                new ImmutableSolidColorBrush(on ? Accent : WithAlpha(Ink, .42)),
                TextAlignment.Center,
                TextWrapping.NoWrap,
                TextTrimming.CharacterEllipsis,
                null,
                FlowDirection.LeftToRight,
                cell,
                double.PositiveInfinity,
                size * 1.2,
                0,
                1,
                null);

            var left = x + cell * i;
            var top = y + (h - layout.Height) / 2 - 2;
            using (context.PushClip(new Rect(left, y, cell, h)))
            {
                layout.Draw(context, new Point(left, top));
            }

            if (!on)
            {
                continue;
            }

            // `.nav-cell i{bottom:4px;width:12px;height:2px}` → 距底 6px 起画 2px 高。
            context.DrawRectangle(
                barBrush,
                null,
                new RoundedRect(new Rect(left + cell / 2 - 6, y + h - 6, 12, 2), 1));
        }
    }

    private static void DrawSwitch(
        DrawingContext context,
        DesignNode node,
        double x,
        double y,
        double w,
        double h,
        bool ellipse,
        Corners corners)
    {
        var on = node.On == true;
        var body = ShapeGeometry(ellipse, corners, x, y, w, h, 0);
        if (body is not null)
        {
            context.DrawGeometry(
                new ImmutableSolidColorBrush(on ? SingleColor(node.Fill, Accent) : SwitchOff),
                null,
                body);
        }

        var knob = Math.Max(h - 6, 1);
        var centerX = on ? x + w - 3 - knob / 2 : x + 3 + knob / 2;
        var centerY = y + h / 2;
        context.DrawEllipse(
            new ImmutableSolidColorBrush(Color.FromArgb(46, 0, 0, 0)),
            null,
            new Rect(centerX - knob / 2, centerY + 1 - knob / 2, knob, knob));
        context.DrawEllipse(
            Brushes.White,
            null,
            new Rect(centerX - knob / 2, centerY - knob / 2, knob, knob));
    }

    private static void DrawSlider(
        DrawingContext context,
        DesignNode node,
        double x,
        double y,
        double w,
        double h)
    {
        const double barHeight = 4;
        var value = Math.Clamp(node.Value ?? .5, 0, 1);
        var fill = SingleColor(node.Fill, Accent);
        var track = SingleColor(node.Track, SwitchOff);
        var centerY = y + h / 2;

        context.DrawRectangle(
            new ImmutableSolidColorBrush(track),
            null,
            new RoundedRect(new Rect(x, centerY - barHeight / 2, w, barHeight), barHeight / 2));

        if (value > 0)
        {
            context.DrawRectangle(
                new ImmutableSolidColorBrush(fill),
                null,
                new RoundedRect(new Rect(x, centerY - barHeight / 2, w * value, barHeight), barHeight / 2));
        }

        var thumbX = x + w * value;
        context.DrawEllipse(Brushes.White, null, new Rect(thumbX - 8, centerY - 8, 16, 16));
        // Web 用 `rgba(fill, .22)` 直接覆盖透明度（不与填充自身 alpha 相乘）。
        context.DrawEllipse(
            new ImmutableSolidColorBrush(Color.FromArgb(56, fill.R, fill.G, fill.B)),
            null,
            new Rect(thumbX - 10, centerY - 10, 20, 20));
    }

    private static string TypeOf(DesignNode node)
    {
        var type = (node.Type ?? "").Trim().ToLowerInvariant();
        return type.Length == 0 ? "text" : type;
    }

    private static double DefaultRadius(string type, double w, double h) => type switch
    {
        "button" or "pill" or "input" or "slider" or "switch" or "progress" or "badge" or "chip" => h / 2,
        "card" or "nav" => 18,
        "circle" or "avatar" => w / 2,
        _ => 0,
    };

    private static Color DefaultFill(string type) => type switch
    {
        "button" or "switch" or "slider" or "progress" or "badge" => Accent,
        "pill" or "decor" or "chip" or "avatar" => WithAlpha(Accent, .12),
        "card" or "nav" => Colors.White,
        "input" => InputFill,
        _ => WithAlpha(Ink, .08),
    };

    private static Color DefaultTextColor(string type) => type switch
    {
        "badge" => Colors.White,
        "chip" or "avatar" => Accent,
        _ => Ink,
    };

    /// <summary>本机已安装的字族名（大小写不敏感）；取不到时返回空集，调用方按首个候选硬取。</summary>
    private static readonly Lazy<HashSet<string>> InstalledFamilies = new(() =>
    {
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var family in FontManager.Current.SystemFonts)
            {
                names.Add(family.Name);
            }
        }
        catch
        {
            // 拿不到系统字体表时退化成「按候选名直接取」，由 Avalonia 自己做回退。
        }

        return names;
    });

    /// <summary>字族别名归一，与后端 `design_compile.font_family` 逐条一致。</summary>
    private static string NormalizeFamily(string? raw)
    {
        var value = Regex.Replace((raw ?? "").Trim().ToLowerInvariant(), @"[\s_]+", "-");
        return FamilyAliases.TryGetValue(value, out var normalized) ? normalized : "system";
    }

    /// <summary>
    /// 枚举 → 本机第一个已安装的候选字体，与网页 `_FONT_STACKS` 的落点一致；
    /// 一个都没装时回退系统默认字族。
    /// </summary>
    private static FontFamily FontFamilyFor(DesignNode node)
    {
        var family = NormalizeFamily(node.FontFamily);
        if (FamilyCache.TryGetValue(family, out var cached))
        {
            return cached;
        }

        var resolved = FontFamily.Default;
        if (FontStacks.TryGetValue(family, out var stack))
        {
            var installed = InstalledFamilies.Value;
            foreach (var name in stack)
            {
                if (installed.Count == 0 || installed.Contains(name))
                {
                    resolved = new FontFamily(name);
                    break;
                }
            }
        }

        FamilyCache[family] = resolved;
        return resolved;
    }

    /// <summary>单行不限宽量宽，供 autoFit 判断是否放得下。</summary>
    private static double MeasureLine(
        string text,
        Typeface typeface,
        IBrush brush,
        TextAlignment alignment,
        double size,
        double lineHeight,
        double letterSpacing)
    {
        var key = new MeasureKey(
            text,
            typeface.FontFamily.Name,
            typeface.Style,
            typeface.Weight,
            size,
            lineHeight,
            letterSpacing);
        if (MeasureCache.TryGetValue(key, out var cached))
        {
            return cached;
        }

        var probe = new TextLayout(
            text,
            typeface,
            size,
            brush,
            alignment,
            TextWrapping.NoWrap,
            TextTrimming.None,
            null,
            FlowDirection.LeftToRight,
            1e6,
            double.PositiveInfinity,
            size * lineHeight,
            letterSpacing,
            1,
            null);

        if (MeasureCache.Count >= MeasureCacheLimit)
        {
            MeasureCache.Clear();
        }

        MeasureCache[key] = probe.Width;
        return probe.Width;
    }

    /// <summary>
    /// autoFit：基准字号不够时按 0.5 步长缩，最低 60%，与网页 `fitTexts()` 完全同口径。
    /// </summary>
    private static double FitSize(
        string text,
        Typeface typeface,
        IBrush brush,
        TextAlignment alignment,
        double boxWidth,
        double lineHeight,
        double letterSpacing,
        double baseSize)
    {
        var floor = baseSize * AutoFitMinScale;
        var size = baseSize;
        while (size > floor)
        {
            if (MeasureLine(text, typeface, brush, alignment, size, lineHeight, letterSpacing) <= boxWidth + .5)
            {
                break;
            }

            size -= AutoFitStep;
        }

        return Math.Max(size, floor);
    }

    /// <summary>填充画刷：CSS 渐变按真实尺寸生成，否则单色（解析失败时取最后一个色标）。</summary>
    private static IBrush FillBrush(string? raw, string type, double w, double h)
    {
        var fallback = DefaultFill(type);
        return ParseGradient(raw) is { } gradient
            ? BuildGradientBrush(gradient, w, h)
            : new ImmutableSolidColorBrush(SingleColor(raw, fallback));
    }

    // ---------- 颜色 ----------

    /// <summary>等价于后端 `_single_color`：解析失败且是渐变时取最后一个色标。</summary>
    private static Color SingleColor(string? raw, Color fallback)
    {
        var value = raw?.Trim();
        if (string.IsNullOrEmpty(value))
        {
            return fallback;
        }

        if (ParseColor(value) is { } parsed)
        {
            return parsed;
        }

        if (value.Contains("gradient", StringComparison.OrdinalIgnoreCase))
        {
            var tokens = ColorTokens(value);
            if (tokens.Count > 0)
            {
                return SingleColor(tokens[^1], fallback);
            }
        }

        return fallback;
    }

    private static List<string> ColorTokens(string value) =>
        TokenRe.Matches(value).Select(match => match.Value).ToList();

    private static Color WithAlpha(Color color, double factor) =>
        Color.FromArgb((byte)Math.Round(color.A * Math.Clamp(factor, 0, 1)), color.R, color.G, color.B);

    /// <summary>`none` / `transparent` 作为填充表示「不画底色」（与后端 `compile_fill` 一致）。</summary>
    private static bool IsNoneFill(string? raw)
    {
        var value = raw?.Trim().ToLowerInvariant();
        return value is "none" or "transparent";
    }

    /// <summary>
    /// 旧设计稿把文字色存在 `fill`（没有 `color`）。对齐 Web `paint.js`：
    /// 仅 text 节点、`color` 为空、`fill` 是纯色时成立；渐变仍按底色处理。
    /// </summary>
    private static Color? LegacyTextInk(DesignNode node, string type)
    {
        if (type != "text" || !string.IsNullOrWhiteSpace(node.Color) || IsNoneFill(node.Fill))
        {
            return null;
        }

        return ParseColor(node.Fill);
    }

    /// <summary>
    /// 支持 `#rgb` / `#rgba` / `#rrggbb` / `#aarrggbb`（Android ARGB）、`rgb()` / `rgba()` /
    /// `hsl()` / `hsla()`、CSS 具名色 —— 与后端 `design_tokens.parse_color` 同规则。
    /// </summary>
    private static Color? ParseColor(string? css)
    {
        var value = css?.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(value))
        {
            return null;
        }

        if (Named.TryGetValue(value, out var named))
        {
            value = named;
        }

        var hex = HexRe.Match(value);
        if (hex.Success)
        {
            var digits = hex.Groups[1].Value;
            if (digits.Length is 3 or 4)
            {
                digits = string.Concat(digits.Select(ch => new string(ch, 2)));
            }

            return digits.Length switch
            {
                8 => Color.FromArgb(
                    HexByte(digits, 0), HexByte(digits, 2), HexByte(digits, 4), HexByte(digits, 6)),
                6 => Color.FromRgb(HexByte(digits, 0), HexByte(digits, 2), HexByte(digits, 4)),
                _ => null,
            };
        }

        var rgb = RgbRe.Match(value);
        if (rgb.Success)
        {
            var parts = SplitArgs(rgb.Groups[1].Value);
            if (parts.Length < 3)
            {
                return null;
            }

            var r = Channel(parts[0], 255);
            var g = Channel(parts[1], 255);
            var b = Channel(parts[2], 255);
            if (r is null || g is null || b is null)
            {
                return null;
            }

            var alpha = 1d;
            if (parts.Length >= 4)
            {
                var parsed = AlphaChannel(parts[3]);
                if (parsed is null)
                {
                    return null;
                }

                alpha = parsed.Value;
            }

            return Color.FromArgb(
                (byte)Math.Round(alpha * 255),
                (byte)Math.Round(r.Value),
                (byte)Math.Round(g.Value),
                (byte)Math.Round(b.Value));
        }

        var hsl = HslRe.Match(value);
        if (!hsl.Success)
        {
            return null;
        }

        var hslParts = SplitArgs(hsl.Groups[1].Value);
        if (hslParts.Length < 3)
        {
            return null;
        }

        if (!double.TryParse(
                hslParts[0].TrimEnd('d', 'e', 'g'),
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out var hue))
        {
            return null;
        }

        var saturation = Channel(hslParts[1], 1);
        var lightness = Channel(hslParts[2], 1);
        if (saturation is null || lightness is null)
        {
            return null;
        }

        var hslAlpha = 1d;
        if (hslParts.Length >= 4)
        {
            var parsed = AlphaChannel(hslParts[3]);
            if (parsed is null)
            {
                return null;
            }

            hslAlpha = parsed.Value;
        }

        var (red, green, blue) = HslToRgb(hue, saturation.Value, lightness.Value);
        return Color.FromArgb((byte)Math.Round(hslAlpha * 255), (byte)red, (byte)green, (byte)blue);
    }

    private static string[] SplitArgs(string body) =>
        ArgSplitRe.Split(body.Trim()).Where(part => part.Length > 0).ToArray();

    /// <summary>颜色通道：`50%` 按比例换算，其余按 0..scale 截断（对齐后端 `_parse_channel`）。</summary>
    private static double? Channel(string token, double scale)
    {
        var value = token.Trim();
        if (value.EndsWith('%'))
        {
            return double.TryParse(
                value[..^1],
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out var percent)
                ? Math.Clamp(percent / 100 * scale, 0, scale)
                : null;
        }

        return double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
            ? Math.Clamp(number, 0, scale)
            : null;
    }

    /// <summary>透明度：`50%` 视作 0.5，其余按 0..1 截断。</summary>
    private static double? AlphaChannel(string token) => Channel(token, 1);

    /// <summary>HSL → RGB，与后端 `design_tokens._hsl_to_rgb` 同算法。</summary>
    private static (int R, int G, int B) HslToRgb(double hue, double saturation, double lightness)
    {
        var h = ((hue % 360) + 360) % 360 / 360;
        var s = Math.Clamp(saturation, 0, 1);
        var l = Math.Clamp(lightness, 0, 1);
        if (s == 0)
        {
            var gray = (int)Math.Round(l * 255);
            return (gray, gray, gray);
        }

        static double ChannelAt(double p, double q, double t)
        {
            t %= 1;
            if (t < 0)
            {
                t += 1;
            }

            if (t < 1.0 / 6)
            {
                return p + (q - p) * 6 * t;
            }

            if (t < 1.0 / 2)
            {
                return q;
            }

            if (t < 2.0 / 3)
            {
                return p + (q - p) * (2.0 / 3 - t) * 6;
            }

            return p;
        }

        var q = l < .5 ? l * (1 + s) : l + s - l * s;
        var p2 = 2 * l - q;
        return (
            (int)Math.Round(ChannelAt(p2, q, h + 1.0 / 3) * 255),
            (int)Math.Round(ChannelAt(p2, q, h) * 255),
            (int)Math.Round(ChannelAt(p2, q, h - 1.0 / 3) * 255));
    }

    private static byte HexByte(string digits, int offset) =>
        (byte)((HexNibble(digits[offset]) << 4) | HexNibble(digits[offset + 1]));

    private static int HexNibble(char ch) => ch switch
    {
        >= '0' and <= '9' => ch - '0',
        >= 'a' and <= 'f' => ch - 'a' + 10,
        >= 'A' and <= 'F' => ch - 'A' + 10,
        _ => 0,
    };
}
