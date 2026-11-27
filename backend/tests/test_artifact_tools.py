"""Artifacts MCP 工具测试（artifact.publish / list / get）。"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest

from flux.core.agent_runtime.protocol import RUNTIME_BOOTSTRAP
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.server import SERVER_INSTRUCTIONS
from flux.core.mcp.tools import TOOLS
from flux.core.mcp.tools.base import ToolContext
from flux.core.permissions.modes import PLAN_ALLOWED_TOOLS
from flux.enums import Capability
from flux.errors import NotFoundError, ValidationError
from flux.models.artifact import Artifact


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
    """与真实仓储同形的内存实现：只覆盖工具用到的四个方法。"""

    def __init__(self) -> None:
        self.items: dict[str, Artifact] = {}

    async def list(self, kind: str | None = None) -> list[Artifact]:
        return [item for item in self.items.values() if kind is None or item.type == kind]

    async def get(self, artifact_id: str) -> Artifact:
        item = self.items.get(str(artifact_id))
        if item is None:
            raise NotFoundError(f"产物 {artifact_id} 不存在")
        return item

    async def create(
        self, *, type: str, title: str, content: str = "", meta: dict | None = None
    ) -> Artifact:
        artifact = Artifact(
            id=uuid.uuid4(),
            type=type,
            title=title,
            content=content,
            version=1,
            meta=meta or {},
        )
        self.items[str(artifact.id)] = artifact
        return artifact

    async def update(
        self,
        artifact_id: str,
        *,
        title: str | None = None,
        content: str | None = None,
        base_version: int | None = None,
        meta: dict | None = None,
    ) -> Artifact:
        artifact = await self.get(artifact_id)
        if title is not None:
            artifact.title = title[:160]
        if content is not None:
            artifact.content = content
        if meta:
            artifact.meta = {**(artifact.meta or {}), **meta}
        artifact.version += 1
        return artifact


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
    for name in ("artifact.publish", "artifact.list", "artifact.get"):
        assert name in TOOLS
        assert TOOLS[name].description
    assert "artifact.publish" in RUNTIME_BOOTSTRAP
    assert "artifact.publish" in SERVER_INSTRUCTIONS
    # Plan 模式只读：能看产物，不能发布
    assert {"artifact.list", "artifact.get"} <= PLAN_ALLOWED_TOOLS
    assert "artifact.publish" not in PLAN_ALLOWED_TOOLS


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
