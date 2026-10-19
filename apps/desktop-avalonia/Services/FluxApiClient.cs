using System;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
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
