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
from ..config import settings
from ..db import get_db
from ..deps import get_current_user
from ..models import RunLog, User, UserIdentity, UserSession, Workspace, utcnow
from ..schemas import (
    DeleteAccountIn,
    IdentityOut,
    LoginIn,
    MeOut,
    OkOut,
    PasswordChangeIn,
    RegisterIn,
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


def _safe_next(value: str) -> str:
    """只允许站内相对跳转，挡掉 //evil.com 之类。"""
    candidate = (value or "").strip()
    if not candidate.startswith("/") or candidate.startswith("//") or "\\" in candidate:
        return "/account"
    return candidate


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


def _issue_session(db: Session, user: User, request: Request, response: Response) -> None:
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
    _set_cookie(response, ACCESS_COOKIE, create_access_token(user.id), settings.access_ttl_min * 60)
    _set_cookie(response, REFRESH_COOKIE, refresh, settings.refresh_ttl_days * 86400)


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
    state = create_state_token(provider, nonce, _safe_next(next))
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

    target = _safe_next(str(payload.get("next") or "/account"))
    if created:
        target = _with_query(target, "new=1")

    response = RedirectResponse(target)
    _clear_cookie(response, STATE_COOKIE)
    _issue_session(db, user, request, response)
    if initial_password:
        _set_cookie(
            response, REVEAL_COOKIE, create_reveal_token(user.id, initial_password), 15 * 60
        )
    return response
