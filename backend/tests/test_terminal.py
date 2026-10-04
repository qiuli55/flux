"""终端会话测试（Agent Terminal Console §8 / §14 的 T1 部分）。

T1 覆盖：会话创建（工作区根与 Apply 同源）、用户命令执行与输出落库、exit code、
按 seq 续读历史、Stop 后拒绝新命令、Force Stop 真的终止长命令，以及 REST 闭环。

除 REST 用例外都是原生 async 测试（pytest asyncio_mode=auto）：整个用例跑在同一个
事件循环里，避免同一引擎被多个 asyncio.run 的循环反复使用。
"""

from __future__ import annotations

import asyncio

import pytest

from flux.container import Container
from flux.enums import TerminalEventKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError, ValidationError


async def test_create_session_uses_configured_workspace_root(
    apply_container: Container, workspace_root
) -> None:
    session = await apply_container.terminal.create_session()
    assert session.status == TerminalSessionStatus.ACTIVE.value
    assert session.workspace_root == str(workspace_root.resolve())


async def test_create_session_without_workspace_root_is_rejected(container: Container) -> None:
    """未配置工作区根时拒绝开会话：与 Apply 同一口径，不猜默认目录。"""
    with pytest.raises(ValidationError):
        await container.terminal.create_session()


async def test_run_command_records_output_and_exit_code(apply_container: Container) -> None:
    session = await apply_container.terminal.create_session()

    event = await apply_container.terminal.run_command(session.id, "echo flux-terminal")

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

    event = await apply_container.terminal.run_command(session.id, "exit 3")

    assert event.kind == TerminalEventKind.COMMAND_FAILED.value
    assert event.exit_code == 3


async def test_events_are_seq_ordered_and_resumable(apply_container: Container) -> None:
    """seq 单调不重复，且 after_seq 之后的续读结果与全量一致（§12 历史恢复的基础）。"""
    session = await apply_container.terminal.create_session()
    await apply_container.terminal.run_command(session.id, "echo one")

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
        await apply_container.terminal.run_command(session.id, "echo x")


async def test_force_stop_terminates_a_long_running_command(apply_container: Container) -> None:
    """Force Stop 必须真的杀掉进程树：命令立刻结束，而不是等它自然跑完（§3.4 / §10）。"""
    session = await apply_container.terminal.create_session()
    task = asyncio.create_task(apply_container.terminal.run_command(session.id, "sleep 30"))
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
        f"/api/v1/terminal/sessions/{session_id}/commands", json={"command": "echo api-check"}
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
