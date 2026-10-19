using System;
using System.IO;
using System.Text.Json;

namespace Flux.Desktop.Services;

/// <summary>本机界面偏好（对齐 web 端 localStorage 里的 flux.fx.prefs）。</summary>
public sealed record UiPrefsData(bool RailWide, bool StatusBar, bool ReduceMotion);

/// <summary>
/// 界面偏好落盘：web 端存在浏览器 localStorage，桌面端存在数据目录下的 prefs.json。
/// 读写失败一律回落到默认值，不影响启动。
/// </summary>
public static class UiPrefs
{
    public static UiPrefsData Defaults { get; } = new(RailWide: false, StatusBar: true, ReduceMotion: false);

    private static string FilePath => Path.Combine(AppPaths.DataDir, "prefs.json");

    public static UiPrefsData Load()
    {
        try
        {
            if (!File.Exists(FilePath))
            {
                return Defaults;
            }

            return JsonSerializer.Deserialize<UiPrefsData>(File.ReadAllText(FilePath),
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? Defaults;
        }
        catch
        {
            return Defaults;
        }
    }

    public static void Save(UiPrefsData data)
    {
        try
        {
            Directory.CreateDirectory(AppPaths.DataDir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(data));
        }
        catch
        {
            // 偏好写盘失败不影响到主流程
        }
    }
}
