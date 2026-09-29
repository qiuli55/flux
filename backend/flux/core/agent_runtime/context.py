"""Agent 运行时上下文与规格（主规格 §5.1 Agent 实体模型 / 记忆层级）。"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

from flux.core.model_gateway.base import ChatMessage
from flux.enums import AgentRole, AgentState, Capability, ModelProvider


@dataclass
class AgentSpec:
    """Agent 定义（Identity + Execution + Capabilities 三组字段，§5.1）。"""

    name: str
    role: AgentRole
    model_provider: ModelProvider = ModelProvider.LOCAL
    model_name: str = "local-echo"
    description: str = ""
    system_prompt: str | None = None
    skills: tuple[str, ...] = ()
    tools: tuple[str, ...] = ()
    permissions: frozenset[Capability] = frozenset()

    def to_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "role": str(self.role),
            "model_provider": str(self.model_provider),
            "model_name": self.model_name,
            "description": self.description,
            "skills": list(self.skills),
            "tools": list(self.tools),
            "permissions": sorted(str(p) for p in self.permissions),
        }


@dataclass
class AgentContext:
    """短期记忆：当前会话与任务状态（§5.1 Memory 短期层）。"""

    task_id: str | None = None
    messages: list[ChatMessage] = field(default_factory=list)
    artifacts: list[str] = field(default_factory=list)
    meta: dict[str, object] = field(default_factory=dict)

    def add(self, role: str, content: str) -> None:
        self.messages.append(ChatMessage(role=role, content=content))


@dataclass
class AgentHandle:
    """运行时的 Agent 实例句柄。"""

    spec: AgentSpec
    state: AgentState = AgentState.CREATED
    context: AgentContext = field(default_factory=AgentContext)
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    last_error: str | None = None
    execution_count: int = 0

    @property
    def id_str(self) -> str:
        return str(self.id)

    def to_dict(self) -> dict[str, object]:
        return {
            "id": self.id_str,
            "state": str(self.state),
            "execution_count": self.execution_count,
            "last_error": self.last_error,
            "spec": self.spec.to_dict(),
            "task_id": self.context.task_id,
        }
