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

/// <summary>看板卡片（web 端 TaskCard）。</summary>
public sealed record TaskCardView(
    string Description,
    string StatusLabel,
    IBrush StatusTone,
    string ModeLabel,
    string IdShort,
    string AgentText,
    bool HasAgent,
    string TimeText,
    ICommand Open);

/// <summary>看板列（web 端 COLUMNS）：只读分组，后端没有拖拽改状态的接口。</summary>
public sealed record KanbanColumn(string Label, int Count, bool IsEmpty, IReadOnlyList<TaskCardView> Items);

/// <summary>项目筛选下拉项（web 端 Select 的 scopeOptions）。</summary>
public sealed record ScopeOption(string Value, string Label);

/// <summary>
/// 任务看板（web 端 TasksPage）：四个状态列只读展示，支持按项目筛选。
/// </summary>
public sealed partial class TasksViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    /// <summary>列定义：与 web 端一致（待处理 / 进行中 / 已完成 / 已结束）。</summary>
    private static readonly (string Key, string Label, string[] Statuses)[] ColumnDefs =
    [
        ("todo", "待处理", ["pending"]),
        ("doing", "进行中", ["running", "waiting_for_user_decision"]),
        ("done", "已完成", ["completed"]),
        ("ended", "已结束", ["failed", "cancelled"]),
    ];

    private readonly Func<FluxApiClient?> _api;
    private readonly Action<string> _navigate;

    private List<FluxTask> _tasks = [];

    public TasksViewModel(Func<FluxApiClient?> api, Action<string> navigate)
    {
        _api = api;
        _navigate = navigate;
    }

    public ObservableCollection<KanbanColumn> Columns { get; } = new();

    public ObservableCollection<ScopeOption> Scopes { get; } = new();

    [ObservableProperty]
    private ScopeOption? _selectedScope;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _totalText = "共 0 个任务";

    [ObservableProperty]
    private bool _hasNoTasks;

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
            var tasks = await api.ListTasksAsync(200).ConfigureAwait(true);
            var projects = await api.ListProjectsAsync().ConfigureAwait(true);

            _tasks = tasks?.Data ?? [];

            // 保留当前选择；项目列表变了就重建选项
            var previous = SelectedScope?.Value ?? "all";
            Scopes.Clear();
            Scopes.Add(new ScopeOption("all", "全部项目"));
            foreach (var project in projects?.Data ?? [])
            {
                Scopes.Add(new ScopeOption(project.Id, project.Name));
            }

            SelectedScope = Scopes.FirstOrDefault(s => s.Value == previous) ?? Scopes[0];

            RebuildColumns();
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    [RelayCommand]
    private void NewTask() => _navigate("solo");

    partial void OnSelectedScopeChanged(ScopeOption? value) => RebuildColumns();

    private void RebuildColumns()
    {
        var scope = SelectedScope?.Value ?? "all";
        var scoped = scope == "all"
            ? _tasks
            : _tasks.Where(t => t.ProjectId == scope).ToList();

        TotalText = $"共 {scoped.Count} 个任务";
        HasNoTasks = scoped.Count == 0;

        Columns.Clear();
        foreach (var (_, label, statuses) in ColumnDefs)
        {
            var items = scoped
                .Where(t => statuses.Contains(t.Status))
                .Select(BuildCard)
                .ToList();

            Columns.Add(new KanbanColumn(label, items.Count, items.Count == 0, items));
        }
    }

    private TaskCardView BuildCard(FluxTask task)
    {
        var (label, tone) = StatusMeta(task.Status);
        return new TaskCardView(
            task.Description,
            label,
            tone,
            task.DecisionMode == "manual" ? "手动确认" : "自动决策",
            task.Id.Length > 8 ? task.Id[..8] : task.Id,
            task.AgentId ?? "",
            !string.IsNullOrWhiteSpace(task.AgentId),
            RelativeTime(task.CreatedAt),
            new RelayCommand(() => _navigate("solo")));
    }

    private static (string Label, IBrush Tone) StatusMeta(string status) => status switch
    {
        "running" => ("执行中", Brand),
        "waiting_for_user_decision" => ("待决策", Orange),
        "completed" => ("已完成", Brand),
        "failed" => ("失败", Danger),
        "cancelled" => ("已取消", Muted),
        _ => ("待处理", Muted),
    };

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
