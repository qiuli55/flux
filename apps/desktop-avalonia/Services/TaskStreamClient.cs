using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Flux.Desktop.Models;

namespace Flux.Desktop.Services;

/// <summary>流式回调；全部在后台线程触发，调用方需自行切回 UI 线程。</summary>
public sealed record TaskStreamCallbacks(
    Action Opened,
    Action<TaskStreamEvent> Received,
    Action<int> Reconnecting,
    Action<string> Failed);

/// <summary>
/// 任务事件流客户端（GET /api/v1/tasks/{id}/stream）。
///
/// - fetch 流式读取 + 手写 SSE 解析（桌面端没有 EventSource 限制，但需要 `after_seq` 续传）；
/// - 断线 1/2/4/8/15s 退避，最多 8 次；
/// - 60s 没有收到任何行（含 15s 心跳注释）视为假死，主动断开重连。
/// </summary>
public sealed class TaskStreamClient : IDisposable
{
    private static readonly TimeSpan[] Backoff =
    [
        TimeSpan.FromSeconds(1),
        TimeSpan.FromSeconds(2),
        TimeSpan.FromSeconds(4),
        TimeSpan.FromSeconds(8),
        TimeSpan.FromSeconds(15),
    ];

    private static readonly TimeSpan Watchdog = TimeSpan.FromSeconds(60);
    private const int MaxAttempts = 8;

    private readonly HttpClient _http;

    public TaskStreamClient(string baseUrl)
    {
        var handler = new HttpClientHandler { UseProxy = false };
        _http = new HttpClient(handler)
        {
            BaseAddress = new Uri(baseUrl),
            Timeout = Timeout.InfiniteTimeSpan,
        };
        _http.DefaultRequestHeaders.TryAddWithoutValidation("X-Flux-Desktop", "1");
        _http.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "text/event-stream");
    }

    public async Task RunAsync(
        string taskId,
        long initialSeq,
        TaskStreamCallbacks callbacks,
        CancellationToken cancellationToken)
    {
        var attempt = 0;
        var lastSeq = initialSeq;

        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var receivedAny = await ReadOnceAsync(
                    taskId,
                    lastSeq,
                    seq => lastSeq = seq,
                    callbacks,
                    cancellationToken);

                // 有数据说明服务端是健康的，重新计算退避次数。
                if (receivedAny)
                {
                    attempt = 0;
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (HttpRequestException error)
                when (error.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.NotFound)
            {
                callbacks.Failed(
                    error.StatusCode == HttpStatusCode.NotFound
                        ? "任务不存在或已被删除"
                        : "没有权限读取该任务的事件流");
                return;
            }
            catch (Exception error)
            {
                if (cancellationToken.IsCancellationRequested)
                {
                    return;
                }

                if (attempt >= MaxAttempts)
                {
                    callbacks.Failed($"实时连接失败：{error.Message}");
                    return;
                }
            }

            attempt += 1;
            if (attempt > MaxAttempts)
            {
                callbacks.Failed("实时连接多次重试仍未恢复，请检查后端服务");
                return;
            }

            callbacks.Reconnecting(attempt);
            try
            {
                await Task.Delay(Backoff[Math.Min(attempt, Backoff.Length) - 1], cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task<bool> ReadOnceAsync(
        string taskId,
        long afterSeq,
        Action<long> onSeq,
        TaskStreamCallbacks callbacks,
        CancellationToken cancellationToken)
    {
        var path = $"api/v1/tasks/{Uri.EscapeDataString(taskId)}/stream";
        if (afterSeq > 0)
        {
            path += $"?after_seq={afterSeq}";
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        using var response = await _http.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        response.EnsureSuccessStatusCode();

        callbacks.Opened();

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var reader = new StreamReader(stream, Encoding.UTF8);

        using var watchdog = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        watchdog.CancelAfter(Watchdog);

        var data = new StringBuilder();
        var receivedAny = false;

        while (true)
        {
            string? line;
            try
            {
                watchdog.CancelAfter(Watchdog);
                line = await reader.ReadLineAsync(watchdog.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new TimeoutException("60 秒没有收到事件流数据");
            }

            if (line is null)
            {
                // 服务端正常关闭（部署重启 / 长连接上限）：交给外层退避重连。
                return receivedAny;
            }

            if (line.Length == 0)
            {
                if (data.Length > 0)
                {
                    var payload = data.ToString();
                    data.Clear();
                    if (TryParse(payload, out var evt) && evt is not null)
                    {
                        receivedAny = true;
                        if (evt.Seq > afterSeq)
                        {
                            afterSeq = evt.Seq;
                            onSeq(evt.Seq);
                        }
                        callbacks.Received(evt);
                    }
                }
                continue;
            }

            if (line[0] == ':')
            {
                // 心跳注释：只要读到就说明连接还活着。
                continue;
            }

            if (line.StartsWith("data:", StringComparison.Ordinal))
            {
                var chunk = line.Length > 5 ? line[5..] : "";
                if (chunk.StartsWith(' '))
                {
                    chunk = chunk[1..];
                }
                if (data.Length > 0)
                {
                    data.Append('\n');
                }
                data.Append(chunk);
            }
        }
    }

    private static bool TryParse(string payload, out TaskStreamEvent? evt)
    {
        evt = null;
        try
        {
            using var document = JsonDocument.Parse(payload);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return false;
            }

            long seq = 0;
            if (root.TryGetProperty("seq", out var seqElement))
            {
                if (seqElement.ValueKind == JsonValueKind.Number)
                {
                    seqElement.TryGetInt64(out seq);
                }
                else if (seqElement.ValueKind == JsonValueKind.String)
                {
                    long.TryParse(seqElement.GetString(), out seq);
                }
            }

            var type = root.TryGetProperty("type", out var typeElement)
                ? typeElement.GetString() ?? ""
                : "";
            if (type.Length == 0)
            {
                return false;
            }

            var data = root.TryGetProperty("data", out var dataElement)
                ? dataElement.Clone()
                : JsonDocument.Parse("{}").RootElement.Clone();

            evt = new TaskStreamEvent(seq, type, data);
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public void Dispose() => _http.Dispose();
}
