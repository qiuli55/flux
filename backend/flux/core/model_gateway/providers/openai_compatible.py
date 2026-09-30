"""OpenAI 兼容的 chat/completions 适配器（OpenAI 与 DeepSeek 共用）。

为什么自己写 `httpx` 而不用官方 SDK：见 `flux.core.model_gateway.http` 的模块说明
（依赖面更小、三家都是 HTTP JSON、MockTransport 可离线断言）。

两家的协议差异只有一处——生成上限参数名不同：OpenAI 用 `max_completion_tokens`
（`max_tokens` 已废弃），DeepSeek 仍用 `max_tokens`。子类通过 `token_limit_param` 覆写。

调用方不传 `max_tokens` 时**不下发**该字段，由模型自身的默认输出上限决定；本适配器不填
兜底值——写死的兜底会把长输出截断（Agent 一次要产出完整文件内容，动辄数千 token）。
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from flux.core.model_gateway.base import (
    ChatMessage,
    ChatResult,
    ModelPricing,
    ModelProviderBase,
    TokenUsage,
)
from flux.core.model_gateway.http import post_json
from flux.enums import ModelProvider
from flux.errors import ProviderError


class OpenAICompatibleProvider(ModelProviderBase):
    provider: ModelProvider
    # 子类覆写：生成上限使用的参数名（OpenAI 用 max_completion_tokens，DeepSeek 用 max_tokens）
    token_limit_param: str = "max_tokens"

    def __init__(
        self,
        model_name: str,
        *,
        api_key: str | None,
        base_url: str,
        timeout: float = 60.0,
        max_retries: int = 2,
        pricing: ModelPricing | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        super().__init__(model_name=model_name, pricing=pricing)
        self.api_key = api_key
        self.base_url = base_url
        self.timeout = timeout
        self.max_retries = max_retries
        # 生产留空：每次调用临时建连；测试注入带 MockTransport 的 client，断言不触网
        self._client = client

    def is_configured(self) -> bool:
        return bool(self.api_key)

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        self._ensure_configured()

        payload: dict[str, Any] = {
            "model": self.model_name,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": False,
        }
        # 不给上限就整条字段都不下发，让模型用自己的默认输出上限；不填兜底值以免截断长输出
        max_tokens = kwargs.get("max_tokens")
        if max_tokens is not None:
            payload[self.token_limit_param] = max_tokens
        temperature = kwargs.get("temperature")
        if temperature is not None:
            payload["temperature"] = temperature

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        url = f"{self.base_url.rstrip('/')}/chat/completions"

        started = time.perf_counter()
        if self._client is not None:
            data = await post_json(
                self._client,
                url,
                payload=payload,
                headers=headers,
                provider=str(self.provider),
                timeout=self.timeout,
                max_retries=self.max_retries,
            )
        else:
            async with httpx.AsyncClient() as client:
                data = await post_json(
                    client,
                    url,
                    payload=payload,
                    headers=headers,
                    provider=str(self.provider),
                    timeout=self.timeout,
                    max_retries=self.max_retries,
                )
        latency_ms = int((time.perf_counter() - started) * 1000)

        choices = data.get("choices")
        if not isinstance(choices, list) or not choices:
            raise ProviderError(
                f"供应商 {self.provider} 上游响应缺少 choices",
                details={"provider": str(self.provider), "status": None},
            )
        content = choices[0].get("message", {}).get("content") or ""

        raw_usage = data.get("usage") or {}
        usage = TokenUsage(
            input_tokens=raw_usage.get("prompt_tokens", 0),
            output_tokens=raw_usage.get("completion_tokens", 0),
        )
        return ChatResult(
            content=content,
            provider=self.provider,
            model=self.model_name,
            usage=usage,
            latency_ms=latency_ms,
            cost=self.calculate_cost(usage),
            raw=data,
        )
