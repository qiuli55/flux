"""API 请求模型（主规格 §12 各接口的入参契约）。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from aios.enums import AgentRole, Capability, ModelProvider


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
