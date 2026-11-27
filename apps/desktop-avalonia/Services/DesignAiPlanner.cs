using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Flux.Desktop.Services;

public enum DesignChangeKind
{
    Changed,
    Added,
    Removed,
    Reordered,
}

/// <summary>一条可读的 AI 变更；Frame 是画板绝对坐标，供画布高亮。</summary>
public sealed record DesignChange(
    DesignChangeKind Kind,
    string NodeId,
    string Title,
    string Detail,
    double X,
    double Y,
    double W,
    double H,
    bool HasFrame);

/// <summary>
/// AI 修改的待确认计划：预览 JSON 与变更清单，用户确认后才写回真源。
/// 与手机 `DesignAiPlan.kt` 同一套语义：applied 按「每个属性 / 结构操作」计 1。
/// </summary>
public sealed class DesignAiPlan
{
    public DesignAiPlan(
        JsonObject preview,
        List<DesignChange> changes,
        int applied,
        int totalOps,
        int unapplied,
        string reply)
    {
        Preview = preview;
        Changes = changes;
        Applied = applied;
        TotalOps = totalOps;
        Unapplied = unapplied;
        Reply = reply;
    }

    public JsonObject Preview { get; }

    public List<DesignChange> Changes { get; }

    public int Applied { get; }

    public int TotalOps { get; }

    public int Unapplied { get; }

    public string Reply { get; }

    public bool HasChanges => Applied > 0;

    public string StatLine => Applied <= 0
        ? "没有可应用的修改"
        : Unapplied > 0
            ? $"可应用 {Applied} 项，另有 {Unapplied} 项无法应用"
            : $"共 {Applied} 项修改";
}

public static class DesignAiPlanner
{
    private const double Epsilon = 1e-6;

    public static DesignAiPlan Plan(JsonObject design, int screenIndex, JsonArray? ops, string reply = "")
    {
        var total = ops?.Count ?? 0;
        var preview = (JsonObject)design.DeepClone();
        var applied = DesignOps.ApplyOps(preview, screenIndex, ops, out _);
        if (applied == 0)
        {
            return new DesignAiPlan(design, new List<DesignChange>(), 0, total, Math.Max(total, 1), reply);
        }

        var changes = BuildChanges(design, preview, screenIndex, ops);
        return new DesignAiPlan(
            preview,
            changes,
            applied,
            total,
            Math.Max(total - changes.Count, 0),
            reply);
    }

    /// <summary>逐条 op 生成变更描述；无法产生实际变化的 op 不出现在清单里。</summary>
    public static List<DesignChange> BuildChanges(
        JsonObject before,
        JsonObject after,
        int screenIndex,
        JsonArray? ops)
    {
        var changes = new List<DesignChange>();
        if (ops is null) return changes;
        foreach (var item in ops)
        {
            if (item is not JsonObject op) continue;
            var kind = Kind(op);
            var target = Truthy(op["target"]) ? Text(op["target"]) : "";
            switch (kind)
            {
                case "set":
                {
                    if (op["set"] is not JsonObject set || target.Length == 0) break;
                    var oldNode = FindNode(before, target);
                    var newNode = FindNode(after, target);
                    if (oldNode is null || newNode is null) break;
                    var detail = DescribeSet(oldNode, newNode, set);
                    if (detail.Length == 0) detail = $"调整了 {CountAllowed(set)} 项属性";
                    var frame = FindFrame(after, target);
                    changes.Add(new DesignChange(
                        DesignChangeKind.Changed,
                        target,
                        NodeTitle(oldNode, target),
                        detail,
                        frame.X, frame.Y, frame.W, frame.H, frame.Ok));
                    break;
                }
                case "add":
                {
                    if (op["node"] is not JsonObject raw) break;
                    var id = Text(raw["id"]);
                    if (id.Length == 0 || FindNode(before, id) is not null) break;
                    var node = FindNode(after, id);
                    if (node is null) break;
                    var frame = FindFrame(after, id);
                    changes.Add(new DesignChange(
                        DesignChangeKind.Added,
                        id,
                        $"新增「{NodeTitle(node, id)}」",
                        $"添加 {Text(node["type"])} · {Round(Num(node["w"]))}×{Round(Num(node["h"]))}",
                        frame.X, frame.Y, frame.W, frame.H, frame.Ok));
                    break;
                }
                case "remove":
                {
                    if (target.Length == 0) break;
                    var node = FindNode(before, target);
                    if (node is null || FindNode(after, target) is not null) break;
                    var frame = FindFrame(before, target);
                    changes.Add(new DesignChange(
                        DesignChangeKind.Removed,
                        target,
                        $"删除「{NodeTitle(node, target)}」",
                        $"从画布移除 {Text(node["type"])}",
                        frame.X, frame.Y, frame.W, frame.H, frame.Ok));
                    break;
                }
                case "reorder":
                {
                    if (target.Length == 0) break;
                    var node = FindNode(after, target);
                    if (node is null) break;
                    var index = Integer(op["index"]) ?? 0;
                    var frame = FindFrame(after, target);
                    changes.Add(new DesignChange(
                        DesignChangeKind.Reordered,
                        target,
                        $"调整「{NodeTitle(node, target)}」层级",
                        $"移动到第 {index + 1} 层",
                        frame.X, frame.Y, frame.W, frame.H, frame.Ok));
                    break;
                }
            }
        }

        return changes;
    }

    // ------------------------------------------------------------ 节点查询

    public static JsonObject? FindNode(JsonObject design, string id)
    {
        if (id.Length == 0 || design["screens"] is not JsonArray screens) return null;
        foreach (var item in screens)
        {
            if (item is not JsonObject screen || screen["nodes"] is not JsonArray nodes) continue;
            var found = FindIn(nodes, id);
            if (found is not null) return found;
        }

        return null;
    }

    private static JsonObject? FindIn(JsonArray nodes, string id)
    {
        foreach (var item in nodes)
        {
            if (item is not JsonObject node) continue;
            if (Text(node["id"]) == id) return node;
            if (node["children"] is JsonArray children)
            {
                var found = FindIn(children, id);
                if (found is not null) return found;
            }
        }

        return null;
    }

    /// <summary>画板绝对坐标框（父节点偏移逐层累加），与手机 `absoluteFrames` 一致。</summary>
    public static (bool Ok, double X, double Y, double W, double H) FindFrame(JsonObject design, string id)
    {
        if (design["screens"] is not JsonArray screens) return (false, 0, 0, 0, 0);
        foreach (var item in screens)
        {
            if (item is not JsonObject screen || screen["nodes"] is not JsonArray nodes) continue;
            var found = FindFrameIn(nodes, id, 0, 0);
            if (found.Ok) return found;
        }

        return (false, 0, 0, 0, 0);
    }

    private static (bool Ok, double X, double Y, double W, double H) FindFrameIn(
        JsonArray nodes,
        string id,
        double originX,
        double originY)
    {
        foreach (var item in nodes)
        {
            if (item is not JsonObject node) continue;
            var x = originX + Num(node["x"]);
            var y = originY + Num(node["y"]);
            if (Text(node["id"]) == id)
            {
                return (true, x, y, Num(node["w"]), Num(node["h"]));
            }

            if (node["children"] is JsonArray children)
            {
                var found = FindFrameIn(children, id, x, y);
                if (found.Ok) return found;
            }
        }

        return (false, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------ 人话描述

    public static string DescribeSet(JsonObject before, JsonObject after, JsonObject set)
    {
        var parts = new List<string>();
        foreach (var key in set.Select(pair => pair.Key))
        {
            if (!DesignOps.AllowedSet.Contains(key)) continue;
            var line = DescribeField(key, before, after);
            if (line is not null) parts.Add(line);
        }

        return string.Join("；", parts);
    }

    private static string? DescribeField(string key, JsonObject before, JsonObject after)
    {
        var oldText = Raw(before[key]);
        var newText = Raw(after[key]);
        if (oldText == newText) return null;
        return key switch
        {
            "text" => $"文案 {Quote(Text(before["text"]))} → {Quote(Text(after["text"]))}",
            "fontSize" => $"字号 {Round(Num(before["fontSize"]))} → {Round(Num(after["fontSize"]))}",
            "fontWeight" => $"字重 {Round(Num(before["fontWeight"]))} → {Round(Num(after["fontWeight"]))}",
            "fill" => $"填充 {ColorText(Text(before["fill"]))} → {ColorText(Text(after["fill"]))}",
            "color" => $"文字颜色 {ColorText(Text(before["color"]))} → {ColorText(Text(after["color"]))}",
            "x" => $"X {Round(Num(before["x"]))} → {Round(Num(after["x"]))}",
            "y" => $"Y {Round(Num(before["y"]))} → {Round(Num(after["y"]))}",
            "w" => $"宽度 {Round(Num(before["w"]))} → {Round(Num(after["w"]))}",
            "h" => $"高度 {Round(Num(before["h"]))} → {Round(Num(after["h"]))}",
            "radius" => $"圆角 {Round(Num(before["radius"]))} → {Round(Num(after["radius"]))}",
            "opacity" => $"不透明度 {Percent(before["opacity"])} → {Percent(after["opacity"])}",
            "align" => $"对齐 {OrDefault(Text(before["align"]), "默认")} → {OrDefault(Text(after["align"]), "默认")}",
            "lineHeight" => $"行高 {Round(Num(before["lineHeight"]))} → {Round(Num(after["lineHeight"]))}",
            "letterSpacing" => $"字距 {Round(Num(before["letterSpacing"]))} → {Round(Num(after["letterSpacing"]))}",
            "stroke" => $"描边 {StrokeText(before["stroke"])} → {StrokeText(after["stroke"])}",
            "blur" => $"模糊 {Round(Num(before["blur"]))} → {Round(Num(after["blur"]))}",
            "padLeft" => $"左内边距 {Round(Num(before["padLeft"]))} → {Round(Num(after["padLeft"]))}",
            "value" => $"进度 {Percent(before["value"])} → {Percent(after["value"])}",
            "track" => $"轨道 {ColorText(Text(before["track"]))} → {ColorText(Text(after["track"]))}",
            "on" => $"开关 {(Truthy(before["on"]) ? "开" : "关")} → {(Truthy(after["on"]) ? "开" : "关")}",
            "items" => "导航项已更新",
            "active" => $"选中项 {(Integer(before["active"]) ?? 0) + 1} → {(Integer(after["active"]) ?? 0) + 1}",
            _ => null,
        };
    }

    // ------------------------------------------------------------ JSON 工具

    private static string Kind(JsonObject op)
    {
        var value = Truthy(op["op"]) ? Text(op["op"]) : "";
        if (value.Length == 0 && op["set"] is JsonObject) value = "set";
        return value;
    }

    private static int CountAllowed(JsonObject set)
        => set.Count(pair => DesignOps.AllowedSet.Contains(pair.Key));

    private static string NodeTitle(JsonObject node, string fallback)
    {
        var name = Text(node["name"]);
        if (name.Length > 0) return name;
        var type = Text(node["type"]);
        return type.Length > 0 ? type : fallback;
    }

    private static string Raw(JsonNode? value) => value?.ToJsonString() ?? "";

    private static string Quote(string raw)
    {
        var value = raw.Replace('\n', ' ').Trim();
        if (value.Length == 0) return "（空）";
        return $"「{(value.Length > 18 ? value[..18] + "…" : value)}」";
    }

    private static string ColorText(string raw) => raw.Length == 0 ? "默认" : raw;

    private static string StrokeText(JsonNode? value)
    {
        switch (value)
        {
            case JsonObject obj:
            {
                var color = Text(obj["color"]);
                return color.Length == 0 ? "无" : $"{color} {Round(Num(obj["width"]))}px";
            }
            case JsonValue scalar:
                var text = Text(scalar);
                return text.Length == 0 || text == "transparent" ? "无" : text;
            default:
                return "无";
        }
    }

    private static string Percent(JsonNode? value)
        => value is null ? "—" : $"{Math.Round(Num(value) * 100)}%";

    private static string Round(double value)
    {
        if (double.IsNaN(value) || double.IsInfinity(value)) return "—";
        return Math.Abs(value - Math.Round(value)) < Epsilon
            ? ((long)Math.Round(value)).ToString(CultureInfo.InvariantCulture)
            : Math.Round(value, 1).ToString(CultureInfo.InvariantCulture);
    }

    private static string Text(JsonNode? value)
    {
        if (value is null) return "";
        return value.GetValueKind() switch
        {
            JsonValueKind.String => value.GetValue<string>(),
            JsonValueKind.Number => value.ToJsonString(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => "",
        };
    }

    private static double Num(JsonNode? value)
    {
        if (value is null) return 0;
        return value.GetValueKind() switch
        {
            JsonValueKind.Number => value.GetValue<double>(),
            JsonValueKind.True => 1,
            JsonValueKind.False => 0,
            JsonValueKind.String => double.TryParse(
                value.GetValue<string>().Trim(),
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out var parsed)
                ? parsed
                : double.NaN,
            _ => double.NaN,
        };
    }

    private static int? Integer(JsonNode? value)
    {
        if (value is null || value.GetValueKind() != JsonValueKind.Number) return null;
        var number = value.GetValue<double>();
        if (double.IsNaN(number) || double.IsInfinity(number) || Math.Floor(number) != number) return null;
        return (int)Math.Clamp(number, int.MinValue, int.MaxValue);
    }

    private static bool Truthy(JsonNode? value)
    {
        if (value is null) return false;
        return value.GetValueKind() switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => value.GetValue<double>() != 0,
            JsonValueKind.String => value.GetValue<string>().Length > 0,
            _ => true,
        };
    }

    private static string OrDefault(string value, string fallback)
        => value.Length == 0 ? fallback : value;
}
