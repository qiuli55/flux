"""API 请求模型（主规格 §12 各接口的入参契约）。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

from flux.enums import (
    AgentRole,
    BrainSection,
    Capability,
    CapabilityKind,
    DecisionMode,
    ModelProvider,
)


class AgentCreateRequest(BaseModel):
    """登记一个 Agent 档案（身份 + 权限边界）。

    模型与 system prompt 不在登记范围内——那是 Agent 自己的事（目标架构 §1）。
    """

    name: str = Field(min_length=1, max_length=128)
    role: AgentRole
    description: str = ""
    skills: list[str] = Field(default_factory=list)
    tools: list[str] = Field(default_factory=list)
    permissions: list[Capability] = Field(default_factory=list)
    #: 执行该 Agent 的 runtime（P2-1 §6.2）：内置 DSH 默认，或本机 CLI（codex / opencode）。
    #: 取值在边界即校验（非法 → 422），并由 AgentManager 再校验一次（内部调用方同样受约束）。
    runtime: Literal["dsh", "codex", "opencode"] = "dsh"


class AgentTokenIssueRequest(BaseModel):
    """为某个 Agent 签发一枚 MCP 接入令牌（目标架构 §3.2）。

    `scopes` 决定这枚令牌能调用哪些工具；`secret.access` 不在可授予范围内（§3.5）。
    明文令牌只在签发响应里出现一次，之后任何接口都取不回——丢了就重新签一枚。
    """

    # 默认只给只读：让"什么都没配"的调用方拿到的是最小权限，而不是能写提案的权限
    scopes: list[Capability] = Field(default_factory=lambda: [Capability.FILE_READ])
    label: str = Field(default="", max_length=128)


class TaskCreateRequest(BaseModel):
    description: str = Field(min_length=1)
    project_id: str | None = None
    agent_id: str | None = None
    # 数字越小越优先（主规格 §5.1 调度依据）
    priority: int = 100
    # 任务级决策策略（文档 §5）：建任务时就定下来，之后可单独切换
    decision_mode: DecisionMode = DecisionMode.AUTO


class TaskMessageCreateRequest(BaseModel):
    """发一条任务消息（任务执行中心的对话输入）。

    只有 user 能由调用方写入：assistant 的回复必须来自平台真实模型调用，
    不接受客户端伪造（role 不在请求体里，由服务端决定）。
    """

    content: str = Field(min_length=1, max_length=8000)


class ConfirmationItem(BaseModel):
    """需求确认的一项：维度 + 结论（P0-06 六个固定维度，label 由服务端校验）。"""

    label: str = Field(min_length=1, max_length=32)
    value: str = Field(min_length=1, max_length=2000)


class ConfirmationUpdateRequest(BaseModel):
    """用户改后的需求确认内容：必须恰好覆盖六个维度（服务端 fail-closed 校验）。"""

    items: list[ConfirmationItem] = Field(min_length=1, max_length=8)


class DecisionModeRequest(BaseModel):
    """切换任务级决策策略（文档 §5 模式 A / B）。"""

    mode: DecisionMode


class DecisionOption(BaseModel):
    """一个候选方案：做什么、带来什么影响、是不是推荐项。"""

    label: str = Field(min_length=1, max_length=64)
    description: str | None = Field(default=None, max_length=2000)
    impact: str | None = Field(default=None, max_length=2000)
    recommended: bool = False


class DecisionCreateRequest(BaseModel):
    """Agent 遇到决策点时向平台登记（文档 §5）：平台按任务策略自动拍板或挂起等用户。"""

    question: str = Field(min_length=1, max_length=2000)
    options: list[DecisionOption] = Field(min_length=2, max_length=6)
    context: str | None = Field(default=None, max_length=4000)
    recommendation: str | None = Field(default=None, max_length=2000)


class DecisionChooseRequest(BaseModel):
    """用户对挂起的决策点做选择：choose 需要给出 option，reject 表示全部候选都不接受。"""

    decision_id: str = Field(min_length=1, max_length=64)
    action: Literal["choose", "reject"] = "choose"
    option: str | None = Field(default=None, max_length=64)
    note: str | None = Field(default=None, max_length=2000)


class TaskStartRequest(BaseModel):
    """开始执行（P0-05）。confirmation 留空表示直接使用任务上已保存的确认内容。"""

    confirmation: list[ConfirmationItem] | None = None


class ChangeIdsRequest(BaseModel):
    change_ids: list[str] = Field(min_length=1)


class RejectRequest(ChangeIdsRequest):
    reason: str | None = None


class ExpireRequest(ChangeIdsRequest):
    """显式让一批提案失效（P0-02）。reason 留空时用默认的"过期"原因。"""

    reason: str | None = None


class GroupRequest(BaseModel):
    """按一次提交（group_id）整组操作（P0-02）。"""

    group_id: str = Field(min_length=1)
    reason: str | None = None


class RecoveryResolveRequest(BaseModel):
    """对一条崩溃恢复挂起项做人工决策（P0-1 §2.2，2026-10-05 定案）。

    cover = 用备份覆盖当前内容（还原为改动前原文，提案回 accepted，可重试）；
    keep = 保持磁盘现状、绝不覆盖用户改动（提案留 failed 作废）。
    """

    change_id: str = Field(min_length=1)
    action: Literal["cover", "keep"]


class TerminalSessionCreateRequest(BaseModel):
    """开一个终端会话（Agent Terminal Console §6）。工作区根由服务端配置决定，不接受客户端指定。"""

    run_id: str | None = None


class TerminalCommandRequest(BaseModel):
    """在终端会话里执行一条命令（§4）。T1 只接 USER 来源；T2 起 Agent 走同一 Session Manager。"""

    command: str = Field(min_length=1, max_length=4000)


class TerminalStopRequest(BaseModel):
    """停止终端会话（§3.3 / §3.4）：force=true 直接 SIGKILL，false 走 SIGTERM → grace → SIGKILL。"""

    force: bool = False


class ChatMessageIn(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessageIn] = Field(min_length=1)
    model: str | None = None
    provider: ModelProvider | None = None
    task_id: str | None = None
    project_id: str | None = None


class ConnectorExecuteRequest(BaseModel):
    connector: str = Field(min_length=1)
    action: str = Field(min_length=1)
    parameters: dict[str, Any] = Field(default_factory=dict)
    # 权限由服务端按 agent_id 解析（见 api/v1/connectors.py），客户端无法自行声明能力
    agent_id: str | None = None


class GitDiffRequest(BaseModel):
    """查看差异。paths 为空表示整个工作区。"""

    paths: list[str] = Field(default_factory=list)
    staged: bool = False


class GitCheckoutRequest(BaseModel):
    target: str = Field(min_length=1, max_length=255)
    # True = 新建并切换（git checkout -b），False = 切换到已有分支
    create: bool = False


class GitCommitRequest(BaseModel):
    """提交改动。message 由调用方给出（用户可改），Agent 无权直接决定提交信息。"""

    message: str = Field(min_length=1, max_length=2000)
    # 只允许提交 applied 状态的提案（已批准 + 已落盘 + 测试通过，§7.6）
    change_ids: list[str] = Field(default_factory=list)
    # 也可直接指定路径；与 change_ids 都为空时提交暂存区已有内容
    paths: list[str] = Field(default_factory=list)


class ProjectCreateRequest(BaseModel):
    """登记一个项目（⑪ Project Brain 的宿主实体）。"""

    name: str = Field(min_length=1, max_length=128)
    repository: str | None = Field(default=None, max_length=255)
    metadata: dict[str, Any] = Field(default_factory=dict)


class MemoryWriteRequest(BaseModel):
    """写一条项目记忆。分区语义见 flux.enums.BrainSection。"""

    section: BrainSection
    content: str = Field(min_length=1)
    metadata: dict[str, Any] = Field(default_factory=dict)


class UserMemoryWriteRequest(BaseModel):
    """写一条 User Memory（跨 Workspace；只有用户侧入口，Agent 无直写通道）。

    上限与 `flux.core.memory.policies.MAX_CONTENT_CHARS` 对齐；密钥/令牌/凭证
    由服务层再次拦截（此处只做长度与形态校验，不做安全判定）。
    """

    content: str = Field(min_length=1, max_length=8000)
    source: str = Field(default="user", max_length=64)
    metadata: dict[str, Any] = Field(default_factory=dict)


class ScanRequest(BaseModel):
    """扫描被登记项目的工作区目录（⑩）。

    workspace_root 留空表示用服务端配置的 FLUX_WORKSPACE_ROOT；record=false
    时只返回画像，不把结果写进 Project Brain。
    """

    workspace_root: str | None = None
    record: bool = True


class CapabilityImportRequest(BaseModel):
    """导入一个扫描到的能力（批次③ §5）。

    - kind + name 定位候选；候选必须来自服务端扫描，调用方不能注入 spec；
    - 重复导入且内容有变化时，decision 必须显式给出 keep（保留现有）或
      replace（替换）；缺省时接口返回 409 冲突，details 里带字段级差异。
    """

    kind: CapabilityKind
    name: str = Field(min_length=1, max_length=128)
    decision: Literal["keep", "replace"] | None = None
