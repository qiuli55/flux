"""WebSocket 鉴权：与 REST 同一套「单用户」边界，但不依赖 Request 依赖。

终端 PTY（`api/v1/terminal.py`）与 LSP 网关（`api/v1/ide.py`）共用这里的两个判断，
避免两处各写一份、日后改一处漏一处。

浏览器不能给 WebSocket 自定义 `Authorization` 头，因此除 Bearer 外还必须认
`flux_auth_token` cookie（`/cloud/exchange`、`/cloud/refresh` 由后端下发 HttpOnly cookie）。
"""

from __future__ import annotations

import hmac
from urllib.parse import urlparse

from fastapi import WebSocket

from flux.api.auth import (
    FORWARDED_HEADERS,
    LOOPBACK_HOSTS,
    is_cloud_access_token,
)


def same_origin_websocket(websocket: WebSocket) -> bool:
    """拒绝与 Host 不同源的浏览器 WebSocket。"""

    origin = websocket.headers.get("origin")
    if not origin:
        # 非浏览器客户端通常不带 Origin；放行，桌面端 / CLI 无需伪装成浏览器。
        return True
    parsed = urlparse(origin)
    host = websocket.headers.get("host", "")
    return parsed.netloc == host


def authenticated_websocket(websocket: WebSocket) -> bool:
    """与 REST 相同的鉴权边界：云令牌 / 本机令牌 / 本机直连。"""

    settings = websocket.app.state.container.settings
    authorization = websocket.headers.get("authorization", "")
    provided = authorization[7:].strip() if authorization.lower().startswith("bearer ") else None
    if not provided:
        # Bearer 为空或没带 Authorization 时退回 cookie，与 REST 的 request_token 一致。
        provided = websocket.cookies.get("flux_auth_token")

    # 第 0 条：账号云 access token（含"属于本实例用户"的绑定校验），与 REST 共用。
    if is_cloud_access_token(settings, provided):
        return True

    expected = (settings.auth_token or "").strip()
    if not expected:
        # 必须鉴权的部署（server / 非回环）不能因为令牌为空而放行；
        # 本地 / 测试 / 回环监听仍保持免配置可用。
        return not settings.auth_required

    client = websocket.client
    has_forwarded = any(websocket.headers.get(name) for name in FORWARDED_HEADERS)
    if client is not None and client.host in LOOPBACK_HOSTS and not has_forwarded:
        return True

    return bool(provided) and hmac.compare_digest(provided, expected)
