"""Terminal APIs.

Agent Terminal and Human Terminal are intentionally separate. Agent Terminal remains
command/SSE based; Human Terminal is an interactive OS PTY bridged over WebSocket.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from contextlib import suppress
from typing import Any

from fastapi import APIRouter, Depends, Header, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.terminal.pty_service import HumanPtyService
from flux.enums import TerminalSource
from flux.schemas.api import (
    TerminalCommandRequest,
    TerminalSessionCreateRequest,
    TerminalStopRequest,
)

router = APIRouter(prefix="/terminal", tags=["terminal"])
SSE_EVENT_NAME = "terminal.event"


def _format_sse(payload: dict[str, Any]) -> str:
    data = json.dumps(payload, ensure_ascii=False)
    return f"id: {payload['seq']}\nevent: {SSE_EVENT_NAME}\ndata: {data}\n\n"


def _human_pty(container: Container) -> HumanPtyService:
    service = getattr(container.terminal, "_human_pty", None)
    if service is None:
        service = HumanPtyService(
            container.terminal._repo,  # noqa: SLF001 - shared terminal repository
            container.bus,
            workspace_root=container.settings.workspace_root,
        )
        container.terminal._human_pty = service  # noqa: SLF001
    return service


@router.post("/sessions")
async def create_session(
    payload: TerminalSessionCreateRequest | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    run_id = payload.run_id if payload is not None else None
    session = await container.terminal.create_session(run_id=run_id)
    return ok(session.to_dict())


@router.get("/sessions")
async def list_sessions(container: Container = Depends(get_container)) -> dict[str, object]:
    sessions = await container.terminal.list_sessions()
    return ok([s.to_dict() for s in sessions], metadata={"count": len(sessions)})


@router.get("/sessions/{session_id}")
async def get_session(
    session_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    return ok((await container.terminal.get_session(session_id)).to_dict())


@router.get("/sessions/{session_id}/events")
async def list_events(
    session_id: str,
    after_seq: int = 0,
    limit: int = 2000,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    events = await container.terminal.list_events(session_id, after_seq=after_seq, limit=limit)
    return ok([e.to_dict() for e in events], metadata={"count": len(events)})


@router.get("/sessions/{session_id}/stream")
async def stream_events(
    session_id: str,
    after_seq: int = 0,
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
    container: Container = Depends(get_container),
) -> StreamingResponse:
    await container.terminal.get_session(session_id)
    resume_from = after_seq
    if last_event_id:
        with suppress(ValueError):
            resume_from = max(resume_from, int(last_event_id))

    async def generate() -> AsyncIterator[str]:
        async for payload in container.terminal.stream_events(session_id, after_seq=resume_from):
            yield ": keep-alive\n\n" if payload is None else _format_sse(payload)

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/sessions/{session_id}/commands")
async def run_command(
    session_id: str,
    payload: TerminalCommandRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
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
    force = payload.force if payload is not None else False
    session = await container.terminal.stop(session_id, force=force)
    return ok(session.to_dict(), metadata={"force": force})


# Human Terminal — real PTY

@router.post("/pty/sessions")
async def create_human_session(
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """Create an interactive user shell backed by a real Unix PTY."""
    session = await _human_pty(container).create_session()
    return ok(session.to_dict())


@router.websocket("/pty/sessions/{session_id}/ws")
async def human_terminal_ws(websocket: WebSocket, session_id: str) -> None:
    """Bridge terminal input/output and resize messages to an OS PTY."""
    container = websocket.app.state.container
    service = _human_pty(container)
    await websocket.accept()
    output_task: asyncio.Task[None] | None = None
    try:
        await service.get_session(session_id)
        output_task = asyncio.create_task(_forward_pty_output(websocket, service, session_id))
        while True:
            raw = await websocket.receive_text()
            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "message": "invalid JSON"})
                continue
            kind = message.get("type")
            if kind == "input":
                await service.send_input(session_id, str(message.get("data", "")))
            elif kind == "resize":
                await service.resize(
                    session_id,
                    int(message.get("cols", 120)),
                    int(message.get("rows", 32)),
                )
            elif kind == "stop":
                await service.stop(session_id, force=bool(message.get("force", False)))
                break
            else:
                await websocket.send_json({"type": "error", "message": f"unsupported message: {kind}"})
    except WebSocketDisconnect:
        return
    except Exception as exc:
        with suppress(Exception):
            await websocket.send_json({"type": "error", "message": str(exc)})
            await websocket.close(code=1011)
    finally:
        if output_task is not None:
            output_task.cancel()
            with suppress(asyncio.CancelledError):
                await output_task


async def _forward_pty_output(
    websocket: WebSocket, service: HumanPtyService, session_id: str
) -> None:
    try:
        async for chunk in service.stream_output(session_id):
            await websocket.send_json({"type": "output", "data": chunk})
    except WebSocketDisconnect:
        return
    finally:
        with suppress(Exception):
            await websocket.send_json({"type": "closed"})
