"""Artifacts API 测试：页面 / 文档落库，设计稿只做 Luma 投影。"""

from __future__ import annotations

from collections.abc import Callable, Iterator

import httpx
import pytest
from fastapi.testclient import TestClient

from flux.api.v1 import designs
from flux.config import Settings
from flux.main import create_app

PREFIX = "/api/v1"


def _mock_client(handler: Callable[[httpx.Request], httpx.Response]):
    transport = httpx.MockTransport(handler)
    return httpx.AsyncClient(transport=transport, base_url="http://design.test")


@pytest.fixture()
def artifacts_client(settings: Settings, db_schema: None) -> Iterator[tuple[TestClient, dict]]:
    app = create_app(settings)
    holder: dict[str, Callable[[httpx.Request], httpx.Response]] = {
        "handler": lambda request: httpx.Response(200, json={"ok": True, "designs": []})
    }

    def override():
        return _mock_client(holder["handler"])

    app.dependency_overrides[designs.design_client] = override
    with TestClient(app) as test_client:
        yield test_client, holder
    app.dependency_overrides.clear()


def test_page_artifact_crud(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts",
        json={"type": "page", "title": "落地页", "content": "<h1>Hi</h1>"},
    )
    assert created.status_code == 200
    artifact = created.json()["data"]
    assert artifact["type"] == "page"
    assert artifact["version"] == 1
    assert artifact["content"] == "<h1>Hi</h1>"

    listed = client.get(f"{PREFIX}/artifacts", params={"type": "page"})
    data = listed.json()["data"]
    assert [item["title"] for item in data] == ["落地页"]
    assert "content" not in data[0]
    assert data[0]["summary"] == "<h1>Hi</h1>"

    updated = client.put(
        f"{PREFIX}/artifacts/{artifact['id']}",
        json={"title": "落地页 v2", "base_version": 1},
    )
    assert updated.status_code == 200
    assert updated.json()["data"]["version"] == 2

    stale = client.put(
        f"{PREFIX}/artifacts/{artifact['id']}",
        json={"base_version": 1},
    )
    assert stale.status_code == 409
    assert stale.json()["code"] == "artifact_conflict"

    assert client.delete(f"{PREFIX}/artifacts/{artifact['id']}").status_code == 200
    assert client.get(f"{PREFIX}/artifacts/{artifact['id']}").status_code == 404


def test_reject_design_type_on_create(artifacts_client) -> None:
    client, _ = artifacts_client
    response = client.post(f"{PREFIX}/artifacts", json={"type": "design", "title": "设计稿"})
    assert response.status_code == 400
    assert response.json()["code"] == "bad_request"


def test_designs_are_projected_into_list(artifacts_client) -> None:
    client, holder = artifacts_client
    design = {
        "id": "landing-a",
        "name": "首页投稿",
        "description": "三屏落地页",
        "version": 4,
        "screens": [{}, {}],
        "updated_at": "2026-11-22T10:00:00+00:00",
    }

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/landing-a"):
            return httpx.Response(200, json={"ok": True, "design": design})
        return httpx.Response(200, json={"ok": True, "designs": [design]})

    holder["handler"] = handler
    client.post(f"{PREFIX}/artifacts", json={"type": "doc", "title": "发布说明"})

    response = client.get(f"{PREFIX}/artifacts")
    assert response.status_code == 200
    data = response.json()["data"]
    assert {item["type"] for item in data} == {"design", "doc"}
    projected = next(item for item in data if item["type"] == "design")
    assert projected["id"] == "design:landing-a"
    assert projected["screen_count"] == 2

    detail = client.get(f"{PREFIX}/artifacts/design:landing-a")
    assert detail.status_code == 200
    assert detail.json()["data"]["title"] == "首页投稿"


def test_design_failure_does_not_block_stored_artifacts(artifacts_client) -> None:
    client, holder = artifacts_client

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down", request=request)

    holder["handler"] = boom
    client.post(f"{PREFIX}/artifacts", json={"type": "doc", "title": "离线文档"})

    response = client.get(f"{PREFIX}/artifacts")
    assert response.status_code == 200
    assert [item["title"] for item in response.json()["data"]] == ["离线文档"]


def test_design_update_and_delete_rejected(artifacts_client) -> None:
    client, _ = artifacts_client
    assert client.put(f"{PREFIX}/artifacts/design:x", json={"title": "改"}).status_code == 400
    assert client.delete(f"{PREFIX}/artifacts/design:x").status_code == 400
