/**
 * 后端统一响应体与各接口的响应类型。
 *
 * 所有取值均对齐冻结的 docs/openapi.json 与后端 `to_dict()`：
 * 统一响应体为 {success, code, message, data, metadata}（主规格 §12.10 裁决 A6）。
 */

/** 后端统一响应体。 */
export interface Envelope<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  metadata?: Record<string, unknown>;
}

/** GET /api/v1/health */
export interface HealthData {
  status: string;
  app: string;
  env: string;
}

/** GET /api/v1/health/ready */
export interface ReadyData {
  database: boolean;
  providers: string[];
  agents: number;
}

/** 项目实体（GET /api/v1/projects） */
export interface Project {
  id: string;
  name: string;
  repository: string | null;
  metadata: Record<string, unknown>;
  created_at: string | null;
  updated_at: string | null;
}

/** Project Scanner 产出的项目画像（scan 响应中的 profile） */
export interface ProjectProfile {
  root: string;
  languages: string[];
  primary_language: string | null;
  frameworks: string[];
  package_manager: string | null;
  manifests: string[];
  entry_points: string[];
  test_commands: string[];
  build_commands: string[];
  git_repository: boolean;
  git_branch: string | null;
  structure: string[];
  files_scanned: number;
  truncated: boolean;
}

/** Project Brain 记忆条目 */
export interface MemoryEntry {
  id: string;
  project_id: string;
  section: string;
  content: string;
  metadata: Record<string, unknown>;
  created_at: string | null;
  updated_at: string | null;
}

/** POST /api/v1/projects/{id}/scan */
export interface ScanOutcome {
  profile: ProjectProfile;
  recorded: MemoryEntry[];
}

/**
 * 工作区文件树的一个条目（GET /api/v1/projects/{id}/files）。
 *
 * `path` 是相对工作区根的 POSIX 路径；目录的 size 恒为 null。
 * 该工作区根由后端 FLUX_WORKSPACE_ROOT 决定，与 project_id 无关
 * （project_id 只做存在性校验）。
 */
export interface FileEntry {
  path: string;
  name: string;
  kind: "dir" | "file";
  size: number | null;
  modified_at: string;
}

/** GET /api/v1/projects/{id}/files 的 data（metadata.count 为条目数） */
export interface FileTree {
  root: string;
  path: string;
  entries: FileEntry[];
  truncated: boolean;
}

/** GET /api/v1/projects/{id}/files/content 的 data：size 是文件真实字节数 */
export interface SearchHit {
  path: string;
  line: number;
  column: number;
  text: string;
}
export interface FileContent {
  path: string;
  content: string;
  size: number;
  truncated: boolean;
}

/** 提案状态机（主规格 §7.2；expired 为文档 P0-02 的失效终态；rolled_back 为 P1-2 回滚终态） */
export type ChangeStatus =
  | "pending"
  | "accepted"
  | "rejected"
  | "applied"
  | "failed"
  | "expired"
  | "rolled_back";

/** 变更语义（P1-1）：create 新建 / modify 修改 / delete 删除 */
export type ChangeKind = "create" | "modify" | "delete";

/** Virtual Workspace 提案（GET /api/v1/workspace/changes） */
export interface Change {
  id: string;
  project_id: string | null;
  task_id: string | null;
  group_id: string | null;
  file_path: string;
  /** 变更语义：delete 时 proposed_content 为 null，diff 为整文件移除 */
  kind: ChangeKind;
  original_hash: string;
  original_content: string;
  proposed_content: string;
  diff: string;
  added_lines: number;
  removed_lines: number;
  hunks: number;
  reason: string | null;
  summary: string | null;
  agent_source: string | null;
  status: ChangeStatus;
  backup_path: string | null;
  apply_error: string | null;
  expires_at: string | null;
  expired_reason: string | null;
  /** 崩溃恢复挂起项的人工决策结果：null = 待决策，cover / keep = 已处理 */
  recovery_resolution: string | null;
}

/** 挂起项的磁盘状态：modified 内容被外部改动 / deleted 文件被外部删除 / not_file 路径不是普通文件 / unknown 看不了盘 */
export type RecoveryDiskState = "modified" | "deleted" | "not_file" | "unknown";

/**
 * 待人工决策的崩溃恢复项（P0-1 §2.2）：
 * 崩溃后目标内容既非原文也非提案内容（或文件被外部删除）时不再自动处理，转为人工决策。
 */
export interface RecoveryItem {
  change_id: string;
  batch_id: string;
  file_path: string;
  /** 备份里的改动前原文 */
  original_content: string;
  /** 当前磁盘真实内容；文件不存在或非文本时为 null */
  disk_content: string | null;
  /** Flux 原本要写入的提案内容 */
  proposed_content: string;
  /** 备份是否可用：false 时不能选「覆盖备份」 */
  backup_available: boolean;
  disk_state: RecoveryDiskState;
  /** 触发挂起的原因说明 */
  note: string;
  detected_at: string | null;
}

/** Apply 批状态（P0-1 事务日志 / P1-2 回滚） */
export type ApplyBatchStatus =
  | "in_progress"
  | "applied"
  | "failed"
  | "recovered"
  | "needs_attention"
  | "rolled_back";

/** 一次 Apply（apply_many 调用）的事务日志（GET /api/v1/workspace/apply-batches） */
export interface ApplyBatch {
  id: string;
  status: ApplyBatchStatus;
  /** 本批包含的 change id（有序），回滚按逆序执行 */
  change_ids: string[];
  phase: string;
  backup_root: string | null;
  error: string | null;
  recovery_note: string | null;
  created_at: string | null;
  finished_at: string | null;
}

/** 单个文件的 Git 状态（git status --porcelain 的一行） */
export interface GitFileStatus {
  path: string;
  index_status: string;
  worktree_status: string;
  original_path: string | null;
  staged: boolean;
  untracked: boolean;
}

/** GET /api/v1/git/status */
export interface GitStatus {
  branch: string | null;
  detached: boolean;
  clean: boolean;
  files: GitFileStatus[];
}

/** POST /api/v1/git/commit */
export interface GitCommit {
  sha: string;
  short_sha: string;
  message: string;
  files: string[];
}

/** Agent 规格（§5.1 三组字段 + 执行它的 runtime） */
export interface AgentSpec {
  name: string;
  role: string;
  model_provider: string;
  model_name: string;
  description: string;
  skills: string[];
  tools: string[];
  permissions: string[];
  /** 执行该 Agent 的 runtime：dsh（内置，缺省）/ codex / opencode（P2-1 §6.2） */
  runtime?: string;
}

/** GET /api/v1/agents 返回的 Agent 句柄 */
export interface AgentHandle {
  id: string;
  state: string;
  execution_count: number;
  last_error: string | null;
  spec: AgentSpec;
  task_id: string | null;
}

/** Capability 权限项（主规格 §5.5） */
export type Capability = "file.read" | "file.write" | "terminal.execute";

/** POST /api/v1/agents 的请求体 */
export interface AgentCreateRequest {
  name: string;
  role: string;
  model_provider: string;
  model_name: string;
  description: string;
  permissions: Capability[];
  /** 缺省 dsh；接入 codex/opencode 后可用它让该 Agent 走对应 CLI（P2-1 §6.2） */
  runtime?: string;
}

/**
 * 本机 CLI Agent 的安装与接入事实（GET /api/v1/installations）。
 *
 * 与 Agent 档案分离：这里是"这台机器上装了什么、接没接进来"，
 * 档案（AgentHandle）是"是谁、能做什么"。
 */
export interface Installation {
  id: string;
  /** adapter 名（codex / opencode） */
  name: string;
  adapter: string;
  /** NOT_INSTALLED / DISCOVERED / VERIFIED / CONNECTED / READY */
  status: string;
  executable: string | null;
  path: string | null;
  version: string | null;
  source: string;
  /** ok / missing / unknown */
  auth_status: string;
  capabilities: string[];
  detail: Record<string, unknown>;
}

/**
 * 任务状态机（主规格 §5.1 + 文档 §5）：
 * pending → running → 终态 completed / failed / cancelled；
 * manual 决策模式下运行中撞上决策点会停在 waiting_for_user_decision（用户选完回到 running）。
 */
export type TaskStatus =
  | "pending"
  | "running"
  | "waiting_for_user_decision"
  | "completed"
  | "failed"
  | "cancelled";

/** 任务级决策策略（文档 §5）：auto = AI 按默认方案自决，manual = 遇决策点停下等用户 */
export type DecisionMode = "auto" | "manual";

/** 需求确认的一项：六个固定维度之一（文档 P0-06） */
export interface ConfirmationItem {
  label: string;
  value: string;
}

/** 任务上保存的需求确认卡（items 恒为六维度，actor 记录最后一版是谁定的） */
export interface TaskConfirmation {
  items: ConfirmationItem[];
  actor: "assistant" | "user";
  updated_at: string;
}

/** 一个候选方案 */
export interface DecisionOption {
  label: string;
  description: string | null;
  impact: string | null;
  recommended: boolean;
}

/** 决策点状态（文档 §5） */
export type DecisionStatus = "pending" | "auto_resolved" | "resolved" | "rejected";

/** 一条决策点记录（tasks.decisions 的元素，也是 pending_decision 的结构） */
export interface DecisionRecord {
  id: string;
  question: string;
  context: string | null;
  options: DecisionOption[];
  recommendation: string | null;
  mode: DecisionMode;
  status: DecisionStatus;
  chosen: string | null;
  note: string | null;
  raised_at: string;
  resolved_at: string | null;
}

/** 任务实体（M0 冻结 7 键 + created_at + Solo 生命周期字段） */
export interface Task {
  id: string;
  description: string;
  status: TaskStatus;
  priority: number;
  agent_id: string | null;
  project_id: string | null;
  result: string | null;
  created_at: string;
  decision_mode: DecisionMode;
  confirmation: TaskConfirmation | null;
  /** 决策点历史（含已决与待决） */
  decisions: DecisionRecord[];
  /** 当前正等用户选择的那条决策点；没有则为 null */
  pending_decision: DecisionRecord | null;
  /** 该任务触发的 DSH Run；未开始执行为 null */
  run_id: string | null;
}

/** 助手回复里落库的结构化负载（模型元信息 / 待补充问题 / 需求确认 / 执行计划） */
export interface TaskMessagePayload {
  model?: {
    provider: string;
    model: string;
    usage: Record<string, number>;
    latency_ms: number;
  };
  /** 仍需用户补充的问题（模型没给出时该键不落库） */
  questions?: string[];
  /** 需求确认六维度（模型没给出时该键不落库） */
  confirmation?: ConfirmationItem[];
  /** 历史数据里的旧字段，保留兼容渲染 */
  clarify?: { label: string; value: string }[];
  steps?: string[];
}

/** 任务对话消息（GET /api/v1/tasks/{id}/messages） */
export interface TaskMessage {
  id: string;
  task_id: string;
  seq: number;
  role: "user" | "assistant";
  kind: string;
  content: string;
  payload: TaskMessagePayload | null;
  created_at: string;
}

/** 一段消息 + 是否还有更早的（分页游标是任务内自增的 seq） */
export interface TaskMessagePage {
  items: TaskMessage[];
  hasMore: boolean;
}

/** POST /api/v1/tasks/{id}/messages 的 data */
export interface TaskReplyOutcome {
  task: Task;
  messages: TaskMessage[];
}

/** PUT /api/v1/tasks/{id}/confirmation 的 data（确认卡刷新 + 一条 kind=confirmation 的用户消息） */
export interface TaskConfirmationOutcome {
  task: Task;
  message: TaskMessage;
}

/** POST /api/v1/tasks/{id}/start 的 data（DSH Run 摘要用 run_id/status 即可展示） */
export interface TaskStartOutcome {
  task: Task;
  run: { run_id: string; status: string };
  message: TaskMessage;
}

/** POST /api/v1/tasks/{id}/decisions/choose 的 data */
export interface DecisionOutcome {
  task: Task;
  decision: DecisionRecord;
  message: TaskMessage;
}

/* ---------- Agent Terminal Console（doc/AGENT_TERMINAL_CONSOLE.md） ---------- */

/** 终端会话状态（terminal_sessions.status） */
export type TerminalSessionStatus = "active" | "stopped" | "closed";

/** 命令来源：AI 还是 USER（§5 要求两者在输出中必须明确区分） */
export type TerminalSourceKind = "ai" | "user";

/** 终端事件类型（§8 事件模型，取值同后端 TerminalEventKind） */
export type TerminalEventKind =
  | "terminal.session.created"
  | "terminal.command.started"
  | "terminal.output"
  | "terminal.command.finished"
  | "terminal.command.failed"
  | "terminal.stop.requested"
  | "terminal.process.terminated"
  | "terminal.session.closed";

/** GET /api/v1/terminal/sessions */
export type TerminalSessionKind = "agent" | "human";

export interface TerminalSession {
  id: string;
  run_id: string | null;
  workspace_root: string;
  kind: TerminalSessionKind;
  status: TerminalSessionStatus;
  next_seq: number;
  created_at: string | null;
  finished_at: string | null;
}

/** 终端事件：SSE 帧 data 与 GET /events 的元素同构 */
export interface TerminalEvent {
  id: string;
  session_id: string;
  seq: number;
  kind: TerminalEventKind;
  source: TerminalSourceKind;
  command: string | null;
  chunk: string | null;
  exit_code: number | null;
  created_at: string | null;
}
