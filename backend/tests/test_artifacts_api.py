"""Artifacts API 测试：页面 / 文档落库，设计稿只做 Luma 投影。"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Iterator

import httpx
import pytest
from fastapi.testclient import TestClient

from flux.api.v1 import designs
from flux.config import Settings
from flux.core.artifacts.content import (
    ASSET_MAX_FILES,
    ASSET_MAX_TOTAL_BYTES,
    MAX_CONTENT_BYTES,
)
from flux.core.artifacts.extract import ExtractFetchError
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
    assert client.post(f"{PREFIX}/artifacts/design:x/pin").status_code == 400
    assert client.get(f"{PREFIX}/artifacts/design:x/versions").status_code == 400


def test_extended_fields_and_html_title(artifacts_client) -> None:
    client, _ = artifacts_client
    html = (
        "<!doctype html><html><head><title>真实标题</title></head><body><h1>Hi</h1></body></html>"
    )
    created = client.post(
        f"{PREFIX}/artifacts",
        json={
            "type": "page",
            "title": "参数标题不应覆盖标签",
            "content": html,
            "description": "画廊副标题",
            "icon": "chart",
            "label": "初版",
            "capabilities": {"downloads": {}},
            "contract": "latest",
            "pinned": True,
        },
    )
    assert created.status_code == 200
    data = created.json()["data"]
    assert data["title"] == "真实标题"
    assert data["description"] == "画廊副标题"
    assert data["icon"] == "chart"
    assert data["label"] == "初版"
    assert data["capabilities"] == {"downloads": {}}
    assert data["contract"] == "latest"
    assert data["pinned"] is True
    assert data["version"] == 1

    listed = client.get(f"{PREFIX}/artifacts", params={"type": "page"}).json()["data"][0]
    assert listed["description"] == "画廊副标题"
    assert listed["pinned"] is True
    assert "content" not in listed

    # 省略 capabilities → 沿用已存声明，不会被顺手清空。
    carried = client.put(
        f"{PREFIX}/artifacts/{data['id']}",
        json={"icon": "map"},
    )
    assert carried.status_code == 200
    assert carried.json()["data"]["version"] == 2
    assert carried.json()["data"]["capabilities"] == {"downloads": {}}
    assert carried.json()["data"]["description"] == "画廊副标题"

    updated = client.put(
        f"{PREFIX}/artifacts/{data['id']}",
        json={
            "description": "新副标题",
            "icon": "map",
            "label": "二版",
            "capabilities": {},
            "contract": "",
        },
    )
    assert updated.status_code == 200
    changed = updated.json()["data"]
    assert changed["version"] == 3
    assert changed["description"] == "新副标题"
    assert changed["icon"] == "map"
    assert changed["label"] == "二版"
    assert changed["capabilities"] == {}
    assert changed["contract"] is None
    assert changed["title"] == "真实标题"

    # 更新时也不能用参数覆盖 HTML 里已有的 <title>。
    retitled = client.put(f"{PREFIX}/artifacts/{data['id']}", json={"title": "想覆盖"})
    assert retitled.status_code == 200
    assert retitled.json()["data"]["title"] == "真实标题"


def test_content_validation_rejects_replacement_char_and_oversize(artifacts_client) -> None:
    client, _ = artifacts_client
    bad = client.post(
        f"{PREFIX}/artifacts",
        json={"type": "page", "content": "<p>ok</p>\n坏字符�"},
    )
    assert bad.status_code == 422
    details = bad.json()["data"]
    assert details["reason"] == "invalid_encoding"
    assert details["position"]["line"] == 2

    oversize = client.post(
        f"{PREFIX}/artifacts",
        json={"type": "doc", "content": "a" * (MAX_CONTENT_BYTES + 1)},
    )
    assert oversize.status_code == 422
    assert oversize.json()["data"]["reason"] == "content_too_large"


def test_version_history_written_and_read(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts",
        json={"type": "doc", "title": "版本测试", "content": "# v1"},
    ).json()["data"]
    artifact_id = created["id"]

    put = client.put(
        f"{PREFIX}/artifacts/{artifact_id}",
        json={"title": "版本测试 v2", "content": "# v2"},
    )
    assert put.status_code == 200
    assert put.json()["data"]["version"] == 2

    # 只改描述也应有快照：历史是「正文 / 元数据写入」的历史，不只是正文。
    put2 = client.put(
        f"{PREFIX}/artifacts/{artifact_id}",
        json={"description": "第三版说明"},
    )
    assert put2.json()["data"]["version"] == 3

    versions = client.get(f"{PREFIX}/artifacts/{artifact_id}/versions")
    assert versions.status_code == 200
    body = versions.json()
    assert body["metadata"]["total"] == 3
    assert [item["version"] for item in body["data"]] == [3, 2, 1]
    assert all("content" not in item for item in body["data"])

    first = client.get(f"{PREFIX}/artifacts/{artifact_id}/versions/1")
    assert first.status_code == 200
    snapshot = first.json()["data"]
    assert snapshot["title"] == "版本测试"
    assert snapshot["content"] == "# v1"
    assert snapshot["capabilities"] == {}

    missing = client.get(f"{PREFIX}/artifacts/{artifact_id}/versions/99")
    assert missing.status_code == 404


def test_base_version_conflict_returns_current_and_force_overwrites(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts",
        json={"type": "doc", "title": "冲突", "content": "# v1"},
    ).json()["data"]
    artifact_id = created["id"]
    client.put(f"{PREFIX}/artifacts/{artifact_id}", json={"content": "# v2"})

    stale = client.put(
        f"{PREFIX}/artifacts/{artifact_id}",
        json={"content": "# stale", "base_version": 1},
    )
    assert stale.status_code == 409
    assert stale.json()["code"] == "artifact_conflict"
    current = stale.json()["data"]
    assert current["version"] == 2
    assert current["content"] == "# v2"
    assert current["title"] == "冲突"

    forced = client.put(
        f"{PREFIX}/artifacts/{artifact_id}",
        json={"content": "# forced", "base_version": 1, "force": True},
    )
    assert forced.status_code == 200
    assert forced.json()["data"]["version"] == 3
    assert forced.json()["data"]["content"] == "# forced"


def test_list_pagination_and_next_page_token(artifacts_client) -> None:
    client, _ = artifacts_client
    for index in range(3):
        client.post(
            f"{PREFIX}/artifacts",
            json={"type": "doc", "title": f"分页 {index}", "content": f"# {index}"},
        )

    everything = client.get(f"{PREFIX}/artifacts").json()
    assert len(everything["data"]) == 3
    assert everything["metadata"]["total"] == 3

    first = client.get(f"{PREFIX}/artifacts", params={"limit": 2}).json()
    assert len(first["data"]) == 2
    token = first["metadata"]["next_page_token"]
    assert token

    second = client.get(f"{PREFIX}/artifacts", params={"limit": 2, "page_token": token}).json()
    assert len(second["data"]) == 1
    assert second["metadata"]["next_page_token"] is None


def test_list_types_endpoint(artifacts_client) -> None:
    client, _ = artifacts_client
    response = client.get(f"{PREFIX}/artifacts/types")
    assert response.status_code == 200
    types = {item["type"]: item for item in response.json()["data"]}
    assert set(types) == {"page", "doc", "design"}
    assert types["design"]["stored"] is False
    assert "只读" in types["design"]["description"]


def test_pin_and_unpin(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts", json={"type": "doc", "title": "钉选", "content": "# x"}
    ).json()["data"]
    artifact_id = created["id"]

    pinned = client.post(f"{PREFIX}/artifacts/{artifact_id}/pin")
    assert pinned.status_code == 200
    assert pinned.json()["data"]["pinned"] is True
    assert pinned.json()["data"]["version"] == 1
    assert client.get(f"{PREFIX}/artifacts/{artifact_id}").json()["data"]["pinned"] is True

    unpinned = client.post(f"{PREFIX}/artifacts/{artifact_id}/unpin")
    assert unpinned.status_code == 200
    assert unpinned.json()["data"]["pinned"] is False
    assert client.get(f"{PREFIX}/artifacts/{artifact_id}").json()["data"]["version"] == 1


def test_asset_upload_list_download_delete_and_usage(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts", json={"type": "doc", "title": "带资产", "content": "# x"}
    ).json()["data"]
    artifact_id = created["id"]

    uploaded = client.post(
        f"{PREFIX}/artifacts/{artifact_id}/assets",
        params={"name": "note.txt", "mime": "text/plain"},
        content=b"hello",
    )
    assert uploaded.status_code == 200
    asset = uploaded.json()["data"]
    assert len(asset["id"]) == 32
    assert asset["file_name"] == "note.txt"
    assert asset["content_type"] == "text/plain"
    assert asset["size_bytes"] == 5
    assert len(asset["sha256"]) == 64
    assert "storage_path" not in asset
    assert uploaded.json()["metadata"]["usage"]["files"] == 1

    listed = client.get(f"{PREFIX}/artifacts/{artifact_id}/assets").json()
    assert [item["id"] for item in listed["data"]["assets"]] == [asset["id"]]
    usage = listed["data"]["usage"]
    assert usage == {
        "files": 1,
        "bytes": 5,
        "max_files": ASSET_MAX_FILES,
        "max_bytes": ASSET_MAX_TOTAL_BYTES,
    }

    download = client.get(f"{PREFIX}/artifacts/{artifact_id}/assets/{asset['id']}")
    assert download.status_code == 200
    assert download.content == b"hello"
    assert download.headers["content-type"].startswith("text/plain")
    assert "attachment" in download.headers["content-disposition"]

    deleted = client.delete(f"{PREFIX}/artifacts/{artifact_id}/assets/{asset['id']}")
    assert deleted.status_code == 200
    assert deleted.json()["data"]["deleted"] is True
    assert deleted.json()["metadata"]["usage"]["files"] == 0
    assert client.get(f"{PREFIX}/artifacts/{artifact_id}/assets/{asset['id']}").status_code == 404


def test_asset_rejects_unsupported_type_and_empty_file(artifacts_client) -> None:
    client, _ = artifacts_client
    created = client.post(
        f"{PREFIX}/artifacts", json={"type": "doc", "title": "资产校验", "content": "# x"}
    ).json()["data"]
    artifact_id = created["id"]

    unsupported = client.post(
        f"{PREFIX}/artifacts/{artifact_id}/assets",
        params={"name": "page.html", "mime": "text/html"},
        content=b"<h1>x</h1>",
    )
    assert unsupported.status_code == 422
    assert unsupported.json()["data"]["reason"] == "unsupported_content_type"

    empty = client.post(
        f"{PREFIX}/artifacts/{artifact_id}/assets",
        params={"name": "empty.txt", "mime": "text/plain"},
        content=b"",
    )
    assert empty.status_code == 422
    assert empty.json()["data"]["reason"] == "empty_file"


# ---------------------------------------------------------------- 网页提取


ARTICLE_HTML = """<!doctype html>
<html><head>
  <title>可维护的提取器</title>
  <meta name="description" content="一篇关于正文提取的文章" />
</head><body>
  <nav>首页 / 分类 / 关于</nav>
  <article>
    <h1>可维护的提取器</h1>
    <p>这是文章的第一段正文，内容足够长，用来确认提取器选中的是 article 而不是导航或页脚。</p>
    <p>第二段包含<a href="/docs/extract">一个相对链接</a>和 <strong>加粗文字</strong>。</p>
    <ul><li>保留列表</li><li>保留代码</li></ul>
    <pre><code class="language-python">print("hello")</code></pre>
  </article>
  <footer>版权所有</footer>
</body></html>
"""


def _long_article_html() -> str:
    paragraph = (
        "这是文章的第一段正文，内容足够长，用来确认提取器选中的是 article 而不是导航或页脚。" * 2
    )
    return ARTICLE_HTML.replace(
        "这是文章的第一段正文，内容足够长，用来确认提取器选中的是 article 而不是导航或页脚。",
        paragraph,
    ).replace("<script>alert(1)</script>", "")


def test_extract_doc_from_client_html(artifacts_client) -> None:
    client, _ = artifacts_client
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={
            "url": "https://example.com/posts/1?utm=x#frag",
            "type": "doc",
            "html": _long_article_html(),
            "title": "客户端标题只作兜底",
        },
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["fallback"] is False
    artifact = data["artifact"]
    assert artifact["type"] == "doc"
    assert artifact["title"] == "可维护的提取器"
    assert "这是文章的第一段正文" in artifact["content"]
    assert "[一个相对链接](https://example.com/docs/extract)" in artifact["content"]
    assert "```python" not in artifact["content"]  # 轻量提取只保留代码块，不猜语言
    assert artifact["metadata"]["source_url"] == "https://example.com/posts/1?utm=x"
    assert data["excerpt"].startswith("一篇关于正文提取的文章")

    listed = client.get(f"{PREFIX}/artifacts", params={"type": "doc"}).json()["data"]
    assert any(item["id"] == artifact["id"] for item in listed)


def test_extract_page_is_single_file_and_strips_scripts(artifacts_client) -> None:
    client, _ = artifacts_client
    html = _long_article_html().replace(
        "<article>", '<article><script>alert("x")</script><p onclick="bad()">正文</p>'
    )
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={"url": "https://example.com/page", "type": "page", "html": html},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["fallback"] is False
    content = data["artifact"]["content"]
    assert content.startswith("<!doctype html>")
    assert "<title>可维护的提取器</title>" in content
    assert "alert(\"x\")" not in content
    assert "onclick" not in content
    assert "https://example.com/page" in content


def test_extract_falls_back_to_link_and_excerpt_when_content_is_thin(artifacts_client) -> None:
    client, _ = artifacts_client
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={
            "url": "https://example.com/thin",
            "type": "doc",
            "html": "<html><head><title>空白页</title></head><body><p>太短</p></body></html>",
        },
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["fallback"] is True
    assert data["artifact"]["title"] == "空白页"
    assert "https://example.com/thin" in data["artifact"]["content"]
    assert data["note"]
    assert "已改为保存链接" in data["note"]


def test_extract_fetch_failure_falls_back_instead_of_error(artifacts_client, monkeypatch) -> None:
    client, _ = artifacts_client

    async def boom(url: str) -> str:
        raise ExtractFetchError("目标网站返回 HTTP 403，已改为保存链接与标题")

    monkeypatch.setattr("flux.api.v1.artifacts.fetch_page_html", boom)
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={"url": "https://blocked.example/a", "type": "page", "title": "被挡住的页面"},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["fallback"] is True
    assert data["artifact"]["title"] == "被挡住的页面"
    assert "blocked.example/a" in data["artifact"]["content"]
    assert data["note"] == "目标网站返回 HTTP 403，已改为保存链接与标题"


def test_extract_fetches_when_client_html_is_missing(artifacts_client, monkeypatch) -> None:
    client, _ = artifacts_client
    fetched: list[str] = []

    async def fake_fetch(url: str) -> str:
        fetched.append(url)
        return _long_article_html()

    monkeypatch.setattr("flux.api.v1.artifacts.fetch_page_html", fake_fetch)
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={"url": "https://example.com/fetched", "type": "doc"},
    )
    assert response.status_code == 200
    assert fetched == ["https://example.com/fetched"]
    assert response.json()["data"]["fallback"] is False
    assert response.json()["data"]["artifact"]["title"] == "可维护的提取器"


def test_extract_rejects_unsafe_or_invalid_urls(artifacts_client) -> None:
    client, _ = artifacts_client
    for url in (
        "",
        "javascript:alert(1)",
        "ftp://example.com/a",
        "http://127.0.0.1/private",
        "http://localhost:8000/admin",
        "http://192.168.1.10/",
    ):
        response = client.post(
            f"{PREFIX}/artifacts/extract",
            json={"url": url, "type": "doc", "html": "<p>x</p>"},
        )
        assert response.status_code == 400, url
        assert response.json()["message"], url


def test_extract_rejects_design_type_and_invalid_task_id(artifacts_client) -> None:
    client, _ = artifacts_client
    assert (
        client.post(
            f"{PREFIX}/artifacts/extract",
            json={"url": "https://example.com", "type": "design"},
        ).status_code
        == 400
    )
    assert (
        client.post(
            f"{PREFIX}/artifacts/extract",
            json={
                "url": "https://example.com",
                "type": "doc",
                "task_id": "not-a-uuid",
            },
        ).status_code
        == 400
    )
    assert (
        client.post(
            f"{PREFIX}/artifacts/extract",
            json={
                "url": "https://example.com",
                "type": "doc",
                "task_id": str(uuid.uuid4()),
            },
        ).status_code
        == 404
    )


def test_extract_optional_send_back_to_conversation(artifacts_client) -> None:
    client, _ = artifacts_client
    task_id = client.post(f"{PREFIX}/tasks", json={"description": "收集资料"}).json()["data"]["id"]
    response = client.post(
        f"{PREFIX}/artifacts/extract",
        json={
            "url": "https://example.com/posts/1",
            "type": "doc",
            "html": _long_article_html(),
            "task_id": task_id,
        },
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["task_id"] == task_id
    assert data["message_id"]
    assert data["artifact"]["metadata"]["task_id"] == task_id

    messages = client.get(f"{PREFIX}/tasks/{task_id}/messages").json()["data"]
    card = next(item for item in messages if item["kind"] == "artifact_card")
    assert card["payload"]["artifact_id"] == data["artifact"]["id"]
    assert card["payload"]["type"] == "doc"
