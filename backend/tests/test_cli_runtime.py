"""P2-1 / P2-2：Flux 主动拉起外部 CLI Agent 的确定性测试。

用**假 CLI 脚本**（打印 JSON 行后 exit 0 / 非 0 / 忽略 SIGTERM）覆盖
completed / failed / cancel 三条路径，真实经过 `CliRuntime` → `CliAdapter` →
`platforms.popen_kwargs` 进程组隔离 → 现有 `RunSupervisor` 的完整链路，
不启动真实 Codex / OpenCode，也不调用真实模型。
"""

from __future__ import annotations

import asyncio
import os
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.core.agent_runtime.adapters import OpenCodeAdapter
from flux.core.agent_runtime.adapters.base import CliAgentProbe
from flux.core.agent_runtime.manager import AgentSpec
from flux.core.agent_runtime.runtimes.base import (
    DEFAULT_RUNTIME,
    RUNTIME_CODEX,
    RuntimeProcess,
)
from flux.core.agent_runtime.runtimes.dsh_runtime import DshRuntime
from flux.enums import AgentInstallStatus, AgentRole
from flux.errors import ValidationError
from flux.main import create_app

PREFIX = "/api/v1"

_COMPLETED_SCRIPT = """#!/usr/bin/env python3
import json, sys
print(json.dumps({"type": "message", "text": "working"}), flush=True)
call = {"type": "tool_call", "name": "workspace.read", "input": {"path": "a.py"}}
print(json.dumps(call), flush=True)
result = {"type": "tool_result", "name": "workspace.read", "output": "ok"}
print(json.dumps(result), flush=True)
print(json.dumps({"type": "final", "text": "done"}), flush=True)
sys.exit(0)
"""

_FAILED_SCRIPT = """#!/usr/bin/env python3
import json, sys
print(json.dumps({"type": "message", "text": "boom"}), flush=True)
sys.exit(2)
"""

_CANCEL_SCRIPT = """#!/usr/bin/env python3
import json, signal, sys, time
signal.signal(signal.SIGTERM, signal.SIG_IGN)
print(json.dumps({"type": "message", "text": "holding"}), flush=True)
while True:
    time.sleep(0.1)
"""


def _write_script(path: Path, body: str) -> str:
    path.write_text(body, encoding="utf-8")
    path.chmod(0o755)
    return str(path)


def _cli_settings(settings: Settings, tmp_path: Path) -> Settings:
    root = tmp_path / "project"
    root.mkdir(exist_ok=True)
    return settings.model_copy(
        update={
            "dsh_enabled": True,
            "dsh_home": str(tmp_path / "dsh-home"),
            "dsh_workspace": str(tmp_path / "dsh-ws"),
            "workspace_root": str(root),
            "dsh_mcp_enabled": True,
            "dsh_mcp_url": "http://127.0.0.1:8000/mcp",
        }
    )


@contextmanager
def _cli_app(settings: Settings, tmp_path: Path, binary: str) -> Iterator[TestClient]:
    app = create_app(_cli_settings(settings, tmp_path))
    previous = os.environ.get("FLUX_CODEX_CLI_BINARY")
    os.environ["FLUX_CODEX_CLI_BINARY"] = binary
    try:
        with TestClient(app) as client:
            yield client
    finally:
        if previous is None:
            os.environ.pop("FLUX_CODEX_CLI_BINARY", None)
        else:
            os.environ["FLUX_CODEX_CLI_BINARY"] = previous


def _mark_ready(container: object, name: str) -> None:
    """把 installation 直接置为 READY（不真实探测本机 CLI，保持用例确定性）。"""

    async def _upsert() -> None:
        await container.installations._repository.upsert(  # noqa: SLF001 - 测试直接落库
            CliAgentProbe(name=name, adapter=name, status=AgentInstallStatus.READY),
            status=AgentInstallStatus.READY,
        )

    asyncio.run(_upsert())


def _create_cli_agent(client: TestClient, runtime: str) -> str:
    response = client.post(
        f"{PREFIX}/agents",
        json={
            "name": f"cli-{runtime}-{uuid.uuid4().hex[:6]}",
            "role": "developer",
            "runtime": runtime,
            "permissions": ["file.read", "file.write"],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["data"]["id"]


def _start_task(client: TestClient, agent_id: str) -> dict:
    created = client.post(
        f"{PREFIX}/tasks", json={"description": "改一下 store.py", "agent_id": agent_id}
    )
    assert created.status_code == 200, created.text
    task_id = created.json()["data"]["id"]
    started = client.post(f"{PREFIX}/tasks/{task_id}/start", json={})
    assert started.status_code == 200, started.text
    return started.json()["data"]


def _wait_status(client: TestClient, task_id: str, wanted: str, *, timeout: float = 8.0) -> dict:
    deadline = time.monotonic() + timeout
    task: dict = {}
    while time.monotonic() < deadline:
        task = client.get(f"{PREFIX}/tasks/{task_id}").json()["data"]
        if task["status"] == wanted:
            return task
        time.sleep(0.02)
    raise AssertionError(f"任务未在 {timeout}s 内进入 {wanted}，当前 {task.get('status')}")


def _wait_last_message(
    client: TestClient, task_id: str, kind: str, *, timeout: float = 8.0
) -> dict:
    """状态与结果消息是两次写入，等消息落库再断言，避免踩进毫秒级窗口。"""
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        messages = client.get(f"{PREFIX}/tasks/{task_id}/messages").json()["data"]
        last = messages[-1] if messages else {}
        if last.get("kind") == kind:
            return last
        time.sleep(0.02)
    raise AssertionError(f"最后一条消息未在 {timeout}s 内变成 {kind}，当前 {last.get('kind')}")


def test_cli_run_completes_and_emits_unified_events(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    binary = _write_script(tmp_path / "fake-codex-ok", _COMPLETED_SCRIPT)
    with _cli_app(settings, tmp_path, binary) as client:
        container = client.app.state.container
        _mark_ready(container, RUNTIME_CODEX)
        agent_id = _create_cli_agent(client, RUNTIME_CODEX)
        started = _start_task(client, agent_id)
        task_id = started["task"]["id"]
        run_id = started["task"]["run_id"]
        assert run_id and started["run"]["run_id"] == run_id
        assert started["run"]["runtime"] == RUNTIME_CODEX

        settled = _wait_status(client, task_id, "completed")
        assert settled["run_id"] == run_id

        # 事件流经事件总线发布（统一词表），含 tool_call
        events = [
            payload
            for event, payload in container.bus.history
            if event == "dsh.event" and payload.get("run_id") == run_id
        ]
        types = [e["event_type"] for e in events]
        assert "tool_call" in types and "tool_result" in types and "message" in types

        # run 目录保留（供排障），配置已收权
        run_dir = Path(container.settings.dsh_home) / "runs" / run_id
        assert run_dir.is_dir()
        config = run_dir / "codex-home" / "config.toml"
        assert config.is_file()
        assert oct(config.stat().st_mode & 0o777) == "0o600"
        assert "mcp_servers.flux" in config.read_text(encoding="utf-8")


def test_cli_run_nonzero_exit_is_failed(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    binary = _write_script(tmp_path / "fake-codex-fail", _FAILED_SCRIPT)
    with _cli_app(settings, tmp_path, binary) as client:
        _mark_ready(client.app.state.container, RUNTIME_CODEX)
        agent_id = _create_cli_agent(client, RUNTIME_CODEX)
        started = _start_task(client, agent_id)
        task_id = started["task"]["id"]

        settled = _wait_status(client, task_id, "failed")
        assert settled["status"] == "failed"
        message = _wait_last_message(client, task_id, "run_finished")
        assert "退出码 2" in message["content"]


def test_cli_run_cancel_kills_process_group(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    binary = _write_script(tmp_path / "fake-codex-hold", _CANCEL_SCRIPT)
    with _cli_app(settings, tmp_path, binary) as client:
        container = client.app.state.container
        _mark_ready(container, RUNTIME_CODEX)
        agent_id = _create_cli_agent(client, RUNTIME_CODEX)
        started = _start_task(client, agent_id)
        task_id = started["task"]["id"]
        run_id = started["task"]["run_id"]

        # 明确等到进程进入 RUNNING（pid/pgid 已登记）再取消
        snapshot = None
        for _ in range(300):
            snapshot = container.dsh.supervisor.monitor_snapshot(run_id)
            if snapshot and snapshot["pgid"]:
                break
            time.sleep(0.02)
        assert snapshot and snapshot["pgid"]

        cancelled = client.post(f"{PREFIX}/tasks/{task_id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["data"]["status"] == "cancelled"
        settled = _wait_status(client, task_id, "cancelled")
        assert settled["status"] == "cancelled"


def test_cli_runtime_requires_ready_installation(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    binary = _write_script(tmp_path / "fake-codex-ok2", _COMPLETED_SCRIPT)
    with _cli_app(settings, tmp_path, binary) as client:
        # 不置 READY：启动必须 409 并给出 flux agents connect 指引
        agent_id = _create_cli_agent(client, RUNTIME_CODEX)
        created = client.post(
            f"{PREFIX}/tasks", json={"description": "x", "agent_id": agent_id}
        ).json()["data"]
        response = client.post(f"{PREFIX}/tasks/{created['id']}/start", json={})
        assert response.status_code == 409
        assert "flux agents connect" in response.json()["message"]
        assert client.get(f"{PREFIX}/tasks/{created['id']}").json()["data"]["run_id"] is None


def test_agent_runtime_value_is_validated(settings: Settings, db_schema: None) -> None:
    container = create_app(settings).state.container

    async def _attempt() -> None:
        with pytest.raises(ValidationError):
            await container.agents.create(
                AgentSpec(name="bad", role=AgentRole.DEVELOPER, runtime="nope")
            )

    asyncio.run(_attempt())


def test_default_agent_runtime_is_dsh(settings: Settings, db_schema: None) -> None:
    container = create_app(settings).state.container

    async def _create() -> str:
        handle = await container.agents.create(AgentSpec(name="默认", role=AgentRole.DEVELOPER))
        return handle.spec.runtime

    assert asyncio.run(_create()) == DEFAULT_RUNTIME


def test_opencode_auth_probe_parses_config_not_only_auth_list(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """P2-2 §7.2：provider 内联在配置里就算已认证，不因 auth.json 为空误判 missing。"""
    import json

    config = tmp_path / "opencode.json"
    config.write_text(
        json.dumps({"provider": {"deepseek": {"options": {"apiKey": "sk-local"}}}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("OPENCODE_CONFIG", str(config))
    monkeypatch.setenv("HOME", str(tmp_path))

    def _never_called(*_args: object, **_kwargs: object) -> tuple[int, str]:
        raise AssertionError("配置已解析到凭据时不应调用 auth list")

    adapter = OpenCodeAdapter(which=lambda _: "/usr/local/bin/opencode", probe_runner=_never_called)
    assert adapter.check_auth() == "ok"


def test_opencode_auth_probe_does_not_call_empty_auth_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """配置里没有凭据、auth list 显示 0 credentials：降级为 unknown，而非 missing。"""
    monkeypatch.setenv("OPENCODE_CONFIG", str(tmp_path / "empty.json"))
    monkeypatch.setenv("HOME", str(tmp_path))
    (tmp_path / "empty.json").write_text("{}", encoding="utf-8")
    adapter = OpenCodeAdapter(
        which=lambda _: "/usr/local/bin/opencode",
        probe_runner=lambda *_a, **_k: (0, "0 credentials"),
    )
    assert adapter.check_auth() == "unknown"


def test_dsh_runtime_wraps_client_behaviour(tmp_path: Path) -> None:
    """DshRuntime 薄包装：start 直接转调 start_run 并回传同一份快照（迁移零回归）。"""

    class _FakeRun:
        run_id = "r1"

        def to_dict(self) -> dict:
            return {"run_id": "r1", "status": "pending"}

    class _FakeClient:
        def __init__(self) -> None:
            self.calls: list[dict] = []

        async def start_run(self, instruction, **kwargs):
            self.calls.append({"instruction": instruction, **kwargs})
            return _FakeRun()

    async def _exercise() -> tuple[RuntimeProcess, _FakeClient]:
        client = _FakeClient()
        runtime = DshRuntime(client, workspace=str(tmp_path))
        task = type("T", (), {"id": "t1"})()
        launch = await runtime.prepare(task=task, agent=None, run_id="r1", instruction="hi")
        return await runtime.start(launch), client

    proc, client = asyncio.run(_exercise())
    assert proc.snapshot == {"run_id": "r1", "status": "pending"}
    assert client.calls[0]["run_id"] == "r1" and client.calls[0]["task_id"] == "t1"
