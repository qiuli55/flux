namespace Flux.Desktop.Models;

/// <summary>GET /api/v1/projects 的元素。</summary>
public sealed record Project(string Id, string Name, string? Repository);

/// <summary>DSH 状态里的 MCP 注入配置（不含密钥）。</summary>
public sealed record DshMcp(bool Enabled, string? Url, string? ServerName);

/// <summary>GET /api/v1/health/ready 的 data。</summary>
public sealed record ReadyInfo(bool Database, int Agents, string[] Providers);

/// <summary>GET /api/v1/workspace/root 的 data。</summary>
public sealed record WorkspaceRootInfo(string Root);

/// <summary>GET /api/v1/installations 的元素（本机 CLI Agent 的事实快照）。</summary>
public sealed record Installation(
    string Id,
    string Name,
    string Adapter,
    string? Path,
    string? Version,
    string? AuthStatus,
    string Status);

/// <summary>GET /api/v1/capabilities 的元素（已导入的能力）。</summary>
public sealed record ImportedCapability(
    string Id,
    string Kind,
    string Name,
    string? Version,
    string? Source,
    string? UpdatedAt);

/// <summary>扫描结果汇总。</summary>
public sealed record CapabilitySummary(int Total, int Ok, int Blocked);

/// <summary>扫描来源目录（path 是目录路径，与能力的 source 不同）。</summary>
public sealed record CapabilitySource(string Path, string Status, int Candidates);

/// <summary>安全扫描的发现项。</summary>
public sealed record CapabilityFinding(string Reason);

/// <summary>扫描候选能力。</summary>
public sealed record CapabilityCandidate(
    string Kind,
    string Name,
    string Status,
    string? Source,
    CapabilityFinding[] Findings);

/// <summary>POST /api/v1/capabilities/scan 的 data。</summary>
public sealed record CapabilityScanReport(
    CapabilitySummary Summary,
    CapabilitySource[] Sources,
    CapabilityCandidate[] Candidates);

/// <summary>POST /api/v1/capabilities/import 的 data。</summary>
public sealed record CapabilityImportResult(string Name, string? Outcome);

/// <summary>GET /api/v1/workspace/changes 的元素（只取外壳概览用得到的字段）。</summary>
public sealed record Change(
    string Id,
    string FilePath,
    string Kind,
    int AddedLines,
    int RemovedLines);

/// <summary>Agent 规格（GET /api/v1/agents 内嵌）。</summary>
public sealed record AgentSpec(string Name, string? Runtime);

/// <summary>GET /api/v1/keys 的元素（密钥库；只有掩码，没有明文/密文）。</summary>
public sealed record ApiKeyEntry(
    string Id,
    string Name,
    string Provider,
    string BaseUrl,
    string WireApi,
    string[] Models,
    string KeyHint,
    bool Enabled)
{
    public string ToggleText => Enabled ? "停用" : "启用";
    public string BaseUrlText => string.IsNullOrWhiteSpace(BaseUrl) ? "默认地址" : BaseUrl;
    public string ModelsText => string.Join(" / ", Models);
}

/// <summary>DELETE /api/v1/keys/{id} 的 data。</summary>
public sealed record ApiKeyDeleteResult(bool Deleted, string KeyId);

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
