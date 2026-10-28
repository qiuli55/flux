using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Flux.Desktop.Models;

namespace Flux.Desktop.Services;

/// <summary>后端统一响应信封；Metadata 目前只有设计稿导入用（携带上游 warnings）。</summary>
public sealed record ApiEnvelope<T>(
    bool Success,
    string? Code,
    string? Message,
    T? Data,
    JsonObject? Metadata = null);

/// <summary>本机后端 HTTP 客户端（/api/v1）。</summary>
public sealed class FluxApiClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
    };

    private readonly HttpClient _http;

    /// <summary>AI 改写要等模型返回，Flux 代理本身给了 60s，这里留足 120s。</summary>
    private readonly HttpClient _aiHttp;

    public FluxApiClient(string baseUrl)
    {
        // 只与本机 127.0.0.1 后端通信。必须显式关闭代理：
        // HttpClient 默认会读取 HTTP_PROXY/HTTPS_PROXY 环境变量，
        // 而它不会自动绕过回环地址，机器上残留的失效代理会让本机请求全部失败。
        var handler = new HttpClientHandler { UseProxy = false };
        _http = new HttpClient(handler)
        {
            BaseAddress = new Uri(baseUrl),
            Timeout = TimeSpan.FromSeconds(30),
        };
        _aiHttp = new HttpClient(handler, disposeHandler: false)
        {
            BaseAddress = new Uri(baseUrl),
            Timeout = TimeSpan.FromSeconds(120),
        };
    }

    public Task<ApiEnvelope<HealthInfo>?> HealthAsync(CancellationToken ct = default)
        => GetAsync<HealthInfo>("/api/v1/health", ct);

    public Task<ApiEnvelope<DshStatus>?> DshStatusAsync(CancellationToken ct = default)
        => GetAsync<DshStatus>("/api/v1/dsh/status", ct);

    // --- 计费 / 成本中心 ---

    public Task<ApiEnvelope<BillingOverview>?> BillingPlansAsync(
        bool refresh = false,
        CancellationToken ct = default)
        => GetAsync<BillingOverview>(
            $"/api/v1/billing/plans{(refresh ? "?refresh=true" : "")}",
            ct);

    public Task<ApiEnvelope<BillingPrices>?> BillingPricesAsync(CancellationToken ct = default)
        => GetAsync<BillingPrices>("/api/v1/billing/prices", ct);

    public Task<ApiEnvelope<BillingRunUsage>?> BillingRunAsync(
        string runId,
        CancellationToken ct = default)
        => GetAsync<BillingRunUsage>(
            $"/api/v1/billing/runs/{Uri.EscapeDataString(runId)}",
            ct);

    public Task<ApiEnvelope<BillingRefreshResult>?> BillingRefreshAsync(
        string? plan = null,
        CancellationToken ct = default)
    {
        var suffix = string.IsNullOrWhiteSpace(plan)
            ? ""
            : $"?plan={Uri.EscapeDataString(plan)}";
        return PostAsync<BillingRefreshResult>($"/api/v1/billing/refresh{suffix}", new { }, ct);
    }

    public Task<ApiEnvelope<ReadyInfo>?> ReadyAsync(CancellationToken ct = default)
        => GetAsync<ReadyInfo>("/api/v1/health/ready", ct);

    public Task<ApiEnvelope<WorkspaceRootInfo>?> WorkspaceRootAsync(CancellationToken ct = default)
        => GetAsync<WorkspaceRootInfo>("/api/v1/workspace/root", ct);

    public Task<ApiEnvelope<List<Installation>>?> ListInstallationsAsync(CancellationToken ct = default)
        => GetAsync<List<Installation>>("/api/v1/installations", ct);

    public Task<ApiEnvelope<List<ImportedCapability>>?> ListCapabilitiesAsync(CancellationToken ct = default)
        => GetAsync<List<ImportedCapability>>("/api/v1/capabilities", ct);

    public Task<ApiEnvelope<CapabilityScanReport>?> ScanCapabilitiesAsync(CancellationToken ct = default)
        => PostAsync<CapabilityScanReport>("/api/v1/capabilities/scan", new { }, ct);

    public Task<ApiEnvelope<CapabilityImportResult>?> ImportCapabilityAsync(
        string kind,
        string name,
        CancellationToken ct = default)
        => PostAsync<CapabilityImportResult>("/api/v1/capabilities/import", new { kind, name }, ct);

    public Task<ApiEnvelope<List<Project>>?> ListProjectsAsync(CancellationToken ct = default)
        => GetAsync<List<Project>>("/api/v1/projects", ct);

    public Task<ApiEnvelope<List<Change>>?> ListPendingChangesAsync(CancellationToken ct = default)
        => GetAsync<List<Change>>("/api/v1/workspace/changes?status=pending", ct);

    public Task<ApiEnvelope<List<AgentHandle>>?> ListAgentsAsync(CancellationToken ct = default)
        => GetAsync<List<AgentHandle>>("/api/v1/agents", ct);

    public Task<ApiEnvelope<List<FluxTask>>?> ListTasksAsync(int limit = 50, CancellationToken ct = default)
        => GetAsync<List<FluxTask>>($"/api/v1/tasks?limit={limit}", ct);

    // --- M3 Solo 任务台 / Agent / Terminal ---

    public Task<ApiEnvelope<TaskDetail>?> GetTaskAsync(string taskId, CancellationToken ct = default)
        => GetAsync<TaskDetail>($"/api/v1/tasks/{Uri.EscapeDataString(taskId)}", ct);

    public Task<ApiEnvelope<TaskDetail>?> CreateTaskAsync(
        string description,
        string? projectId,
        string? agentId,
        string decisionMode = "auto",
        string? model = null,
        string? keyId = null,
        string? summary = null,
        string? summaryFrom = null,
        CancellationToken ct = default)
        => PostAsync<TaskDetail>("/api/v1/tasks", new
        {
            description,
            project_id = projectId,
            agent_id = agentId,
            decision_mode = decisionMode,
            model,
            key_id = keyId,
            summary,
            summary_from = summaryFrom,
        }, ct);

    /// <summary>GET /tasks/{id}/context：对话上下文占用（「上下文快满」提醒的口径）。</summary>
    public Task<ApiEnvelope<TaskContextUsage>?> GetTaskContextAsync(
        string taskId,
        CancellationToken ct = default)
        => GetAsync<TaskContextUsage>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/context",
            ct);

    /// <summary>POST /tasks/{id}/handoff：把这段对话压成前情摘要。</summary>
    public Task<ApiEnvelope<HandoffSummary>?> CreateHandoffSummaryAsync(
        string taskId,
        CancellationToken ct = default)
        => PostAsync<HandoffSummary>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/handoff",
            new { },
            ct);

    public Task<ApiEnvelope<List<TaskMessage>>?> ListTaskMessagesAsync(
        string taskId,
        int limit = 100,
        CancellationToken ct = default)
        => GetAsync<List<TaskMessage>>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/messages?limit={limit}",
            ct);

    public Task<ApiEnvelope<TaskMessageResult>?> CreateTaskMessageAsync(
        string taskId,
        string content,
        CancellationToken ct = default)
        => PostAsync<TaskMessageResult>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/messages",
            new { content },
            ct);

    public Task<ApiEnvelope<TaskConfirmationResult>?> UpdateConfirmationAsync(
        string taskId,
        IEnumerable<ConfirmationItem> items,
        CancellationToken ct = default)
        => SendAsync<TaskConfirmationResult>(
            HttpMethod.Put,
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/confirmation",
            new { items },
            ct);

    public Task<ApiEnvelope<TaskDetail>?> SetDecisionModeAsync(
        string taskId,
        string mode,
        CancellationToken ct = default)
        => PostAsync<TaskDetail>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/decision-mode",
            new { mode },
            ct);

    public Task<ApiEnvelope<TaskStartResult>?> StartTaskAsync(
        string taskId,
        IEnumerable<ConfirmationItem>? items,
        CancellationToken ct = default)
        => PostAsync<TaskStartResult>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/start",
            new { confirmation = items },
            ct);

    public Task<ApiEnvelope<TaskDetail>?> CancelTaskAsync(string taskId, CancellationToken ct = default)
        => PostAsync<TaskDetail>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/cancel",
            new { },
            ct);

    public Task<ApiEnvelope<TaskDecisionResult>?> ChooseDecisionAsync(
        string taskId,
        string decisionId,
        string action,
        string? option,
        string? note,
        CancellationToken ct = default)
        => PostAsync<TaskDecisionResult>(
            $"/api/v1/tasks/{Uri.EscapeDataString(taskId)}/decisions/choose",
            new { decision_id = decisionId, action, option, note },
            ct);

    public Task<ApiEnvelope<List<WorkspaceChange>>?> ListChangesAsync(
        string status = "pending",
        string? taskId = null,
        CancellationToken ct = default)
    {
        var url = $"/api/v1/workspace/changes?status={status}";
        if (!string.IsNullOrWhiteSpace(taskId))
        {
            url += $"&task_id={Uri.EscapeDataString(taskId)}";
        }

        return GetAsync<List<WorkspaceChange>>(url, ct);
    }

    public Task<ApiEnvelope<List<WorkspaceChange>>?> ApplyChangesAsync(
        IEnumerable<string> changeIds,
        CancellationToken ct = default)
        => PostAsync<List<WorkspaceChange>>(
            "/api/v1/workspace/apply",
            new { change_ids = changeIds.ToArray() },
            ct);

    public Task<ApiEnvelope<List<WorkspaceChange>>?> RejectChangesAsync(
        IEnumerable<string> changeIds,
        string? reason = null,
        CancellationToken ct = default)
        => PostAsync<List<WorkspaceChange>>(
            "/api/v1/workspace/reject",
            new { change_ids = changeIds.ToArray(), reason },
            ct);

    public Task<ApiEnvelope<WorkspaceChange>?> RollbackChangeAsync(
        string changeId,
        CancellationToken ct = default)
        => PostAsync<WorkspaceChange>(
            $"/api/v1/workspace/changes/{Uri.EscapeDataString(changeId)}/rollback",
            new { },
            ct);

    public Task<ApiEnvelope<List<AgentDetail>>?> ListAgentDetailsAsync(CancellationToken ct = default)
        => GetAsync<List<AgentDetail>>("/api/v1/agents", ct);

    public Task<ApiEnvelope<DshModelsResponse>?> DshModelsAsync(CancellationToken ct = default)
        => GetAsync<DshModelsResponse>("/api/v1/dsh/models", ct);

    public Task<ApiEnvelope<DshRun>?> StartDshRunAsync(
        string instruction,
        string? sessionId,
        string? agentId,
        string? model,
        string? keyId,
        CancellationToken ct = default)
        => PostAsync<DshRun>("/api/v1/dsh/runs", new
        {
            instruction,
            session_id = sessionId,
            agent_id = agentId,
            model,
            key_id = keyId,
        }, ct);

    public Task<ApiEnvelope<DshRun>?> GetDshRunAsync(string runId, CancellationToken ct = default)
        => GetAsync<DshRun>($"/api/v1/dsh/runs/{Uri.EscapeDataString(runId)}", ct);

    public Task<ApiEnvelope<DshRun>?> InterruptDshRunAsync(string runId, CancellationToken ct = default)
        => PostAsync<DshRun>($"/api/v1/dsh/runs/{Uri.EscapeDataString(runId)}/interrupt", new { }, ct);

    public Task<ApiEnvelope<List<TerminalSessionInfo>>?> ListTerminalSessionsAsync(
        CancellationToken ct = default)
        => GetAsync<List<TerminalSessionInfo>>("/api/v1/terminal/sessions", ct);

    public Task<ApiEnvelope<TerminalSessionInfo>?> CreateTerminalSessionAsync(
        string? runId,
        CancellationToken ct = default)
        => PostAsync<TerminalSessionInfo>("/api/v1/terminal/sessions", new { run_id = runId }, ct);

    public Task<ApiEnvelope<List<TerminalEventInfo>>?> ListTerminalEventsAsync(
        string sessionId,
        long afterSeq = 0,
        int limit = 500,
        CancellationToken ct = default)
        => GetAsync<List<TerminalEventInfo>>(
            $"/api/v1/terminal/sessions/{Uri.EscapeDataString(sessionId)}/events"
            + $"?after_seq={afterSeq}&limit={limit}",
            ct);

    public Task<ApiEnvelope<TerminalEventInfo>?> SendTerminalCommandAsync(
        string sessionId,
        string command,
        CancellationToken ct = default)
        => PostAsync<TerminalEventInfo>(
            $"/api/v1/terminal/sessions/{Uri.EscapeDataString(sessionId)}/commands",
            new { command },
            ct);

    public Task<ApiEnvelope<TerminalSessionInfo>?> StopTerminalSessionAsync(
        string sessionId,
        bool force,
        CancellationToken ct = default)
        => PostAsync<TerminalSessionInfo>(
            $"/api/v1/terminal/sessions/{Uri.EscapeDataString(sessionId)}/stop",
            new { force },
            ct);

    // --- 密钥库（API Key Vault）---

    public Task<ApiEnvelope<List<ApiKeyEntry>>?> ListApiKeysAsync(CancellationToken ct = default)
        => GetAsync<List<ApiKeyEntry>>("/api/v1/keys", ct);

    public Task<ApiEnvelope<ApiKeyEntry>?> CreateApiKeyAsync(
        string name,
        string provider,
        string baseUrl,
        string apiKey,
        string[] models,
        string wireApi,
        bool enabled,
        CancellationToken ct = default)
        => PostAsync<ApiKeyEntry>("/api/v1/keys", new
        {
            name,
            provider,
            base_url = NormalizeUrl(baseUrl),
            api_key = apiKey,
            models,
            wire_api = wireApi ?? "",
            enabled,
        }, ct);

    public Task<ApiEnvelope<ApiKeyEntry>?> UpdateApiKeyAsync(
        string keyId,
        object body,
        CancellationToken ct = default)
        => SendAsync<ApiKeyEntry>(HttpMethod.Patch, $"/api/v1/keys/{Uri.EscapeDataString(keyId)}", body, ct);

    public Task<ApiEnvelope<ApiKeyDeleteResult>?> DeleteApiKeyAsync(
        string keyId,
        CancellationToken ct = default)
        => SendAsync<ApiKeyDeleteResult>(
            HttpMethod.Delete,
            $"/api/v1/keys/{Uri.EscapeDataString(keyId)}",
            null,
            ct);

    public Task<ApiEnvelope<CloudSession>?> GetCloudSessionAsync(CancellationToken ct = default)
        => GetAsync<CloudSession>("/api/v1/cloud/session", ct);

    // --- Luma 设计稿（/api/v1/designs 代理到本机 Luma Studio） ---

    public Task<ApiEnvelope<List<DesignSummary>>?> ListDesignsAsync(CancellationToken ct = default)
        => GetAsync<List<DesignSummary>>("/api/v1/designs", ct, DesignJson.Options);

    public Task<ApiEnvelope<JsonObject>?> GetDesignAsync(string designId, CancellationToken ct = default)
        => GetAsync<JsonObject>(
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}",
            ct,
            DesignJson.Options);

    public Task<ApiEnvelope<JsonObject>?> SaveDesignAsync(
        string designId,
        JsonObject design,
        int baseVersion,
        CancellationToken ct = default)
    {
        var payload = new JsonObject
        {
            ["design"] = design.DeepClone(),
            ["base_version"] = baseVersion,
        };
        return SendAsync<JsonObject>(
            HttpMethod.Put,
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}",
            payload,
            ct,
            DesignJson.Options);
    }

    public Task<ApiEnvelope<List<DesignRevision>>?> ListDesignRevisionsAsync(
        string designId,
        CancellationToken ct = default)
        => GetAsync<List<DesignRevision>>(
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}/revisions",
            ct,
            DesignJson.Options);

    public Task<ApiEnvelope<JsonObject>?> RevertDesignAsync(
        string designId,
        int version,
        int baseVersion,
        CancellationToken ct = default)
    {
        var payload = new JsonObject
        {
            ["version"] = version,
            ["base_version"] = baseVersion,
        };
        return PostAsync<JsonObject>(
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}/revert",
            payload,
            ct,
            DesignJson.Options);
    }

    public Task<ApiEnvelope<DesignAiResult>?> AskDesignAiAsync(
        string designId,
        JsonObject payload,
        CancellationToken ct = default)
        => PostAsync<DesignAiResult>(
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}/ai",
            payload,
            ct,
            DesignJson.Options,
            _aiHttp);

    /// <summary>POST /designs/import：裸请求体上传 .svg/.fig/.sketch，16 MB 上限由后端把关。</summary>
    public async Task<ApiEnvelope<JsonObject>?> ImportDesignAsync(
        string filePath,
        string? project = null,
        CancellationToken ct = default)
    {
        var query = "?filename=" + Uri.EscapeDataString(Path.GetFileName(filePath));
        if (!string.IsNullOrWhiteSpace(project))
        {
            query += "&project=" + Uri.EscapeDataString(project);
        }

        try
        {
            await using var stream = File.OpenRead(filePath);
            using var content = new StreamContent(stream);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            return await SendContentAsync<JsonObject>(
                "/api/v1/designs/import" + query,
                content,
                ct,
                DesignJson.Options).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            DebugLog($"POST /designs/import -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    /// <summary>GET /designs/{id}/export.svg：响应体是 SVG 字节而不是 JSON，单独处理。</summary>
    public async Task<(byte[]? Svg, string? Error)> ExportDesignSvgAsync(
        string designId,
        string screen = "all",
        CancellationToken ct = default)
    {
        var path = $"/api/v1/designs/{Uri.EscapeDataString(designId)}/export.svg"
            + $"?screen={Uri.EscapeDataString(screen)}";
        try
        {
            using var res = await _http.GetAsync(path, ct).ConfigureAwait(false);
            if (res.IsSuccessStatusCode)
            {
                return (await res.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false), null);
            }

            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            var envelope = JsonSerializer.Deserialize<ApiEnvelope<JsonObject>>(body, JsonOptions);
            return (null, envelope?.Message is { Length: > 0 } message
                ? message
                : $"导出失败（HTTP {(int)res.StatusCode}）");
        }
        catch (Exception ex)
        {
            DebugLog($"GET {path} -> {ex.GetType().Name}: {ex.Message}");
            return (null, "导出失败：" + ex.Message);
        }
    }

    /// <summary>GET /designs/{id}/preview：后端渲染好的单文件交互 HTML。</summary>
    public Task<ApiEnvelope<DesignWebPreview>?> GetDesignPreviewAsync(
        string designId,
        CancellationToken ct = default)
        => GetAsync<DesignWebPreview>(
            $"/api/v1/designs/{Uri.EscapeDataString(designId)}/preview",
            ct,
            DesignJson.Options);

    public Task<ApiEnvelope<CloudTokens>?> ExchangeCodeAsync(string code, CancellationToken ct = default)
        => PostAsync<CloudTokens>("/api/v1/cloud/exchange", new { code }, ct);

    public Task<ApiEnvelope<CloudTokens>?> RefreshAsync(string refreshToken, CancellationToken ct = default)
        => PostAsync<CloudTokens>("/api/v1/cloud/refresh", new { refresh_token = refreshToken }, ct);

    public async Task LogoutAsync(CancellationToken ct = default)
    {
        try
        {
            using var res = await _http.PostAsJsonAsync("/api/v1/cloud/logout", new { }, JsonOptions, ct)
                .ConfigureAwait(false);
        }
        catch
        {
            // 登出即使失败也继续把前端状态切回未登录
        }
    }

    private async Task<ApiEnvelope<T>?> SendAsync<T>(
        HttpMethod method,
        string path,
        object? payload,
        CancellationToken ct,
        JsonSerializerOptions? options = null,
        HttpClient? client = null)
    {
        var json = options ?? JsonOptions;
        var http = client ?? _http;
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (payload is not null)
            {
                request.Content = new StringContent(
                    JsonSerializer.Serialize(payload, json),
                    Encoding.UTF8,
                    "application/json");
            }
            using var res = await http.SendAsync(request, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return FailAsNull(JsonSerializer.Deserialize<ApiEnvelope<T>>(body, json));
        }
        catch (Exception ex)
        {
            DebugLog($"{method} {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    /// <summary>收 JSON 但发的是裸字节流（设计稿导入），和 SendAsync 只差请求体。</summary>
    private async Task<ApiEnvelope<T>?> SendContentAsync<T>(
        string path,
        HttpContent content,
        CancellationToken ct,
        JsonSerializerOptions? options = null)
    {
        var json = options ?? JsonOptions;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = content };
            using var res = await _http.SendAsync(request, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return FailAsNull(JsonSerializer.Deserialize<ApiEnvelope<T>>(body, json));
        }
        catch (Exception ex)
        {
            DebugLog($"POST {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    /// <summary>Base URL 去掉空白与末尾斜杠；留空表示用供应商默认地址。</summary>
    private static string NormalizeUrl(string? value)
        => string.IsNullOrWhiteSpace(value) ? "" : value.Trim().TrimEnd('/');

    private async Task<ApiEnvelope<T>?> GetAsync<T>(
        string path,
        CancellationToken ct,
        JsonSerializerOptions? options = null)
    {
        var json = options ?? JsonOptions;
        try
        {
            using var res = await _http.GetAsync(path, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return FailAsNull(JsonSerializer.Deserialize<ApiEnvelope<T>>(body, json));
        }
        catch (Exception ex)
        {
            DebugLog($"GET {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    private async Task<ApiEnvelope<T>?> PostAsync<T>(
        string path,
        object payload,
        CancellationToken ct,
        JsonSerializerOptions? options = null,
        HttpClient? client = null)
    {
        var json = options ?? JsonOptions;
        var http = client ?? _http;
        try
        {
            using var res = await http.PostAsJsonAsync(path, payload, json, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return FailAsNull(JsonSerializer.Deserialize<ApiEnvelope<T>>(body, json));
        }
        catch (Exception ex)
        {
            DebugLog($"POST {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// 失败响应的 data 常带 {task_id,status} 这类诊断明细，直接反序列化会被调用方当成正常数据。
    /// 统一把失败响应的 Data 置空，调用方只能按 Message 报错，避免空字段引发空引用。
    /// </summary>
    private static ApiEnvelope<T>? FailAsNull<T>(ApiEnvelope<T>? envelope)
        => envelope is { Success: false } ? envelope with { Data = default } : envelope;

    /// <summary>请求异常只记一行到临时目录，便于排查「后端未响应」这类被吞掉的失败。</summary>
    private static void DebugLog(string message)
    {
        try
        {
            var path = Path.Combine(Path.GetTempPath(), "flux-api-debug.log");
            File.AppendAllText(path, $"{DateTime.Now:HH:mm:ss} {message}{Environment.NewLine}");
        }
        catch
        {
            // 诊断日志失败不影响到主流程
        }
    }

    public void Dispose()
    {
        _aiHttp.Dispose();
        _http.Dispose();
    }
}
