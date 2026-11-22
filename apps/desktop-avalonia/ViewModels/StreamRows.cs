using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>执行过程里的一个工具调用行（实时与历史轨迹共用）。</summary>
public partial class StreamToolRow : ObservableObject
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Danger = new SolidColorBrush(Color.Parse("#FF7A6E"));
    private static readonly IBrush Info = new SolidColorBrush(Color.Parse("#7FB6E0"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    public StreamToolRow(string callId)
    {
        CallId = callId;
    }

    public string CallId { get; }

    [ObservableProperty]
    private string _name = "";

    [ObservableProperty]
    private string _detail = "";

    [ObservableProperty]
    private string _output = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Icon))]
    private string _category = "other";

    [ObservableProperty]
    private string _status = "running";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StatusGlyph))]
    private bool _isRunning = true;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StatusBrush))]
    private bool _isFailed;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowOutput))]
    private bool _hasOutput;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(PathsText))]
    [NotifyPropertyChangedFor(nameof(HasPaths))]
    private string[] _paths = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowOutput))]
    private bool _isExpanded;

    public bool ShowOutput => IsExpanded && HasOutput;

    public string PathsText => string.Join("   ", Paths);

    public bool HasPaths => Paths.Length > 0;

    public string StatusGlyph => IsRunning ? "…" : IsFailed ? "✕" : "✓";

    public IBrush StatusBrush => IsFailed ? Danger : IsRunning ? Info : Brand;

    public string Icon => Category switch
    {
        "file" or "files" or "edit" or "write" => "▤",
        "read" => "▤",
        "search" => "⌕",
        "command" or "shell" or "terminal" => "›_",
        "web" or "http" => "◍",
        "plan" or "todo" => "☰",
        "test" => "✓",
        _ => "·",
    };

    [RelayCommand]
    private void Toggle() => IsExpanded = !IsExpanded;

    public void Apply(StreamToolStep step)
    {
        Name = step.Name;
        Detail = step.Detail;
        Output = step.Output;
        Category = step.Category.Length > 0 ? step.Category : "other";
        Status = step.Status;
        IsRunning = step.IsRunning;
        IsFailed = TaskStreamReducer.NormalizeToolStatus(step.Status) == "failed";
        HasOutput = step.Output.Length > 0;
        if (!Paths.SequenceEqual(step.Paths))
        {
            Paths = step.Paths;
        }
    }
}

/// <summary>
/// 挂在 run_finished 消息下方的历史执行轨迹卡：折叠头 + 首次展开懒加载 trace。
/// </summary>
public partial class RunTraceCardModel : ObservableObject
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<Task<RunTrace?>> _load;
    private TaskRunSummary? _summary;
    private bool _summaryFromTrace;

    public RunTraceCardModel(string runId, TaskRunSummary? summary, Func<Task<RunTrace?>> load)
    {
        RunId = runId;
        _summary = summary;
        _load = load;
        Title = "执行轨迹";
        MetaText = BuildSummaryText(summary);
    }

    public string RunId { get; }

    public string Title { get; }

    public ObservableCollection<StreamToolRow> Tools { get; } = new();

    public ObservableCollection<string> Errors { get; } = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Caret))]
    private bool _isExpanded;

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private bool _isLoaded;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsEmpty))]
    private bool _hasSteps;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Caret))]
    private string _metaText = "";

    [ObservableProperty]
    private string _errorText = "";

    [ObservableProperty]
    private bool _hasError;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasReasoning))]
    private string _reasoningText = "";

    public string Caret => IsExpanded ? "▾" : "▸";

    public bool HasReasoning => ReasoningText.Length > 0;

    public bool IsEmpty => IsLoaded && !HasSteps;

    [RelayCommand]
    private async Task ToggleAsync()
    {
        if (IsExpanded)
        {
            IsExpanded = false;
            return;
        }

        IsExpanded = true;
        if (IsLoaded || IsLoading)
        {
            return;
        }

        IsLoading = true;
        ErrorText = "";
        HasError = false;
        try
        {
            var trace = await _load();
            if (trace is null)
            {
                ErrorText = "执行轨迹加载失败";
                HasError = true;
                return;
            }
            ApplyTrace(trace);
            IsLoaded = true;
        }
        catch (Exception ex)
        {
            ErrorText = ex.Message;
            HasError = true;
        }
        finally
        {
            IsLoading = false;
        }
    }

    public void ApplySummary(TaskRunSummary? summary)
    {
        if (summary is null || _summaryFromTrace)
        {
            return;
        }
        _summary = summary;
        MetaText = BuildSummaryText(summary);
    }

    private void ApplyTrace(RunTrace trace)
    {
        ReasoningText = string.Concat(
            (trace.Items ?? [])
                .Where(item => item.Kind == "thinking" && !string.IsNullOrEmpty(item.Text))
                .Select(item => item.Text));

        Tools.Clear();
        Errors.Clear();
        var index = 0;
        foreach (var item in trace.Items ?? [])
        {
            if (item.Kind == "tool")
            {
                var name = string.IsNullOrWhiteSpace(item.Name) ? "tool" : item.Name;
                var callId = string.IsNullOrWhiteSpace(item.CallId) ? $"{name}-{index}" : item.CallId;
                var row = new StreamToolRow(callId);
                row.Apply(new StreamToolStep(
                    callId,
                    name,
                    item.Detail ?? "",
                    item.Output ?? "",
                    item.Status ?? "done",
                    item.Category ?? "other",
                    item.Paths ?? [],
                    IsRunning: false));
                Tools.Add(row);
            }
            else if (item.Kind == "error")
            {
                Errors.Add(
                    !string.IsNullOrWhiteSpace(item.Text)
                        ? item.Text!
                        : !string.IsNullOrWhiteSpace(item.Detail)
                            ? item.Detail!
                            : "执行出错");
            }
            index += 1;
        }

        HasSteps = HasReasoning || Tools.Count > 0 || Errors.Count > 0;

        if (!_summaryFromTrace)
        {
            _summaryFromTrace = trace.DurationMs is not null;
        }
        MetaText = BuildSummaryText(
            _summary,
            trace.Commands,
            trace.FilesRead,
            trace.FilesChanged,
            trace.DurationMs);
    }

    private static string BuildSummaryText(
        TaskRunSummary? summary,
        int commands = 0,
        int filesRead = 0,
        int filesChanged = 0,
        double? durationMs = null)
    {
        commands = summary?.Commands ?? commands;
        filesRead = summary?.FilesRead ?? filesRead;
        filesChanged = summary?.FilesChanged ?? filesChanged;
        var duration = summary?.DurationMs ?? durationMs;

        var parts = new List<string>();
        if (commands > 0)
        {
            parts.Add($"{commands} 条命令");
        }
        if (filesChanged > 0)
        {
            parts.Add($"改动 {filesChanged} 文件");
        }
        if (filesRead > 0)
        {
            parts.Add($"读取 {filesRead} 文件");
        }
        if (duration is > 0)
        {
            parts.Add(FormatDuration(duration.Value));
        }
        return string.Join(" · ", parts);
    }

    private static string FormatDuration(double milliseconds)
    {
        var totalSeconds = Math.Max(0, (int)Math.Round(milliseconds / 1000.0));
        if (totalSeconds < 60)
        {
            return $"{Math.Max(1, totalSeconds)}s";
        }
        return $"{totalSeconds / 60}m {totalSeconds % 60:00}s";
    }
}
