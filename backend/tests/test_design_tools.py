"""设计工作室 MCP 工具测试（design.list / get / create / update / share）。

上游 Luma Studio 用 httpx.MockTransport 顶替，不依赖本机 8091。
断言重点：两步创建、乐观锁冲突翻译、分享消息的卡片载荷、工具面与系统指令的约束。
"""

from __future__ import annotations

import importlib
import json
import uuid
from collections.abc import Callable
from types import SimpleNamespace

import httpx
import pytest

from flux.core.agent_runtime.protocol import RUNTIME_BOOTSTRAP
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.server import SERVER_INSTRUCTIONS
from flux.core.mcp.tools import TOOLS
from flux.core.mcp.tools.base import ToolContext
from flux.enums import Capability
from flux.errors import AIOSError, ConflictError, NotFoundError, ValidationError

# 包 __init__ 里导出了同名函数 design_tools，直接 `import ... as module` 会拿到函数；
# 这里显式取模块本身，monkeypatch 才能替换 _client。
module = importlib.import_module("flux.core.mcp.tools.design_tools")

DESIGN_ID = "draft-abc12345"


def _design(version: int = 2) -> dict:
    return {
        "id": DESIGN_ID,
        "name": "任务中心",
        "project": "Flux 移动端",
        "project_id": None,
        "description": "浅色版",
        "status": "draft",
        "bg": "#F4F6F9",
        "file": f"{DESIGN_ID}.fluxds",
        "version": version,
        "updated_at": "2026-10-25T10:00:00.000Z",
        "screens": [
            {
                "id": "screen-1",
                "name": "任务中心",
                "x": 0,
                "y": 40,
                "w": 390,
                "h": 844,
                "nodes": [
                    {
                        "id": "t1",
                        "type": "text",
                        "name": "标题",
                        "x": 22,
                        "y": 52,
                        "w": 180,
                        "h": 38,
                    },
                    {
                        "id": "card-1",
                        "type": "card",
                        "name": "内容卡",
                        "x": 22,
                        "y": 120,
                        "w": 346,
                        "h": 120,
                        "children": [{"id": "t2", "type": "text", "name": "副标题"}],
                    },
                ],
            }
        ],
    }


class FakeRunRepo:
    def __init__(self, task_id: uuid.UUID | None) -> None:
        self.task_id = task_id

    async def get(self, run_id: str):
        if self.task_id is None:
            return None
        return SimpleNamespace(task_id=self.task_id)


class FakeResolver:
    """design.ai 只用到解析后的模型名与密钥 id。"""

    async def resolve(self, **_: object) -> SimpleNamespace:
        return SimpleNamespace(model="deepseek-chat", key_id=None)


class FakeTaskRepo:
    def __init__(self) -> None:
        self.drafts: list = []
        self.task = SimpleNamespace(project_id=None)

    async def get(self, task_id):
        return self.task

    async def add_messages(self, task_id, drafts):
        self.drafts.extend(drafts)
        return [SimpleNamespace(id=uuid.uuid4(), kind=draft.kind) for draft in drafts]


def _ctx(run_id: str | None, task_id: uuid.UUID | None = None) -> tuple[ToolContext, FakeTaskRepo]:
    task_repo = FakeTaskRepo()
    container = SimpleNamespace(
        settings=SimpleNamespace(
            design_studio_url="http://design.test",
            design_studio_timeout_seconds=5.0,
        ),
        run_repo=FakeRunRepo(task_id),
        task_repo=task_repo,
        completion_resolver=FakeResolver(),
    )
    identity = AgentIdentity(
        agent_id=str(uuid.uuid4()),
        token_id=str(uuid.uuid4()),
        scopes=frozenset({Capability.FILE_READ, Capability.FILE_WRITE}),
        run_id=run_id,
    )
    return ToolContext(container=container, identity=identity, tool_name="design.test"), task_repo


@pytest.fixture()
def upstream(monkeypatch):
    """把 design_tools._client 换成 MockTransport 客户端，并返回记录的请求。"""
    holder: dict[str, Callable[[httpx.Request], httpx.Response]] = {}
    seen: list[httpx.Request] = []

    def fake_client(ctx: ToolContext) -> httpx.AsyncClient:
        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(request)
            return holder["handler"](request)

        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="http://design.test"
        )

    monkeypatch.setattr(module, "_client", fake_client)
    return holder, seen


def test_design_tools_are_registered_and_instructed() -> None:
    for name in (
        "design.list",
        "design.get",
        "design.view",
        "design.preview",
        "design.create",
        "design.update",
        "design.patch",
        "design.layout",
        "design.ai",
        "design.revisions",
        "design.revert",
        "design.share",
        "design.icon_create",
    ):
        assert name in TOOLS
        assert TOOLS[name].description
    assert "design.share" in RUNTIME_BOOTSTRAP
    assert "design studio" in RUNTIME_BOOTSTRAP
    assert "design.ai" in RUNTIME_BOOTSTRAP
    assert "design.icon_create" in RUNTIME_BOOTSTRAP
    assert "design.preview" in RUNTIME_BOOTSTRAP
    assert "design.share" in SERVER_INSTRUCTIONS
    assert "design.ai" in SERVER_INSTRUCTIONS
    assert "design.icon_create" in SERVER_INSTRUCTIONS


async def test_design_preview_returns_http_url_and_curl(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    ctx, _ = _ctx(run_id=None)
    ctx.container.settings.dsh_mcp_url = "http://127.0.0.1:8801/mcp"
    ctx.container.settings.api_v1_prefix = "/api/v1"
    result = await module.design_preview(ctx, {"design_id": DESIGN_ID, "screen": "1"})
    assert result["url"] == (
        f"http://127.0.0.1:8801/api/v1/designs/{DESIGN_ID}/preview.png?scale=2&screen=screen-1"
    )
    assert result["curl"] == (f"curl -sS -o 'preview-{DESIGN_ID}-screen-1.png' '{result['url']}'")
    assert result["image"] == {
        "format": "png",
        "width": 780,
        "height": 1688,
        "scale": 2.0,
    }


async def test_design_preview_rejects_unknown_screen(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="没有这块画板"):
        await module.design_preview(ctx, {"design_id": DESIGN_ID, "screen": "nope"})


async def test_design_list_filters_by_project(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(
        200,
        json={
            "ok": True,
            "designs": [
                {"id": "d-one", "name": "A", "project_id": "p-a"},
                {"id": "d-two", "name": "B", "project_id": "p-b"},
            ],
        },
    )
    ctx, _ = _ctx(run_id=None)
    result = await module.design_list(ctx, {"project_id": "p-a"})
    assert result["count"] == 1
    assert result["designs"][0]["id"] == "d-one"


async def test_design_get_maps_missing_to_not_found(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(
        404, json={"ok": False, "error": "设计稿不存在"}
    )
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(NotFoundError, match="设计稿不存在"):
        await module.design_get(ctx, {"design_id": DESIGN_ID})


async def test_design_create_posts_blank_then_writes_screens(upstream) -> None:
    holder, seen = upstream
    blank = {
        "id": DESIGN_ID,
        "name": "任务中心",
        "project": "Flux 移动端",
        "version": 1,
        "bg": "#F4F6F9",
        "screens": [{"id": "draft-screen", "name": "01 · 新页面", "w": 390, "h": 844, "nodes": []}],
    }
    saved = _design(version=2)

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(201, json={"ok": True, "design": blank})
        assert request.method == "PUT"
        return httpx.Response(200, json={"ok": True, "design": saved})

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    screens = [
        {
            "id": "screen-1",
            "name": "任务中心",
            "w": 390,
            "h": 844,
            "nodes": [{"id": "t1", "type": "text", "name": "标题", "x": 22, "y": 52}],
        }
    ]
    result = await module.design_create(
        ctx, {"name": "任务中心", "project": "Flux 移动端", "screens": screens}
    )

    assert result["created"] is True
    assert result["summary"]["version"] == 2
    assert result["summary"]["layer_count"] == 3
    assert [request.method for request in seen] == ["POST", "PUT"]
    sent = json.loads(seen[1].content)
    assert seen[1].url.path == f"/api/designs/{DESIGN_ID}"
    assert sent["base_version"] == 1
    assert sent["design"]["screens"] == screens


async def test_design_update_conflict_keeps_current_version(upstream) -> None:
    holder, _ = upstream

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design(version=3)})
        return httpx.Response(
            409, json={"ok": False, "error": "设计稿已在别处更新，请刷新后再改", "version": 5}
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ConflictError) as excinfo:
        await module.design_update(ctx, {"design_id": DESIGN_ID, "name": "新名字"})
    assert excinfo.value.code == "design_conflict"
    assert excinfo.value.details["version"] == 5
    assert excinfo.value.details["upstream_status"] == 409


async def test_design_share_writes_design_card_message(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    task_id = uuid.uuid4()
    ctx, task_repo = _ctx(run_id=str(uuid.uuid4()), task_id=task_id)

    result = await module.design_share(ctx, {"design_id": DESIGN_ID, "note": "浅色方案已就绪"})

    assert result["shared"] is True
    assert result["task_id"] == str(task_id)
    assert len(task_repo.drafts) == 1
    draft = task_repo.drafts[0]
    assert draft.kind == "design_card"
    assert draft.content == "浅色方案已就绪"
    assert draft.payload["design_id"] == DESIGN_ID
    assert draft.payload["screen_count"] == 1
    assert draft.payload["layer_count"] == 3
    assert draft.payload["preview"]["w"] == 390
    assert len(draft.payload["preview"]["nodes"]) == 2


async def test_design_share_requires_a_run(upstream) -> None:
    ctx, task_repo = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="必须在 Flux 任务 Run 内调用"):
        await module.design_share(ctx, {"design_id": DESIGN_ID})
    assert task_repo.drafts == []


# ---------------------------------------------------------------- design.ai / revisions / revert


def test_design_ai_is_read_only_in_plan_mode() -> None:
    from flux.core.permissions.modes import PLAN_ALLOWED_TOOLS

    assert "design.ai" not in PLAN_ALLOWED_TOOLS
    assert "design.revert" not in PLAN_ALLOWED_TOOLS
    assert "design.revisions" in PLAN_ALLOWED_TOOLS


async def test_design_ai_commits_ops_instead_of_overwriting_design(upstream) -> None:
    holder, seen = upstream
    asked_ops = [{"op": "set", "target": "t1", "set": {"text": "新标题"}}]
    committed = _design(version=5)
    committed["screens"][0]["nodes"][0]["text"] = "新标题"

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design(version=4)})
        if request.url.path == "/api/ask":
            return httpx.Response(
                200, json={"ok": True, "ops": asked_ops, "reply": "改好了", "model": "m"}
            )
        assert (request.method, request.url.path) == (
            "POST",
            f"/api/designs/{DESIGN_ID}/commits",
        )
        return httpx.Response(
            200,
            json={
                "ok": True,
                "commit_id": "c1",
                "version": 5,
                "applied": 1,
                "skipped": [],
                "deferred": [],
                "touched": ["t1"],
                "rebased": False,
                "design": committed,
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    result = await module.design_ai(
        ctx, {"design_id": DESIGN_ID, "instruction": "把标题改成新标题"}
    )

    assert result["changed"] is True
    assert result["version"] == 5
    assert result["applied"] == 1
    assert result["skipped"] == []
    assert result["deferred"] == []
    assert result["rebased"] is False
    assert result["mode"] == "screen"
    assert [(r.method, r.url.path) for r in seen] == [
        ("GET", f"/api/designs/{DESIGN_ID}"),
        ("POST", "/api/ask"),
        ("POST", f"/api/designs/{DESIGN_ID}/commits"),
    ]
    sent = json.loads(seen[1].content)
    assert sent["mode"] == "screen"
    assert sent["screen"]["w"] == 390
    assert sent["screen"]["nodes"][0]["id"] == "t1"
    # 模型通道由调用方 Agent 解析：Luma 不再用自身的默认路由
    assert sent["agent_id"] == ctx.identity.agent_id
    assert sent["model"] == "deepseek-chat"
    assert sent["key_id"] == ""
    commit = json.loads(seen[2].content)
    assert commit["author"] == "flux-agent"
    assert commit["role"] == "agent"
    assert commit["label"] == "把标题改成新标题"
    assert commit["screen"] == "screen-1"
    assert commit["base_version"] == 4
    assert commit["ops"] == asked_ops
    # 关键：不再整稿 PUT，避免覆盖人同时在改的内容
    assert "design" not in commit


async def test_design_ai_rejects_external_agent(upstream) -> None:
    """外部接入的 Agent 只能自己改画布，不能借用 Flux 的 AI 修改。"""
    holder, seen = upstream
    holder["handler"] = lambda request: httpx.Response(500)
    ctx, _ = _ctx(run_id=None)
    external = ToolContext(
        container=ctx.container,
        identity=AgentIdentity(
            agent_id=ctx.identity.agent_id,
            token_id=ctx.identity.token_id,
            scopes=ctx.identity.scopes,
            kind="external",
        ),
        tool_name="design.test",
    )
    with pytest.raises(ValidationError, match="外部接入"):
        await module.design_ai(external, {"design_id": DESIGN_ID, "instruction": "改一下"})
    assert seen == []


async def test_design_ai_defers_when_human_locked(upstream) -> None:
    holder, _ = upstream

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design()})
        if request.url.path == "/api/ask":
            return httpx.Response(
                200,
                json={"ok": True, "ops": [{"op": "set", "target": "t1", "set": {"text": "x"}}]},
            )
        return httpx.Response(
            200,
            json={
                "ok": True,
                "commit_id": "c2",
                "version": 2,
                "applied": 0,
                "skipped": [],
                "deferred": [{"index": 0, "target": "t1"}],
                "touched": [],
                "rebased": False,
                "design": _design(),
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError) as excinfo:
        await module.design_ai(ctx, {"design_id": DESIGN_ID, "instruction": "改标题"})
    assert excinfo.value.details["deferred"] == [{"index": 0, "target": "t1"}]


async def test_design_ai_validates_selected_ids(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="不在该画板"):
        await module.design_ai(
            ctx,
            {
                "design_id": DESIGN_ID,
                "instruction": "改标题",
                "selected_ids": ["t1", "ghost"],
            },
        )
    with pytest.raises(ValidationError, match="必须传 selected_ids"):
        await module.design_ai(
            ctx, {"design_id": DESIGN_ID, "instruction": "改标题", "mode": "node"}
        )


async def test_design_ai_missing_upstream_ops_maps_to_validation(upstream) -> None:
    holder, seen = upstream
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design()})
        assert request.method == "POST"
        calls["n"] += 1
        return httpx.Response(502, json={"ok": False, "error": "AI 连续两次没有给出可应用的修改"})

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    # 502 是「AI 没产出」的可重试失败，翻译成 ai_step_failed 而不是服务不可用
    with pytest.raises(AIOSError, match="AI 连续两次") as excinfo:
        await module.design_ai(ctx, {"design_id": DESIGN_ID, "instruction": "随便改"})
    assert excinfo.value.code == "ai_step_failed"
    assert calls["n"] == 1


async def test_design_ai_rejects_unknown_params(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError):
        await module.design_ai(ctx, {"design_id": DESIGN_ID, "instruction": "x", "bogus": 1})


async def test_upstream_error_exposes_status_and_code(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(
        502,
        json={
            "ok": False,
            "error": "模型服务暂时不可用（HTTP 400）：订阅过期",
            "upstream_status": 400,
            "upstream_code": "InvalidSubscription",
        },
    )
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(AIOSError) as excinfo:
        await module.design_get(ctx, {"design_id": DESIGN_ID})
    assert excinfo.value.details["upstream_status"] == 400
    assert excinfo.value.details["upstream_code"] == "InvalidSubscription"


async def test_design_ai_accepts_long_instruction(upstream) -> None:
    holder, _ = upstream
    committed = _design(version=3)

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design()})
        if request.url.path == "/api/ask":
            return httpx.Response(
                200, json={"ok": True, "ops": [{"op": "set", "target": "t1", "set": {"x": 1}}]}
            )
        return httpx.Response(
            200,
            json={
                "ok": True,
                "applied": 1,
                "skipped": [],
                "deferred": [],
                "touched": ["t1"],
                "rebased": False,
                "design": committed,
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    result = await module.design_ai(
        ctx, {"design_id": DESIGN_ID, "instruction": "把标题左移。" + "补充说明。" * 150}
    )
    assert result["changed"] is True


async def test_design_ai_rejects_instruction_over_2000(upstream) -> None:
    holder, seen = upstream
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="2000"):
        await module.design_ai(ctx, {"design_id": DESIGN_ID, "instruction": "x" * 2001})
    assert seen == []


async def test_design_patch_batches_style_changes(upstream) -> None:
    holder, seen = upstream
    committed = _design(version=3)
    committed["screens"][0]["nodes"][1]["h"] = 96
    committed["screens"][0]["nodes"][1]["radius"] = 16

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design(version=2)})
        return httpx.Response(
            200,
            json={
                "ok": True,
                "applied": 1,
                "skipped": [],
                "deferred": [],
                "touched": ["card-1"],
                "rebased": False,
                "design": committed,
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    result = await module.design_patch(
        ctx,
        {
            "design_id": DESIGN_ID,
            "changes": [{"id": "card-1", "set": {"h": 96, "radius": 16}}],
        },
    )
    assert result["applied"] == 1
    assert result["version"] == 3
    assert result["nodes"][0]["id"] == "card-1"
    commit = json.loads(seen[1].content)
    assert commit["ops"] == [{"op": "set", "target": "card-1", "set": {"h": 96, "radius": 16}}]
    assert commit["screen"] == "screen-1"
    assert commit["base_version"] == 2
    assert (seen[1].method, seen[1].url.path) == ("POST", f"/api/designs/{DESIGN_ID}/commits")


async def test_design_patch_reports_skipped_when_nothing_applied(upstream) -> None:
    holder, _ = upstream
    skipped = [{"index": 0, "reason": "missing-target", "target": "ghost"}]

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design()})
        return httpx.Response(
            200,
            json={
                "ok": True,
                "applied": 0,
                "skipped": skipped,
                "deferred": [],
                "touched": [],
                "rebased": False,
                "design": _design(),
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError) as excinfo:
        await module.design_patch(
            ctx, {"design_id": DESIGN_ID, "changes": [{"id": "ghost", "set": {"x": 1}}]}
        )
    assert excinfo.value.details["skipped"] == skipped


async def test_design_patch_requires_changes_or_ops(upstream) -> None:
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="至少给一个"):
        await module.design_patch(ctx, {"design_id": DESIGN_ID})


def test_layout_patches_align_stack_and_equal_size() -> None:
    aligned = module._layout_patches(
        "align-left", [(10, 0, 50, 20), (30, 40, 60, 20)], 390, 844, 12, "selection", None
    )
    assert aligned == [{}, {"x": 10.0}]
    stacked = module._layout_patches(
        "stack-v", [(0, 100, 50, 40), (20, 300, 50, 40)], 390, 844, 12, "selection", None
    )
    assert stacked == [{}, {"y": 152.0}]
    equal = module._layout_patches(
        "equal-height", [(0, 0, 50, 20), (0, 0, 50, 60)], 390, 844, 12, "selection", None
    )
    assert equal == [{"h": 60.0}, {}]


async def test_design_layout_commits_same_parent_ops(upstream) -> None:
    holder, seen = upstream
    committed = _design(version=5)
    committed["screens"][0]["nodes"][1]["y"] = 102

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, json={"ok": True, "design": _design(version=4)})
        return httpx.Response(
            200,
            json={
                "ok": True,
                "applied": 1,
                "skipped": [],
                "deferred": [],
                "touched": ["card-1"],
                "rebased": False,
                "design": committed,
            },
        )

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    result = await module.design_layout(
        ctx,
        {
            "design_id": DESIGN_ID,
            "node_ids": ["t1", "card-1"],
            "action": "stack-v",
            "gap": 12,
        },
    )
    assert result["applied"] == 1
    assert result["screen_index"] == 0
    commit = json.loads(seen[1].content)
    assert commit["ops"] == [{"op": "set", "target": "card-1", "set": {"y": 102.0}}]
    assert commit["base_version"] == 4


async def test_design_layout_rejects_cross_parent_nodes(upstream) -> None:
    holder, _ = upstream
    holder["handler"] = lambda request: httpx.Response(200, json={"ok": True, "design": _design()})
    ctx, _ = _ctx(run_id=None)
    with pytest.raises(ValidationError, match="同一父级"):
        await module.design_layout(
            ctx,
            {"design_id": DESIGN_ID, "node_ids": ["t1", "t2"], "action": "align-left"},
        )


async def test_design_revisions_lists_history(upstream) -> None:
    holder, seen = upstream
    revisions = [{"version": 2, "at": "2026-10-27T00:00:00Z", "note": "手动保存"}]
    holder["handler"] = lambda request: httpx.Response(
        200, json={"ok": True, "revisions": revisions}
    )
    ctx, _ = _ctx(run_id=None)
    result = await module.design_revisions(ctx, {"design_id": DESIGN_ID})
    assert result["count"] == 1
    assert result["revisions"] == revisions
    assert seen[0].url.path == f"/api/designs/{DESIGN_ID}/revisions"


async def test_design_revert_posts_version(upstream) -> None:
    holder, seen = upstream
    restored = _design(version=9)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        return httpx.Response(200, json={"ok": True, "design": restored})

    holder["handler"] = handler
    ctx, _ = _ctx(run_id=None)
    result = await module.design_revert(
        ctx, {"design_id": DESIGN_ID, "version": 2, "base_version": 8}
    )
    assert result["reverted"] is True
    assert result["version"] == 9
    assert seen[0].url.path == f"/api/designs/{DESIGN_ID}/revert"
    assert json.loads(seen[0].content) == {"version": 2, "base_version": 8}
    with pytest.raises(ValidationError):
        await module.design_revert(ctx, {"design_id": DESIGN_ID, "version": 0})
