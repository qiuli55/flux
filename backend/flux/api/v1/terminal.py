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
from urllib.parse import urlparse

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


def _same_origin_websocket(websocket: WebSocket) -> bool:
    """Reject browser WebSockets initiated by an unrelated Origin.

    The browser WebSocket API does not let application code attach arbitrary
    Authorization headers, so Flux currently relies on the same-origin boundary here.
    Deployment-level authentication remains responsible for protecting the web app.
    """
    origin = websocket.headers.get("origin")
    if not origin:
        # Non-browser clients normally omit Origin; allow them so desktop/CLI clients
        # can use the PTY protocol without pretending to be a browser.
        return True
    parsed = urlparse(origin)
    host = websocket.headers.get("host", "")
    return parsed.netloc == host


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
    session = await container.human_pty.create_session()
    return ok(session.to_dict())


@router.websocket("/pty/sessions/{session_id}/ws")
async def human_terminal_ws(websocket: WebSocket, session_id: str) -> None:
    """Bridge terminal input/output and resize messages to an OS PTY."""
    if not _same_origin_websocket(websocket):
        await websocket.close(code=1008, reason="cross-origin websocket rejected")
        return

    container: Container = websocket.app.state.container
    service: HumanPtyService = container.human_pty
    session = await service.get_session(session_id)
    if session.run_id is not None:
        await websocket.close(code=1008, reason="agent terminal sessions are not human PTYs")
        return

    await websocket.accept()
    output_task: asyncio.Task[None] | None = None
    receive_task: asyncio.Task[str] | None = None
    try:
        output_task = asyncio.create_task(
            _forward_pty_output(websocket, service, session_id),
            name=f"flux-pty-ws-output-{session_id}",
        )
        receive_task = asyncio.create_task(
            websocket.receive_text(),
            name=f"flux-pty-ws-input-{session_id}",
        )

        while True:
            done, _ = await asyncio.wait(
                {output_task, receive_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            if output_task in done:
                with suppress(Exception):
                    await websocket.close(code=1000, reason="terminal closed")
                break

            try:
                raw = receive_task.result()
            except WebSocketDisconnect:
                break

            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "message": "invalid JSON"})
                receive_task = asyncio.create_task(websocket.receive_text())
                continue

            kind = message.get("type")
            try:
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
                    await websocket.send_json(
                        {"type": "error", "message": f"unsupported message: {kind}"}
                    )
            except Exception as exc:
                await websocket.send_json({"type": "error", "message": str(exc)})

            receive_task = asyncio.create_task(websocket.receive_text())
    finally:
        for task in (receive_task, output_task):
            if task is not None and not task.done():
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task


async def _forward_pty_output(
    websocket: WebSocket, service: HumanPtyService, session_id: str
) -> None:
    try:
        async for chunk in service.stream_output(session_id):
            await websocket.send_json({"type": "output", "data": chunk})
    except (WebSocketDisconnect, asyncio.CancelledError):
        return
    finally:
        with suppress(Exception):
            await websocket.send_json({"type": "closed"})
