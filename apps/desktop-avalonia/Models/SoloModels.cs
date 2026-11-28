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
    string? ApiKeyId,
    string? Origin = null,
    string? ParentTaskId = null);

/// <summary>右上角助理切换项：主助理或已创建的项目助理。</summary>
public sealed record AssistantEntry(
    string Id,
    string Name,
    string Kind,
    string? ConversationTaskId,
    string? ProjectId = null,
    string[]? SourceTaskIds = null,
    string? StatusLabel = null);

public sealed record AssistantList(AssistantEntry Master, AssistantEntry[] Projects);

/// <summary>项目助理创建建议：只建议，不创建。</summary>
public sealed record AssistantSuggestion(
    string Name,
    string? ProjectId,
    int ConversationCount,
    string[] ConversationIds,
    string[]? Titles = null);

/// <summary>项目助理进度汇总里的一段相关对话（只读）。</summary>
public sealed record AssistantProgressItem(
    string TaskId,
    string Title,
    string Status,
    string StatusLabel,
    string Bucket,
    string BucketLabel,
    double IdleHours,
    string? LastActivityAt,
    string LastMessage,
    int MessageCount,
    bool ReadOnly)
{
    public string IdleText => $"最后更新 {IdleHours:0.#} 小时前";

    public string SummaryText => LastMessage.Length > 0 ? $"{IdleText} · {LastMessage}" : IdleText;

    /// <summary>真正进行中先别叠第二轮；可能已停住可在二次确认后接着干。</summary>
    public bool CanContinue => Bucket is not "running";
}

/// <summary>GET /assistants/{id}/progress：只读汇总，不启动电脑 Agent、不派子任务。</summary>
public sealed record AssistantProgress(
    string AssistantId,
    string AssistantName,
    string? GeneratedAt,
    int StalledAfterHours,
    Dictionary<string, int> Counts,
    AssistantProgressItem[] Conversations,
    string[]? MissingSources = null)
{
    public int Running => Counts.TryGetValue("running", out var value) ? value : 0;

    public int PossiblyStalled => Counts.TryGetValue("possibly_stalled", out var value) ? value : 0;

    public int Failed => Counts.TryGetValue("failed", out var value) ? value : 0;

    public int Completed => Counts.TryGetValue("completed", out var value) ? value : 0;

    public string HintText =>
        $"超过 {StalledAfterHours} 小时没有新消息，就算「可能已停住」。这里只读服务端已有记录，不会替你开电脑，也不会派子任务。";
}

/// <summary>GET /assistants/{id}/history：可「读历史」的相关对话列表。</summary>
public sealed record AssistantHistory(
    string AssistantId,
    string AssistantName,
    bool ReadOnly,
    int Count,
    AssistantProgressItem[] Conversations,
    string[]? MissingSources = null);

/// <summary>GET /assistants/{id}/history/{task_id}：只读打开的对话与消息。</summary>
public sealed record AssistantHistoryDetail(
    string AssistantId,
    bool ReadOnly,
    bool HasMore,
    TaskDetail Task,
    TaskMessage[] Messages);

/// <summary>POST /assistants/{id}/continue：确认后向原对话发送并续跑。</summary>
public sealed record AssistantContinueResult(
    TaskDetail Task,
    TaskMessage[] Messages,
    bool ReadOnly);

/// <summary>POST /assistants/{id}/fork：确认后新建的独立常驻对话。</summary>
public sealed record AssistantForkResult(
    TaskDetail Task,
    string SourceTaskId,
    int SummaryChars,
    string? ParentTaskId,
    string Origin);

/// <summary>某主对话下的临时子任务摘要。</summary>
public sealed record SubtaskSummary(
    string SubtaskId,
    string? ParentTaskId,
    string Status,
    string StatusLabel,
    string? AgentId,
    string? ModelName,
    string? ApiKeyId,
    string Title,
    string? Result = null,
    string? CreatedAt = null);

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

/// <summary>GET /tasks/{id}/context：平台助手这一轮实际能带上的对话上下文占用。</summary>
public sealed record TaskContextUsage(
    int Limit,
    int MessageCount,
    int UsedMessages,
    int UsedChars,
    int BudgetChars,
    double Ratio,
    int Percent,
    string Level,
    int MessageChars);

/// <summary>POST /tasks/{id}/handoff：旧对话的前情摘要，供新建对话带入。</summary>
public sealed record HandoffSummary(
    string Summary,
    string FromTaskId,
    string Provider,
    string Model,
    int SourceMessages,
    bool Truncated);

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
    DshKeyModel[]? KeyModels,
    bool? NativeStreaming = null);

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
