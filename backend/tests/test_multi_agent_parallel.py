"""多 Agent 并行执行（主规格 §19.1「M0–M1 验收标准：多 Agent 可并行运行」）。

用一个"人为变慢"的本地供应商替代回显供应商：异步代码写错成串行时，回显供应商
快到看不出差别，必须让调用真的在事件循环里让出控制权才能观测到并发。

判定标准刻意不依赖墙钟时间（CI 机器负载会抖），而是统计**同时在飞的调用数**：
并行的下界是 3，串行只可能是 1。
"""

from __future__ import annotations

import asyncio
from typing import Any

from flux.core.agent_runtime.context import AgentSpec
from flux.core.agent_runtime.manager import AgentManager
from flux.core.event.bus import EventBus, Events
from flux.core.model_gateway.base import (
    ChatMessage,
    ChatResult,
    ModelProviderBase,
    TokenUsage,
)
from flux.core.model_gateway.router import ModelRouter
from flux.enums import AgentRole, AgentState, Capability, ModelProvider

# 单次调用的人工延迟：够长，使三个并发调用必然重叠
CALL_DELAY_SECONDS = 0.05

AGENT_COUNT = 3


class SlowLocalProvider(ModelProviderBase):
    """人为变慢的本地供应商，用于观测并发重叠。"""

    provider = ModelProvider.LOCAL

    def __init__(self) -> None:
        super().__init__(model_name="local-slow")
        self.in_flight = 0
        self.max_in_flight = 0
        self.seen_instructions: list[str] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        self.in_flight += 1
        self.max_in_flight = max(self.max_in_flight, self.in_flight)
        try:
            await asyncio.sleep(CALL_DELAY_SECONDS)
            instruction = next((m.content for m in reversed(messages) if m.role == "user"), "")
            self.seen_instructions.append(instruction)
            return ChatResult(
                content=f"[local-slow] {instruction}",
                provider=self.provider,
                model=self.model_name,
                usage=TokenUsage(input_tokens=8, output_tokens=8),
                latency_ms=int(CALL_DELAY_SECONDS * 1000),
            )
        finally:
            self.in_flight -= 1


def _spec(name: str) -> AgentSpec:
    return AgentSpec(
        name=name,
        role=AgentRole.DEVELOPER,
        system_prompt=f"你是 {name}，负责一个独立子任务。",
        permissions=frozenset({Capability.FILE_WRITE}),
    )


async def test_three_agents_execute_in_parallel() -> None:
    provider = SlowLocalProvider()
    bus = EventBus()
    manager = AgentManager(ModelRouter({ModelProvider.LOCAL: provider}), bus)

    handles = [manager.create(_spec(f"agent-{index}")) for index in range(AGENT_COUNT)]
    instructions = [f"实现第 {index} 个子任务" for index in range(AGENT_COUNT)]

    results = await asyncio.gather(
        *(
            manager.execute(handle.id_str, instruction, task_id=f"t-{index}")
            for index, (handle, instruction) in enumerate(zip(handles, instructions, strict=True))
        )
    )

    # 真的并行：三个调用同时在飞（串行时该值恒为 1）
    assert provider.max_in_flight == AGENT_COUNT

    # 各自的执行结果与自己的指令对应，没有串台
    assert [result.content for result in results] == [
        f"[local-slow] {instruction}" for instruction in instructions
    ]
    assert provider.seen_instructions == instructions

    # 每个 Agent 都独立完成了一轮执行
    assert [handle.state for handle in handles] == [AgentState.COMPLETED] * AGENT_COUNT
    assert [handle.execution_count for handle in handles] == [1] * AGENT_COUNT

    # 短期上下文互相隔离：每个 Agent 只看到自己的指令（§5.1 记忆层级）
    for handle, instruction in zip(handles, instructions, strict=True):
        seen = [message.content for message in handle.context.messages if message.role == "user"]
        assert seen == [instruction]

    # 用量分别按 Agent 记账（§15.4 成本按 agent / task 维度）
    usage_events = [payload for event, payload in bus.history if event == Events.USAGE_RECORDED]
    assert len(usage_events) == AGENT_COUNT
    assert {event["agent_id"] for event in usage_events} == {handle.id_str for handle in handles}
    assert {event["task_id"] for event in usage_events} == {"t-0", "t-1", "t-2"}


async def test_parallel_batch_is_faster_than_serial() -> None:
    """并行度的旁证：同一批调用的墙钟耗时明显短于串行下界。

    阈值取得很宽松（串行下界的三分之二），只用来兜住"把 gather 误改成 for 循环"
    这类退化，不与 CI 机器的负载抖动较劲。
    """
    provider = SlowLocalProvider()
    manager = AgentManager(ModelRouter({ModelProvider.LOCAL: provider}), EventBus())
    handles = [manager.create(_spec(f"agent-{index}")) for index in range(AGENT_COUNT)]

    loop = asyncio.get_running_loop()
    started = loop.time()
    await asyncio.gather(*(manager.execute(handle.id_str, "并行耗时对照") for handle in handles))
    elapsed = loop.time() - started

    serial_lower_bound = CALL_DELAY_SECONDS * AGENT_COUNT
    assert elapsed < serial_lower_bound * 2 / 3
