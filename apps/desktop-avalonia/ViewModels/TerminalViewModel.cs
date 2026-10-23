using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// Agent Terminal：会话列表 + 事件流 + 命令输入。
/// 事件按 after_seq 增量轮询，输出只追加不重绘；停止分优雅与强杀两档。
/// </summary>
public partial class TerminalViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Orange = new SolidColorBrush(Color.Parse("#E8B25E"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));
    private static readonly IBrush Text2 = new SolidColorBrush(Color.Parse("#B9C1BE"));

    private readonly Func<FluxApiClient?> _api;
    private readonly DispatcherTimer _timer;
    private long _afterSeq;
    private bool _polling;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private TerminalSessionInfo? _selectedSession;

    [ObservableProperty]
    private string _statusText = "未连接会话";

    [ObservableProperty]
    private IBrush _statusBrush = Muted;

    [ObservableProperty]
    private string _commandInput = "";

    [ObservableProperty]
    private string _hint = "新建一个终端会话，或在会话里执行命令。";

    [ObservableProperty]
    private bool _hasSession;

    [ObservableProperty]
    private bool _canStop;

    [ObservableProperty]
    private bool _hasEvents;

    public TerminalViewModel()
        : this(() => null)
    {
    }

    public TerminalViewModel(Func<FluxApiClient?> api)
    {
        _api = api;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += async (_, _) => await PollAsync(false);
        _timer.Start();
    }

    public ObservableCollection<TerminalSessionInfo> Sessions { get; } = new();

    public ObservableCollection<TerminalEventRow> Events { get; } = new();

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            Hint = "后端尚未就绪。";
            AppLog.Warn("Terminal 刷新跳过：后端未就绪");
            return;
        }

        IsBusy = true;
        try
        {
            var sessions = (await api.ListTerminalSessionsAsync())?.Data ?? new List<TerminalSessionInfo>();
            var currentId = SelectedSession?.Id;
            Sessions.Clear();
            foreach (var session in sessions)
            {
                Sessions.Add(session);
            }

            var target = sessions.FirstOrDefault(s => s.Id == currentId)
                ?? sessions.FirstOrDefault(s => s.Status == "active")
                ?? sessions.FirstOrDefault();
            SetSelected(target);
            AppLog.Info($"Terminal 刷新 · {sessions.Count} 个会话");
        }
        catch (Exception ex)
        {
            Hint = $"加载失败：{ex.Message}";
            AppLog.Error("Terminal 刷新失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task NewSessionAsync()
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var session = (await api.CreateTerminalSessionAsync(null))?.Data;
            if (session is null)
            {
                throw new InvalidOperationException("创建会话没有返回数据");
            }

            Sessions.Insert(0, session);
            SetSelected(session);
            Hint = $"已创建会话 · {SoloRules.ShortId(session.Id)}";
            AppLog.Info($"Terminal 新会话 · {session.Id}");
        }
        catch (Exception ex)
        {
            Hint = $"创建失败：{ex.Message}";
            AppLog.Error("Terminal 创建会话失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task SendCommandAsync()
    {
        var api = _api();
        var session = SelectedSession;
        var command = CommandInput.Trim();
        if (api is null || session is null || command.Length == 0)
        {
            return;
        }

        try
        {
            // POST 只返回这条命令最后落定的事件（seq 已越过 started/output），
            // 不能拿它当轮询游标，否则中间的输出事件会被永久跳过。统一按游标增量拉取。
            await api.SendTerminalCommandAsync(session.Id, command);
            CommandInput = "";
            Hint = $"已发送 · {command}";
            AppLog.Info($"Terminal 命令 · {session.Id} · {command}");
            await PollAsync(true);
        }
        catch (Exception ex)
        {
            Hint = $"发送失败：{ex.Message}";
            AppLog.Error($"Terminal 命令失败 · {session.Id}", ex);
        }
    }

    [RelayCommand]
    private async Task StopAsync() => await StopCoreAsync(false);

    [RelayCommand]
    private async Task ForceStopAsync() => await StopCoreAsync(true);

    private async Task StopCoreAsync(bool force)
    {
        var api = _api();
        var session = SelectedSession;
        if (api is null || session is null)
        {
            return;
        }

        try
        {
            var updated = (await api.StopTerminalSessionAsync(session.Id, force))?.Data;
            if (updated is not null)
            {
                ReplaceSession(updated);
                SelectedSession = updated;
                ApplySession(updated);
            }

            Hint = force ? "已强制停止会话" : "已优雅停止会话";
            AppLog.Info($"Terminal 停止 · {session.Id} · force={force}");
        }
        catch (Exception ex)
        {
            Hint = $"停止失败：{ex.Message}";
            AppLog.Error($"Terminal 停止失败 · {session.Id}", ex);
        }
    }

    private async Task PollAsync(bool force)
    {
        if (_polling)
        {
            return;
        }

        var api = _api();
        var session = SelectedSession;
        if (api is null || session is null)
        {
            return;
        }

        if (!force && session.Status != "active")
        {
            return;
        }

        _polling = true;
        try
        {
            var events = (await api.ListTerminalEventsAsync(session.Id, _afterSeq))?.Data
                ?? new List<TerminalEventInfo>();
            foreach (var evt in events)
            {
                Append(evt);
            }

            if (events.Any(IsClosingEvent))
            {
                await RefreshSessionAsync(api, session.Id);
            }
        }
        catch (Exception ex)
        {
            AppLog.Warn($"Terminal 轮询失败 · {session.Id} · {ex.Message}");
        }
        finally
        {
            _polling = false;
        }
    }

    private async Task RefreshSessionAsync(FluxApiClient api, string sessionId)
    {
        var sessions = (await api.ListTerminalSessionsAsync())?.Data
            ?? new List<TerminalSessionInfo>();
        var currentId = SelectedSession?.Id;
        Sessions.Clear();
        foreach (var session in sessions)
        {
            Sessions.Add(session);
        }

        var target = sessions.FirstOrDefault(s => s.Id == currentId);
        if (target is not null)
        {
            SelectedSession = target;
            ApplySession(target);
        }
    }

    private void SetSelected(TerminalSessionInfo? session)
    {
        SelectedSession = session;
        ApplySession(session);
        Events.Clear();
        _afterSeq = 0;
        HasEvents = false;
        if (session is not null)
        {
            _ = PollAsync(true);
        }
    }

    private void ApplySession(TerminalSessionInfo? session)
    {
        HasSession = session is not null;
        CanStop = session?.Status == "active";
        StatusText = session?.Status switch
        {
            "active" => "运行中",
            "stopped" => "已停止",
            "closed" => "已关闭",
            _ => "未连接会话",
        };
        StatusBrush = session?.Status == "active" ? Brand : Muted;
        if (session is not null)
        {
            Hint = $"会话 {SoloRules.ShortId(session.Id)} · {session.WorkspaceRoot}";
        }
    }

    private void ReplaceSession(TerminalSessionInfo updated)
    {
        var index = Sessions.ToList().FindIndex(s => s.Id == updated.Id);
        if (index >= 0)
        {
            Sessions[index] = updated;
        }
    }

    private void Append(TerminalEventInfo evt)
    {
        if (evt.Seq <= _afterSeq)
        {
            return;
        }

        _afterSeq = evt.Seq;
        Events.Add(new TerminalEventRow(evt, Brand, Danger, Info, Orange, Muted, Text2));
        HasEvents = true;
    }

    private static bool IsClosingEvent(TerminalEventInfo evt)
        => evt.Kind is "terminal.session.closed" or "terminal.process.terminated";
}

/// <summary>终端事件行：命令、输出、退出码按语义着色。</summary>
public sealed class TerminalEventRow
{
    public TerminalEventRow(
        TerminalEventInfo evt,
        IBrush brand,
        IBrush danger,
        IBrush info,
        IBrush warn,
        IBrush muted,
        IBrush text2)
    {
        SeqText = evt.Seq.ToString();
        switch (evt.Kind)
        {
            case "terminal.command.started":
                Text = $"$ {evt.Command}";
                Brush = info;
                break;
            case "terminal.output":
                Text = evt.Chunk ?? "";
                Brush = text2;
                break;
            case "terminal.command.finished":
                Text = $"✓ {evt.Command ?? "命令结束"} · exit {evt.ExitCode ?? 0}";
                Brush = brand;
                break;
            case "terminal.command.failed":
                Text = $"✕ {evt.Command ?? "命令失败"} · exit {evt.ExitCode ?? -1}";
                Brush = danger;
                break;
            case "terminal.stop.requested":
                Text = "已请求停止会话";
                Brush = warn;
                break;
            case "terminal.process.terminated":
                Text = "进程已终止";
                Brush = warn;
                break;
            case "terminal.session.created":
                Text = "会话已创建";
                Brush = muted;
                break;
            case "terminal.session.closed":
                Text = "会话已关闭";
                Brush = muted;
                break;
            default:
                Text = $"{evt.Kind} {evt.Command ?? evt.Chunk}";
                Brush = muted;
                break;
        }
    }

    public string SeqText { get; }

    public string Text { get; }

    public IBrush Brush { get; }
}
