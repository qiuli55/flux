"""DSH 客户端单元测试（集成方案 §18 Phase 1）。

全部用 fake harness，不启动真实 runtime 子进程、不调用 DeepSeek API。
"""

from __future__ import annotations

import asyncio
import time
from pathlib import Path

import pytest
from deepseek_harness import Notification, RunResult, SdkProtocolError

from flux.config import Settings
from flux.core.agent_runtime.dsh_client import (
    TERMINAL_STATUSES,
    DshRun,
    FluxDshClient,
)
from flux.core.agent_runtime.dsh_events import to_flux_event
from flux.core.event.bus import EventBus, Events
from flux.enums import DshRunStatus
from flux.errors import ConfigurationError, NotFoundError
from tests.fakes import (
    DshBehavior,
    FakeDshHarness,
    FakeDshHarnessFactory,
    default_dsh_behavior,
)


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    """启用态的测试配置：DSH_HOME / 工作区落在 tmp_path 下。"""
    base = Settings(
        env="test",
        log_level="WARNING",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'dsh.db'}",
        openai_api_key=None,
        anthropic_api_key=None,
        deepseek_api_key=None,
        default_provider="local",
        dsh_enabled=True,
        dsh_home=str(tmp_path / "dsh-home"),
        dsh_workspace=str(tmp_path / "dsh-ws"),
    )
    return base.model_copy(update=overrides) if overrides else base


async def _wait_terminal(client: FluxDshClient, run_id: str, timeout: float = 3.0) -> DshRun:
    """轮询到 Run 进入终态，避免用固定 sleep 造成 flaky。"""
    deadline = time.monotonic() + timeout
    run = client.get_run(run_id)
    while run.status not in TERMINAL_STATUSES and time.monotonic() < deadline:
        await asyncio.sleep(0.01)
    return run


def _failing_behavior(
    harness: FakeDshHarness,
    instruction: str,
    session_id: str | None,
    on_notification: object,
) -> RunResult:
    raise SdkProtocolError("协议错误")


def _blocking_behavior(
    harness: FakeDshHarness,
    instruction: str,
    session_id: str | None,
    on_notification: object,
) -> RunResult:
    """阻塞到收到 session/cancel 再返回，用来验证中断路径。"""
    harness.cancel_event.wait(timeout=5)
    sid = session_id or "session"
    return RunResult(
        session_id=sid,
        final_response="",
        finish_reason="cancelled",
        events=[],
        notifications=[],
    )


async def test_start_run_completes_and_maps_events(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    bus = EventBus()
    factory = FakeDshHarnessFactory()
    client = FluxDshClient(settings, bus=bus, harness_factory=factory)

    run = await client.start_run("写一个加法函数", session_id="s-1")
    assert run.status is DshRunStatus.RUNNING
    assert run.session_id == "s-1"
    assert run.started_at is not None

    finished = await _wait_terminal(client, run.run_id)
    assert finished.status is DshRunStatus.COMPLETED
    assert finished.final_response == "echo: 写一个加法函数"
    assert finished.finish_reason == "completed"
    assert finished.finished_at is not None
    assert finished.duration_seconds is not None and finished.duration_seconds >= 0

    # 通知被压扁成 Flux 事件体，并带上 run_id 关联
    assert finished.events[0]["event_type"] == "assistant/message"
    assert finished.events[0]["text"] == "echo: 写一个加法函数"
    assert finished.events[0]["run_id"] == run.run_id
    assert finished.events[1]["event_type"] == "turn/end"
    assert finished.events[1]["finish_reason"] == "completed"

    # run 结束后 harness 被关闭并移出活跃表
    assert factory.created[0].closed is True
    assert client.interrupt(run.run_id).status is DshRunStatus.COMPLETED

    names = [name for name, _ in bus.history]
    assert Events.DSH_RUN_STARTED in names
    assert Events.DSH_RUN_COMPLETED in names
    client.close()


async def test_start_run_defaults_session_id_from_run_id(tmp_path: Path) -> None:
    client = FluxDshClient(_settings(tmp_path), harness_factory=FakeDshHarnessFactory())
    run = await client.start_run("hi")
    assert run.session_id == f"flux-{run.run_id}"
    await _wait_terminal(client, run.run_id)
    client.close()


async def test_run_failure_maps_to_failed(tmp_path: Path) -> None:
    bus = EventBus()
    client = FluxDshClient(
        _settings(tmp_path), bus=bus, harness_factory=FakeDshHarnessFactory(_failing_behavior)
    )
    run = await client.start_run("boom", session_id="s-f")

    finished = await _wait_terminal(client, run.run_id)
    assert finished.status is DshRunStatus.FAILED
    assert finished.error == "协议错误"
    assert finished.final_response == ""
    assert finished.finished_at is not None
    assert finished.duration_seconds is not None

    names = [name for name, _ in bus.history]
    assert Events.DSH_RUN_FAILED in names
    client.close()


async def test_interrupt_marks_run_cancelled(tmp_path: Path) -> None:
    bus = EventBus()
    factory = FakeDshHarnessFactory(_blocking_behavior)
    client = FluxDshClient(_settings(tmp_path), bus=bus, harness_factory=factory)
    run = await client.start_run("长任务", session_id="s-i")

    # 轮询触发，直到 harness 真正注册并收到 session/cancel（规避注册竞态）
    for _ in range(300):
        interrupted = client.interrupt(run.run_id)
        if factory.created and factory.created[0].notifications:
            break
        await asyncio.sleep(0.01)

    assert interrupted.status is DshRunStatus.RUNNING
    assert interrupted.cancel_requested is True
    assert factory.created[0].notifications == [("session/cancel", {"sessionId": "s-i"})]

    finished = await _wait_terminal(client, run.run_id)
    assert finished.status is DshRunStatus.CANCELLED
    assert finished.finished_at is not None
    names = [name for name, _ in bus.history]
    assert Events.DSH_RUN_CANCELLED in names
    client.close()


async def test_interrupt_terminal_run_is_noop(tmp_path: Path) -> None:
    factory = FakeDshHarnessFactory()
    client = FluxDshClient(_settings(tmp_path), harness_factory=factory)
    run = await client.start_run("done", session_id="s-2")
    finished = await _wait_terminal(client, run.run_id)

    again = client.interrupt(run.run_id)
    assert again is finished
    assert again.status is DshRunStatus.COMPLETED
    assert again.cancel_requested is False
    assert factory.created[0].notifications == []
    client.close()


async def test_start_run_disabled_raises_configuration_error(tmp_path: Path) -> None:
    client = FluxDshClient(_settings(tmp_path, dsh_enabled=False))
    with pytest.raises(ConfigurationError):
        await client.start_run("x")


def test_ensure_ready_creates_directories(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    FluxDshClient(settings).ensure_ready()
    assert Path(settings.dsh_home).is_dir()
    assert Path(settings.dsh_workspace).is_dir()


def test_get_run_missing_raises_not_found(tmp_path: Path) -> None:
    client = FluxDshClient(_settings(tmp_path))
    with pytest.raises(NotFoundError):
        client.get_run("nope")


def test_to_flux_event_keeps_unknown_notification() -> None:
    event, body = to_flux_event(
        Notification(method="session/status", payload={"sessionId": "s", "status": "idle"})
    )
    assert event == Events.DSH_EVENT
    assert body["method"] == "session/status"
    assert body["session_id"] == "s"
    assert body["event_type"] is None


def test_to_flux_event_reads_data_content_without_message_wrapper() -> None:
    notification = Notification(
        method="session.event",
        payload={
            "sessionId": "s",
            "event": {
                "type": "assistant/message",
                "data": {"content": [{"type": "text", "text": "hi"}, {"type": "tool_use"}]},
            },
        },
    )
    _, body = to_flux_event(notification)
    assert body["text"] == "hi"


def test_default_behavior_is_a_valid_dsh_behavior() -> None:
    """确认默认替身行为满足 DshBehavior 契约（类型层面外的运行时自检）。"""
    behavior: DshBehavior = default_dsh_behavior
    assert callable(behavior)
