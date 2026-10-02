/**
 * API 客户端：只发同源 /api 请求（Vite 已把 /api 代理到 127.0.0.1:8010）。
 *
 * 统一处理 {success, code, message, data, metadata} 信封：
 * - success=false 或 HTTP 非 2xx → 抛 ApiError（message 优先取后端原文）；
 * - 422 校验失败 → 提取 FastAPI 的 details（字段级），拼成可读文本。
 */
import type {
  AgentCreateRequest,
  AgentHandle,
  Change,
  ConfirmationItem,
  DecisionMode,
  DecisionOutcome,
  Envelope,
  FileContent,
  FileTree,
  GitCommit,
  GitStatus,
  HealthData,
  Project,
  ReadyData,
  ScanOutcome,
  Task,
  TaskConfirmationOutcome,
  TaskMessage,
  TaskMessagePage,
  TaskReplyOutcome,
  TaskStartOutcome,
} from "./types";

/** API 基址：允许用 VITE_API_BASE 覆盖，默认同源 /api/v1 */
export const API_BASE = (import.meta.env.VITE_API_BASE ?? "/api/v1").replace(/\/$/, "");

/** 请求失败时抛出的错误，message 直接面向用户展示 */
export class ApiError extends Error {
  /** 后端错误码（如 validation_error / provider_not_configured / git_failed） */
  readonly code: string;
  /** HTTP 状态码 */
  readonly status: number;
  /** 后端附加详情（details 字段） */
  readonly details: unknown;

  constructor(message: string, code: string, status: number, details?: unknown) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

/** 把 FastAPI 校验错误 details 渲染成可读文本 */
function formatValidationDetails(details: unknown): string | null {
  if (!Array.isArray(details) || details.length === 0) return null;
  const lines = details
    .map((item) => {
      if (typeof item !== "object" || item === null) return null;
      const entry = item as { loc?: unknown; msg?: unknown };
      const loc = Array.isArray(entry.loc) ? entry.loc.slice(1).join(".") : "";
      const msg = typeof entry.msg === "string" ? entry.msg : "";
      if (!msg) return null;
      return loc ? `字段 ${loc}：${msg}` : msg;
    })
    .filter((line): line is string => line !== null);
  return lines.length > 0 ? lines.join("；") : null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<Envelope<T>> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError("无法连接后端服务，请确认 Flux 后端已启动", "network_error", 0);
  }

  let envelope: Envelope<T> | null = null;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    // 非 JSON 响应（如反向代理的 502 页面），兜底展示状态码
    throw new ApiError(`后端返回了无法解析的响应（HTTP ${response.status}）`, "bad_response", response.status);
  }

  if (!response.ok || envelope.success === false) {
    const code = envelope.code || "unknown_error";
    let message = envelope.message || `请求失败（HTTP ${response.status}）`;
    if (code === "validation_error") {
      const fieldText = formatValidationDetails(envelope.data);
      if (fieldText) message = `${message}：${fieldText}`;
    }
    throw new ApiError(message, code, response.status, envelope.data);
  }
  return envelope;
}

/** 只取 data 字段的便捷封装 */
async function getData<T>(method: string, path: string, body?: unknown): Promise<T> {
  const envelope = await request<T>(method, path, body);
  return envelope.data;
}

/** 拼接查询串（跳过空值） */
function query(params: Record<string, string | undefined>): string {
  const parts = Object.entries(params)
    .filter((entry): entry is [string, string] => entry[1] !== undefined && entry[1] !== "")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length > 0 ? `?${parts.join("&")}` : "";
}

export const api = {
  /** 存活探针 */
  health: () => getData<HealthData>("GET", "/health"),
  /** 就绪探针：数据库连通 + 可用供应商 */
  ready: () => getData<ReadyData>("GET", "/health/ready"),

  /** 项目列表 */
  listProjects: () => getData<Project[]>("GET", "/projects"),
  /** 登记项目 */
  createProject: (body: { name: string; repository: string | null }) =>
    getData<Project>("POST", "/projects", body),
  /** 扫描项目工作区（workspace_root 留空用服务端配置） */
  scanProject: (projectId: string) =>
    getData<ScanOutcome>("POST", `/projects/${projectId}/scan`, { record: true }),

  /**
   * 列出工作区文件树（只读）。project_id 仅做存在性校验，工作区根由后端配置决定，
   * 因此缺省 path 是「工作区根」而不是「该项目的仓库」。
   */
  listFiles: (projectId: string, options?: { path?: string; depth?: number }) =>
    getData<FileTree>(
      "GET",
      `/projects/${projectId}/files${query({ path: options?.path, depth: options?.depth?.toString() })}`,
    ),
  /** 读单个文件（只读）；越界/二进制/非 UTF-8 由后端 422 拒绝，错误原文直接展示 */
  readFile: (projectId: string, path: string) =>
    getData<FileContent>("GET", `/projects/${projectId}/files/content${query({ path })}`),

  /** 变更列表（可按状态过滤） */
  listChanges: (status?: string) => getData<Change[]>("GET", `/workspace/changes${query({ status })}`),
  /** 变更详情 */
  getChange: (changeId: string) => getData<Change>("GET", `/workspace/changes/${changeId}`),
  /** 批准（不落盘） */
  accept: (changeIds: string[]) => getData<Change[]>("POST", "/workspace/accept", { change_ids: changeIds }),
  /** 批准并落盘（会跑项目测试） */
  apply: (changeIds: string[]) => getData<Change[]>("POST", "/workspace/apply", { change_ids: changeIds }),
  /** 拒绝（可带理由） */
  reject: (changeIds: string[], reason: string | null) =>
    getData<Change[]>("POST", "/workspace/reject", { change_ids: changeIds, reason }),

  /** Git 状态：分支 + 改动文件 */
  gitStatus: () => getData<GitStatus>("GET", "/git/status"),
  /** 提交（只提交 applied 状态的变更，后端强制） */
  gitCommit: (body: { message: string; change_ids: string[] }) =>
    getData<GitCommit>("POST", "/git/commit", body),

  /** Agent 列表（只列用户创建过的 Agent） */
  listAgents: () => getData<AgentHandle[]>("GET", "/agents"),
  /** 创建 Agent */
  createAgent: (body: AgentCreateRequest) => getData<AgentHandle>("POST", "/agents", body),

  /** 任务列表（任务执行中心左列/任务切换用），默认按创建时间倒序 */
  listTasks: (options?: { projectId?: string; status?: string; limit?: number }) =>
    getData<Task[]>(
      "GET",
      `/tasks${query({
        project_id: options?.projectId,
        status: options?.status,
        limit: options?.limit?.toString(),
      })}`,
    ),
  /** 新建任务（描述即需求；decision_mode 缺省时后端用 auto） */
  createTask: (body: {
    description: string;
    project_id: string | null;
    decision_mode?: DecisionMode;
  }) => getData<Task>("POST", "/tasks", body),
  /** 任务详情 */
  getTask: (taskId: string) => getData<Task>("GET", `/tasks/${taskId}`),
  /** 任务消息（before 为向上加载更早消息的 seq 游标；metadata 里的 has_more 一并返回） */
  async listTaskMessages(
    taskId: string,
    options?: { limit?: number; before?: number },
  ): Promise<TaskMessagePage> {
    const envelope = await request<TaskMessage[]>(
      "GET",
      `/tasks/${taskId}/messages${query({
        limit: options?.limit?.toString(),
        before: options?.before?.toString(),
      })}`,
    );
    const hasMore = envelope.metadata?.has_more === true;
    return { items: envelope.data, hasMore };
  },
  /** 发一条消息并取回助手回复（用户消息与助手回复都已落库） */
  sendTaskMessage: (taskId: string, content: string) =>
    getData<TaskReplyOutcome>("POST", `/tasks/${taskId}/messages`, { content }),
  /** 用户修改需求确认（P0-06）：必须传齐六个维度，服务端 fail-closed 校验 */
  updateTaskConfirmation: (taskId: string, items: ConfirmationItem[]) =>
    getData<TaskConfirmationOutcome>("PUT", `/tasks/${taskId}/confirmation`, { items }),
  /** 切换任务级决策策略（文档 §5） */
  setDecisionMode: (taskId: string, mode: DecisionMode) =>
    getData<Task>("POST", `/tasks/${taskId}/decision-mode`, { mode }),
  /**
   * 开始执行（P0-05）：可选随请求提交用户改后的确认卡，留空则用任务上已保存的那份。
   * DSH 未启用时后端返回 503，不会假装开始执行。
   */
  startTask: (taskId: string, confirmation?: ConfirmationItem[]) =>
    getData<TaskStartOutcome>(
      "POST",
      `/tasks/${taskId}/start`,
      confirmation ? { confirmation } : {},
    ),
  /** 对挂起的决策点做选择：choose 需给 option，reject 表示全部候选都不接受 */
  chooseDecision: (
    taskId: string,
    body: { decision_id: string; action: "choose" | "reject"; option?: string; note?: string },
  ) => getData<DecisionOutcome>("POST", `/tasks/${taskId}/decisions/choose`, body),
  /** 取消任务 */
  cancelTask: (taskId: string) => getData<Task>("POST", `/tasks/${taskId}/cancel`),
};
