"""Agent Manager（主规格 §5.1）：Flux 侧的 Agent 档案注册表。

Flux 本身不执行 Agent——没有 loop、不组装 prompt、不存对话（目标架构 §1）。
本类只维护平台上的 Agent 档案：创建、查询、列出、停止，并在状态跃迁时发布事件；
执行由各 Agent 自己的运行时完成（内置 DSH Agent 或外部 Codex / Claude Code 等）。
档案里的 permissions 是平台侧权限的唯一来源（Connector 按 agent_id 解析）。

M0 为进程内实现（档案存内存）。M1 里程碑接入 agents 表持久化，
届时本类的公开接口保持不变。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

from flux.core.agent_runtime.lifecycle import assert_transition
from flux.core.agent_runtime.manifest import AgentManifest, builtin_manifests
from flux.core.event.bus import EventBus, Events
from flux.enums import AgentRole, AgentState, Capability
from flux.errors import NotFoundError
from flux.logging import get_logger

logger = get_logger(__name__)


@dataclass
class AgentSpec:
    """Agent 档案定义（Identity + Capabilities 两组字段，§5.1）。

    不含模型与 system prompt：模型与对话归 Agent 自己（目标架构 §1）。
    """

    name: str
    role: AgentRole
    description: str = ""
    skills: tuple[str, ...] = ()
    tools: tuple[str, ...] = ()
    permissions: frozenset[Capability] = frozenset()

    def to_dict(self) -> dict[str, object]:
        return {
            "name": self.name,
            "role": str(self.role),
            "description": self.description,
            "skills": list(self.skills),
            "tools": list(self.tools),
            "permissions": sorted(str(p) for p in self.permissions),
        }


@dataclass
class AgentHandle:
    """注册表里的 Agent 档案句柄。"""

    spec: AgentSpec
    state: AgentState = AgentState.CREATED
    id: uuid.UUID = field(default_factory=uuid.uuid4)

    @property
    def id_str(self) -> str:
        return str(self.id)

    def to_dict(self) -> dict[str, object]:
        return {"id": self.id_str, "state": str(self.state), "spec": self.spec.to_dict()}


class AgentManager:
    def __init__(self, bus: EventBus | None = None) -> None:
        self._bus = bus
        self._agents: dict[uuid.UUID, AgentHandle] = {}

    # --- 接口：create ---

    async def create(self, spec: AgentSpec) -> AgentHandle:
        handle = AgentHandle(spec=spec)
        self._agents[handle.id] = handle
        await self._transition(handle, AgentState.INITIALIZING)
        await self._transition(handle, AgentState.READY)
        logger.info("agent.create id=%s name=%s role=%s", handle.id_str, spec.name, spec.role)
        return handle

    async def create_from_manifest(self, manifest: AgentManifest) -> AgentHandle:
        """按 Manifest 声明创建 Agent 档案（§6.2）：声明式配置 → 运行时档案。"""
        return await self.create(
            AgentSpec(
                name=manifest.name,
                role=manifest.role,
                description=manifest.description,
                skills=manifest.skills,
                tools=manifest.tools,
                permissions=manifest.permissions,
            )
        )

    async def create_builtin_agents(self) -> dict[str, AgentHandle]:
        """创建第一批内置档案（Tech Lead / Developer / Reviewer / Tester）。"""
        return {name: await self.create_from_manifest(m) for name, m in builtin_manifests().items()}

    # --- 接口：查询 ---

    def get(self, agent_id: str | uuid.UUID) -> AgentHandle:
        key = self._as_uuid(agent_id)
        handle = self._agents.get(key)
        if handle is None:
            raise NotFoundError(f"Agent {agent_id} 不存在", details={"agent_id": str(agent_id)})
        return handle

    def list(self) -> list[AgentHandle]:
        return list(self._agents.values())

    def count(self) -> int:
        return len(self._agents)

    # --- 接口：stop ---

    async def stop(self, agent_id: str | uuid.UUID) -> AgentHandle:
        handle = self.get(agent_id)
        if handle.state is AgentState.STOPPED:
            return handle
        await self._transition(handle, AgentState.STOPPED)
        return handle

    # --- 内部 ---

    async def _transition(self, handle: AgentHandle, target: AgentState) -> None:
        assert_transition(handle.state, target)
        previous = handle.state
        handle.state = target
        logger.debug("agent.state %s: %s → %s", handle.id_str, previous, target)
        if self._bus is not None:
            await self._bus.publish(
                Events.AGENT_STATE_CHANGED,
                {"agent_id": handle.id_str, "from": str(previous), "to": str(target)},
            )

    @staticmethod
    def _as_uuid(agent_id: str | uuid.UUID) -> uuid.UUID:
        if isinstance(agent_id, uuid.UUID):
            return agent_id
        try:
            return uuid.UUID(str(agent_id))
        except ValueError as exc:
            raise NotFoundError(
                f"Agent 标识非法：{agent_id}", details={"agent_id": str(agent_id)}
            ) from exc
