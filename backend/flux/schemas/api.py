"""API 请求模型（主规格 §12 各接口的入参契约）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from flux.enums import AgentRole, BrainSection, Capability, ModelProvider


class AgentCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=128)
    role: AgentRole
    model_provider: ModelProvider = ModelProvider.LOCAL
    model_name: str = "local-echo"
    description: str = ""
    system_prompt: str | None = None
    skills: list[str] = Field(default_factory=list)
    tools: list[str] = Field(default_factory=list)
    permissions: list[Capability] = Field(default_factory=list)


class AgentExecuteRequest(BaseModel):
    instruction: str = Field(min_length=1)
    task_id: str | None = None


class TaskCreateRequest(BaseModel):
    description: str = Field(min_length=1)
    project_id: str | None = None
    agent_id: str | None = None
    # 数字越小越优先（主规格 §5.1 调度依据）
    priority: int = 100


class ChangeIdsRequest(BaseModel):
    change_ids: list[str] = Field(min_length=1)


class RejectRequest(ChangeIdsRequest):
    reason: str | None = None


class GenerateProposalsRequest(BaseModel):
    """让 Developer Agent 依据一句需求产出提案（⑫ 最小 IDE 的"让 AI 改"）。

    paths 是需求相关的文件（相对工作区根）；服务端只读这些文件的现状当作上下文，
    产出的是待审阅提案，不会写任何用户文件。paths 为空表示不携带上下文。
    """

    instruction: str = Field(min_length=1, max_length=4000)
    paths: list[str] = Field(default_factory=list)
    task_id: str | None = None
    project_id: str | None = None


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


class ScanRequest(BaseModel):
    """扫描被登记项目的工作区目录（⑩）。

    workspace_root 留空表示用服务端配置的 FLUX_WORKSPACE_ROOT；record=false
    时只返回画像，不把结果写进 Project Brain。
    """

    workspace_root: str | None = None
    record: bool = True
