"""Terminal API（Agent Terminal Console §6 / §8）。

会话是"观察 + 控制"的载体：Flux 自己执行命令，因此命令、输出、退出码都拿得到。
T1 只暴露用户命令（source=user）；T2 起 Agent 经 MCP 走同一 Session Manager。

历史按 seq 续读：`GET /terminal/sessions/{id}/events?after_seq=N` 即恢复历史（§12），
SSE 实时流在 T3 接入（同一 seq 语义，断线重连只带 after_seq）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.enums import TerminalSource
from flux.schemas.api import (
    TerminalCommandRequest,
    TerminalSessionCreateRequest,
    TerminalStopRequest,
)

router = APIRouter(prefix="/terminal", tags=["terminal"])


@router.post("/sessions")
async def create_session(
    payload: TerminalSessionCreateRequest | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """开一个终端会话（工作区根取服务端配置；未配置时 422，与 Apply 同一口径）。"""
    run_id = payload.run_id if payload is not None else None
    session = await container.terminal.create_session(run_id=run_id)
    return ok(session.to_dict())


@router.get("/sessions")
async def list_sessions(container: Container = Depends(get_container)) -> dict[str, object]:
    """最近的终端会话列表，最新的在前。"""
    sessions = await container.terminal.list_sessions()
    return ok([s.to_dict() for s in sessions], metadata={"count": len(sessions)})


@router.get("/sessions/{session_id}")
async def get_session(
    session_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """取单个会话（含状态与 next_seq），供终端窗口重开时对齐。"""
    return ok((await container.terminal.get_session(session_id)).to_dict())


@router.get("/sessions/{session_id}/events")
async def list_events(
    session_id: str,
    after_seq: int = 0,
    limit: int = 2000,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """按 seq 续读终端事件（历史恢复）：after_seq 之后、按 seq 升序。"""
    events = await container.terminal.list_events(session_id, after_seq=after_seq, limit=limit)
    return ok([e.to_dict() for e in events], metadata={"count": len(events)})


@router.post("/sessions/{session_id}/commands")
async def run_command(
    session_id: str,
    payload: TerminalCommandRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """执行一条用户命令并返回结束事件（含 exit code）；输出以 terminal.output 事件落库。"""
    event = await container.terminal.run_command(
        session_id, payload.command, source=TerminalSource.USER
    )
    return ok(event.to_dict())


@router.post("/sessions/{session_id}/stop")
async def stop_session(
    session_id: str,
    payload: TerminalStopRequest | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """停止会话：默认 SIGTERM → grace → SIGKILL，force=true 直接 SIGKILL。

    确认进程组消失之后才落 stopped，不谎报「已停止」（§10）。
    """
    force = payload.force if payload is not None else False
    session = await container.terminal.stop(session_id, force=force)
    return ok(session.to_dict(), metadata={"force": force})
