using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Flux.Desktop.Services;

/// <summary>
/// Luma 结构补丁（set / add / remove / reorder）在桌面端的执行器。
/// 语义严格对齐 web/app.js 的 aiApplyPatch / aiApplyOps：
/// 只接受白名单属性、按同样的规则归一化数值、每处理一个属性计 1 项修改。
/// 纯 System.Text.Json.Nodes 实现，便于单元测试。
/// </summary>
public static class DesignOps
{
    public static readonly HashSet<string> AllowedSet = new(StringComparer.Ordinal)
    {
        "x", "y", "w", "h", "radius", "fill", "color", "text",
        "fontSize", "fontWeight", "opacity", "letterSpacing",
        "stroke", "lineHeight", "align", "blur", "padLeft",
        "value", "track", "on", "items", "active",
    };

    private static readonly JsonSerializerOptions SnapshotJson = new() { WriteIndented = false };

    /// <summary>取第 index 块画板；越界返回 null。</summary>
    public static JsonObject? FindScreen(JsonObject design, int index)
    {
        if (design["screens"] is not JsonArray screens) return null;
        if (index < 0 || index >= screens.Count) return null;
        return screens[index] as JsonObject;
    }

    /// <summary>AI 请求里的 screen 快照：画板尺寸 + 全部顶层节点的浅快照。</summary>
    public static JsonObject BuildAskScreen(JsonObject screen)
    {
        var result = new JsonObject
        {
            ["id"] = Text(screen["id"]),
            ["name"] = Text(screen["name"]),
            ["w"] = Rounded(Num(screen["w"])),
            ["h"] = Rounded(Num(screen["h"])),
            ["nodes"] = SnapshotNodes(screen["nodes"] as JsonArray),
        };
        return result;
    }

    /// <summary>顶层节点全量快照；子层最多 80 个，只展开两层（与 Web 一致）。</summary>
    public static JsonArray SnapshotNodes(JsonArray? nodes)
    {
        var result = new JsonArray();
        if (nodes is null) return result;
        foreach (var item in nodes)
        {
            if (item is JsonObject node) result.Add(Snapshot(node, 0));
        }
        return result;
    }

    private static JsonObject Snapshot(JsonObject node, int depth)
    {
        var snap = new JsonObject
        {
            ["id"] = Text(node["id"]),
            ["type"] = Text(node["type"]),
            ["name"] = Text(node["name"]),
            ["x"] = Rounded(Num(node["x"])),
            ["y"] = Rounded(Num(node["y"])),
            ["w"] = Rounded(Num(node["w"])),
            ["h"] = Rounded(Num(node["h"])),
            ["text"] = Text(node["text"]),
        };
        if (depth < 2 && node["children"] is JsonArray children && children.Count > 0)
        {
            var list = new JsonArray();
            var limit = Math.Min(children.Count, 80);
            for (var i = 0; i < limit; i++)
            {
                if (children[i] is JsonObject child) list.Add(Snapshot(child, depth + 1));
            }
            if (list.Count > 0) snap["children"] = list;
        }
        return snap;
    }

    /// <summary>
    /// 对第 screenIndex 块画板执行补丁，返回应用成功的条目数。
    /// 为 0 时 error 给出可直接展示的失败原因。
    /// </summary>
    public static int ApplyOps(JsonObject design, int screenIndex, JsonArray? ops, out string? error)
    {
        error = null;
        var screen = FindScreen(design, screenIndex);
        if (screen is null)
        {
            error = "当前画板不存在";
            return 0;
        }
        var nodes = EnsureNodes(screen);
        var applied = 0;
        if (ops is not null)
        {
            foreach (var item in ops)
            {
                if (item is not JsonObject op) continue;
                applied += ApplyOp(design, screen, nodes, op);
            }
        }
        if (applied == 0) error = "AI 给的修改无法应用";
        return applied;
    }

    private static int ApplyOp(JsonObject design, JsonObject screen, JsonArray nodes, JsonObject op)
    {
        var kind = Kind(op);
        var target = Truthy(op["target"]) ? Text(op["target"]) : "";
        switch (kind)
        {
            case "set":
                if (op["set"] is not JsonObject set || target.Length == 0) return 0;
                var hit = Find(design, target);
                return hit is null ? 0 : ApplyPatch(hit.Node, set);
            case "add":
                return Add(design, nodes, target, op);
            case "remove":
                return Remove(design, target);
            case "reorder":
                return Reorder(design, target, op);
            default:
                return 0;
        }
    }

    private static int Add(JsonObject design, JsonArray nodes, string target, JsonObject op)
    {
        if (op["node"] is not JsonObject raw) return 0;
        var node = (JsonObject)raw.DeepClone();
        var id = Text(node["id"]);
        if (id.Length == 0 || Text(node["type"]).Length == 0) return 0;
        if (Find(design, id) is not null) return 0;

        var list = nodes;
        if (target.Length > 0 && target != "@screen")
        {
            var hit = Find(design, target);
            if (hit is null) return 0;
            list = EnsureChildren(hit.Node);
        }
        var index = Integer(op["index"]);
        var at = index is null ? list.Count : Math.Clamp(index.Value, 0, list.Count);
        list.Insert(at, node);
        return 1;
    }

    private static int Remove(JsonObject design, string target)
    {
        var hit = target.Length == 0 ? null : Find(design, target);
        if (hit is null || hit.Index < 0 || hit.Index >= hit.List.Count) return 0;
        hit.List.RemoveAt(hit.Index);
        return 1;
    }

    private static int Reorder(JsonObject design, string target, JsonObject op)
    {
        var hit = target.Length == 0 ? null : Find(design, target);
        if (hit is null || hit.Index < 0 || hit.Index >= hit.List.Count) return 0;
        hit.List.RemoveAt(hit.Index);
        var index = Integer(op["index"]);
        var at = index is null ? hit.List.Count : Math.Clamp(index.Value, 0, hit.List.Count);
        hit.List.Insert(at, hit.Node);
        return 1;
    }

    /// <summary>应用一组属性；只接受白名单键，每个被处理的键计 1 项。</summary>
    public static int ApplyPatch(JsonObject node, JsonObject set)
    {
        var count = 0;
        foreach (var pair in set)
        {
            if (!AllowedSet.Contains(pair.Key)) continue;
            var value = pair.Value;
            switch (pair.Key)
            {
                case "w":
                case "h":
                    SetNumber(node, pair.Key, Math.Max(1, Num(value)));
                    break;
                case "radius":
                case "blur":
                case "padLeft":
                    SetNumber(node, pair.Key, Math.Max(0, Num(value)));
                    break;
                case "opacity":
                case "value":
                    SetNumber(node, pair.Key, Math.Clamp(Num(value), 0, 1));
                    break;
                case "fontSize":
                    SetNumber(node, pair.Key, Math.Max(6, Num(value)));
                    break;
                case "fontWeight":
                    var weight = Num(value);
                    SetNumber(node, pair.Key, double.IsNaN(weight) || weight == 0 ? 400 : weight);
                    break;
                case "lineHeight":
                case "letterSpacing":
                case "x":
                case "y":
                    SetNumber(node, pair.Key, Num(value));
                    break;
                case "on":
                    node[pair.Key] = Truthy(value);
                    break;
                case "active":
                    SetNumber(node, pair.Key, Num(value));
                    break;
                case "items" when value is JsonArray items:
                    var sliced = new JsonArray();
                    for (var i = 0; i < Math.Min(items.Count, 12); i++)
                    {
                        sliced.Add(items[i]?.DeepClone());
                    }
                    node[pair.Key] = sliced;
                    break;
                default:
                    node[pair.Key] = value?.DeepClone();
                    break;
            }
            count++;
        }
        return count;
    }

    private sealed record Hit(JsonObject Node, JsonArray List, int Index, JsonObject? Parent);

    private static Hit? Find(JsonObject design, string id)
    {
        if (id.Length == 0 || design["screens"] is not JsonArray screens) return null;
        foreach (var item in screens)
        {
            if (item is not JsonObject screen || screen["nodes"] is not JsonArray nodes) continue;
            var hit = FindIn(nodes, id, null);
            if (hit is not null) return hit;
        }
        return null;
    }

    private static Hit? FindIn(JsonArray list, string id, JsonObject? parent)
    {
        for (var i = 0; i < list.Count; i++)
        {
            if (list[i] is not JsonObject node) continue;
            if (Text(node["id"]) == id) return new Hit(node, list, i, parent);
            if (node["children"] is JsonArray children && children.Count > 0)
            {
                var deeper = FindIn(children, id, node);
                if (deeper is not null) return deeper;
            }
        }
        return null;
    }

    private static JsonArray EnsureNodes(JsonObject screen)
    {
        if (screen["nodes"] is JsonArray nodes) return nodes;
        nodes = new JsonArray();
        screen["nodes"] = nodes;
        return nodes;
    }

    private static JsonArray EnsureChildren(JsonObject node)
    {
        if (node["children"] is JsonArray children) return children;
        children = new JsonArray();
        node["children"] = children;
        return children;
    }

    private static string Kind(JsonObject op)
    {
        var value = op["op"];
        var kind = Truthy(value) ? Text(value) : "";
        if (kind.Length == 0 && op["set"] is JsonObject) kind = "set";
        return kind;
    }

    private static string Text(JsonNode? value)
    {
        if (value is null) return "";
        switch (value.GetValueKind())
        {
            case JsonValueKind.String:
                return value.GetValue<string>();
            case JsonValueKind.Number:
                return value.ToJsonString(SnapshotJson);
            case JsonValueKind.True:
                return "true";
            case JsonValueKind.False:
                return "false";
            default:
                return "";
        }
    }

    /// <summary>JS Number() 语义；无法转换时返回 NaN。</summary>
    private static double Num(JsonNode? value)
    {
        if (value is null) return 0;
        switch (value.GetValueKind())
        {
            case JsonValueKind.Number:
                return value.GetValue<double>();
            case JsonValueKind.True:
                return 1;
            case JsonValueKind.False:
                return 0;
            case JsonValueKind.String:
                var text = value.GetValue<string>().Trim();
                if (text.Length == 0) return 0;
                return double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed)
                    ? parsed
                    : double.NaN;
            default:
                return double.NaN;
        }
    }

    /// <summary>JS Number.isInteger() 语义。</summary>
    private static int? Integer(JsonNode? value)
    {
        if (value is null || value.GetValueKind() != JsonValueKind.Number) return null;
        var number = value.GetValue<double>();
        if (double.IsNaN(number) || double.IsInfinity(number) || Math.Floor(number) != number) return null;
        return (int)Math.Clamp(number, int.MinValue, int.MaxValue);
    }

    /// <summary>JS Boolean() 语义（空数组/空对象为 true）。</summary>
    private static bool Truthy(JsonNode? value)
    {
        if (value is null) return false;
        switch (value.GetValueKind())
        {
            case JsonValueKind.True:
                return true;
            case JsonValueKind.False:
                return false;
            case JsonValueKind.Number:
                var number = value.GetValue<double>();
                return number != 0 && !double.IsNaN(number);
            case JsonValueKind.String:
                return value.GetValue<string>().Length > 0;
            default:
                return true;
        }
    }

    private static void SetNumber(JsonObject node, string key, double value)
    {
        if (double.IsNaN(value) || double.IsInfinity(value)) value = 0;
        node[key] = Math.Abs(value - Math.Round(value)) < 1e-9 ? (int)Math.Round(value) : value;
    }

    private static double Rounded(double value)
        => double.IsNaN(value) || double.IsInfinity(value) ? 0 : Math.Round(value, 4);
}
