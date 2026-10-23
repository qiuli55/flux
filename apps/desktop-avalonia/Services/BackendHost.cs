using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Flux.Desktop.Services;

/// <summary>
/// 以子进程方式拉起本地 Python 后端（uvicorn），并等待 /api/v1/health 就绪。
/// 端口随机分配，避免与本机常驻实例（8801）冲突。
/// </summary>
public sealed class BackendHost : IDisposable
{
    private const int HealthTimeoutSeconds = 90;

    private readonly object _logLock = new();
    private readonly Queue<string> _logTail = new();

    private Process? _process;

    public int Port { get; private set; }

    public string BaseUrl => $"http://127.0.0.1:{Port}";

    public string LogTail
    {
        get
        {
            lock (_logLock)
            {
                return string.Join(Environment.NewLine, _logTail);
            }
        }
    }

    public async Task StartAsync(string? cloudReturnUrl, CancellationToken ct = default)
    {
        if (!File.Exists(AppPaths.PythonExecutable))
        {
            throw new FileNotFoundException(
                $"未找到后端 Python 解释器：{AppPaths.PythonExecutable}{Environment.NewLine}" +
                $"请先在 {AppPaths.RepoRoot} 下创建 .venv 并安装 backend/requirements.txt。");
        }

        var dataDir = EnsureDataDir();
        // 全新机器上库文件可能是空的；后端不会自己建表，必须先迁移到 head
        await RunMigrationsAsync(dataDir, ct).ConfigureAwait(false);
        Port = GetFreePort();

        var psi = new ProcessStartInfo
        {
            FileName = AppPaths.PythonExecutable,
            WorkingDirectory = AppPaths.BackendDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        foreach (var arg in new[]
                 {
                     "-m", "uvicorn", "flux.main:create_app", "--factory",
                     "--host", "127.0.0.1", "--port", Port.ToString(),
                 })
        {
            psi.ArgumentList.Add(arg);
        }

        var env = psi.Environment;
        env["PYTHONPATH"] = AppPaths.BackendDir;
        env["PYTHONIOENCODING"] = "utf-8";
        env["FLUX_DATA_DIR"] = dataDir;
        env["FLUX_DATABASE_URL"] = $"sqlite+aiosqlite:///{ToUrlPath(dataDir)}/flux.db";
        env["FLUX_WORKSPACE_ROOT"] = Env("FLUX_WORKSPACE_ROOT") ?? Path.Combine(dataDir, "workspace");
        env["FLUX_LOG_DIR"] = Env("FLUX_LOG_DIR") ?? Path.Combine(dataDir, "logs");
        env["FLUX_CLOUD_LOGIN_REQUIRED"] = Env("FLUX_CLOUD_LOGIN_REQUIRED") ?? "1";

        // 子进程会继承当前进程的 HTTP(S)_PROXY。若它指向失效代理（本机常见）或与
        // 账号云不兼容的代理，后端回源 flux.qiuli55.top 的 httpx 调用会直接连接失败，
        // 表现为 /cloud/exchange 报「登录码兑换失败：All connection attempts failed」。
        // 账号云是境内直连可达的，这里清掉继承来的代理设置，让后端直连。
        foreach (var key in new[]
                 {
                     "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
                     "http_proxy", "https_proxy", "all_proxy",
                 })
        {
            env.Remove(key);
        }

        env["NO_PROXY"] = "127.0.0.1,localhost";
        env["no_proxy"] = "127.0.0.1,localhost";

        if (!string.IsNullOrWhiteSpace(cloudReturnUrl))
        {
            env["FLUX_CLOUD_RETURN_URL"] = cloudReturnUrl;
        }

        _process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        _process.OutputDataReceived += (_, e) => AppendLog(e.Data);
        _process.ErrorDataReceived += (_, e) => AppendLog(e.Data);
        _process.Start();
        _process.BeginOutputReadLine();
        _process.BeginErrorReadLine();

        await WaitForHealthAsync(ct).ConfigureAwait(false);
    }

    /// <summary>用仓库内的 alembic 把用户数据目录的 SQLite 迁到 head（幂等）。</summary>
    private static async Task RunMigrationsAsync(string dataDir, CancellationToken ct)
    {
        var dbUrl = $"sqlite+aiosqlite:///{ToUrlPath(dataDir)}/flux.db";
        var psi = new ProcessStartInfo
        {
            FileName = AppPaths.PythonExecutable,
            WorkingDirectory = AppPaths.BackendDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };

        foreach (var arg in new[] { "-m", "alembic", "-x", $"db_url={dbUrl}", "upgrade", "head" })
        {
            psi.ArgumentList.Add(arg);
        }

        psi.Environment["PYTHONPATH"] = AppPaths.BackendDir;
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        psi.Environment["FLUX_DATA_DIR"] = dataDir;
        psi.Environment["FLUX_DATABASE_URL"] = dbUrl;

        using var process = Process.Start(psi)
            ?? throw new InvalidOperationException("无法启动数据库迁移进程");
        var stdout = process.StandardOutput.ReadToEndAsync(ct);
        var stderr = process.StandardError.ReadToEndAsync(ct);
        await process.WaitForExitAsync(ct).ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException(
                $"数据库迁移失败（退出码 {process.ExitCode}）。{Environment.NewLine}" +
                $"{await stdout.ConfigureAwait(false)}{Environment.NewLine}{await stderr.ConfigureAwait(false)}");
        }
    }

    public async Task WaitForHealthAsync(CancellationToken ct = default)
    {
        var deadline = DateTime.UtcNow.AddSeconds(HealthTimeoutSeconds);
        // 探活走本机回环，必须显式关闭代理：HttpClient 默认读取 HTTP_PROXY/HTTPS_PROXY，
        // 且不会自动绕过 127.0.0.1，机器上失效的代理会让探活恒失败。
        using var handler = new HttpClientHandler { UseProxy = false };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(5) };

        while (DateTime.UtcNow < deadline)
        {
            ct.ThrowIfCancellationRequested();

            if (_process is { HasExited: true })
            {
                throw new InvalidOperationException(
                    $"后端进程已退出（退出码 {_process.ExitCode}）。{Environment.NewLine}{LogTail}");
            }

            try
            {
                using var res = await http.GetAsync($"{BaseUrl}/api/v1/health", ct).ConfigureAwait(false);
                if (res.IsSuccessStatusCode)
                {
                    var body = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                    if (body.Replace(" ", string.Empty).Contains("\"success\":true", StringComparison.Ordinal))
                    {
                        return;
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // 后端还没起来，继续轮询
            }

            await Task.Delay(300, ct).ConfigureAwait(false);
        }

        throw new TimeoutException(
            $"后端在 {HealthTimeoutSeconds} 秒内未就绪。{Environment.NewLine}{LogTail}");
    }

    private static string EnsureDataDir()
    {
        var dataDir = AppPaths.DataDir;
        Directory.CreateDirectory(dataDir);
        Directory.CreateDirectory(Path.Combine(dataDir, "workspace"));
        Directory.CreateDirectory(Path.Combine(dataDir, "backups"));
        return dataDir;
    }

    private static string? Env(string key)
    {
        var value = Environment.GetEnvironmentVariable(key);
        return string.IsNullOrWhiteSpace(value) ? null : value;
    }

    private static string ToUrlPath(string path) => path.Replace('\\', '/');

    private static int GetFreePort()
    {
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();
        return port;
    }

    private void AppendLog(string? line)
    {
        if (string.IsNullOrEmpty(line))
        {
            return;
        }

        lock (_logLock)
        {
            _logTail.Enqueue(line);
            while (_logTail.Count > 200)
            {
                _logTail.Dequeue();
            }
        }
    }

    public void Dispose()
    {
        var process = _process;
        _process = null;
        if (process is null)
        {
            return;
        }

        try
        {
            if (!process.HasExited)
            {
                if (OperatingSystem.IsWindows())
                {
                    using var killer = Process.Start(new ProcessStartInfo("taskkill", $"/PID {process.Id} /T /F")
                    {
                        UseShellExecute = false,
                        CreateNoWindow = true,
                    });
                    killer?.WaitForExit(5000);
                }
                else
                {
                    process.Kill(entireProcessTree: true);
                }
            }
        }
        catch
        {
            // 退出清理失败不影响关闭流程
        }
        finally
        {
            process.Dispose();
        }
    }
}
