"""账号云接入 API（Flux 本体 ↔ B 机 `flux.qiuli55.top` 账号系统）。

三个端点都**不**受 `require_rest_auth` 保护（见 flux.main 的挂载方式）：
浏览器在未登录时也要能读到登录入口，并用一次性 code 换取本机会话——
这正是"先有鸡还是先有蛋"的那一步。真正的写操作仍然落在 `/api/v1` 的其它面上，
由 `require_rest_auth` 用同一份云令牌把关。
"""

from __future__ import annotations

from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from flux.api.deps import get_settings_dep
from flux.api.response import ok
from flux.cloud.session import CloudClient, CloudError, clear_session, load_session, save_session
from flux.config import Settings
from flux.errors import AuthenticationError

router = APIRouter(prefix="/cloud", tags=["cloud"])


class CloudExchangeIn(BaseModel):
    code: str = Field(
        min_length=8,
        max_length=256,
        description="账号云登录页回跳时携带的一次性登录码",
    )


def _login_url(settings: Settings) -> str:
    base = (settings.cloud_base_url or "").rstrip("/")
    next_url = (settings.cloud_return_url or "").strip()
    if not next_url:
        return f"{base}/login"
    return f"{base}/login?next={quote(next_url, safe='')}"


def _user_payload(settings: Settings) -> dict[str, object]:
    session = load_session(settings.cloud_session_path)
    if session is None:
        return {"authenticated": False, "user": None}
    return {
        "authenticated": not session.expired,
        "user": {"id": session.user_id, "username": session.username},
    }


@router.get("/session", summary="查询本机账号云登录状态与登录入口")
async def cloud_session(settings: Settings = Depends(get_settings_dep)) -> dict[str, object]:
    payload = _user_payload(settings)
    payload["configured"] = bool((settings.cloud_secret_key or "").strip())
    payload["login_url"] = _login_url(settings)
    return ok(payload)


@router.post("/exchange", summary="用一次性登录码换取本机账号云会话")
async def cloud_exchange(
    body: CloudExchangeIn,
    settings: Settings = Depends(get_settings_dep),
) -> dict[str, object]:
    client = CloudClient(settings.cloud_base_url)
    try:
        session = await client.exchange_code(body.code)
    except (CloudError, httpx.HTTPError) as exc:
        raise AuthenticationError(f"登录码兑换失败：{exc}") from exc
    save_session(settings.cloud_session_path, session)
    return ok(
        {
            "authenticated": True,
            "user": {"id": session.user_id, "username": session.username},
        }
    )


@router.post("/logout", summary="清除本机保存的账号云会话")
async def cloud_logout(settings: Settings = Depends(get_settings_dep)) -> dict[str, object]:
    clear_session(settings.cloud_session_path)
    return ok({"authenticated": False})
