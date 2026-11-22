using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using Flux.Desktop.Models;

namespace Flux.Desktop.Services;

public enum StreamConnectionState
{
    Idle,
    Connecting,
    Live,
    Reconnecting,
    Failed,
}

public enum StreamApplyKind
{
    Ignored,
    Committed,
}

/// <summary>`Apply` 的结果：需要并入消息列表的持久化消息，以及本轮是否已进入终态。</summary>
public readonly record struct StreamApplyResult(
    StreamApplyKind Kind,
    TaskMessage? Message = null,
    bool Settled = false);

/// <summary>执行过程里的一个工具调用步骤。</summary>
public sealed record StreamToolStep(
    string CallId,
    string Name,
    string Detail,
    string Output,
    string Status,
    string Category,
    string[] Paths,
    bool IsRunning);

/// <summary>执行过程里的一行错误。</summary>
public sealed record StreamErrorLine(long Id, string Text);

/// <summary>执行过程里的思考块。</summary>
public sealed record StreamReasoningInfo(
    string Text,
    bool Active,
    DateTimeOffset? StartedAt,
    DateTimeOffset? EndedAt);

/// <summary>
/// 任务 SSE 的纯状态归约器（与 Web / 手机端同一语义）。
/// 只在 UI 线程调用；不持有任何 Avalonia 类型。
/// </summary>
public sealed class TaskStreamReducer
{
    private const int LiveTextLimit = 20_000;
    private const string TrimmedMark = "…（前文已折叠）\n\n";

    private readonly List<StreamToolStep> _tools = [];
    private readonly List<StreamErrorLine> _errors = [];
    private long _errorSeq;
    private long _lastSeq;
    private int _revision;

    public StreamConnectionState Connection { get; private set; } = StreamConnectionState.Idle;

    public string LiveText { get; private set; } = "";

    public string LiveLabel { get; private set; } = "";

    public bool LiveActive { get; private set; }

    public DateTimeOffset? StartedAt { get; private set; }

    public DateTimeOffset? EndedAt { get; private set; }

    public StreamReasoningInfo? Reasoning { get; private set; }

    public IReadOnlyList<StreamToolStep> Tools => _tools;

    public IReadOnlyList<StreamErrorLine> Errors => _errors;

    public string TerminalError { get; private set; } = "";

    public int RetryAttempt { get; private set; }

    public string RunId { get; private set; } = "";

    public int Revision => _revision;

    public long LastSeq => _lastSeq;

    public bool HasActivity =>
        LiveActive
        || LiveText.Length > 0
        || Reasoning is not null
        || _tools.Count > 0
        || _errors.Count > 0
        || TerminalError.Length > 0;

    public StreamApplyResult Apply(TaskStreamEvent evt)
    {
        if (evt.Seq > 0)
        {
            if (evt.Seq <= _lastSeq)
            {
                return new StreamApplyResult(StreamApplyKind.Ignored);
            }
            _lastSeq = evt.Seq;
        }

        var data = evt.Data;
        switch (evt.Type)
        {
            case "user_message":
                ResetLive();
                return new StreamApplyResult(
                    Commit(),
                    TaskStreamJson.ReadMessage(data));

            case "assistant_start":
                _errorSeq = 0;
                StartLive("正在输入…");
                Reasoning = null;
                _tools.Clear();
                _errors.Clear();
                TerminalError = "";
                return new StreamApplyResult(Commit());

            case "assistant_delta":
                LiveActive = true;
                LiveLabel = "正在输入…";
                AppendText(TaskStreamJson.ReadString(data, "text"));
                return new StreamApplyResult(Commit());

            case "assistant_message":
                var assistantMessage = TaskStreamJson.ReadMessage(data);
                LiveText = "";
                LiveActive = false;
                LiveLabel = "已完成";
                EndedAt ??= DateTimeOffset.UtcNow;
                FinishReasoning();
                FinishTools(failed: false);
                return new StreamApplyResult(Commit(), assistantMessage);

            case "assistant_error":
                var error = TaskStreamJson.ReadString(data, "error");
                TerminalError = error.Length > 0 ? error : "执行失败";
                LiveActive = false;
                LiveLabel = "已中断";
                EndedAt ??= DateTimeOffset.UtcNow;
                FinishReasoning();
                FinishTools(failed: true);
                return new StreamApplyResult(Commit());

            case "run_started":
                _errorSeq = 0;
                var runtime = TaskStreamJson.ReadString(data, "runtime");
                StartLive(runtime.Length > 0 ? $"{runtime} 执行中" : "Agent 执行中");
                Reasoning = null;
                _tools.Clear();
                _errors.Clear();
                TerminalError = "";
                RunId = TaskStreamJson.ReadString(data, "run_id");
                return new StreamApplyResult(Commit());

            case "run_event":
                ApplyRunEvent(data);
                return new StreamApplyResult(Commit());

            case "run_status":
                var status = TaskStreamJson.ReadString(data, "status");
                var failedStatus = status is "failed" or "error" or "cancelled" or "canceled";
                LiveActive = false;
                LiveLabel = failedStatus ? "已结束（失败）" : "已完成";
                EndedAt ??= DateTimeOffset.UtcNow;
                FinishReasoning();
                FinishTools(failedStatus);
                return new StreamApplyResult(Commit(), Settled: true);

            case "run_finished":
                var finishedMessage = TaskStreamJson.ReadMessage(data);
                // 终态由落库消息 + 轨迹卡接管，清掉实时执行过程，避免重复展示。
                LiveText = "";
                LiveActive = false;
                LiveLabel = "已完成";
                Reasoning = null;
                _tools.Clear();
                _errors.Clear();
                TerminalError = "";
                EndedAt ??= DateTimeOffset.UtcNow;
                RunId = TaskStreamJson.ReadString(data, "run_id") is { Length: > 0 } runId
                    ? runId
                    : RunId;
                return new StreamApplyResult(Commit(), finishedMessage, Settled: true);

            case "run_retry":
                var retry = TaskStreamJson.ReadString(data, "message");
                LiveLabel = retry.Length > 0 ? retry : "上游限流，正在重试…";
                return new StreamApplyResult(Commit());

            default:
                return new StreamApplyResult(StreamApplyKind.Ignored);
        }
    }

    private void ApplyRunEvent(JsonElement data)
    {
        var kind = TaskStreamJson.ReadString(data, "kind");
        var text = TaskStreamJson.ReadString(data, "text");
        switch (kind)
        {
            case "thinking":
                if (text.Length == 0)
                {
                    return;
                }
                if (Reasoning is null)
                {
                    Reasoning = new StreamReasoningInfo(text, true, DateTimeOffset.UtcNow, null);
                }
                else
                {
                    Reasoning = Reasoning with
                    {
                        Text = AppendLimited(Reasoning.Text, text),
                        Active = true,
                        StartedAt = Reasoning.StartedAt ?? DateTimeOffset.UtcNow,
                    };
                }
                LiveLabel = "正在思考…";
                LiveActive = true;
                return;

            case "text":
                if (text.Length == 0)
                {
                    return;
                }
                LiveText = AppendLimited(LiveText, text);
                LiveLabel = "正在输出…";
                LiveActive = true;
                return;

            case "tool":
                UpsertTool(data);
                return;

            case "error":
                var message = text.Length > 0
                    ? text
                    : TaskStreamJson.ReadString(data, "detail");
                if (message.Length == 0)
                {
                    message = "执行出错";
                }
                _errorSeq += 1;
                _errors.Add(new StreamErrorLine(_errorSeq, message));
                return;

            case "status":
                if (text.Length > 0)
                {
                    LiveLabel = text;
                }
                return;
        }
    }

    private void UpsertTool(JsonElement data)
    {
        var name = TaskStreamJson.ReadString(data, "name");
        if (name.Length == 0)
        {
            name = "tool";
        }
        var callId = TaskStreamJson.ReadString(data, "call_id");
        if (callId.Length == 0)
        {
            callId = $"{name}-{_tools.Count}";
        }
        var detail = TaskStreamJson.ReadString(data, "detail");
        var output = TaskStreamJson.ReadString(data, "output");
        var category = TaskStreamJson.ReadString(data, "category");
        var status = TaskStreamJson.ReadString(data, "status");
        var paths = TaskStreamJson.ReadStringArray(data, "paths");

        var index = _tools.FindIndex(tool => tool.CallId == callId);
        if (index >= 0)
        {
            var previous = _tools[index];
            _tools[index] = previous with
            {
                Name = name.Length > 0 ? name : previous.Name,
                Detail = detail.Length > 0 ? detail : previous.Detail,
                Output = output.Length > 0 ? output : previous.Output,
                Status = status.Length > 0 ? status : previous.Status,
                Category = category.Length > 0 ? category : previous.Category,
                Paths = paths.Length > 0
                    ? previous.Paths.Union(paths).ToArray()
                    : previous.Paths,
                IsRunning = previous.IsRunning && NormalizeToolStatus(status) == "running",
            };
            if (status.Length > 0)
            {
                _tools[index] = _tools[index] with
                {
                    IsRunning = NormalizeToolStatus(status) == "running",
                };
            }
            return;
        }

        _tools.Add(new StreamToolStep(
            callId,
            name,
            detail,
            output,
            status,
            category,
            paths,
            NormalizeToolStatus(status) == "running"));
    }

    public static string NormalizeToolStatus(string raw)
    {
        var value = raw.Trim().ToLowerInvariant();
        if (value is "running" or "started" or "in_progress" or "pending" or "queued")
        {
            return "running";
        }
        if (value is "completed" or "complete" or "success" or "succeeded" or "done" or "ok" or "finished")
        {
            return "done";
        }
        if (value is "failed" or "error" or "cancelled" or "canceled" or "timeout")
        {
            return "failed";
        }
        return value.Length == 0 ? "running" : "done";
    }

    public void OnConnecting()
    {
        Connection = StreamConnectionState.Connecting;
        Commit();
    }

    public void OnOpen()
    {
        Connection = StreamConnectionState.Live;
        RetryAttempt = 0;
        Commit();
    }

    public void OnDisconnected(int attempt)
    {
        Connection = StreamConnectionState.Reconnecting;
        RetryAttempt = attempt;
        LiveActive = false;
        if (LiveText.Length == 0 && StartedAt is null)
        {
            LiveLabel = "连接中断，正在重连…";
        }
        else
        {
            FinishReasoning();
            FinishTools(failed: false);
        }
        Commit();
    }

    public void OnTerminal(string error)
    {
        Connection = StreamConnectionState.Failed;
        TerminalError = error.Length > 0 ? error : "实时连接已中断";
        LiveActive = false;
        EndedAt ??= DateTimeOffset.UtcNow;
        FinishReasoning();
        FinishTools(failed: true);
        Commit();
    }

    public void MarkCancelled()
    {
        LiveActive = false;
        LiveLabel = "已停止";
        EndedAt ??= DateTimeOffset.UtcNow;
        FinishReasoning();
        FinishTools(failed: true);
        Commit();
    }

    public void Reset()
    {
        LiveText = "";
        LiveLabel = "";
        LiveActive = false;
        StartedAt = null;
        EndedAt = null;
        Reasoning = null;
        _tools.Clear();
        _errors.Clear();
        TerminalError = "";
        RetryAttempt = 0;
        RunId = "";
        Connection = StreamConnectionState.Idle;
        Commit();
    }

    /// <summary>重连成功后清掉上一轮已落库的 live 内容，但保留 lastSeq。</summary>
    public void ClearLiveAfterSettled()
    {
        if (LiveText.Length == 0)
        {
            return;
        }
        LiveText = "";
        Commit();
    }

    private void StartLive(string label)
    {
        Connection = Connection == StreamConnectionState.Idle
            ? StreamConnectionState.Live
            : Connection;
        LiveActive = true;
        LiveLabel = label;
        LiveText = "";
        StartedAt = DateTimeOffset.UtcNow;
        EndedAt = null;
    }

    private void ResetLive()
    {
        LiveText = "";
        LiveLabel = "";
        LiveActive = false;
        StartedAt = null;
        EndedAt = null;
        Reasoning = null;
        _tools.Clear();
        _errors.Clear();
        TerminalError = "";
        _errorSeq = 0;
        RunId = "";
    }

    private void FinishReasoning()
    {
        if (Reasoning is { Active: true } reasoning)
        {
            Reasoning = reasoning with { Active = false, EndedAt = DateTimeOffset.UtcNow };
        }
    }

    private void FinishTools(bool failed)
    {
        for (var i = 0; i < _tools.Count; i++)
        {
            if (!_tools[i].IsRunning)
            {
                continue;
            }
            _tools[i] = _tools[i] with
            {
                IsRunning = false,
                Status = failed ? "failed" : "done",
            };
        }
    }

    private void AppendText(string addition)
    {
        if (addition.Length == 0)
        {
            return;
        }
        LiveText = AppendLimited(LiveText, addition);
    }

    private static string AppendLimited(string current, string addition)
    {
        var next = current + addition;
        if (next.Length <= LiveTextLimit)
        {
            return next;
        }
        var keep = next.Length - LiveTextLimit;
        return TrimmedMark + next[keep..];
    }

    private StreamApplyKind Commit()
    {
        _revision++;
        return StreamApplyKind.Committed;
    }
}
