"""Agent Manager（主规格 §5.1；接口对齐 Code Implementation Specification 的
create() / execute() / stop() / get_status()）。

M0 为进程内实现（Agent 注册表存内存）。M1 里程碑接入 agents / agent_execution_logs 表持久化，
届时本类的公开接口保持不变。
"""

from __future__ import annotations

import uuid

from flux.core.agent_runtime.context import AgentContext, AgentHandle, AgentSpec
from flux.core.agent_runtime.executor import AgentExecutor, AgentRunResult
from flux.core.agent_runtime.lifecycle import assert_transition
from flux.core.agent_runtime.manifest import AgentManifest, builtin_manifests
from flux.core.event.bus import EventBus, Events
from flux.core.model_gateway.router import ModelRouter
from flux.enums import AgentState
from flux.errors import NotFoundError
from flux.logging import get_logger

logger = get_logger(__name__)


class AgentManager:
    def __init__(
        self,
        router: ModelRouter,
        bus: EventBus | None = None,
        *,
        max_output_tokens: int | None = None,
    ) -> None:
        self._bus = bus
        self._executor = AgentExecutor(router, bus, max_output_tokens=max_output_tokens)
        self._agents: dict[uuid.UUID, AgentHandle] = {}

    # --- 接口：create ---

    def create(self, spec: AgentSpec) -> AgentHandle:
        handle = AgentHandle(spec=spec, context=AgentContext())
        self._agents[handle.id] = handle
        self._transition(handle, AgentState.INITIALIZING)
        self._transition(handle, AgentState.READY)
        logger.info("agent.create id=%s name=%s role=%s", handle.id_str, spec.name, spec.role)
        return handle

    def create_from_manifest(self, manifest: AgentManifest) -> AgentHandle:
        """按 Manifest 声明创建 Agent（§6.2）。"""
        return self.create(manifest.to_spec())

    def create_builtin_agents(self) -> dict[str, AgentHandle]:
        """创建第一批内置 Agent（Tech Lead / Developer / Reviewer / Tester）。"""
        return {name: self.create_from_manifest(m) for name, m in builtin_manifests().items()}

    # --- 接口：get_status ---

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

    def stop(self, agent_id: str | uuid.UUID) -> AgentHandle:
        handle = self.get(agent_id)
        if handle.state is AgentState.STOPPED:
            return handle
        self._transition(handle, AgentState.STOPPED)
        return handle

    # --- 接口：execute ---

    async def execute(
        self, agent_id: str | uuid.UUID, instruction: str, *, task_id: str | None = None
    ) -> AgentRunResult:
        handle = self.get(agent_id)
        self._rearm(handle)
        self._transition(handle, AgentState.RUNNING)
        if self._bus is not None:
            await self._bus.publish(
                Events.AGENT_STARTED,
                {"agent_id": handle.id_str, "task_id": task_id, "role": str(handle.spec.role)},
            )
        try:
            result = await self._executor.run(handle, instruction, task_id=task_id)
        except Exception as exc:
            handle.last_error = str(exc)
            self._transition(handle, AgentState.FAILED)
            if self._bus is not None:
                await self._bus.publish(
                    Events.TASK_FAILED,
                    {"agent_id": handle.id_str, "task_id": task_id, "error": str(exc)},
                )
            raise
        handle.execution_count += 1
        handle.last_error = None
        self._transition(handle, AgentState.COMPLETED)
        if self._bus is not None:
            await self._bus.publish(
                Events.AGENT_COMPLETED,
                {"agent_id": handle.id_str, "task_id": task_id, "cost": result.cost},
            )
        return result

    # --- 内部 ---

    def _rearm(self, handle: AgentHandle) -> None:
        """把已完成/已停止/已失败的 Agent 复位到 READY，使其可复用（§5.1 恢复策略）。"""
        if handle.state in (AgentState.COMPLETED, AgentState.STOPPED, AgentState.FAILED):
            self._transition(handle, AgentState.READY)

    def _transition(self, handle: AgentHandle, target: AgentState) -> None:
        assert_transition(handle.state, target)
        previous = handle.state
        handle.state = target
        logger.debug("agent.state %s: %s → %s", handle.id_str, previous, target)

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
