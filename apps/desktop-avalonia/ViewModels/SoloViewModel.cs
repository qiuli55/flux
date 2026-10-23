using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// Solo 任务台：流水线（七步 + 六维确认 + 决策）与单 Agent 直接执行双模式。
/// 左侧任务列表，中间工作区，右侧项目/改动；所有网络动作都走 FluxApiClient 并记日志。
/// </summary>
public partial class SoloViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));
    private static readonly IBrush Text2 = new SolidColorBrush(Color.Parse("#B9C1BE"));

    private readonly Func<FluxApiClient?> _api;
    private readonly Action<string> _navigate;
    private readonly DispatcherTimer _timer;
    private readonly HashSet<string> _autoApplied = new();
    private readonly Dictionary<string, AgentChat> _chats = new();
    private bool _polling;
    private bool _autoApplying;
    private string? _lastTaskId;
    private string? _lastStatus;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsPipeline))]
    private bool _isSoloAgent;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _isNewTask = true;

    [ObservableProperty]
    private string _taskTitle = "新建任务";

    [ObservableProperty]
    private string _statusText = "未创建";

    [ObservableProperty]
    private IBrush _statusBrush = Muted;

    [ObservableProperty]
    private string _taskMeta = "描述需求，Flux 会先澄清再给计划";

    [ObservableProperty]
    private string _stepText = "第 1 / 7 步 · 需求确认";

    [ObservableProperty]
    private bool _hasConfirmation;

    [ObservableProperty]
    private bool _isConfirmationEditable = true;

    [ObservableProperty]
    private string _confirmationActorText = "";

    [ObservableProperty]
    private string _confirmationHint = "六个维度都填写后才能保存并开始执行。";

    [ObservableProperty]
    private string _draft = "";

    [ObservableProperty]
    private bool _isSending;

    [ObservableProperty]
    private bool _hasPendingDecision;

    [ObservableProperty]
    private string _decisionQuestion = "";

    [ObservableProperty]
    private string _decisionContext = "";

    [ObservableProperty]
    private string _decisionHint = "";

    [ObservableProperty]
    private string _decisionNote = "";

    [ObservableProperty]
    private string _strategyText = "自动决策";

    [ObservableProperty]
    private bool _isRunning;

    [ObservableProperty]
    private bool _hasChanges;

    [ObservableProperty]
    private string _changesSummary = "暂无改动";

    [ObservableProperty]
    private string _projectName = "未选择项目";

    [ObservableProperty]
    private string _projectRepo = "—";

    [ObservableProperty]
    private string _workspaceText = "";

    [ObservableProperty]
    private bool _agentBusy;

    [ObservableProperty]
    private string _agentInput = "";

    [ObservableProperty]
    private string _agentHint = "单 Agent 直接执行指令，不经流水线。";

    [ObservableProperty]
    private bool _hasAgentTurns;

    [ObservableProperty]
    private bool _hasRound;

    [ObservableProperty]
    private string _roundFilesText = "";

    [ObservableProperty]
    private string _roundAddText = "+0";

    [ObservableProperty]
    private string _roundDelText = "-0";

    [ObservableProperty]
    private bool _roundExpanded;

    [ObservableProperty]
    private bool _autoApply;

    [ObservableProperty]
    private string _autoApplyText = "需审核";

    [ObservableProperty]
    private AgentChoice? _selectedAgent;

    [ObservableProperty]
    private ModelChoice? _selectedModel;

    public SoloViewModel()
        : this(() => null, _ => { })
    {
    }

    public SoloViewModel(Func<FluxApiClient?> api, Action<string> navigate)
    {
        _api = api;
        _navigate = navigate;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _timer.Tick += async (_, _) => await PollSelectedAsync();
        _timer.Start();
    }

    public bool IsPipeline => !IsSoloAgent;

    public ObservableCollection<TaskRow> Tasks { get; } = new();

    public ObservableCollection<FlowStepRow> FlowSteps { get; } = new();

    public ObservableCollection<ConfirmationEdit> ConfirmationItems { get; } = new();

    public ObservableCollection<MessageRow> Messages { get; } = new();

    public ObservableCollection<DecisionOptionRow> DecisionOptions { get; } = new();

    public ObservableCollection<ChangeRow> Changes { get; } = new();

    public ObservableCollection<AgentTurnRow> AgentTurns { get; } = new();

    public ObservableCollection<ChangeRow> RoundItems { get; } = new();

    public ObservableCollection<AgentChoice> AgentChoices { get; } = new();

    public ObservableCollection<ModelChoice> ModelChoices { get; } = new();

    public TaskDetail? SelectedTask { get; private set; }

    public async Task RefreshAsync()
    {
        var api = Api();
        if (api is null)
        {
            AppLog.Warn("Solo 刷新跳过：后端尚未就绪");
            return;
        }

        IsBusy = true;
        try
        {
            await LoadProjectsAsync(api);
            await RefreshTaskListAsync(api);
            if (!IsNewTask)
            {
                var target = SelectedTask?.Id ?? Tasks.FirstOrDefault()?.Id;
                if (target is not null)
                {
                    await LoadTaskAsync(target);
                }
            }

            await LoadAgentOptionsAsync(api);
            await RefreshChangesAsync();
            WorkspaceText = (await api.WorkspaceRootAsync())?.Data?.Root ?? "";
            AppLog.Info($"Solo 刷新完成 · 任务 {Tasks.Count} 个 · 改动 {Changes.Count} 个");
        }
        catch (Exception ex)
        {
            AppLog.Error("Solo 刷新失败", ex);
            AgentHint = $"加载失败：{ex.Message}";
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void NewTask()
    {
        IsNewTask = true;
        SelectedTask = null;
        TaskTitle = "新建任务";
        StatusText = "未创建";
        StatusBrush = Muted;
        TaskMeta = "描述需求，Flux 会先澄清再给计划";
        StepText = "第 1 / 7 步 · 需求确认";
        HasConfirmation = false;
        ConfirmationItems.Clear();
        Messages.Clear();
        DecisionOptions.Clear();
        HasPendingDecision = false;
        DecisionNote = "";
        Draft = "";
        BuildFlow(1);
        foreach (var row in Tasks)
        {
            row.IsActive = false;
        }

        SwitchChat(null);
        AppLog.Info("Solo 新建任务草稿");
    }

    [RelayCommand]
    private async Task SelectTaskAsync(TaskRow? row)
    {
        if (row is null)
        {
            return;
        }

        IsNewTask = false;
        foreach (var item in Tasks)
        {
            item.IsActive = item.Id == row.Id;
        }

        await LoadTaskAsync(row.Id);
    }

    [RelayCommand]
    private async Task SendAsync()
    {
        var api = Api();
        if (api is null || IsSending)
        {
            return;
        }

        var content = Draft.Trim();
        if (content.Length == 0)
        {
            ConfirmationHint = "请输入内容后再发送。";
            return;
        }

        IsSending = true;
        Draft = "";
        try
        {
            var taskId = SelectedTask?.Id;
            if (taskId is null)
            {
                var created = (await api.CreateTaskAsync(content, null, null, "auto"))?.Data;
                if (created is null)
                {
                    throw new InvalidOperationException("创建任务没有返回数据");
                }

                ApplyTask(created);
                IsNewTask = false;
                taskId = created.Id;
                await RefreshTaskListAsync(api);
            }
            else if (SoloRules.IsTerminalStatus(SelectedTask?.Status))
            {
                throw new InvalidOperationException("任务已结束，请新建任务后继续。");
            }

            var result = (await api.CreateTaskMessageAsync(taskId, content))?.Data;
            if (result is null)
            {
                throw new InvalidOperationException("发送消息没有返回数据");
            }

            ApplyTask(result.Task);
            AppendMessages(result.Messages);
            ConfirmationHint = $"已落库 · {result.Messages.Length} 条消息";
            await RefreshTaskListAsync(api);
            AppLog.Info($"Solo 发送消息 · task={taskId} · {result.Messages.Length} 条");
        }
        catch (Exception ex)
        {
            Draft = content;
            ConfirmationHint = $"发送失败：{ex.Message}";
            AppLog.Error($"Solo 发送消息失败 · task={SelectedTask?.Id}", ex);
        }
        finally
        {
            IsSending = false;
        }
    }

    [RelayCommand]
    private async Task SaveConfirmationAsync()
    {
        var api = Api();
        var taskId = SelectedTask?.Id;
        if (api is null || taskId is null)
        {
            return;
        }

        var items = ConfirmationItems
            .Select(x => new ConfirmationItem(x.Label, x.Value.Trim()))
            .ToArray();
        if (items.Any(x => x.Value.Length == 0))
        {
            ConfirmationHint = "六个维度都填写后才能保存。";
            return;
        }

        try
        {
            var result = (await api.UpdateConfirmationAsync(taskId, items))?.Data;
            if (result is null)
            {
                throw new InvalidOperationException("保存确认没有返回数据");
            }

            ApplyTask(result.Task);
            await ReloadMessagesAsync(taskId);
            ConfirmationHint = "确认已保存，可以开始执行。";
            AppLog.Info($"Solo 保存确认 · task={taskId} · {items.Length} 维");
        }
        catch (Exception ex)
        {
            ConfirmationHint = $"保存失败：{ex.Message}";
            AppLog.Error($"Solo 保存确认失败 · task={taskId}", ex);
        }
    }

    [RelayCommand]
    private async Task StartTaskAsync()
    {
        var api = Api();
        var taskId = SelectedTask?.Id;
        if (api is null || taskId is null)
        {
            return;
        }

        try
        {
            ConfirmationItem[]? items = null;
            if (HasConfirmation)
            {
                var edited = ConfirmationItems
                    .Select(x => new ConfirmationItem(x.Label, x.Value.Trim()))
                    .ToArray();
                if (edited.All(x => x.Value.Length > 0))
                {
                    items = edited;
                }
            }

            // 后端在 runtime 未启用等配置问题上会返回 success=false 且 data=null，直接把原因透给用户
            var envelope = await api.StartTaskAsync(taskId, items);
            var result = envelope?.Data;
            if (result is null)
            {
                throw new InvalidOperationException(envelope?.Message ?? "开始执行没有返回数据");
            }

            ApplyTask(result.Task);
            await ReloadMessagesAsync(taskId);
            await RefreshChangesAsync(taskId);
            await MaybeAutoApplyPipelineAsync(api);
            ConfirmationHint = "已开始执行，Flux 会在需要你决策时停下。";
            AppLog.Info($"Solo 开始执行 · task={taskId}");
        }
        catch (Exception ex)
        {
            ConfirmationHint = $"开始失败：{ex.Message}";
            AppLog.Error($"Solo 开始执行失败 · task={taskId}", ex);
        }
    }

    [RelayCommand]
    private async Task CancelTaskAsync()
    {
        var api = Api();
        var taskId = SelectedTask?.Id;
        if (api is null || taskId is null)
        {
            return;
        }

        try
        {
            var task = (await api.CancelTaskAsync(taskId))?.Data;
            if (task is not null)
            {
                ApplyTask(task);
            }

            await ReloadMessagesAsync(taskId);
            await RefreshChangesAsync(taskId);
            AppLog.Info($"Solo 取消执行 · task={taskId}");
        }
        catch (Exception ex)
        {
            ConfirmationHint = $"取消失败：{ex.Message}";
            AppLog.Error($"Solo 取消执行失败 · task={taskId}", ex);
        }
    }

    [RelayCommand]
    private async Task ChooseDecisionAsync(DecisionOptionRow? option)
    {
        if (option is null)
        {
            return;
        }

        await ResolveDecisionAsync(option.Label);
    }

    [RelayCommand]
    private async Task RejectDecisionAsync()
    {
        await ResolveDecisionAsync(null);
    }

    private async Task ResolveDecisionAsync(string? choice)
    {
        var api = Api();
        var taskId = SelectedTask?.Id;
        var pending = SelectedTask?.PendingDecision;
        if (api is null || taskId is null || pending is null)
        {
            return;
        }

        try
        {
            var action = choice is null ? "reject" : "choose";
            var note = DecisionNote.Trim();
            var result = (await api.ChooseDecisionAsync(
                taskId,
                pending.Id,
                action,
                choice,
                note.Length == 0 ? null : note))?.Data;
            if (result is null)
            {
                throw new InvalidOperationException("决策提交没有返回数据");
            }

            ApplyTask(result.Task);
            await ReloadMessagesAsync(taskId);
            DecisionNote = "";
            AppLog.Info($"Solo 提交决策 · task={taskId} · action={action}");
        }
        catch (Exception ex)
        {
            DecisionContext = $"提交失败：{ex.Message}";
            AppLog.Error($"Solo 提交决策失败 · task={taskId}", ex);
        }
    }

    [RelayCommand]
    private void SwitchToAgent() => IsSoloAgent = true;

    [RelayCommand]
    private void SwitchToPipeline() => IsSoloAgent = false;

    [RelayCommand]
    private async Task SendAgentAsync()
    {
        var api = Api();
        if (api is null || AgentBusy)
        {
            return;
        }

        var text = AgentInput.Trim();
        if (text.Length == 0)
        {
            AgentHint = "请输入指令后再发送。";
            return;
        }

        var chat = ChatFor(SelectedTask?.Id);
        AgentInput = "";
        AgentBusy = true;
        AgentHint = "Agent 正在执行…";

        var userTurn = new AgentTurnRow(true, "你", text, "");
        var agentTurn = new AgentTurnRow(false, "Agent", "正在思考…", "排队中") { IsRunning = true };
        chat.Turns.Add(userTurn);
        chat.Turns.Add(agentTurn);
        AgentTurns.Add(userTurn);
        AgentTurns.Add(agentTurn);
        HasAgentTurns = true;

        try
        {
            var baseline = (await api.ListChangesAsync("pending"))?.Data ?? [];
            chat.BaselineIds = baseline.Select(c => c.Id).ToHashSet();

            var envelope = await api.StartDshRunAsync(
                text,
                chat.SessionId,
                SelectedAgent?.Id,
                SelectedModel?.Model,
                SelectedModel?.KeyId);
            var run = envelope?.Data;
            if (run is null)
            {
                // runtime 未启用 / 鉴权失败等配置问题会带 success=false，直接把后端原因透出来
                throw new InvalidOperationException(envelope?.Message ?? "DSH 没有返回 Run");
            }

            chat.SessionId = run.SessionId;
            chat.RunId = run.RunId;
            await PollAgentRunAsync(api, chat, run, agentTurn);
        }
        catch (Exception ex)
        {
            agentTurn.Text = $"运行失败：{ex.Message}";
            agentTurn.IsFailed = true;
            agentTurn.IsRunning = false;
            agentTurn.Meta = "失败";
            AgentHint = "执行失败，可直接重发或清空后重试。";
            AppLog.Error($"Solo 单 Agent 运行失败 · task={SelectedTask?.Id}", ex);
        }
        finally
        {
            AgentBusy = false;
        }
    }

    [RelayCommand]
    private async Task StopAgentAsync()
    {
        var api = Api();
        var chat = ChatFor(SelectedTask?.Id);
        if (api is null || chat.RunId is null)
        {
            return;
        }

        try
        {
            var run = (await api.InterruptDshRunAsync(chat.RunId))?.Data;
            AgentHint = run is null ? "已请求停止。" : $"已请求停止 · {SoloRules.RunLabel(run.Status)}";
            AppLog.Info($"Solo 停止 Agent · run={chat.RunId}");
        }
        catch (Exception ex)
        {
            AgentHint = $"停止失败：{ex.Message}";
            AppLog.Error($"Solo 停止 Agent 失败 · run={chat.RunId}", ex);
        }
    }

    [RelayCommand]
    private void ClearAgentChat()
    {
        var chat = ChatFor(SelectedTask?.Id);
        chat.Turns.Clear();
        chat.Round.Clear();
        chat.SessionId = null;
        chat.RunId = null;
        AgentTurns.Clear();
        RoundItems.Clear();
        HasAgentTurns = false;
        HasRound = false;
        AgentHint = "对话已清空，下次指令会开新会话。";
        AppLog.Info($"Solo 清空单 Agent 对话 · task={SelectedTask?.Id}");
    }

    [RelayCommand]
    private async Task ToggleAutoApplyAsync()
    {
        AutoApply = !AutoApply;
        AutoApplyText = AutoApply ? "自动落盘" : "需审核";
        AppLog.Info($"Solo 审核开关 · auto={AutoApply}");
        if (AutoApply)
        {
            await KeepRoundAsync();
        }
    }

    [RelayCommand]
    private async Task KeepRoundAsync()
    {
        var api = Api();
        var chat = ChatFor(SelectedTask?.Id);
        var ids = chat.Round.Where(c => c.Status == "pending").Select(c => c.Id).ToArray();
        if (api is null || ids.Length == 0)
        {
            return;
        }

        try
        {
            var applied = (await api.ApplyChangesAsync(ids))?.Data ?? new List<WorkspaceChange>();
            chat.Round = applied.ToList();
            RebuildRound(chat);
            await RefreshChangesAsync();
            AgentHint = $"已保留并落盘 {ids.Length} 个改动。";
            AppLog.Info($"Solo 本轮改动保留 · {ids.Length} 个");
        }
        catch (Exception ex)
        {
            AgentHint = $"保留失败：{ex.Message}";
            AppLog.Error("Solo 本轮改动保留失败", ex);
        }
    }

    [RelayCommand]
    private async Task UndoRoundAsync()
    {
        var api = Api();
        var chat = ChatFor(SelectedTask?.Id);
        if (api is null || chat.Round.Count == 0)
        {
            return;
        }

        var pending = chat.Round.Where(c => c.Status == "pending").Select(c => c.Id).ToArray();
        var applied = chat.Round.Where(c => c.Status == "applied").Select(c => c.Id).ToArray();
        try
        {
            if (pending.Length > 0)
            {
                await api.RejectChangesAsync(pending, "Solo 单 Agent 本轮撤销");
            }

            foreach (var id in applied)
            {
                await api.RollbackChangeAsync(id);
            }

            chat.Round.Clear();
            RebuildRound(chat);
            await RefreshChangesAsync();
            AgentHint = $"已撤销本轮改动 · 拒绝 {pending.Length} · 回滚 {applied.Length}";
            AppLog.Info($"Solo 本轮改动撤销 · reject={pending.Length} rollback={applied.Length}");
        }
        catch (Exception ex)
        {
            AgentHint = $"撤销失败：{ex.Message}";
            AppLog.Error("Solo 本轮改动撤销失败", ex);
        }
    }

    [RelayCommand]
    private void ToggleRound() => RoundExpanded = !RoundExpanded;

    [RelayCommand]
    private void OpenProjects() => _navigate("projects");

    private FluxApiClient? Api() => _api();

    private async Task LoadProjectsAsync(FluxApiClient api)
    {
        var projects = (await api.ListProjectsAsync())?.Data ?? [];
        var first = projects.FirstOrDefault();
        ProjectName = first?.Name ?? "未选择项目";
        ProjectRepo = first?.Repository ?? "—";
        AppLog.Info($"Solo 项目 · {projects.Count} 个");
    }

    private async Task RefreshTaskListAsync(FluxApiClient api)
    {
        var tasks = (await api.ListTasksAsync(100))?.Data ?? [];
        var activeId = IsNewTask ? null : SelectedTask?.Id;
        Tasks.Clear();
        foreach (var task in tasks)
        {
            Tasks.Add(new TaskRow(task, SelectTaskCommand) { IsActive = task.Id == activeId });
        }

        if (!IsNewTask && activeId is null && Tasks.Count > 0)
        {
            await SelectTaskAsync(Tasks[0]);
        }
    }

    private async Task LoadTaskAsync(string taskId)
    {
        var api = Api();
        if (api is null)
        {
            return;
        }

        try
        {
            var task = (await api.GetTaskAsync(taskId))?.Data;
            if (task is null)
            {
                throw new InvalidOperationException("任务不存在");
            }

            ApplyTask(task);
            await ReloadMessagesAsync(taskId);
            await RefreshChangesAsync(taskId);
            AppLog.Info($"Solo 载入任务 · {taskId} · {task.Status}");
        }
        catch (Exception ex)
        {
            ConfirmationHint = $"载入任务失败：{ex.Message}";
            AppLog.Error($"Solo 载入任务失败 · {taskId}", ex);
        }
    }

    private void ApplyTask(TaskDetail task)
    {
        // 状态只在这一处汇总：轮询、开始/取消、任务切换都走它，状态变化留一条日志便于排查
        if (_lastTaskId != task.Id || _lastStatus != task.Status)
        {
            AppLog.Info($"Solo 任务状态 · {SoloRules.ShortId(task.Id)} · {_lastStatus ?? "无"} → {task.Status}");
            _lastTaskId = task.Id;
            _lastStatus = task.Status;
        }

        SelectedTask = task;
        IsNewTask = false;
        TaskTitle = SoloRules.Title(task.Description);
        StatusText = SoloRules.StatusLabel(task.Status);
        StatusBrush = ToneBrush(SoloRules.StatusTone(task.Status));
        TaskMeta = $"{SoloRules.ShortId(task.Id)} · {Clock(task.CreatedAt)} · {task.AgentId ?? "内置执行器"}";
        StrategyText = task.DecisionMode == "manual" ? "手动决策" : "自动决策";
        IsRunning = task.Status == "running";
        var step = SoloRules.FlowStep(task.Status, task.Confirmation?.Items is { Length: > 0 });
        StepText = $"第 {step} / 7 步 · {SoloRules.FlowNames[step - 1]}";
        BuildFlow(step);
        ApplyConfirmation(task.Confirmation, task.Status);
        ApplyDecision(task.PendingDecision);
        SwitchChat(task.Id);
    }

    private void ApplyConfirmation(TaskConfirmation? confirmation, string status)
    {
        var items = confirmation?.Items ?? [];
        HasConfirmation = items.Length > 0;
        IsConfirmationEditable = status == "pending";
        ConfirmationActorText = confirmation?.Actor switch
        {
            "assistant" => "Agent 整理，可修改后保存",
            "user" => $"你已确认 · {Clock(confirmation.UpdatedAt)}",
            _ => "",
        };

        if (!HasConfirmation)
        {
            ConfirmationItems.Clear();
            return;
        }

        var sameShape = ConfirmationItems.Count == items.Length
            && ConfirmationItems.Select(x => x.Label).SequenceEqual(items.Select(x => x.Label));
        if (!sameShape)
        {
            ConfirmationItems.Clear();
            foreach (var item in items)
            {
                ConfirmationItems.Add(new ConfirmationEdit(item.Label, item.Value));
            }
        }
    }

    private void ApplyDecision(TaskDecision? pending)
    {
        HasPendingDecision = pending is not null && pending.Status is null or "pending" or "open";
        DecisionOptions.Clear();
        if (!HasPendingDecision || pending is null)
        {
            return;
        }

        DecisionQuestion = pending.Question;
        DecisionContext = pending.Context ?? "";
        DecisionHint = pending.Recommendation is { Length: > 0 } rec ? $"建议：{rec}" : "";
        foreach (var option in pending.Options ?? [])
        {
            DecisionOptions.Add(new DecisionOptionRow(option, ChooseDecisionCommand));
        }
    }

    private void BuildFlow(int step)
    {
        FlowSteps.Clear();
        for (var i = 1; i <= 7; i++)
        {
            var state = i < step ? "done" : i == step ? "active" : "todo";
            FlowSteps.Add(new FlowStepRow(i, SoloRules.FlowNames[i - 1], state));
        }
    }

    private async Task ReloadMessagesAsync(string taskId)
    {
        var api = Api();
        if (api is null)
        {
            return;
        }

        var messages = (await api.ListTaskMessagesAsync(taskId))?.Data ?? [];
        Messages.Clear();
        foreach (var message in messages)
        {
            Messages.Add(new MessageRow(message));
        }

        AppLog.Info($"Solo 消息 · task={taskId} · {messages.Count} 条");
    }

    private void AppendMessages(IEnumerable<TaskMessage> messages)
    {
        foreach (var message in messages)
        {
            Messages.Add(new MessageRow(message));
        }
    }

    private async Task RefreshChangesAsync(string? taskId = null)
    {
        var api = Api();
        if (api is null)
        {
            return;
        }

        var id = taskId ?? (IsNewTask ? null : SelectedTask?.Id);
        var pending = (await api.ListChangesAsync("pending", id))?.Data ?? new List<WorkspaceChange>();
        var applied = id is null
            ? new List<WorkspaceChange>()
            : (await api.ListChangesAsync("applied", id))?.Data ?? new List<WorkspaceChange>();
        var all = pending.Concat(applied).GroupBy(c => c.Id).Select(g => g.First()).ToList();

        var expanded = Changes.Where(x => x.IsExpanded).Select(x => x.Id).ToHashSet();
        Changes.Clear();
        foreach (var change in all)
        {
            var row = new ChangeRow(change, ToggleChangeCommand, ReviewChangeCommand);
            row.IsExpanded = expanded.Contains(change.Id);
            Changes.Add(row);
        }

        var totals = SoloRules.Totals(all);
        HasChanges = Changes.Count > 0;
        ChangesSummary = HasChanges
            ? $"{totals.Files} 个文件 · +{totals.Added} / -{totals.Removed}"
            : "暂无改动";
    }

    [RelayCommand]
    private void ToggleChange(ChangeRow? row)
    {
        if (row is not null)
        {
            row.IsExpanded = !row.IsExpanded;
        }
    }

    [RelayCommand]
    private async Task ReviewChangeAsync(ChangeRow? row)
    {
        var api = Api();
        if (api is null || row is null)
        {
            return;
        }

        try
        {
            if (row.IsPending)
            {
                await api.ApplyChangesAsync([row.Id]);
            }
            else if (row.Status == "applied")
            {
                await api.RollbackChangeAsync(row.Id);
            }

            await RefreshChangesAsync();
            AppLog.Info($"Solo 改动审核 · {row.FilePath} · {row.Status}");
        }
        catch (Exception ex)
        {
            AgentHint = $"改动处理失败：{ex.Message}";
            AppLog.Error($"Solo 改动审核失败 · {row.FilePath}", ex);
        }
    }

    private async Task MaybeAutoApplyPipelineAsync(FluxApiClient api)
    {
        if (IsSoloAgent || _autoApplying || SelectedTask is null)
        {
            return;
        }

        var pending = (await api.ListChangesAsync("pending", SelectedTask.Id))?.Data ?? [];
        var ids = pending.Select(c => c.Id).Where(id => !_autoApplied.Contains(id)).ToArray();
        if (ids.Length == 0)
        {
            return;
        }

        _autoApplying = true;
        try
        {
            await api.ApplyChangesAsync(ids);
            foreach (var id in ids)
            {
                _autoApplied.Add(id);
            }

            await RefreshChangesAsync(SelectedTask.Id);
            AppLog.Info($"Solo 自动落盘 · task={SelectedTask.Id} · {ids.Length} 个");
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Solo 自动落盘失败 · task={SelectedTask.Id} · {ex.Message}");
        }
        finally
        {
            _autoApplying = false;
        }
    }

    private async Task PollSelectedAsync()
    {
        if (_polling || IsBusy)
        {
            return;
        }

        var api = Api();
        var taskId = SelectedTask?.Id;
        if (api is null || taskId is null)
        {
            return;
        }

        _polling = true;
        try
        {
            var wasActive = SoloRules.IsActiveStatus(SelectedTask?.Status);
            var task = (await api.GetTaskAsync(taskId))?.Data;
            if (task is null)
            {
                return;
            }

            ApplyTask(task);
            if (wasActive && !SoloRules.IsActiveStatus(task.Status))
            {
                await ReloadMessagesAsync(taskId);
            }

            await RefreshChangesAsync(taskId);
            await MaybeAutoApplyPipelineAsync(api);
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Solo 轮询失败 · task={taskId} · {ex.Message}");
        }
        finally
        {
            _polling = false;
        }
    }

    private async Task LoadAgentOptionsAsync(FluxApiClient api)
    {
        var models = (await api.DshModelsAsync())?.Data;
        ModelChoices.Clear();
        ModelChoices.Add(new ModelChoice(null, null, "默认模型"));
        foreach (var model in models?.Models ?? [])
        {
            ModelChoices.Add(new ModelChoice(null, model, model));
        }

        foreach (var keyModel in models?.KeyModels ?? [])
        {
            var label = string.IsNullOrWhiteSpace(keyModel.KeyName)
                ? keyModel.Model
                : $"{keyModel.Model} · {keyModel.KeyName}";
            ModelChoices.Add(new ModelChoice(keyModel.KeyId, keyModel.Model, label));
        }

        SelectedModel ??= ModelChoices.FirstOrDefault();

        var agents = (await api.ListAgentDetailsAsync())?.Data ?? [];
        AgentChoices.Clear();
        AgentChoices.Add(new AgentChoice(null, "内置 DSH"));
        foreach (var agent in agents)
        {
            var label = $"{agent.Spec.Name} · {agent.Spec.Role ?? agent.Spec.Runtime ?? "agent"}";
            AgentChoices.Add(new AgentChoice(agent.Id, label));
        }

        SelectedAgent ??= AgentChoices.FirstOrDefault();
        AppLog.Info($"Solo Agent 选项 · {agents.Count} 个 · 模型 {ModelChoices.Count} 个");
    }

    private async Task PollAgentRunAsync(
        FluxApiClient api,
        AgentChat chat,
        DshRun run,
        AgentTurnRow turn,
        bool allowSessionRetry = true)
    {
        var current = run;
        var deadline = DateTime.UtcNow.AddMinutes(60);
        var misses = 0;
        while (!SoloRules.IsRunTerminal(current.Status))
        {
            // 轮询必须有出口：Run 长时间不落定或后端持续不可达时放弃，避免无限挂起
            if (DateTime.UtcNow > deadline)
            {
                throw new TimeoutException("等待 DSH Run 落定超过 60 分钟，已停止轮询。");
            }

            await Task.Delay(1000);
            var fresh = (await api.GetDshRunAsync(run.RunId))?.Data;
            if (fresh is null)
            {
                if (++misses >= 30)
                {
                    throw new InvalidOperationException("连续 30 次取不到 DSH Run 状态，已停止轮询。");
                }

                continue;
            }

            misses = 0;
            current = fresh;
            turn.RunStatus = SoloRules.RunLabel(current.Status);
            if (!string.IsNullOrWhiteSpace(current.FinalResponse))
            {
                turn.Text = current.FinalResponse!;
            }
        }

        turn.IsRunning = false;
        turn.RunStatus = SoloRules.RunLabel(current.Status);
        turn.Meta = current.DurationSeconds is { } seconds ? $"{seconds:F1}s" : "";
        if (current.Status is "failed" or "timeout" or "cancelled" or "interrupted")
        {
            turn.IsFailed = current.Status is "failed" or "timeout";
            turn.Text = string.IsNullOrWhiteSpace(current.Error)
                ? turn.IsFailed
                    ? $"运行失败：{SoloRules.RunLabel(current.Status)}"
                    : SoloRules.RunLabel(current.Status)!
                : current.Error!;
        }

        AppLog.Info($"Solo Agent 运行结束 · run={current.RunId} · {current.Status}");
        if (turn.IsFailed && allowSessionRetry && SoloRules.IsSessionConflict(current.Error))
        {
            // runtime 每 Run 新起进程，复用旧会话 id 会被拒；降级为新会话重发一次（只一次）
            var retried = await RestartAgentRunAsync(api, chat, current);
            turn.IsFailed = false;
            turn.IsRunning = true;
            turn.Meta = "";
            turn.Text = "会话已重建，正在重新执行…";
            await PollAgentRunAsync(api, chat, retried, turn, allowSessionRetry: false);
            return;
        }

        await ClaimRoundChangesAsync(api, chat);
        await RefreshChangesAsync();
    }

    /// <summary>
    /// 会话冲突后重建会话：把失败 Run 的原始指令用新会话再发一次。
    /// 后端不可改，桌面端只做一次降级，避免多轮对话在第二轮就卡死。
    /// </summary>
    private async Task<DshRun> RestartAgentRunAsync(FluxApiClient api, AgentChat chat, DshRun failed)
    {
        if (string.IsNullOrWhiteSpace(failed.Instruction))
        {
            throw new InvalidOperationException("会话冲突但原始指令为空，无法重发。");
        }

        var stale = chat.SessionId;
        chat.SessionId = null;
        chat.RunId = null;
        AppLog.Info($"Solo 会话冲突，改用新会话重发 · 旧会话={stale} · run={failed.RunId}");
        var envelope = await api.StartDshRunAsync(
            failed.Instruction,
            null,
            SelectedAgent?.Id,
            SelectedModel?.Model,
            SelectedModel?.KeyId);
        var run = envelope?.Data;
        if (run is null)
        {
            throw new InvalidOperationException(envelope?.Message ?? "DSH 没有返回 Run");
        }

        chat.SessionId = run.SessionId;
        chat.RunId = run.RunId;
        return run;
    }

    private async Task ClaimRoundChangesAsync(FluxApiClient api, AgentChat chat)
    {
        var pending = (await api.ListChangesAsync("pending"))?.Data ?? new List<WorkspaceChange>();
        var mine = pending.Where(c => !chat.BaselineIds.Contains(c.Id)).ToList();
        if (mine.Count == 0)
        {
            AgentHint = "本轮没有产生文件改动。";
            return;
        }

        if (AutoApply)
        {
            var applied = (await api.ApplyChangesAsync(mine.Select(c => c.Id)))?.Data ?? mine;
            chat.Round = applied.ToList();
            AgentHint = $"已自动落盘 {applied.Count} 个改动。";
            AppLog.Info($"Solo Agent 自动落盘 · {applied.Count} 个");
        }
        else
        {
            chat.Round = mine;
            AgentHint = $"本轮产出 {mine.Count} 个改动，等待保留或撤销。";
            AppLog.Info($"Solo Agent 待审核改动 · {mine.Count} 个");
        }

        RebuildRound(chat);
    }

    private AgentChat ChatFor(string? taskId)
    {
        var key = SoloRules.AgentChatKey(taskId);
        if (!_chats.TryGetValue(key, out var chat))
        {
            chat = new AgentChat();
            _chats[key] = chat;
        }

        return chat;
    }

    private void SwitchChat(string? taskId)
    {
        var chat = ChatFor(taskId);
        AgentTurns.Clear();
        foreach (var turn in chat.Turns)
        {
            AgentTurns.Add(turn);
        }

        HasAgentTurns = AgentTurns.Count > 0;
        RebuildRound(chat);
    }

    private void RebuildRound(AgentChat chat)
    {
        RoundItems.Clear();
        foreach (var change in chat.Round)
        {
            RoundItems.Add(new ChangeRow(change, ToggleChangeCommand, ReviewChangeCommand));
        }

        var totals = SoloRules.Totals(chat.Round);
        HasRound = chat.Round.Count > 0;
        RoundFilesText = $"{totals.Files} 个文件";
        RoundAddText = $"+{totals.Added}";
        RoundDelText = $"-{totals.Removed}";
    }

    private static IBrush ToneBrush(string tone)
        => tone switch
        {
            "run" => Info,
            "wait" => Orange,
            "ok" => Brand,
            "fail" => Danger,
            _ => Muted,
        };

    private static string Clock(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "—";
        }

        return DateTime.TryParse(value, out var time) ? time.ToLocalTime().ToString("MM-dd HH:mm") : value;
    }

    private sealed class AgentChat
    {
        public string? SessionId { get; set; }

        public string? RunId { get; set; }

        public List<AgentTurnRow> Turns { get; } = new();

        public List<WorkspaceChange> Round { get; set; } = new();

        public HashSet<string> BaselineIds { get; set; } = new();
    }
}

/// <summary>左栏任务行。</summary>
public partial class TaskRow : ObservableObject
{
    public TaskRow(FluxTask task, ICommand select)
    {
        Id = task.Id;
        Title = SoloRules.Title(task.Description);
        Meta = $"{SoloRules.ShortId(task.Id)} · {ClockText(task.CreatedAt)}";
        StatusText = SoloRules.StatusLabel(task.Status);
        StatusBrush = ToneBrush(SoloRules.StatusTone(task.Status));
        SelectCommand = select;
    }

    public string Id { get; }

    public string Title { get; }

    public string Meta { get; }

    public string StatusText { get; }

    public IBrush StatusBrush { get; }

    public ICommand SelectCommand { get; }

    [ObservableProperty]
    private bool _isActive;

    private static string ClockText(string? value)
        => DateTime.TryParse(value, out var time) ? time.ToLocalTime().ToString("MM-dd HH:mm") : "—";

    private static IBrush ToneBrush(string tone)
        => tone switch
        {
            "run" => new SolidColorBrush(Color.Parse("#7FB6E0")),
            "wait" => new SolidColorBrush(Color.Parse("#E8B25E")),
            "ok" => new SolidColorBrush(Color.Parse("#3DF5A8")),
            "fail" => new SolidColorBrush(Color.Parse("#FF7A6E")),
            _ => new SolidColorBrush(Color.Parse("#6C7674")),
        };
}

/// <summary>七步流程的一行。</summary>
public sealed class FlowStepRow
{
    public FlowStepRow(int index, string name, string state)
    {
        Marker = state == "done" ? "✓" : index.ToString();
        Name = name;
        IsDone = state == "done";
        IsActive = state == "active";
        DotBrush = state switch
        {
            "done" => new SolidColorBrush(Color.Parse("#3DF5A8")),
            "active" => new SolidColorBrush(Color.Parse("#7FB6E0")),
            _ => new SolidColorBrush(Color.Parse("#39413F")),
        };
        TextBrush = state switch
        {
            "done" => new SolidColorBrush(Color.Parse("#B9C1BE")),
            "active" => new SolidColorBrush(Color.Parse("#EAF0EE")),
            _ => new SolidColorBrush(Color.Parse("#6C7674")),
        };
    }

    public string Marker { get; }

    public string Name { get; }

    public bool IsDone { get; }

    public bool IsActive { get; }

    public IBrush DotBrush { get; }

    public IBrush TextBrush { get; }
}

/// <summary>六维确认的可编辑项。</summary>
public partial class ConfirmationEdit : ObservableObject
{
    public ConfirmationEdit(string label, string value)
    {
        Label = label;
        _value = value;
    }

    public string Label { get; }

    [ObservableProperty]
    private string _value;
}

/// <summary>对话消息行。</summary>
public sealed class MessageRow
{
    public MessageRow(TaskMessage message)
    {
        IsUser = message.Role == "user";
        Author = message.Role switch
        {
            "user" => "你",
            "assistant" => "Flux",
            "system" => "系统",
            _ => message.Role,
        };
        Content = message.Content;
        Meta = $"{Author} · {ClockText(message.CreatedAt)}";
    }

    public bool IsUser { get; }

    public string Author { get; }

    public string Content { get; }

    public string Meta { get; }

    private static string ClockText(string? value)
        => DateTime.TryParse(value, out var time) ? time.ToLocalTime().ToString("HH:mm") : "";
}

/// <summary>决策候选行。</summary>
public sealed class DecisionOptionRow
{
    public DecisionOptionRow(DecisionOption option, ICommand choose)
    {
        Label = option.Label;
        Description = option.Description ?? "";
        Impact = option.Impact ?? "";
        Badge = option.Recommended ? "推荐" : "";
        IsRecommended = option.Recommended;
        ChooseCommand = choose;
    }

    public string Label { get; }

    public string Description { get; }

    public string Impact { get; }

    public string Badge { get; }

    public bool IsRecommended { get; }

    public ICommand ChooseCommand { get; }
}

/// <summary>单 Agent 对话轮次。</summary>
public partial class AgentTurnRow : ObservableObject
{
    public AgentTurnRow(bool isUser, string author, string text, string meta)
    {
        IsUser = isUser;
        Author = author;
        _text = text;
        _meta = meta;
    }

    public bool IsUser { get; }

    public string Author { get; }

    [ObservableProperty]
    private string _text;

    [ObservableProperty]
    private string _meta;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAgent))]
    private bool _isRunning;

    [ObservableProperty]
    private bool _isFailed;

    [ObservableProperty]
    private string _runStatus = "";

    public bool IsAgent => !IsUser;
}

/// <summary>改动行（右侧列表与本轮改动条共用）。</summary>
public partial class ChangeRow : ObservableObject
{
    public ChangeRow(WorkspaceChange change, ICommand toggle, ICommand review)
    {
        Id = change.Id;
        FilePath = change.FilePath;
        AddText = $"+{change.AddedLines}";
        DelText = $"-{change.RemovedLines}";
        ToggleCommand = toggle;
        ReviewCommand = review;
        Source = change;
        Lines = BuildLines(change.Diff);
        Mark(change);
    }

    public string Id { get; }

    public string FilePath { get; }

    public string AddText { get; }

    public string DelText { get; }

    public string Status { get; private set; } = "pending";

    public WorkspaceChange Source { get; private set; }

    public ObservableCollection<DiffLineRow> Lines { get; }

    public ICommand ToggleCommand { get; }

    public ICommand ReviewCommand { get; }

    [ObservableProperty]
    private bool _isPending = true;

    [ObservableProperty]
    private bool _canReview = true;

    [ObservableProperty]
    private bool _isExpanded;

    [ObservableProperty]
    private string _stateText = "待审核";

    [ObservableProperty]
    private IBrush _stateBrush = new SolidColorBrush(Color.Parse("#E8B25E"));

    [ObservableProperty]
    private string _actionText = "批准";

    public void Mark(WorkspaceChange change)
    {
        Source = change;
        Status = change.Status;
        IsPending = change.Status == "pending";
        CanReview = IsPending || change.Status == "applied";
        StateText = change.Status switch
        {
            "applied" => "已落盘",
            "rejected" => "已拒绝",
            "rolled_back" => "已回滚",
            "failed" => "落盘失败",
            _ => "待审核",
        };
        StateBrush = change.Status switch
        {
            "applied" => new SolidColorBrush(Color.Parse("#3DF5A8")),
            "failed" => new SolidColorBrush(Color.Parse("#FF7A6E")),
            _ => new SolidColorBrush(Color.Parse("#E8B25E")),
        };
        ActionText = change.Status == "applied" ? "回滚" : "批准";
        if (Lines.Count == 0)
        {
            foreach (var line in BuildLines(change.Diff))
            {
                Lines.Add(line);
            }
        }
    }

    private static ObservableCollection<DiffLineRow> BuildLines(string? diff)
    {
        var lines = new ObservableCollection<DiffLineRow>();
        foreach (var line in (diff ?? "").Split('\n').Take(240))
        {
            lines.Add(new DiffLineRow(line.TrimEnd('\r')));
        }

        return lines;
    }
}

/// <summary>diff 的单行，颜色按增/删/块头区分。</summary>
public sealed class DiffLineRow
{
    public DiffLineRow(string text)
    {
        Text = text;
        var kind = SoloRules.DiffKind(text);
        Foreground = kind switch
        {
            "ins" => new SolidColorBrush(Color.Parse("#8CE8BE")),
            "del" => new SolidColorBrush(Color.Parse("#FFA79E")),
            "hunk" => new SolidColorBrush(Color.Parse("#7FB6E0")),
            _ => new SolidColorBrush(Color.Parse("#A8B0AE")),
        };
        Background = kind switch
        {
            "ins" => new SolidColorBrush(Color.Parse("#16302A")),
            "del" => new SolidColorBrush(Color.Parse("#33201E")),
            _ => Brushes.Transparent,
        };
    }

    public string Text { get; }

    public IBrush Foreground { get; }

    public IBrush Background { get; }
}

/// <summary>Agent 下拉项；Id 为 null 表示内置 DSH。</summary>
public sealed record AgentChoice(string? Id, string Label);

/// <summary>模型下拉项；KeyId 为空表示使用原生模型名。</summary>
public sealed record ModelChoice(string? KeyId, string? Model, string Label);
