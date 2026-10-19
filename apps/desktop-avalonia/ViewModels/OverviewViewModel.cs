using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>概览页行：任务 / 待审变更，统一带色调（对齐 web 端 fx-badge 的 6 种 tone）与跳转命令。</summary>
public sealed record RowView(string Lead, string Text, string Trailing, IBrush Tone, ICommand Open);

/// <summary>
/// 概览页（web 端 OverviewPage）：关键指标、执行流程、最近任务、待审变更、Agent 运行时。
/// 数据全部来自真实后端；本机无凭据时后端可能返回空集合，页面按空态渲染。
/// </summary>
public sealed partial class OverviewViewModel : ViewModelBase
{
    // 与 tokens.css 一一对应的色调（只在行内做强调，不参与全局令牌）
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private static readonly string[] Flow = ["需求澄清", "可行性计划", "生成变更", "人工审核", "落盘 / Git"];

    private readonly Func<FluxApiClient?> _api;
    private readonly Action<string> _navigate;

    public OverviewViewModel(Func<FluxApiClient?> api, Action<string> navigate)
    {
        _api = api;
        _navigate = navigate;
    }

    /// <summary>执行流程的只读文案（web 端 FLOW 链）。</summary>
    public string FlowText => string.Join("   →   ", Flow);

    public ObservableCollection<RowView> RecentTasks { get; } = new();

    public ObservableCollection<RowView> PendingChanges { get; } = new();

    public ObservableCollection<RowView> Agents { get; } = new();

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _engineText = "—";

    [ObservableProperty]
    private int _projectCount;

    [ObservableProperty]
    private int _runningCount;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TaskUnitText))]
    private int _taskCount;

    /// <summary>「运行中任务」右下角的小字单位（对齐 web 的 `/ 总数`）。</summary>
    public string TaskUnitText => $"/ {TaskCount}";

    [ObservableProperty]
    private int _waitingCount;

    [ObservableProperty]
    private int _pendingChangeCount;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoRecentTasks))]
    private bool _hasRecentTasks;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoPendingChanges))]
    private bool _hasPendingChanges;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoAgents))]
    private bool _hasAgents;

    public bool HasNoRecentTasks => !HasRecentTasks;

    public bool HasNoPendingChanges => !HasPendingChanges;

    public bool HasNoAgents => !HasAgents;

    /// <summary>拉一遍概览所需数据；任一接口失败按空处理，不阻塞其它块。</summary>
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
            var projects = await api.ListProjectsAsync().ConfigureAwait(true);
            var tasks = await api.ListTasksAsync().ConfigureAwait(true);
            var changes = await api.ListPendingChangesAsync().ConfigureAwait(true);
            var agents = await api.ListAgentsAsync().ConfigureAwait(true);
            var dsh = await api.DshStatusAsync().ConfigureAwait(true);

            var taskList = tasks?.Data ?? [];
            ProjectCount = projects?.Data?.Count ?? 0;
            TaskCount = taskList.Count;
            RunningCount = taskList.Count(t => t.Status == "running");
            WaitingCount = taskList.Count(t => t.Status == "waiting_for_user_decision");
            PendingChangeCount = changes?.Data?.Count ?? 0;

            EngineText = dsh?.Data is { } status && !string.IsNullOrWhiteSpace(status.Provider)
                ? $"{status.Provider}/{status.Model}"
                : "—";

            RecentTasks.Clear();
            foreach (var task in taskList.OrderByDescending(t => t.CreatedAt).Take(6))
            {
                var (tone, label) = TaskTone(task.Status);
                RecentTasks.Add(new RowView(
                    label,
                    task.Description,
                    RelativeTime(task.CreatedAt),
                    tone,
                    new RelayCommand(() => _navigate("tasks"))));
            }

            HasRecentTasks = RecentTasks.Count > 0;

            PendingChanges.Clear();
            foreach (var change in (changes?.Data ?? []).Take(6))
            {
                PendingChanges.Add(new RowView(
                    change.Kind,
                    change.FilePath,
                    $"+{change.AddedLines} -{change.RemovedLines}",
                    ChangeTone(change.Kind),
                    new RelayCommand(() => _navigate("ide"))));
            }

            HasPendingChanges = PendingChanges.Count > 0;

            Agents.Clear();
            foreach (var agent in agents?.Data ?? [])
            {
                var runtime = string.IsNullOrWhiteSpace(agent.Spec.Runtime) ? "dsh" : agent.Spec.Runtime;
                Agents.Add(new RowView(
                    $"{agent.Spec.Name} · {runtime} · {agent.State}",
                    "",
                    "",
                    runtime == "dsh" ? Brand : Info,
                    new RelayCommand(() => _navigate("ide"))));
            }

            HasAgents = Agents.Count > 0;
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    /// <summary>快捷入口：solo / ide 走模式切换，其余走平台页面跳转。</summary>
    [RelayCommand]
    private void Go(string? key)
    {
        if (!string.IsNullOrWhiteSpace(key))
        {
            _navigate(key);
        }
    }

    private static (IBrush Tone, string Label) TaskTone(string status) => status switch
    {
        "running" => (Brand, "运行中"),
        "waiting_for_user_decision" => (Orange, "待决策"),
        "completed" => (Brand, "已完成"),
        "failed" => (Danger, "失败"),
        "cancelled" => (Muted, "已取消"),
        _ => (Muted, "待处理"),
    };

    private static IBrush ChangeTone(string kind) => kind switch
    {
        "delete" => Danger,
        "create" => Brand,
        _ => Info,
    };

    /// <summary>相对时间（web 端 relTime：刚刚 / N 分钟前 / N 小时前 / N 天前）。</summary>
    private static string RelativeTime(string? iso)
    {
        if (string.IsNullOrWhiteSpace(iso) || !DateTimeOffset.TryParse(iso, out var moment))
        {
            return "—";
        }

        var minutes = (DateTimeOffset.Now - moment).TotalMinutes;
        if (minutes < 1)
        {
            return "刚刚";
        }

        if (minutes < 60)
        {
            return $"{(int)minutes} 分钟前";
        }

        var hours = minutes / 60;
        return hours < 24 ? $"{(int)hours} 小时前" : $"{(int)(hours / 24)} 天前";
    }
}
