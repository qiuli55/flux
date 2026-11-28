using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia;
using Flux.Desktop.Models;

namespace Flux.Desktop.Views;

/// <summary>
/// 桌面画布的纯几何层：相机、命中、手柄、缩放、吸附、参考线。
///
/// 与手机 `DesignInteract.kt` 保持同一套判定（相机换算、旋转对角钉住、
/// 多参考线 + 空隙吸附、15° / 90° 旋转、椭圆与裁剪命中），差别只在
/// 输入设备与展示单位。所有函数不碰 Avalonia 绘制上下文，便于自测。
/// </summary>
public static class DesignCanvasInteract
{
    public const double MinZoom = .6;
    public const double MaxZoom = 4;

    public enum Handle
    {
        TopLeft,
        Top,
        TopRight,
        Right,
        BottomRight,
        Bottom,
        BottomLeft,
        Left,
        Rotate,
    }

    public readonly record struct Frame(double X, double Y, double W, double H)
    {
        public double Right => X + W;
        public double Bottom => Y + H;
        public double CenterX => X + W / 2;
        public double CenterY => Y + H / 2;
        public double MinSide => Math.Min(W, H);
        public Rect Bounds => new(X, Y, W, H);
    }

    /// <summary>一条空隙参考线（画板绝对坐标）。</summary>
    public readonly record struct GapGuide(Point Start, Point End);

    public readonly record struct SnapResult(
        double Dx,
        double Dy,
        IReadOnlyList<double> Vertical,
        IReadOnlyList<double> Horizontal,
        IReadOnlyList<GapGuide> Gaps)
    {
        public static SnapResult None => new(0, 0, Array.Empty<double>(), Array.Empty<double>(), Array.Empty<GapGuide>());
    }

    public readonly record struct ResizeSnap(
        Frame Frame,
        IReadOnlyList<double> Vertical,
        IReadOnlyList<double> Horizontal)
    {
        public static ResizeSnap None(Frame frame) =>
            new(frame, Array.Empty<double>(), Array.Empty<double>());
    }

    /// <summary>画布相机：屏幕 ↔ 设计坐标的唯一换算入口，缩放绕指针位置。</summary>
    public readonly record struct DesignCamera(
        double ViewW,
        double ViewH,
        double BoardW,
        double BoardH,
        double Zoom,
        double PanX,
        double PanY,
        double Padding = 24)
    {
        public double FitScale
        {
            get
            {
                if (BoardW <= 0 || BoardH <= 0) return 1;
                var availW = Math.Max(ViewW - Padding * 2, 1);
                var availH = Math.Max(ViewH - Padding * 2, 1);
                return Math.Min(availW / BoardW, availH / BoardH);
            }
        }

        public double Scale => FitScale * Zoom;

        public double OriginX => (ViewW - BoardW * Scale) / 2 + PanX;

        public double OriginY => (ViewH - BoardH * Scale) / 2 + PanY;

        public Point ToDesign(Point point) => new(
            (point.X - OriginX) / Scale,
            (point.Y - OriginY) / Scale);

        public Point ToScreen(Point point) => new(
            point.X * Scale + OriginX,
            point.Y * Scale + OriginY);

        public DesignCamera PanBy(double dx, double dy) => this with { PanX = PanX + dx, PanY = PanY + dy };

        /// <summary>绕屏幕上的 [anchor] 缩放：锚点下的设计坐标保持不动。</summary>
        public DesignCamera ZoomAround(Point anchor, double factor)
        {
            if (factor <= 0 || double.IsNaN(factor)) return this;
            var nextZoom = Math.Clamp(Zoom * factor, MinZoom, MaxZoom);
            if (Math.Abs(nextZoom - Zoom) < 1e-9) return this;
            var ratio = nextZoom / Zoom;
            var nextOriginX = anchor.X - (anchor.X - OriginX) * ratio;
            var nextOriginY = anchor.Y - (anchor.Y - OriginY) * ratio;
            var centeredX = (ViewW - BoardW * FitScale * nextZoom) / 2;
            var centeredY = (ViewH - BoardH * FitScale * nextZoom) / 2;
            return this with
            {
                Zoom = nextZoom,
                PanX = nextOriginX - centeredX,
                PanY = nextOriginY - centeredY,
            };
        }

        public DesignCamera Reset() => this with { Zoom = 1, PanX = 0, PanY = 0 };
    }

    /// <summary>扁平化后的节点：绝对坐标 + 父级原点，命中 / 手柄 / 吸附共用。</summary>
    public sealed class FlatNode
    {
        public FlatNode(
            DesignNode node,
            double originX,
            double originY,
            double x,
            double y,
            double w,
            double h,
            double alpha,
            bool clip)
        {
            Node = node;
            OriginX = originX;
            OriginY = originY;
            X = x;
            Y = y;
            W = w;
            H = h;
            Alpha = alpha;
            Clip = clip;
        }

        public DesignNode Node { get; }
        public double OriginX { get; }
        public double OriginY { get; }
        public double X { get; }
        public double Y { get; }
        public double W { get; }
        public double H { get; }
        public double Alpha { get; }
        public bool Clip { get; }
        public List<FlatNode> Children { get; } = new();
        public string Id => Node.Id ?? "";
        public string Type => TypeOf(Node);
        public double Rotation => Node.Rotation ?? 0;
        public double CenterX => X + W / 2;
        public double CenterY => Y + H / 2;
        public double Right => X + W;
        public double Bottom => Y + H;
        public bool Ellipse => IsEllipse(Type, W, H);
        public Frame Frame => new(X, Y, W, H);
        public Rect Bounds => new(X, Y, W, H);
        public double LocalX => Node.X ?? 0;
        public double LocalY => Node.Y ?? 0;
    }

    private static readonly HashSet<string> ClipTypes = new(StringComparer.Ordinal)
    {
        "text", "button", "pill", "input", "nav", "badge", "chip", "avatar",
    };

    public static string TypeOf(DesignNode node)
    {
        var type = (node.Type ?? "").Trim().ToLowerInvariant();
        return type.Length == 0 ? "text" : type;
    }

    private static bool IsEllipse(string type, double w, double h) =>
        type is "circle" or "avatar" || (type == "decor" && Math.Abs(w - h) < .01);

    /// <summary>展开成扁平树；隐藏 / 透明 / 零尺寸节点不参与命中与选择。</summary>
    public static List<FlatNode> Flatten(
        IEnumerable<DesignNode>? nodes,
        double originX = 0,
        double originY = 0,
        double parentAlpha = 1)
    {
        var result = new List<FlatNode>();
        if (nodes is null) return result;
        foreach (var node in nodes)
        {
            if (node.Hidden == true) continue;
            var w = node.W ?? 0;
            var h = node.H ?? 0;
            if (w <= 0 || h <= 0) continue;
            var alpha = Math.Clamp(parentAlpha * Math.Clamp(node.Opacity ?? 1, 0, 1), 0, 1);
            if (alpha <= .01) continue;
            var x = originX + (node.X ?? 0);
            var y = originY + (node.Y ?? 0);
            var type = TypeOf(node);
            var flat = new FlatNode(node, originX, originY, x, y, w, h, alpha, ClipTypes.Contains(type));
            flat.Children.AddRange(Flatten(node.Children, x, y, alpha));
            result.Add(flat);
        }
        return result;
    }

    public static FlatNode? FindById(IEnumerable<FlatNode> nodes, string id)
    {
        foreach (var node in nodes)
        {
            if (node.Id == id) return node;
            var child = FindById(node.Children, id);
            if (child is not null) return child;
        }
        return null;
    }

    public static IReadOnlyList<FlatNode> FlattenAll(IEnumerable<FlatNode> nodes)
    {
        var result = new List<FlatNode>();
        void Walk(IEnumerable<FlatNode> list)
        {
            foreach (var node in list)
            {
                result.Add(node);
                Walk(node.Children);
            }
        }
        Walk(nodes);
        return result;
    }

    /// <summary>命中测试：后画的在上，先查子节点；装饰层不参与；椭圆按真实形状。</summary>
    public static FlatNode? HitTest(IReadOnlyList<FlatNode> nodes, Point point, double tolerance = 0)
    {
        for (var i = nodes.Count - 1; i >= 0; i--)
        {
            var node = nodes[i];
            if (node.Alpha <= .05) continue;
            var local = RotatePoint(point, new Point(node.CenterX, node.CenterY), -node.Rotation);
            var inside = Inside(node, local, tolerance);
            if (!node.Clip || inside)
            {
                var child = HitTest(node.Children, point, tolerance);
                if (child is not null) return child;
            }
            if (inside && node.Type != "decor") return node;
        }
        return null;
    }

    private static bool Inside(FlatNode node, Point local, double tolerance)
    {
        if (node.Ellipse)
        {
            var rx = node.W / 2 + tolerance;
            var ry = node.H / 2 + tolerance;
            if (rx <= 0 || ry <= 0) return false;
            var nx = (local.X - node.CenterX) / rx;
            var ny = (local.Y - node.CenterY) / ry;
            return nx * nx + ny * ny <= 1;
        }
        return local.X >= node.X - tolerance && local.X <= node.Right + tolerance &&
               local.Y >= node.Y - tolerance && local.Y <= node.Bottom + tolerance;
    }

    /// <summary>框选：与选框相交的图层都算选中（装饰层除外）。</summary>
    public static HashSet<string> MarqueeSelection(IReadOnlyList<FlatNode> nodes, Rect rect)
    {
        var result = new HashSet<string>(StringComparer.Ordinal);
        foreach (var node in FlattenAll(nodes))
        {
            if (node.Alpha <= .05 || node.Type == "decor") continue;
            if (node.X < rect.Right && node.Right > rect.Left &&
                node.Y < rect.Bottom && node.Bottom > rect.Top)
            {
                result.Add(node.Id);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 手柄

    public static IReadOnlyDictionary<Handle, Point> HandlePositions(FlatNode node, double rotateOffset)
    {
        var frame = node.Frame;
        var raw = new Dictionary<Handle, Point>
        {
            [Handle.TopLeft] = new(frame.X, frame.Y),
            [Handle.Top] = new(frame.CenterX, frame.Y),
            [Handle.TopRight] = new(frame.Right, frame.Y),
            [Handle.Right] = new(frame.Right, frame.CenterY),
            [Handle.BottomRight] = new(frame.Right, frame.Bottom),
            [Handle.Bottom] = new(frame.CenterX, frame.Bottom),
            [Handle.BottomLeft] = new(frame.X, frame.Bottom),
            [Handle.Left] = new(frame.X, frame.CenterY),
            [Handle.Rotate] = new(frame.CenterX, frame.Y - rotateOffset),
        };
        if (Math.Abs(node.Rotation) < .01) return raw;
        var center = new Point(frame.CenterX, frame.CenterY);
        return raw.ToDictionary(pair => pair.Key, pair => RotatePoint(pair.Value, center, node.Rotation));
    }

    public static Handle? HitHandle(FlatNode node, Point point, double radius, double rotateOffset)
    {
        Handle? best = null;
        var bestDistance = double.MaxValue;
        foreach (var (handle, center) in HandlePositions(node, rotateOffset))
        {
            var distance = Distance(point, center);
            if (distance > radius || distance >= bestDistance) continue;
            best = handle;
            bestDistance = distance;
        }
        return best;
    }

    /// <summary>热区随节点大小收窄：小节点上 8 个手柄不能把整个节点盖住。</summary>
    public static double HandleRadius(double desired, Frame frame)
    {
        if (desired <= 0) return 0;
        if (frame.MinSide <= 0) return desired;
        return Math.Min(desired, Math.Max(frame.MinSide / 2.2, desired * .35));
    }

    /// <summary>拖手柄改框；[lockAspect] 等价于按住 Shift 的等比缩放。</summary>
    public static Frame ResizeFrame(
        Frame frame,
        double rotation,
        Handle handle,
        Point pointer,
        double minSize = 12,
        bool lockAspect = false)
    {
        if (handle == Handle.Rotate) return frame;
        if (lockAspect) return ResizeFrameLocked(frame, rotation, handle, pointer, minSize);

        var center = new Point(frame.CenterX, frame.CenterY);
        var local = RotatePoint(pointer, center, -rotation);
        var left = frame.X;
        var top = frame.Y;
        var right = frame.Right;
        var bottom = frame.Bottom;
        switch (handle)
        {
            case Handle.TopLeft:
                left = Math.Min(local.X, right - minSize);
                top = Math.Min(local.Y, bottom - minSize);
                break;
            case Handle.Top:
                top = Math.Min(local.Y, bottom - minSize);
                break;
            case Handle.TopRight:
                right = Math.Max(local.X, left + minSize);
                top = Math.Min(local.Y, bottom - minSize);
                break;
            case Handle.Right:
                right = Math.Max(local.X, left + minSize);
                break;
            case Handle.BottomRight:
                right = Math.Max(local.X, left + minSize);
                bottom = Math.Max(local.Y, top + minSize);
                break;
            case Handle.Bottom:
                bottom = Math.Max(local.Y, top + minSize);
                break;
            case Handle.BottomLeft:
                left = Math.Min(local.X, right - minSize);
                bottom = Math.Max(local.Y, top + minSize);
                break;
            case Handle.Left:
                left = Math.Min(local.X, right - minSize);
                break;
        }
        var nextCenter = new Point((left + right) / 2, (top + bottom) / 2);
        var shift = RotateVector(new Point(nextCenter.X - center.X, nextCenter.Y - center.Y), rotation);
        var w = right - left;
        var h = bottom - top;
        return new Frame(center.X + shift.X - w / 2, center.Y + shift.Y - h / 2, w, h);
    }

    private static Frame ResizeFrameLocked(
        Frame frame,
        double rotation,
        Handle handle,
        Point pointer,
        double minSize)
    {
        var center = new Point(frame.CenterX, frame.CenterY);
        var local = RotatePoint(pointer, center, -rotation);
        var ratio = frame.H > 1e-4 ? frame.W / frame.H : 1;
        var left = frame.X;
        var top = frame.Y;
        var right = frame.Right;
        var bottom = frame.Bottom;
        switch (handle)
        {
            case Handle.TopLeft:
            case Handle.TopRight:
            case Handle.BottomRight:
            case Handle.BottomLeft:
            {
                var anchorX = handle is Handle.TopRight or Handle.BottomRight ? left : right;
                var anchorY = handle is Handle.BottomLeft or Handle.BottomRight ? top : bottom;
                var signX = Math.Abs(anchorX - left) < .001 ? 1 : -1;
                var signY = Math.Abs(anchorY - top) < .001 ? 1 : -1;
                var vx = (local.X - anchorX) * signX;
                var vy = (local.Y - anchorY) * signY;
                var denom = frame.W * frame.W + frame.H * frame.H;
                var factor = denom <= 1e-4 ? 1 : (vx * frame.W + vy * frame.H) / denom;
                if (double.IsNaN(factor)) factor = 1;
                factor = Math.Max(factor, minSize / Math.Max(frame.W, 1e-4));
                factor = Math.Max(factor, minSize / Math.Max(frame.H, 1e-4));
                var w = frame.W * factor;
                var h = frame.H * factor;
                left = signX > 0 ? anchorX : anchorX - w;
                right = left + w;
                top = signY > 0 ? anchorY : anchorY - h;
                bottom = top + h;
                break;
            }
            case Handle.Left:
            case Handle.Right:
            {
                var anchorX = handle == Handle.Right ? frame.X : frame.Right;
                var signX = handle == Handle.Right ? 1 : -1;
                var w = Math.Max((local.X - anchorX) * signX, minSize);
                var h = Math.Max(w / Math.Max(ratio, 1e-4), minSize);
                left = signX > 0 ? anchorX : anchorX - w;
                right = left + w;
                top = center.Y - h / 2;
                bottom = top + h;
                break;
            }
            case Handle.Top:
            case Handle.Bottom:
            {
                var anchorY = handle == Handle.Bottom ? frame.Y : frame.Bottom;
                var signY = handle == Handle.Bottom ? 1 : -1;
                var h = Math.Max((local.Y - anchorY) * signY, minSize);
                var w = Math.Max(h * ratio, minSize);
                top = signY > 0 ? anchorY : anchorY - h;
                bottom = top + h;
                left = center.X - w / 2;
                right = left + w;
                break;
            }
        }
        var nextCenter = new Point((left + right) / 2, (top + bottom) / 2);
        var shift = RotateVector(new Point(nextCenter.X - center.X, nextCenter.Y - center.Y), rotation);
        var nextW = right - left;
        var nextH = bottom - top;
        return new Frame(center.X + shift.X - nextW / 2, center.Y + shift.Y - nextH / 2, nextW, nextH);
    }

    // ------------------------------------------------------------ 旋转

    public static double RotationDegrees(Point center, Point start, Point current)
    {
        var a = Math.Atan2(start.Y - center.Y, start.X - center.X);
        var b = Math.Atan2(current.Y - center.Y, current.X - center.X);
        return (b - a) * 180 / Math.PI;
    }

    public static double SnapRotation(double degrees, double threshold = 4, double step = 15)
    {
        var right = SnapRotationToStep(degrees, 90, threshold);
        return Math.Abs(right - degrees) > 1e-9 ? right : SnapRotationToStep(degrees, step, threshold);
    }

    public static double SnapRotationToStep(double degrees, double step, double threshold = 4)
    {
        if (step <= 0) return degrees;
        var nearest = Math.Round(degrees / step, MidpointRounding.AwayFromZero) * step;
        var delta = shortestDelta(degrees, nearest);
        return Math.Abs(delta) <= threshold ? nearest : degrees;
    }

    /// <summary>强制按 [step] 取整：桌面按住 Shift 时旋转固定 15° 档。</summary>
    public static double ConstrainRotation(double degrees, double step = 15)
    {
        if (step <= 0) return degrees;
        return Math.Round(degrees / step, MidpointRounding.AwayFromZero) * step;
    }

    // ------------------------------------------------------------ 吸附

    /// <summary>移动吸附：三对齐 + 等间距；对齐命中时返回所有吻合的参考线。</summary>
    public static SnapResult SnapMove(Rect rect, IReadOnlyList<Rect> targets, double threshold)
    {
        var bestDx = 0.0;
        var bestX = threshold;
        var snappedX = false;
        var bestDy = 0.0;
        var bestY = threshold;
        var snappedY = false;

        var moveXs = new[] { rect.Left, rect.Center.X, rect.Right };
        var moveYs = new[] { rect.Top, rect.Center.Y, rect.Bottom };
        foreach (var target in targets)
        {
            var targetXs = new[] { target.Left, target.Center.X, target.Right };
            var targetYs = new[] { target.Top, target.Center.Y, target.Bottom };
            foreach (var mx in moveXs)
            {
                foreach (var tx in targetXs)
                {
                    var delta = tx - mx;
                    if (Math.Abs(delta) < bestX)
                    {
                        bestX = Math.Abs(delta);
                        bestDx = delta;
                        snappedX = true;
                    }
                }
            }
            foreach (var my in moveYs)
            {
                foreach (var ty in targetYs)
                {
                    var delta = ty - my;
                    if (Math.Abs(delta) < bestY)
                    {
                        bestY = Math.Abs(delta);
                        bestDy = delta;
                        snappedY = true;
                    }
                }
            }
        }

        var gaps = new List<GapGuide>();
        if (!snappedX && SnapGapAxis(rect, targets, threshold, true) is { } gapX)
        {
            bestDx = gapX.Delta;
            gaps.AddRange(gapX.Guides);
            snappedX = true;
        }
        if (!snappedY && SnapGapAxis(rect, targets, threshold, false) is { } gapY)
        {
            bestDy = gapY.Delta;
            gaps.AddRange(gapY.Guides);
            snappedY = true;
        }

        var moved = new Rect(rect.X + bestDx, rect.Y + bestDy, rect.Width, rect.Height);
        return new SnapResult(
            bestDx,
            bestDy,
            snappedX ? AlignGuides(moved, targets, true) : Array.Empty<double>(),
            snappedY ? AlignGuides(moved, targets, false) : Array.Empty<double>(),
            gaps);
    }

    private readonly record struct AxisGap(double Delta, IReadOnlyList<GapGuide> Guides);

    private static AxisGap? SnapGapAxis(Rect rect, IReadOnlyList<Rect> targets, double threshold, bool horizontal)
    {
        var overlapping = targets.Where(target => horizontal
            ? target.Top < rect.Bottom && target.Bottom > rect.Top
            : target.Left < rect.Right && target.Right > rect.Left).ToList();
        if (overlapping.Count == 0) return null;

        var known = KnownGaps(overlapping, horizontal);
        var bestDelta = 0.0;
        var bestDistance = threshold;
        var guides = new List<GapGuide>();

        void Consider(double delta, IReadOnlyList<GapGuide> lines)
        {
            if (Math.Abs(delta) > threshold) return;
            if (Math.Abs(delta) > bestDistance) return;
            bestDistance = Math.Abs(delta);
            bestDelta = delta;
            guides.Clear();
            guides.AddRange(lines);
        }

        foreach (var target in overlapping)
        {
            var targetStart = horizontal ? target.Left : target.Top;
            var targetEnd = horizontal ? target.Right : target.Bottom;
            var rectStart = horizontal ? rect.Left : rect.Top;
            var rectEnd = horizontal ? rect.Right : rect.Bottom;
            var across = horizontal ? OverlapCenterY(rect, target) : OverlapCenterX(rect, target);

            if (rectEnd <= targetStart + .01)
            {
                var currentGap = targetStart - rectEnd;
                foreach (var gap in known)
                {
                    var delta = gap - currentGap;
                    Consider(delta, new[] { GapLine(rectEnd + delta, targetStart, across, horizontal) });
                }
            }
            if (rectStart >= targetEnd - .01)
            {
                var currentGap = rectStart - targetEnd;
                foreach (var gap in known)
                {
                    var delta = targetEnd + gap - rectStart;
                    Consider(delta, new[] { GapLine(targetEnd, rectStart + delta, across, horizontal) });
                }
            }
        }

        var before = overlapping.Where(target => horizontal
            ? target.Right <= rect.Left + .01
            : target.Bottom <= rect.Top + .01).ToList();
        var after = overlapping.Where(target => horizontal
            ? target.Left >= rect.Right - .01
            : target.Top >= rect.Bottom - .01).ToList();
        foreach (var first in before)
        {
            foreach (var second in after)
            {
                var startGap = horizontal ? rect.Left - first.Right : rect.Top - first.Bottom;
                var endGap = horizontal ? second.Left - rect.Right : second.Top - rect.Bottom;
                var delta = (endGap - startGap) / 2;
                var across = horizontal
                    ? (OverlapCenterY(rect, first) + OverlapCenterY(rect, second)) / 2
                    : (OverlapCenterX(rect, first) + OverlapCenterX(rect, second)) / 2;
                var firstEnd = horizontal ? first.Right : first.Bottom;
                var rectStart = horizontal ? rect.Left + delta : rect.Top + delta;
                var rectEnd = horizontal ? rect.Right + delta : rect.Bottom + delta;
                var secondStart = horizontal ? second.Left : second.Top;
                Consider(delta, new[]
                {
                    GapLine(firstEnd, rectStart, across, horizontal),
                    GapLine(rectEnd, secondStart, across, horizontal),
                });
            }
        }

        return guides.Count == 0 ? null : new AxisGap(bestDelta, guides);
    }

    private static List<double> KnownGaps(IReadOnlyList<Rect> targets, bool horizontal)
    {
        var gaps = new List<double>();
        for (var i = 0; i < targets.Count; i++)
        {
            for (var j = 0; j < targets.Count; j++)
            {
                if (i == j) continue;
                var first = targets[i];
                var second = targets[j];
                var overlaps = horizontal
                    ? first.Top < second.Bottom && first.Bottom > second.Top
                    : first.Left < second.Right && first.Right > second.Left;
                if (!overlaps) continue;
                var gap = horizontal ? second.Left - first.Right : second.Top - first.Bottom;
                if (gap > .5) gaps.Add(gap);
            }
        }
        return gaps.Distinct().ToList();
    }

    private static GapGuide GapLine(double from, double to, double across, bool horizontal) =>
        horizontal
            ? new GapGuide(new Point(from, across), new Point(to, across))
            : new GapGuide(new Point(across, from), new Point(across, to));

    private static double OverlapCenterY(Rect a, Rect b) =>
        (Math.Max(a.Top, b.Top) + Math.Min(a.Bottom, b.Bottom)) / 2;

    private static double OverlapCenterX(Rect a, Rect b) =>
        (Math.Max(a.Left, b.Left) + Math.Min(a.Right, b.Right)) / 2;

    private static IReadOnlyList<double> AlignGuides(Rect moved, IReadOnlyList<Rect> targets, bool horizontal)
    {
        var result = new SortedSet<double>();
        var moveValues = horizontal
            ? new[] { moved.Left, moved.Center.X, moved.Right }
            : new[] { moved.Top, moved.Center.Y, moved.Bottom };
        foreach (var target in targets)
        {
            var targetValues = horizontal
                ? new[] { target.Left, target.Center.X, target.Right }
                : new[] { target.Top, target.Center.Y, target.Bottom };
            foreach (var movedValue in moveValues)
            {
                foreach (var targetValue in targetValues)
                {
                    if (Math.Abs(movedValue - targetValue) <= .01) result.Add(targetValue);
                }
            }
        }
        return result.ToList();
    }

    /// <summary>缩放手柄吸附：只吸附当前被拖动的边；旋转节点不做轴对齐吸附。</summary>
    public static ResizeSnap SnapResize(
        Frame frame,
        double rotation,
        Handle handle,
        IReadOnlyList<Rect> targets,
        double threshold,
        double minSize = 12)
    {
        if (handle == Handle.Rotate || Math.Abs(rotation) > .01 || targets.Count == 0)
        {
            return ResizeSnap.None(frame);
        }

        var left = frame.X;
        var top = frame.Y;
        var right = frame.Right;
        var bottom = frame.Bottom;
        var movesLeft = handle is Handle.TopLeft or Handle.Left or Handle.BottomLeft;
        var movesRight = handle is Handle.TopRight or Handle.Right or Handle.BottomRight;
        var movesTop = handle is Handle.TopLeft or Handle.Top or Handle.TopRight;
        var movesBottom = handle is Handle.BottomLeft or Handle.Bottom or Handle.BottomRight;

        var xs = targets.SelectMany(rect => new[] { rect.Left, rect.Right }).ToList();
        var ys = targets.SelectMany(rect => new[] { rect.Top, rect.Bottom }).ToList();

        double? Nearest(List<double> values, double current)
        {
            double? best = null;
            foreach (var value in values)
            {
                var delta = value - current;
                if (Math.Abs(delta) <= threshold && (best is null || Math.Abs(delta) < Math.Abs(best.Value)))
                {
                    best = delta;
                }
            }
            return best;
        }

        var snappedLeft = false;
        var snappedRight = false;
        var snappedTop = false;
        var snappedBottom = false;
        if (movesLeft && Nearest(xs, left) is { } dl)
        {
            left += dl;
            snappedLeft = Math.Abs(dl) > .001;
        }
        if (movesRight && Nearest(xs, right) is { } dr)
        {
            right += dr;
            snappedRight = Math.Abs(dr) > .001;
        }
        if (movesTop && Nearest(ys, top) is { } dt)
        {
            top += dt;
            snappedTop = Math.Abs(dt) > .001;
        }
        if (movesBottom && Nearest(ys, bottom) is { } db)
        {
            bottom += db;
            snappedBottom = Math.Abs(db) > .001;
        }

        if (right - left < minSize)
        {
            if (movesLeft) left = right - minSize;
            else right = left + minSize;
        }
        if (bottom - top < minSize)
        {
            if (movesTop) top = bottom - minSize;
            else bottom = top + minSize;
        }

        var vertical = new List<double>();
        if (snappedLeft) vertical.Add(left);
        if (snappedRight) vertical.Add(right);
        var horizontal = new List<double>();
        if (snappedTop) horizontal.Add(top);
        if (snappedBottom) horizontal.Add(bottom);
        return new ResizeSnap(new Frame(left, top, right - left, bottom - top), vertical, horizontal);
    }

    // ------------------------------------------------------------ 几何工具

    public static Point RotatePoint(Point point, Point pivot, double degrees)
    {
        if (Math.Abs(degrees) < 1e-9) return point;
        var vector = RotateVector(new Point(point.X - pivot.X, point.Y - pivot.Y), degrees);
        return new Point(pivot.X + vector.X, pivot.Y + vector.Y);
    }

    public static Point RotateVector(Point vector, double degrees)
    {
        if (Math.Abs(degrees) < 1e-9) return vector;
        var radians = degrees * Math.PI / 180;
        var cos = Math.Cos(radians);
        var sin = Math.Sin(radians);
        return new Point(vector.X * cos - vector.Y * sin, vector.X * sin + vector.Y * cos);
    }

    public static double Distance(Point a, Point b) =>
        Math.Sqrt((a.X - b.X) * (a.X - b.X) + (a.Y - b.Y) * (a.Y - b.Y));

    /// <summary>拖动阈值：按下后移动超过 slop 才算拖动。</summary>
    public static bool DragExceeded(Point start, Point current, double slop) =>
        Distance(start, current) > slop;

    private static double shortestDelta(double from, double to)
    {
        var delta = to - from;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }
}
