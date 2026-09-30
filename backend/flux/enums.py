"""Flux 全局枚举（主规格 §3 术语 / §5.1 生命周期 / §5.5 权限 / §11 状态）。"""

from __future__ import annotations

from enum import Enum


class StrEnum(str, Enum):
    """Python 3.10 兼容的字符串枚举基类（3.11 起可用 enum.StrEnum）。"""

    def __str__(self) -> str:  # pragma: no cover - 仅用于日志与序列化
        return str(self.value)


class AgentState(StrEnum):
    """Agent 生命周期状态机（主规格 §5.1）。"""

    CREATED = "CREATED"
    INITIALIZING = "INITIALIZING"
    READY = "READY"
    RUNNING = "RUNNING"
    WAITING_TOOL = "WAITING_TOOL"
    REVIEWING = "REVIEWING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    # 源状态机未定义停止态，但 Code Implementation Specification 要求 Agent 接口提供 stop()，
    # 故补 STOPPED（主规格附录 A 裁决 A15）。
    STOPPED = "STOPPED"


class TaskStatus(StrEnum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class VirtualChangeStatus(StrEnum):
    """Virtual Workspace 文件状态机（主规格 §7.2）。

    FAILED 用于 Apply 阶段：补丁打不上或校验不通过时留下终态记录（实施计划 §5 状态列表），
    不允许从 FAILED 回到任何可执行状态——失败原因必须由人重新生成提案。
    """

    PENDING = "pending"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    APPLIED = "applied"
    FAILED = "failed"


class BrainSection(StrEnum):
    """Project Brain 的记忆分区（主规格 §5.6；实施计划 ⑪）。

    前四个是"项目现状"型分区，每区只保留一份最新内容（重新写入即覆盖）；
    后两个是"累积"型分区，每次写入都新增一条，历史不可被覆盖。
    """

    OVERVIEW = "overview"
    TECH_STACK = "tech_stack"
    ARCHITECTURE = "architecture"
    CODING_RULES = "coding_rules"
    DECISIONS = "decisions"
    AGENT_NOTES = "agent_notes"


#: 单例分区（写入即覆盖当前内容）
SINGLETON_BRAIN_SECTIONS = frozenset(
    {
        BrainSection.OVERVIEW,
        BrainSection.TECH_STACK,
        BrainSection.ARCHITECTURE,
        BrainSection.CODING_RULES,
    }
)


class MessageType(StrEnum):
    """Agent 通信协议消息类型（主规格 §12.8）。"""

    REQUEST = "REQUEST"
    RESPONSE = "RESPONSE"
    EVENT = "EVENT"
    ERROR = "ERROR"
    HANDOFF = "HANDOFF"


class DshRunStatus(StrEnum):
    """DSH Agent Run 状态机（集成方案 §18 Phase 1）。"""

    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class Capability(StrEnum):
    """Capability 权限项（主规格 §5.5 / §8.4）。"""

    FILE_READ = "file.read"
    FILE_WRITE = "file.write"
    TERMINAL_EXECUTE = "terminal.execute"
    GITHUB_CREATE_PR = "github.create_pr"
    BROWSER_OPEN = "browser.open"
    DEPLOY = "deploy"
    SECRET_ACCESS = "secret.access"
    DEVICE_COMMAND = "device.command"


class Role(StrEnum):
    """企业角色（主规格 §5.5）。"""

    ADMIN = "admin"
    DEVELOPER = "developer"
    REVIEWER = "reviewer"
    OBSERVER = "observer"


class ModelProvider(StrEnum):
    """模型供应商（主规格 §5.3 裁决 A4）。

    只服务于 Flux 侧基础设施的模型调用（T2 压缩、扫描摘要等）——
    Agent 用什么模型是 Agent 自己的事，不经本枚举（目标架构 §1）。
    """

    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    DEEPSEEK = "deepseek"
    LOCAL = "local"


class AgentRole(StrEnum):
    """六个内置 Agent 角色（主规格 §6.1）。"""

    TECH_LEAD = "tech_lead"
    ARCHITECT = "architect"
    DEVELOPER = "developer"
    REVIEWER = "reviewer"
    TESTER = "tester"
    DEVOPS = "devops"
