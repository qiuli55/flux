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
    """为某个 Agent 签发一枚 MCP 接入令牌（目标架构 §3.2）。"""

    scopes: list[Capability] = Field(default_factory=lambda: [Capability.FILE_READ])
    label: str = Field(default="", max_length=128)


class TaskCreateRequest(BaseModel):
    description: str = Field(min_length=1)
    project_id: str | None = None
    agent_id: str | None = None
    priority: int = 100
    decision_mode: DecisionMode = DecisionMode.AUTO


class TaskMessageCreateRequest(BaseModel):
    content: str = Field(min_length=1, max_length=8000)


class ConfirmationItem(BaseModel):
    label: str = Field(min_length=1, max_length=32)
    value: str = Field(min_length=1, max_length=2000)


class ConfirmationUpdateRequest(BaseModel):
    items: list[ConfirmationItem] = Field(min_length=1, max_length=8)


class DecisionModeRequest(BaseModel):
    mode: DecisionMode


class DecisionOption(BaseModel):
    label: str = Field(min_length=1, max_length=64)
    description: str | None = Field(default=None, max_length=2000)
    impact: str | None = Field(default=None, max_length=2000)
    recommended: bool = False


class DecisionCreateRequest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    options: list[DecisionOption] = Field(min_length=2, max_length=6)
    context: str | None = Field(default=None, max_length=4000)
    recommendation: str | None = Field(default=None, max_length=2000)


class DecisionChooseRequest(BaseModel):
    decision_id: str = Field(min_length=1, max_length=64)
    action: Literal["choose", "reject"] = "choose"
    option: str | None = Field(default=None, max_length=64)
    note: str | None = Field(default=None, max_length=2000)


class TaskStartRequest(BaseModel):
    confirmation: list[ConfirmationItem] | None = None


class ChangeIdsRequest(BaseModel):
    change_ids: list[str] = Field(min_length=1)


class RejectRequest(ChangeIdsRequest):
    reason: str | None = None


class ExpireRequest(ChangeIdsRequest):
    reason: str | None = None


class GroupRequest(BaseModel):
    group_id: str = Field(min_length=1)
    reason: str | None = None


class RecoveryResolveRequest(BaseModel):
    change_id: str = Field(min_length=1)
    action: Literal["cover", "keep"]


class WorkspaceRootRequest(BaseModel):
    """Native desktop-selected local workspace root."""

    root: str = Field(min_length=1, max_length=4096)


class WorkspaceFileCreateRequest(BaseModel):
    path: str = Field(min_length=1, max_length=4096)
    content: str = Field(default="", max_length=262144)


class WorkspaceFilePathRequest(BaseModel):
    path: str = Field(min_length=1, max_length=4096)


class WorkspaceFileRenameRequest(BaseModel):
    path: str = Field(min_length=1, max_length=4096)
    new_path: str = Field(min_length=1, max_length=4096)


class WorkspaceFileReplaceRequest(BaseModel):
    query: str = Field(min_length=1, max_length=500)
    replacement: str = Field(max_length=10000)
    path: str | None = Field(default=None, max_length=4096)
    case_sensitive: bool = False
    regex: bool = False



class TerminalSessionCreateRequest(BaseModel):
    run_id: str | None = None


class TerminalCommandRequest(BaseModel):
    command: str = Field(min_length=1, max_length=4000)


class TerminalStopRequest(BaseModel):
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
    agent_id: str | None = None


class GitPathsRequest(BaseModel):
    paths: list[str] = Field(min_length=1, max_length=200)
class GitDiffRequest(BaseModel):
    paths: list[str] = Field(default_factory=list)
    staged: bool = False


class GitCheckoutRequest(BaseModel):
    target: str = Field(min_length=1, max_length=255)
    create: bool = False


class GitCommitRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    change_ids: list[str] = Field(default_factory=list)
    paths: list[str] = Field(default_factory=list)


class ProjectCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=128)
    repository: str | None = Field(default=None, max_length=255)
    metadata: dict[str, Any] = Field(default_factory=dict)


class MemoryWriteRequest(BaseModel):
    section: BrainSection
    content: str = Field(min_length=1)
    metadata: dict[str, Any] = Field(default_factory=dict)


class UserMemoryWriteRequest(BaseModel):
    content: str = Field(min_length=1, max_length=8000)
    source: str = Field(default="user", max_length=64)
    metadata: dict[str, Any] = Field(default_factory=dict)


class ScanRequest(BaseModel):
    workspace_root: str | None = None
    record: bool = True


class CapabilityImportRequest(BaseModel):
    kind: CapabilityKind
    name: str = Field(min_length=1, max_length=128)
    decision: Literal["keep", "replace"] | None = None


class InstallationConnectRequest(BaseModel):
    agent: str | None = Field(default=None, max_length=64)
    all: bool = False
