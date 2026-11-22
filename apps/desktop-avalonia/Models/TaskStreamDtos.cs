using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Flux.Desktop.Models;

/// <summary>SSE 帧：`event: task.event` + `data: {seq,type,data}`。</summary>
public sealed record TaskStreamEvent(long Seq, string Type, JsonElement Data);

/// <summary>GET /tasks/{id}/runs 的单条 Run 概览（TaskRunSummary）。</summary>
public sealed record TaskRunSummary(
    string RunId,
    string? TaskId,
    string? AgentId,
    string Status,
    string? StartedAt,
    string? FinishedAt,
    double? DurationMs,
    int Commands,
    int FilesRead,
    int FilesChanged);

/// <summary>GET /tasks/{id}/runs/{run_id}/trace 的单个步骤。</summary>
public sealed record RunTraceItem(
    long? Seq,
    string Kind,
    string? Category,
    string? Text,
    string? Name,
    string? Detail,
    string? Output,
    string? Status,
    string? CallId,
    string[]? Paths);

/// <summary>GET /tasks/{id}/runs/{run_id}/trace 的完整轨迹。</summary>
public sealed record RunTrace(
    string RunId,
    string Status,
    double? DurationMs,
    int Commands,
    int FilesRead,
    int FilesChanged,
    RunTraceItem[]? Items);

/// <summary>SSE data 反序列化用的 JSON 选项（与后端 snake_case 对齐）。</summary>
public static class TaskStreamJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
    };

    public static string ReadString(JsonElement data, string name)
        => data.ValueKind == JsonValueKind.Object
            && data.TryGetProperty(name, out var value)
            && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? ""
            : "";

    public static string[] ReadStringArray(JsonElement data, string name)
    {
        if (data.ValueKind != JsonValueKind.Object
            || !data.TryGetProperty(name, out var value)
            || value.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        return value.EnumerateArray()
            .Where(item => item.ValueKind == JsonValueKind.String)
            .Select(item => item.GetString() ?? "")
            .Where(text => text.Length > 0)
            .ToArray();
    }

    public static TaskMessage? ReadMessage(JsonElement data)
    {
        if (data.ValueKind != JsonValueKind.Object
            || !data.TryGetProperty("message", out var message)
            || message.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        try
        {
            return message.Deserialize<TaskMessage>(Options);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
