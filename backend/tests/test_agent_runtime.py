"""Agent Runtime 测试（主规格 §5.1）。"""

from __future__ import annotations

import asyncio

import pytest

from flux.container import Container
from flux.core.agent_runtime.context import AgentSpec
from flux.core.event.bus import Events
from flux.enums import AgentRole, AgentState, Capability, ModelProvider
from flux.errors import NotFoundError, ProviderNotConfiguredError


def _spec(**overrides: object) -> AgentSpec:
    base: dict[str, object] = {
        "name": "developer-agent",
        "role": AgentRole.DEVELOPER,
        "system_prompt": "你是开发者 Agent。",
        "permissions": frozenset({Capability.FILE_WRITE}),
    }
    base.update(overrides)
    return AgentSpec(**base)  # type: ignore[arg-type]


def test_create_agent_ends_ready(container: Container) -> None:
    handle = container.agents.create(_spec())
    assert handle.state is AgentState.READY
    assert handle.execution_count == 0
    assert container.agents.count() == 1


def test_execute_runs_provider_and_publishes_events(container: Container) -> None:
    handle = container.agents.create(_spec())
    result = asyncio.run(container.agents.execute(handle.id_str, "实现一个登录接口", task_id="t-1"))

    assert "实现一个登录接口" in result.content
    assert result.provider == "local"
    assert result.usage.total_tokens > 0
    # 离线回显不产生费用，但计价为 0 也必须是数字而非 None——这里 pricing 未配置，故为 None
    assert result.cost is None
    assert handle.state is AgentState.COMPLETED
    assert handle.execution_count == 1

    published = [event for event, _ in container.bus.history]
    assert Events.AGENT_STARTED in published
    assert Events.AGENT_COMPLETED in published
    assert Events.USAGE_RECORDED in published


def test_completed_agent_can_be_reused(container: Container) -> None:
    handle = container.agents.create(_spec())
    asyncio.run(container.agents.execute(handle.id_str, "第一次"))
    asyncio.run(container.agents.execute(handle.id_str, "第二次"))
    assert handle.execution_count == 2
    assert handle.state is AgentState.COMPLETED
    # 上下文累计两轮问答（§5.1 短期记忆）
    assert len(handle.context.messages) == 4


def test_execute_failure_marks_agent_failed(container: Container) -> None:
    """未接入的供应商必须明确失败，并留下可诊断的错误信息。"""
    handle = container.agents.create(_spec(model_provider=ModelProvider.OPENAI))

    with pytest.raises(ProviderNotConfiguredError):
        asyncio.run(container.agents.execute(handle.id_str, "任意指令"))

    assert handle.state is AgentState.FAILED
    assert handle.last_error is not None
    assert Events.TASK_FAILED in [event for event, _ in container.bus.history]


def test_failed_agent_recovers_on_next_run(container: Container) -> None:
    handle = container.agents.create(_spec(model_provider=ModelProvider.OPENAI))
    with pytest.raises(ProviderNotConfiguredError):
        asyncio.run(container.agents.execute(handle.id_str, "第一次"))

    handle.spec.model_provider = ModelProvider.LOCAL
    asyncio.run(container.agents.execute(handle.id_str, "第二次"))

    assert handle.state is AgentState.COMPLETED
    assert handle.last_error is None


def test_stop_and_restart(container: Container) -> None:
    handle = container.agents.create(_spec())
    assert container.agents.stop(handle.id_str).state is AgentState.STOPPED
    # 重复 stop 幂等
    assert container.agents.stop(handle.id_str).state is AgentState.STOPPED
    asyncio.run(container.agents.execute(handle.id_str, "恢复后继续"))
    assert handle.state is AgentState.COMPLETED


def test_unknown_and_malformed_agent_id(container: Container) -> None:
    with pytest.raises(NotFoundError):
        container.agents.get("0f5b6f4c-0000-0000-0000-000000000000")
    with pytest.raises(NotFoundError):
        container.agents.get("不是-uuid")
