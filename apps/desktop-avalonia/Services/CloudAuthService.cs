using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Flux.Desktop.Models;

namespace Flux.Desktop.Services;

/// <summary>
/// 云登录编排：打开系统浏览器 → 等待回环回跳拿到 code → 本机后端换取 token。
/// </summary>
public sealed class CloudAuthService
{
    private readonly FluxApiClient _api;
    private readonly LoopbackCodeListener _listener;

    public CloudAuthService(FluxApiClient api, LoopbackCodeListener listener)
    {
        _api = api;
        _listener = listener;
    }

    /// <summary>回环回跳地址，需要作为 FLUX_CLOUD_RETURN_URL 注入后端。</summary>
    public string CallbackUrl => _listener.CallbackUrl;

    /// <summary>等待回跳的最长时间，需与账号云 state 的 10 分钟有效期对齐，留出输入密码/二次验证的余量。</summary>
    private static readonly TimeSpan LoginTimeout = TimeSpan.FromMinutes(10);

    /// <summary>登录结果：成功时带令牌，失败时带上可直接展示的原因。</summary>
    public sealed record LoginOutcome(CloudTokens? Tokens, string? Error);

    public async Task<LoginOutcome> LoginAsync(string loginUrl, CancellationToken ct = default)
    {
        using var browser = Process.Start(new ProcessStartInfo(loginUrl) { UseShellExecute = true });

        var code = await _listener.WaitForCodeAsync(LoginTimeout, ct).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(code))
        {
            return new LoginOutcome(
                null,
                $"等待浏览器回跳超过 {LoginTimeout.TotalMinutes:0} 分钟，未收到登录码，请重试。");
        }

        var envelope = await _api.ExchangeCodeAsync(code, ct).ConfigureAwait(false);
        if (envelope is null)
        {
            return new LoginOutcome(null, "本机后端未响应 /api/v1/cloud/exchange。");
        }

        if (!envelope.Success)
        {
            return new LoginOutcome(null, envelope.Message ?? envelope.Code ?? "登录码兑换失败。");
        }

        return new LoginOutcome(envelope.Data, null);
    }

    public Task LogoutAsync(CancellationToken ct = default) => _api.LogoutAsync(ct);
}
