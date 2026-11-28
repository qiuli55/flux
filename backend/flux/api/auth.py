"""REST 面的单用户令牌鉴权（公网暴露前置）。

为什么需要：Flux 的 REST 面有 `terminal.execute` 等价能力（终端会话可执行任意命令）、
`workspace.apply`（落盘）、`git.commit`——把 `/api/v1` 裸暴露到公网等于把服务器 shell 交出去。
MCP 面本来就有 Agent 令牌，REST 面此前完全没有身份校验（只在本机用）。

规则（任一通过即放行）：

0. 带的是**账号云**（B 机 `flux.qiuli55.top`，独立私有仓库）签发的 access token
   （`typ=access`、`exp` 有效、`sub` 非空），且与云共享的 HS256 密钥本地验签通过，
   **并且**该 `sub` 属于本实例：在 `FLUX_CLOUD_ALLOWED_USERS` 里，或等于本机绑定的
   主人（`cloud_owner_file`）。只验签不放行——否则任何账号（包括攻击者自己注册的）
   都能凭一枚合法签名令牌拿到本机 shell；
1. 请求带 `Authorization: Bearer <token>` 且与 `FLUX_AUTH_TOKEN` **常量时间**比对通过 → 放行；
   浏览器也可以把同一枚令牌放在 `flux_auth_token` cookie 里；
2. 例外：来源是回环地址**且**没有任何转发头（`X-Forwarded-For` / `X-Real-IP` / `Forwarded`）
   → 放行。这条例外的用途是保护"同机直连"：桌面端（Electron 主进程反代）、
   `make run` + 本机浏览器、本机 CLI 探活都不必带令牌；
3. 其余一律 401（`unauthenticated`，不区分"没带 / 带错 / 格式不对"——区分等于给探测信号）。

`FLUX_AUTH_TOKEN` 留空**不再等于全开放**：只有 `env` 不是 `server` 且监听回环地址时
才允许 fail-open（本地 `make run`、`env=test`、桌面同机直连）。`env=server` 或监听
`0.0.0.0` / 公网地址时，启动期直接拒绝（见 `validate_auth_configuration`），请求期
也不会因为令牌为空而放行。

第 2 条是**反向代理配置的硬约束**：反代必须转发 `X-Forwarded-For`（见 `deploy/nginx-flux.conf`
里的 `proxy_set_header`），否则经代理进来的公网请求会以"回环 + 无转发头"的形态被误判为本地直连。
部署脚本用 `curl` 不带令牌复验 401，正是为了盯住这条约束。
"""

from __future__ import annotations

import hmac
from urllib.parse import urlparse

from fastapi import Request, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from flux.api.deps import get_container
from flux.cloud.owner import load_owner
from flux.cloud.session import verify_hs256
from flux.config import Settings
from flux.errors import AuthenticationError

#: 视为"同机直连"的来源地址。Unix socket 下 ASGI 的 client 为 None，不算回环。
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})

#: 只要出现任一转发头，就说明请求是经代理进来的——不再享有回环例外。
FORWARDED_HEADERS = ("x-forwarded-for", "x-real-ip", "forwarded")

#: 需要 CSRF 校验的状态改变方法。GET/HEAD/OPTIONS 不改变状态，浏览器跨站也拿不到响应。
_UNSAFE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})

#: 声明成 OpenAPI 的 securityScheme 是为了 /docs 上有 Authorize 按钮：
#: 公网地址的 /docs 不带令牌调任何接口都是 401，没有这个按钮就没法在浏览器里自测。
#: `auto_error=False` 把"没带令牌"交给下面的依赖统一判定，以便保留回环例外。
bearer_scheme = HTTPBearer(
    auto_error=False,
    description="Flux 访问令牌（服务端 FLUX_AUTH_TOKEN 或账号云签发的 access token）",
)

#: 账号云 access token 的 payload 类型标记（见云服务的 security.py）。
_CLOUD_ACCESS_TYP = "access"


def _has_forwarded_headers(request: Request) -> bool:
    return any(request.headers.get(name) for name in FORWARDED_HEADERS)


def is_trusted_local(request: Request) -> bool:
    """同机直连（回环 + 无转发头）。"""
    client = request.client
    if client is None or client.host not in LOOPBACK_HOSTS:
        return False
    return not _has_forwarded_headers(request)


def request_token(request: Request) -> str | None:
    """取请求携带的访问令牌：Authorization 头优先，其次 `flux_auth_token` cookie。"""
    authorization = request.headers.get("authorization", "")
    if authorization.lower().startswith("bearer "):
        value = authorization[7:].strip()
        if value:
            return value
    cookie = request.cookies.get("flux_auth_token")
    return cookie or None


def same_origin_request(request: Request) -> bool:
    """仅靠 cookie 认证的状态改变请求必须与 Host 同源（CSRF 防护）。

    浏览器跨站提交表单 / fetch 时会带 `Origin`（旧浏览器可能只有 `Referer`）；
    非浏览器客户端（手机 / 桌面 / CLI）通常两者都不带，不能误伤。带
    `Authorization` 头的请求不受 cookie 自动附带影响，也跳过校验。
    """
    if request.method.upper() not in _UNSAFE_METHODS:
        return True
    if request.headers.get("authorization"):
        return True
    origin = request.headers.get("origin") or request.headers.get("referer")
    if not origin:
        return True
    host = request.headers.get("host", "")
    if not host:
        return True
    return urlparse(origin).netloc.lower() == host.lower()


def is_local_owner_request(
    settings: Settings, request: Request, *, provided: str | None = None
) -> bool:
    """本机主人鉴权：回环直连，或带了有效的 `FLUX_AUTH_TOKEN`。

    账号云令牌**不算**——它只能证明某个云账号登录过，不能证明它是这台机器的主人。
    绑定主人、写服务器单例会话这类操作只认这个判断。
    """
    if is_trusted_local(request):
        return True
    expected = (settings.auth_token or "").strip()
    if not expected:
        return False
    token = provided if provided is not None else request_token(request)
    return bool(token) and hmac.compare_digest(token, expected)


def cloud_access_claims(settings: Settings, token: str | None) -> dict[str, object] | None:
    """只验 token 本身（签名 / typ / exp / sub）；不判断该用户是否属于本实例。"""
    secret = (settings.cloud_secret_key or "").strip()
    if not secret or not token:
        return None
    payload = verify_hs256(
        token,
        secret,
        issuer=settings.cloud_issuer,
        audience=settings.cloud_audience,
    )
    if payload is None or payload.get("typ") != _CLOUD_ACCESS_TYP:
        return None
    sub = payload.get("sub")
    if not isinstance(sub, str) or not sub.strip():
        return None
    return payload


def is_cloud_user_allowed(settings: Settings, user_id: str) -> bool:
    """该云用户是否属于本实例：在允许名单里，或等于本机绑定主人。"""
    sub = (user_id or "").strip()
    if not sub:
        return False
    allowed = settings.cloud_allowed_user_ids
    if sub in allowed:
        return True
    # 允许名单之外再看本机绑定主人：绑定只会由本机主人鉴权的请求写入，
    # 因此它同样是可信授权来源（两者是"或"的关系，见模块开头第 0 条）。
    owner = load_owner(settings.cloud_owner_path)
    return owner is not None and owner.user_id == sub


def is_cloud_access_token(settings: Settings, token: str | None) -> bool:
    """账号云签发的 access token，且属于本实例允许的用户？本地验签，不每次回源。"""
    payload = cloud_access_claims(settings, token)
    if payload is None:
        return False
    return is_cloud_user_allowed(settings, str(payload.get("sub") or ""))


def validate_auth_configuration(settings: Settings) -> None:
    """启动闸门：该鉴权的部署不允许空 `FLUX_AUTH_TOKEN`。

    只有 "env 不是 server 且监听回环" 才允许空令牌（本地开发 / 测试 / 桌面同机直连）。
    放在 `create_app()` 里而不是只在请求期判断，是为了让配置错误在启动时立刻暴露，
    而不是等公网请求进来才发现整台机器是开放的。
    """
    if (settings.auth_token or "").strip():
        return
    if settings.auth_required:
        raise RuntimeError(
            "拒绝启动：当前部署必须配置 FLUX_AUTH_TOKEN，但它是空的。"
            f"（env={settings.env!r}, listen_host={settings.listen_host!r}）"
            "只有 env 不是 server 且监听回环地址时才允许空令牌的本地/测试模式。"
        )


def require_rest_auth(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Security(bearer_scheme),
) -> None:
    """`/api/v1` 的统一鉴权依赖。"""
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    settings = get_container(request).settings
    provided = (
        credentials.credentials
        if credentials is not None
        else request.cookies.get("flux_auth_token")
    )
    # 第 0 条：账号云登录态。放在最前，让"登录后可用的接口"不依赖 FLUX_AUTH_TOKEN 是否配置。
    if is_cloud_access_token(settings, provided):
        return

    expected = (settings.auth_token or "").strip()
    if not expected:
        # 请求期兜底：即使启动闸门被绕过（例如测试里直接改 settings），
        # 在必须鉴权的部署上也不能因为"没配令牌"就全开放。
        if settings.auth_required:
            raise AuthenticationError("缺少或无效的访问令牌")
        return
    if is_trusted_local(request):
        return
    # 常量时间比对：不因"前缀对上了"而早退，不给计时侧信道。
    if not provided or not hmac.compare_digest(provided, expected):
        raise AuthenticationError("缺少或无效的访问令牌")
