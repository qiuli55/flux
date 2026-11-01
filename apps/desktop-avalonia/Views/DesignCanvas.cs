using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Avalonia.Media.TextFormatting;
using Flux.Desktop.Models;

namespace Flux.Desktop.Views;

/// <summary>
/// 设计稿画布（桌面端只读预览）。
///
/// 渲染规则与移动端 <c>DesignSpec.kt</c> / <c>DesignPainter.kt</c>、后端
/// <c>backend/flux/core/design_render.py</c> 完全一致：默认圆角、默认填充、文本排版、
/// 导航 / 开关 / 滑杆几何都按同一份契约来，三端看同一份 JSON 必须得到同一张图。
/// 想"调好看"请先改契约，不要只改这里。
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

    /// <summary>阴影环层数，与后端 `SHADOW_RINGS` / 手机 `SHADOW_RINGS` 一致。</summary>
    private const int ShadowRingCount = 10;

    /// <summary>发光/投影环：(半径倍率, 透明度倍率)，与后端 `FX_RINGS` / 手机 `DesignSpec.FX_RINGS` 一致。</summary>
    private static readonly (double Radius, double Alpha)[] FxRings =
    {
        (0.35, 0.55), (0.7, 0.35), (1.0, 0.18),
    };

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

    static DesignCanvas()
    {
        AffectsRender<DesignCanvas>(DocumentProperty, ScreenIndexProperty, PreviewProperty, IsThumbnailProperty);
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
        var nodes = ApplyDemoState(frame.Nodes);

        using (context.PushTransform(Matrix.CreateScale(frame.Scale, frame.Scale) * Matrix.CreateTranslation(frame.OffsetX, frame.OffsetY)))
        {
            if (!IsThumbnail)
            {
                DrawDeviceShadow(context, frame.Width, frame.Height, DeviceRadius);
            }

            using (context.PushClip(new RoundedRect(new Rect(0, 0, frame.Width, frame.Height), DeviceRadius)))
            {
                context.FillRectangle(new ImmutableSolidColorBrush(bg), new Rect(0, 0, frame.Width, frame.Height));
                foreach (var node in nodes)
                {
                    DrawNode(context, node, 0, 0, 1d);
                }
            }
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
            var screens = Document?.Screens;
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
            rawBg = Document?.Bg;
        }

        if (width <= 0 || height <= 0)
        {
            return null;
        }

        var padding = thumbnail ? 0d : 24d;
        var availableW = Math.Max(bounds.Width - padding * 2, 1);
        var availableH = Math.Max(bounds.Height - padding * 2, 1);
        var scale = Math.Min(availableW / width, availableH / height);
        var offsetX = (bounds.Width - width * scale) / 2;
        var offsetY = (bounds.Height - height * scale) / 2;
        return new CanvasFrame(width, height, nodes, scale, offsetX, offsetY, rawBg);
    }

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
        }
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (IsThumbnail || Document is null || ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        var position = e.GetPosition(this);
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

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        if (IsThumbnail || Document is null || ProbeLayout(Bounds) is not { } frame)
        {
            return;
        }

        var point = DesignPoint(e.GetPosition(this), frame);
        if (_dragSlider is { Id: { Length: > 0 } id } slider)
        {
            _sliders[id] = SliderValue(slider, point.X);
            InvalidateVisual();
            return;
        }

        UpdateCursor(HitTest(frame.Nodes, point, default, 1d));
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        var pressed = _pressedNode;
        _pressedNode = null;
        _dragSlider = null;
        e.Pointer.Capture(null);

        if (pressed is null || IsThumbnail || Document is null || ProbeLayout(Bounds) is not { } frame)
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

    /// <summary>设备投影 `0 26px 60px rgba(0,0,0,.55)`：用多层扩张圆角矩形近似高斯模糊。</summary>
    private static void DrawDeviceShadow(DrawingContext context, double width, double height, double radius)
    {
        const int layers = 10;
        for (var i = layers; i >= 1; i--)
        {
            var t = i / (double)layers;
            var expand = 34 * t;
            var alpha = (byte)Math.Round(140 * 3.0 / layers * (1 - t) * (1 - t));
            if (alpha == 0)
            {
                continue;
            }

            context.DrawRectangle(
                new ImmutableSolidColorBrush(Color.FromArgb(alpha, 0, 0, 0)),
                null,
                new RoundedRect(
                    new Rect(-expand, 26 - expand, width + expand * 2, height + expand * 2),
                    radius + expand));
        }
    }

    private static void DrawNode(
        DrawingContext context,
        DesignNode node,
        double originX,
        double originY,
        double parentAlpha)
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

        using (context.PushOpacity(opacity))
        using (context.PushTransform(matrix))
        {
            DrawNodeCore(context, node, type, x, y, w, h);

            if (node.Children is { Count: > 0 })
            {
                if (ClipTypes.Contains(type))
                {
                    // 网页里这类节点是 `overflow:hidden`，子层裁剪到父框圆角内。
                    var corners = CornersOf(node, type, w, h);
                    using (PushShapeClip(context, IsEllipse(type, w, h), corners, new Rect(x, y, w, h)))
                    {
                        foreach (var child in node.Children)
                        {
                            DrawNode(context, child, x, y, alpha);
                        }
                    }
                }
                else
                {
                    foreach (var child in node.Children)
                    {
                        DrawNode(context, child, x, y, alpha);
                    }
                }
            }
        }
    }

    private static void DrawNodeCore(
        DrawingContext context,
        DesignNode node,
        string type,
        double x,
        double y,
        double w,
        double h)
    {
        var ellipse = IsEllipse(type, w, h);
        var corners = CornersOf(node, type, w, h);

        // CSS 合成顺序：外阴影 → 背景 → 内阴影 → 内容/子节点 → 边框 → 文字。
        foreach (var ring in ShadowRings(node, type))
        {
            DrawShadowRing(context, ellipse, corners, x, y, w, h, ring);
        }

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
                var bareText = type == "text" && string.IsNullOrWhiteSpace(node.Fill);
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
            DrawNodeText(context, node, type, x, y, w, h);
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

    /// <summary>一个阴影环；`Spread` 是向外扩张（inset 时向内收）的距离。</summary>
    private readonly record struct ShadowRing(double Dx, double Dy, double Spread, Color Color, bool Inset);

    /// <summary>外阴影环（大 spread 在下、小 spread 在上）；card 无显式 shadow 时补默认投影。</summary>
    private static List<ShadowRing> ShadowRings(DesignNode node, string type)
    {
        if (node.Shadow is null && type == "card")
        {
            return new List<ShadowRing>
            {
                new(
                    0,
                    CardShadowOffset,
                    0,
                    Color.FromArgb((byte)Math.Round(CardShadowAlpha * 255), 0, 0, 0),
                    false),
            };
        }

        return ShadowRingLayers(node.Shadow, false).Where(ring => !ring.Inset).ToList();
    }

    /// <summary>内阴影环：`insetShadow` 恒在盒内，`shadow` 里 inset=true 的层也算内阴影。</summary>
    private static List<ShadowRing> InsetShadowRings(DesignNode node) =>
        ShadowRingLayers(node.Shadow, false)
            .Concat(ShadowRingLayers(node.InsetShadow, true))
            .Where(ring => ring.Inset)
            .ToList();

    /// <summary>
    /// blur&gt;0 时按 N=10 环近似：spread_i = blur × i / N，
    /// alpha_i = color.alpha × 3/N × (1 − i/N)²；blur=0 退化为单段。
    /// </summary>
    private static List<ShadowRing> ShadowRingLayers(DesignShadow? shadow, bool forceInset)
    {
        var rings = new List<ShadowRing>();
        if (shadow is null)
        {
            return rings;
        }

        var baseColor = SingleColor(shadow.Color, DefaultShadow);
        var baseAlpha = baseColor.A / 255d;
        if (baseAlpha <= .004)
        {
            return rings;
        }

        var dx = shadow.Dx;
        var dy = shadow.Dy;
        var blur = Math.Max(shadow.Blur, 0);
        var inset = forceInset || shadow.Inset;
        if (blur <= .0001)
        {
            rings.Add(new ShadowRing(dx, dy, 0, baseColor, inset));
            return rings;
        }

        for (var index = ShadowRingCount; index >= 1; index--)
        {
            var ratio = index / (double)ShadowRingCount;
            var alpha = baseAlpha * 3d / ShadowRingCount * (1 - ratio) * (1 - ratio);
            if (alpha <= .004)
            {
                continue;
            }

            rings.Add(
                new ShadowRing(
                    dx,
                    dy,
                    blur * ratio,
                    Color.FromArgb((byte)Math.Round(alpha * 255), baseColor.R, baseColor.G, baseColor.B),
                    inset));
        }

        return rings;
    }

    /// <summary>外阴影环：环已按「大 spread 在下、小 spread 在上」排好序。</summary>
    private static void DrawShadowRing(
        DrawingContext context,
        bool ellipse,
        Corners corners,
        double x,
        double y,
        double w,
        double h,
        ShadowRing ring)
    {
        var left = x - ring.Spread + ring.Dx;
        var top = y - ring.Spread + ring.Dy;
        var width = w + ring.Spread * 2;
        var height = h + ring.Spread * 2;
        var geometry = ShapeGeometry(ellipse, corners, left, top, width, height, ring.Spread);
        if (geometry is null)
        {
            return;
        }

        context.DrawGeometry(new ImmutableSolidColorBrush(ring.Color), null, geometry);
    }

    /// <summary>内阴影：外框减去「内缩 spread 并偏移 (dx, dy)」的洞，得到一圈贴边的阴影。</summary>
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
        var rings = InsetShadowRings(node);
        if (rings.Count == 0)
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
            foreach (var ring in rings)
            {
                var inset = ring.Spread;
                var hole = ShapeGeometry(
                    ellipse,
                    corners,
                    x + inset + ring.Dx,
                    y + inset + ring.Dy,
                    w - inset * 2,
                    h - inset * 2,
                    -inset);
                var brush = new ImmutableSolidColorBrush(ring.Color);
                if (hole is null)
                {
                    context.DrawGeometry(brush, null, outer);
                }
                else
                {
                    context.DrawGeometry(
                        brush,
                        null,
                        new CombinedGeometry(GeometryCombineMode.Exclude, outer, hole));
                }
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
        double h)
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

        var color = SingleColor(node.Color, DefaultTextColor(type));
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
        var origin = new Point(x + inset, top);
        var rings = TextRingsOf(node);
        var stroke = TextStrokeOf(node);

        using (context.PushClip(new Rect(x, y, w, h)))
        {
            // Web 的 `input::placeholder{opacity:.5}`：透明度只在绘制期乘一次，
            // 不并进颜色，避免与节点自身 alpha 相乘两次。
            var alpha = type == "input" ? InputPlaceholderAlpha : 1d;

            void Paint()
            {
                if (rings.Count == 0 && stroke == null)
                {
                    layout.Draw(context, origin);
                    return;
                }

                // 与手机端 `drawTextNode` 同序：投影环 → 发光环 → 描边 → 填充。
                var geometry = TextGeometry(text, typeface, size, alignment, boxWidth, ratio, node, autoFit, decorations, origin);
                if (geometry != null)
                {
                    foreach (var ring in rings)
                    {
                        DrawTextRing(context, geometry, ring);
                    }

                    if (stroke is { } line)
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
    /// 文字环：投影整组在下、发光整组在上，每组按半径从大到小画。
    /// 顺序与手机端 `DesignSpec.FX_RINGS.asReversed()` 完全一致。
    /// </summary>
    private static List<TextRing> TextRingsOf(DesignNode node) =>
        RingsOf(node.TextShadow, false).Concat(RingsOf(node.TextGlow, true)).ToList();

    private static List<TextRing> RingsOf(DesignTextEffect? effect, bool glow)
    {
        var rings = new List<TextRing>();
        if (effect == null || ParseColor(effect.Color) is not { } baseColor)
        {
            // 颜色解析失败整段丢弃，对齐编译层 `_compile_fx`。
            return rings;
        }

        var baseAlpha = baseColor.A / 255d;
        var intensity = Math.Clamp(effect.Intensity ?? 1, 0, 1);
        // blur 缺省时回退到 radius（设计稿里发光习惯写 radius），与 `_compile_fx` 一致。
        var blur = Math.Max(effect.Blur ?? effect.Radius ?? 0, 0);
        var dx = glow ? 0 : effect.Dx ?? 0;
        var dy = glow ? 0 : effect.Dy ?? 0;
        for (var i = FxRings.Length - 1; i >= 0; i--)
        {
            var (radius, ringAlpha) = FxRings[i];
            var alpha = Math.Clamp(baseAlpha * intensity * ringAlpha, 0, 1);
            if (alpha <= .004)
            {
                continue;
            }

            var color = Color.FromArgb((byte)Math.Round(alpha * 255), baseColor.R, baseColor.G, baseColor.B);
            rings.Add(new TextRing(dx, dy, blur * radius, color));
        }

        return rings;
    }

    private static void DrawTextRing(DrawingContext context, Geometry geometry, TextRing ring)
    {
        var brush = new ImmutableSolidColorBrush(ring.Color);
        using (context.PushTransform(Matrix.CreateTranslation(ring.Dx, ring.Dy)))
        {
            if (ring.Spread <= 0)
            {
                // blur=0：网页是硬阴影（同形实心副本），直接填充字形轮廓。
                context.DrawGeometry(brush, null, geometry);
            }
            else
            {
                // 环外扩 spread：居中描边的线宽 = 2×spread，内侧一半随后被填充盖住。
                context.DrawGeometry(null, new ImmutablePen(brush, ring.Spread * 2), geometry);
            }
        }
    }

    /// <summary>
    /// 字形轮廓：网页没有高斯模糊，三端统一用「轮廓环」近似，所以描边/发光都基于几何。
    /// `FormattedText` 不支持 letterSpacing，带字距的艺术字环会有微小水平偏差（已知限制）。
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

    private readonly record struct TextRing(double Dx, double Dy, double Spread, Color Color);

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
