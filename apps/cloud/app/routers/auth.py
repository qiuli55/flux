"""注册 / 登录 / 会话 / 第三方 OAuth。"""
from __future__ import annotations

import logging
import shutil
from datetime import timedelta
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import RedirectResponse
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from .. import oauth
from ..config import is_loopback_redirect, settings, url_origin
from ..db import get_db
from ..deps import get_current_user
from ..models import LoginCode, RunLog, User, UserIdentity, UserSession, Workspace, utcnow
from ..schemas import (
    DeleteAccountIn,
    ExchangeIn,
    HandoffOut,
    IdentityOut,
    LoginIn,
    MeOut,
    OkOut,
    PasswordChangeIn,
    RefreshIn,
    RegisterIn,
    TokenOut,
    UserOut,
)
from ..security import (
    ACCESS_COOKIE,
    REFRESH_COOKIE,
    REVEAL_COOKIE,
    STATE_COOKIE,
    constant_time_equals,
    create_access_token,
    create_reveal_token,
    create_state_token,
    decode_token,
    generate_readable_password,
    hash_password,
    new_login_code,
    new_nonce,
    new_refresh_token,
    sha256_hex,
    username_from_email,
    verify_password,
)

router = APIRouter(prefix="/api/auth", tags=["auth"])
log = logging.getLogger("flux_cloud.auth")


# ---------------------------------------------------------------- 内部工具

def _client_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64] or None
    host = request.client.host if request.client else ""
    return host[:64] or None


def _set_cookie(response: Response, key: str, value: str, max_age: int) -> None:
    response.set_cookie(
        key,
        value,
        max_age=max_age,
        httponly=True,
        secure=settings.cookie_secure,
        samesite="lax",
        domain=settings.cookie_domain or None,
        path="/",
    )


def _clear_cookie(response: Response, key: str) -> None:
    response.delete_cookie(key, path="/", domain=settings.cookie_domain or None)


def _resolve_next(value: str) -> str:
    """登录后跳哪里：站内相对路径放行，绝对地址须是受信任站点或本机回环地址，否则回账号页。"""
    candidate = (value or "").strip()
    if not candidate:
        return "/account"
    if candidate.startswith("/") and not candidate.startswith("//") and "\\" not in candidate:
        return candidate
    if settings.is_trusted_return_origin(candidate) or is_loopback_redirect(candidate):
        return candidate
    return "/account"


def _is_external_target(target: str) -> bool:
    return target.startswith("http://") or target.startswith("https://")


def _handoff_target(db: Session, user: User, target: str) -> str:
    """站外目标不能靠 cookie 带身份过去，改发一次性登录码让对方换 token。"""
    if not _is_external_target(target):
        return target
    code = new_login_code()
    db.add(
        LoginCode(
            user_id=user.id,
            code_hash=sha256_hex(code),
            redirect_uri=target[:512],
            expires_at=utcnow() + timedelta(seconds=settings.login_code_ttl_sec),
        )
    )
    db.commit()
    log.info("签发跨站登录码 user_id=%s origin=%s", user.id, url_origin(target))
    return _with_query(target, f"code={quote(code)}")


def _with_query(url: str, extra: str) -> str:
    return f"{url}{'&' if '?' in url else '?'}{extra}"


def _unique_username(db: Session, base: str) -> str:
    base = (base or "flux")[:24]
    candidate = base
    suffix = 1
    while db.scalar(select(User.id).where(User.username == candidate)):
        suffix += 1
        candidate = f"{base}{suffix}"[:64]
    return candidate


def _start_session(db: Session, user: User, request: Request) -> str:
    """建一条登录会话记录并返回 refresh token 明文。"""
    refresh = new_refresh_token()
    db.add(
        UserSession(
            user_id=user.id,
            refresh_hash=sha256_hex(refresh),
            user_agent=(request.headers.get("user-agent") or "")[:400] or None,
            ip=_client_ip(request),
            expires_at=utcnow() + timedelta(days=settings.refresh_ttl_days),
        )
    )
    user.last_login_at = utcnow()
    db.commit()
    return refresh


def _issue_session(db: Session, user: User, request: Request, response: Response) -> None:
    refresh = _start_session(db, user, request)
    _set_cookie(response, ACCESS_COOKIE, create_access_token(user.id), settings.access_ttl_min * 60)
    _set_cookie(response, REFRESH_COOKIE, refresh, settings.refresh_ttl_days * 86400)


def _token_payload(user: User, refresh: str) -> TokenOut:
    return TokenOut(
        access_token=create_access_token(user.id),
        refresh_token=refresh,
        expires_in=settings.access_ttl_min * 60,
        user=UserOut.model_validate(user),
    )


def _me_payload(db: Session, user: User) -> MeOut:
    identities = db.scalars(
        select(UserIdentity).where(UserIdentity.user_id == user.id).order_by(UserIdentity.id)
    ).all()
    return MeOut(
        user=UserOut.model_validate(user),
        identities=[IdentityOut.model_validate(item) for item in identities],
        providers={
            "github": settings.provider_enabled("github"),
            "google": settings.provider_enabled("google"),
        },
    )


def _upsert_oauth_user(
    db: Session, profile: oauth.ProviderProfile
) -> tuple[User, bool, str | None]:
    """返回 (用户, 是否本次新建, 初始密码或 None)。"""
    identity = db.scalar(
        select(UserIdentity).where(
            UserIdentity.provider == profile.provider,
            UserIdentity.provider_uid == profile.provider_uid,
        )
    )
    if identity is not None:
        user = db.get(User, identity.user_id)
        identity.provider_email = profile.email or identity.provider_email
        identity.provider_login = profile.login or identity.provider_login
        if user is not None and profile.avatar_url and not user.avatar_url:
            user.avatar_url = profile.avatar_url
        db.commit()
        return user, False, None  # type: ignore[return-value]

    existing = None
    if profile.email and profile.email_verified:
        existing = db.scalar(select(User).where(func.lower(User.email) == profile.email.lower()))

    if existing is not None:
        # 邮箱已存在同一个人：把这次登录渠道绑定上去，不另建账号
        db.add(
            UserIdentity(
                user_id=existing.id,
                provider=profile.provider,
                provider_uid=profile.provider_uid,
                provider_email=profile.email,
                provider_login=profile.login,
            )
        )
        if profile.avatar_url and not existing.avatar_url:
            existing.avatar_url = profile.avatar_url
        db.commit()
        return existing, False, None

    # 全新用户：自动建号，并生成一份用户可见的初始密码
    base = username_from_email(profile.email or profile.login)
    username = _unique_username(db, base)
    initial_password = generate_readable_password()
    user = User(
        email=(profile.email.lower() if profile.email else None),
        username=username,
        display_name=profile.name or username,
        avatar_url=profile.avatar_url,
        password_hash=hash_password(initial_password),
        must_change_password=True,
    )
    db.add(user)
    db.flush()
    db.add(
        UserIdentity(
            user_id=user.id,
            provider=profile.provider,
            provider_uid=profile.provider_uid,
            provider_email=profile.email,
            provider_login=profile.login,
        )
    )
    db.add(Workspace(user_id=user.id, name=f"{username} 的空间", kind="personal"))
    db.commit()
    return user, True, initial_password


# ---------------------------------------------------------------- 注册 / 登录

@router.get("/providers")
def providers() -> dict[str, bool]:
    return {
        "github": settings.provider_enabled("github"),
        "google": settings.provider_enabled("google"),
    }


@router.post("/register", response_model=MeOut)
def register(
    payload: RegisterIn,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
) -> MeOut:
    if db.scalar(select(User.id).where(func.lower(User.email) == payload.email)):
        raise HTTPException(status.HTTP_409_CONFLICT, "该邮箱已注册，请直接登录")
    if payload.username:
        if db.scalar(select(User.id).where(User.username == payload.username)):
            raise HTTPException(status.HTTP_409_CONFLICT, "该用户名已被占用")
        username = payload.username
    else:
        username = _unique_username(db, username_from_email(payload.email))

    user = User(
        email=payload.email,
        username=username,
        display_name=payload.display_name or username,
        password_hash=hash_password(payload.password),
    )
    db.add(user)
    db.flush()
    db.add(Workspace(user_id=user.id, name=f"{username} 的空间", kind="personal"))
    db.commit()
    _issue_session(db, user, request, response)
    return _me_payload(db, user)


@router.post("/login", response_model=MeOut)
def login(
    payload: LoginIn,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
) -> MeOut:
    identifier = payload.identifier.strip()
    user = db.scalar(
        select(User).where(
            (func.lower(User.email) == identifier.lower()) | (User.username == identifier)
        )
    )
    if user is None or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "账号或密码不正确")
    if not user.is_active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "账号已被禁用")
    _issue_session(db, user, request, response)
    return _me_payload(db, user)


@router.post("/refresh", response_model=OkOut)
def refresh(request: Request, response: Response, db: Session = Depends(get_db)) -> OkOut:
    token = request.cookies.get(REFRESH_COOKIE)
    if not token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "缺少会话凭证")
    session = db.scalar(select(UserSession).where(UserSession.refresh_hash == sha256_hex(token)))
    if session is None or session.revoked_at is not None or session.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "会话已过期，请重新登录")
    user = db.get(User, session.user_id)
    if user is None or not user.is_active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "账号不可用")
    session.last_used_at = utcnow()
    db.commit()
    _set_cookie(response, ACCESS_COOKIE, create_access_token(user.id), settings.access_ttl_min * 60)
    return OkOut(message="已续期")


@router.post("/logout", response_model=OkOut)
def logout(request: Request, response: Response, db: Session = Depends(get_db)) -> OkOut:
    token = request.cookies.get(REFRESH_COOKIE)
    if token:
        session = db.scalar(select(UserSession).where(UserSession.refresh_hash == sha256_hex(token)))
        if session is not None and session.revoked_at is None:
            session.revoked_at = utcnow()
            db.commit()
    _clear_cookie(response, ACCESS_COOKIE)
    _clear_cookie(response, REFRESH_COOKIE)
    _clear_cookie(response, REVEAL_COOKIE)
    return OkOut(message="已退出登录")


@router.post("/delete", response_model=OkOut)
def delete_account(
    payload: DeleteAccountIn,
    response: Response,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> OkOut:
    """彻底注销：删除账号及其全部数据（身份绑定、会话、工作区、运行日志），不可恢复。

    同一邮箱之后可以重新注册。
    """
    # 二次确认：必须原样输入用户名
    if payload.confirm_username.strip() != user.username:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "请输入完整用户名以确认注销")
    # 与修改密码一致：已经自己设过密码的用户必须验旧密码
    if user.password_hash and not user.must_change_password:
        if not payload.password:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "请输入当前密码")
        if not verify_password(payload.password, user.password_hash):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "当前密码不正确")

    user_id = user.id
    username = user.username
    # 显式逐表删除，不依赖 SQLite 的外键级联，行为更确定
    db.execute(delete(UserSession).where(UserSession.user_id == user_id))
    db.execute(delete(LoginCode).where(LoginCode.user_id == user_id))
    db.execute(delete(RunLog).where(RunLog.user_id == user_id))
    db.execute(delete(Workspace).where(Workspace.user_id == user_id))
    db.execute(delete(UserIdentity).where(UserIdentity.user_id == user_id))
    db.execute(delete(User).where(User.id == user_id))
    db.commit()
    # 日志包文件本体落在 settings.log_dir/<user_id>/
    shutil.rmtree(settings.log_dir / str(user_id), ignore_errors=True)

    _clear_cookie(response, ACCESS_COOKIE)
    _clear_cookie(response, REFRESH_COOKIE)
    _clear_cookie(response, REVEAL_COOKIE)
    log.info("账号已注销：user_id=%s username=%s", user_id, username)
    return OkOut(message="账号已注销")


@router.get("/me", response_model=MeOut)
def me(db: Session = Depends(get_db), user: User = Depends(get_current_user)) -> MeOut:
    return _me_payload(db, user)


@router.post("/password", response_model=OkOut)
def change_password(
    payload: PasswordChangeIn,
    response: Response,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> OkOut:
    # 第三方首登自动建号的用户可以直接改密（初始密码只是给用户看的）；
    # 已经自己设过密码的用户必须验旧密码。
    if user.password_hash and not user.must_change_password:
        if not payload.current_password:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "请输入当前密码")
        if not verify_password(payload.current_password, user.password_hash):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "当前密码不正确")
    user.password_hash = hash_password(payload.new_password)
    user.must_change_password = False
    db.commit()
    _clear_cookie(response, REVEAL_COOKIE)
    return OkOut(message="密码已更新")


# ---------------------------------------------------------------- 初始密码展示

@router.get("/reveal")
def reveal(request: Request) -> dict[str, str | None]:
    token = request.cookies.get(REVEAL_COOKIE)
    payload = decode_token(token) if token else None
    if not payload or payload.get("typ") != "reveal":
        return {"password": None}
    return {"password": payload.get("pwd")}


@router.post("/reveal/ack", response_model=OkOut)
def reveal_ack(response: Response) -> OkOut:
    _clear_cookie(response, REVEAL_COOKIE)
    return OkOut(message="已确认收到初始密码")


# ---------------------------------------------------------------- OAuth

@router.get("/oauth/{provider}/start")
def oauth_start(provider: str, next: str = "/account") -> RedirectResponse:
    if not settings.provider_enabled(provider):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "该登录渠道未启用")
    nonce = new_nonce()
    state = create_state_token(provider, nonce, _resolve_next(next))
    response = RedirectResponse(oauth.authorize_url(provider, state))
    _set_cookie(response, STATE_COOKIE, state, 600)
    return response


@router.get("/oauth/{provider}/callback")
async def oauth_callback(
    provider: str,
    request: Request,
    code: str = "",
    state: str = "",
    error: str = "",
    error_description: str = "",
    db: Session = Depends(get_db),
) -> RedirectResponse:
    if not settings.provider_enabled(provider):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "该登录渠道未启用")

    def fail(message: str) -> RedirectResponse:
        return RedirectResponse(f"/login?error={quote(message)}")

    if error:
        return fail(error_description or error)
    if not code:
        return fail("第三方未返回授权码，请重试")

    cookie_state = request.cookies.get(STATE_COOKIE) or ""
    payload = decode_token(state) if state else None
    if (
        not cookie_state
        or not state
        or not constant_time_equals(cookie_state, state)
        or not payload
        or payload.get("typ") != "state"
        or payload.get("provider") != provider
    ):
        return fail("登录状态校验失败，请重新发起登录")

    try:
        profile = await oauth.fetch_profile(provider, code)
    except oauth.OAuthError as exc:
        log.warning("OAuth 换取资料失败 provider=%s err=%s", provider, exc)
        return fail("第三方登录失败，请稍后重试")

    user, created, initial_password = _upsert_oauth_user(db, profile)
    if user is None or not user.is_active:
        return fail("账号不可用")

    target = _resolve_next(str(payload.get("next") or "/account"))
    if created:
        target = _with_query(target, "new=1")
    # 站外目标（Flux 工作台）走一次性登录码
    target = _handoff_target(db, user, target)

    response = RedirectResponse(target)
    _clear_cookie(response, STATE_COOKIE)
    _issue_session(db, user, request, response)
    if initial_password:
        _set_cookie(
            response, REVEAL_COOKIE, create_reveal_token(user.id, initial_password), 15 * 60
        )
    return response


# ---------------------------------------------------------------- 跨站登录回跳
#
# 站外站点（Flux 本体工作台）拿不到这里的 httponly cookie，所以走三步：
#   1. 登录成功后前端调 GET /handoff?next=<目标>，换到带一次性 code 的回跳地址；
#   2. 目标站点拿 code 调 POST /exchange，换回 access / refresh token；
#   3. access 过期后调 POST /token 用 refresh 续期。
# 两边的签名密钥一致，目标站点可以本地验签，不必每次回源。

@router.get("/handoff", response_model=HandoffOut)
def handoff(
    request: Request,
    next: str = "/account",
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> HandoffOut:
    target = _resolve_next(next)
    return HandoffOut(redirect=_handoff_target(db, user, target))


@router.post("/exchange", response_model=TokenOut)
def exchange(
    payload: ExchangeIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokenOut:
    record = db.scalar(select(LoginCode).where(LoginCode.code_hash == sha256_hex(payload.code.strip())))
    if record is None or record.used_at is not None or record.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "登录码无效或已过期，请重新登录")
    user = db.get(User, record.user_id)
    if user is None or not user.is_active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "账号不可用")
    record.used_at = utcnow()
    refresh = _start_session(db, user, request)
    log.info("跨站登录码已兑换 user_id=%s", user.id)
    return _token_payload(user, refresh)


@router.post("/token", response_model=TokenOut)
def token(
    payload: RefreshIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokenOut:
    """用 refresh token 换新的 access token（站外客户端的续期入口）。"""
    refresh = payload.refresh_token.strip()
    session = db.scalar(select(UserSession).where(UserSession.refresh_hash == sha256_hex(refresh)))
    if session is None or session.revoked_at is not None or session.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "会话已过期，请重新登录")
    user = db.get(User, session.user_id)
    if user is None or not user.is_active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "账号不可用")
    session.last_used_at = utcnow()
    db.commit()
    return _token_payload(user, refresh)
