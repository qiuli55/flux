using System.Text.Json;

namespace Flux.Desktop.Models;

/// <summary>任务确认卡的一个维度（PUT /tasks/{id}/confirmation 的 items 元素）。</summary>
public sealed record ConfirmationItem(string Label, string Value);

/// <summary>任务上的需求确认快照；actor 取 assistant / user。</summary>
public sealed record TaskConfirmation(ConfirmationItem[]? Items, string? Actor, string? UpdatedAt);

/// <summary>决策点的候选方案。</summary>
public sealed record DecisionOption(string Label, string? Description, string? Impact, bool Recommended);

/// <summary>决策点记录（历史与当前待决策项同形）。</summary>
public sealed record TaskDecision(
    string Id,
    string Question,
    string? Context,
    DecisionOption[]? Options,
    string? Recommendation,
    string? Mode,
    string? Status,
    string? Chosen,
    string? Note,
    string? RaisedAt,
    string? ResolvedAt);

/// <summary>GET /tasks/{id} 与各动作返回的完整任务；字段与 Task.to_dict 对齐。</summary>
public sealed record TaskDetail(
    string Id,
    string Description,
    string Status,
    int Priority,
    string? AgentId,
    string? ProjectId,
    JsonElement? Result,
    string? CreatedAt,
    string? DecisionMode,
    TaskConfirmation? Confirmation,
    TaskDecision[]? Decisions,
    TaskDecision? PendingDecision,
    string? RunId,
    string? Model,
    string? ApiKeyId);

/// <summary>GET/POST /tasks/{id}/messages 的单条消息。</summary>
public sealed record TaskMessage(
    string Id,
    string TaskId,
    long Seq,
    string Role,
    string Kind,
    string Content,
    JsonElement? Payload,
    string? CreatedAt);

/// <summary>POST /tasks/{id}/messages 的 data。</summary>
public sealed record TaskMessageResult(TaskDetail Task, TaskMessage[] Messages);

/// <summary>PUT /tasks/{id}/confirmation 的 data。</summary>
public sealed record TaskConfirmationResult(TaskDetail Task, TaskMessage Message);

/// <summary>POST /tasks/{id}/start 的 data：task + run + message。</summary>
public sealed record TaskStartResult(TaskDetail Task, JsonElement? Run, TaskMessage? Message);

/// <summary>POST /tasks/{id}/decisions/choose 的 data。</summary>
public sealed record TaskDecisionResult(TaskDetail Task, TaskDecision Decision, TaskMessage Message);

/// <summary>GET /workspace/changes 的完整变更（VirtualChange.to_dict 子集）。</summary>
public sealed record WorkspaceChange(
    string Id,
    string? ProjectId,
    string? TaskId,
    string? GroupId,
    string FilePath,
    string Kind,
    string? Diff,
    int AddedLines,
    int RemovedLines,
    string Status,
    string? Summary,
    string? Reason,
    string? AgentSource,
    string? ApplyError);

/// <summary>DSH Run 快照（DshRun.to_dict 子集）。</summary>
public sealed record DshRun(
    string RunId,
    string SessionId,
    string Instruction,
    string Status,
    string? FinalResponse,
    string? Error,
    double? DurationSeconds,
    string? StartedAt,
    string? FinishedAt,
    string? Model);

/// <summary>密钥库里可选的模型（GET /dsh/models 的 key_models 元素）。</summary>
public sealed record DshKeyModel(string KeyId, string Model, string? KeyName);

/// <summary>GET /dsh/models 的 data。</summary>
public sealed record DshModelsResponse(string[] Models, string? Model, DshKeyModel[]? KeyModels);

/// <summary>Agent 规格（含可执行模型目录）。</summary>
public sealed record AgentSpecDetail(
    string Name,
    string? Role,
    string? Runtime,
    string[]? Models,
    string? Model,
    DshKeyModel[]? KeyModels);

/// <summary>GET /agents/{id} 的完整 Agent 档案。</summary>
public sealed record AgentDetail(string Id, string State, AgentSpecDetail Spec);

/// <summary>终端会话（TerminalSession.to_dict）。</summary>
public sealed record TerminalSessionInfo(
    string Id,
    string? RunId,
    string WorkspaceRoot,
    string Kind,
    string Status,
    long NextSeq,
    string? CreatedAt,
    string? FinishedAt);

/// <summary>终端事件（TerminalEvent.to_dict）。</summary>
public sealed record TerminalEventInfo(
    string Id,
    string SessionId,
    long Seq,
    string Kind,
    string Source,
    string? Command,
    string? Chunk,
    int? ExitCode,
    string? CreatedAt);
