using System;
using System.Diagnostics;
using System.IO;

namespace Flux.Desktop.Services;

/// <summary>
/// 桌面端运行日志：写数据目录 logs/desktop.log，同时输出到调试器。
/// 页面加载、动作成功与异常分支都留一条，便于桌面端无控制台时定位问题。
/// </summary>
public static class AppLog
{
    private static readonly object Gate = new();
    private const long MaxBytes = 2 * 1024 * 1024;

    public static string FilePath { get; } = Path.Combine(AppPaths.DataDir, "logs", "desktop.log");

    public static void Info(string message) => Write("INFO", message);

    public static void Warn(string message) => Write("WARN", message);

    public static void Error(string message, Exception? ex = null)
        => Write("ERROR", ex is null ? message : $"{message} | {ex.GetType().Name}: {ex.Message}");

    private static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {message}";
        Debug.WriteLine(line);

        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
                RotateIfTooLarge();
                File.AppendAllText(FilePath, line + Environment.NewLine);
            }
        }
        catch (Exception ex)
        {
            // 日志写盘失败不能反过来影响主流程，只留在调试输出里
            Debug.WriteLine($"{line} | log write failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private static void RotateIfTooLarge()
    {
        var info = new FileInfo(FilePath);
        if (!info.Exists || info.Length < MaxBytes)
        {
            return;
        }

        var backup = FilePath + ".1";
        File.Delete(backup);
        File.Move(FilePath, backup);
    }
}
