using System;
using System.Collections.Generic;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Flux.Desktop.Models;

namespace Flux.Desktop.Views;

/// <summary>
/// Bot 头像：形状 × 颜色，渲染契约与 Web <c>BotAvatar.tsx</c> / 安卓 <c>V2BotAvatar.kt</c> 一致。
/// 64 × 64 设计坐标，按控件边长等比缩放；未知取值回退 circle / violet。
/// </summary>
public sealed class BotAvatar : Control
{
    public static readonly StyledProperty<string?> AvatarShapeProperty =
        AvaloniaProperty.Register<BotAvatar, string?>(nameof(AvatarShape), BotAvatarPalette.DefaultShape);

    public static readonly StyledProperty<string?> AvatarColorProperty =
        AvaloniaProperty.Register<BotAvatar, string?>(nameof(AvatarColor), BotAvatarPalette.DefaultColor);

    private const double DesignSize = 64;

    /// <summary>形状按同色 4px 描边并集，避免多个基本图形之间出现接缝。</summary>
    private const double BodyStroke = 4;

    private static readonly Dictionary<string, Geometry> Cache = new(StringComparer.Ordinal);

    static BotAvatar()
    {
        AffectsRender<BotAvatar>(AvatarShapeProperty, AvatarColorProperty);
    }

    public string? AvatarShape
    {
        get => GetValue(AvatarShapeProperty);
        set => SetValue(AvatarShapeProperty, value);
    }

    public string? AvatarColor
    {
        get => GetValue(AvatarColorProperty);
        set => SetValue(AvatarColorProperty, value);
    }

    public override void Render(DrawingContext context)
    {
        var size = Math.Min(Bounds.Width, Bounds.Height);
        if (size <= 0)
        {
            return;
        }

        var shape = BotAvatarPalette.NormalizeShape(AvatarShape);
        var brush = new SolidColorBrush(Color.Parse(BotAvatarPalette.Hex(AvatarColor)));
        var pen = new Pen(brush, BodyStroke, lineJoin: PenLineJoin.Round);
        var eye = new SolidColorBrush(Color.Parse("#0A0C0E"), 0.82);
        var shine = new SolidColorBrush(Colors.White, 0.5);
        var faceY = BotAvatarPalette.EyeY(shape);

        using (context.PushTransform(Matrix.CreateScale(size / DesignSize, size / DesignSize)))
        {
            context.DrawGeometry(brush, pen, ShapeGeometry(shape));
            context.DrawEllipse(eye, null, new Point(24.5, faceY), 3.4, 3.4);
            context.DrawEllipse(eye, null, new Point(39.5, faceY), 3.4, 3.4);
            context.DrawEllipse(shine, null, new Point(25.6, faceY - 1.2), 1.1, 1.1);
            context.DrawEllipse(shine, null, new Point(40.6, faceY - 1.2), 1.1, 1.1);
        }
    }

    private static Geometry ShapeGeometry(string shape)
    {
        if (!Cache.TryGetValue(shape, out var geometry))
        {
            geometry = Build(shape);
            Cache[shape] = geometry;
        }

        return geometry;
    }

    private static Geometry Build(string shape) => shape switch
    {
        "squircle" => new RectangleGeometry(new Rect(6, 6, 52, 52), 16, 16),
        "hexagon" => Geometry.Parse("M32,5 L53.7,17.5 L53.7,42.5 L32,55 L10.3,42.5 L10.3,17.5 Z"),
        "triangle" => Geometry.Parse("M32,8 L57,51 L7,51 Z"),
        "diamond" => Geometry.Parse("M32,6 L58,32 L32,58 L6,32 Z"),
        "star" => Radial(5, 26, 11),
        "burst" => Radial(12, 29, 20),
        "flower" => Group(
            Circle(32 + Math.Cos(Rad(0)) * 11, 32 + Math.Sin(Rad(0)) * 11, 13),
            Circle(32 + Math.Cos(Rad(60)) * 11, 32 + Math.Sin(Rad(60)) * 11, 13),
            Circle(32 + Math.Cos(Rad(120)) * 11, 32 + Math.Sin(Rad(120)) * 11, 13),
            Circle(32 + Math.Cos(Rad(180)) * 11, 32 + Math.Sin(Rad(180)) * 11, 13),
            Circle(32 + Math.Cos(Rad(240)) * 11, 32 + Math.Sin(Rad(240)) * 11, 13),
            Circle(32 + Math.Cos(Rad(300)) * 11, 32 + Math.Sin(Rad(300)) * 11, 13),
            Circle(32, 32, 15)),
        "shield" => Geometry.Parse(
            "M32,5 L55,13 L55,29 C55,43 45,52 32,58 C19,52 9,43 9,29 L9,13 Z"),
        "drop" => Geometry.Parse(
            "M32,5 C45,19 52,28 52,37.5 A20,20 0 0 1 12,37.5 C12,28 19,19 32,5 Z"),
        "cloud" => Group(
            Circle(23, 36, 11),
            Circle(32, 29, 13),
            Circle(42, 36, 10),
            new RectangleGeometry(new Rect(18, 36, 28, 12), 6, 6)),
        "gear" => Gear(),
        "egg" => Geometry.Parse(
            "M32,6 C42,6 50,20 50,32 C50,46 42,58 32,58 C22,58 14,46 14,32 C14,20 22,6 32,6 Z"),
        "blob" => Geometry.Parse(
            "M34,5 C48,4 60,15 58,29 C56,43 46,60 31,59 C16,58 4,46 6,31 C8,16 20,6 34,5 Z"),
        _ => Circle(32, 32, 26),
    };

    private static Geometry Gear()
    {
        var children = new List<Geometry>();
        foreach (var degree in new[] { 0, 45, 90, 135, 180, 225, 270, 315 })
        {
            children.Add(RotatedRect(28.5, 2, 7, 13, degree));
        }

        children.Add(Circle(32, 32, 19));
        return Group(children.ToArray());
    }

    private static double Rad(double degree) => degree * Math.PI / 180;

    private static Geometry Circle(double cx, double cy, double r)
        => new EllipseGeometry(new Rect(cx - r, cy - r, r * 2, r * 2));

    private static Geometry Group(params Geometry[] children)
    {
        var group = new GeometryGroup();
        foreach (var child in children)
        {
            group.Children.Add(child);
        }

        return group;
    }

    /// <summary>极坐标多边形（星星 / 爆炸形）。</summary>
    private static Geometry Radial(int spikes, double outer, double inner)
    {
        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            for (var index = 0; index < spikes * 2; index += 1)
            {
                var radius = index % 2 == 0 ? outer : inner;
                var angle = -Math.PI / 2 + index * Math.PI / spikes;
                var point = new Point(32 + Math.Cos(angle) * radius, 32 + Math.Sin(angle) * radius);
                if (index == 0)
                {
                    ctx.BeginFigure(point, true);
                }
                else
                {
                    ctx.LineTo(point);
                }
            }

            ctx.EndFigure(true);
        }

        return geometry;
    }

    /// <summary>绕 (32,32) 旋转的圆角矩形（齿轮的齿），直接算成多边形，避免依赖 Transform。</summary>
    private static Geometry RotatedRect(double x, double y, double width, double height, double degree)
    {
        var angle = Rad(degree);
        var cos = Math.Cos(angle);
        var sin = Math.Sin(angle);
        Point Rotate(double px, double py)
            => new(32 + (px - 32) * cos - (py - 32) * sin, 32 + (px - 32) * sin + (py - 32) * cos);

        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            ctx.BeginFigure(Rotate(x, y), true);
            ctx.LineTo(Rotate(x + width, y));
            ctx.LineTo(Rotate(x + width, y + height));
            ctx.LineTo(Rotate(x, y + height));
            ctx.EndFigure(true);
        }

        return geometry;
    }
}
