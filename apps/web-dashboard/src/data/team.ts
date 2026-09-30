/**
 * 内置 AI 工程团队的四个角色（主规格 §6.1 的内置角色子集）。
 *
 * 前端不持有假的 Agent 列表——这里只是"装配团队"时提交给 POST /api/v1/agents 的
 * 创建参数；真正的 Agent 数据始终来自后端 GET /api/v1/agents。
 */
import type { AgentCreateRequest, Capability } from "../api/types";

export interface BuiltinRole {
  /** Agent 名称 */
  name: string;
  /** AgentRole 枚举值 */
  role: string;
  /** 中文角色名，界面展示用 */
  label: string;
  /** 职责描述 */
  description: string;
  /** 提交的权限子集（Capability） */
  permissions: Capability[];
}

/** 依次创建内置角色时使用的参数顺序：Tech Lead → Developer → Reviewer → Tester */
export const BUILTIN_ROLES: BuiltinRole[] = [
  {
    name: "Tech Lead",
    role: "tech_lead",
    label: "技术负责人",
    description: "架构设计 · 任务分解 · 技术决策",
    permissions: ["file.read"],
  },
  {
    name: "Developer",
    role: "developer",
    label: "开发工程师",
    description: "代码实现 · 单元测试 · 读取代码库",
    permissions: ["file.read", "file.write", "terminal.execute"],
  },
  {
    name: "Reviewer",
    role: "reviewer",
    label: "代码评审",
    description: "代码审查 · 质量监督 · 安全审计",
    permissions: ["file.read"],
  },
  {
    name: "Tester",
    role: "tester",
    label: "测试工程师",
    description: "功能测试 · 集成测试 · 运行测试命令",
    permissions: ["file.read", "terminal.execute"],
  },
];

/** 把内置角色转成创建请求体（统一用本地 echo 模型） */
export function toCreateRequest(role: BuiltinRole): AgentCreateRequest {
  return {
    name: role.name,
    role: role.role,
    model_provider: "local",
    model_name: "local-echo",
    description: role.description,
    permissions: role.permissions,
  };
}

/** AgentRole 枚举值 → 中文标签 */
export const ROLE_LABELS: Record<string, string> = {
  tech_lead: "技术负责人",
  architect: "架构师",
  developer: "开发工程师",
  reviewer: "代码评审",
  tester: "测试工程师",
  devops: "运维发布",
};

/** AgentState 枚举值 → 中文标签 */
export const STATE_LABELS: Record<string, string> = {
  CREATED: "已创建",
  INITIALIZING: "初始化中",
  READY: "就绪",
  RUNNING: "运行中",
  WAITING_TOOL: "等待工具",
  REVIEWING: "审阅中",
  COMPLETED: "已完成",
  FAILED: "失败",
  STOPPED: "已停止",
};