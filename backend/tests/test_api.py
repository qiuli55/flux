"""API 层端到端测试（主规格 §12）。

所有断言都检查统一响应体（裁决 A6：success / code / message / data / metadata）。
"""

from __future__ import annotations

import asyncio

from fastapi.testclient import TestClient

from flux.core.virtual_workspace.repository import ProposalRepository
from flux.core.virtual_workspace.service import VirtualWorkspaceService
from flux.db.session import create_engine, create_session_factory

PREFIX = "/api/v1"


def _agent_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "name": "测试 Agent",
        "role": "developer",
        "model_provider": "local",
        "permissions": ["file.read", "file.write"],
    }
    payload.update(overrides)
    return payload


# --- 健康检查 ---


def test_health_is_alive(client: TestClient) -> None:
    response = client.get(f"{PREFIX}/health")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["status"] == "ok"
    assert body["data"]["env"] == "test"


def test_ready_reports_database_and_providers(client: TestClient) -> None:
    response = client.get(f"{PREFIX}/health/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["data"]["database"] is True
    assert body["data"]["providers"] == ["local"]


# --- Agent（§12.3）---


def test_agent_crud_flow(client: TestClient) -> None:
    created = client.post(f"{PREFIX}/agents", json=_agent_payload())
    assert created.status_code == 200
    agent = created.json()["data"]
    assert agent["state"] == "READY"
    assert agent["spec"]["role"] == "developer"

    listed = client.get(f"{PREFIX}/agents").json()
    assert listed["metadata"]["count"] == 1
    assert len(listed["data"]) == 1

    fetched = client.get(f"{PREFIX}/agents/{agent['id']}").json()
    assert fetched["data"]["id"] == agent["id"]


def test_execute_agent_returns_model_result(client: TestClient) -> None:
    agent_id = client.post(f"{PREFIX}/agents", json=_agent_payload()).json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/agents/{agent_id}/execute",
        json={"instruction": "实现一个健康检查接口", "task_id": "task-1"},
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert "实现一个健康检查接口" in data["content"]
    assert data["provider"] == "local"
    assert data["usage"]["total_tokens"] > 0
    assert data["task_id"] == "task-1"


def test_execute_unknown_agent_returns_error_envelope(client: TestClient) -> None:
    response = client.post(
        f"{PREFIX}/agents/0f5b6f4c-0000-0000-0000-000000000000/execute",
        json={"instruction": "x"},
    )
    assert response.status_code == 404
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "not_found"


def test_invalid_payload_returns_validation_error(client: TestClient) -> None:
    response = client.post(f"{PREFIX}/agents", json={"name": "", "role": "不存在的角色"})
    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "validation_error"


# --- Task（§12.4）---


def test_task_create_get_cancel(client: TestClient) -> None:
    created = client.post(f"{PREFIX}/tasks", json={"description": "修复登录缺陷", "priority": 5})
    assert created.status_code == 200
    task = created.json()["data"]
    assert task["status"] == "pending"
    assert task["priority"] == 5

    assert client.get(f"{PREFIX}/tasks/{task['id']}").json()["data"]["id"] == task["id"]

    cancelled = client.post(f"{PREFIX}/tasks/{task['id']}/cancel")
    assert cancelled.status_code == 200
    assert cancelled.json()["data"]["status"] == "cancelled"


def test_cancel_twice_conflicts(client: TestClient) -> None:
    task_id = client.post(f"{PREFIX}/tasks", json={"description": "甲"}).json()["data"]["id"]
    client.post(f"{PREFIX}/tasks/{task_id}/cancel")

    response = client.post(f"{PREFIX}/tasks/{task_id}/cancel")
    assert response.status_code == 409
    assert response.json()["code"] == "conflict"


def test_get_unknown_task_not_found(client: TestClient) -> None:
    response = client.get(f"{PREFIX}/tasks/不存在")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


# --- Virtual Workspace（§12.5）---


def _seed_proposal(client: TestClient, *, file_path: str = "auth/login.py") -> str:
    """直接经服务层写库（提案已落 virtual_changes 表，不能用内存字典塞）。

    单独开一个短生命周期引擎：app 的容器跑在自己的事件循环里，跨循环复用它的
    连接池会踩到 aiosqlite 的连接绑定问题。
    """
    database_url = client.app.state.container.settings.database_url
    engine = create_engine(database_url)

    async def _insert() -> str:
        service = VirtualWorkspaceService(ProposalRepository(create_session_factory(engine)))
        change = await service.propose(
            file_path=file_path,
            original_content="return False\n",
            proposed_content="return check_password(user)\n",
            agent_source="developer-agent",
        )
        return str(change.id)

    try:
        return asyncio.run(_insert())
    finally:
        asyncio.run(engine.dispose())


def test_workspace_list_apply_reject(apply_client: TestClient, workspace_root) -> None:
    """经 API 走完整审查链路：apply 会真正落盘（§7.6），所以先把被改文件写进工作区。"""
    (workspace_root / "a.py").write_text("return False\n", encoding="utf-8")
    apply_id = _seed_proposal(apply_client, file_path="a.py")
    reject_id = _seed_proposal(apply_client, file_path="b.py")

    listed = apply_client.get(f"{PREFIX}/workspace/changes").json()
    assert listed["metadata"]["count"] == 2
    assert listed["data"][0]["diff"].startswith("---")
    assert listed["data"][0]["added_lines"] == 1

    detail = apply_client.get(f"{PREFIX}/workspace/changes/{apply_id}").json()["data"]
    assert detail["status"] == "pending"
    assert detail["original_hash"]

    applied = apply_client.post(f"{PREFIX}/workspace/apply", json={"change_ids": [apply_id]})
    assert applied.status_code == 200
    body = applied.json()["data"][0]
    assert body["status"] == "applied"
    assert body["backup_path"]
    assert (workspace_root / "a.py").read_text(encoding="utf-8") == "return check_password(user)\n"

    rejected = apply_client.post(
        f"{PREFIX}/workspace/reject",
        json={"change_ids": [reject_id], "reason": "改动范围过大"},
    )
    assert rejected.json()["data"][0]["status"] == "rejected"
    assert rejected.json()["metadata"]["reason"] == "改动范围过大"

    assert len(apply_client.get(f"{PREFIX}/workspace/changes?status=applied").json()["data"]) == 1


def test_workspace_accept_then_apply(apply_client: TestClient, workspace_root) -> None:
    """accept 只批准不落盘，apply 才写文件：两步之间文件内容必须保持原样。"""
    (workspace_root / "a.py").write_text("return False\n", encoding="utf-8")
    change_id = _seed_proposal(apply_client, file_path="a.py")

    accepted = apply_client.post(f"{PREFIX}/workspace/accept", json={"change_ids": [change_id]})
    assert accepted.status_code == 200
    assert accepted.json()["data"][0]["status"] == "accepted"
    assert (workspace_root / "a.py").read_text(encoding="utf-8") == "return False\n"

    applied = apply_client.post(f"{PREFIX}/workspace/apply", json={"change_ids": [change_id]})
    assert applied.json()["data"][0]["status"] == "applied"
    assert (workspace_root / "a.py").read_text(encoding="utf-8") == "return check_password(user)\n"


def test_workspace_apply_without_workspace_root_fails(client: TestClient) -> None:
    """未配置 FLUX_WORKSPACE_ROOT 时 apply 明确报错（422），状态停在 accepted 等人工处理。"""
    change_id = _seed_proposal(client, file_path="a.py")

    response = client.post(f"{PREFIX}/workspace/apply", json={"change_ids": [change_id]})

    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"
    detail = client.get(f"{PREFIX}/workspace/changes/{change_id}").json()["data"]
    assert detail["status"] == "accepted"
    assert detail["apply_error"] is None


def test_workspace_apply_conflict_when_file_changed(
    apply_client: TestClient, workspace_root
) -> None:
    """人工在提案生成后改了文件 → 409，用户改动不能被覆盖。"""
    change_id = _seed_proposal(apply_client, file_path="a.py")
    (workspace_root / "a.py").write_text("user 自己改的\n", encoding="utf-8")

    response = apply_client.post(f"{PREFIX}/workspace/apply", json={"change_ids": [change_id]})

    assert response.status_code == 409
    assert response.json()["code"] == "conflict"
    assert (workspace_root / "a.py").read_text(encoding="utf-8") == "user 自己改的\n"


def test_workspace_get_unknown_change(client: TestClient) -> None:
    response = client.get(f"{PREFIX}/workspace/changes/0f5b6f4c-0000-0000-0000-000000000000")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_workspace_apply_unknown_change(client: TestClient) -> None:
    response = client.post(f"{PREFIX}/workspace/apply", json={"change_ids": ["不存在"]})
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


# --- Model Gateway（§12.6）---


def test_models_chat_with_local_provider(client: TestClient) -> None:
    response = client.post(
        f"{PREFIX}/models/chat",
        json={
            "messages": [{"role": "user", "content": "用一句话说明 Virtual Workspace"}],
            "task_id": "task-9",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert "Virtual Workspace" in body["data"]["content"]
    assert body["data"]["provider"] == "local"
    assert body["data"]["usage"]["total_tokens"] > 0
    assert body["metadata"]["task_id"] == "task-9"


def test_models_chat_with_unconfigured_provider(client: TestClient) -> None:
    """M0 未接入真实供应商，必须给出明确错误，而不是假装成功。"""
    response = client.post(
        f"{PREFIX}/models/chat",
        json={
            "messages": [{"role": "user", "content": "你好"}],
            "provider": "openai",
        },
    )
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == "provider_not_configured"
    assert body["data"]["available"] == ["local"]


# --- Connector（§12.7）---


def test_connector_execute_unregistered(client: TestClient) -> None:
    response = client.post(
        f"{PREFIX}/connectors/execute",
        json={"connector": "github", "action": "create_pr", "parameters": {}},
    )
    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "connector_not_registered"
    assert body["data"]["registered"] == []


def test_connector_execute_denied_without_capability(client: TestClient) -> None:
    """客户端不能自行声明能力：agent 没有 terminal.execute 时必须 403。"""
    from flux.connectors.base import ConnectorRegistry
    from tests.fakes import FakeTerminalConnector

    container = client.app.state.container
    registry = ConnectorRegistry(container.bus)
    registry.register(FakeTerminalConnector())
    client.app.state.container.connectors = registry

    agent_id = client.post(
        f"{PREFIX}/agents", json=_agent_payload(permissions=["file.read"])
    ).json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/connectors/execute",
        json={
            "connector": "terminal",
            "action": "run",
            "parameters": {"command": "ls"},
            "agent_id": agent_id,
        },
    )
    assert response.status_code == 403
    assert response.json()["code"] == "permission_denied"


def test_connector_execute_succeeds_with_capability(client: TestClient) -> None:
    from flux.connectors.base import ConnectorRegistry
    from tests.fakes import FakeTerminalConnector

    container = client.app.state.container
    registry = ConnectorRegistry(container.bus)
    registry.register(FakeTerminalConnector())
    client.app.state.container.connectors = registry

    agent_id = client.post(
        f"{PREFIX}/agents", json=_agent_payload(permissions=["terminal.execute"])
    ).json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/connectors/execute",
        json={
            "connector": "terminal",
            "action": "run",
            "parameters": {"command": "pytest"},
            "agent_id": agent_id,
        },
    )
    assert response.status_code == 200
    assert response.json()["data"]["result"]["exit_code"] == 0
