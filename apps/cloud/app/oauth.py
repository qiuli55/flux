"""第三方登录（GitHub / Google）的最小实现。

只做「拿 code → 换 token → 取资料」三步，不保存第三方 token：
Flux 云服务不需要长期调用对方 API，也就不承担 token 泄露风险。
"""
from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import urlencode

import httpx

from .config import settings

_TIMEOUT = httpx.Timeout(15.0, connect=8.0)


@dataclass
class ProviderProfile:
    provider: str
    provider_uid: str
    email: str | None
    email_verified: bool
    name: str | None
    avatar_url: str | None
    login: str | None


class OAuthError(RuntimeError):
    pass


# ---------------------------------------------------------------- GitHub

GITHUB_AUTHORIZE = "https://github.com/login/oauth/authorize"
GITHUB_TOKEN = "https://github.com/login/oauth/access_token"
GITHUB_API = "https://api.github.com"


async def _github_profile(code: str) -> ProviderProfile:
    redirect_uri = settings.oauth_redirect_uri("github")
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(
            GITHUB_TOKEN,
            headers={"Accept": "application/json"},
            data={
                "client_id": settings.github_client_id,
                "client_secret": settings.github_client_secret,
                "code": code,
                "redirect_uri": redirect_uri,
            },
        )
        data = _as_json(resp, "GitHub 换取 token 失败")
        access_token = data.get("access_token")
        if not access_token:
            raise OAuthError(f"GitHub 未返回 access_token: {data.get('error_description') or data}")

        headers = {
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "flux-cloud",
        }
        user_resp = await client.get(f"{GITHUB_API}/user", headers=headers)
        user = _as_json(user_resp, "GitHub 读取用户资料失败")

        email = user.get("email")
        verified = bool(email)
        if not email:
            emails_resp = await client.get(f"{GITHUB_API}/user/emails", headers=headers)
            if emails_resp.status_code == 200:
                emails = emails_resp.json() or []
                primary = next(
                    (e for e in emails if e.get("primary") and e.get("verified")),
                    None,
                ) or next((e for e in emails if e.get("verified")), None)
                if primary:
                    email = primary.get("email")
                    verified = True

    return ProviderProfile(
        provider="github",
        provider_uid=str(user.get("id")),
        email=(email or None),
        email_verified=verified,
        name=user.get("name") or user.get("login"),
        avatar_url=user.get("avatar_url"),
        login=user.get("login"),
    )


def _github_authorize_url(state: str) -> str:
    return f"{GITHUB_AUTHORIZE}?" + urlencode(
        {
            "client_id": settings.github_client_id,
            "redirect_uri": settings.oauth_redirect_uri("github"),
            "scope": "read:user user:email",
            "state": state,
            "allow_signup": "true",
        }
    )


# ---------------------------------------------------------------- Google

GOOGLE_AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
GOOGLE_USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"


async def _google_profile(code: str) -> ProviderProfile:
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(
            GOOGLE_TOKEN,
            data={
                "client_id": settings.google_client_id,
                "client_secret": settings.google_client_secret,
                "code": code,
                "grant_type": "authorization_code",
                "redirect_uri": settings.oauth_redirect_uri("google"),
            },
        )
        data = _as_json(resp, "Google 换取 token 失败")
        access_token = data.get("access_token")
        if not access_token:
            raise OAuthError(f"Google 未返回 access_token: {data.get('error_description') or data}")

        info_resp = await client.get(
            GOOGLE_USERINFO, headers={"Authorization": f"Bearer {access_token}"}
        )
        info = _as_json(info_resp, "Google 读取用户资料失败")

    return ProviderProfile(
        provider="google",
        provider_uid=str(info.get("sub")),
        email=(info.get("email") or None),
        email_verified=bool(info.get("email_verified")),
        name=info.get("name"),
        avatar_url=info.get("picture"),
        login=info.get("email"),
    )


def _google_authorize_url(state: str) -> str:
    return f"{GOOGLE_AUTHORIZE}?" + urlencode(
        {
            "client_id": settings.google_client_id,
            "redirect_uri": settings.oauth_redirect_uri("google"),
            "response_type": "code",
            "scope": "openid email profile",
            "state": state,
            "access_type": "online",
            "include_granted_scopes": "true",
            "prompt": "select_account",
        }
    )


# ---------------------------------------------------------------- 分发

def authorize_url(provider: str, state: str) -> str:
    if provider == "github":
        return _github_authorize_url(state)
    if provider == "google":
        return _google_authorize_url(state)
    raise OAuthError(f"不支持的登录渠道: {provider}")


async def fetch_profile(provider: str, code: str) -> ProviderProfile:
    if provider == "github":
        return await _github_profile(code)
    if provider == "google":
        return await _google_profile(code)
    raise OAuthError(f"不支持的登录渠道: {provider}")


def _as_json(resp: httpx.Response, what: str) -> dict:
    if resp.status_code >= 400:
        raise OAuthError(f"{what}（HTTP {resp.status_code}）：{resp.text[:300]}")
    try:
        payload = resp.json()
    except ValueError as exc:  # pragma: no cover - 极少见
        raise OAuthError(f"{what}：返回内容不是 JSON") from exc
    if not isinstance(payload, dict):
        raise OAuthError(f"{what}：返回结构异常")
    return payload
