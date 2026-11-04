namespace Flux.Desktop.Models;

/// <summary>
/// GET /api/v1/bots 的元素（Bot 模式）。
/// 与对话共用执行链路，但有自己的工作目录、Agent、权限模式与 cron 定时。
/// </summary>
public sealed record BotModel(
    string Id,
    string Name,
    string Description,
    string AvatarShape,
    string AvatarColor,
    string? AgentId,
    string? Model,
    string PermissionMode,
    string DecisionMode,
    string? Workspace,
    string WorkspacePath,
    string Status,
    string? Schedule,
    string? NextRunAt,
    string? LastRunAt,
    string? LastTaskId,
    string? CreatedAt,
    string? UpdatedAt)
{
    public bool IsActive => Status == "active";
}

/// <summary>POST /api/v1/bots/{id}/run 的 data：刷新后的 Bot + 本轮任务。</summary>
public sealed record BotRunOutcome(BotModel Bot, FluxTask Task);

/// <summary>GET /api/v1/bots/{id}/tasks 的元素（只取列表要展示的字段）。</summary>
public sealed record BotRunItem(
    string Id,
    string Description,
    string Status,
    string? Result,
    string? CreatedAt,
    string? Workspace);
