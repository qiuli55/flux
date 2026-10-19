using System;
using System.IO;

namespace Flux.Desktop.Services;

/// <summary>
/// 运行时路径解析：仓库根、后端目录、venv 内的 Python、数据目录。
/// </summary>
public static class AppPaths
{
    public static string RepoRoot { get; } = ResolveRepoRoot();

    public static string BackendDir => Path.Combine(RepoRoot, "backend");

    public static string PythonExecutable { get; } = ResolvePython();

    public static string DataDir { get; } = ResolveDataDir();

    private static string ResolveRepoRoot()
    {
        var env = Environment.GetEnvironmentVariable("FLUX_REPO_ROOT");
        if (!string.IsNullOrWhiteSpace(env))
        {
            return env!;
        }

        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "backend", "flux", "main.py")))
            {
                return dir.FullName;
            }

            dir = dir.Parent;
        }

        return AppContext.BaseDirectory;
    }

    private static string ResolvePython()
    {
        var env = Environment.GetEnvironmentVariable("FLUX_PYTHON");
        if (!string.IsNullOrWhiteSpace(env))
        {
            return env!;
        }

        return OperatingSystem.IsWindows()
            ? Path.Combine(RepoRoot, ".venv", "Scripts", "python.exe")
            : Path.Combine(RepoRoot, ".venv", "bin", "python");
    }

    private static string ResolveDataDir()
    {
        var env = Environment.GetEnvironmentVariable("FLUX_DATA_DIR");
        if (!string.IsNullOrWhiteSpace(env))
        {
            return env!;
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            ".flux");
    }
}
