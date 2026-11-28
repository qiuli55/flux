"""密钥库 API 测试：加密落库、掩码响应、CRUD 与任务级 key 引用。"""

from __future__ import annotations

import sqlite3
from collections.abc import Callable

import httpx
import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.core.agent_runtime.runtimes.base import ModelChannel
from flux.core.key_vault.probe import probe_channel

PREFIX = "/api/v1"


def _thinking(models: list[str]) -> dict[str, dict[str, object]]:
    return {
        model: {"kind": "levels", "levels": ["none", "low", "high", "max"], "default": "low"}
        for model in models
    }


def _create_key(client: TestClient, **overrides: object) -> dict:
    body = {
        "name": "DeepSeek 官方",
        "provider": "deepseek",
        "base_url": "https://api.deepseek.com",
        "api_key": "sk-test-12345678",
        "models": ["deepseek-v4-pro"],
    }
    body.update(overrides)
    if "model_thinking" not in body:
        body["model_thinking"] = _thinking(list(body["models"]))  # type: ignore[arg-type]
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
            "model_thinking": _thinking(["deepseek-chat"]),
        },
    )
    assert response.status_code == 409, response.text


def test_models_require_thinking_mode_on_create_and_update(client: TestClient) -> None:
    # 创建时登记了模型却没选思考模式：拒绝，不能空着落库
    rejected = client.post(
        f"{PREFIX}/keys",
        json={
            "name": "缺思考",
            "provider": "deepseek",
            "api_key": "sk-missing-thinking",
            "models": ["deepseek-v4-pro"],
        },
    )
    assert rejected.status_code == 400, rejected.text
    assert "思考" in rejected.text

    created = _create_key(client)
    # 保存模型清单时缺一个模型的思考模式也要拒绝
    missing = client.patch(
        f"{PREFIX}/keys/{created['id']}",
        json={
            "models": ["deepseek-v4-pro", "deepseek-chat"],
            "model_thinking": _thinking(["deepseek-v4-pro"]),
        },
    )
    assert missing.status_code == 400, missing.text

    # 每个模型都明确给出时保存成功，并能读回同一形态
    ok = client.patch(
        f"{PREFIX}/keys/{created['id']}",
        json={
            "models": ["deepseek-v4-pro", "deepseek-chat"],
            "model_thinking": {
                "deepseek-v4-pro": {
                    "kind": "toggle",
                    "levels": ["on", "off"],
                    "default": "off",
                },
                "deepseek-chat": {
                    "kind": "levels",
                    "levels": ["none", "low", "high", "max"],
                    "default": "high",
                },
            },
        },
    )
    assert ok.status_code == 200, ok.text
    data = ok.json()["data"]
    assert data["model_thinking"]["deepseek-v4-pro"]["kind"] == "toggle"
    assert data["model_thinking"]["deepseek-v4-pro"]["default"] == "off"
    assert data["model_thinking"]["deepseek-chat"]["default"] == "high"


def test_thinking_defaults_are_prefilled_without_calling_models(
    client: TestClient,
) -> None:
    response = client.post(
        f"{PREFIX}/keys/thinking-defaults",
        json={"models": ["deepseek-v4-pro", "gpt-5.5", "mystery-model"]},
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["deepseek-v4-pro"]["levels"] == ["none", "low", "high", "max"]
    assert data["gpt-5.5"]["levels"] == ["minimal", "low", "medium", "high"]
    # 对不上官方模型时：无思考 + 5 档
    assert data["mystery-model"]["levels"] == [
        "none",
        "minimal",
        "low",
        "medium",
        "high",
        "max",
    ]
    assert data["mystery-model"]["kind"] == "levels"


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
    assert "鉴权失败" in str(result["error"])


async def test_probe_channel_maps_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("connect timeout", request=request)

    result = await _run_probe(handler)

    assert result["ok"] is False
    assert result["error_code"] == "provider_timeout"


def test_probe_endpoint_uses_vault_key(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    created = _create_key(client)
    calls: dict[str, object] = {}

    async def fake_probe(vault: object, key_id: str) -> dict[str, object]:
        calls["key_id"] = key_id
        return {"ok": True, "skipped": False, "latency_ms": 12, "error": ""}

    monkeypatch.setattr("flux.api.v1.keys.probe_vault_key", fake_probe)

    response = client.post(f"{PREFIX}/keys/{created['id']}/probe")

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["ok"] is True
    assert data["latency_ms"] == 12
    assert calls["key_id"] == created["id"]


def test_probe_disabled_key_is_skipped(client: TestClient) -> None:
    created = _create_key(client, enabled=False)

    response = client.post(f"{PREFIX}/keys/{created['id']}/probe")

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["skipped"] is True
    assert data["error_code"] == "key_disabled"


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
