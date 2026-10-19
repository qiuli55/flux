using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Flux.Desktop.Services;

/// <summary>
/// 极简本地回环 HTTP 服务，只用于接收账号云登录成功后的 ?code= 回跳。
/// 用 TcpListener 而非 HttpListener，避免 Windows 上的 URL ACL / 管理员权限问题。
/// </summary>
public sealed class LoopbackCodeListener : IDisposable
{
    private const string CallbackPath = "/auth/callback";

    private readonly TcpListener _listener;

    public LoopbackCodeListener()
    {
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        CallbackUrl = $"http://127.0.0.1:{Port}{CallbackPath}";
    }

    public int Port { get; }

    /// <summary>回跳地址，注入后端 FLUX_CLOUD_RETURN_URL 后由 /cloud/session 的 login_url 携带。</summary>
    public string CallbackUrl { get; }

    /// <summary>等待一次带 code 的回跳；超时或取消返回 null。</summary>
    public async Task<string?> WaitForCodeAsync(TimeSpan timeout, CancellationToken ct = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);

        while (!cts.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return null;
            }
            catch (ObjectDisposedException)
            {
                return null;
            }
            catch (SocketException)
            {
                return null;
            }

            using (client)
            {
                try
                {
                    using var stream = client.GetStream();
                    using var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, leaveOpen: true);

                    var requestLine = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false);
                    string? header;
                    while (!string.IsNullOrEmpty(header = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false)))
                    {
                        // 只需读空请求头，丢弃即可
                    }

                    var code = ExtractCode(requestLine);
                    if (code is not null)
                    {
                        await WriteResponseAsync(stream, 200, "OK", SuccessHtml).ConfigureAwait(false);
                        return code;
                    }

                    // 浏览器favicon 等杂项请求：答 404 后继续等待真正的回跳
                    await WriteResponseAsync(stream, 404, "Not Found", "<html><body>Not Found</body></html>")
                        .ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return null;
                }
                catch (IOException)
                {
                    // 客户端提前断开，继续等待下一次回跳
                }
            }
        }

        return null;
    }

    private static string? ExtractCode(string? requestLine)
    {
        if (string.IsNullOrEmpty(requestLine))
        {
            return null;
        }

        var parts = requestLine.Split(' ');
        if (parts.Length < 2)
        {
            return null;
        }

        var target = parts[1];
        var queryIndex = target.IndexOf('?');
        if (queryIndex < 0)
        {
            return null;
        }

        var path = target[..queryIndex];
        if (!path.Equals(CallbackPath, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        foreach (var pair in target[(queryIndex + 1)..].Split('&'))
        {
            var eq = pair.IndexOf('=');
            if (eq <= 0)
            {
                continue;
            }

            if (Uri.UnescapeDataString(pair[..eq]) == "code")
            {
                return Uri.UnescapeDataString(pair[(eq + 1)..]);
            }
        }

        return null;
    }

    private static async Task WriteResponseAsync(NetworkStream stream, int status, string reason, string html)
    {
        var body = Encoding.UTF8.GetBytes(html);
        var header =
            $"HTTP/1.1 {status} {reason}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            $"Content-Length: {body.Length}\r\n" +
            "Connection: close\r\n\r\n";

        await stream.WriteAsync(Encoding.ASCII.GetBytes(header)).ConfigureAwait(false);
        await stream.WriteAsync(body).ConfigureAwait(false);
        await stream.FlushAsync().ConfigureAwait(false);
    }

    private const string SuccessHtml = """
        <!doctype html>
        <html lang="zh-CN">
        <head><meta charset="utf-8"><title>Flux</title></head>
        <body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#0b0d12;color:#e6e9f0;
                     display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
          <div style="text-align:center">
            <div style="font-size:20px;font-weight:600;margin-bottom:8px">登录成功</div>
            <div style="color:#8b93a7;font-size:13px">请返回 Flux 桌面端继续操作，本页面可以关闭。</div>
          </div>
        </body>
        </html>
        """;

    public void Dispose() => _listener.Stop();
}
