"""Anthropic 适配器（主规格 §5.3 / M1 交付物 Issue #014）。

端点与参数口径（来源：console.anthropic.com/docs/en/api 与
platform.claude.com/docs/en/models/overview，2026-09-22 发布说明）：

- `POST https://api.anthropic.com/v1/messages`
- 鉴权头是 `x-api-key: <API_KEY>`（不是 `Authorization: Bearer`），
  且 `anthropic-version: 2023-06-01` 为必填
- `max_tokens` 必填；`system` 是与 `messages` 平级的顶层字段；
  `temperature` 取值域为 `[0, 1]`（OpenAI 是 `[0, 2]`）
- 响应正文是内容块数组 `content: [{"type": "text", "text": "..."}, ...]`，
  需按顺序拼接全部 `type == "text"` 的块
- 用量字段为 `usage.input_tokens` / `usage.output_tokens`

默认模型 id 为 `claude-sonnet-5-5`（官方模型对比表列出 Claude Sonnet 5.5，$2/$10 每百万 token），
可用环境变量 `FLUX_ANTHROPIC_MODEL` 覆盖。

因为 system 位置、`max_tokens` 必填与响应结构都与 OpenAI 不同，本适配器独立实现，
不复用 `OpenAICompatibleProvider`。

为什么自己写 `httpx` 而不用官方 SDK：见 `flux.core.model_gateway.http` 的模块说明。
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
from flux.logging import get_logger

logger = get_logger(__name__)

# Anthropic 要求的 API 版本头，缺失会被上游拒绝
ANTHROPIC_VERSION = "2023-06-01"

# Anthropic 的 `max_tokens` 是必填字段，做不到"不传就不限"，所以调用方未指定时必须给一个值。
# 这是不得已的兜底，仅本适配器需要；OpenAI / DeepSeek 不传时整条字段都不下发。
DEFAULT_MAX_TOKENS = 1024


class AnthropicProvider(ModelProviderBase):
    provider = ModelProvider.ANTHROPIC

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

        system_prompt = "\n\n".join(m.content for m in messages if m.role == "system")
        dialog = [{"role": m.role, "content": m.content} for m in messages if m.role != "system"]

        payload: dict[str, Any] = {
            "model": self.model_name,
            "max_tokens": kwargs.get("max_tokens") or DEFAULT_MAX_TOKENS,
            "messages": dialog,
            "stream": False,
        }
        if system_prompt:
            payload["system"] = system_prompt

        temperature = kwargs.get("temperature")
        if temperature is not None:
            clamped = min(1.0, max(0.0, temperature))
            if clamped != temperature:
                # 只记录被钳制的取值，不涉及任何密钥
                logger.debug(
                    "Anthropic temperature 超出 [0, 1] 取值域，已钳制：%s -> %s",
                    temperature,
                    clamped,
                )
            payload["temperature"] = clamped

        headers = {
            "x-api-key": f"{self.api_key}",
            "anthropic-version": ANTHROPIC_VERSION,
            "Content-Type": "application/json",
        }
        url = f"{self.base_url.rstrip('/')}/v1/messages"

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

        blocks = data.get("content")
        if not isinstance(blocks, list) or not blocks:
            raise ProviderError(
                f"供应商 {self.provider} 上游响应缺少 content",
                details={"provider": str(self.provider), "status": None},
            )
        content = "".join(
            block.get("text", "")
            for block in blocks
            if isinstance(block, dict) and block.get("type") == "text"
        )

        raw_usage = data.get("usage") or {}
        usage = TokenUsage(
            input_tokens=raw_usage.get("input_tokens", 0),
            output_tokens=raw_usage.get("output_tokens", 0),
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
