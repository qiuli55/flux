"""REST 面的单用户令牌鉴权（公网暴露前置）。

为什么需要：Flux 的 REST 面有 `terminal.execute` 等价能力（终端会话可执行任意命令）、
`workspace.apply`（落盘）、`git.commit`——把 `/api/v1` 裸暴露到公网等于把服务器 shell 交出去。
MCP 面本来就有 Agent 令牌，REST 面此前完全没有身份校验（只在本机用）。

规则（`FLUX_AUTH_TOKEN` 非空才启用，留空 = 关闭，保持本地开发零配置）：

1. 请求带 `Authorization: Bearer <token>` 且与配置值**常量时间**比对通过 → 放行；
2. 例外：来源是回环地址**且**没有任何转发头（`X-Forwarded-For` / `X-Real-IP` / `Forwarded`）
   → 放行。这条例外的用途是保护"同机直连"：桌面端（Electron 主进程反代）、
   `make run` + 本机浏览器、本机 CLI 探活都不必带令牌；
3. 其余一律 401（`unauthenticated`，不区分"没带 / 带错 / 格式不对"——区分等于给探测信号）。

第 2 条是**反向代理配置的硬约束**：反代必须转发 `X-Forwarded-For`（见 `deploy/nginx-flux.conf`
里的 `proxy_set_header`），否则经代理进来的公网请求会以"回环 + 无转发头"的形态被误判为本地直连。
部署脚本用 `curl` 不带令牌复验 401，正是为了盯住这条约束。
"""

from __future__ import annotations

import hmac

from fastapi import Request, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from flux.api.deps import get_container
from flux.errors import AuthenticationError

#: 视为"同机直连"的来源地址。Unix socket 下 ASGI 的 client 为 None，不算回环。
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})

#: 只要出现任一转发头，就说明请求是经代理进来的——不再享有回环例外。
FORWARDED_HEADERS = ("x-forwarded-for", "x-real-ip", "forwarded")

#: 声明成 OpenAPI 的 securityScheme 是为了 /docs 上有 Authorize 按钮：
#: 公网地址的 /docs 不带令牌调任何接口都是 401，没有这个按钮就没法在浏览器里自测。
#: `auto_error=False` 把"没带令牌"交给下面的依赖统一判定，以便保留回环例外。
bearer_scheme = HTTPBearer(
    auto_error=False,
    description="Flux 访问令牌（服务端 FLUX_AUTH_TOKEN）",
)


def _has_forwarded_headers(request: Request) -> bool:
    return any(request.headers.get(name) for name in FORWARDED_HEADERS)


def is_trusted_local(request: Request) -> bool:
    """同机直连（回环 + 无转发头）。"""
    client = request.client
    if client is None or client.host not in LOOPBACK_HOSTS:
        return False
    return not _has_forwarded_headers(request)


def require_rest_auth(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Security(bearer_scheme),
) -> None:
    """`/api/v1` 的统一鉴权依赖。未配置 `FLUX_AUTH_TOKEN` 时整体跳过。"""
    settings = get_container(request).settings
    expected = (settings.auth_token or "").strip()
    if not expected:
        return
    if is_trusted_local(request):
        return
    provided = (
        credentials.credentials
        if credentials is not None
        else request.cookies.get("flux_auth_token")
    )
    # 常量时间比对：不因"前缀对上了"而早退，不给计时侧信道。
    if not provided or not hmac.compare_digest(provided, expected):
        raise AuthenticationError("缺少或无效的访问令牌")
