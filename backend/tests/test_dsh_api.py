"""DSH Run API 测试（集成方案 §18 Phase 1）。

用注入 fake harness 的 FluxDshClient 跑通全链路，不启动真实 runtime、不调用真实模型。
"""

from __future__ import annotations

import time

from deepseek_harness import RunResult
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.core.agent_runtime.dsh_client import FluxDshClient
from flux.main import create_app
from tests.fakes import FakeDshHarness, FakeDshHarnessFactory

PREFIX = "/api/v1"


def _blocking_behavior(
    harness: FakeDshHarness,
    instruction: str,
    session_id: str | None,
    on_notification: object,
) -> RunResult:
    """阻塞到收到 session/cancel 再返回，用来验证 API 的中断链路。"""
    harness.cancel_event.wait(timeout=5)
    sid = session_id or "session"
    return RunResult(
        session_id=sid,
        final_response="",
        finish_reason="cancelled",
        events=[],
        notifications=[],
    )


def test_post_runs_disabled_returns_503(settings: Settings) -> None:
    """未启用时（默认）POST /dsh/runs 必须 503 configuration_error。"""
    disabled = settings.model_copy(update={"dsh_enabled": False})
    with TestClient(create_app(disabled)) as client:
        response = client.post(f"{PREFIX}/dsh/runs", json={"instruction": "hi"})

    assert response.status_code == 503
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "configuration_error"


def test_status_reports_configuration(settings: Settings) -> None:
    with TestClient(create_app(settings)) as client:
        response = client.get(f"{PREFIX}/dsh/status")

    assert response.status_code == 200
    data = response.json()["data"]
    assert set(data) == {"enabled", "home", "workspace", "provider", "model"}


def test_dsh_run_api_chain(settings: Settings, tmp_path) -> None:
    """POST → GET → interrupt 全链路：注入 fake harness，Run 最终落到 cancelled。"""
    enabled = settings.model_copy(
        update={
            "dsh_enabled": True,
            "dsh_home": str(tmp_path / "dsh-home"),
            "dsh_workspace": str(tmp_path / "dsh-ws"),
        }
    )
    app = create_app(enabled)
    factory = FakeDshHarnessFactory(_blocking_behavior)

    with TestClient(app) as client:
        container = app.state.container
        container.dsh = FluxDshClient(enabled, bus=container.bus, harness_factory=factory)

        created = client.post(
            f"{PREFIX}/dsh/runs", json={"instruction": "写一个测试", "session_id": "api-1"}
        )
        assert created.status_code == 200
        run = created.json()["data"]
        assert run["status"] == "running"
        assert run["session_id"] == "api-1"

        listed = client.get(f"{PREFIX}/dsh/runs")
        assert listed.status_code == 200
        assert listed.json()["metadata"]["count"] == 1

        detail = client.get(f"{PREFIX}/dsh/runs/{run['run_id']}")
        assert detail.status_code == 200
        assert detail.json()["data"]["run_id"] == run["run_id"]

        # 轮询触发中断，直到 harness 收到 session/cancel（规避 POST 与 harness 注册的竞态）
        for _ in range(300):
            interrupted = client.post(f"{PREFIX}/dsh/runs/{run['run_id']}/interrupt")
            assert interrupted.status_code == 200
            if factory.created and factory.created[0].notifications:
                break
            time.sleep(0.01)
        assert interrupted.json()["data"]["cancel_requested"] is True
        assert factory.created[0].notifications == [("session/cancel", {"sessionId": "api-1"})]

        final = None
        for _ in range(300):
            final = client.get(f"{PREFIX}/dsh/runs/{run['run_id']}").json()["data"]
            if final["status"] == "cancelled":
                break
            time.sleep(0.01)

    assert final is not None
    assert final["status"] == "cancelled"
    assert final["finished_at"] is not None
    assert final["duration_seconds"] is not None


def test_get_missing_run_returns_404(settings: Settings, tmp_path) -> None:
    enabled = settings.model_copy(
        update={
            "dsh_enabled": True,
            "dsh_home": str(tmp_path / "dsh-home"),
            "dsh_workspace": str(tmp_path / "dsh-ws"),
        }
    )
    with TestClient(create_app(enabled)) as client:
        response = client.get(f"{PREFIX}/dsh/runs/does-not-exist")

    assert response.status_code == 404
    assert response.json()["code"] == "not_found"
