/**
 * API 客户端：只发同源 /api 请求（Vite 已把 /api 代理到后端，默认 127.0.0.1:8000，
 * 可用 FLUX_VITE_BACKEND_ORIGIN 覆盖，见 vite.config.ts）。
 *
 * 鉴权：已登录时带上账号云签发的 access token（`Authorization: Bearer`）；
 * 收到 401 先尝试续期一次再重放，续不动就交给 AuthGate 把用户送回登录页。
 *
 * 统一处理 {success, code, message, data, metadata} 信封：
 * - success=false 或 HTTP 非 2xx → 抛 ApiError（message 优先取后端原文）；
 * - 422 校验失败 → 提取 FastAPI 的 details（字段级），拼成可读文本。
 */
import { getAccessToken, getAuth, refreshAuth } from "./auth";
import { API_BASE } from "./base";
import type {
  AgentCreateRequest, AgentHandle, ApplyBatch, ApiKeyCreateRequest, ApiKeyEntry,
  ApiKeyUpdateRequest, BillingOverview, BillingPlan, BillingPrices, BillingRefreshResult, BillingRunUsage,
  Bot, BotCreateInput, BotMemory, BotMemoryChunk, BotMemoryDetail, BotMemoryRelevance,
  BotNotification, BotRoutine, BotRoutineInput, BotRoutineUpdateInput, BotRunOutcome,
  BotStatus, BotTool, BotUpdateInput,
  CapabilityImportOutcome, CapabilityKind,
  CapabilityScanReport, Change, ChatGroup, ChatGroupInterruptOutcome, ChatGroupMember,
  ChatGroupMessagePage, ChatGroupSendOutcome,
  ChatMessageIn, ChatResult, ConfirmationItem, DecisionMode,
  DecisionOutcome, DshModels, DshRun, DshStatus, Envelope, FileContent, FileTree, GitCommit, GitStatus, HealthData,
  IdeCatalog, IdeInstallResult, IdeSession,
  ImportedCapability, Installation, Project, ReadyData, RecoveryItem, ScanOutcome, SearchHit,
  SupervisorAssessResult, SupervisorDispatchOutcome, SupervisorDispatchThreadResult,
  SupervisorOverview, SupervisorPlanResult, SupervisorProcessResult, SupervisorProjectOverview,
  SupervisorReport, SupervisorSearchHit, SupervisorSettings, SupervisorStopResult,
  SupervisorTicket, SupervisorTimelineItem, SupervisorTrigger, SupervisorWatchdogResult, Task,
  TaskConfirmationOutcome, TaskMessage, TaskMessagePage, TaskReplyOutcome, TaskStartOutcome,
  TerminalEvent, TerminalSession,
} from "./types";

export { API_BASE } from "./base";

export function terminalStreamUrl(sessionId: string, afterSeq = 0): string {
  const suffix = afterSeq > 0 ? `?after_seq=${encodeURIComponent(afterSeq)}` : "";
  return `${API_BASE}/terminal/sessions/${encodeURIComponent(sessionId)}/stream${suffix}`;
}

/**
 * 语言服务器 WebSocket 地址。
 *
 * 浏览器不能给 WebSocket 加 Authorization 头，鉴权靠同源 cookie（`flux_auth_token`）
 * 或后端对 loopback 的放行，见 flux/api/ws_auth.py。
 */
export function ideLspUrl(projectId: string, server: string, workspaceRoot?: string): string {
  const url = new URL(
    `${API_BASE}/ide/lsp/${encodeURIComponent(projectId)}${query({ server, workspace_root: workspaceRoot })}`,
    window.location.origin,
  );
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly details: unknown;
  constructor(message: string, code: string, status: number, details?: unknown) {
    super(message); this.name = "ApiError"; this.code = code; this.status = status; this.details = details;
  }
}

function formatValidationDetails(details: unknown): string | null {
  if (!Array.isArray(details) || details.length === 0) return null;
  const lines = details.map((item) => {
    if (typeof item !== "object" || item === null) return null;
    const entry = item as { loc?: unknown; msg?: unknown };
    const loc = Array.isArray(entry.loc) ? entry.loc.slice(1).join(".") : "";
    const msg = typeof entry.msg === "string" ? entry.msg : "";
    return msg ? (loc ? `字段 ${loc}：${msg}` : msg) : null;
  }).filter((line): line is string => line !== null);
  return lines.length > 0 ? lines.join("；") : null;
}

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (
    typeof window !== "undefined" &&
    (window as Window & { fluxDesktop?: { isDesktop?: boolean } }).fluxDesktop?.isDesktop
  ) {
    headers["X-Flux-Desktop"] = "1";
  }
  const token = getAccessToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;
  try {
    return await fetch(`${API_BASE}${path}`, {
      method,
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError("无法连接后端服务，请确认 Flux 后端已启动", "network_error", 0);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<Envelope<T>> {
  let response = await send(method, path, body);
  // access token 到期（默认 30 分钟）：续期一次再重放，别让用着的人突然被判未登录。
  if (response.status === 401 && getAuth()) {
    const renewed = await refreshAuth();
    if (renewed) response = await send(method, path, body);
  }
  let envelope: Envelope<T> | null = null;
  try { envelope = (await response.json()) as Envelope<T>; }
  catch { throw new ApiError(`后端返回了无法解析的响应（HTTP ${response.status}）`, "bad_response", response.status); }
  if (!response.ok || envelope.success === false) {
    const code = envelope.code || "unknown_error";
    // HTTPException 风格的错误体（{detail: "..."}）没有信封 message，此时同样要透出后端原文。
    const detail = (envelope as { detail?: unknown }).detail;
    let message = envelope.message
      || (typeof detail === "string" && detail.trim() ? detail : "")
      || `请求失败（HTTP ${response.status}）`;
    if (code === "validation_error") { const fieldText = formatValidationDetails(envelope.data); if (fieldText) message = `${message}：${fieldText}`; }
    throw new ApiError(message, code, response.status, envelope.data);
  }
  return envelope;
}

async function getData<T>(method: string, path: string, body?: unknown): Promise<T> { return (await request<T>(method, path, body)).data; }
function query(params: Record<string, string | undefined>): string {
  const parts = Object.entries(params).filter((entry): entry is [string, string] => entry[1] !== undefined && entry[1] !== "").map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length > 0 ? `?${parts.join("&")}` : "";
}

export const api = {
  health: () => getData<HealthData>("GET", "/health"),
  ready: () => getData<ReadyData>("GET", "/health/ready"),
  /** 统一 LLM 接口（主规格 §12.6）：未配置任何供应商时后端返回 ProviderNotConfiguredError */
  chat: (body: { messages: ChatMessageIn[]; model?: string; provider?: string; task_id?: string; project_id?: string }) =>
    getData<ChatResult>("POST", "/models/chat", body),
  getWorkspaceRoot: () => getData<{ root: string }>("GET", "/workspace/root"),
  createWorkspaceFile: (projectId: string, path: string, content = "") =>
    getData<{ path: string }>("POST", `/projects/${projectId}/files/file`, { path, content }),
  createWorkspaceDirectory: (projectId: string, path: string) =>
    getData<{ path: string }>("POST", `/projects/${projectId}/files/directory`, { path }),
  renameWorkspacePath: (projectId: string, path: string, newPath: string) =>
    getData<{ path: string }>("PATCH", `/projects/${projectId}/files`, { path, new_path: newPath }),
  deleteWorkspacePath: (projectId: string, path: string) =>
    getData<{ path: string }>("DELETE", `/projects/${projectId}/files`, { path }),
  listProjects: () => getData<Project[]>("GET", "/projects"),
  createProject: (body: { name: string; repository: string | null }) => getData<Project>("POST", "/projects", body),
  scanProject: (projectId: string) => getData<ScanOutcome>("POST", `/projects/${projectId}/scan`, { record: true }),
  listFiles: (projectId: string, options?: { path?: string; depth?: number }) => getData<FileTree>("GET", `/projects/${projectId}/files${query({ path: options?.path, depth: options?.depth?.toString() })}`),
  readFile: (projectId: string, path: string) => getData<FileContent>("GET", `/projects/${projectId}/files/content${query({ path })}`),
  /** 覆写既有文件；后端要求 env=local 且桌面端头（send() 会自动带 X-Flux-Desktop） */
  saveFile: (projectId: string, path: string, content: string) =>
    getData<FileContent>("PUT", `/projects/${projectId}/files/content`, { path, content }),
  searchWorkspace: (projectId: string, search: string, options?: { path?: string; caseSensitive?: boolean; regex?: boolean; maxResults?: number }) =>
    getData<SearchHit[]>("GET", `/projects/${projectId}/search${query({
      q: search,
      path: options?.path,
      case_sensitive: options?.caseSensitive ? "true" : undefined,
      regex: options?.regex ? "true" : undefined,
      max_results: options?.maxResults?.toString(),
    })}`),
  replaceWorkspaceSearch: (
    projectId: string,
    queryText: string,
    replacement: string,
    options?: { path?: string; caseSensitive?: boolean; regex?: boolean },
  ) =>
    getData<{ files: string[]; replacements: number; truncated: boolean }>(
      "POST",
      `/projects/${projectId}/search/replace`,
      {
        query: queryText,
        replacement,
        path: options?.path,
        case_sensitive: options?.caseSensitive ?? false,
        regex: options?.regex ?? false,
      },
    ),
  listChanges: (status?: string) => getData<Change[]>("GET", `/workspace/changes${query({ status })}`),
  getChange: (changeId: string) => getData<Change>("GET", `/workspace/changes/${changeId}`),
  accept: (changeIds: string[]) => getData<Change[]>("POST", "/workspace/accept", { change_ids: changeIds }),
  apply: (changeIds: string[]) => getData<Change[]>("POST", "/workspace/apply", { change_ids: changeIds }),
  reject: (changeIds: string[], reason: string | null) => getData<Change[]>("POST", "/workspace/reject", { change_ids: changeIds, reason }),
  listRollbackableBatches: () => getData<ApplyBatch[]>("GET", "/workspace/apply-batches?recent=1"),
  rollbackChange: (changeId: string) => getData<Change>("POST", `/workspace/changes/${changeId}/rollback`),
  rollbackBatch: (batchId: string) => getData<ApplyBatch>("POST", `/workspace/apply-batches/${batchId}/rollback`),
  listRecovery: () => getData<RecoveryItem[]>("GET", "/workspace/recovery"),
  resolveRecovery: (changeId: string, action: "cover" | "keep") => getData<Change>("POST", "/workspace/recovery/resolve", { change_id: changeId, action }),
  gitStatus: () => getData<GitStatus>("GET", "/git/status"),
  gitStage: (paths: string[]) => getData<GitStatus>("POST", "/git/stage", { paths }),
  gitUnstage: (paths: string[]) => getData<GitStatus>("POST", "/git/unstage", { paths }),
  gitCommit: (body: { message: string; change_ids: string[]; paths?: string[] }) => getData<GitCommit>("POST", "/git/commit", body),
  listAgents: () => getData<AgentHandle[]>("GET", "/agents"),
  createAgent: (body: AgentCreateRequest) => getData<AgentHandle>("POST", "/agents", body),
  listInstallations: () => getData<Installation[]>("GET", "/installations"),
  scanInstallations: () => getData<Installation[]>("POST", "/installations/scan"),
  connectInstallation: (body: { agent?: string; all?: boolean }) => getData<Installation | Installation[]>("POST", "/installations/connect", body),
  removeInstallation: (name: string) => getData<{ agent: string; removed: boolean }>("DELETE", `/installations/${name}`),
  listCapabilities: (kind?: CapabilityKind) => getData<ImportedCapability[]>("GET", `/capabilities${query({ kind })}`),
  scanCapabilities: () => getData<CapabilityScanReport>("POST", "/capabilities/scan"),
  importCapability: (body: { kind: string; name: string; decision?: "keep" | "replace" }) =>
    getData<CapabilityImportOutcome>("POST", "/capabilities/import", body),
  dshStatus: () => getData<DshStatus>("GET", "/dsh/status"),
  /** 起一次 DSH Run：instruction 必填；session_id 复用即同一会话（留空由后端生成）；
   *  agent_id 指定执行者（留空 = 内置 DSH） */
  startDshRun: (instruction: string, sessionId?: string, agentId?: string | null, model?: string, keyId?: string | null) =>
    getData<DshRun>("POST", "/dsh/runs", {
      instruction,
      ...(sessionId ? { session_id: sessionId } : {}),
      ...(agentId ? { agent_id: agentId } : {}),
      ...(model ? { model } : {}),
      ...(keyId ? { key_id: keyId } : {}),
    }),
  getDshModels: () => getData<DshModels>("GET", "/dsh/models"),
  getDshRun: (runId: string) => getData<DshRun>("GET", `/dsh/runs/${encodeURIComponent(runId)}`),
  listDshRuns: () => getData<DshRun[]>("GET", "/dsh/runs"),
  interruptDshRun: (runId: string) =>
    getData<DshRun>("POST", `/dsh/runs/${encodeURIComponent(runId)}/interrupt`),
  listTasks: (options?: { projectId?: string; status?: string; limit?: number }) => getData<Task[]>("GET", `/tasks${query({ project_id: options?.projectId, status: options?.status, limit: options?.limit?.toString() })}`),
  createTask: (body: {
    description: string;
    project_id: string | null;
    decision_mode?: DecisionMode;
    agent_id?: string | null;
    /** bot 自定义名称（对话即 bot） */
    bot_name?: string | null;
    avatar_shape?: string;
    avatar_color?: string;
    /** false = 只建空 bot，不立刻调度执行；建 bot 必须传 false */
    start?: boolean;
  }) => getData<Task>("POST", "/tasks", body),
  getTask: (taskId: string) => getData<Task>("GET", `/tasks/${taskId}`),
  async listTaskMessages(taskId: string, options?: { limit?: number; before?: number }): Promise<TaskMessagePage> {
    const envelope = await request<TaskMessage[]>("GET", `/tasks/${taskId}/messages${query({ limit: options?.limit?.toString(), before: options?.before?.toString() })}`);
    return { items: envelope.data, hasMore: Boolean(envelope.metadata?.has_more) };
  },
  sendTaskMessage: (taskId: string, content: string) =>
    getData<TaskReplyOutcome>("POST", `/tasks/${taskId}/messages`, { content }),
  /** 终态对话续跑：新消息作为新一轮 Agent 指令，历史消息保留 */
  continueTask: (taskId: string, content: string) =>
    getData<TaskReplyOutcome>("POST", `/tasks/${taskId}/continue`, { content }),
  startTask: (taskId: string, confirmation?: ConfirmationItem[]) =>
    getData<TaskStartOutcome>(
      "POST",
      `/tasks/${taskId}/start`,
      confirmation ? { confirmation } : {},
    ),
  setDecisionMode: (taskId: string, mode: DecisionMode) =>
    getData<Task>("POST", `/tasks/${taskId}/decision-mode`, { mode }),
  updateTaskConfirmation: (taskId: string, items: ConfirmationItem[]) =>
    getData<TaskConfirmationOutcome>("PUT", `/tasks/${taskId}/confirmation`, { items }),
  cancelTask: (taskId: string) => getData<Task>("POST", `/tasks/${taskId}/cancel`),
  /** 密钥库：列表只含掩码与元数据，明文只进 create/update 的请求体 */
  listApiKeys: () => getData<ApiKeyEntry[]>("GET", "/keys"),
  createApiKey: (body: ApiKeyCreateRequest) => getData<ApiKeyEntry>("POST", "/keys", body),
  updateApiKey: (keyId: string, body: ApiKeyUpdateRequest) =>
    getData<ApiKeyEntry>("PATCH", `/keys/${encodeURIComponent(keyId)}`, body),
  deleteApiKey: (keyId: string) =>
    getData<{ deleted: boolean; key_id: string }>("DELETE", `/keys/${encodeURIComponent(keyId)}`),
  chooseDecision: (
    taskId: string,
    body: { decision_id: string; action: "choose" | "reject"; option?: string; note?: string },
  ) => getData<DecisionOutcome>("POST", `/tasks/${taskId}/decisions/choose`, body),
  replyTask: (taskId: string, body: { content: string }) => getData<TaskReplyOutcome>("POST", `/tasks/${taskId}/reply`, body),
  confirmTask: (taskId: string, body: { confirmation_id: string; approved: boolean }) => getData<TaskConfirmationOutcome>("POST", `/tasks/${taskId}/confirm`, body),
  decideTask: (taskId: string, body: { decision: DecisionMode }) => getData<DecisionOutcome>("POST", `/tasks/${taskId}/decision`, body),
  listConfirmations: (taskId: string) => getData<ConfirmationItem[]>("GET", `/tasks/${taskId}/confirmations`),
  listTerminalSessions: () => getData<TerminalSession[]>("GET", "/terminal/sessions"),
  getTerminalSession: (sessionId: string) =>
    getData<TerminalSession>("GET", `/terminal/sessions/${encodeURIComponent(sessionId)}`),
  runTerminalCommand: (sessionId: string, command: string) =>
    getData<TerminalEvent>("POST", `/terminal/sessions/${encodeURIComponent(sessionId)}/commands`, { command }),
  listHumanTerminalSessions: () => getData<TerminalSession[]>("GET", "/terminal/pty/sessions"),
  getHumanTerminalSession: (sessionId: string) =>
    getData<TerminalSession>("GET", `/terminal/pty/sessions/${encodeURIComponent(sessionId)}`),
  createHumanTerminalSession: () =>
    getData<TerminalSession>("POST", "/terminal/pty/sessions"),
  stopHumanTerminalSession: (sessionId: string, force = false) =>
    getData<TerminalSession>(
      "POST",
      `/terminal/pty/sessions/${encodeURIComponent(sessionId)}/stop`,
      { force },
    ),
  createTerminalSession: (body?: { workspace_root?: string }) => getData<TerminalSession>("POST", "/terminal/sessions", body),
  stopTerminalSession: (id: string, force = false) => getData<TerminalSession>("POST", `/terminal/sessions/${id}/stop`, { force }),
  terminalEvents: (id: string, after?: number) => getData<TerminalEvent[]>("GET", `/terminal/sessions/${id}/events${query({ after_seq: after?.toString() })}`),
  /** 成本中心：plans 返回所有套餐与各窗口的额度视图，refresh=true 时顺带拉官方快照 */
  billingPlans: (refresh = false) => getData<BillingOverview>("GET", `/billing/plans${query({ refresh: refresh ? "true" : undefined })}`),
  billingUsage: (plan: string, refresh = false) => getData<BillingPlan>("GET", `/billing/usage${query({ plan, refresh: refresh ? "true" : undefined })}`),
  billingRunUsage: (runId: string) => getData<BillingRunUsage>("GET", `/billing/runs/${encodeURIComponent(runId)}`),
  billingRefresh: (plan?: string) => getData<BillingRefreshResult>("POST", `/billing/refresh${query({ plan })}`),
  /** 官方 API 价目表（每 100 万 token；CNY 条目已给换算率，由前端展示） */
  billingPrices: () => getData<BillingPrices>("GET", "/billing/prices"),
  /** IDE：语言服务器清单 + 语言目录 + 扩展名/文件名映射 */
  ideLanguages: () => getData<IdeCatalog>("GET", "/ide/languages"),
  installLanguageServer: (serverId: string) =>
    getData<IdeInstallResult>("POST", `/ide/languages/${encodeURIComponent(serverId)}/install`),
  ideSessions: () => getData<IdeSession[]>("GET", "/ide/sessions"),
  stopIdeSession: (server: string, workspaceRoot?: string) =>
    getData<{ server: string; stopped: boolean }>(
      "POST",
      `/ide/sessions/stop${query({ server, workspace_root: workspaceRoot })}`,
    ),
  /** 助理（Supervisor）：观察回放、人工重判、规划派发、工单、报告、时间线、设置 */
  supervisorOverview: (limit = 20) =>
    getData<SupervisorOverview>("GET", `/supervisor/overview${query({ limit: limit.toString() })}`),
  supervisorObservations: (options?: { limit?: number; status?: string }) =>
    getData<SupervisorTrigger[]>(
      "GET",
      `/supervisor/observations${query({ limit: options?.limit?.toString(), status: options?.status })}`,
    ),
  supervisorObservation: (id: string) =>
    getData<SupervisorTrigger>("GET", `/supervisor/observations/${encodeURIComponent(id)}`),
  supervisorAssess: (id: string) =>
    getData<SupervisorAssessResult>("POST", `/supervisor/observations/${encodeURIComponent(id)}/assess`),
  supervisorPlan: (id: string, dispatch = false) =>
    getData<SupervisorPlanResult>(
      "POST",
      `/supervisor/observations/${encodeURIComponent(id)}/plan${query({ dispatch: dispatch ? "true" : undefined })}`,
    ),
  supervisorTickets: (options?: { threadId?: string; limit?: number }) =>
    getData<SupervisorTicket[]>(
      "GET",
      `/supervisor/tickets${query({ thread_id: options?.threadId, limit: options?.limit?.toString() })}`,
    ),
  supervisorTicket: (id: string) =>
    getData<SupervisorTicket>("GET", `/supervisor/tickets/${encodeURIComponent(id)}`),
  supervisorReports: (limit = 50) =>
    getData<SupervisorReport[]>("GET", `/supervisor/reports${query({ limit: limit.toString() })}`),
  supervisorTimeline: (options?: { threadId?: string; projectId?: string; limit?: number }) =>
    getData<SupervisorTimelineItem[]>(
      "GET",
      `/supervisor/timeline${query({
        thread_id: options?.threadId,
        project_id: options?.projectId,
        limit: options?.limit?.toString(),
      })}`,
    ),
  supervisorProject: (projectId: string, limit = 50) =>
    getData<SupervisorProjectOverview>(
      "GET",
      `/supervisor/projects/${encodeURIComponent(projectId)}${query({ limit: limit.toString() })}`,
    ),
  supervisorSearch: (q: string, limit = 20) =>
    getData<SupervisorSearchHit[]>("GET", `/supervisor/search${query({ q, limit: limit.toString() })}`),
  supervisorWatchdog: (limit = 50) =>
    getData<SupervisorWatchdogResult>(
      "POST",
      `/supervisor/watchdog${query({ limit: limit.toString() })}`,
    ),
  supervisorProcess: (limit = 10) =>
    getData<SupervisorProcessResult>(
      "POST",
      `/supervisor/process${query({ limit: limit.toString() })}`,
    ),
  supervisorSettings: () => getData<SupervisorSettings>("GET", "/supervisor/settings"),
  updateSupervisorSettings: (
    body: Partial<
      Pick<
        SupervisorSettings,
        | "accept_mode"
        | "model_provider"
        | "model_name"
        | "max_tickets_per_thread"
        | "max_ticket_depth"
        | "max_concurrent_runs"
      >
    >,
  ) => getData<SupervisorSettings>("PUT", "/supervisor/settings", body),
  supervisorDispatchThread: (threadId: string) =>
    getData<SupervisorDispatchThreadResult>(
      "POST",
      `/supervisor/threads/${encodeURIComponent(threadId)}/dispatch`,
    ),
  supervisorDispatchTicket: (ticketId: string) =>
    getData<SupervisorDispatchOutcome>(
      "POST",
      `/supervisor/tickets/${encodeURIComponent(ticketId)}/dispatch`,
    ),
  supervisorRetryTicket: (ticketId: string) =>
    getData<SupervisorDispatchOutcome>(
      "POST",
      `/supervisor/tickets/${encodeURIComponent(ticketId)}/retry`,
    ),
  supervisorStopTicket: (ticketId: string, note = "") =>
    getData<SupervisorStopResult>(
      "POST",
      `/supervisor/tickets/${encodeURIComponent(ticketId)}/stop`,
      { note },
    ),
  /** 内部群聊：群 CRUD、成员、消息轮询（非流式）、打断 */
  listGroups: () => getData<ChatGroup[]>("GET", "/groups"),
  createGroup: (body: {
    name: string;
    project_id?: string | null;
    description?: string | null;
    max_rounds?: number;
    bot_ids?: string[];
    admin_bot_id?: string | null;
  }) => getData<ChatGroup>("POST", "/groups", body),
  getGroup: (groupId: string) => getData<ChatGroup>("GET", `/groups/${groupId}`),
  deleteGroup: (groupId: string) =>
    getData<{ deleted: boolean }>("DELETE", `/groups/${groupId}`),
  addGroupMember: (groupId: string, botId: string) =>
    getData<ChatGroupMember[]>("POST", `/groups/${groupId}/members`, { bot_id: botId }),
  removeGroupMember: (groupId: string, botId: string) =>
    getData<{ removed: boolean; members: ChatGroupMember[] }>(
      "DELETE",
      `/groups/${groupId}/members/${botId}`,
    ),
  listGroupMessages: (groupId: string, options?: { afterSeq?: number; limit?: number }) =>
    getData<ChatGroupMessagePage>(
      "GET",
      `/groups/${groupId}/messages${query({
        after_seq: options?.afterSeq?.toString(),
        limit: options?.limit?.toString(),
      })}`,
    ),
  sendGroupMessage: (groupId: string, content: string) =>
    getData<ChatGroupSendOutcome>("POST", `/groups/${groupId}/messages`, { content }),
  interruptGroup: (groupId: string) =>
    getData<ChatGroupInterruptOutcome>("POST", `/groups/${groupId}/interrupt`),
  /** 群成员候选：全部常驻 Bot */
  listBotCandidates: () => getData<Bot[]>("GET", "/bots?limit=200"),
  /**
   * Bot 模式：常驻 Bot 的配置面。
   * 开工仍是普通任务（`tasks.bot_id` 指回 Bot），执行 / 消息 / 计费都复用任务接口。
   */
  listBots: (options?: { status?: BotStatus; limit?: number }) =>
    getData<Bot[]>(
      "GET",
      `/bots${query({ status: options?.status, limit: options?.limit?.toString() })}`,
    ),
  getBot: (botId: string) => getData<Bot>("GET", `/bots/${botId}`),
  createBot: (body: BotCreateInput) => getData<Bot>("POST", "/bots", body),
  updateBot: (botId: string, body: BotUpdateInput) =>
    getData<Bot>("PATCH", `/bots/${botId}`, body),
  deleteBot: (botId: string) => getData<{ deleted: string }>("DELETE", `/bots/${botId}`),
  /** 手动开工一次；instruction 留空则用 Bot 的 description */
  runBot: (botId: string, instruction?: string) =>
    getData<BotRunOutcome>("POST", `/bots/${botId}/run`, {
      instruction: instruction?.trim() || null,
    }),
  listBotTasks: (botId: string, options?: { status?: string; limit?: number }) =>
    getData<Task[]>(
      "GET",
      `/bots/${botId}/tasks${query({
        status: options?.status,
        limit: options?.limit?.toString(),
      })}`,
    ),
  /** 分层长期记忆：摘要层列表（可按相关性 / 来源群过滤） */
  listBotMemories: (
    botId: string,
    options?: { relevance?: BotMemoryRelevance; groupId?: string; limit?: number; offset?: number },
  ) =>
    getData<BotMemory[]>(
      "GET",
      `/bots/${botId}/memories${query({
        relevance: options?.relevance,
        group_id: options?.groupId,
        limit: options?.limit?.toString(),
        offset: options?.offset?.toString(),
      })}`,
    ),
  /** 全文检索：从只追加的原文层里找回「当时到底怎么说的」 */
  searchBotMemories: (botId: string, q: string, limit = 20) =>
    getData<BotMemoryChunk[]>(
      "GET",
      `/bots/${botId}/memories/search${query({ q, limit: limit.toString() })}`,
    ),
  /** 一条记忆的摘要 + 全文副本 */
  getBotMemory: (botId: string, memoryId: string) =>
    getData<BotMemoryDetail>("GET", `/bots/${botId}/memories/${memoryId}`),
  deleteBotMemory: (botId: string, memoryId: string) =>
    getData<{ deleted: string }>("DELETE", `/bots/${botId}/memories/${memoryId}`),
  /** 清空该 Bot 的记忆；传 groupId 时只清某个群沉淀的部分 */
  clearBotMemories: (botId: string, groupId?: string) =>
    getData<{ deleted: number }>("DELETE", `/bots/${botId}/memories${query({ group_id: groupId })}`),
  /** 可为 Bot 配置的技能清单（已剔除硬禁令工具） */
  listBotTools: () => getData<BotTool[]>("GET", "/bots/tools"),
  /** 例程：一个 Bot 下多个独立作业单元 */
  listBotRoutines: (botId: string) => getData<BotRoutine[]>("GET", `/bots/${botId}/routines`),
  createBotRoutine: (botId: string, body: BotRoutineInput) =>
    getData<BotRoutine>("POST", `/bots/${botId}/routines`, body),
  updateBotRoutine: (botId: string, routineId: string, body: BotRoutineUpdateInput) =>
    getData<BotRoutine>("PATCH", `/bots/${botId}/routines/${routineId}`, body),
  deleteBotRoutine: (botId: string, routineId: string) =>
    getData<{ deleted: string }>("DELETE", `/bots/${botId}/routines/${routineId}`),
  /** Bot 主动通知（当前主要是开工失败） */
  listBotNotifications: (options?: { unreadOnly?: boolean; limit?: number }) =>
    getData<BotNotification[]>(
      "GET",
      `/bots/notifications${query({
        unread_only: options?.unreadOnly ? "true" : undefined,
        limit: options?.limit?.toString(),
      })}`,
    ),
  markBotNotificationRead: (notificationId: string) =>
    getData<BotNotification>("POST", `/bots/notifications/${notificationId}/read`),
  markAllBotNotificationsRead: () =>
    getData<{ updated: number }>("POST", "/bots/notifications/read-all"),
};
