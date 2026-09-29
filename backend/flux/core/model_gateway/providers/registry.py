"""Provider 装配。

M0 只装配 LOCAL（离线回显），OPENAI / ANTHROPIC / DEEPSEEK 是 M1 交付物
（主规格 §19.1 的 Issues #013 / #014 / #015）。当时不注册的理由是：不想提交一个
"从没被验证过的 HTTP 客户端"。现在这三家已经用手写 httpx + httpx.MockTransport
离线验证了请求/响应映射、错误映射与重试，因此四个供应商全部注册。

是否可用由各自的 is_configured() 决定（无密钥即不可用），ModelRouter 按此过滤，
所以 /api/v1/health/ready 的 providers 列表会自然只显示已配置的供应商。

本增量不传 pricing（即 pricing=None），于是 ChatResult.cost 恒为 null：单价必须来自
官方价格页快照，属 M6「成本与可观测」（主规格 §15.4：绝不用猜测值填充），
不在本次范围内。
"""

from __future__ import annotations

from flux.config import Settings
from flux.core.model_gateway.base import ModelProviderBase
from flux.core.model_gateway.providers.anthropic import AnthropicProvider
from flux.core.model_gateway.providers.deepseek import DeepSeekProvider
from flux.core.model_gateway.providers.echo import EchoProvider
from flux.core.model_gateway.providers.openai import OpenAIProvider
from flux.enums import ModelProvider


def build_providers(settings: Settings) -> dict[ModelProvider, ModelProviderBase]:
    """装配四个供应商。密钥由各适配器自行持有，绝不下发给 Agent（主规格 §14.3）。"""
    return {
        ModelProvider.LOCAL: EchoProvider(model_name=settings.local_model_name),
        ModelProvider.OPENAI: OpenAIProvider(
            model_name=settings.openai_model,
            api_key=settings.openai_api_key,
            base_url=settings.openai_base_url,
            timeout=settings.model_timeout_seconds,
            max_retries=settings.model_max_retries,
        ),
        ModelProvider.ANTHROPIC: AnthropicProvider(
            model_name=settings.anthropic_model,
            api_key=settings.anthropic_api_key,
            base_url=settings.anthropic_base_url,
            timeout=settings.model_timeout_seconds,
            max_retries=settings.model_max_retries,
        ),
        ModelProvider.DEEPSEEK: DeepSeekProvider(
            model_name=settings.deepseek_model,
            api_key=settings.deepseek_api_key,
            base_url=settings.deepseek_base_url,
            timeout=settings.model_timeout_seconds,
            max_retries=settings.model_max_retries,
        ),
    }
