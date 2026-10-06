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
    name: str = Field(min_length=1, max_length=128)
    role: AgentRole
    description: str = ""
    skills: list[str] = Field(default_factory=list)
    tools: list[str] = Field(default_factory=list)
    permissions: list[Capability] = Field(default_factory=list)
    runtime: Literal["dsh", "codex", "opencode"] = "dsh"


class AgentTokenIssueRequest(BaseModel):
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
