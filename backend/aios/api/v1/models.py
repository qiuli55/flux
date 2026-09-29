"""Model Gateway API（主规格 §12.6 统一 LLM 接口）。

请求字段：model / messages / task_id / project_id
响应字段：content / token usage / cost
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from aios.api.deps import get_container
from aios.api.response import ok
from aios.container import Container
from aios.core.model_gateway.base import ChatMessage
from aios.schemas.api import ChatRequest

router = APIRouter(prefix="/models", tags=["models"])


@router.post("/chat")
async def chat(
    payload: ChatRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    messages = [ChatMessage(role=m.role, content=m.content) for m in payload.messages]
    result = await container.router.chat(messages, provider=payload.provider)
    return ok(
        {
            "content": result.content,
            "provider": str(result.provider),
            "model": result.model,
            "usage": {
                "input_tokens": result.usage.input_tokens,
                "output_tokens": result.usage.output_tokens,
                "total_tokens": result.usage.total_tokens,
            },
            # cost 为 null：该供应商未配置计价，不臆造金额（主规格 §15.4）
            "cost": result.cost,
            "latency_ms": result.latency_ms,
        },
        metadata={"task_id": payload.task_id, "project_id": payload.project_id},
    )
