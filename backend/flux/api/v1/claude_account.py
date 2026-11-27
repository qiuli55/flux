"""Claude Code 订阅（claude.ai）账号 API。

状态 / 开始登录 / 轮询 / 提交授权码 / 退出。响应里永远不出现订阅令牌：
status 只给是否已登录与登录态目录，login 只给授权链接和状态。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.errors import NotFoundError
from flux.schemas.api import ClaudeLoginCodeRequest

router = APIRouter(prefix="/claude-account", tags=["claude-account"])


@router.get("")
async def account_status(container: Container = Depends(get_container)) -> dict[str, object]:
    """当前 Claude Code 订阅的登录态（不含任何令牌）。"""
    return ok(container.claude_account.status())


@router.post("/login")
async def start_login(container: Container = Depends(get_container)) -> dict[str, object]:
    """发起官方 setup-token 登录；返回可在手机打开的授权链接。"""
    session = await container.claude_account.start_login()
    return ok(session)


@router.get("/login/{login_id}")
async def login_state(
    login_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """轮询登录结果：starting / pending / success / failed。"""
    state = container.claude_account.login_state(login_id)
    if state is None:
        raise NotFoundError(f"登录会话不存在或已过期：{login_id}", details={"login_id": login_id})
    return ok(state)


@router.post("/login/{login_id}/code")
async def submit_code(
    login_id: str,
    payload: ClaudeLoginCodeRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """把浏览器授权页给出的 `code#state` 粘回登录进程。"""
    return ok(await container.claude_account.submit_code(login_id, payload.code))


@router.post("/logout")
async def logout(container: Container = Depends(get_container)) -> dict[str, object]:
    """退出 Claude Code 订阅：删除 Flux 隔离目录里的订阅令牌。"""
    return ok(await container.claude_account.logout())


__all__ = ["router"]
