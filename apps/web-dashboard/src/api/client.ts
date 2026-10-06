/**
 * API 客户端：只发同源 /api 请求（Vite 已把 /api 代理到后端，默认 127.0.0.1:8000，
 * 可用 FLUX_VITE_BACKEND_ORIGIN 覆盖，见 vite.config.ts）。
 *
 * 统一处理 {success, code, message, data, metadata} 信封：
 * - success=false 或 HTTP 非 2xx → 抛 ApiError（message 优先取后端原文）；
 * - 422 校验失败 → 提取 FastAPI 的 details（字段级），拼成可读文本。
 */
import type {
  AgentCreateRequest, AgentHandle, ApplyBatch, Change, ConfirmationItem, DecisionMode,
  DecisionOutcome, Envelope, FileContent, FileTree, GitCommit, GitStatus, HealthData,
  Installation, Project, ReadyData, RecoveryItem, ScanOutcome, SearchHit, Task, TaskConfirmationOutcome,
  TaskMessage, TaskMessagePage, TaskReplyOutcome, TaskStartOutcome, TerminalEvent, TerminalSession,
} from "./types";

export const API_BASE = (import.meta.env.VITE_API_BASE ?? "/api/v1").replace(/\/$/, "");

export function terminalStreamUrl(sessionId: string, afterSeq = 0): string {
  const suffix = afterSeq > 0 ? `?after_seq=${encodeURIComponent(afterSeq)}` : "";
  return `${API_BASE}/terminal/sessions/${encodeURIComponent(sessionId)}/stream${suffix}`;
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

async function request<T>(method: string, path: string, body?: unknown): Promise<Envelope<T>> {
  let response: Response;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (
    typeof window !== "undefined" &&
    (window as Window & { fluxDesktop?: { isDesktop?: boolean } }).fluxDesktop?.isDesktop
  ) {
    headers["X-Flux-Desktop"] = "1";
  }
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError("无法连接后端服务，请确认 Flux 后端已启动", "network_error", 0);
  }
  let envelope: Envelope<T> | null = null;
  try { envelope = (await response.json()) as Envelope<T>; }
  catch { throw new ApiError(`后端返回了无法解析的响应（HTTP ${response.status}）`, "bad_response", response.status); }
  if (!response.ok || envelope.success === false) {
    const code = envelope.code || "unknown_error";
    let message = envelope.message || `请求失败（HTTP ${response.status}）`;
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
  gitCommit: (body: { message: string; change_ids: string[] }) => getData<GitCommit>("POST", "/git/commit", body),
  listAgents: () => getData<AgentHandle[]>("GET", "/agents"),
  createAgent: (body: AgentCreateRequest) => getData<AgentHandle>("POST", "/agents", body),
  listInstallations: () => getData<Installation[]>("GET", "/installations"),
  scanInstallations: () => getData<Installation[]>("POST", "/installations/scan"),
  connectInstallation: (body: { agent?: string; all?: boolean }) => getData<Installation | Installation[]>("POST", "/installations/connect", body),
  removeInstallation: (name: string) => getData<{ agent: string; removed: boolean }>("DELETE", `/installations/${name}`),
  listTasks: (options?: { projectId?: string; status?: string; limit?: number }) => getData<Task[]>("GET", `/tasks${query({ project_id: options?.projectId, status: options?.status, limit: options?.limit?.toString() })}`),
  createTask: (body: { description: string; project_id: string | null; decision_mode?: DecisionMode; agent_id?: string | null }) => getData<Task>("POST", "/tasks", body),
  getTask: (taskId: string) => getData<Task>("GET", `/tasks/${taskId}`),
  async listTaskMessages(taskId: string, options?: { limit?: number; before?: number }): Promise<TaskMessagePage> {
    const envelope = await request<TaskMessage[]>("GET", `/tasks/${taskId}/messages${query({ limit: options?.limit?.toString(), before: options?.before?.toString() })}`);
    return { items: envelope.data, hasMore: Boolean(envelope.metadata?.has_more) };
  },
  sendTaskMessage: (taskId: string, content: string) =>
    getData<TaskReplyOutcome>("POST", `/tasks/${taskId}/messages`, { content }),
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
  terminalEvents: (id: string, after?: number) => getData<TerminalEvent[]>("GET", `/terminal/sessions/${id}/events${query({ after: after?.toString() })}`),
};
