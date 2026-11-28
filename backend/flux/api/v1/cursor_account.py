"""Cursor 账号（官方 Cursor CLI 浏览器授权）API。

状态 / 开始登录 / 轮询 /（官方 CLI 要求时）提交授权码 / 退出。
响应里不出现 access token、refresh token、cookie 或 API key。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.errors import NotFoundError
from flux.schemas.api import ClaudeLoginCodeRequest

router = APIRouter(prefix="/cursor-account", tags=["cursor-account"])


@router.get("")
async def account_status(container: Container = Depends(get_container)) -> dict[str, object]:
    """当前 Cursor 官方 CLI 的登录态（不含任何令牌）。"""
    return ok(container.cursor_account.status())


@router.post("/login")
async def start_login(container: Container = Depends(get_container)) -> dict[str, object]:
    """在电脑上拉起 `agent login`；返回官方授权链接供客户端打开。"""
    session = await container.cursor_account.start_login()
    return ok(session)


@router.get("/login/{login_id}")
async def login_state(
    login_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """轮询登录结果：starting / pending / success / failed。"""
    state = container.cursor_account.login_state(login_id)
    if state is None:
        raise NotFoundError(f"登录会话不存在或已过期：{login_id}", details={"login_id": login_id})
    return ok(state)


@router.post("/login/{login_id}/code")
async def submit_code(
    login_id: str,
    payload: ClaudeLoginCodeRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """仅当官方 CLI 自己要求回填授权码时使用（不是 API token 入口）。"""
    return ok(await container.cursor_account.submit_code(login_id, payload.code))


@router.post("/logout")
async def logout(container: Container = Depends(get_container)) -> dict[str, object]:
    """调官方 `agent logout` 并清理 Flux 的非机密登录标记。"""
    return ok(await container.cursor_account.logout())


__all__ = ["router"]
