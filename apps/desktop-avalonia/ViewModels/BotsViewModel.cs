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

/// <summary>权限模式下拉项（与 web 端 PERMISSION_OPTIONS 同序）。</summary>
public sealed record PermissionOption(string Value, string Label);

/// <summary>Bot 编辑弹窗里的 Agent 下拉项；Id 为空表示平台默认 Agent。</summary>
public sealed record BotAgentOption(string Id, string Label);

/// <summary>Bot 卡片（web 端 BotCard）。</summary>
public sealed record BotCardView(
    string Id,
    string Name,
    string Description,
    string AvatarShape,
    string AvatarColor,
    string StatusLabel,
    IBrush StatusTone,
    string ScheduleText,
    string WorkspaceText,
    string LastRunText,
    string ToggleLabel,
    string DeleteLabel,
    ICommand Run,
    ICommand Toggle,
    ICommand Edit,
    ICommand OpenRuns,
    ICommand Delete);

/// <summary>开工记录行（web 端 RunsModal 的一项）。</summary>
public sealed record BotRunView(
    string StatusLabel,
    IBrush Tone,
    string Description,
    string TimeText,
    string ResultText,
    bool HasResult);

/// <summary>
/// Bot 模式（web 端 BotsPage）：常驻执行者的列表、新建/编辑、手动开工、暂停恢复与开工记录。
/// 定时由后端调度器负责，这里只展示 next_run_at 并允许手动触发。
/// </summary>
public sealed partial class BotsViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Success = new SolidColorBrush(Color.Parse("#2ECC8F"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<FluxApiClient?> _api;

    private List<BotModel> _bots = [];
    private string? _editingId;
    private string? _deleteArmedId = null;

    public BotsViewModel(Func<FluxApiClient?> api)
    {
        _api = api;

        PermissionOptions.Add(new PermissionOption("unattended", "免审核（无人值守）"));
        PermissionOptions.Add(new PermissionOption("normal", "正常（人工把关）"));
        PermissionOptions.Add(new PermissionOption("plan", "只读计划"));
        PermissionOptions.Add(new PermissionOption("full", "完全放行"));

        AgentOptions.Add(new BotAgentOption("", "平台默认 Agent"));

        EditorPermission = PermissionOptions[0];
        EditorAgent = AgentOptions[0];
    }

    public ObservableCollection<BotCardView> Bots { get; } = new();

    public ObservableCollection<PermissionOption> PermissionOptions { get; } = new();

    public ObservableCollection<BotAgentOption> AgentOptions { get; } = new();

    public ObservableCollection<BotRunView> Runs { get; } = new();

    /// <summary>头像形状 / 颜色候选，与 web、安卓、后端同一份取值。</summary>
    public IReadOnlyList<string> Shapes => BotAvatarPalette.Shapes;

    public IReadOnlyList<string> Colors => BotAvatarPalette.Colors;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAll))]
    [NotifyPropertyChangedFor(nameof(IsActiveFilter))]
    [NotifyPropertyChangedFor(nameof(IsPausedFilter))]
    private string _filter = "all";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoBots))]
    private bool _hasBots;

    [ObservableProperty]
    private string _countText = "共 0 个 Bot";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNotice))]
    private string _notice = "";

    [ObservableProperty]
    private IBrush _noticeTone = Brand;

    [ObservableProperty]
    private bool _isEditorOpen;

    [ObservableProperty]
    private string _editorTitle = "新建 Bot";

    [ObservableProperty]
    private string _editorName = "";

    [ObservableProperty]
    private string _editorDescription = "";

    [ObservableProperty]
    private string _editorSchedule = "";

    [ObservableProperty]
    private string _editorWorkspace = "";

    [ObservableProperty]
    private PermissionOption? _editorPermission;

    [ObservableProperty]
    private BotAgentOption? _editorAgent;

    [ObservableProperty]
    private string _editorShape = BotAvatarPalette.DefaultShape;

    [ObservableProperty]
    private string _editorColor = BotAvatarPalette.DefaultColor;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasEditorError))]
    private string _editorError = "";

    [ObservableProperty]
    private bool _isSaving;

    [ObservableProperty]
    private bool _isRunsOpen;

    [ObservableProperty]
    private string _runsTitle = "开工记录";

    [ObservableProperty]
    private bool _isRunsBusy;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasNoRuns))]
    private bool _hasRuns;

    public bool HasNoBots => !HasBots;

    public bool HasNotice => !string.IsNullOrWhiteSpace(Notice);

    public bool HasEditorError => !string.IsNullOrWhiteSpace(EditorError);

    public bool HasNoRuns => !HasRuns;

    public bool IsAll => Filter == "all";

    public bool IsActiveFilter => Filter == "active";

    public bool IsPausedFilter => Filter == "paused";

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
            var bots = await api.ListBotsAsync().ConfigureAwait(true);
            var agents = await api.ListAgentsAsync().ConfigureAwait(true);

            _bots = bots?.Data ?? [];

            AgentOptions.Clear();
            AgentOptions.Add(new BotAgentOption("", "平台默认 Agent"));
            foreach (var agent in agents?.Data ?? [])
            {
                AgentOptions.Add(new BotAgentOption(agent.Id, agent.Spec?.Name ?? agent.Id));
            }

            RebuildCards();
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    [RelayCommand]
    private void SelectFilter(string? filter)
    {
        Filter = filter ?? "all";
        RebuildCards();
    }

    [RelayCommand]
    private void NewBot()
    {
        _editingId = null;
        _deleteArmedId = null;
        EditorTitle = "新建 Bot";
        EditorName = "";
        EditorDescription = "";
        EditorSchedule = "";
        EditorWorkspace = "";
        EditorPermission = PermissionOptions[0];
        EditorAgent = AgentOptions.Count > 0 ? AgentOptions[0] : null;
        EditorShape = Shapes[Random.Shared.Next(Shapes.Count)];
        EditorColor = Colors[Random.Shared.Next(Colors.Count)];
        EditorError = "";
        IsEditorOpen = true;
    }

    [RelayCommand]
    private void CloseEditor() => IsEditorOpen = false;

    [RelayCommand]
    private void CloseRuns() => IsRunsOpen = false;

    /// <summary>保存（新建或编辑）；失败时把后端 message 直接展示在弹窗里。</summary>
    [RelayCommand]
    private async Task Save()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        var name = EditorName.Trim();
        if (name.Length == 0)
        {
            EditorError = "名称不能为空";
            return;
        }

        var agentId = string.IsNullOrWhiteSpace(EditorAgent?.Id) ? null : EditorAgent!.Id;
        var payload = new
        {
            name,
            description = EditorDescription,
            avatar_shape = EditorShape,
            avatar_color = EditorColor,
            agent_id = agentId,
            permission_mode = EditorPermission?.Value ?? "unattended",
            workspace = string.IsNullOrWhiteSpace(EditorWorkspace) ? null : EditorWorkspace.Trim(),
            schedule = string.IsNullOrWhiteSpace(EditorSchedule) ? null : EditorSchedule.Trim(),
        };

        IsSaving = true;
        EditorError = "";
        try
        {
            var res = _editingId is null
                ? await api.CreateBotAsync(payload).ConfigureAwait(true)
                : await api.UpdateBotAsync(_editingId, payload).ConfigureAwait(true);

            if (res is { Success: true, Data: { } saved })
            {
                IsEditorOpen = false;
                SetNotice($"已保存「{saved.Name}」", Brand);
                await RefreshAsync().ConfigureAwait(true);
            }
            else
            {
                EditorError = res?.Message ?? "保存失败：后端未响应 /bots";
            }
        }
        finally
        {
            IsSaving = false;
        }
    }

    [RelayCommand]
    private async Task RunNow(BotCardView? card)
    {
        var api = _api();
        var bot = Find(card);
        if (api is null || bot is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var res = await api.RunBotAsync(bot.Id).ConfigureAwait(true);
            if (res is { Success: true, Data: { } outcome })
            {
                SetNotice($"「{outcome.Bot.Name}」已开工", Brand);
                await RefreshAsync().ConfigureAwait(true);
            }
            else
            {
                SetNotice(res?.Message ?? "开工失败：后端未响应 /bots/{id}/run", Danger);
            }
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task ToggleStatus(BotCardView? card)
    {
        var api = _api();
        var bot = Find(card);
        if (api is null || bot is null)
        {
            return;
        }

        var next = bot.IsActive ? "paused" : "active";
        var res = await api.UpdateBotAsync(bot.Id, new { status = next }).ConfigureAwait(true);
        if (res is { Success: true })
        {
            SetNotice(next == "paused" ? $"已暂停「{bot.Name}」" : $"已恢复「{bot.Name}」", Brand);
            await RefreshAsync().ConfigureAwait(true);
        }
        else
        {
            SetNotice(res?.Message ?? "状态切换失败：后端未响应 /bots/{id}", Danger);
        }
    }

    [RelayCommand]
    private void EditBot(BotCardView? card)
    {
        var bot = Find(card);
        if (bot is null)
        {
            return;
        }

        _editingId = bot.Id;
        _deleteArmedId = null;
        EditorTitle = "编辑 Bot";
        EditorName = bot.Name;
        EditorDescription = bot.Description;
        EditorSchedule = bot.Schedule ?? "";
        EditorWorkspace = bot.Workspace ?? "";
        EditorPermission = PermissionOptions.FirstOrDefault(p => p.Value == bot.PermissionMode)
            ?? PermissionOptions[0];
        EditorAgent = AgentOptions.FirstOrDefault(a => a.Id == (bot.AgentId ?? "")) ?? AgentOptions[0];
        EditorShape = BotAvatarPalette.NormalizeShape(bot.AvatarShape);
        EditorColor = BotAvatarPalette.NormalizeColor(bot.AvatarColor);
        EditorError = "";
        IsEditorOpen = true;
    }

    /// <summary>删除需点两次：第一次只把按钮切成「确认删除」，避免误删配置。</summary>
    [RelayCommand]
    private async Task DeleteBot(BotCardView? card)
    {
        var api = _api();
        var bot = Find(card);
        if (api is null || bot is null)
        {
            return;
        }

        if (_deleteArmedId != bot.Id)
        {
            _deleteArmedId = bot.Id;
            RebuildCards();
            SetNotice($"再点一次「确认删除」才会移除「{bot.Name}」（工作目录文件不受影响）", Orange);
            return;
        }

        _deleteArmedId = null;
        var res = await api.DeleteBotAsync(bot.Id).ConfigureAwait(true);
        if (res is { Success: true })
        {
            SetNotice($"已删除「{bot.Name}」", Brand);
            await RefreshAsync().ConfigureAwait(true);
            return;
        }

        RebuildCards();
        SetNotice(res?.Message ?? "删除失败：后端未响应 /bots/{id}", Danger);
    }

    [RelayCommand]
    private async Task OpenRuns(BotCardView? card)
    {
        var api = _api();
        var bot = Find(card);
        if (api is null || bot is null)
        {
            return;
        }

        RunsTitle = $"开工记录 · {bot.Name}";
        Runs.Clear();
        HasRuns = false;
        IsRunsOpen = true;
        IsRunsBusy = true;
        try
        {
            var res = await api.ListBotTasksAsync(bot.Id, 50).ConfigureAwait(true);
            foreach (var run in res?.Data ?? [])
            {
                var (label, tone) = StatusMeta(run.Status);
                Runs.Add(new BotRunView(
                    label,
                    tone,
                    FirstLine(run.Description),
                    Stamp(run.CreatedAt),
                    Shorten(run.Result, 240),
                    !string.IsNullOrWhiteSpace(run.Result)));
            }

            HasRuns = Runs.Count > 0;
        }
        finally
        {
            IsRunsBusy = false;
        }
    }

    private BotModel? Find(BotCardView? card)
        => card is null ? null : _bots.FirstOrDefault(b => b.Id == card.Id);

    private void RebuildCards()
    {
        var shown = Filter switch
        {
            "active" => _bots.Where(b => b.IsActive).ToList(),
            "paused" => _bots.Where(b => !b.IsActive).ToList(),
            _ => _bots,
        };

        CountText = $"共 {shown.Count} 个 Bot";

        Bots.Clear();
        foreach (var bot in shown)
        {
            Bots.Add(BuildCard(bot));
        }

        HasBots = Bots.Count > 0;
    }

    private BotCardView BuildCard(BotModel bot)
    {
        var active = bot.IsActive;
        var armed = _deleteArmedId == bot.Id;
        var schedule = string.IsNullOrWhiteSpace(bot.Schedule)
            ? "仅手动触发"
            : $"{bot.Schedule} · 下次 {Stamp(bot.NextRunAt)}";

        return new BotCardView(
            bot.Id,
            bot.Name,
            string.IsNullOrWhiteSpace(bot.Description) ? "（未填写职责）" : bot.Description,
            bot.AvatarShape,
            bot.AvatarColor,
            active ? "运行中" : "已暂停",
            active ? Success : Orange,
            schedule,
            bot.WorkspacePath,
            bot.LastRunAt is null ? "尚未开工" : $"上次开工 {Stamp(bot.LastRunAt)}",
            active ? "暂停" : "恢复",
            armed ? "确认删除" : "删除",
            new RelayCommand(() => _ = RunNow(BuildCard(bot))),
            new RelayCommand(() => _ = ToggleStatus(BuildCard(bot))),
            new RelayCommand(() => EditBot(BuildCard(bot))),
            new RelayCommand(() => _ = OpenRuns(BuildCard(bot))),
            new RelayCommand(() => _ = DeleteBot(BuildCard(bot))));
    }

    private void SetNotice(string text, IBrush tone)
    {
        NoticeTone = tone;
        Notice = text;
    }

    private static (string Label, IBrush Tone) StatusMeta(string? status) => status switch
    {
        "pending" => ("排队中", Info),
        "running" => ("执行中", Brand),
        "waiting_for_user_decision" => ("待决策", Orange),
        "completed" => ("已完成", Success),
        "failed" => ("失败", Danger),
        "cancelled" => ("已取消", Muted),
        _ => (status ?? "未知", Muted),
    };

    private static string Stamp(string? iso) =>
        DateTimeOffset.TryParse(iso, out var moment)
            ? moment.LocalDateTime.ToString("M/d HH:mm")
            : "—";

    private static string FirstLine(string? text)
    {
        var value = (text ?? "").Trim();
        if (value.Length == 0)
        {
            return "（无描述）";
        }

        var line = value.Split('\n')[0].Trim();
        return line.Length > 120 ? line[..120] + "…" : line;
    }

    private static string Shorten(string? text, int max)
    {
        var value = (text ?? "").Trim();
        return value.Length > max ? value[..max] + "…" : value;
    }
}
