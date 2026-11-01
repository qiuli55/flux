using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Flux.Desktop.Models;

/// <summary>
/// 设计稿 JSON 用 camelCase 的节点字段（fontSize / lineHeight），不能套用接口默认的
/// snake_case 命名策略；这里只做大小写不敏感匹配，顶层 snake_case 字段单独标注。
/// </summary>
public static class DesignJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        Converters =
        {
            new DesignRadiusConverter(),
            new DesignStrokeConverter(),
            new DesignShadowConverter(),
        },
    };
}

/// <summary>GET /api/v1/designs 的列表项（Luma 的 design_summary）。</summary>
public sealed class DesignSummary
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Project { get; set; }
    [JsonPropertyName("project_id")] public string? ProjectId { get; set; }
    public string? Description { get; set; }
    public string? Status { get; set; }
    public string? Bg { get; set; }
    public int Version { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
    [JsonPropertyName("screen_count")] public int ScreenCount { get; set; }
    [JsonPropertyName("layer_count")] public int LayerCount { get; set; }
    public DesignPreview? Preview { get; set; }
}

/// <summary>列表缩略图：首屏尺寸与最多 48 个顶层节点。</summary>
public sealed class DesignPreview
{
    public double? W { get; set; }
    public double? H { get; set; }
    public string? Bg { get; set; }
    public List<DesignNode>? Nodes { get; set; }
}

/// <summary>GET /api/v1/designs/{id}/revisions 的一项。</summary>
public sealed class DesignRevision
{
    public int Version { get; set; }
    public string? Name { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
    [JsonPropertyName("screen_count")] public int ScreenCount { get; set; }
    [JsonPropertyName("layer_count")] public int LayerCount { get; set; }
    [JsonPropertyName("size_bytes")] public long SizeBytes { get; set; }
}

/// <summary>整份设计稿的只读视图；保存与 AI 补丁始终作用于原始 JsonObject。</summary>
public sealed class DesignDocument
{
    public string? Id { get; set; }
    public string? Name { get; set; }
    public string? Project { get; set; }
    [JsonPropertyName("project_id")] public string? ProjectId { get; set; }
    public string? Description { get; set; }
    public string? Status { get; set; }
    public string? Bg { get; set; }
    public string? File { get; set; }
    [JsonPropertyName("created_at")] public string? CreatedAt { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
    public int Version { get; set; }
    public List<DesignScreen>? Screens { get; set; }
}

/// <summary>一块画板；移动稿用 w/h，桌面稿另有 x/y 排布。</summary>
public sealed class DesignScreen
{
    public string? Id { get; set; }
    public string? Name { get; set; }
    public double? W { get; set; }
    public double? H { get; set; }
    public double? X { get; set; }
    public double? Y { get; set; }
    public string? Bg { get; set; }
    public List<DesignNode>? Nodes { get; set; }
}

/// <summary>
/// 画布节点。字段与 web/app.js 的配色/排版一一对应；数值可缺省，
/// 缺省时按 Web 的 CSS 初值处理（透明度 1、行高 1.35、字号 13 等）。
/// </summary>
public sealed class DesignNode
{
    public string? Id { get; set; }
    public string? Type { get; set; }
    public string? Name { get; set; }
    public double? X { get; set; }
    public double? Y { get; set; }
    public double? W { get; set; }
    public double? H { get; set; }
    public string? Fill { get; set; }
    public DesignRadius? Radius { get; set; }
    public string? Text { get; set; }
    public double? FontSize { get; set; }
    public string? Color { get; set; }
    public double? FontWeight { get; set; }
    public string? Align { get; set; }
    public double? LineHeight { get; set; }
    public double? LetterSpacing { get; set; }

    /// <summary>字族枚举：system / noto-sans-sc / noto-serif-sc / inter / mono。</summary>
    public string? FontFamily { get; set; }

    /// <summary>文字行数上限 1..64，缺省 3。</summary>
    public int? MaxLines { get; set; }

    /// <summary>单行放不下时按 0.5 步长缩字号，最低 60%。</summary>
    public bool? AutoFit { get; set; }

    public bool? Italic { get; set; }
    public bool? Underline { get; set; }
    public bool? Strikethrough { get; set; }

    /// <summary>文字渐变，语法与 Fill 相同（linear-gradient(...)）。</summary>
    public string? TextFill { get; set; }
    public DesignTextStroke? TextStroke { get; set; }
    public DesignTextEffect? TextGlow { get; set; }
    public DesignTextEffect? TextShadow { get; set; }
    /// <summary>节点描边：字符串等价于 1px 内描边，也可写成对象（虚线 / 单边）。</summary>
    public DesignStroke? Stroke { get; set; }
    public double? PadLeft { get; set; }
    public double? Rotation { get; set; }
    public double? Value { get; set; }
    public string? Track { get; set; }

    /// <summary>节点级模糊：IR 保留原值，桌面端与手机端一样忽略绘制（仅网页支持）。</summary>
    public double? Blur { get; set; }

    public DesignShadow? Shadow { get; set; }
    public DesignShadow? InsetShadow { get; set; }
    public double? Opacity { get; set; }
    public bool? Hidden { get; set; }
    public bool? On { get; set; }
    public int? Active { get; set; }
    public List<string>? Items { get; set; }
    public List<DesignNode>? Children { get; set; }

    /// <summary>浅拷贝：演示态（滑杆/开关/导航）只改少量字段，避免整棵树深拷贝。</summary>
    public DesignNode ShallowCopy() => (DesignNode)MemberwiseClone();
}

/// <summary>
/// 四角圆角。缺省角为 null，绘制时逐角回落到类型默认值（与手机 `DesignSpec.cornersOf`、
/// 后端 `radius_of` 一致）。JSON 支持数字 / [tl,tr,br,bl] / {tl,tr,br,bl}。
/// </summary>
public sealed class DesignRadius
{
    public double? Tl { get; set; }
    public double? Tr { get; set; }
    public double? Br { get; set; }
    public double? Bl { get; set; }

    public bool Uniform => Tl is not null && Tl == Tr && Tr == Br && Br == Bl;

    public static DesignRadius All(double value) => new() { Tl = value, Tr = value, Br = value, Bl = value };
}

public sealed class DesignRadiusConverter : JsonConverter<DesignRadius>
{
    public override DesignRadius? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        switch (reader.TokenType)
        {
            case JsonTokenType.Null:
                return null;
            case JsonTokenType.Number:
                return DesignRadius.All(reader.GetDouble());
            case JsonTokenType.StartArray:
            {
                var values = new List<double?>();
                while (reader.Read() && reader.TokenType != JsonTokenType.EndArray)
                {
                    values.Add(reader.TokenType == JsonTokenType.Number ? reader.GetDouble() : null);
                }

                return new DesignRadius
                {
                    Tl = values.Count > 0 ? values[0] : null,
                    Tr = values.Count > 1 ? values[1] : null,
                    Br = values.Count > 2 ? values[2] : null,
                    Bl = values.Count > 3 ? values[3] : null,
                };
            }

            case JsonTokenType.StartObject:
            {
                var radius = new DesignRadius();
                while (reader.Read() && reader.TokenType != JsonTokenType.EndObject)
                {
                    if (reader.TokenType != JsonTokenType.PropertyName)
                    {
                        continue;
                    }

                    var name = (reader.GetString() ?? "").Trim().ToLowerInvariant();
                    reader.Read();
                    if (reader.TokenType != JsonTokenType.Number)
                    {
                        reader.Skip();
                        continue;
                    }

                    var value = reader.GetDouble();
                    switch (name)
                    {
                        case "tl":
                        case "topleft":
                            radius.Tl = value;
                            break;
                        case "tr":
                        case "topright":
                            radius.Tr = value;
                            break;
                        case "br":
                        case "bottomright":
                            radius.Br = value;
                            break;
                        case "bl":
                        case "bottomleft":
                            radius.Bl = value;
                            break;
                    }
                }

                return radius;
            }

            default:
                reader.Skip();
                return null;
        }
    }

    public override void Write(Utf8JsonWriter writer, DesignRadius value, JsonSerializerOptions options)
    {
        if (value.Uniform)
        {
            writer.WriteNumberValue(value.Tl!.Value);
            return;
        }

        writer.WriteStartObject();
        if (value.Tl.HasValue)
        {
            writer.WriteNumber("tl", value.Tl.Value);
        }

        if (value.Tr.HasValue)
        {
            writer.WriteNumber("tr", value.Tr.Value);
        }

        if (value.Br.HasValue)
        {
            writer.WriteNumber("br", value.Br.Value);
        }

        if (value.Bl.HasValue)
        {
            writer.WriteNumber("bl", value.Bl.Value);
        }

        writer.WriteEndObject();
    }
}

/// <summary>节点描边；字符串等价于 <c>{ color, width: 1, align: "inside" }</c>。</summary>
public sealed class DesignStroke
{
    public string? Color { get; set; }
    public double Width { get; set; } = 1;
    public string Align { get; set; } = "inside";
    public List<double>? Dash { get; set; }
    public List<string>? Sides { get; set; }

    public bool IsPlain =>
        Math.Abs(Width - 1) < .0001 &&
        string.Equals(Align, "inside", StringComparison.OrdinalIgnoreCase) &&
        (Dash is null || Dash.Count == 0) &&
        (Sides is null || Sides.Count == 0);
}

public sealed class DesignStrokeConverter : JsonConverter<DesignStroke>
{
    public override DesignStroke? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null)
        {
            return null;
        }

        var element = JsonElement.ParseValue(ref reader);
        if (element.ValueKind == JsonValueKind.String)
        {
            return new DesignStroke { Color = element.GetString() };
        }

        if (element.ValueKind != JsonValueKind.Object)
        {
            // 与手机端一致：非对象一律当作颜色字符串，解析失败时整段描边丢弃。
            return new DesignStroke { Color = element.ToString() };
        }

        var stroke = new DesignStroke();
        foreach (var property in element.EnumerateObject())
        {
            switch (property.Name.Trim().ToLowerInvariant())
            {
                case "color":
                    if (property.Value.ValueKind == JsonValueKind.String)
                    {
                        stroke.Color = property.Value.GetString();
                    }

                    break;
                case "width":
                    if (property.Value.TryGetDouble(out var width))
                    {
                        stroke.Width = width;
                    }

                    break;
                case "align":
                    if (property.Value.ValueKind == JsonValueKind.String)
                    {
                        stroke.Align = property.Value.GetString() ?? "inside";
                    }

                    break;
                case "dash":
                    stroke.Dash = ReadNumbers(property.Value);
                    break;
                case "sides":
                    stroke.Sides = ReadStrings(property.Value);
                    break;
            }
        }

        return stroke;
    }

    public override void Write(Utf8JsonWriter writer, DesignStroke value, JsonSerializerOptions options)
    {
        if (value.IsPlain)
        {
            writer.WriteStringValue(value.Color ?? "");
            return;
        }

        writer.WriteStartObject();
        if (!string.IsNullOrEmpty(value.Color))
        {
            writer.WriteString("color", value.Color);
        }

        if (Math.Abs(value.Width - 1) > .0001)
        {
            writer.WriteNumber("width", value.Width);
        }

        if (!string.Equals(value.Align, "inside", StringComparison.OrdinalIgnoreCase))
        {
            writer.WriteString("align", value.Align);
        }

        if (value.Dash is { Count: > 0 })
        {
            writer.WriteStartArray("dash");
            foreach (var dash in value.Dash)
            {
                writer.WriteNumberValue(dash);
            }

            writer.WriteEndArray();
        }

        if (value.Sides is { Count: > 0 })
        {
            writer.WriteStartArray("sides");
            foreach (var side in value.Sides)
            {
                writer.WriteStringValue(side);
            }

            writer.WriteEndArray();
        }

        writer.WriteEndObject();
    }

    private static List<double> ReadNumbers(JsonElement element)
    {
        var values = new List<double>();
        if (element.ValueKind != JsonValueKind.Array)
        {
            return values;
        }

        foreach (var item in element.EnumerateArray())
        {
            if (item.TryGetDouble(out var value))
            {
                values.Add(value);
            }
        }

        return values;
    }

    private static List<string> ReadStrings(JsonElement element)
    {
        var values = new List<string>();
        if (element.ValueKind != JsonValueKind.Array)
        {
            return values;
        }

        foreach (var item in element.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.String)
            {
                values.Add(item.GetString() ?? "");
            }
        }

        return values;
    }
}

/// <summary>节点阴影；非对象（如字符串）等价于透明阴影，即不画。</summary>
public sealed class DesignShadow
{
    public double Dx { get; set; }
    public double Dy { get; set; }
    public double Blur { get; set; }
    public string? Color { get; set; }
    public bool Inset { get; set; }
}

public sealed class DesignShadowConverter : JsonConverter<DesignShadow>
{
    public override DesignShadow? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null)
        {
            return null;
        }

        var element = JsonElement.ParseValue(ref reader);
        if (element.ValueKind != JsonValueKind.Object)
        {
            return new DesignShadow { Color = "transparent" };
        }

        var shadow = new DesignShadow();
        foreach (var property in element.EnumerateObject())
        {
            switch (property.Name.Trim().ToLowerInvariant())
            {
                case "dx":
                    if (property.Value.TryGetDouble(out var dx))
                    {
                        shadow.Dx = dx;
                    }

                    break;
                case "dy":
                    if (property.Value.TryGetDouble(out var dy))
                    {
                        shadow.Dy = dy;
                    }

                    break;
                case "blur":
                    if (property.Value.TryGetDouble(out var blur))
                    {
                        shadow.Blur = blur;
                    }

                    break;
                case "color":
                    if (property.Value.ValueKind == JsonValueKind.String)
                    {
                        shadow.Color = property.Value.GetString();
                    }

                    break;
                case "inset":
                    shadow.Inset = IsTrue(property.Value);
                    break;
            }
        }

        return shadow;
    }

    public override void Write(Utf8JsonWriter writer, DesignShadow value, JsonSerializerOptions options)
    {
        writer.WriteStartObject();
        writer.WriteNumber("dx", value.Dx);
        writer.WriteNumber("dy", value.Dy);
        writer.WriteNumber("blur", value.Blur);
        if (!string.IsNullOrEmpty(value.Color))
        {
            writer.WriteString("color", value.Color);
        }

        if (value.Inset)
        {
            writer.WriteBoolean("inset", true);
        }

        writer.WriteEndObject();
    }

    private static bool IsTrue(JsonElement element) => element.ValueKind switch
    {
        JsonValueKind.True => true,
        JsonValueKind.Number => element.TryGetDouble(out var number) && Math.Abs(number) > .0001,
        JsonValueKind.String => (element.GetString() ?? "").Trim().ToLowerInvariant()
            is "true" or "1" or "yes" or "on",
        _ => false,
    };
}

/// <summary>文字描边；Align 为 outside 时视觉宽度是 inside 的两倍（webkit 居中描边）。</summary>
public sealed class DesignTextStroke
{
    public double? Width { get; set; }
    public string? Color { get; set; }
    public string? Align { get; set; }
}

/// <summary>文字发光/投影；glow 用 Radius，shadow 用 Dx/Dy + Blur。</summary>
public sealed class DesignTextEffect
{
    public double? Dx { get; set; }
    public double? Dy { get; set; }
    public double? Blur { get; set; }
    public double? Radius { get; set; }
    public double? Intensity { get; set; }
    public string? Color { get; set; }
}

/// <summary>GET /api/v1/designs/{id}/preview 的返回：后端渲染好的单文件交互 HTML。</summary>
public sealed class DesignWebPreview
{
    [JsonPropertyName("design_id")] public string? DesignId { get; set; }
    public string? Name { get; set; }
    public int Version { get; set; }
    [JsonPropertyName("screen_count")] public int ScreenCount { get; set; }
    [JsonPropertyName("size_bytes")] public long SizeBytes { get; set; }
    public string? Html { get; set; }
}

/// <summary>POST /api/v1/designs/{id}/ai 的 data。</summary>
public sealed class DesignAiResult
{
    public string? Reply { get; set; }
    public JsonArray? Ops { get; set; }
    public string? Model { get; set; }
    public string? Mode { get; set; }
}
