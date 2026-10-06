"""终端会话测试（Agent Terminal Console §8 / §14 的 T1 部分）。

T1 覆盖：会话创建（工作区根与 Apply 同源）、用户命令执行与输出落库、exit code、
按 seq 续读历史、Stop 后拒绝新命令、Force Stop 真的终止长命令，以及 REST 闭环。

除 REST 用例外都是原生 async 测试（pytest asyncio_mode=auto）：整个用例跑在同一个
事件循环里，避免同一引擎被多个 asyncio.run 的循环反复使用。
"""

from __future__ import annotations

import asyncio
import os
import shlex
import sys

import pytest

from flux.container import Container
from flux.enums import TerminalEventKind, TerminalSessionKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError, NotFoundError, ValidationError


def _py(code: str) -> str:
    """一条跨平台可执行的 `python -c` 命令。

    终端执行的是 shell 命令，Windows 上是 cmd.exe：`sleep` / `echo` 这类 POSIX 命令不存在，
    且 cmd 不认单引号。用例统一改用 python 调用，命令语义在两个平台一致。
    """
    exe = f'"{sys.executable}"' if os.name == "nt" else shlex.quote(sys.executable)
    body = f'"{code}"' if os.name == "nt" else shlex.quote(code)
    return f"{exe} -c {body}"


async def test_create_session_uses_configured_workspace_root(
    apply_container: Container, workspace_root
) -> None:
    session = await apply_container.terminal.create_session()
    assert session.status == TerminalSessionStatus.ACTIVE.value
    assert session.kind == TerminalSessionKind.AGENT.value
    assert session.workspace_root == str(workspace_root.resolve())


async def test_create_session_without_workspace_root_is_rejected(container: Container) -> None:
    """未配置工作区根时拒绝开会话：与 Apply 同一口径，不猜默认目录。"""
    with pytest.raises(ValidationError):
        await container.terminal.create_session()


async def test_run_command_records_output_and_exit_code(apply_container: Container) -> None:
    session = await apply_container.terminal.create_session()

    event = await apply_container.terminal.run_command(session.id, _py("print('flux-terminal')"))

    assert event.kind == TerminalEventKind.COMMAND_FINISHED.value
    assert event.exit_code == 0
    # §5：命令来源必须可区分（T1 一律 user）
    assert event.source == TerminalSource.USER.value
    events = await apply_container.terminal.list_events(session.id)
    kinds = [item.kind for item in events]
    assert kinds[0] == TerminalEventKind.SESSION_CREATED.value
    assert TerminalEventKind.COMMAND_STARTED.value in kinds
    assert TerminalEventKind.OUTPUT.value in kinds
    assert any("flux-terminal" in (item.chunk or "") for item in events)


async def test_run_command_reports_failure_exit_code(apply_container: Container) -> None:
    session = await apply_container.terminal.create_session()

    event = await apply_container.terminal.run_command(session.id, _py("import sys; sys.exit(3)"))

    assert event.kind == TerminalEventKind.COMMAND_FAILED.value
    assert event.exit_code == 3


async def test_events_are_seq_ordered_and_resumable(apply_container: Container) -> None:
    """seq 单调不重复，且 after_seq 之后的续读结果与全量一致（§12 历史恢复的基础）。"""
    session = await apply_container.terminal.create_session()
    await apply_container.terminal.run_command(session.id, _py("print('one')"))

    all_events = await apply_container.terminal.list_events(session.id)
    seqs = [item.seq for item in all_events]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)

    tail = await apply_container.terminal.list_events(session.id, after_seq=seqs[0])
    assert [item.seq for item in tail] == seqs[1:]


async def test_stop_closes_session_and_rejects_further_commands(
    apply_container: Container,
) -> None:
    session = await apply_container.terminal.create_session()

    stopped = await apply_container.terminal.stop(session.id)

    assert stopped.status == TerminalSessionStatus.STOPPED.value
    kinds = [item.kind for item in await apply_container.terminal.list_events(session.id)]
    assert TerminalEventKind.STOP_REQUESTED.value in kinds
    assert TerminalEventKind.SESSION_CLOSED.value in kinds
    with pytest.raises(ConflictError):
        await apply_container.terminal.run_command(session.id, _py("print('x')"))


async def test_stop_blocks_a_command_already_queued(
    apply_container: Container,
) -> None:
    session = await apply_container.terminal.create_session()
    running = asyncio.create_task(
        apply_container.terminal.run_command(
            session.id,
            _py("import time; time.sleep(30)"),
        )
    )
    await asyncio.sleep(0.4)

    queued = asyncio.create_task(
        apply_container.terminal.run_command(
            session.id,
            _py("print('must-not-run-after-stop')"),
        )
    )
    await asyncio.sleep(0.1)

    stopped = await apply_container.terminal.stop(session.id, force=True)
    assert stopped.status == TerminalSessionStatus.STOPPED.value

    with pytest.raises(ConflictError):
        await queued
    finished = await asyncio.wait_for(running, timeout=10)
    assert finished.kind == TerminalEventKind.COMMAND_FAILED.value

    events = await apply_container.terminal.list_events(session.id)
    assert not any(
        event.command and "must-not-run-after-stop" in event.command
        for event in events
    )


async def test_force_stop_terminates_a_long_running_command(apply_container: Container) -> None:
    """Force Stop 必须真的杀掉进程树：命令立刻结束，而不是等它自然跑完（§3.4 / §10）。"""
    session = await apply_container.terminal.create_session()
    task = asyncio.create_task(
        apply_container.terminal.run_command(session.id, _py("import time; time.sleep(30)"))
    )
    await asyncio.sleep(1.0)  # 让命令真正跑起来

    stopped = await apply_container.terminal.stop(session.id, force=True)
    event = await asyncio.wait_for(task, timeout=10)

    assert stopped.status == TerminalSessionStatus.STOPPED.value
    assert event.kind == TerminalEventKind.COMMAND_FAILED.value
    assert event.exit_code not in (0, None)


def test_terminal_api_roundtrip(apply_client, workspace_root) -> None:
    created = apply_client.post("/api/v1/terminal/sessions", json={}).json()
    assert created["success"] is True
    session_id = created["data"]["id"]

    ran = apply_client.post(
        f"/api/v1/terminal/sessions/{session_id}/commands",
        json={"command": _py("print('api-check')")},
    ).json()
    assert ran["success"] is True
    assert ran["data"]["exit_code"] == 0

    events = apply_client.get(f"/api/v1/terminal/sessions/{session_id}/events").json()
    assert any("api-check" in (item["chunk"] or "") for item in events["data"])

    stopped = apply_client.post(
        f"/api/v1/terminal/sessions/{session_id}/stop", json={"force": False}
    ).json()
    assert stopped["data"]["status"] == TerminalSessionStatus.STOPPED.value
    assert stopped["metadata"]["force"] is False


# --- T3：SSE 实时流（§11 窗口同步 / §12 历史恢复）---


async def test_stream_replays_history_and_pushes_live_until_closed(
    apply_container: Container,
) -> None:
    """打开既运行中的会话：先补历史，再实时推 Stop 产生的事件，收流于 session.closed。"""
    session = await apply_container.terminal.create_session()
    await apply_container.terminal.run_command(session.id, _py("print('stream-me')"))

    collected: list[dict] = []

    async def consume() -> None:
        async for payload in apply_container.terminal.stream_events(session.id):
            if payload is None:  # 心跳哨兵
                continue
            collected.append(payload)
            if payload["kind"] == TerminalEventKind.SESSION_CLOSED.value:
                return

    task = asyncio.create_task(consume())
    await asyncio.sleep(0.3)  # 让补历史先跑完，再触发实时事件
    await apply_container.terminal.stop(session.id)
    await asyncio.wait_for(task, timeout=5)

    kinds = [item["kind"] for item in collected]
    assert TerminalEventKind.OUTPUT.value in kinds
    assert kinds[-1] == TerminalEventKind.SESSION_CLOSED.value
    assert any("stream-me" in (item["chunk"] or "") for item in collected)
    seqs = [item["seq"] for item in collected]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)  # 历史与实时合并后不重复


async def test_stream_resumes_from_after_seq_on_finished_session(
    apply_container: Container,
) -> None:
    """已结束会话 + after_seq 续读：只补断点之后的事件，补完立即收流（不挂住连接）。"""
    session = await apply_container.terminal.create_session()
    await apply_container.terminal.run_command(session.id, _py("print('done')"))
    head = await apply_container.terminal.list_events(session.id)
    await apply_container.terminal.stop(session.id)
    tail = await apply_container.terminal.list_events(session.id, after_seq=head[-1].seq)

    collected: list[dict] = []
    async for payload in apply_container.terminal.stream_events(session.id, after_seq=head[-1].seq):
        if payload is not None:
            collected.append(payload)

    assert [item["seq"] for item in collected] == [item.seq for item in tail]
    assert collected[-1]["kind"] == TerminalEventKind.SESSION_CLOSED.value


async def test_stream_unknown_session_raises_not_found(apply_container: Container) -> None:
    with pytest.raises(NotFoundError):
        async for _ in apply_container.terminal.stream_events(
            "00000000-0000-0000-0000-000000000000"
        ):
            pass


def test_terminal_stream_sse_endpoint(apply_client) -> None:
    """SSE 闭环：事件帧带 id/event/data，会话结束后服务端主动收流。"""
    created = apply_client.post("/api/v1/terminal/sessions", json={}).json()
    session_id = created["data"]["id"]
    apply_client.post(
        f"/api/v1/terminal/sessions/{session_id}/commands",
        json={"command": _py("print('sse-check')")},
    )
    apply_client.post(f"/api/v1/terminal/sessions/{session_id}/stop", json={})

    url = f"/api/v1/terminal/sessions/{session_id}/stream?after_seq=0"
    with apply_client.stream("GET", url) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        body = "".join(response.iter_text())

    assert "event: terminal.event" in body
    assert "id: 1" in body
    assert "sse-check" in body
    assert '"kind": "terminal.session.closed"' in body

    # 断线重连：Last-Event-ID 已到末尾时不再重放旧帧，也不挂住连接
    with apply_client.stream("GET", url, headers={"Last-Event-ID": "9999"}) as response:
        assert response.status_code == 200
        assert "data:" not in "".join(response.iter_text())


def test_terminal_stream_unknown_session_returns_404(apply_client) -> None:
    response = apply_client.get(
        "/api/v1/terminal/sessions/00000000-0000-0000-0000-000000000000/stream"
    )
    assert response.status_code == 404


@pytest.mark.skipif(os.name == "nt", reason="native PTY is not enabled on Windows")
def test_agent_terminal_list_excludes_human_pty_sessions(apply_client) -> None:
    human = apply_client.post("/api/v1/terminal/pty/sessions").json()
    assert human["success"] is True

    agent = apply_client.post("/api/v1/terminal/sessions", json={}).json()
    assert agent["success"] is True

    listed = apply_client.get("/api/v1/terminal/sessions").json()
    ids = {item["id"] for item in listed["data"]}
    assert agent["data"]["id"] in ids
    assert human["data"]["id"] not in ids
