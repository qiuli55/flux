"""Agent Runtime 测试（主规格 §5.1）。"""

from __future__ import annotations

import asyncio

import pytest

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.context import AgentSpec
from flux.core.agent_runtime.manager import AgentManager
from flux.core.event.bus import Events
from flux.core.model_gateway.base import (
    ChatMessage,
    ChatResult,
    ModelProviderBase,
    TokenUsage,
)
from flux.core.model_gateway.router import ModelRouter
from flux.enums import AgentRole, AgentState, Capability, ModelProvider
from flux.errors import NotFoundError, ProviderNotConfiguredError


class _RecordingProvider(ModelProviderBase):
    """只记录 chat() 拿到的 max_tokens，用来断言 Agent 执行层有没有硬塞输出上限。"""

    provider = ModelProvider.LOCAL

    def __init__(self) -> None:
        super().__init__(model_name="recording-provider")
        self.seen_max_tokens: list[object] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: object) -> ChatResult:
        self.seen_max_tokens.append(kwargs.get("max_tokens"))
        return ChatResult(
            content="ok",
            provider=self.provider,
            model=self.model_name,
            usage=TokenUsage(input_tokens=1, output_tokens=1),
            latency_ms=0,
        )


def _manager(provider: _RecordingProvider, *, max_output_tokens: int | None = None) -> AgentManager:
    return AgentManager(
        ModelRouter({ModelProvider.LOCAL: provider}), max_output_tokens=max_output_tokens
    )


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


def test_agent_run_does_not_cap_output_by_default() -> None:
    """Agent 执行层默认不设输出上限。

    写死的上限会把多文件提案的完整文件内容截断、让提案 JSON 解析失败（已踩过），
    所以默认必须是"不传"，而不是"传一个够大的数"。
    """
    provider = _RecordingProvider()
    manager = _manager(provider)
    handle = manager.create(_spec())

    asyncio.run(manager.execute(handle.id_str, "实现一个登录接口"))

    assert provider.seen_max_tokens == [None]


def test_agent_run_forwards_explicit_output_budget() -> None:
    """显式配了上限就照传：不设上限是默认值，不是写死行为。"""
    provider = _RecordingProvider()
    manager = _manager(provider, max_output_tokens=2048)
    handle = manager.create(_spec())

    asyncio.run(manager.execute(handle.id_str, "实现一个登录接口"))

    assert provider.seen_max_tokens == [2048]


def test_default_settings_leave_agent_output_uncapped() -> None:
    """默认配置不设上限——容器按 settings 装配 Agent，这里写死就等于又把提案截断。"""
    assert Settings().model_max_output_tokens is None
