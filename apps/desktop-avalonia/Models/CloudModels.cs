namespace Flux.Desktop.Models;

/// <summary>账号云返回的用户信息。账号云的 user_id 是字符串（如 "2"），不要按整数解析。</summary>
public sealed record CloudUser(string Id, string Username);

/// <summary>GET /api/v1/cloud/session 的 data。</summary>
public sealed record CloudSession(
    bool Authenticated,
    CloudUser? User,
    bool Configured,
    string? LoginUrl);

/// <summary>POST /api/v1/cloud/exchange 与 /refresh 的 data。</summary>
public sealed record CloudTokens(
    bool Authenticated,
    string? AccessToken,
    string? RefreshToken,
    int ExpiresIn,
    CloudUser? User);

/// <summary>GET /api/v1/health 的 data。</summary>
public sealed record HealthInfo(string Status, string App, string Env);

/// <summary>GET /api/v1/dsh/status 的 data（不含密钥），用于顶栏引擎胶囊与状态栏工作区。</summary>
public sealed record DshStatus(
    bool Enabled,
    string? Home,
    string? Workspace,
    string? Provider,
    string? Model,
    DshMcp? Mcp);
