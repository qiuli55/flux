"""密钥库 API 测试：加密落库、掩码响应、CRUD 与任务级 key 引用。"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Callable

import httpx
import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.runtimes.base import ModelChannel
from flux.core.key_vault.probe import (
    REASON_INVALID_KEY,
    REASON_MODEL_UNAVAILABLE,
    REASON_NETWORK_UNREACHABLE,
    REASON_OTHER,
    REASON_QUOTA_EXHAUSTED,
    classify_probe_failure,
    probe_channel,
    probe_vault_key,
)

PREFIX = "/api/v1"


def _create_key(client: TestClient, **overrides: object) -> dict:
    body = {
        "name": "DeepSeek 官方",
        "provider": "deepseek",
        "base_url": "https://api.deepseek.com",
        "api_key": "sk-test-12345678",
        "models": ["deepseek-v4-pro"],
    }
    body.update(overrides)
    response = client.post(f"{PREFIX}/keys", json=body)
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _db_path(settings: Settings) -> str:
    return settings.database_url.split("///", 1)[-1]


def test_create_and_list_never_returns_the_secret(client: TestClient, settings: Settings) -> None:
    created = _create_key(client)
    assert created["key_hint"] == "••••5678"
    assert "api_key" not in created
    assert "key_ciphertext" not in created

    listed = client.get(f"{PREFIX}/keys").json()["data"]
    assert len(listed) == 1
    assert listed[0]["id"] == created["id"]
    assert "api_key" not in listed[0]
    assert "key_ciphertext" not in listed[0]

    # 直接查库：密文里不能出现明文 key
    with sqlite3.connect(_db_path(settings)) as conn:
        row = conn.execute("SELECT key_ciphertext FROM api_keys").fetchone()
    assert row is not None
    assert b"sk-test-12345678" not in bytes(row[0])


def test_duplicate_name_conflicts(client: TestClient) -> None:
    _create_key(client)
    response = client.post(
        f"{PREFIX}/keys",
        json={
            "name": "DeepSeek 官方",
            "provider": "deepseek",
            "api_key": "sk-another-9999",
            "models": ["deepseek-chat"],
        },
    )
    assert response.status_code == 409, response.text


def test_invalid_provider_and_missing_base_url_are_rejected(client: TestClient) -> None:
    bad = client.post(
        f"{PREFIX}/keys",
        json={"name": "x", "provider": "nope", "api_key": "sk-x", "models": ["m"]},
    )
    assert bad.status_code == 422

    missing = client.post(
        f"{PREFIX}/keys",
        json={
            "name": "y",
            "provider": "openai_compatible",
            "api_key": "sk-x",
            "models": ["m"],
        },
    )
    assert missing.status_code == 400, missing.text


def test_partial_update_keeps_the_key_when_blank(client: TestClient) -> None:
    created = _create_key(client)
    response = client.patch(
        f"{PREFIX}/keys/{created['id']}",
        json={"name": "DeepSeek 备用", "enabled": False, "api_key": "  "},
    )
    assert response.status_code == 200, response.text
    updated = response.json()["data"]
    assert updated["name"] == "DeepSeek 备用"
    assert updated["enabled"] is False
    assert updated["key_hint"] == created["key_hint"]


def test_task_model_can_reference_a_key_and_delete_clears_it(client: TestClient) -> None:
    key = _create_key(client)
    created = client.post(
        f"{PREFIX}/tasks",
        json={
            "description": "用 key 库模型跑一次",
            "model": "deepseek-v4-pro",
            "key_id": key["id"],
        },
    )
    assert created.status_code == 200, created.text
    task = created.json()["data"]
    assert task["model"] == "deepseek-v4-pro"
    assert task["api_key_id"] == key["id"]

    deleted = client.delete(f"{PREFIX}/keys/{key['id']}")
    assert deleted.status_code == 200, deleted.text
    reloaded = client.get(f"{PREFIX}/tasks/{task['id']}").json()["data"]
    assert reloaded["model"] is None
    assert reloaded["api_key_id"] is None


def test_key_models_are_exposed_per_runtime(client: TestClient) -> None:
    deepseek = _create_key(client)
    openai = _create_key(
        client,
        name="OpenAI 官方",
        provider="openai",
        base_url="https://api.openai.com/v1",
        api_key="sk-openai-abcdef12",
        models=["gpt-5.5"],
    )
    codex = client.post(
        f"{PREFIX}/agents",
        json={"name": "codex-agent", "role": "developer", "runtime": "codex"},
    )
    assert codex.status_code == 200, codex.text
    dsh = client.post(
        f"{PREFIX}/agents",
        json={"name": "dsh-agent", "role": "developer", "runtime": "dsh"},
    )
    assert dsh.status_code == 200, dsh.text

    agents = {a["spec"]["name"]: a for a in client.get(f"{PREFIX}/agents").json()["data"]}
    builtin_models = {(m["key_id"], m["model"]) for m in agents["dsh-agent"]["spec"]["key_models"]}
    codex_models = {(m["key_id"], m["model"]) for m in agents["codex-agent"]["spec"]["key_models"]}
    # dsh 只吃 DeepSeek 协议；codex 同时能看到 DeepSeek 与 OpenAI
    assert (deepseek["id"], "deepseek-v4-pro") in builtin_models
    assert (openai["id"], "gpt-5.5") not in builtin_models
    assert (deepseek["id"], "deepseek-v4-pro") in codex_models
    assert (openai["id"], "gpt-5.5") in codex_models


# --- 连通性探测（保存后校验端点/模型是否可用）---


def _probe_channel_stub(provider: str = "deepseek") -> ModelChannel:
    return ModelChannel(
        provider=provider,
        base_url="https://api.deepseek.com",
        api_key="sk-test-12345678",
        key_id="stub-key",
    )


async def _run_probe(
    handler: Callable[[httpx.Request], httpx.Response], provider: str = "deepseek"
) -> dict[str, object]:
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        return await probe_channel(_probe_channel_stub(provider), "deepseek-v4-pro", client=client)


async def test_probe_channel_ok_hits_chat_completions() -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization", "")
        return httpx.Response(200, json={"choices": [{"message": {"content": "可用"}}]})

    result = await _run_probe(handler)

    assert result["ok"] is True
    assert result["error_code"] == ""
    assert result["reply"] == "可用"
    assert result["latency_ms"] >= 0
    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    assert seen["auth"] == "Bearer sk-test-12345678"


async def test_probe_channel_maps_auth_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "invalid api key"}})

    result = await _run_probe(handler)

    assert result["ok"] is False
    assert result["error_code"] == "provider_auth_error"
    assert result["reason_code"] == REASON_INVALID_KEY
    assert result["reason"] == "密钥无效或权限不足"
    assert "鉴权失败" in str(result["error"])


async def test_probe_channel_redacts_secret_from_upstream_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            401,
            json={"error": {"message": "invalid api key sk-test-12345678"}},
        )

    result = await _run_probe(handler)

    rendered = f"{result['error']} {result['detail']} {result['reason']}"
    assert "sk-test-12345678" not in rendered
    assert "••••5678" in rendered


async def test_probe_channel_maps_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("connect timeout", request=request)

    result = await _run_probe(handler)

    assert result["ok"] is False
    assert result["error_code"] == "provider_timeout"
    assert result["reason_code"] == REASON_NETWORK_UNREACHABLE


def test_probe_endpoint_uses_vault_key(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    created = _create_key(client, models=["model-a", "model-b"])
    calls: dict[str, object] = {}

    async def fake_probe(vault: object, key_id: str) -> dict[str, object]:
        calls["key_id"] = key_id
        return {
            "ok": False,
            "skipped": False,
            "latency_ms": 12,
            "error": "模型名不存在或不可用",
            "models": [
                {"model": "model-a", "ok": True, "status": "available"},
                {
                    "model": "model-b",
                    "ok": False,
                    "status": "unreachable",
                    "reason_code": REASON_MODEL_UNAVAILABLE,
                    "reason": "模型名不存在或不可用",
                },
            ],
        }

    monkeypatch.setattr("flux.api.v1.keys.probe_vault_key", fake_probe)

    response = client.post(f"{PREFIX}/keys/{created['id']}/probe")

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["latency_ms"] == 12
    assert [item["model"] for item in data["models"]] == ["model-a", "model-b"]
    assert calls["key_id"] == created["id"]


def test_probe_disabled_key_is_skipped(client: TestClient) -> None:
    created = _create_key(client, enabled=False)

    response = client.post(f"{PREFIX}/keys/{created['id']}/probe")

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["skipped"] is True
    assert data["error_code"] == "key_disabled"
    assert data["models"] == []


# --- 失败原因分类 ---


@pytest.mark.parametrize(
    ("status", "message", "expected"),
    [
        (401, "Invalid API key", REASON_INVALID_KEY),
        (403, "permission denied", REASON_INVALID_KEY),
        (402, "payment required", REASON_QUOTA_EXHAUSTED),
        (429, "insufficient_quota: you exceeded your current quota", REASON_QUOTA_EXHAUSTED),
        (400, "Model not found: gpt-nope", REASON_MODEL_UNAVAILABLE),
        (404, "The model `x` does not exist", REASON_MODEL_UNAVAILABLE),
        (503, "service unavailable", REASON_NETWORK_UNREACHABLE),
        (None, "connection refused", REASON_NETWORK_UNREACHABLE),
        (429, "rate limit exceeded", REASON_OTHER),
        (400, "temperature must be <= 2", REASON_OTHER),
    ],
)
def test_classify_probe_failure(status: int | None, message: str, expected: str) -> None:
    reason_code, reason = classify_probe_failure(status=status, message=message)

    assert reason_code == expected
    assert reason


def test_classify_probe_failure_other_keeps_short_reason() -> None:
    reason_code, reason = classify_probe_failure(
        status=400, message="bad request: invalid parameter"
    )

    assert reason_code == REASON_OTHER
    assert reason.startswith("其他失败：")
    assert "invalid parameter" in reason


# --- 按模型探测与持久化 ---


def _multi_model_handler(request: httpx.Request) -> httpx.Response:
    payload = json.loads(request.content)
    model = payload.get("model")
    if model == "bad-model":
        return httpx.Response(400, json={"error": {"message": "Model not found: bad-model"}})
    return httpx.Response(200, json={"choices": [{"message": {"content": "可用"}}]})


async def test_probe_vault_key_checks_every_registered_model(container: Container) -> None:
    row = await container.key_vault.create(
        name="多模型 key",
        provider="deepseek",
        base_url="https://api.deepseek.com",
        api_key="sk-test-12345678",
        models=["good-model", "bad-model"],
    )

    async with httpx.AsyncClient(transport=httpx.MockTransport(_multi_model_handler)) as client:
        data = await probe_vault_key(container.key_vault, str(row.id), client=client)

    assert data["ok"] is False
    assert [item["model"] for item in data["models"]] == ["good-model", "bad-model"]
    by_model = {item["model"]: item for item in data["models"]}
    assert by_model["good-model"]["ok"] is True
    assert by_model["good-model"]["status"] == "available"
    assert by_model["good-model"]["reason_code"] == ""
    assert by_model["bad-model"]["ok"] is False
    assert by_model["bad-model"]["status"] == "unreachable"
    assert by_model["bad-model"]["reason_code"] == REASON_MODEL_UNAVAILABLE
    assert by_model["bad-model"]["reason"] == "模型名不存在或不可用"

    # 结果落库：失败的配置没有被删除或停用
    reloaded = await container.key_vault.get(row.id)
    assert reloaded.enabled is True
    assert reloaded.models == ["good-model", "bad-model"]
    assert reloaded.model_connectivity["good-model"]["status"] == "available"
    assert reloaded.model_connectivity["bad-model"]["reason_code"] == REASON_MODEL_UNAVAILABLE


async def test_invalid_key_marks_all_models_and_keeps_config(container: Container) -> None:
    row = await container.key_vault.create(
        name="坏 key",
        provider="deepseek",
        base_url="https://api.deepseek.com",
        api_key="sk-wrong-12345678",
        models=["model-a", "model-b"],
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "Invalid API key"}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        data = await probe_vault_key(container.key_vault, str(row.id), client=client)

    assert all(item["reason_code"] == REASON_INVALID_KEY for item in data["models"])
    assert all(item["reason"] == "密钥无效或权限不足" for item in data["models"])

    reloaded = await container.key_vault.get(row.id)
    assert reloaded.enabled is True
    assert len(reloaded.models) == 2
    assert reloaded.model_connectivity["model-a"]["reason_code"] == REASON_INVALID_KEY


def test_probe_persists_status_and_exposes_it_in_catalog(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    created = _create_key(client, models=["good-model", "bad-model"])
    state = {"bad_ok": False}

    async def fake_probe_channel(
        channel: ModelChannel, model: str, *, timeout: float = 0.0, client: object = None
    ) -> dict[str, object]:
        if model == "bad-model" and not state["bad_ok"]:
            return {
                "ok": False,
                "status": "unreachable",
                "skipped": False,
                "provider": channel.provider,
                "model": model,
                "base_url": channel.base_url,
                "latency_ms": 8,
                "reply": "",
                "error": "模型名不存在或不可用",
                "error_code": "provider_bad_request",
                "reason_code": REASON_MODEL_UNAVAILABLE,
                "reason": "模型名不存在或不可用",
                "detail": "",
                "checked_at": "2026-11-28T00:00:00+00:00",
            }
        return {
            "ok": True,
            "status": "available",
            "skipped": False,
            "provider": channel.provider,
            "model": model,
            "base_url": channel.base_url,
            "latency_ms": 5,
            "reply": "可用",
            "error": "",
            "error_code": "",
            "reason_code": "",
            "reason": "",
            "detail": "",
            "checked_at": "2026-11-28T00:00:00+00:00",
        }

    monkeypatch.setattr("flux.core.key_vault.probe.probe_channel", fake_probe_channel)

    probe = client.post(f"{PREFIX}/keys/{created['id']}/probe")
    assert probe.status_code == 200, probe.text
    assert [item["model"] for item in probe.json()["data"]["models"]] == [
        "good-model",
        "bad-model",
    ]

    listed = {item["id"]: item for item in client.get(f"{PREFIX}/keys").json()["data"]}
    connectivity = listed[created["id"]]["model_connectivity"]
    assert connectivity["good-model"]["status"] == "available"
    assert connectivity["bad-model"]["status"] == "unreachable"
    assert connectivity["bad-model"]["reason_code"] == REASON_MODEL_UNAVAILABLE

    agent = client.post(
        f"{PREFIX}/agents",
        json={"name": "dsh-conn", "role": "developer", "runtime": "dsh"},
    ).json()["data"]
    agents = {a["spec"]["name"]: a for a in client.get(f"{PREFIX}/agents").json()["data"]}
    key_models = {item["model"]: item for item in agents["dsh-conn"]["spec"]["key_models"]}
    assert agent["id"]
    assert key_models["good-model"]["status"] == "available"
    assert key_models["bad-model"]["status"] == "unreachable"
    assert key_models["bad-model"]["reason"] == "模型名不存在或不可用"
    assert key_models["bad-model"]["reason_code"] == REASON_MODEL_UNAVAILABLE

    # 修好模型名后再探测：状态更新为可用，不再显示不通
    state["bad_ok"] = True
    again = client.post(f"{PREFIX}/keys/{created['id']}/probe")
    assert again.status_code == 200, again.text
    listed = {item["id"]: item for item in client.get(f"{PREFIX}/keys").json()["data"]}
    assert listed[created["id"]]["model_connectivity"]["bad-model"]["status"] == "available"


def test_probe_failure_does_not_block_editing_the_saved_key(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    created = _create_key(client)

    async def failing_probe(
        channel: ModelChannel, model: str, *, timeout: float = 0.0, client: object = None
    ) -> dict[str, object]:
        return {
            "ok": False,
            "status": "unreachable",
            "skipped": False,
            "provider": channel.provider,
            "model": model,
            "base_url": channel.base_url,
            "latency_ms": 0,
            "reply": "",
            "error": "网络或服务不可达",
            "error_code": "provider_unavailable",
            "reason_code": REASON_NETWORK_UNREACHABLE,
            "reason": "网络或服务不可达",
            "detail": "",
            "checked_at": "2026-11-28T00:00:00+00:00",
        }

    monkeypatch.setattr("flux.core.key_vault.probe.probe_channel", failing_probe)

    probe = client.post(f"{PREFIX}/keys/{created['id']}/probe")
    assert probe.status_code == 200, probe.text

    listed = client.get(f"{PREFIX}/keys").json()["data"]
    assert len(listed) == 1
    assert listed[0]["enabled"] is True
    assert listed[0]["model_connectivity"]["deepseek-v4-pro"]["status"] == "unreachable"

    # 保存接口本身不因探测失败而失败，条目仍可修改并再次落盘
    updated = client.patch(
        f"{PREFIX}/keys/{created['id']}",
        json={"models": ["deepseek-v4-pro", "deepseek-chat"]},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["data"]["models"] == ["deepseek-v4-pro", "deepseek-chat"]
    # 新模型尚未探测：保持 unchecked，不假装可用/不可用
    assert "deepseek-chat" not in updated.json()["data"]["model_connectivity"]
    assert (
        updated.json()["data"]["model_connectivity"]["deepseek-v4-pro"]["status"] == "unreachable"
    )


def test_changing_upstream_config_clears_old_probe_status(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    created = _create_key(client)

    async def ok_probe(
        channel: ModelChannel, model: str, *, timeout: float = 0.0, client: object = None
    ) -> dict[str, object]:
        return {
            "ok": True,
            "status": "available",
            "skipped": False,
            "provider": channel.provider,
            "model": model,
            "base_url": channel.base_url,
            "latency_ms": 3,
            "reply": "可用",
            "error": "",
            "error_code": "",
            "reason_code": "",
            "reason": "",
            "detail": "",
            "checked_at": "2026-11-28T00:00:00+00:00",
        }

    monkeypatch.setattr("flux.core.key_vault.probe.probe_channel", ok_probe)
    assert client.post(f"{PREFIX}/keys/{created['id']}/probe").status_code == 200

    updated = client.patch(
        f"{PREFIX}/keys/{created['id']}",
        json={"base_url": "https://api.deepseek.com/v2"},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["data"]["model_connectivity"] == {}


def test_switch_model_with_key_and_agent_switch_clears_it(client: TestClient) -> None:
    key = _create_key(client)
    task = client.post(f"{PREFIX}/tasks", json={"description": "切模型", "agent_id": None}).json()[
        "data"
    ]
    agent_id = client.post(
        f"{PREFIX}/agents",
        json={"name": "builtin-2", "role": "developer", "runtime": "dsh"},
    ).json()["data"]["id"]

    switched = client.post(
        f"{PREFIX}/tasks/{task['id']}/model",
        json={"model": "deepseek-v4-pro", "key_id": key["id"]},
    )
    assert switched.status_code == 200, switched.text
    assert switched.json()["data"]["api_key_id"] == key["id"]

    moved = client.post(f"{PREFIX}/tasks/{task['id']}/agent", json={"agent_id": agent_id})
    assert moved.status_code == 200, moved.text
    assert moved.json()["data"]["model"] is None
    assert moved.json()["data"]["api_key_id"] is None


def test_switch_agent_and_model_on_a_finished_task(client: TestClient) -> None:
    """终态对话也能换 Agent / 模型：切换只影响下一次续聊，不执行任何东西。"""
    task = client.post(f"{PREFIX}/tasks", json={"description": "终态切换"}).json()["data"]
    assert client.post(f"{PREFIX}/tasks/{task['id']}/cancel").status_code == 200
    key = _create_key(client)
    agent_id = client.post(
        f"{PREFIX}/agents",
        json={"name": "builtin-3", "role": "developer", "runtime": "dsh"},
    ).json()["data"]["id"]

    switched = client.post(
        f"{PREFIX}/tasks/{task['id']}/model",
        json={"model": "deepseek-v4-pro", "key_id": key["id"]},
    )
    assert switched.status_code == 200, switched.text
    assert switched.json()["data"]["api_key_id"] == key["id"]

    moved = client.post(f"{PREFIX}/tasks/{task['id']}/agent", json={"agent_id": agent_id})
    assert moved.status_code == 200, moved.text
    assert moved.json()["data"]["agent_id"] == agent_id
