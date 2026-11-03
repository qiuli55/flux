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

/// <summary>顶部统计瓦片。</summary>
public sealed record SupervisorStatView(string Label, string Value, IBrush Tone);

/// <summary>观察事件行（web 端 obsRow）。</summary>
public sealed record ObservationRowView(
    string Id,
    string Source,
    string StatusText,
    string DecisionText,
    IBrush DecisionTone,
    string Meta,
    string Content,
    string Reason,
    bool HasReason,
    ICommand Assess,
    ICommand Plan,
    ICommand PlanDispatch);

/// <summary>工单卡（web 端 TicketCard）。</summary>
public sealed record TicketCardView(
    string Id,
    string Title,
    string RoleText,
    string ReportText,
    bool HasReport,
    string StageLabel,
    IBrush Tone,
    bool Degraded,
    bool CanDispatch,
    bool CanRetry,
    bool CanStop,
    string FootText,
    ICommand Open,
    ICommand Dispatch,
    ICommand Retry,
    ICommand Stop);

/// <summary>看板一列。</summary>
public sealed record TicketColumnView(
    string Title,
    string CountText,
    bool IsEmpty,
    IReadOnlyList<TicketCardView> Items);

/// <summary>工单详情里的派活记录行。</summary>
public sealed record DetailAssignmentView(
    string AgentText,
    string StatusText,
    string TaskText,
    string RunText,
    string TimeText);

/// <summary>报告行（列表与详情共用）。</summary>
public sealed record ReportRowView(
    string StatusLabel,
    IBrush Tone,
    string Summary,
    string MetaText);

/// <summary>时间线条目。</summary>
public sealed record TimelineRowView(
    string KindLabel,
    string StatusText,
    IBrush Tone,
    string Title,
    string Summary,
    string TimeText);

/// <summary>
/// 助理（Supervisor）页：外部事件 → 判断 → 工单树 → 派给子 Agent → 报告回收。
/// 只发起动作并回读后端事实，不在本地模拟状态（与 web 端 AssistantPage 同构）。
/// </summary>
public sealed partial class AssistantViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    /// <summary>看板固定四列（与 web 端 COLUMNS 一致）。</summary>
    private static readonly (string Key, string Label, string[] Stages)[] BoardColumns =
    [
        ("todo", "待派发", ["intake", "planned"]),
        ("doing", "进行中", ["running", "waiting"]),
        ("review", "待验收", ["reviewing"]),
        ("ended", "已结束", ["done", "stopped"]),
    ];

    private readonly Func<FluxApiClient?> _api;

    private List<SupervisorTrigger> _observations = [];
    private List<SupervisorTicket> _tickets = [];
    private Dictionary<string, int> _counts = new();

    public AssistantViewModel(Func<FluxApiClient?> api) => _api = api;

    public ObservableCollection<SupervisorStatView> Stats { get; } = new();

    public ObservableCollection<ObservationRowView> Observations { get; } = new();

    public ObservableCollection<TicketColumnView> Columns { get; } = new();

    public ObservableCollection<ReportRowView> Reports { get; } = new();

    public ObservableCollection<TimelineRowView> Timeline { get; } = new();

    public ObservableCollection<DetailAssignmentView> DetailAssignments { get; } = new();

    public ObservableCollection<ReportRowView> DetailReports { get; } = new();

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _isLoaded;

    [ObservableProperty]
    private string _statusText = "";

    // --- 设置 ---

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsOff))]
    [NotifyPropertyChangedFor(nameof(IsObserve))]
    [NotifyPropertyChangedFor(nameof(IsAuto))]
    private string _acceptMode = "observe";

    [ObservableProperty]
    private string _modelProvider = "";

    [ObservableProperty]
    private string _modelName = "";

    [ObservableProperty]
    private string _perThreadText = "5";

    [ObservableProperty]
    private string _depthText = "2";

    [ObservableProperty]
    private string _concurrentText = "2";

    public bool IsOff => AcceptMode == "off";

    public bool IsObserve => AcceptMode == "observe";

    public bool IsAuto => AcceptMode == "auto";

    // --- 空态 ---

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoObservations))]
    private bool _hasObservations;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoTickets))]
    private bool _hasTickets;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoReports))]
    private bool _hasReports;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoTimeline))]
    private bool _hasTimeline;

    public bool HasNoObservations => !HasObservations;

    public bool HasNoTickets => !HasTickets;

    public bool HasNoReports => !HasReports;

    public bool HasNoTimeline => !HasTimeline;

    // --- 工单详情 ---

    [ObservableProperty]
    private bool _isDetailOpen;

    [ObservableProperty]
    private string _detailId = "";

    [ObservableProperty]
    private string _detailTitle = "";

    [ObservableProperty]
    private string _detailDescription = "";

    [ObservableProperty]
    private string _detailStage = "";

    [ObservableProperty]
    private string _detailRole = "";

    [ObservableProperty]
    private string _detailPlanKey = "";

    [ObservableProperty]
    private string _detailDeps = "";

    [ObservableProperty]
    private string _detailMeta = "";

    [ObservableProperty]
    private bool _canDetailDispatch;

    [ObservableProperty]
    private bool _canDetailRetry;

    [ObservableProperty]
    private bool _canDetailStop;

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
            var overview = (await api.SupervisorOverviewAsync(50).ConfigureAwait(true))?.Data;
            var observations = (await api.SupervisorObservationsAsync(50).ConfigureAwait(true))?.Data;
            var tickets = (await api.SupervisorTicketsAsync(100).ConfigureAwait(true))?.Data;
            var reports = (await api.SupervisorReportsAsync(20).ConfigureAwait(true))?.Data;
            var timeline = (await api.SupervisorTimelineAsync(30).ConfigureAwait(true))?.Data;

            if (overview is null && observations is null && tickets is null)
            {
                StatusText = "加载失败：后端未响应 /api/v1/supervisor";
                return;
            }

            if (overview is not null)
            {
                _counts = overview.Counts;
                ApplySettings(overview.Settings);
            }

            _observations = observations ?? [];
            RebuildObservations();

            if (overview is not null)
            {
                RebuildStats(overview.PendingOutbox);
            }

            _tickets = tickets ?? [];
            RebuildColumns();

            Reports.Clear();
            foreach (var report in reports ?? [])
            {
                Reports.Add(ReportRow(report));
            }

            HasReports = Reports.Count > 0;

            Timeline.Clear();
            foreach (var item in timeline ?? [])
            {
                Timeline.Add(TimelineRow(item));
            }

            HasTimeline = Timeline.Count > 0;
            IsLoaded = true;
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    [RelayCommand]
    private void SelectMode(string? mode) => AcceptMode = mode ?? "observe";

    [RelayCommand]
    private async Task SaveSettings()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var result = await api.UpdateSupervisorSettingsAsync(
                AcceptMode,
                ModelProvider.Trim(),
                ModelName.Trim(),
                ClampInt(PerThreadText, 1, 20, 5),
                ClampInt(DepthText, 1, 3, 2),
                ClampInt(ConcurrentText, 1, 8, 2)).ConfigureAwait(true);

            if (result?.Data is { } updated)
            {
                ApplySettings(updated);
                StatusText = "设置已保存";
            }
            else
            {
                StatusText = "保存失败：" + (result?.Message ?? "后端未响应");
            }
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>人工重判：追加一条判断，观察模式下不会派活。</summary>
    [RelayCommand]
    private async Task Assess(string? triggerId)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(triggerId))
        {
            return;
        }

        var result = await api.SupervisorAssessAsync(triggerId).ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? $"重判完成：{DecisionMeta(data.Assessment?.Decision ?? "").Label}"
            : "重判失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private Task Plan(string? triggerId) => PlanAsync(triggerId, dispatch: false);

    [RelayCommand]
    private Task PlanAndDispatch(string? triggerId) => PlanAsync(triggerId, dispatch: true);

    private async Task PlanAsync(string? triggerId, bool dispatch)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(triggerId))
        {
            return;
        }

        var result = await api.SupervisorPlanAsync(triggerId, dispatch).ConfigureAwait(true);
        if (result?.Data is { } data)
        {
            var dispatched = data.Dispatch.Count(item => item.Dispatched);
            StatusText = dispatch
                ? $"已规划 {data.Tickets.Length} 张工单，派发 {dispatched} 张"
                : $"已规划 {data.Tickets.Length} 张工单（未派发）";
        }
        else
        {
            StatusText = "规划失败：" + (result?.Message ?? "后端未响应");
        }

        await RefreshAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task ProcessOutbox()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var result = await api.SupervisorProcessAsync().ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? $"已推进 {data.Processed} 条事件，待处理队列 {data.PendingOutbox}"
            : "推进失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RunWatchdog()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var result = await api.SupervisorWatchdogAsync().ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? $"超时回收：检查 {data.Checked} 条，回收 {data.Expired} 条"
            : "回收失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task OpenTicket(string? ticketId)
    {
        if (string.IsNullOrWhiteSpace(ticketId))
        {
            return;
        }

        await LoadDetailAsync(ticketId).ConfigureAwait(true);
    }

    [RelayCommand]
    private void CloseDetail() => IsDetailOpen = false;

    [RelayCommand]
    private async Task DispatchTicket(string? ticketId)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(ticketId))
        {
            return;
        }

        var result = await api.SupervisorDispatchTicketAsync(ticketId).ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? data.Dispatched ? "已派发" : "未派发：" + data.Reason
            : "派发失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
        await LoadDetailAsync(ticketId).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task RetryTicket(string? ticketId)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(ticketId))
        {
            return;
        }

        var result = await api.SupervisorRetryTicketAsync(ticketId).ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? data.Dispatched ? "已重试并派发" : "重试未派发：" + data.Reason
            : "重试失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
        await LoadDetailAsync(ticketId).ConfigureAwait(true);
    }

    [RelayCommand]
    private async Task StopTicket(string? ticketId)
    {
        var api = _api();
        if (api is null || string.IsNullOrWhiteSpace(ticketId))
        {
            return;
        }

        var result = await api.SupervisorStopTicketAsync(ticketId, "由桌面端手动停止").ConfigureAwait(true);
        StatusText = result?.Data is { } data
            ? $"已停止工单（报告 {data.Report}）"
            : "停止失败：" + (result?.Message ?? "后端未响应");

        await RefreshAsync().ConfigureAwait(true);
        await LoadDetailAsync(ticketId).ConfigureAwait(true);
    }

    private async Task LoadDetailAsync(string ticketId)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var detail = (await api.SupervisorTicketAsync(ticketId).ConfigureAwait(true))?.Data;
        if (detail is null)
        {
            StatusText = "打开工单失败：后端未响应";
            return;
        }

        var (stageLabel, _) = StageMeta(detail.Stage);
        DetailId = detail.Id;
        DetailTitle = detail.Title;
        DetailDescription = string.IsNullOrWhiteSpace(detail.Description) ? "（无描述）" : detail.Description;
        DetailStage = stageLabel;
        DetailRole = string.IsNullOrWhiteSpace(detail.Role) ? "未指定角色" : detail.Role;
        DetailPlanKey = detail.PlanKey;
        DetailDeps = detail.DependsOn.Length == 0 ? "无依赖" : string.Join("、", detail.DependsOn);
        DetailMeta = $"{ShortId(detail.Id)} · 更新于 {RelTime(detail.UpdatedAt)}";

        CanDetailDispatch = detail.Stage is "intake" or "planned";
        CanDetailRetry = detail.Stage == "stopped";
        CanDetailStop = detail.Stage is "running" or "waiting" or "reviewing";

        DetailAssignments.Clear();
        foreach (var assignment in detail.Assignments ?? [])
        {
            DetailAssignments.Add(new DetailAssignmentView(
                string.IsNullOrWhiteSpace(assignment.AgentId) ? "未指定 Agent" : ShortId(assignment.AgentId),
                assignment.Status,
                string.IsNullOrWhiteSpace(assignment.TaskId) ? "无任务" : ShortId(assignment.TaskId),
                string.IsNullOrWhiteSpace(assignment.RunId) ? "无 Run" : ShortId(assignment.RunId),
                RelTime(assignment.StartedAt ?? assignment.FinishedAt ?? detail.UpdatedAt)));
        }

        DetailReports.Clear();
        foreach (var report in detail.Reports ?? [])
        {
            DetailReports.Add(ReportRow(report));
        }

        IsDetailOpen = true;
    }

    // --- 视图模型构建 ---

    private void ApplySettings(SupervisorSettings settings)
    {
        AcceptMode = settings.AcceptMode;
        ModelProvider = settings.ModelProvider;
        ModelName = settings.ModelName;
        PerThreadText = settings.MaxTicketsPerThread.ToString();
        DepthText = settings.MaxTicketDepth.ToString();
        ConcurrentText = settings.MaxConcurrentRuns.ToString();
    }

    private void RebuildStats(int pendingOutbox)
    {
        Stats.Clear();
        Stats.Add(new SupervisorStatView("观察事件", _observations.Count.ToString(), Info));
        Stats.Add(new SupervisorStatView("待处理队列", pendingOutbox.ToString(), Orange));
        Stats.Add(new SupervisorStatView(
            "进行中工单",
            (CountOf("running") + CountOf("waiting")).ToString(),
            Brand));
        Stats.Add(new SupervisorStatView(
            "已收尾工单",
            (CountOf("done") + CountOf("stopped")).ToString(),
            Info));
    }

    private void RebuildObservations()
    {
        Observations.Clear();
        foreach (var item in _observations)
        {
            var (decisionLabel, decisionTone) = DecisionMeta(item.Eval?.Decision ?? "");
            Observations.Add(new ObservationRowView(
                item.Id,
                item.SourceKey,
                TriggerStatusLabel(item.Status),
                item.Eval is null ? "未判断" : decisionLabel,
                item.Eval is null ? Orange : decisionTone,
                $"{(string.IsNullOrWhiteSpace(item.ActorId) ? "未知来源" : item.ActorId)} · {RelTime(item.CreatedAt)}",
                string.IsNullOrWhiteSpace(item.Content) ? "（空事件）" : item.Content,
                string.IsNullOrWhiteSpace(item.Eval?.Reason) ? "" : "判断：" + item.Eval!.Reason,
                !string.IsNullOrWhiteSpace(item.Eval?.Reason),
                new RelayCommand(() => _ = Assess(item.Id)),
                new RelayCommand(() => _ = PlanAsync(item.Id, dispatch: false)),
                new RelayCommand(() => _ = PlanAsync(item.Id, dispatch: true))));
        }

        HasObservations = Observations.Count > 0;
    }

    private void RebuildColumns()
    {
        Columns.Clear();
        foreach (var (_, label, stages) in BoardColumns)
        {
            var cards = _tickets
                .Where(ticket => stages.Contains(ticket.Stage))
                .OrderBy(ticket => ticket.Sequence)
                .Select(Card)
                .ToList();
            Columns.Add(new TicketColumnView(label, cards.Count.ToString(), cards.Count == 0, cards));
        }

        HasTickets = _tickets.Count > 0;
    }

    private TicketCardView Card(SupervisorTicket ticket)
    {
        var (label, tone) = StageMeta(ticket.Stage);
        var summary = ticket.Report?.Summary ?? "";
        return new TicketCardView(
            ticket.Id,
            ticket.Title,
            string.IsNullOrWhiteSpace(ticket.Role) ? "未指定角色" : ticket.Role,
            summary,
            !string.IsNullOrWhiteSpace(summary),
            label,
            tone,
            ticket.Degraded,
            ticket.Stage is "intake" or "planned",
            ticket.Stage == "stopped",
            ticket.Stage is "running" or "waiting" or "reviewing",
            $"{ShortId(ticket.Id)} · {RelTime(ticket.UpdatedAt)}",
            new RelayCommand(() => _ = LoadDetailAsync(ticket.Id)),
            new RelayCommand(() => _ = DispatchTicket(ticket.Id)),
            new RelayCommand(() => _ = RetryTicket(ticket.Id)),
            new RelayCommand(() => _ = StopTicket(ticket.Id)));
    }

    private ReportRowView ReportRow(SupervisorReport report)
    {
        var (label, tone) = ReportMeta(report.Status);
        return new ReportRowView(
            label,
            tone,
            string.IsNullOrWhiteSpace(report.Summary) ? "（无摘要）" : report.Summary,
            $"{ShortId(report.TicketId)} · {RelTime(report.CreatedAt)}");
    }

    private TimelineRowView TimelineRow(SupervisorTimelineItem item)
    {
        var (kindLabel, tone) = item.Kind switch
        {
            "trigger" => ("事件", Info),
            "ticket" => ("工单", Brand),
            "report" => ("报告", Brand),
            _ => (item.Kind, Muted),
        };
        return new TimelineRowView(
            kindLabel,
            item.Status,
            tone,
            item.Title,
            item.Summary ?? "",
            RelTime(item.At));
    }

    private int CountOf(string stage) => _counts.TryGetValue(stage, out var value) ? value : 0;

    // --- 文案映射 ---

    private static (string Label, IBrush Tone) StageMeta(string stage) => stage switch
    {
        "intake" => ("待规划", Muted),
        "planned" => ("待派发", Info),
        "running" => ("执行中", Brand),
        "waiting" => ("等输入", Orange),
        "reviewing" => ("待验收", Info),
        "done" => ("已完成", Brand),
        "stopped" => ("已停止", Danger),
        _ => (stage, Muted),
    };

    private static (string Label, IBrush Tone) DecisionMeta(string decision) => decision switch
    {
        "accept" => ("接受", Brand),
        "stop" => ("停止", Danger),
        "ignore" => ("忽略", Muted),
        "unavailable" => ("未判定", Orange),
        _ => ("未判断", Orange),
    };

    private static (string Label, IBrush Tone) ReportMeta(string status) => status switch
    {
        "completed" => ("完成", Brand),
        "failed" => ("失败", Danger),
        "blocked" => ("阻塞", Orange),
        "needs_input" => ("待输入", Orange),
        "cancelled" => ("已取消", Muted),
        "timed_out" => ("超时", Danger),
        _ => (status, Muted),
    };

    private static string TriggerStatusLabel(string status) => status switch
    {
        "received" => "待处理",
        "gated" => "已过门",
        "assessed" => "已判断",
        "queued" => "已排队",
        "planned" => "已规划",
        "ignored" => "已忽略",
        _ => status,
    };

    private static string ShortId(string id) => id.Length <= 8 ? id : id[..8];

    private static int ClampInt(string text, int min, int max, int fallback) =>
        int.TryParse(text, out var value) ? Math.Clamp(value, min, max) : fallback;

    /// <summary>相对时间（与 web 端 relTime 一致）。</summary>
    private static string RelTime(string? iso)
    {
        if (string.IsNullOrWhiteSpace(iso) || !DateTimeOffset.TryParse(iso, out var moment))
        {
            return "—";
        }

        var diff = DateTimeOffset.Now - moment;
        var minutes = (int)diff.TotalMinutes;
        if (minutes < 1)
        {
            return "刚刚";
        }

        if (minutes < 60)
        {
            return $"{minutes} 分钟前";
        }

        var hours = minutes / 60;
        return hours < 24 ? $"{hours} 小时前" : $"{hours / 24} 天前";
    }
}
