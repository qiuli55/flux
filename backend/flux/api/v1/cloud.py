"""账号云接入 API（Flux 本体 ↔ B 机 `flux.qiuli55.top` 账号系统）。

这里的端点**不**挂在 `require_rest_auth` 下：浏览器在未登录时也要能读到登录入口，
并用一次性 code 换取本机会话——这正是"先有鸡还是先有蛋"的那一步。

但"客户端登录态"与"服务器后台会话"必须分开（安全审查 P0-2）：

- **客户端**（浏览器 / 手机 / 桌面）拿到的是 Set-Cookie（`flux_auth_token` /
  `flux_refresh_token`，HttpOnly）以及响应 JSON 里的令牌；它们只属于发起请求的客户端。
- **服务器单例** `~/.flux/cloud.json` 是给后台日志上传 / 记忆 / 账单 / 上下文同步用的，
  只有通过本机主人鉴权（回环直连或有效 `FLUX_AUTH_TOKEN`）的请求才能写 / 清它，
  并把当前账号绑成这台机器的主人（见 `flux.cloud.owner`）。

未鉴权的公网请求因此不能清掉服务器会话，也不能用攻击者自己的登录码把后台同步
绑到攻击者账号上。
"""

from __future__ import annotations

import time
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, Field

from flux.api.auth import (
    cloud_access_claims,
    is_cloud_user_allowed,
    is_local_owner_request,
    request_token,
    same_origin_request,
)
from flux.api.deps import get_settings_dep
from flux.api.response import ok
from flux.cloud.owner import CloudOwner, clear_owner, load_owner, save_owner
from flux.cloud.session import (
    CloudClient,
    CloudError,
    CloudSession,
    clear_session,
    save_session,
)
from flux.config import Settings
from flux.errors import AuthenticationError, PermissionDeniedError

router = APIRouter(prefix="/cloud", tags=["cloud"])

#: access token 放在整站路径上：REST 与同源 WebSocket 都要读它。
_ACCESS_COOKIE = "flux_auth_token"
#: refresh token 只发给 /api/v1/cloud 下的端点，收窄泄露面。
_REFRESH_COOKIE = "flux_refresh_token"
_REFRESH_COOKIE_PATH = "/api/v1/cloud"
#: refresh token 的 cookie 寿命（秒）：账号云侧才是权威，这里给 30 天足够。
_REFRESH_COOKIE_MAX_AGE = 30 * 24 * 3600


class CloudExchangeIn(BaseModel):
    code: str = Field(
        min_length=8,
        max_length=256,
        description="账号云登录页回跳时携带的一次性登录码",
    )


class CloudRefreshIn(BaseModel):
    refresh_token: str | None = Field(
        default=None,
        min_length=8,
        max_length=2048,
        description="账号云下发的 refresh token；不传则从 HttpOnly cookie 读取",
    )


def _login_url(settings: Settings) -> str:
    base = (settings.cloud_base_url or "").rstrip("/")
    next_url = (settings.cloud_return_url or "").strip()
    if not next_url:
        return f"{base}/login"
    return f"{base}/login?next={quote(next_url, safe='')}"


def _token_payload(session: CloudSession) -> dict[str, object]:
    """回给客户端的令牌信封（手机 / 桌面仍用 Bearer，Web 走 HttpOnly cookie）。"""
    return {
        "authenticated": True,
        "access_token": session.access_token,
        "refresh_token": session.refresh_token,
        "expires_in": max(0, int(session.expires_at - time.time())),
        "user": {"id": session.user_id, "username": session.username},
    }


def _request_is_https(request: Request) -> bool:
    """按实际协议决定是否加 `Secure`：本地 http 开发不能设，否则 cookie 写不上。"""
    forwarded = request.headers.get("x-forwarded-proto", "")
    if forwarded:
        return forwarded.split(",", 1)[0].strip().lower() == "https"
    return request.url.scheme == "https"


def _set_auth_cookies(response: Response, request: Request, session: CloudSession) -> None:
    """由后端统一下发 HttpOnly cookie；浏览器不再把令牌写进 JS 可读存储。"""
    secure = _request_is_https(request)
    max_age = max(0, int(session.expires_at - time.time()))
    response.set_cookie(
        _ACCESS_COOKIE,
        session.access_token,
        max_age=max_age,
        httponly=True,
        samesite="lax",
        secure=secure,
        path="/",
    )
    response.set_cookie(
        _REFRESH_COOKIE,
        session.refresh_token,
        max_age=_REFRESH_COOKIE_MAX_AGE,
        httponly=True,
        samesite="lax",
        secure=secure,
        path=_REFRESH_COOKIE_PATH,
    )


def _clear_auth_cookies(response: Response, request: Request) -> None:
    secure = _request_is_https(request)
    response.delete_cookie(_ACCESS_COOKIE, path="/", secure=secure, samesite="lax")
    response.delete_cookie(
        _REFRESH_COOKIE,
        path=_REFRESH_COOKIE_PATH,
        secure=secure,
        samesite="lax",
    )


def _remember_server_session(settings: Settings, session: CloudSession) -> None:
    """只有本机主人鉴权通过后才写服务器单例，并同步绑定主人。"""
    save_session(settings.cloud_session_path, session)
    user_id = (session.user_id or "").strip()
    if user_id:
        save_owner(
            settings.cloud_owner_path,
            CloudOwner(user_id=user_id, username=session.username),
        )


#: 账号云可能用来携带展示名的常见 claim；只用于回填**当前客户端自己**的用户名。
_USERNAME_CLAIMS = ("username", "name", "preferred_username")


def _claims_username(claims: dict[str, object]) -> str:
    """从 JWT claims 里取展示名；没有就返回空串（不猜、不查服务器单例）。"""
    for key in _USERNAME_CLAIMS:
        value = claims.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _client_user(
    settings: Settings, claims: dict[str, object], *, username: str = ""
) -> dict[str, str]:
    """客户端自己的用户展示信息。

    username 的来源优先级：本机主人文件（仅当 sub 就是主人）→ 本次会话返回值
    （`CloudSession.username`，允许名单用户靠它才有展示名）→ JWT claims。
    绝不把服务器单例里的主人 username 回填给别的 sub——那会把主人身份泄露出去。
    """
    sub = str(claims.get("sub") or "").strip()
    owner = load_owner(settings.cloud_owner_path)
    if owner is not None and owner.user_id == sub:
        name = owner.username
    else:
        name = (username or "").strip() or _claims_username(claims)
    return {"id": sub, "username": name}


def _configured(settings: Settings) -> bool:
    return bool((settings.cloud_secret_key or "").strip()) or settings.cloud_login_required


def _session_payload(
    settings: Settings, *, authenticated: bool, user: dict[str, str] | None
) -> dict[str, object]:
    return {
        "authenticated": authenticated,
        "user": user,
        "configured": _configured(settings),
        "login_url": _login_url(settings),
    }


@router.get("/session", summary="查询当前客户端的账号云登录状态与登录入口")
async def cloud_session(
    request: Request,
    response: Response,
    settings: Settings = Depends(get_settings_dep),
) -> dict[str, object]:
    """只反映**发起请求的这个客户端**的登录态。

    不读服务器单例里的用户（否则陌生人会拿到本机主人 username），也不拿单例去续期。
    客户端 access token（Authorization 头或 HttpOnly cookie）过期时，用客户端自己的
    refresh cookie 续一次——续期结果仍只回给该客户端；只有本机主人鉴权通过时才顺带
    更新服务器单例。
    """
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    trusted = is_local_owner_request(settings, request)
    # 浏览器只靠 HttpOnly cookie；手机 / 桌面仍可把 access token 放在
    # `Authorization: Bearer` 里（request_token 优先取头，其次 cookie）。
    access = request_token(request)
    refresh = request.cookies.get(_REFRESH_COOKIE)

    claims = cloud_access_claims(settings, access) if access else None
    user: dict[str, str] | None = None
    authenticated = False
    refreshed: CloudSession | None = None

    if claims is not None:
        sub = str(claims.get("sub") or "")
        if is_cloud_user_allowed(settings, sub):
            authenticated = True
            user = _client_user(settings, claims)
    elif refresh:
        # 只续"这个客户端"的会话（refresh 来自它自己的 HttpOnly cookie）。
        try:
            refreshed = await CloudClient(settings.cloud_base_url).refresh(refresh)
        except (CloudError, httpx.HTTPError):
            refreshed = None
        if refreshed is not None:
            if trusted:
                _remember_server_session(settings, refreshed)
            claims = cloud_access_claims(settings, refreshed.access_token)
            sub = str((claims or {}).get("sub") or "")
            if claims is not None and is_cloud_user_allowed(settings, sub):
                authenticated = True
                user = _client_user(settings, claims, username=refreshed.username)

    if refreshed is not None:
        _set_auth_cookies(response, request, refreshed)
    return ok(_session_payload(settings, authenticated=authenticated, user=user))


@router.post("/exchange", summary="用一次性登录码换取本机账号云会话")
async def cloud_exchange(
    request: Request,
    body: CloudExchangeIn,
    response: Response,
    settings: Settings = Depends(get_settings_dep),
) -> dict[str, object]:
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    client = CloudClient(settings.cloud_base_url)
    try:
        session = await client.exchange_code(body.code)
    except (CloudError, httpx.HTTPError) as exc:
        raise AuthenticationError(f"登录码兑换失败：{exc}") from exc

    # 只有本机主人（回环直连 / 有效 FLUX_AUTH_TOKEN）能写服务器单例与绑定主人。
    if is_local_owner_request(settings, request):
        _remember_server_session(settings, session)
    elif not is_cloud_user_allowed(settings, session.user_id):
        # 验签通过但用户不属于本实例：不能假装登录成功。
        # 不种 cookie、不写服务器单例，返回可读错误让前端直接展示——否则前端会
        # 因为随后 GET /session 仍是未登录而把用户送回登录页，形成循环跳转。
        raise PermissionDeniedError(_not_allowed_message(settings))

    _set_auth_cookies(response, request, session)
    return ok(_token_payload(session))


@router.post("/refresh", summary="用 refresh token 续期当前客户端的账号云会话")
async def cloud_refresh(
    request: Request,
    response: Response,
    body: CloudRefreshIn | None = None,
    settings: Settings = Depends(get_settings_dep),
) -> dict[str, object]:
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    # Web 不再把 refresh 放进 JS 可读存储：body 缺省时从 HttpOnly cookie 取。
    refresh_token = (body.refresh_token if body is not None else None) or request.cookies.get(
        _REFRESH_COOKIE
    )
    if not refresh_token:
        raise AuthenticationError("缺少 refresh token")

    client = CloudClient(settings.cloud_base_url)
    try:
        session = await client.refresh(refresh_token)
    except (CloudError, httpx.HTTPError) as exc:
        raise AuthenticationError(f"令牌续期失败：{exc}") from exc

    if is_local_owner_request(settings, request):
        _remember_server_session(settings, session)
    elif not is_cloud_user_allowed(settings, session.user_id):
        # 与 exchange 对称：续期回来的用户也必须属于本实例，否则不种登录 cookie。
        raise PermissionDeniedError(_not_allowed_message(settings))

    _set_auth_cookies(response, request, session)
    return ok(_token_payload(session))


@router.post("/logout", summary="退出当前客户端（只清本客户端 cookie，不解绑本机）")
async def cloud_logout(
    request: Request,
    response: Response,
    settings: Settings = Depends(get_settings_dep),  # noqa: ARG001 - 保留依赖以复用配置注入
) -> dict[str, object]:
    """只清**这个浏览器 / 客户端**的登录 cookie。

    即使用户是同机直连的本机主人，也不在这里清服务器单例或解绑主人——那会影响
    其它设备与后台同步。解除本机绑定是独立且需要显式确认的操作（`POST /cloud/unbind`）。
    """
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    _clear_auth_cookies(response, request)
    return ok({"authenticated": False})


@router.post("/unbind", summary="解除本机账号云绑定（清除服务器会话，需本机主人鉴权）")
async def cloud_unbind(
    request: Request,
    response: Response,
    settings: Settings = Depends(get_settings_dep),
) -> dict[str, object]:
    """清除服务器单例会话并解绑本机主人。

    这是会波及其它设备与后台同步的高影响操作，因此仍由本机主人鉴权把关：
    回环直连（无转发头）或有效 `FLUX_AUTH_TOKEN`。未鉴权的公网请求不能解绑。
    """
    if not same_origin_request(request):
        raise AuthenticationError("跨站请求被拒绝")

    if not is_local_owner_request(settings, request):
        raise AuthenticationError("只有本机主人可以解除本机绑定")

    clear_session(settings.cloud_session_path)
    clear_owner(settings.cloud_owner_path)
    _clear_auth_cookies(response, request)
    return ok({"bound": False})


def _not_allowed_message(settings: Settings) -> str:
    """账号验签通过但不属于本实例时的可展示错误（前端会原样显示）。"""
    if settings.cloud_allowed_user_ids:
        return (
            "当前账号不是本实例允许的用户。请让运维把该账号加入 FLUX_CLOUD_ALLOWED_USERS，"
            "或在本机完成主人绑定后重试。"
        )
    return (
        "当前账号不是本实例允许的用户：本实例还没有绑定主人。"
        "请先在本机（回环直连或带 FLUX_AUTH_TOKEN）完成一次登录绑定主人，"
        "或由运维配置 FLUX_CLOUD_ALLOWED_USERS 后再试。"
    )
