"""Agent 执行器（主规格 §5.1 任务执行 / §15.4 成本记录）。"""

from __future__ import annotations

from dataclasses import dataclass

from flux.core.agent_runtime.context import AgentHandle
from flux.core.event.bus import EventBus, Events
from flux.core.model_gateway.base import ChatMessage, TokenUsage
from flux.core.model_gateway.router import ModelRouter
from flux.logging import get_logger

logger = get_logger(__name__)


@dataclass(frozen=True)
class AgentRunResult:
    agent_id: str
    task_id: str | None
    content: str
    provider: str
    model: str
    usage: TokenUsage
    cost: float | None
    latency_ms: int

    def to_dict(self) -> dict[str, object]:
        return {
            "agent_id": self.agent_id,
            "task_id": self.task_id,
            "content": self.content,
            "provider": self.provider,
            "model": self.model,
            "usage": {
                "input_tokens": self.usage.input_tokens,
                "output_tokens": self.usage.output_tokens,
                "total_tokens": self.usage.total_tokens,
            },
            # cost 为 null 表示该供应商未配置计价（主规格 §15.4）
            "cost": self.cost,
            "latency_ms": self.latency_ms,
        }


class AgentExecutor:
    def __init__(
        self,
        router: ModelRouter,
        bus: EventBus | None = None,
        *,
        max_output_tokens: int | None = None,
    ) -> None:
        self._router = router
        self._bus = bus
        # None = 不设上限：Agent 要产出完整文件内容，截断会让提案 JSON 解析失败
        self._max_output_tokens = max_output_tokens

    async def run(
        self, handle: AgentHandle, instruction: str, *, task_id: str | None = None
    ) -> AgentRunResult:
        messages: list[ChatMessage] = []
        if handle.spec.system_prompt:
            messages.append(ChatMessage(role="system", content=handle.spec.system_prompt))
        messages.extend(handle.context.messages)
        messages.append(ChatMessage(role="user", content=instruction))

        result = await self._router.chat(
            messages,
            provider=handle.spec.model_provider,
            max_tokens=self._max_output_tokens,
        )

        handle.context.task_id = task_id or handle.context.task_id
        handle.context.add("user", instruction)
        handle.context.add("assistant", result.content)

        run_result = AgentRunResult(
            agent_id=handle.id_str,
            task_id=task_id,
            content=result.content,
            provider=str(result.provider),
            model=result.model,
            usage=result.usage,
            cost=result.cost,
            latency_ms=result.latency_ms,
        )

        if self._bus is not None:
            await self._bus.publish(Events.USAGE_RECORDED, run_result.to_dict())

        logger.info(
            "agent.run agent=%s task=%s provider=%s tokens=%d",
            handle.id_str,
            task_id,
            result.provider,
            result.usage.total_tokens,
        )
        return run_result
