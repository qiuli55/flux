using System;
using System.Collections.ObjectModel;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>健康瓦片（web 端 HealthTile）。</summary>
public sealed record HealthTileView(string Label, string Value, string Desc, bool Ok);

/// <summary>键值对（web 端 fx-kv）。</summary>
public sealed record KvView(string Key, string Value);

/// <summary>Agent 运行时行（web 端 Agent 运行时卡片）。</summary>
public sealed record AgentRowView(string Name, string Runtime, string State, IBrush RuntimeTone, IBrush StateTone);

/// <summary>本机 CLI 安装行（web 端 InstallRow）。</summary>
public sealed record InstallRowView(
    string Name,
    string Adapter,
    string PathText,
    string AuthLabel,
    IBrush AuthTone,
    string StatusLabel,
    IBrush StatusTone);

/// <summary>
/// 系统诊断页（web 端 DiagnosticsPage）：后端健康 / 就绪 / DSH 运行时 / 本机 CLI 安装的事实快照，只读。
/// </summary>
public sealed partial class DiagnosticsViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<FluxApiClient?> _api;

    public DiagnosticsViewModel(Func<FluxApiClient?> api) => _api = api;

    public ObservableCollection<HealthTileView> Tiles { get; } = new();

    public ObservableCollection<KvView> DshRows { get; } = new();

    public ObservableCollection<string> Providers { get; } = new();

    public ObservableCollection<AgentRowView> Agents { get; } = new();

    public ObservableCollection<InstallRowView> Installs { get; } = new();

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _hasDsh;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoAgents))]
    private bool _hasAgents;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoInstalls))]
    private bool _hasInstalls;

    [ObservableProperty]
    private string _agentCountText = "0 个";

    [ObservableProperty]
    private string _installCountText = "0 个";

    public bool HasNoAgents => !HasAgents;

    public bool HasNoInstalls => !HasInstalls;

    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var health = await api.HealthAsync().ConfigureAwait(true);
            var ready = await api.ReadyAsync().ConfigureAwait(true);
            var dsh = await api.DshStatusAsync().ConfigureAwait(true);
            var installs = await api.ListInstallationsAsync().ConfigureAwait(true);
            var agents = await api.ListAgentsAsync().ConfigureAwait(true);

            var healthData = health?.Data;
            var readyData = ready?.Data;
            var dshData = dsh?.Data;

            Tiles.Clear();
            Tiles.Add(new HealthTileView(
                "后端健康",
                healthData?.Status ?? "未连接",
                healthData is null ? "无法访问 /health" : $"{healthData.App} · {healthData.Env}",
                healthData is not null));
            Tiles.Add(new HealthTileView(
                "数据库",
                readyData?.Database == true ? "已就绪" : "未就绪",
                readyData is null
                    ? "无法访问 /health/ready"
                    : $"Agent {readyData.Agents} 个 · Provider {readyData.Providers.Length} 个",
                readyData?.Database == true));
            Tiles.Add(new HealthTileView(
                "DSH 运行时",
                dshData?.Enabled == true ? "已启用" : "已停用",
                dshData is null ? "无法访问 /dsh/status" : $"{dshData.Provider}/{dshData.Model}",
                dshData?.Enabled == true));

            DshRows.Clear();
            HasDsh = dshData is not null;
            if (dshData is not null)
            {
                DshRows.Add(new KvView("Home", dshData.Home ?? "—"));
                DshRows.Add(new KvView("Workspace", dshData.Workspace ?? "—"));
                DshRows.Add(new KvView("Provider", dshData.Provider ?? "—"));
                DshRows.Add(new KvView("Model", dshData.Model ?? "—"));
                DshRows.Add(new KvView(
                    "MCP",
                    dshData.Mcp is null
                        ? "—"
                        : $"{(dshData.Mcp.Enabled ? "已启用" : "已停用")} · {dshData.Mcp.ServerName}"));
                DshRows.Add(new KvView("MCP URL", dshData.Mcp?.Url ?? "—"));
            }

            Providers.Clear();
            foreach (var provider in readyData?.Providers ?? [])
            {
                Providers.Add(provider);
            }

            Agents.Clear();
            foreach (var agent in agents?.Data ?? [])
            {
                var runtime = string.IsNullOrWhiteSpace(agent.Spec.Runtime) ? "dsh" : agent.Spec.Runtime;
                Agents.Add(new AgentRowView(
                    agent.Spec.Name,
                    runtime,
                    agent.State,
                    runtime == "dsh" ? Brand : Info,
                    agent.State == "READY" ? Brand : Muted));
            }

            HasAgents = Agents.Count > 0;
            AgentCountText = $"{Agents.Count} 个";

            Installs.Clear();
            foreach (var install in installs?.Data ?? [])
            {
                Installs.Add(new InstallRowView(
                    install.Name,
                    install.Adapter,
                    string.IsNullOrWhiteSpace(install.Path) ? "未发现可执行文件" : install.Path!,
                    $"鉴权 {install.AuthStatus ?? "unknown"}",
                    install.AuthStatus == "ok" ? Brand : install.AuthStatus == "missing" ? Orange : Muted,
                    install.Status,
                    InstallTone(install.Status)));
            }

            HasInstalls = Installs.Count > 0;
            InstallCountText = $"{Installs.Count} 个";
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    private static IBrush InstallTone(string status) => status switch
    {
        "READY" => Brand,
        "CONNECTED" => Brand,
        "VERIFIED" => Info,
        "DISCOVERED" => Orange,
        _ => Muted,
    };
}
