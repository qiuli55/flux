using System;
using System.Linq;

namespace Flux.Desktop.Views;

/// <summary>
/// 设计稿渲染的纯数值换算：模糊半径、像素对齐、盒式模糊。
///
/// 这些公式必须与手机 <c>DesignSpec.kt</c>、后端 <c>design_compile.py</c> 逐项一致，
/// 否则同一份设计稿在三端会出现「一端的阴影更硬 / 更糊」。放在独立类型里是为了能在
/// <c>desktop-avalonia.tests</c> 中直接回归，不必启动 Avalonia 渲染栈。
/// </summary>
public static class DesignRenderMath
{
    /// <summary>CSS box-shadow / text-shadow 的 blur 半径是高斯标准差的两倍。</summary>
    public const double ShadowBlurSigmaScale = .5;

    /// <summary>节点级 <c>filter: blur()</c> 的参数就是标准差本身。</summary>
    public const double LayerBlurSigmaScale = 1;

    /// <summary>3 遍盒式模糊已经足够接近高斯（σ 误差 &lt; 3%）。</summary>
    public const int BlurPasses = 3;

    public static double ShadowSigma(double blur) => Math.Max(blur, 0) * ShadowBlurSigmaScale;

    public static double LayerSigma(double blur) => Math.Max(blur, 0) * LayerBlurSigmaScale;

    /// <summary>模糊核在几何外的可见半径（3σ 截断 + 1px 保护）。</summary>
    public static double BlurPadding(double blur) => Math.Ceiling(ShadowSigma(blur) * 3 + 1);

    public static double LayerBlurPadding(double blur) => Math.Ceiling(LayerSigma(blur) * 3 + 1);

    /// <summary>只有 100% / 200% 这类整数缩放才做像素吸附，任意缩放时吸附会让边缘抖动。</summary>
    public static bool IsPixelScale(double scale)
    {
        if (!double.IsFinite(scale) || scale <= 0)
        {
            return false;
        }

        var rounded = Math.Round(scale);
        return rounded >= 1 && Math.Abs(scale - rounded) <= .002;
    }

    public static double SnapToPixel(double value, double scale) =>
        !IsPixelScale(scale) ? value : Math.Round(value * scale) / scale;

    public static double SnapStrokeCenter(double center, double width, double scale)
    {
        if (!IsPixelScale(scale))
        {
            return center;
        }

        return SnapToPixel(center - width / 2, scale) + width / 2;
    }

    /// <summary>
    /// 3 遍盒式模糊的盒宽（奇数）。盒式模糊方差 = (w² − 1) / 12，
    /// 用 Kutskir 近似把总方差对齐到 σ²，边缘不会出现方形光晕。
    /// </summary>
    public static int[] BoxBlurRadii(double sigma, int passes = BlurPasses)
    {
        if (passes <= 0)
        {
            return Array.Empty<int>();
        }

        if (sigma <= .01)
        {
            return Enumerable.Repeat(1, passes).ToArray();
        }

        var square = sigma * sigma;
        var ideal = Math.Sqrt(12 * square / passes + 1);
        var lower = (int)Math.Floor(ideal);
        if (lower % 2 == 0)
        {
            lower--;
        }

        lower = Math.Max(lower, 1);
        var upper = lower + 2;
        var idealM = (12 * square - passes * lower * lower - 4.0 * passes * lower - 3.0 * passes)
            / (-4.0 * lower - 4);
        var m = Math.Clamp((int)Math.Round(idealM), 0, passes);
        var radii = new int[passes];
        for (var index = 0; index < passes; index++)
        {
            radii[index] = index < m ? lower : upper;
        }

        return radii;
    }

    /// <summary>
    /// 纯托管 ARGB 盒式模糊（premultiplied，像素顺序 BGRA/BGRX 均可）。
    /// 与手机 <c>DesignSpec.boxBlurArgb</c> 同算法，用于节点级图层模糊。
    /// </summary>
    public static void BlurArgb(int[] pixels, int width, int height, double sigma)
    {
        if (width <= 0 || height <= 0 || pixels.Length < width * height || sigma <= .01)
        {
            return;
        }

        var temp = new int[pixels.Length];
        foreach (var box in BoxBlurRadii(sigma))
        {
            var radius = (box - 1) / 2;
            if (radius <= 0)
            {
                continue;
            }

            BlurHorizontal(pixels, temp, width, height, radius);
            BlurVertical(temp, pixels, width, height, radius);
        }
    }

    private static void BlurHorizontal(int[] src, int[] dst, int w, int h, int r)
    {
        var divisor = r * 2 + 1;
        for (var y = 0; y < h; y++)
        {
            var row = y * w;
            var a = 0;
            var red = 0;
            var green = 0;
            var blue = 0;
            for (var offset = -r; offset <= r; offset++)
            {
                var pixel = src[row + Math.Clamp(offset, 0, w - 1)];
                a += (int)((uint)pixel >> 24) & 0xFF;
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }

            for (var x = 0; x < w; x++)
            {
                dst[row + x] = ((a / divisor) << 24)
                    | ((red / divisor) << 16)
                    | ((green / divisor) << 8)
                    | (blue / divisor);
                var leave = src[row + Math.Clamp(x - r, 0, w - 1)];
                var enter = src[row + Math.Clamp(x + r + 1, 0, w - 1)];
                a += ((int)((uint)enter >> 24) & 0xFF) - ((int)((uint)leave >> 24) & 0xFF);
                red += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                green += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                blue += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }

    private static void BlurVertical(int[] src, int[] dst, int w, int h, int r)
    {
        var divisor = r * 2 + 1;
        for (var x = 0; x < w; x++)
        {
            var a = 0;
            var red = 0;
            var green = 0;
            var blue = 0;
            for (var offset = -r; offset <= r; offset++)
            {
                var pixel = src[Math.Clamp(offset, 0, h - 1) * w + x];
                a += (int)((uint)pixel >> 24) & 0xFF;
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }

            for (var y = 0; y < h; y++)
            {
                dst[y * w + x] = ((a / divisor) << 24)
                    | ((red / divisor) << 16)
                    | ((green / divisor) << 8)
                    | (blue / divisor);
                var leave = src[Math.Clamp(y - r, 0, h - 1) * w + x];
                var enter = src[Math.Clamp(y + r + 1, 0, h - 1) * w + x];
                a += ((int)((uint)enter >> 24) & 0xFF) - ((int)((uint)leave >> 24) & 0xFF);
                red += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                green += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                blue += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }
}
