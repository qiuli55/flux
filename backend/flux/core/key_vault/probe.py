"""密钥库连通性探测：用该 key 登记的**每个**模型与端点真实发起一次最小对话。

用途是"保存 key 时确认这个端点 / 这把 API Key / 这些模型真的能用"：Base URL 可达、
鉴权通过、模型 id 存在。探测本身不抛异常——失败统一折叠成按模型的结果列表，
把"保存成功但连通性异常"与"保存失败"区分开。

两处口径：
- 重试次数固定 0：保存是交互动作，不能让用户等退避重试；
- 输出上限至少 512：DeepSeek thinking 模式把推理 token 计入 `max_tokens`，
  给太小（如 32）会让 `content` 为空，实测 512 可用。

失败原因按 HTTP 状态与上游错误体归到五类（`invalid_key` / `model_unavailable` /
`quota_exhausted` / `network_unreachable` / `other`），供三端展示与选择器标记。
探测结果按模型写回 `api_keys.model_connectivity`，**不会**回滚、删除或停用配置。
"""

from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone
from typing import Any

import httpx

from flux.core.agent_runtime.runtimes.base import ModelChannel
from flux.core.key_vault.service import KeyVaultService
from flux.core.model_gateway.base import ChatMessage
from flux.core.model_gateway.providers.anthropic import AnthropicProvider
from flux.core.model_gateway.providers.deepseek import DeepSeekProvider
from flux.core.model_gateway.providers.openai import OpenAIProvider
from flux.core.model_gateway.providers.openai_compatible import OpenAICompatibleProvider
from flux.errors import AIOSError

#: 探测用输出上限（原因见模块说明，不能小）
PROBE_MAX_TOKENS = 512

#: 探测超时（秒）：比常规对话短，避免保存动作卡住管理面
PROBE_TIMEOUT = 20.0

#: 同一条 key 下并行探测的模型上限：够快，又不会把上游打爆
PROBE_MAX_CONCURRENCY = 4

_PROBE_PROMPT = "只回复两个字：可用"

# --- 稳定的失败原因码（三端按它做文案与标记） ---

REASON_INVALID_KEY = "invalid_key"
REASON_MODEL_UNAVAILABLE = "model_unavailable"
REASON_QUOTA_EXHAUSTED = "quota_exhausted"
REASON_NETWORK_UNREACHABLE = "network_unreachable"
REASON_OTHER = "other"

REASON_TEXTS: dict[str, str] = {
    REASON_INVALID_KEY: "密钥无效或权限不足",
    REASON_MODEL_UNAVAILABLE: "模型名不存在或不可用",
    REASON_QUOTA_EXHAUSTED: "额度用尽",
    REASON_NETWORK_UNREACHABLE: "网络或服务不可达",
    REASON_OTHER: "其他失败",
}

#: 上游错误体里指向"模型名不对 / 模型无权访问"的标记
_MODEL_ERROR_MARKERS = (
    "model not found",
    "model_not_found",
    "unknown model",
    "model does not exist",
    "model doesn't exist",
    "no such model",
    "invalid model",
    "unsupported model",
    "model is not available",
    "model not available",
    "model_not_available",
    "not a valid model",
    "model unavailable",
    "模型不存在",
    "模型不可用",
    "模型名",
)

#: 上游错误体里指向"额度 / 余额"的标记
_QUOTA_MARKERS = (
    "insufficient_quota",
    "insufficient quota",
    "quota exceeded",
    "quota_exceeded",
    "exceeded your current quota",
    "billing",
    "credit",
    "balance",
    "余额",
    "额度",
)

#: 上游错误体里指向"网络 / 服务不可达"的标记
_NETWORK_MARKERS = (
    "timeout",
    "timed out",
    "connection",
    "connect error",
    "connect failed",
    "name or service not known",
    "temporary failure in name resolution",
    "network",
    "unreachable",
    "temporarily unavailable",
    "service unavailable",
)

#: 上游错误体里指向"鉴权 / 权限"的标记
_AUTH_MARKERS = (
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "authentication",
    "unauthorized",
    "permission denied",
    "permission_denied",
    "forbidden",
)

#: 领域错误码 → 给人看的一句话（保留兼容；新分类见 `classify_probe_failure`）
_FRIENDLY_ERRORS = {
    "provider_auth_error": "鉴权失败：API Key 无效或未授权",
    "provider_bad_request": "上游拒绝了请求：模型 id 或请求参数可能不对",
    "provider_rate_limited": "上游限流：请稍后重试",
    "provider_unavailable": "上游不可用：端点无法访问或服务异常",
    "provider_timeout": "请求超时：端点无响应",
    "provider_not_configured": "该密钥缺少可用的调用凭据",
    "configuration_error": "配置不完整，无法发起请求",
}


class _OpenAICompatibleProbe(OpenAICompatibleProvider):
    """`openai_compatible` 在 `ModelProvider` 枚举里没有对应值，探测时用字符串占位。

    只有错误文案与日志会用到它（`str(self.provider)`），不参与任何路由。
    """

    provider = "openai_compatible"  # type: ignore[assignment]


def _build_provider(
    channel: ModelChannel,
    model: str,
    *,
    timeout: float,
    client: httpx.AsyncClient,
) -> OpenAICompatibleProvider | AnthropicProvider:
    if channel.provider == "anthropic":
        return AnthropicProvider(
            model,
            api_key=channel.api_key,
            base_url=channel.base_url,
            timeout=timeout,
            max_retries=0,
            client=client,
        )
    if channel.provider == "openai":
        return OpenAIProvider(
            model,
            api_key=channel.api_key,
            base_url=channel.base_url,
            timeout=timeout,
            max_retries=0,
            client=client,
        )
    if channel.provider == "deepseek":
        return DeepSeekProvider(
            model,
            api_key=channel.api_key,
            base_url=channel.base_url,
            timeout=timeout,
            max_retries=0,
            client=client,
        )
    return _OpenAICompatibleProbe(
        model,
        api_key=channel.api_key,
        base_url=channel.base_url,
        timeout=timeout,
        max_retries=0,
        client=client,
    )


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _blank_result(provider: str, model: str, base_url: str) -> dict[str, Any]:
    return {
        "ok": False,
        "status": "unreachable",
        "skipped": False,
        "provider": provider,
        "model": model,
        "base_url": base_url,
        "latency_ms": 0,
        "reply": "",
        "error": "",
        "error_code": "",
        "reason_code": "",
        "reason": "",
        "detail": "",
        "checked_at": "",
    }


def _short_summary(message: str, *, limit: int = 160) -> str:
    """从领域异常 message 里截一段可读摘要；不写密钥、不塞长堆栈。"""
    text = " ".join((message or "").split())
    return text[:limit]


def _redact_secret(text: str, secret: str) -> str:
    """上游错误体偶尔会回显 key；探测结果只保留末位掩码。"""
    if not text or not secret:
        return text
    masked = f"••••{secret[-4:]}" if len(secret) >= 8 else "••••"
    return text.replace(secret, masked)


def classify_probe_failure(
    *,
    error_code: str = "",
    status: int | None = None,
    message: str = "",
) -> tuple[str, str]:
    """把上游失败归到五类原因，返回 `(reason_code, 中文原因)`。

    分类只看 HTTP 状态与错误摘要，不读取或回显密钥。限流（429）在错误体
    没有额度字样时归入 `other`，不会被误标成密钥无效或模型不存在。
    """
    text = (message or "").lower()

    if (
        error_code in ("provider_auth_error", "provider_not_configured")
        or status in (401, 403)
        or any(marker in text for marker in _AUTH_MARKERS)
    ):
        return REASON_INVALID_KEY, REASON_TEXTS[REASON_INVALID_KEY]

    if status == 402 or any(marker in text for marker in _QUOTA_MARKERS):
        return REASON_QUOTA_EXHAUSTED, REASON_TEXTS[REASON_QUOTA_EXHAUSTED]

    model_error = any(marker in text for marker in _MODEL_ERROR_MARKERS) or (
        "model" in text
        and any(phrase in text for phrase in ("does not exist", "not found", "not available"))
    )
    if model_error:
        return REASON_MODEL_UNAVAILABLE, REASON_TEXTS[REASON_MODEL_UNAVAILABLE]

    if (
        error_code in ("provider_timeout", "provider_unavailable")
        or status in (502, 503, 504)
        or any(marker in text for marker in _NETWORK_MARKERS)
    ):
        return REASON_NETWORK_UNREACHABLE, REASON_TEXTS[REASON_NETWORK_UNREACHABLE]

    summary = _short_summary(message)
    if summary:
        return REASON_OTHER, f"{REASON_TEXTS[REASON_OTHER]}：{summary}"
    return REASON_OTHER, REASON_TEXTS[REASON_OTHER]


def _status_of(exc: AIOSError) -> int | None:
    details = exc.details if isinstance(exc.details, dict) else {}
    status = details.get("status")
    return status if isinstance(status, int) else None


async def probe_channel(
    channel: ModelChannel,
    model: str,
    *,
    timeout: float = PROBE_TIMEOUT,
    client: httpx.AsyncClient | None = None,
) -> dict[str, Any]:
    """对一条已解析的模型通道发起最小请求；永不抛异常。"""
    result = _blank_result(channel.provider, model, channel.base_url)
    result["checked_at"] = _now_iso()
    started = time.perf_counter()
    owned = client is None
    if client is None:
        client = httpx.AsyncClient()
    try:
        provider = _build_provider(channel, model, timeout=timeout, client=client)
        reply = await provider.chat(
            [ChatMessage(role="user", content=_PROBE_PROMPT)],
            max_tokens=PROBE_MAX_TOKENS,
        )
    except AIOSError as exc:
        status = _status_of(exc)
        result["error_code"] = exc.code
        if status == 402:
            result["error"] = "账户余额不足（上游返回 HTTP 402），请充值后再试"
        else:
            result["error"] = _FRIENDLY_ERRORS.get(exc.code, exc.message)
        result["detail"] = _short_summary(exc.message, limit=500)
        reason_code, reason = classify_probe_failure(
            error_code=exc.code, status=status, message=exc.message
        )
        result["reason_code"] = reason_code
        result["reason"] = reason
    except Exception as exc:  # noqa: BLE001 - 探测是管理面辅助动作，任何异常都只能折叠成结果
        result["error_code"] = "probe_failed"
        result["error"] = f"探测失败：{exc}"[:500]
        result["detail"] = str(exc)[:500]
        result["reason_code"] = REASON_OTHER
        result["reason"] = f"{REASON_TEXTS[REASON_OTHER]}：{_short_summary(str(exc))}"
    else:
        result["ok"] = True
        result["status"] = "available"
        result["reply"] = (reply.content or "").strip()[:200]
    finally:
        if owned:
            await client.aclose()
        result["latency_ms"] = int((time.perf_counter() - started) * 1000)
        for key in ("error", "detail", "reason"):
            result[key] = _redact_secret(str(result.get(key) or ""), channel.api_key)
    return result


def _empty_model_result(provider: str, model: str, base_url: str) -> dict[str, Any]:
    """空模型名：不发网，直接标"模型名不存在或不可用"。"""
    result = _blank_result(provider, model, base_url)
    result["checked_at"] = _now_iso()
    result["error_code"] = "empty_model"
    result["error"] = "模型名为空，请补全后再校验"
    result["reason_code"] = REASON_MODEL_UNAVAILABLE
    result["reason"] = REASON_TEXTS[REASON_MODEL_UNAVAILABLE]
    return result


def _config_error_result(provider: str, model: str, base_url: str, message: str) -> dict[str, Any]:
    result = _blank_result(provider, model, base_url)
    result["checked_at"] = _now_iso()
    result["error_code"] = "configuration_error"
    result["error"] = _short_summary(message, limit=500)
    reason_code, reason = classify_probe_failure(error_code="configuration_error", message=message)
    result["reason_code"] = reason_code
    result["reason"] = reason
    return result


def _registered_models(row: Any) -> list[str]:
    """按登记顺序去重；保留空串（由调用方标成模型名不可用，而不是悄悄丢掉）。"""
    models: list[str] = []
    for item in row.models or []:
        value = str(item)
        if value not in models:
            models.append(value)
    return models


async def _probe_one(
    vault: KeyVaultService,
    row: Any,
    model: str,
    *,
    timeout: float,
    client: httpx.AsyncClient,
    semaphore: asyncio.Semaphore,
) -> dict[str, Any]:
    wanted = model.strip()
    if not wanted:
        return _empty_model_result(row.provider, wanted, row.base_url)
    async with semaphore:
        try:
            channel = await vault.channel_for_model(row, wanted)
        except AIOSError as exc:
            return _config_error_result(row.provider, wanted, row.base_url, exc.message)
        return await probe_channel(channel, wanted, timeout=timeout, client=client)


async def probe_vault_key(
    vault: KeyVaultService,
    key_id: str,
    *,
    timeout: float = PROBE_TIMEOUT,
    client: httpx.AsyncClient | None = None,
    persist: bool = True,
) -> dict[str, Any]:
    """按 key 库条目探测它登记的每一个模型。

    - 停用 / 没有模型的条目直接返回 `skipped=True`，不发起网络请求；
    - 同一把 key 下的模型并发探测（上限 `PROBE_MAX_CONCURRENCY`）；
    - 探测结果按模型写回 `model_connectivity`，失败也不影响配置本身。
    """
    row = await vault.get(key_id)
    models = _registered_models(row)
    summary: dict[str, Any] = {
        "ok": False,
        "skipped": False,
        "provider": row.provider,
        "base_url": row.base_url,
        "key_id": str(row.id),
        "checked_at": _now_iso(),
        "models": [],
        # 兼容旧调用方的汇总字段；前端主展示必须用 models
        "model": models[0].strip() if models else "",
        "latency_ms": 0,
        "reply": "",
        "error": "",
        "error_code": "",
        "detail": "",
    }
    if not row.enabled:
        summary["skipped"] = True
        summary["error_code"] = "key_disabled"
        summary["error"] = "密钥已停用，未发起连通性校验"
        return summary
    if not models:
        summary["skipped"] = True
        summary["error_code"] = "no_model"
        summary["error"] = "该密钥没有登记任何模型，无法校验"
        return summary

    owned = client is None
    if client is None:
        client = httpx.AsyncClient()
    semaphore = asyncio.Semaphore(PROBE_MAX_CONCURRENCY)
    try:
        results = await asyncio.gather(
            *(
                _probe_one(
                    vault,
                    row,
                    model,
                    timeout=timeout,
                    client=client,
                    semaphore=semaphore,
                )
                for model in models
            )
        )
    finally:
        if owned:
            await client.aclose()

    model_results = list(results)
    summary["models"] = model_results
    summary["ok"] = bool(model_results) and all(item["ok"] for item in model_results)
    summary["latency_ms"] = max((int(item["latency_ms"]) for item in model_results), default=0)
    summary["reply"] = next((item["reply"] for item in model_results if item["reply"]), "")
    first_failure = next((item for item in model_results if not item["ok"]), None)
    if first_failure is not None:
        summary["model"] = first_failure["model"]
        summary["error"] = first_failure["reason"] or first_failure["error"]
        summary["error_code"] = first_failure["error_code"]
        summary["detail"] = first_failure["detail"]
    if persist:
        await vault.record_connectivity(str(row.id), model_results)
    return summary


__all__ = [
    "PROBE_MAX_CONCURRENCY",
    "PROBE_MAX_TOKENS",
    "PROBE_TIMEOUT",
    "REASON_INVALID_KEY",
    "REASON_MODEL_UNAVAILABLE",
    "REASON_NETWORK_UNREACHABLE",
    "REASON_OTHER",
    "REASON_QUOTA_EXHAUSTED",
    "REASON_TEXTS",
    "classify_probe_failure",
    "probe_channel",
    "probe_vault_key",
]
