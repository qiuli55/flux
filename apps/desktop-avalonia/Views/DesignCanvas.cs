using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Flux.Desktop.Models;

namespace Flux.Desktop.Views;

/// <summary>
/// 设计稿画布。按 Luma Web 端 app.js 的渲染语义逐节点绘制：
/// 节点绝对定位、卡片/圆形/文字/按钮/导航/开关/滑杆各自特化。
/// 只读展示，不参与编辑。
/// </summary>
public sealed class DesignCanvas : Control
{
    private static readonly Color ScreenBg = Color.Parse("#0B0F14");
    private static readonly IBrush ScreenBgBrush = new ImmutableSolidColorBrush(ScreenBg);
    private static readonly Color TextColor = Color.Parse("#EEF1F6");
    private static readonly Color NavActiveDot = Color.Parse("#6EE7B7");
    private static readonly Color NavIdleDot = Color.Parse("#2A3340");
    private static readonly Color NavActiveText = Color.Parse("#8CF3C9");
    private static readonly Color NavIdleText = Color.Parse("#66707F");
    private static readonly Color SwitchOn = Color.Parse("#5FD3B0");
    private static readonly Color SwitchOff = Color.Parse("#232B36");
    private static readonly Color Knob = Color.Parse("#F4FAF7");
    private static readonly Color TrackColor = Color.Parse("#1B242E");

    private static readonly BoxShadows KnobShadow = new(new BoxShadow
    {
        OffsetX = 0,
        OffsetY = 2,
        Blur = 8,
        Spread = 0,
        Color = Color.FromArgb(115, 0, 0, 0),
    });

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
        if (bounds.Width <= 0 || bounds.Height <= 0)
        {
            return;
        }

        double width;
        double height;
        List<DesignNode> nodes;
        var thumbnail = IsThumbnail;

        if (thumbnail)
        {
            width = Preview?.W is > 0 ? Preview!.W!.Value : 390;
            height = Preview?.H is > 0 ? Preview!.H!.Value : 844;
            nodes = Preview?.Nodes ?? new List<DesignNode>();
        }
        else
        {
            var screens = Document?.Screens;
            var index = ScreenIndex;
            if (screens is null || index < 0 || index >= screens.Count)
            {
                return;
            }

            var screen = screens[index];
            width = screen.W is > 0 ? screen.W!.Value : 390;
            height = screen.H is > 0 ? screen.H!.Value : 844;
            nodes = screen.Nodes ?? new List<DesignNode>();
        }

        if (width <= 0 || height <= 0)
        {
            return;
        }

        var padding = thumbnail ? 0d : 24d;
        var availableW = Math.Max(bounds.Width - padding * 2, 1);
        var availableH = Math.Max(bounds.Height - padding * 2, 1);
        var scale = Math.Min(availableW / width, availableH / height);
        var offsetX = (bounds.Width - width * scale) / 2;
        var offsetY = (bounds.Height - height * scale) / 2;
        var radius = thumbnail ? 8d : 26d;

        using (context.PushTransform(Matrix.CreateScale(scale, scale) * Matrix.CreateTranslation(offsetX, offsetY)))
        using (context.PushClip(new RoundedRect(new Rect(0, 0, width, height), radius)))
        {
            context.FillRectangle(ScreenBgBrush, new Rect(0, 0, width, height));
            foreach (var node in nodes)
            {
                DrawNode(context, node, 0, 0, TextColor, width, height);
            }

            // 屏幕内描边，对应 Web 端 .screen::after 的 inset 1px 高光
            context.DrawRectangle(
                null,
                new Pen(new ImmutableSolidColorBrush(Color.FromArgb(13, 255, 255, 255)), 1),
                new RoundedRect(new Rect(0.5, 0.5, width - 1, height - 1), radius));
        }
    }

    private static void DrawNode(
        DrawingContext context,
        DesignNode node,
        double originX,
        double originY,
        Color inheritedColor,
        double parentW,
        double parentH)
    {
        if (node.Hidden == true)
        {
            return;
        }

        var type = (node.Type ?? "").Trim().ToLowerInvariant();
        var x = originX + (node.X ?? 0);
        var y = originY + (node.Y ?? 0);
        var w = Math.Max(node.W ?? 0, 0);
        var h = Math.Max(node.H ?? 0, 0);
        var color = ParseColor(node.Color) ?? inheritedColor;
        var opacity = node.Opacity is { } value ? Math.Clamp(value, 0, 1) : 1d;

        if (opacity < 1)
        {
            using (context.PushOpacity(opacity))
            {
                DrawNodeCore(context, node, type, x, y, w, h, color, parentW, parentH);
            }
        }
        else
        {
            DrawNodeCore(context, node, type, x, y, w, h, color, parentW, parentH);
        }
    }

    private static void DrawNodeCore(
        DrawingContext context,
        DesignNode node,
        string type,
        double x,
        double y,
        double w,
        double h,
        Color color,
        double parentW,
        double parentH)
    {
        var radius = type == "circle" ? Math.Min(w, h) / 2 : node.Radius ?? 0;
        var rect = new RoundedRect(new Rect(x, y, w, h), radius);
        var fill = type == "slider" ? null : ParseBrush(node.Fill);
        if (type == "switch")
        {
            fill = new ImmutableSolidColorBrush(node.On == true ? SwitchOn : SwitchOff);
        }

        var strokeBrush = ParseBrush(node.Stroke);
        var pen = strokeBrush is null ? null : new Pen(strokeBrush, node.Sw ?? 1);
        var shadows = ParseShadows(node.Shadow);

        if (fill is not null || pen is not null || shadows is not null)
        {
            context.DrawRectangle(fill, pen, rect, shadows ?? default);
        }

        switch (type)
        {
            case "text":
                DrawText(context, node, x, y, w, color, TextAlignment.Left, 0, null);
                break;
            case "button":
            case "pill":
                DrawText(context, node, x, y, w, color, TextAlignment.Center, 0, h);
                break;
            case "input":
                var padLeft = node.PadLeft ?? 16;
                DrawText(context, node, x, y, Math.Max(w - padLeft, 1), color, TextAlignment.Left, padLeft, h);
                break;
            case "nav":
                DrawNav(context, node, x, y, w, h);
                break;
            case "switch":
                DrawSwitch(context, x, y, w, h);
                break;
            case "slider":
                DrawSlider(context, node, x, y, w, h);
                break;
        }

        if (node.Children is not { Count: > 0 })
        {
            return;
        }

        var padX = type switch
        {
            "nav" => 8,
            "switch" => 2,
            _ => 0,
        };
        var padY = type == "switch" ? 2 : 0;
        _ = parentW;
        _ = parentH;

        foreach (var child in node.Children)
        {
            DrawNode(context, child, x + padX, y + padY, color, w, h);
        }
    }

    private static void DrawText(
        DrawingContext context,
        DesignNode node,
        double x,
        double y,
        double width,
        Color color,
        TextAlignment alignment,
        double leftInset,
        double? boxHeight)
    {
        var text = node.Text ?? "";
        if (text.Length == 0)
        {
            return;
        }

        var fontSize = node.FontSize is > 0 ? node.FontSize!.Value : 16;
        var lineHeight = node.LineHeight is > 0 ? node.LineHeight!.Value : 1.4;
        var formatted = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            new Typeface(FontFamily.Default, FontStyle.Normal, MapWeight(node.FontWeight)),
            fontSize,
            new ImmutableSolidColorBrush(color))
        {
            MaxTextWidth = Math.Max(width, 1),
            LineHeight = fontSize * lineHeight,
            TextAlignment = alignment,
        };

        var top = y;
        if (boxHeight is { } height && height > 0)
        {
            top = y + Math.Max((height - formatted.Height) / 2, 0);
        }

        context.DrawText(formatted, new Point(x + leftInset, top));
    }

    private static void DrawNav(DrawingContext context, DesignNode node, double x, double y, double w, double h)
    {
        var items = node.Items;
        if (items is null || items.Count == 0)
        {
            return;
        }

        var contentW = Math.Max(w - 16, 1);
        var itemW = contentW / items.Count;
        var active = node.Active ?? -1;
        var textHeight = 9.5 * 1.4;
        var totalH = 6 + 5 + textHeight;
        var top = y + Math.Max((h - totalH) / 2, 0);

        for (var i = 0; i < items.Count; i++)
        {
            var centerX = x + 8 + itemW * (i + 0.5);
            var isActive = i == active;
            var dotW = isActive ? 18d : 6d;
            context.DrawRectangle(
                new ImmutableSolidColorBrush(isActive ? NavActiveDot : NavIdleDot),
                null,
                new RoundedRect(new Rect(centerX - dotW / 2, top, dotW, 6), 3));

            var label = new FormattedText(
                items[i] ?? "",
                CultureInfo.CurrentCulture,
                FlowDirection.LeftToRight,
                new Typeface(FontFamily.Default, FontStyle.Normal, FontWeight.Normal),
                9.5,
                new ImmutableSolidColorBrush(isActive ? NavActiveText : NavIdleText))
            {
                MaxTextWidth = Math.Max(itemW, 1),
                TextAlignment = TextAlignment.Center,
            };
            context.DrawText(label, new Point(centerX - itemW / 2, top + 11));
        }
    }

    private static void DrawSwitch(DrawingContext context, double x, double y, double w, double h)
    {
        const double knobSize = 22;
        var knobX = Math.Max(w - knobSize - 2, 2);
        context.DrawRectangle(
            new ImmutableSolidColorBrush(Knob),
            null,
            new RoundedRect(new Rect(x + knobX, y + (h - knobSize) / 2, knobSize, knobSize), knobSize / 2),
            KnobShadow);
    }

    private static void DrawSlider(DrawingContext context, DesignNode node, double x, double y, double w, double h)
    {
        var value = Math.Clamp(node.Value ?? 0, 0, 1);
        var trackY = y + h / 2 - 3;
        var trackBrush = ParseBrush(node.Track) ?? new ImmutableSolidColorBrush(TrackColor);
        context.DrawRectangle(trackBrush, null, new RoundedRect(new Rect(x, trackY, w, 6), 3));

        if (value > 0)
        {
            var fillBrush = ParseBrush(node.Fill) ?? new ImmutableSolidColorBrush(NavActiveDot);
            context.DrawRectangle(fillBrush, null, new RoundedRect(new Rect(x, trackY, w * value, 6), 3));
        }

        context.DrawRectangle(
            new ImmutableSolidColorBrush(Knob),
            null,
            new RoundedRect(new Rect(x + w * value - 9, y + (h - 18) / 2, 18, 18), 9),
            KnobShadow);
    }

    private static FontWeight MapWeight(double? weight) => weight switch
    {
        >= 700 => FontWeight.Bold,
        >= 600 => FontWeight.SemiBold,
        >= 500 => FontWeight.Medium,
        _ => FontWeight.Normal,
    };

    // ---------- CSS 解析 ----------

    private static IBrush? ParseBrush(string? css)
    {
        if (string.IsNullOrWhiteSpace(css))
        {
            return null;
        }

        var value = css.Trim();
        if (value.Equals("transparent", StringComparison.OrdinalIgnoreCase)
            || value.Equals("none", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        if (value.StartsWith("linear-gradient", StringComparison.OrdinalIgnoreCase))
        {
            return ParseLinear(value);
        }

        if (value.StartsWith("radial-gradient", StringComparison.OrdinalIgnoreCase))
        {
            return ParseRadial(value);
        }

        var color = ParseColor(value);
        return color is null ? null : new ImmutableSolidColorBrush(color.Value);
    }

    private static IBrush? ParseLinear(string css)
    {
        var parts = Arguments(css);
        if (parts.Count < 2)
        {
            return null;
        }

        var start = 0;
        var degrees = 180d;
        var head = parts[0].Trim();
        if (head.EndsWith("deg", StringComparison.OrdinalIgnoreCase)
            && double.TryParse(head[..^3], NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed))
        {
            degrees = parsed;
            start = 1;
        }
        else if (head.StartsWith("to ", StringComparison.OrdinalIgnoreCase))
        {
            degrees = Direction(head[3..]);
            start = 1;
        }

        var stops = ParseStops(parts.Skip(start));
        if (stops.Count < 2)
        {
            return null;
        }

        var radians = degrees * Math.PI / 180;
        var dx = Math.Sin(radians);
        var dy = -Math.Cos(radians);
        return new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0.5 - dx * 0.5, 0.5 - dy * 0.5, RelativeUnit.Relative),
            EndPoint = new RelativePoint(0.5 + dx * 0.5, 0.5 + dy * 0.5, RelativeUnit.Relative),
            GradientStops = ToStops(stops),
        };
    }

    private static GradientStops ToStops(IEnumerable<GradientStop> stops)
    {
        var result = new GradientStops();
        foreach (var stop in stops)
        {
            result.Add(stop);
        }

        return result;
    }

    private static double Direction(string keywords)
    {
        var words = keywords.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var right = words.Contains("right");
        var left = words.Contains("left");
        var top = words.Contains("top");
        var bottom = words.Contains("bottom");
        if ((right && bottom) || (left && top))
        {
            return right ? 135 : 315;
        }

        if ((left && bottom) || (right && top))
        {
            return left ? 225 : 45;
        }

        if (right)
        {
            return 90;
        }

        if (left)
        {
            return 270;
        }

        return top ? 0 : 180;
    }

    private static IBrush? ParseRadial(string css)
    {
        var parts = Arguments(css);
        if (parts.Count < 2)
        {
            return null;
        }

        var start = 0;
        var centerX = 0.5;
        var centerY = 0.5;
        var head = parts[0].Trim();
        if (head.Contains("circle", StringComparison.OrdinalIgnoreCase)
            || head.Contains("ellipse", StringComparison.OrdinalIgnoreCase))
        {
            start = 1;
            var at = head.IndexOf(" at ", StringComparison.OrdinalIgnoreCase);
            if (at >= 0)
            {
                var coords = head[(at + 4)..].Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (coords.Length >= 2
                    && Percent(coords[0]) is { } cx
                    && Percent(coords[1]) is { } cy)
                {
                    centerX = cx;
                    centerY = cy;
                }
            }
        }

        var stops = ParseStops(parts.Skip(start));
        if (stops.Count < 2)
        {
            return null;
        }

        var center = new RelativePoint(centerX, centerY, RelativeUnit.Relative);
        return new RadialGradientBrush
        {
            Center = center,
            GradientOrigin = center,
            RadiusX = new RelativeScalar(0.7, RelativeUnit.Relative),
            RadiusY = new RelativeScalar(0.7, RelativeUnit.Relative),
            GradientStops = ToStops(stops),
        };
    }

    private static double? Percent(string token)
    {
        var text = token.Trim();
        if (text.EndsWith('%')
            && double.TryParse(text[..^1], NumberStyles.Float, CultureInfo.InvariantCulture, out var value))
        {
            return value / 100;
        }

        return null;
    }

    private static List<GradientStop> ParseStops(IEnumerable<string> parts)
    {
        var colors = new List<Color>();
        var offsets = new List<double?>();
        foreach (var part in parts)
        {
            var text = part.Trim();
            if (text.Length == 0)
            {
                continue;
            }

            var split = SplitColorAndPosition(text);
            var color = ParseColor(split.Color);
            if (color is null)
            {
                continue;
            }

            colors.Add(color.Value);
            offsets.Add(split.Position);
        }

        if (colors.Count < 2)
        {
            return new List<GradientStop>();
        }

        if (offsets[0] is null)
        {
            offsets[0] = 0;
        }

        for (var i = 1; i < offsets.Count; i++)
        {
            offsets[i] ??= offsets[i - 1];
        }

        if (offsets[0] > 1)
        {
            offsets[0] = 0;
        }

        var stops = new List<GradientStop>(colors.Count);
        for (var i = 0; i < colors.Count; i++)
        {
            var offset = Math.Clamp(offsets[i]!.Value, 0, 1);
            stops.Add(new GradientStop(colors[i], offset));
        }

        return stops;
    }

    private static (string Color, double? Position) SplitColorAndPosition(string text)
    {
        var depth = 0;
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (ch == '(')
            {
                depth++;
            }
            else if (ch == ')')
            {
                depth--;
            }
            else if (char.IsWhiteSpace(ch) && depth == 0)
            {
                var color = text[..i].Trim();
                var rest = text[i..].Trim();
                return (color, Percent(rest));
            }
        }

        return (text, null);
    }

    /// <summary>取函数括号内的参数并按顶层逗号切分。</summary>
    private static List<string> Arguments(string css)
    {
        var open = css.IndexOf('(');
        var close = css.LastIndexOf(')');
        if (open < 0 || close <= open)
        {
            return new List<string>();
        }

        return SplitTopLevel(css[(open + 1)..close]);
    }

    private static List<string> SplitTopLevel(string text)
    {
        var parts = new List<string>();
        var depth = 0;
        var start = 0;
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (ch == '(')
            {
                depth++;
            }
            else if (ch == ')')
            {
                depth = Math.Max(depth - 1, 0);
            }
            else if (ch == ',' && depth == 0)
            {
                parts.Add(text[start..i]);
                start = i + 1;
            }
        }

        parts.Add(text[start..]);
        return parts;
    }

    private static Color? ParseColor(string? css)
    {
        if (string.IsNullOrWhiteSpace(css))
        {
            return null;
        }

        var value = css.Trim();
        if (value.StartsWith('#'))
        {
            return ParseHex(value[1..]);
        }

        if (value.StartsWith("rgb", StringComparison.OrdinalIgnoreCase))
        {
            return ParseRgb(value);
        }

        if (value.Equals("white", StringComparison.OrdinalIgnoreCase))
        {
            return Colors.White;
        }

        if (value.Equals("black", StringComparison.OrdinalIgnoreCase))
        {
            return Colors.Black;
        }

        return Color.TryParse(value, out var parsed) ? parsed : null;
    }

    private static Color? ParseHex(string hex)
    {
        static int Nibble(char c) => c switch
        {
            >= '0' and <= '9' => c - '0',
            >= 'a' and <= 'f' => c - 'a' + 10,
            >= 'A' and <= 'F' => c - 'A' + 10,
            _ => -1,
        };

        foreach (var ch in hex)
        {
            if (Nibble(ch) < 0)
            {
                return null;
            }
        }

        return hex.Length switch
        {
            // CSS 的 #RGBA / #RRGGBBAA 都是 alpha 在后
            3 => Color.FromRgb(Byte(hex[0], hex[0]), Byte(hex[1], hex[1]), Byte(hex[2], hex[2])),
            4 => Color.FromArgb(
                Byte(hex[3], hex[3]),
                Byte(hex[0], hex[0]),
                Byte(hex[1], hex[1]),
                Byte(hex[2], hex[2])),
            6 => Color.FromRgb(
                Byte(hex[0], hex[1]),
                Byte(hex[2], hex[3]),
                Byte(hex[4], hex[5])),
            8 => Color.FromArgb(
                Byte(hex[6], hex[7]),
                Byte(hex[0], hex[1]),
                Byte(hex[2], hex[3]),
                Byte(hex[4], hex[5])),
            _ => null,
        };

        static byte Byte(char high, char low) => (byte)(Nibble(high) * 16 + Nibble(low));
    }

    private static Color? ParseRgb(string css)
    {
        var parts = Arguments(css);
        if (parts.Count < 3)
        {
            return null;
        }

        if (!Channel(parts[0], out var r) || !Channel(parts[1], out var g) || !Channel(parts[2], out var b))
        {
            return null;
        }

        var alpha = 255;
        if (parts.Count >= 4)
        {
            var text = parts[3].Trim();
            if (text.EndsWith('%')
                && double.TryParse(text[..^1], NumberStyles.Float, CultureInfo.InvariantCulture, out var pct))
            {
                alpha = (int)Math.Round(Math.Clamp(pct / 100, 0, 1) * 255);
            }
            else if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var a))
            {
                alpha = (int)Math.Round(Math.Clamp(a, 0, 1) * 255);
            }
        }

        return Color.FromArgb((byte)alpha, r, g, b);

        static bool Channel(string token, out byte value)
        {
            value = 0;
            var text = token.Trim();
            if (text.EndsWith('%'))
            {
                if (!double.TryParse(text[..^1], NumberStyles.Float, CultureInfo.InvariantCulture, out var pct))
                {
                    return false;
                }

                value = (byte)Math.Round(Math.Clamp(pct, 0, 100) * 2.55);
                return true;
            }

            if (!double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var number))
            {
                return false;
            }

            value = (byte)Math.Clamp(Math.Round(number), 0, 255);
            return true;
        }
    }

    private static BoxShadows? ParseShadows(string? css)
    {
        if (string.IsNullOrWhiteSpace(css) || css.Trim().Equals("none", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var layers = new List<BoxShadow>();
        foreach (var layer in SplitTopLevel(css))
        {
            var shadow = ParseShadow(layer);
            if (shadow is not null)
            {
                layers.Add(shadow.Value);
            }
        }

        return layers.Count switch
        {
            0 => null,
            1 => new BoxShadows(layers[0]),
            _ => new BoxShadows(layers[0], layers.Skip(1).ToArray()),
        };
    }

    private static BoxShadow? ParseShadow(string layer)
    {
        var tokens = SplitSpaces(layer);
        if (tokens.Count == 0)
        {
            return null;
        }

        var inset = false;
        var numbers = new List<double>();
        Color color = Colors.Black;
        foreach (var token in tokens)
        {
            if (token.Equals("inset", StringComparison.OrdinalIgnoreCase))
            {
                inset = true;
                continue;
            }

            var parsed = ParseColor(token);
            if (parsed is not null)
            {
                color = parsed.Value;
                continue;
            }

            var number = token.EndsWith("px", StringComparison.OrdinalIgnoreCase) ? token[..^2] : token;
            if (double.TryParse(number, NumberStyles.Float, CultureInfo.InvariantCulture, out var value))
            {
                numbers.Add(value);
            }
        }

        if (numbers.Count < 2)
        {
            return null;
        }

        return new BoxShadow
        {
            OffsetX = numbers[0],
            OffsetY = numbers[1],
            Blur = numbers.Count > 2 ? numbers[2] : 0,
            Spread = numbers.Count > 3 ? numbers[3] : 0,
            Color = color,
            IsInset = inset,
        };
    }

    private static List<string> SplitSpaces(string text)
    {
        var tokens = new List<string>();
        var depth = 0;
        var start = -1;
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (ch == '(')
            {
                depth++;
            }
            else if (ch == ')')
            {
                depth = Math.Max(depth - 1, 0);
            }

            if (char.IsWhiteSpace(ch) && depth == 0)
            {
                if (start >= 0)
                {
                    tokens.Add(text[start..i]);
                    start = -1;
                }
            }
            else if (start < 0)
            {
                start = i;
            }
        }

        if (start >= 0)
        {
            tokens.Add(text[start..]);
        }

        return tokens;
    }
}
