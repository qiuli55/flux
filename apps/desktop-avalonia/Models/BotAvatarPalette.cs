using System;
using System.Collections.Generic;

namespace Flux.Desktop.Models;

/// <summary>
/// Bot 头像契约：形状 × 颜色。取值与顺序必须与后端 <c>flux/core/group/avatar.py</c>、
/// Web <c>BotAvatar.tsx</c>、安卓 <c>V2BotAvatar.kt</c> 保持一致。
/// </summary>
public static class BotAvatarPalette
{
    public static readonly string[] Shapes =
    {
        "circle", "squircle", "hexagon", "triangle", "diamond", "star", "flower",
        "burst", "shield", "drop", "cloud", "gear", "egg", "blob",
    };

    public static readonly string[] Colors =
    {
        "violet", "indigo", "sky", "teal", "emerald", "lime", "amber",
        "orange", "rose", "fuchsia", "slate", "cyan",
    };

    public const string DefaultShape = "circle";
    public const string DefaultColor = "violet";

    private static readonly Dictionary<string, string> HexMap = new(StringComparer.Ordinal)
    {
        ["violet"] = "#8b6cf6",
        ["indigo"] = "#6d7ef8",
        ["sky"] = "#4aa8f0",
        ["teal"] = "#21b3a6",
        ["emerald"] = "#2ecc8f",
        ["lime"] = "#9ad84a",
        ["amber"] = "#e8b25e",
        ["orange"] = "#f08a4b",
        ["rose"] = "#f0708f",
        ["fuchsia"] = "#d464e8",
        ["slate"] = "#8794a3",
        ["cyan"] = "#43d6d6",
    };

    /// <summary>未知形状回退 circle。</summary>
    public static string NormalizeShape(string? value)
        => value is not null && Array.IndexOf(Shapes, value) >= 0 ? value : DefaultShape;

    /// <summary>未知颜色回退 violet。</summary>
    public static string NormalizeColor(string? value)
        => value is not null && HexMap.ContainsKey(value) ? value : DefaultColor;

    /// <summary>颜色的十六进制值（选择器里的实心色块用）。</summary>
    public static string Hex(string? color) => HexMap[NormalizeColor(color)];

    /// <summary>每种形状的眼睛基线（避免在多边形尖角处显得贴边）。</summary>
    public static double EyeY(string shape) => shape switch
    {
        "triangle" => 38,
        "star" => 34,
        "shield" => 34,
        "drop" => 30,
        "cloud" => 35,
        "gear" => 33,
        _ => 32,
    };
}
