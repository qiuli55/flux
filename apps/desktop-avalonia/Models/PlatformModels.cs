namespace Flux.Desktop.Models;

/// <summary>GET /api/v1/projects 的元素。</summary>
public sealed record Project(string Id, string Name, string? Repository);

/// <summary>GET /api/v1/workspace/changes 的元素（只取外壳概览用得到的字段）。</summary>
public sealed record Change(
    string Id,
    string FilePath,
    string Kind,
    int AddedLines,
    int RemovedLines);

/// <summary>Agent 规格（GET /api/v1/agents 内嵌）。</summary>
public sealed record AgentSpec(string Name, string? Runtime);

/// <summary>GET /api/v1/agents 的元素。</summary>
public sealed record AgentHandle(string Id, string State, AgentSpec Spec);

/// <summary>
/// GET /api/v1/tasks 的元素。刻意不叫 Task，避免与 System.Threading.Tasks.Task 冲突。
/// </summary>
public sealed record FluxTask(
    string Id,
    string Description,
    string Status,
    string DecisionMode,
    string? AgentId,
    string? ProjectId,
    string CreatedAt);
