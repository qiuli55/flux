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

/** 提案状态机（主规格 §7.2） */
export type ChangeStatus = "pending" | "accepted" | "rejected" | "applied" | "failed";

/** Virtual Workspace 提案（GET /api/v1/workspace/changes） */
export interface Change {
  id: string;
  project_id: string | null;
  task_id: string | null;
  file_path: string;
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
}

/** POST /api/v1/workspace/generate */
export interface ProposalOutcome {
  summary: string;
  files: string[];
  proposals: Change[];
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

/** Agent 规格（§5.1 三组字段） */
export interface AgentSpec {
  name: string;
  role: string;
  model_provider: string;
  model_name: string;
  description: string;
  skills: string[];
  tools: string[];
  permissions: string[];
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
}