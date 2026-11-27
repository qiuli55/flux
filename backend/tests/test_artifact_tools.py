"""Artifacts MCP 工具测试（artifact.publish / list / get）。"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from flux.core.agent_runtime.protocol import RUNTIME_BOOTSTRAP
from flux.core.artifacts.content import (
    ASSET_MAX_FILES,
    ASSET_MAX_TOTAL_BYTES,
    clean_capabilities,
    clean_contract,
    clean_description,
    clean_icon,
    clean_label,
    create_title,
    update_title,
    validate_asset,
    validate_content,
)
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.server import SERVER_INSTRUCTIONS
from flux.core.mcp.tools import TOOLS
from flux.core.mcp.tools.base import ToolContext
from flux.core.permissions.modes import PLAN_ALLOWED_TOOLS
from flux.enums import Capability
from flux.errors import ConflictError, NotFoundError, ValidationError
from flux.models.artifact import Artifact, ArtifactAsset, ArtifactVersion


class FakeRunRepo:
    def __init__(self, task_id: uuid.UUID | None) -> None:
        self.task_id = task_id

    async def get(self, run_id: str):
        if self.task_id is None:
            return None
        return SimpleNamespace(task_id=self.task_id)


class FakeTaskRepo:
    def __init__(self) -> None:
        self.messages: list = []

    async def add_messages(self, task_id, drafts):
        self.messages.extend(drafts)
        return [SimpleNamespace(id=uuid.uuid4(), kind=draft.kind) for draft in drafts]


class FakeArtifactRepo:
    """与真实仓储同形的内存实现：覆盖工具用到的产物、版本与资产方法。"""

    def __init__(self) -> None:
        self.items: dict[str, Artifact] = {}
        self.versions: dict[str, list[ArtifactVersion]] = {}
        self.assets: dict[str, list[ArtifactAsset]] = {}
        self.asset_bytes: dict[str, bytes] = {}

    def _snapshot(self, artifact: Artifact) -> ArtifactVersion:
        return ArtifactVersion(
            id=uuid.uuid4(),
            artifact_id=artifact.id,
            version=artifact.version,
            title=artifact.title,
            content=artifact.content,
            description=artifact.description,
            icon=artifact.icon,
            label=artifact.label,
            contract=artifact.contract,
            capabilities=artifact.capabilities,
            created_at=datetime.now(timezone.utc),
        )

    async def list(self, kind: str | None = None) -> list[Artifact]:
        return [item for item in self.items.values() if kind is None or item.type == kind]

    async def get(self, artifact_id: str) -> Artifact:
        item = self.items.get(str(artifact_id))
        if item is None:
            raise NotFoundError(f"产物 {artifact_id} 不存在")
        return item

    async def create(
        self,
        *,
        type: str,
        title: str | None = None,
        content: str = "",
        description: str | None = None,
        icon: str | None = None,
        label: str | None = None,
        contract: str | None = None,
        capabilities: dict | None = None,
        pinned: bool = False,
        meta: dict | None = None,
    ) -> Artifact:
        validate_content(type, content)
        artifact = Artifact(
            id=uuid.uuid4(),
            type=type,
            title=create_title(type, content, title),
            content=content,
            description=clean_description(description),
            icon=clean_icon(icon),
            label=clean_label(label),
            contract=clean_contract(contract),
            capabilities=clean_capabilities(capabilities) if capabilities is not None else {},
            pinned=bool(pinned),
            version=1,
            meta=meta or {},
        )
        self.items[str(artifact.id)] = artifact
        self.versions[str(artifact.id)] = [self._snapshot(artifact)]
        self.assets[str(artifact.id)] = []
        return artifact

    async def update(
        self,
        artifact_id: str,
        *,
        title: str | None = None,
        content: str | None = None,
        description: str | None = None,
        icon: str | None = None,
        label: str | None = None,
        contract: str | None = None,
        capabilities: dict | None = None,
        base_version: int | None = None,
        force: bool = False,
        meta: dict | None = None,
    ) -> Artifact:
        artifact = await self.get(artifact_id)
        if base_version is not None and artifact.version != base_version and not force:
            raise ConflictError(
                "产物已被更新",
                code="artifact_conflict",
                details={
                    "artifact_id": str(artifact.id),
                    "version": artifact.version,
                    "base_version": base_version,
                    "title": artifact.title,
                    "content": artifact.content,
                    "current": artifact.to_dict(include_content=True),
                },
            )
        new_content = artifact.content
        if content is not None:
            validate_content(artifact.type, content)
            new_content = content
        artifact.content = new_content
        artifact.title = update_title(
            artifact.type,
            content=new_content,
            incoming_title=title,
            existing_title=artifact.title,
            content_changed=content is not None,
        )
        if description is not None:
            artifact.description = clean_description(description)
        if icon is not None:
            artifact.icon = clean_icon(icon)
        if label is not None:
            artifact.label = clean_label(label)
        if contract is not None:
            artifact.contract = clean_contract(contract)
        if capabilities is not None:
            artifact.capabilities = clean_capabilities(capabilities)
        if meta:
            artifact.meta = {**(artifact.meta or {}), **meta}
        artifact.version += 1
        self.versions[str(artifact.id)].append(self._snapshot(artifact))
        return artifact

    async def set_pinned(self, artifact_id: str, pinned: bool) -> Artifact:
        artifact = await self.get(artifact_id)
        artifact.pinned = bool(pinned)
        return artifact

    async def list_versions(
        self, artifact_id: str, *, limit: int | None = None, offset: int = 0
    ) -> tuple[list[ArtifactVersion], int]:
        rows = sorted(
            self.versions.get(str(artifact_id), []),
            key=lambda item: item.version,
            reverse=True,
        )
        end = None if limit is None else offset + limit
        return rows[offset:end], len(rows)

    async def get_version(self, artifact_id: str, version: int) -> ArtifactVersion:
        for item in self.versions.get(str(artifact_id), []):
            if item.version == version:
                return item
        raise NotFoundError(f"产物 {artifact_id} 没有版本 {version}")

    def _usage(self, artifact_id: str) -> dict[str, int]:
        rows = self.assets.get(str(artifact_id), [])
        return {
            "files": len(rows),
            "bytes": sum(item.size_bytes for item in rows),
            "max_files": ASSET_MAX_FILES,
            "max_bytes": ASSET_MAX_TOTAL_BYTES,
        }

    async def asset_usage(self, artifact_id: str) -> dict[str, int]:
        return self._usage(artifact_id)

    async def add_asset(
        self,
        artifact_id: str,
        *,
        file_name: str,
        content_type: str | None,
        data: bytes,
    ) -> ArtifactAsset:
        await self.get(artifact_id)
        resolved = validate_asset(file_name, content_type or "", data)
        asset = ArtifactAsset(
            id=uuid.uuid4(),
            artifact_id=uuid.UUID(str(artifact_id)),
            file_name=file_name,
            content_type=resolved,
            size_bytes=len(data),
            sha256="0" * 64,
            storage_path="",
            created_at=datetime.now(timezone.utc),
        )
        self.assets.setdefault(str(artifact_id), []).append(asset)
        self.asset_bytes[asset.id.hex] = data
        return asset

    async def list_assets(self, artifact_id: str) -> list[ArtifactAsset]:
        await self.get(artifact_id)
        return list(self.assets.get(str(artifact_id), []))

    async def read_asset(self, artifact_id: str, asset_id: str) -> tuple[ArtifactAsset, bytes]:
        await self.get(artifact_id)
        for asset in self.assets.get(str(artifact_id), []):
            if asset.id.hex == asset_id:
                return asset, self.asset_bytes[asset.id.hex]
        raise NotFoundError(f"资产 {asset_id} 不存在")

    async def delete_asset(self, artifact_id: str, asset_id: str) -> ArtifactAsset:
        asset, _ = await self.read_asset(artifact_id, asset_id)
        self.assets[str(artifact_id)].remove(asset)
        self.asset_bytes.pop(asset.id.hex, None)
        return asset


def _ctx(
    run_id: str | None, task_id: uuid.UUID | None = None
) -> tuple[ToolContext, FakeTaskRepo, FakeArtifactRepo]:
    task_repo = FakeTaskRepo()
    artifacts = FakeArtifactRepo()
    container = SimpleNamespace(
        artifacts=artifacts,
        run_repo=FakeRunRepo(task_id),
        task_repo=task_repo,
    )
    identity = AgentIdentity(
        agent_id=str(uuid.uuid4()),
        token_id=str(uuid.uuid4()),
        scopes=frozenset({Capability.FILE_READ, Capability.FILE_WRITE}),
        run_id=run_id,
    )
    return ToolContext(container=container, identity=identity, tool_name="artifact.test"), (
        task_repo,
        artifacts,
    )


def test_artifact_tools_are_registered_and_instructed() -> None:
    expected = {
        "artifact.publish",
        "artifact.list",
        "artifact.get",
        "artifact.list_types",
        "artifact.pin",
        "artifact.unpin",
        "artifact.upload_asset",
        "artifact.list_assets",
        "artifact.get_asset",
        "artifact.delete_asset",
    }
    assert expected <= set(TOOLS)
    for name in expected:
        assert TOOLS[name].description
    assert "artifact.publish" in RUNTIME_BOOTSTRAP
    assert "artifact.publish" in SERVER_INSTRUCTIONS
    # artifact.publish 的工具描述要写清与 design.* 的互斥关系。
    publish_desc = TOOLS["artifact.publish"].description
    assert "type=page" in publish_desc
    assert "type=doc" in publish_desc
    assert "design.share" in publish_desc
    # 常驻说明不能再说所有页面都必须进设计工作室。
    assert "use the design studio tools (design.list" not in RUNTIME_BOOTSTRAP
    # Plan 模式只读：能看产物与资产，不能发布 / 钉选 / 上传
    assert {
        "artifact.list",
        "artifact.get",
        "artifact.list_types",
        "artifact.list_assets",
        "artifact.get_asset",
    } <= PLAN_ALLOWED_TOOLS
    assert "artifact.publish" not in PLAN_ALLOWED_TOOLS
    assert "artifact.pin" not in PLAN_ALLOWED_TOOLS
    assert "artifact.upload_asset" not in PLAN_ALLOWED_TOOLS


async def test_publish_creates_artifact_and_card_message() -> None:
    task_id = uuid.uuid4()
    ctx, (task_repo, artifacts) = _ctx("run-1", task_id)
    result = await TOOLS["artifact.publish"].handler(
        ctx,
        {"type": "page", "title": "发布说明", "content": "<h1>v1</h1>"},
    )

    assert result["published"] is True
    assert result["type"] == "page"
    assert result["title"] == "发布说明"
    assert result["version"] == 1
    # 产物落库，并记住来源对话（App 详情页据此跳回）
    stored = await artifacts.get(result["artifact_id"])
    assert stored.content == "<h1>v1</h1>"
    assert stored.meta["task_id"] == str(task_id)
    # 对话里只有卡片消息，正文不拼接 URL
    assert len(task_repo.messages) == 1
    message = task_repo.messages[0]
    assert message.payload["metadata"]["task_id"] == str(task_id)
    assert message.kind == "artifact_card"
    assert "http" not in message.content
    assert message.payload["artifact_id"] == result["artifact_id"]
    assert message.payload["summary"] == "<h1>v1</h1>"
    assert "content" not in message.payload


async def test_publish_with_artifact_id_updates_existing() -> None:
    task_id = uuid.uuid4()
    ctx, (task_repo, artifacts) = _ctx("run-1", task_id)
    created = await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "title": "周报", "content": "# v1"}
    )
    updated = await TOOLS["artifact.publish"].handler(
        ctx, {"artifact_id": created["artifact_id"], "content": "# v2"}
    )

    assert updated["version"] == 2
    assert updated["title"] == "周报"
    stored = await artifacts.get(created["artifact_id"])
    assert stored.content == "# v2"
    # 更新不能丢掉首次发布时写入的来源 task_id
    assert stored.meta["task_id"] == str(task_id)
    assert [message.kind for message in task_repo.messages] == ["artifact_card", "artifact_card"]
    assert task_repo.messages[-1].payload["version"] == 2


async def test_publish_rejects_design_and_unknown_type() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    with pytest.raises(ValidationError):
        await TOOLS["artifact.publish"].handler(
            ctx, {"type": "design", "title": "设计稿", "content": "x"}
        )
    with pytest.raises(ValidationError):
        await TOOLS["artifact.publish"].handler(
            ctx, {"type": "page", "title": "空内容", "content": "   "}
        )


async def test_publish_requires_task_run() -> None:
    ctx, (task_repo, _) = _ctx(run_id=None)
    with pytest.raises(ValidationError):
        await TOOLS["artifact.publish"].handler(
            ctx, {"type": "doc", "title": "没有任务", "content": "# x"}
        )
    assert task_repo.messages == []


async def test_list_and_get_do_not_leak_content_in_list() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "title": "文档 A", "content": "# A"}
    )
    listed = await TOOLS["artifact.list"].handler(ctx, {})
    assert listed["count"] == 1
    assert "content" not in listed["artifacts"][0]
    assert listed["artifacts"][0]["summary"] == "# A"

    detail = await TOOLS["artifact.get"].handler(ctx, {"artifact_id": listed["artifacts"][0]["id"]})
    assert detail["artifact"]["content"] == "# A"

    with pytest.raises(ValidationError):
        await TOOLS["artifact.get"].handler(ctx, {"artifact_id": "design:abc"})


async def test_publish_extended_fields_title_from_tag_and_card_payload() -> None:
    task_id = uuid.uuid4()
    ctx, (task_repo, artifacts) = _ctx("run-1", task_id)
    html = (
        "<!doctype html><html><head><title>数据看板</title></head><body><h1>v1</h1></body></html>"
    )
    result = await TOOLS["artifact.publish"].handler(
        ctx,
        {
            "type": "page",
            "title": "参数标题不应覆盖 <title>",
            "content": html,
            "description": "本周增长数据一览",
            "icon": "chart",
            "label": "初版",
            "capabilities": {"downloads": {}},
            "contract": "latest",
            "pin": True,
        },
    )

    # 与官方一致：正文里有 <title> 时，参数 title 不覆盖标签文本。
    assert result["title"] == "数据看板"
    assert result["description"] == "本周增长数据一览"
    assert result["icon"] == "chart"
    assert result["label"] == "初版"
    assert result["capabilities"] == {"downloads": {}}
    assert result["contract"] == "latest"
    assert result["pinned"] is True
    stored = await artifacts.get(result["artifact_id"])
    assert stored.title == "数据看板"
    assert stored.pinned is True
    # 卡片载荷给 UI 足够的最小集合，且不出现可分享 URL / 正文。
    payload = task_repo.messages[0].payload
    assert payload["description"] == "本周增长数据一览"
    assert payload["icon"] == "chart"
    assert payload["pinned"] is True
    assert payload["version"] == 1
    assert "content" not in payload
    assert "http" not in task_repo.messages[0].content


async def test_publish_doc_title_falls_back_to_first_heading() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    result = await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "content": "# 周报\n\n本周完成三件事。"}
    )
    assert result["title"] == "周报"


async def test_publish_conflict_returns_live_content_then_force_overwrites() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    created = await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "title": "周报", "content": "# v1"}
    )
    second = await TOOLS["artifact.publish"].handler(
        ctx, {"artifact_id": created["artifact_id"], "content": "# v2"}
    )
    assert second["version"] == 2

    conflict = await TOOLS["artifact.publish"].handler(
        ctx,
        {"artifact_id": created["artifact_id"], "content": "# stale", "base_version": 1},
    )
    assert conflict["published"] is False
    assert conflict["conflict"] is True
    assert conflict["version"] == 2
    assert conflict["content"] == "# v2"
    assert conflict["title"] == "周报"

    forced = await TOOLS["artifact.publish"].handler(
        ctx,
        {
            "artifact_id": created["artifact_id"],
            "content": "# forced",
            "base_version": 1,
            "force": True,
        },
    )
    assert forced["published"] is True
    assert forced["version"] == 3


async def test_list_pagination_and_list_types() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    for index in range(3):
        await TOOLS["artifact.publish"].handler(
            ctx, {"type": "doc", "title": f"文档 {index}", "content": f"# {index}"}
        )

    first = await TOOLS["artifact.list"].handler(ctx, {"limit": 2})
    assert first["count"] == 2
    assert first["total"] == 3
    assert first["next_page_token"]
    second = await TOOLS["artifact.list"].handler(
        ctx, {"limit": 2, "page_token": first["next_page_token"]}
    )
    assert second["count"] == 1
    assert second["next_page_token"] is None
    assert all("content" not in item for item in first["artifacts"] + second["artifacts"])

    types = await TOOLS["artifact.list_types"].handler(ctx, {})
    assert {item["type"] for item in types["types"]} == {"page", "doc", "design"}
    design = next(item for item in types["types"] if item["type"] == "design")
    assert design["stored"] is False


async def test_pin_and_unpin_tools_do_not_bump_version() -> None:
    ctx, (_, artifacts) = _ctx("run-1", uuid.uuid4())
    created = await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "title": "钉选", "content": "# x"}
    )
    pinned = await TOOLS["artifact.pin"].handler(ctx, {"artifact_id": created["artifact_id"]})
    assert pinned["pinned"] is True
    assert pinned["version"] == 1
    assert (await artifacts.get(created["artifact_id"])).pinned is True

    unpinned = await TOOLS["artifact.unpin"].handler(ctx, {"artifact_id": created["artifact_id"]})
    assert unpinned["pinned"] is False
    assert unpinned["version"] == 1


async def test_asset_tools_upload_list_get_delete_and_usage() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    created = await TOOLS["artifact.publish"].handler(
        ctx, {"type": "doc", "title": "带资产", "content": "# x"}
    )
    artifact_id = created["artifact_id"]

    uploaded = await TOOLS["artifact.upload_asset"].handler(
        ctx,
        {"artifact_id": artifact_id, "file_name": "note.txt", "content": "hello"},
    )
    asset = uploaded["asset"]
    assert len(asset["id"]) == 32
    assert asset["content_type"] == "text/plain"
    assert asset["size_bytes"] == 5
    assert uploaded["usage"]["files"] == 1
    assert uploaded["usage"]["bytes"] == 5

    listed = await TOOLS["artifact.list_assets"].handler(ctx, {"artifact_id": artifact_id})
    assert [item["id"] for item in listed["assets"]] == [asset["id"]]
    assert listed["usage"]["max_files"] == ASSET_MAX_FILES

    read = await TOOLS["artifact.get_asset"].handler(
        ctx, {"artifact_id": artifact_id, "asset_id": asset["id"]}
    )
    assert base64.b64decode(read["content_base64"]) == b"hello"

    binary = await TOOLS["artifact.upload_asset"].handler(
        ctx,
        {
            "artifact_id": artifact_id,
            "file_name": "dot.png",
            "content_base64": base64.b64encode(b"\x89PNG").decode("ascii"),
        },
    )
    assert binary["asset"]["content_type"] == "image/png"

    deleted = await TOOLS["artifact.delete_asset"].handler(
        ctx, {"artifact_id": artifact_id, "asset_id": asset["id"]}
    )
    assert deleted["deleted"] is True
    assert deleted["usage"]["files"] == 1

    with pytest.raises(ValidationError):
        await TOOLS["artifact.upload_asset"].handler(
            ctx,
            {"artifact_id": artifact_id, "file_name": "page.html", "content": "<h1>x</h1>"},
        )


async def test_publish_rejects_unknown_params() -> None:
    ctx, _ = _ctx("run-1", uuid.uuid4())
    with pytest.raises(ValidationError) as excinfo:
        await TOOLS["artifact.publish"].handler(
            ctx,
            {
                "type": "doc",
                "title": "x",
                "content": "# x",
                "favicon": "https://example.test/icon.png",
            },
        )
    assert "favicon" in excinfo.value.message
