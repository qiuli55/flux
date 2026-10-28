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
/// 缺省时按 Web 的 CSS 初值处理（透明度 1、行高 1.4 等）。
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
    public double? Radius { get; set; }
    public string? Text { get; set; }
    public double? FontSize { get; set; }
    public string? Color { get; set; }
    public double? FontWeight { get; set; }
    public string? Align { get; set; }
    public double? LineHeight { get; set; }
    public double? LetterSpacing { get; set; }
    public string? Stroke { get; set; }
    public double? Sw { get; set; }
    public double? PadLeft { get; set; }
    public double? Value { get; set; }
    public string? Track { get; set; }
    public double? Blur { get; set; }
    public string? Shadow { get; set; }
    public double? Opacity { get; set; }
    public bool? Hidden { get; set; }
    public bool? On { get; set; }
    public int? Active { get; set; }
    public List<string>? Items { get; set; }
    public List<DesignNode>? Children { get; set; }
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
