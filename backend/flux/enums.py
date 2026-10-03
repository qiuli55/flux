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
    #: 执行中撞上需要用户拍板的决策点（文档 §5 模式 B）：任务停在原地等人，
    #: 既不是失败也不是完成——用户长时间不响应时状态必须保持在这里。
    WAITING_FOR_USER_DECISION = "waiting_for_user_decision"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class DecisionMode(StrEnum):
    """任务级决策策略（文档 §5）：Agent 遇到可选方案时是自己拍板还是停下来问用户。"""

    AUTO = "auto"
    MANUAL = "manual"


class DecisionStatus(StrEnum):
    """一个决策点的状态（文档 §5）。"""

    PENDING = "pending"
    #: auto 模式：平台按推荐方案自行拍板，不打断用户
    AUTO_RESOLVED = "auto_resolved"
    #: manual 模式：用户已在候选方案里选了一个
    RESOLVED = "resolved"
    #: 用户拒绝全部候选方案，要求重新给方案
    REJECTED = "rejected"


class TaskMessageRole(StrEnum):
    """任务对话消息的说话方（task_messages.role）。"""

    USER = "user"
    ASSISTANT = "assistant"


class VirtualChangeStatus(StrEnum):
    """Virtual Workspace 文件状态机（主规格 §7.2）。

    FAILED 用于 Apply 阶段：补丁打不上或校验不通过时留下终态记录（实施计划 §5 状态列表），
    不允许从 FAILED 回到任何可执行状态——失败原因必须由人重新生成提案。

    EXPIRED 用于提案过期/失效（P0-02）：超过 TTL 仍未审核，或同文件被更新的提案取代时，
    提案进入 EXPIRED 终态，不允许再被批准或落盘——审核队列里不该长期挂着改不动的旧提案。
    """

    PENDING = "pending"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    APPLIED = "applied"
    FAILED = "failed"
    EXPIRED = "expired"


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
    """DSH Agent Run 状态机（修复方案 §2.2）。

    PENDING → STARTING → RUNNING → COMPLETED / FAILED / TIMEOUT，
    取消走 RUNNING → CANCELLING → CANCELLED（进程树确认清理完成才算 CANCELLED），
    重启接管的遗留 Run 归 INTERRUPTED。QUEUED 为 Phase 1 遗留值，保留以免旧数据反序列化失败。
    """

    QUEUED = "queued"
    PENDING = "pending"
    STARTING = "starting"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    TIMEOUT = "timeout"
    CANCELLING = "cancelling"
    CANCELLED = "cancelled"
    INTERRUPTED = "interrupted"


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


class AgentInstallStatus(StrEnum):
    """本机 Agent 的可接入状态机（最终方案 §3.2 / §4.1）。

    NOT_INSTALLED 是 Scanner 的发现事实（本机没有这个 CLI），不是"流程中的一步"；
    已安装的 Agent 沿 DISCOVERED → VERIFIED → CONNECTED → READY 单向收敛。
    """

    NOT_INSTALLED = "NOT_INSTALLED"
    DISCOVERED = "DISCOVERED"
    VERIFIED = "VERIFIED"
    CONNECTED = "CONNECTED"
    READY = "READY"


class FluxCapability(StrEnum):
    """Flux Runtime 能力项（最终方案 §8）：与权限项 Capability 不同，它描述
    "当前这套 Flux 能不能做这件事"，由 `flux_context.capabilities` 声明，缺失即降级。"""

    CONTEXT = "context"
    WORKSPACE = "workspace"
    PROPOSAL = "proposal"
    APPLY = "apply"
    ROLLBACK = "rollback"
    GIT = "git"


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
