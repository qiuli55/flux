using System.Collections.Generic;
using System.Text.Json;

namespace Flux.Desktop.Models;

// 助理（Supervisor）接口的只读模型：字段与 web 端 api/types.ts 的 Supervisor 段一一对应。
// 枚举值后端以字符串下发（off/observe/auto、intake/planned/...），这里保持 string 不透传强类型，
// 避免新增阶段时桌面端反序列化直接失败。

/// <summary>GET/PUT /api/v1/supervisor/settings 的 data。</summary>
public sealed record SupervisorSettings(
    string AcceptMode,
    string ModelProvider,
    string ModelName,
    int MaxTicketsPerThread,
    int MaxTicketDepth,
    int MaxConcurrentRuns,
    string? UpdatedAt);

/// <summary>一次判断结论（只追加，不更新）。</summary>
public sealed record SupervisorEval(
    string Id,
    string TriggerId,
    string Decision,
    double? Confidence,
    string Reason,
    JsonElement? Risk,
    JsonElement? Model,
    int? LatencyMs,
    string CreatedAt);

/// <summary>一条外部事件（消息 / webhook / 内部指令）。</summary>
public sealed record SupervisorTrigger(
    string Id,
    string SourceKey,
    string ExternalId,
    string EventType,
    string ActorId,
    string ActorPlatform,
    string ThreadKey,
    string? ProjectId,
    string Content,
    string Status,
    string? ReceivedAt,
    string CreatedAt,
    SupervisorEval? Eval,
    SupervisorEval[]? Evals);

/// <summary>一段持续对话（授权边界）。</summary>
public sealed record SupervisorThread(
    string Id,
    string ThreadKey,
    string SourceKey,
    string? ProjectId,
    string Title,
    string? Summary,
    string? LastActivityAt);

/// <summary>一次派活记录：工单 → Agent / Task / DSH Run。</summary>
public sealed record SupervisorAssignment(
    string Id,
    string TicketId,
    string? AgentId,
    string? TaskId,
    string? RunId,
    string Status,
    string? StartedAt,
    string? FinishedAt);

/// <summary>子 Agent 交回的结构化结论。</summary>
public sealed record SupervisorReport(
    string Id,
    string TicketId,
    string? AssignmentId,
    string Status,
    string Summary,
    JsonElement? Payload,
    string CreatedAt);

/// <summary>报告直通投递的订阅目标。</summary>
public sealed record SupervisorSubscription(
    string Id,
    string TicketId,
    string SourceKey,
    string ExternalId,
    string Mode);

/// <summary>一张可派活的工单；阶段由 task / assignment / report 事实派生。</summary>
public sealed record SupervisorTicket(
    string Id,
    string ThreadId,
    string? ParentTicketId,
    string? ProjectId,
    string? TaskId,
    string PlanKey,
    string Title,
    string Description,
    string Role,
    string[] DependsOn,
    bool Degraded,
    int Sequence,
    string Stage,
    string CreatedAt,
    string UpdatedAt,
    SupervisorAssignment? Assignment,
    SupervisorReport? Report,
    SupervisorAssignment[]? Assignments,
    SupervisorReport[]? Reports,
    SupervisorSubscription[]? Subscriptions);

/// <summary>规划产出的建议工单（未落库前的树结构）。</summary>
public sealed record SupervisorPlanTicket(
    string Key,
    string Title,
    string Role,
    string[] DependsOn,
    int Sequence);

/// <summary>规划结果。</summary>
public sealed record SupervisorPlan(
    bool Degraded,
    string Reason,
    int? LatencyMs,
    JsonElement? ModelMeta,
    SupervisorPlanTicket[] Tickets);

/// <summary>一次派发的结论。</summary>
public sealed record SupervisorDispatchOutcome(
    string TicketId,
    bool Dispatched,
    string Reason,
    string? TaskId,
    string RunId,
    string? AgentId);

/// <summary>POST /observations/{id}/plan 的 data。</summary>
public sealed record SupervisorPlanResult(
    SupervisorThread Thread,
    SupervisorPlan Plan,
    SupervisorTicket[] Tickets,
    SupervisorDispatchOutcome[] Dispatch);

/// <summary>GET /api/v1/supervisor/overview 的 data。</summary>
public sealed record SupervisorOverview(
    SupervisorSettings Settings,
    Dictionary<string, int> Counts,
    SupervisorTicket[] Tickets,
    int PendingOutbox);

/// <summary>时间线条目：事件 / 工单 / 报告三合一。</summary>
public sealed record SupervisorTimelineItem(
    string At,
    string Kind,
    string Id,
    string Title,
    string Status,
    string? SourceKey,
    string? ActorId,
    string? ThreadId,
    string? Summary,
    string? TicketId);

/// <summary>判断层输出（人工重判接口返回）。</summary>
public sealed record SupervisorAssessment(
    string Decision,
    string Reason,
    double? Confidence,
    JsonElement? Risk,
    bool ShouldDispatch,
    string Title,
    JsonElement? ModelMeta,
    int? LatencyMs);

/// <summary>POST /observations/{id}/assess 的 data。</summary>
public sealed record SupervisorAssessResult(
    SupervisorTrigger Trigger,
    SupervisorAssessment? Assessment);

/// <summary>POST /api/v1/supervisor/watchdog 的 data。</summary>
public sealed record SupervisorWatchdogItem(string TicketId, bool Expired, string? Reason);

public sealed record SupervisorWatchdogResult(
    int Checked,
    int Expired,
    SupervisorWatchdogItem[] Items);

/// <summary>POST /api/v1/supervisor/process 的 data。</summary>
public sealed record SupervisorProcessResult(int Processed, int PendingOutbox);

/// <summary>POST /api/v1/supervisor/tickets/{id}/stop 的 data。</summary>
public sealed record SupervisorStopResult(string TicketId, string StoppedRun, string Report);

/// <summary>POST /api/v1/supervisor/threads/{id}/dispatch 的 data。</summary>
public sealed record SupervisorDispatchThreadResult(
    int Dispatched,
    SupervisorDispatchOutcome[] Outcomes);
