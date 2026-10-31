using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace Flux.Desktop.Services;

/// <summary>
/// 拉起独立的 Flux IDE（VSCodium 底座 + Flux 扩展的打包产物，运行在自己的窗口里）。
/// 查找顺序：FLUX_IDE_PATH → 程序目录旁的 flux-ide → 数据目录 ide/ → PATH。
/// </summary>
public static class IdeLauncher
{
    private static Process? _process;

    /// <summary>已解析到的可执行文件；没装 IDE 时为 null。</summary>
    public static string? Executable { get; } = Resolve();

    public static bool IsAvailable => Executable is not null;

    /// <summary>
    /// 打开 IDE 窗口；已在运行就复用，不重复拉起。
    /// `serverUrl` 会以 FLUX_SERVER_URL 传给 IDE 里的 Flux 扩展——桌面端后端起在随机端口，
    /// 不传的话扩展会退回 8000/用户设置里的地址，连不到本进程拉起的后端。
    /// </summary>
    public static bool Launch(out string message, string? serverUrl = null)
    {
        AppLog.Info($"IDE 启动请求 · exe={Executable ?? "null"} · active={(IsProcessAlive() ? _process!.Id.ToString() : "none")}");

        if (Executable is null)
        {
            message = "未找到 Flux IDE：请解压到 ~/.flux/ide，或用 FLUX_IDE_PATH 指定可执行文件";
            AppLog.Warn(message);
            return false;
        }

        if (IsProcessAlive())
        {
            var activated = TryActivate();
            message = activated ? "已切换到 Flux IDE 窗口" : "Flux IDE 窗口已打开";
            AppLog.Info($"Flux IDE 已在运行 · pid={_process!.Id} · 切换={(activated ? "成功" : "失败")}");
            return true;
        }

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = Executable,
                WorkingDirectory = Path.GetDirectoryName(Executable) ?? AppContext.BaseDirectory,
                UseShellExecute = false,
            };

            // Chromium 内核以 root 运行会因沙箱拒绝启动，这里补上开关
            if (!OperatingSystem.IsWindows() && Environment.UserName == "root")
            {
                psi.ArgumentList.Add("--no-sandbox");
            }

            // 后端是随机端口：把地址交给 IDE 里的 Flux 扩展（纯回环直连，无需令牌）
            if (!string.IsNullOrWhiteSpace(serverUrl))
            {
                psi.Environment["FLUX_SERVER_URL"] = serverUrl;
            }
            var token = Environment.GetEnvironmentVariable("FLUX_AUTH_TOKEN");
            if (!string.IsNullOrWhiteSpace(token))
            {
                psi.Environment["FLUX_AUTH_TOKEN"] = token;
            }

            _process = Process.Start(psi);
            if (_process is null)
            {
                message = "启动 Flux IDE 失败";
                AppLog.Error(message);
                return false;
            }

            message = "已打开 Flux IDE 窗口";
            AppLog.Info($"已启动 Flux IDE：{Executable} (pid={_process.Id})");
            return true;
        }
        catch (Exception ex)
        {
            message = $"启动 Flux IDE 失败：{ex.Message}";
            AppLog.Error(message, ex);
            return false;
        }
    }

    /// <summary>跟踪的 IDE 进程是否还活着；已退出就丢掉引用，下次点击重新拉起。</summary>
    private static bool IsProcessAlive()
    {
        if (_process is null)
        {
            return false;
        }

        try
        {
            if (!_process.HasExited)
            {
                return true;
            }
        }
        catch
        {
            // 进程对象失效（已释放 / 无法查询）按已退出处理
        }

        _process.Dispose();
        _process = null;
        return false;
    }

    /// <summary>把已在运行的 IDE 窗口切到前台；做不到就返回 false（不影响调用方）。</summary>
    private static bool TryActivate()
    {
        if (!IsProcessAlive())
        {
            return false;
        }

        try
        {
            if (OperatingSystem.IsWindows())
            {
                _process!.Refresh();
                var handle = _process.MainWindowHandle;
                if (handle == IntPtr.Zero)
                {
                    return false;
                }

                ShowWindow(handle, SwRestore);
                return SetForegroundWindow(handle);
            }

            // Linux 没有统一的激活接口：优先 wmctrl，其次 xdotool，缺哪个跳过哪个
            if (RunActivate("wmctrl", new[] { "-a", "Flux IDE" }))
            {
                return true;
            }

            return RunActivate("xdotool", new[] { "search", "--name", "Flux IDE", "windowactivate", "%@" });
        }
        catch
        {
            return false;
        }
    }

    private static bool RunActivate(string fileName, string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(fileName)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            foreach (var arg in args)
            {
                psi.ArgumentList.Add(arg);
            }

            using var proc = Process.Start(psi);
            if (proc is null)
            {
                return false;
            }

            proc.WaitForExit(1000);
            return proc.HasExited && proc.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    private static string? Resolve()
    {
        var env = Environment.GetEnvironmentVariable("FLUX_IDE_PATH");
        if (!string.IsNullOrWhiteSpace(env) && File.Exists(env))
        {
            return env;
        }

        foreach (var candidate in Candidates())
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return FromPath();
    }

    private static IEnumerable<string> Candidates()
    {
        var relative = OperatingSystem.IsWindows()
            ? new[] { "flux-ide/VSCodium.exe", "flux-ide/bin/flux-ide.exe" }
            : new[] { "flux-ide/bin/flux-ide", "flux-ide/bin/codium" };

        foreach (var path in relative)
        {
            yield return Path.Combine(AppContext.BaseDirectory, path);
            yield return Path.Combine(AppPaths.DataDir, "ide", path);
        }
    }

    private static string? FromPath()
    {
        var name = OperatingSystem.IsWindows() ? "flux-ide.exe" : "flux-ide";
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(dir))
            {
                continue;
            }

            var candidate = Path.Combine(dir, name);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }

    private const int SwRestore = 9;

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
