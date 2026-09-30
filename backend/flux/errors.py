"""领域异常与统一错误码（主规格 §12.10：所有 API 必须有错误码）。

异常 → 统一响应体的映射在 flux.api.errors 中处理。
"""

from __future__ import annotations

from typing import Any


class AIOSError(Exception):
    """所有可预期领域异常的基类。"""

    code: str = "internal_error"
    http_status: int = 500

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        http_status: int | None = None,
        details: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if http_status is not None:
            self.http_status = http_status
        self.details = details


class NotFoundError(AIOSError):
    code = "not_found"
    http_status = 404


class ConflictError(AIOSError):
    code = "conflict"
    http_status = 409


class InvalidTransitionError(ConflictError):
    """Agent / Virtual Workspace 状态机非法跃迁（主规格 §5.1、§7.2）。"""

    code = "invalid_state_transition"


class ValidationError(AIOSError):
    code = "validation_error"
    http_status = 422


class BadRequestError(AIOSError):
    """请求语义非法（如标识串不是合法 UUID）。主规格 §12.10 错误码 400。"""

    code = "bad_request"
    http_status = 400


class PermissionDeniedError(AIOSError):
    code = "permission_denied"
    http_status = 403


class ProviderNotConfiguredError(AIOSError):
    code = "provider_not_configured"
    http_status = 503


class ProviderError(AIOSError):
    """上游模型供应商调用失败（主规格 §5.1 错误处理）。"""

    code = "provider_error"
    http_status = 502


class ProviderAuthError(ProviderError):
    """上游鉴权失败。注意：是本服务对上游的 Key 无效，不是客户端鉴权失败。"""

    code = "provider_auth_error"


class ProviderBadRequestError(ProviderError):
    """上游拒绝了请求（参数、模型名不存在等）。"""

    code = "provider_bad_request"


class ProviderRateLimitedError(ProviderError):
    code = "provider_rate_limited"
    http_status = 503


class ProviderUnavailableError(ProviderError):
    code = "provider_unavailable"
    http_status = 503


class ProviderTimeoutError(ProviderError):
    code = "provider_timeout"
    http_status = 504


class ConnectorNotRegisteredError(AIOSError):
    code = "connector_not_registered"
    http_status = 404


class ApplyFailedError(AIOSError):
    """Apply 在落盘阶段失败（校验不通过 / 跑测试失败）。

    调用方报错时提案已被置为 `failed` 并尽可能回滚原文件，错误详情落在提案的 apply_error。
    """

    code = "apply_failed"
    http_status = 500
