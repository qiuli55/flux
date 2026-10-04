"""Terminal API（Agent Terminal Console §6 / §8）。

会话是"观察 + 控制"的载体：Flux 自己执行命令，因此命令、输出、退出码都拿得到。
T1 只暴露用户命令（source=user）；T2 起 Agent 经 MCP 走同一 Session Manager。

历史按 seq 续读：`GET /terminal/sessions/{id}/events?after_seq=N` 即恢复历史（§12），
`GET /terminal/sessions/{id}/stream` 是同一 seq 语义的 SSE 实时流（T3）：断线重连带
`Last-Event-ID` 即可从断点续读，不重复也不丢。
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from contextlib import suppress
from typing import Any

from fastapi import APIRouter, Depends, Header
from fastapi.responses import StreamingResponse

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

#: SSE 事件名：终端窗口按它 addEventListener，与总线事件名一致
SSE_EVENT_NAME = "terminal.event"


def _format_sse(payload: dict[str, Any]) -> str:
    """一条 SSE 帧：id 用 seq（EventSource 重连时以 Last-Event-ID 回传，即断点续读）。"""
    data = json.dumps(payload, ensure_ascii=False)
    return f"id: {payload['seq']}\nevent: {SSE_EVENT_NAME}\ndata: {data}\n\n"


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


@router.get("/sessions/{session_id}/stream")
async def stream_events(
    session_id: str,
    after_seq: int = 0,
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
    container: Container = Depends(get_container),
) -> StreamingResponse:
    """终端事件实时流（SSE，§11 / §12）：先补历史再推增量。

    断线重连时浏览器会带 `Last-Event-ID`（即上一帧的 seq），与 `after_seq` 取较大者续读。
    会话结束时服务端会推完 `terminal.session.closed` 后收流；客户端断开只结束观察，
    不会停 Agent（§11）。
    """
    # 先校验存在：NotFoundError 要在开始推流之前抛出，否则响应已 200 无法再改状态码
    await container.terminal.get_session(session_id)
    resume_from = after_seq
    if last_event_id:
        with suppress(ValueError):
            resume_from = max(resume_from, int(last_event_id))

    async def generate() -> AsyncIterator[str]:
        async for payload in container.terminal.stream_events(session_id, after_seq=resume_from):
            # None 是心跳哨兵（服务层产出），转成 SSE 注释行，不触发前端事件
            yield ": keep-alive\n\n" if payload is None else _format_sse(payload)

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # 反向代理（nginx）不缓冲，否则实时流会被攒成一坨
            "X-Accel-Buffering": "no",
        },
    )


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
