"""设计工作室代理 API 测试。

上游 Luma Studio 用 httpx.MockTransport 顶替，不依赖本机 8091 是否在跑。
断言重点：统一响应体、按项目过滤、错误码翻译、AI 请求原样转发。
"""

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
def design_app(settings: Settings, db_schema: None):
    """返回 (app, 安装上游 handler 的函数)。"""
    app = create_app(settings)
    holder: dict[str, Callable[[httpx.Request], httpx.Response]] = {}

    def override():
        return _mock_client(holder["handler"])

    app.dependency_overrides[designs.design_client] = override
    yield app, holder
    app.dependency_overrides.clear()


@pytest.fixture()
def design_client_app(design_app) -> Iterator[tuple[TestClient, dict]]:
    app, holder = design_app
    with TestClient(app) as test_client:
        yield test_client, holder


def test_list_designs_filters_by_project(design_client_app) -> None:
    test_client, holder = design_client_app
    holder["handler"] = lambda request: httpx.Response(
        200,
        json={
            "ok": True,
            "designs": [
                {"id": "a-one", "name": "A", "project_id": "p-a"},
                {"id": "b-two", "name": "B", "project_id": "p-b"},
            ],
        },
    )
    response = test_client.get(f"{PREFIX}/designs", params={"project_id": "p-a"})
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"] == [{"id": "a-one", "name": "A", "project_id": "p-a"}]
    assert body["metadata"]["count"] == 1


def test_get_design_missing_maps_to_not_found(design_client_app) -> None:
    test_client, holder = design_client_app
    holder["handler"] = lambda request: httpx.Response(
        404, json={"ok": False, "error": "设计稿不存在"}
    )
    response = test_client.get(f"{PREFIX}/designs/missing-one")
    assert response.status_code == 404
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "not_found"
    assert body["message"] == "设计稿不存在"


def test_save_design_conflict_keeps_current_version(design_client_app) -> None:
    test_client, holder = design_client_app
    holder["handler"] = lambda request: httpx.Response(
        409,
        json={"ok": False, "error": "设计稿已在别处更新，请刷新后再改", "version": 7},
    )
    response = test_client.put(
        f"{PREFIX}/designs/luma-home",
        json={"base_version": 3, "design": {"screens": []}},
    )
    assert response.status_code == 409
    body = response.json()
    assert body["code"] == "design_conflict"
    assert body["data"] == {"version": 7}


def test_save_design_forwards_base_version(design_client_app) -> None:
    test_client, holder = design_client_app
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/luma-home") and request.method == "PUT":
            import json

            seen.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={"ok": True, "design": {"id": "luma-home", "version": 4}},
            )
        return httpx.Response(404, json={"ok": False, "error": "unexpected"})

    holder["handler"] = handler
    response = test_client.put(
        f"{PREFIX}/designs/luma-home",
        json={"base_version": 3, "design": {"name": "Luma", "screens": [{"id": "s1"}]}},
    )
    assert response.status_code == 200
    assert response.json()["data"]["version"] == 4
    assert response.json()["metadata"]["version"] == 4
    assert seen["base_version"] == 3
    assert seen["design"]["name"] == "Luma"


def test_create_design_forwards_project(design_client_app) -> None:
    test_client, holder = design_client_app
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen.update(json.loads(request.content))
        return httpx.Response(
            201,
            json={
                "ok": True,
                "design": {"id": "draft-abc12345", "version": 1},
                "summary": {"screen_count": 1},
            },
        )

    holder["handler"] = handler
    response = test_client.post(
        f"{PREFIX}/designs",
        json={"name": "新页面", "project_id": "p-a", "project": "Flux 移动端"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["data"]["id"] == "draft-abc12345"
    assert body["metadata"]["summary"] == {"screen_count": 1}
    assert seen["project_id"] == "p-a"


def test_ask_ai_forwards_node_and_prompt(design_client_app) -> None:
    test_client, holder = design_client_app
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen.update(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "ok": True,
                "reply": "圆角调大到 22",
                "ops": [{"set": {"radius": 22}}],
                "model": "deepseek-flash",
            },
        )

    holder["handler"] = handler
    response = test_client.post(
        f"{PREFIX}/designs/luma-home/ai",
        json={"node": {"id": "n1", "type": "button"}, "screen": {"id": "s1"}, "prompt": "圆角调大"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["data"]["reply"] == "圆角调大到 22"
    assert body["data"]["ops"] == [{"set": {"radius": 22}}]
    assert body["metadata"]["ops"] == 1
    assert seen["node"]["id"] == "n1"
    assert seen["prompt"] == "圆角调大"


def test_delete_design_proxies_and_returns_id(design_client_app) -> None:
    test_client, holder = design_client_app
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = request.url.path
        return httpx.Response(200, json={"ok": True, "id": "luma-home"})

    holder["handler"] = handler
    response = test_client.delete(f"{PREFIX}/designs/luma-home")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"] == {"id": "luma-home"}
    assert seen == {"method": "DELETE", "path": "/api/designs/luma-home"}


def test_delete_design_missing_maps_to_not_found(design_client_app) -> None:
    test_client, holder = design_client_app
    holder["handler"] = lambda request: httpx.Response(
        404, json={"ok": False, "error": "设计稿不存在"}
    )
    response = test_client.delete(f"{PREFIX}/designs/missing-one")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_upstream_connection_error_maps_to_503(design_client_app) -> None:
    test_client, holder = design_client_app

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    holder["handler"] = handler
    response = test_client.get(f"{PREFIX}/designs")
    assert response.status_code == 503
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "design_studio_unavailable"


def test_invalid_design_id_is_rejected_before_upstream(design_client_app) -> None:
    test_client, holder = design_client_app
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200, json={"ok": True, "design": {}})

    holder["handler"] = handler
    response = test_client.get(f"{PREFIX}/designs/Bad_ID")
    assert response.status_code == 400
    assert response.json()["code"] == "bad_request"
    assert called is False
