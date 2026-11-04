import * as http from "http";
import * as https from "https";
import { URL } from "url";

export interface FluxProject {
  id: string;
  name?: string;
  /** 后端实际存的是 repository，历史上也有 path / root_path 两种字段 */
  repository?: string;
  path?: string;
  root_path?: string;
  [key: string]: unknown;
}

export interface FluxTask {
  id: string;
  title?: string;
  description?: string;
  status?: string;
  project_id?: string;
  agent_id?: string;
  bot_name?: string | null;
  avatar_shape?: string | null;
  avatar_color?: string | null;
  created_at?: string;
  updated_at?: string;
  [key: string]: unknown;
}

export interface FluxMessage {
  id?: string;
  role: string;
  content: string;
  created_at?: string;
  [key: string]: unknown;
}

export interface FluxChange {
  id: string;
  file_path: string;
  kind: string;
  status: string;
  diff?: string | null;
  original_content?: string | null;
  proposed_content?: string | null;
  added_lines?: number;
  removed_lines?: number;
  hunks?: number;
  reason?: string | null;
  summary?: string | null;
  project_id?: string | null;
  task_id?: string | null;
  apply_error?: string | null;
  [key: string]: unknown;
}

export interface FluxLanguageServer {
  id: string;
  name: string;
  languages: string[];
  command?: string[];
  install?: string[] | null;
  install_hint?: string;
  description?: string;
  homepage?: string;
  available: boolean;
  executable?: string | null;
}

export interface FluxLanguageEntry {
  id: string;
  label: string;
  /** 该语言最终选用的服务器（优先已安装的那个） */
  server: string | null;
  server_name?: string | null;
  available: boolean;
  servers: string[];
}

export interface FluxLanguageCatalog {
  servers: FluxLanguageServer[];
  languages: FluxLanguageEntry[];
  /** 后缀 → languageId */
  extensions: Record<string, string>;
  /** 文件名 → languageId（Dockerfile/CMakeLists.txt 这类） */
  filenames: Record<string, string>;
}

export interface FluxLspSession {
  server: string;
  workspace_root: string;
  clients?: number;
  pid?: number | null;
  [key: string]: unknown;
}

/** 行内补全请求（`POST /models/fim`，AI_COMPLETION_SPEC §5.4）。 */
export interface FluxFimRequest {
  prefix: string;
  suffix: string;
  /** 相对项目根的路径，服务端据此补 L1/L2 上下文 */
  path?: string;
  project_id?: string;
  language_id?: string;
  /** 光标偏移，仅用于服务端切片 */
  caret?: number;
  /** 想覆盖前端截断时可传全文（一般不必） */
  text?: string;
  /** `follow-agent` 或显式模型 / `<keyId>::<model>` */
  model?: string;
  key_id?: string;
  agent_id?: string;
  max_tokens?: number;
  temperature?: number;
  stop?: string[];
  max_lines?: number;
}

export interface FluxActiveAgent {
  agent_id?: string | null;
  model?: string | null;
  key_id?: string | null;
  resolved?: Record<string, unknown>;
}

/** 补全偏好（`GET/PUT /ide/completion/prefs`）。 */
export interface FluxCompletionPrefs extends FluxActiveAgent {
  enabled: boolean;
  model: string;
  debounce_ms: number;
  max_lines: number;
}

/** 三层上下文（`POST /ide/completion/context`）。 */
export interface FluxCompletionContext {
  l0: { text: string; start_line: number; end_line: number; caret_line?: number; language_id?: string };
  l1: { path: string; excerpt: string }[];
  l2: { spec: string; stack: string; recent: string };
  cache_key?: string;
  project_id?: string | null;
}

/** 补全接受埋点（`POST /ide/completion/accepted`，AI_COMPLETION_SPEC §8）。 */
export interface FluxCompletionAccepted {
  accepted: boolean;
  /** 服务端通道（`fim` / `chat`） */
  mode?: string;
  model?: string;
  language_id?: string;
  /** 交互模式（`insert` / `line`） */
  completion_mode?: string;
}

/** 补全埋点聚合（`GET /ide/completion/stats`）。 */
export interface FluxCompletionStatsBucket {
  requests: number;
  successes: number;
  failures: number;
  aborts: number;
  empty: number;
  delivered: number;
  accepted: number;
  acceptance_rate: number;
  avg_first_token_ms: number | null;
  avg_total_ms: number | null;
}

export interface FluxCompletionStats {
  totals: FluxCompletionStatsBucket;
  today: FluxCompletionStatsBucket;
  by_model: Record<string, FluxCompletionStatsBucket>;
  by_mode: Record<string, FluxCompletionStatsBucket>;
  days: Array<{ date: string } & FluxCompletionStatsBucket>;
  last?: Record<string, unknown> | null;
  last_accept?: Record<string, unknown> | null;
  updated_at?: string | null;
}

export class FluxApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body: string
  ) {
    super(message);
    this.name = "FluxApiError";
  }
}

function asList<T>(value: unknown): T[] {
  if (Array.isArray(value)) {
    return value as T[];
  }
  if (value && typeof value === "object") {
    const obj = value as Record<string, unknown>;
    for (const key of ["items", "data", "results", "tasks", "projects", "messages"]) {
      if (Array.isArray(obj[key])) {
        return obj[key] as T[];
      }
    }
  }
  return [];
}

export class FluxClient {
  private readonly base: URL;

  constructor(baseUrl: string, private readonly token: string) {
    this.base = new URL(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...extra,
    };
    if (this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }
    return headers;
  }

  private target(path: string): URL {
    const prefix = "/api/v1";
    return new URL(prefix + path, this.base);
  }

  request<T>(method: string, path: string, body?: unknown, timeoutMs = 30000): Promise<T> {
    const url = this.target(path);
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const transport = url.protocol === "https:" ? https : http;

    return new Promise<T>((resolve, reject) => {
      const req = transport.request(
        url,
        {
          method,
          headers: this.headers(payload ? { "Content-Length": String(Buffer.byteLength(payload)) } : undefined),
          timeout: timeoutMs,
        },
        (res) => {
          const chunks: Buffer[] = [];
          res.on("data", (chunk: Buffer) => chunks.push(chunk));
          res.on("end", () => {
            const text = Buffer.concat(chunks).toString("utf8");
            const status = res.statusCode ?? 0;
            if (status < 200 || status >= 300) {
              reject(new FluxApiError(`Flux API ${method} ${path} -> ${status}`, status, text));
              return;
            }
            if (!text) {
              resolve(undefined as unknown as T);
              return;
            }
            try {
              const parsed = JSON.parse(text) as Record<string, unknown>;
              if (parsed && typeof parsed === "object" && "data" in parsed && ("success" in parsed || "code" in parsed)) {
                resolve(parsed.data as T);
              } else {
                resolve(parsed as unknown as T);
              }
            } catch {
              resolve(text as unknown as T);
            }
          });
        }
      );
      req.on("timeout", () => req.destroy(new Error(`请求超时: ${method} ${path}`)));
      req.on("error", reject);
      if (payload) {
        req.write(payload);
      }
      req.end();
    });
  }

  async health(): Promise<Record<string, unknown>> {
    return this.request<Record<string, unknown>>("GET", "/health", undefined, 5000);
  }

  async projects(): Promise<FluxProject[]> {
    return asList<FluxProject>(await this.request<unknown>("GET", "/projects"));
  }

  async tasks(projectId?: string): Promise<FluxTask[]> {
    const query = new URLSearchParams({ limit: "50" });
    if (projectId) {
      query.set("project_id", projectId);
    }
    return asList<FluxTask>(await this.request<unknown>("GET", `/tasks?${query.toString()}`));
  }

  async task(taskId: string): Promise<FluxTask> {
    return this.request<FluxTask>("GET", `/tasks/${encodeURIComponent(taskId)}`);
  }

  async createTask(input: {
    description: string;
    project_id?: string;
    priority?: number;
    summary?: string;
    bot_name?: string;
    avatar_shape?: string;
    avatar_color?: string;
    start?: boolean;
  }): Promise<FluxTask> {
    return this.request<FluxTask>("POST", "/tasks", input);
  }

  async startTask(taskId: string): Promise<unknown> {
    return this.request<unknown>("POST", `/tasks/${encodeURIComponent(taskId)}/start`, {});
  }

  async cancelTask(taskId: string): Promise<unknown> {
    return this.request<unknown>("POST", `/tasks/${encodeURIComponent(taskId)}/cancel`, {});
  }

  async messages(taskId: string): Promise<FluxMessage[]> {
    return asList<FluxMessage>(await this.request<unknown>("GET", `/tasks/${encodeURIComponent(taskId)}/messages`));
  }

  async sendMessage(taskId: string, content: string): Promise<unknown> {
    return this.request<unknown>("POST", `/tasks/${encodeURIComponent(taskId)}/messages`, { content });
  }

  /** 终态对话续跑：新消息作为本轮 Agent 指令，重开同一个对话（历史与归属都保留）。 */
  async continueTask(taskId: string, content: string): Promise<unknown> {
    return this.request<unknown>("POST", `/tasks/${encodeURIComponent(taskId)}/continue`, { content });
  }

  async changes(status?: string, projectId?: string): Promise<FluxChange[]> {
    const query = new URLSearchParams();
    if (status) {
      query.set("status", status);
    }
    if (projectId) {
      query.set("project_id", projectId);
    }
    const suffix = query.toString() ? `?${query.toString()}` : "";
    return asList<FluxChange>(await this.request<unknown>("GET", `/workspace/changes${suffix}`));
  }

  async change(changeId: string): Promise<FluxChange> {
    return this.request<FluxChange>("GET", `/workspace/changes/${encodeURIComponent(changeId)}`);
  }

  async acceptChanges(changeIds: string[]): Promise<unknown> {
    return this.request<unknown>("POST", "/workspace/accept", { change_ids: changeIds });
  }

  async rejectChanges(changeIds: string[], reason?: string): Promise<unknown> {
    const body: Record<string, unknown> = { change_ids: changeIds };
    if (reason) {
      body.reason = reason;
    }
    return this.request<unknown>("POST", "/workspace/reject", body);
  }

  async applyChanges(changeIds: string[]): Promise<unknown> {
    return this.request<unknown>("POST", "/workspace/apply", { change_ids: changeIds });
  }

  async rollbackChange(changeId: string): Promise<unknown> {
    return this.request<unknown>("POST", `/workspace/changes/${encodeURIComponent(changeId)}/rollback`, {});
  }

  /** 订阅任务 SSE 事件流；回调收到每条 data 负载的原始字符串。`afterSeq` 用于断线续接，避免回放历史。 */
  streamTask(taskId: string, onEvent: (payload: string) => void, signal: AbortSignal, afterSeq = 0): Promise<void> {
    const query = afterSeq > 0 ? `?after_seq=${afterSeq}` : "";
    const url = this.target(`/tasks/${encodeURIComponent(taskId)}/stream${query}`);
    const transport = url.protocol === "https:" ? https : http;

    return new Promise<void>((resolve, reject) => {
      const req = transport.request(
        url,
        {
          method: "GET",
          headers: this.headers({ Accept: "text/event-stream" }),
          timeout: 0,
        },
        (res) => {
          const status = res.statusCode ?? 0;
          if (status < 200 || status >= 300) {
            reject(new FluxApiError(`事件流打开失败 -> ${status}`, status, ""));
            return;
          }
          let buffer = "";
          let data: string[] = [];
          res.on("data", (chunk: Buffer) => {
            buffer += chunk.toString("utf8");
            const lines = buffer.split(/\r?\n/);
            buffer = lines.pop() ?? "";
            for (const line of lines) {
              if (line === "") {
                if (data.length) {
                  onEvent(data.join("\n"));
                  data = [];
                }
              } else if (line.startsWith("data:")) {
                data.push(line.slice(5).trimStart());
              }
            }
          });
          res.on("end", () => {
            if (data.length) {
              onEvent(data.join("\n"));
            }
            resolve();
          });
          res.on("error", reject);
        }
      );
      signal.addEventListener("abort", () => {
        req.destroy();
        resolve();
      });
      req.on("error", (err) => {
        if (!signal.aborted) {
          reject(err);
        }
      });
      req.end();
    });
  }

  /**
   * 行内补全流（`POST /models/fim`，AI_COMPLETION_SPEC §5.4）。
   *
   * 服务端按 `event: delta|done|error` 推送命名事件；`onEvent` 逐条回调，
   * 流正常结束 resolve，打开失败 / 传输故障 reject，主动 abort 时静默 resolve。
   */
  streamFim(
    request: FluxFimRequest,
    onEvent: (event: string, data: Record<string, unknown>) => void,
    signal: AbortSignal
  ): Promise<void> {
    const url = this.target("/models/fim");
    const payload = JSON.stringify(request);
    const transport = url.protocol === "https:" ? https : http;

    return new Promise<void>((resolve, reject) => {
      const req = transport.request(
        url,
        {
          method: "POST",
          headers: this.headers({
            Accept: "text/event-stream",
            "Content-Length": String(Buffer.byteLength(payload)),
          }),
          timeout: 0,
        },
        (res) => {
          const status = res.statusCode ?? 0;
          if (status < 200 || status >= 300) {
            const chunks: Buffer[] = [];
            res.on("data", (chunk: Buffer) => chunks.push(chunk));
            res.on("end", () =>
              reject(new FluxApiError(`补全请求失败 -> ${status}`, status, Buffer.concat(chunks).toString("utf8")))
            );
            res.on("error", reject);
            return;
          }

          let buffer = "";
          let event = "";
          let data: string[] = [];
          const flush = () => {
            if (!data.length) {
              event = "";
              return;
            }
            const raw = data.join("\n");
            const name = event || "message";
            data = [];
            event = "";
            let parsed: Record<string, unknown>;
            try {
              const value = JSON.parse(raw) as unknown;
              parsed = value && typeof value === "object" ? (value as Record<string, unknown>) : { value };
            } catch {
              parsed = { text: raw };
            }
            onEvent(name, parsed);
          };

          res.on("data", (chunk: Buffer) => {
            buffer += chunk.toString("utf8");
            const lines = buffer.split(/\r?\n/);
            buffer = lines.pop() ?? "";
            for (const line of lines) {
              if (line === "") {
                flush();
              } else if (line.startsWith("event:")) {
                event = line.slice(6).trim();
              } else if (line.startsWith("data:")) {
                data.push(line.slice(5).trimStart());
              }
            }
          });
          res.on("end", () => {
            flush();
            resolve();
          });
          res.on("error", reject);
        }
      );
      signal.addEventListener("abort", () => {
        req.destroy();
        resolve();
      });
      req.on("error", (err) => {
        if (!signal.aborted) {
          reject(err);
        }
      });
      req.write(payload);
      req.end();
    });
  }

  /** 补全偏好 + 当前 Agent（`GET /ide/completion/prefs`）。 */
  async completionPrefs(): Promise<FluxCompletionPrefs> {
    return this.request<FluxCompletionPrefs>("GET", "/ide/completion/prefs", undefined, 10000);
  }

  /** 写入补全偏好，返回落盘后的完整快照。 */
  async putCompletionPrefs(prefs: Partial<FluxCompletionPrefs>): Promise<FluxCompletionPrefs> {
    const body: Record<string, unknown> = {};
    if (prefs.enabled !== undefined) body.enabled = prefs.enabled;
    if (prefs.model !== undefined) body.model = prefs.model;
    if (prefs.debounce_ms !== undefined) body.debounce_ms = prefs.debounce_ms;
    if (prefs.max_lines !== undefined) body.max_lines = prefs.max_lines;
    return this.request<FluxCompletionPrefs>("PUT", "/ide/completion/prefs", body, 10000);
  }

  /** 补全接受埋点：只在计数，不携带任何代码内容（AI_COMPLETION_SPEC §8）。 */
  async completionAccepted(payload: FluxCompletionAccepted): Promise<{ accepted: boolean }> {
    const body: Record<string, unknown> = { accepted: payload.accepted };
    if (payload.mode !== undefined) body.mode = payload.mode;
    if (payload.model !== undefined) body.model = payload.model;
    if (payload.language_id !== undefined) body.language_id = payload.language_id;
    return this.request<{ accepted: boolean }>("POST", "/ide/completion/accepted", body, 5000);
  }

  /** 补全埋点汇总（接受率 / 首字延迟 / 空结果占比）。 */
  async completionStats(): Promise<FluxCompletionStats> {
    return this.request<FluxCompletionStats>("GET", "/ide/completion/stats", undefined, 10000);
  }

  /** 拉取服务端构建的三层上下文（调试 / 状态栏展示用）。 */
  async completionContext(payload: {
    path: string;
    project_id?: string;
    caret?: number;
    text?: string;
    language_id?: string;
  }): Promise<FluxCompletionContext> {
    return this.request<FluxCompletionContext>("POST", "/ide/completion/context", payload, 20000);
  }

  /** 当前生效的 Agent / 模型（`GET /agents/active`）。 */
  async activeAgent(): Promise<FluxActiveAgent> {
    return this.request<FluxActiveAgent>("GET", "/agents/active", undefined, 10000);
  }

  /** 切换当前生效的 Agent / 模型（`PUT /agents/active`）。 */
  async putActiveAgent(selection: FluxActiveAgent): Promise<FluxActiveAgent> {
    const body: Record<string, unknown> = {};
    if (selection.agent_id !== undefined) body.agent_id = selection.agent_id;
    if (selection.model !== undefined) body.model = selection.model;
    if (selection.key_id !== undefined) body.key_id = selection.key_id;
    return this.request<FluxActiveAgent>("PUT", "/agents/active", body, 10000);
  }

  /** 后端基地址（不含 /api/v1），拼 LSP 的 ws:// 地址时用。 */
  get baseUrl(): string {
    return this.base.toString().replace(/\/$/, "");
  }

  get authToken(): string {
    return this.token;
  }

  /** 语言服务器清单 + 语言目录（含是否已安装）。 */
  async ideLanguages(): Promise<FluxLanguageCatalog> {
    return this.request<FluxLanguageCatalog>("GET", "/ide/languages", undefined, 20000);
  }

  /** 当前常驻的语言服务器进程（排查「为什么没补全」）。 */
  async ideSessions(): Promise<FluxLspSession[]> {
    return asList<FluxLspSession>(await this.request<unknown>("GET", "/ide/sessions"));
  }

  /** 停掉常驻语言服务器；下次打开文件会重新拉起。 */
  async stopIdeSession(server: string, workspaceRoot?: string): Promise<unknown> {
    const query = new URLSearchParams({ server });
    if (workspaceRoot) {
      query.set("workspace_root", workspaceRoot);
    }
    return this.request<unknown>("POST", `/ide/sessions/stop?${query.toString()}`, {});
  }

  /** 按注册表里写死的命令安装语言服务器（仅本地后端可用）。 */
  async installLanguageServer(serverId: string): Promise<unknown> {
    return this.request<unknown>("POST", `/ide/languages/${encodeURIComponent(serverId)}/install`, {}, 300000);
  }
}
