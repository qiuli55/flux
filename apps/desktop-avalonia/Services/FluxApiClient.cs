using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Flux.Desktop.Models;

namespace Flux.Desktop.Services;

/// <summary>后端统一响应信封。</summary>
public sealed record ApiEnvelope<T>(bool Success, string? Code, string? Message, T? Data);

/// <summary>本机后端 HTTP 客户端（/api/v1）。</summary>
public sealed class FluxApiClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
    };

    private readonly HttpClient _http;

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
    }

    public Task<ApiEnvelope<HealthInfo>?> HealthAsync(CancellationToken ct = default)
        => GetAsync<HealthInfo>("/api/v1/health", ct);

    public Task<ApiEnvelope<DshStatus>?> DshStatusAsync(CancellationToken ct = default)
        => GetAsync<DshStatus>("/api/v1/dsh/status", ct);

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
        CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (payload is not null)
            {
                request.Content = new StringContent(
                    JsonSerializer.Serialize(payload, JsonOptions),
                    Encoding.UTF8,
                    "application/json");
            }
            using var res = await _http.SendAsync(request, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return JsonSerializer.Deserialize<ApiEnvelope<T>>(body, JsonOptions);
        }
        catch (Exception ex)
        {
            DebugLog($"{method} {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    /// <summary>Base URL 去掉空白与末尾斜杠；留空表示用供应商默认地址。</summary>
    private static string NormalizeUrl(string? value)
        => string.IsNullOrWhiteSpace(value) ? "" : value.Trim().TrimEnd('/');

    private async Task<ApiEnvelope<T>?> GetAsync<T>(string path, CancellationToken ct)
    {
        try
        {
            using var res = await _http.GetAsync(path, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return JsonSerializer.Deserialize<ApiEnvelope<T>>(body, JsonOptions);
        }
        catch (Exception ex)
        {
            DebugLog($"GET {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

    private async Task<ApiEnvelope<T>?> PostAsync<T>(string path, object payload, CancellationToken ct)
    {
        try
        {
            using var res = await _http.PostAsJsonAsync(path, payload, JsonOptions, ct).ConfigureAwait(false);
            var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return JsonSerializer.Deserialize<ApiEnvelope<T>>(body, JsonOptions);
        }
        catch (Exception ex)
        {
            DebugLog($"POST {path} -> {ex.GetType().Name}: {ex.Message}");
            return null;
        }
    }

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

    public void Dispose() => _http.Dispose();
}
