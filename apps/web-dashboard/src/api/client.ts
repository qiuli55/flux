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
  Envelope,
  GitCommit,
  GitStatus,
  HealthData,
  Project,
  ProposalOutcome,
  ReadyData,
  ScanOutcome,
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
    throw new ApiError("无法连接后端服务，请确认 8010 端口的 Flux 后端已启动", "network_error", 0);
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

  /** 变更列表（可按状态过滤） */
  listChanges: (status?: string) => getData<Change[]>("GET", `/workspace/changes${query({ status })}`),
  /** 变更详情 */
  getChange: (changeId: string) => getData<Change>("GET", `/workspace/changes/${changeId}`),
  /** 让 AI 改：一句话需求 → pending 提案 */
  generate: (body: { instruction: string; paths: string[]; task_id: string | null; project_id: string | null }) =>
    getData<ProposalOutcome>("POST", "/workspace/generate", body),
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
};