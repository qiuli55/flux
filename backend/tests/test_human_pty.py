"""Human Terminal PTY 隔离与 I/O 测试。

Windows 不启用 native PTY；本文件在 Windows CI 上只做模块导入/收集，不执行 Unix PTY 用例。
"""

from __future__ import annotations

import asyncio
import os

import pytest

from flux.container import Container
from flux.enums import TerminalSessionKind, TerminalSessionStatus
from flux.errors import ConflictError


pytestmark = pytest.mark.skipif(os.name == "nt", reason="native PTY is not enabled on Windows")


async def test_human_terminal_session_is_persistently_isolated(
    apply_container: Container,
) -> None:
    human = await apply_container.human_pty.create_session()
    agent = await apply_container.terminal.create_session()

    assert human.kind == TerminalSessionKind.HUMAN.value
    assert agent.kind == TerminalSessionKind.AGENT.value

    with pytest.raises(ConflictError):
        await apply_container.human_pty.get_session(agent.id)

    await apply_container.human_pty.stop(human.id, force=True)
    assert (
        await apply_container.human_pty.get_session(human.id)
    ).status == TerminalSessionStatus.STOPPED.value


async def test_human_terminal_can_receive_input_and_stream_output(
    apply_container: Container,
) -> None:
    session = await apply_container.human_pty.create_session()
    chunks: list[str] = []
    marker = "flux-human-pty-check"

    async def consume_until_marker() -> None:
        async for chunk in apply_container.human_pty.stream_output(session.id):
            chunks.append(chunk)
            if marker in "".join(chunks):
                return

    consumer = asyncio.create_task(consume_until_marker())
    try:
        await asyncio.sleep(0.1)
        await apply_container.human_pty.send_input(session.id, f"printf '%s\\n' '{marker}'\\n")
        await asyncio.wait_for(consumer, timeout=5)
        assert marker in "".join(chunks)
    finally:
        if not consumer.done():
            consumer.cancel()
            with pytest.raises(asyncio.CancelledError):
                await consumer
        await apply_container.human_pty.stop(session.id, force=True)


def test_human_terminal_api_only_lists_human_sessions(apply_client) -> None:
    created = apply_client.post("/api/v1/terminal/pty/sessions").json()
    assert created["success"] is True
    session_id = created["data"]["id"]
    assert created["data"]["kind"] == TerminalSessionKind.HUMAN.value

    agent = apply_client.post("/api/v1/terminal/sessions", json={}).json()
    assert agent["success"] is True
    assert agent["data"]["kind"] == TerminalSessionKind.AGENT.value

    human_list = apply_client.get("/api/v1/terminal/pty/sessions").json()
    ids = {item["id"] for item in human_list["data"]}
    assert session_id in ids
    assert agent["data"]["id"] not in ids

    agent_get_human = apply_client.get(
        f"/api/v1/terminal/sessions/{session_id}"
    )
    assert agent_get_human.status_code == 409

    human_get_agent = apply_client.get(
        f"/api/v1/terminal/pty/sessions/{agent['data']['id']}"
    )
    assert human_get_agent.status_code == 409

    stopped = apply_client.post(
        f"/api/v1/terminal/pty/sessions/{session_id}/stop",
        json={"force": False},
    )
    assert stopped.status_code == 200
    assert stopped.json()["data"]["status"] == TerminalSessionStatus.STOPPED.value

    stopped_detail = apply_client.get(
        f"/api/v1/terminal/pty/sessions/{session_id}"
    )
    assert stopped_detail.status_code == 200
    assert stopped_detail.json()["data"]["status"] == TerminalSessionStatus.STOPPED.value

    live_humans = apply_client.get("/api/v1/terminal/pty/sessions").json()["data"]
    assert session_id not in {item["id"] for item in live_humans}


async def test_human_terminal_list_excludes_stopped_sessions(
    apply_container: Container,
) -> None:
    session = await apply_container.human_pty.create_session()
    assert session.id in {item.id for item in await apply_container.human_pty.list_sessions()}

    await apply_container.human_pty.stop(session.id, force=True)

    assert session.id not in {item.id for item in await apply_container.human_pty.list_sessions()}


def test_human_terminal_websocket_roundtrip(apply_client) -> None:
    created = apply_client.post("/api/v1/terminal/pty/sessions").json()
    assert created["success"] is True
    session_id = created["data"]["id"]

    marker = "flux-human-ws-check"
    with apply_client.websocket_connect(
        f"/api/v1/terminal/pty/sessions/{session_id}/ws"
    ) as websocket:
        websocket.send_json({"type": "input", "data": f"printf '%s\\n' '{marker}'\\n"})
        chunks: list[str] = []
        for _ in range(20):
            message = websocket.receive_json()
            if message.get("type") != "output":
                continue
            chunks.append(str(message.get("data", "")))
            if marker in "".join(chunks):
                break
        assert marker in "".join(chunks)
        websocket.send_json({"type": "stop", "force": True})
