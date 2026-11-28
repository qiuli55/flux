"""PR #42 遗留回归：其余裸 `redact` 路径接入 `FLUX_REDACTION_LITERALS`。

验收报告第 5 节第 3 项点名了 MCP 工具、Context 打包、知识索引、工作记忆与 DSH
沉淀仍在用裸 `redact(...)`：内置规则只认高熵 / 带前缀密钥，配置里的无前缀短字面量
会以明文进入审批展示、步骤摘要、上下文、知识库与长期记忆。这里用虚构字面量逐条
锁定：装配层把 `Settings.redaction_literal_list` 注入后，这些路径都应出现
`[REDACTED:key]` 且不再包含明文。

所有测试值均为明显虚构的 `CUSTOM_LITERAL_SMOKE_XYZ`，不含任何真实凭据。
"""

from __future__ import annotations

import asyncio
import importlib
import json
import logging
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from flux.config import Settings
from flux.container import Container
from flux.core.event.bus import Events
from flux.core.knowledge.indexer import BRAIN, CONVERSATION, DOC
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.context_packager import (
    APPLY_ERROR_CONTEXT_CHARS,
    RUN_ERROR_CONTEXT_CHARS,
    package_context,
)
from flux.core.mcp.tools.base import ToolContext
from flux.core.mcp.tools.read_tools import context_steps
from flux.core.task_engine.dsh_bridge import SEDIMENT_ERROR_CHARS
from flux.core.task_engine.repository import MessageDraft
from flux.core.task_engine.work_memory import MEMORY_ERROR_CHARS, build_memory_card
from flux.enums import (
    BrainSection,
    Capability,
    DshRunStatus,
    StepKind,
    StepStatus,
    TaskStatus,
    VirtualChangeStatus,
)
from flux.main import create_app
from flux.models.knowledge import KnowledgeChunk

LITERAL = "CUSTOM_LITERAL_SMOKE_XYZ"
PLACEHOLDER = "[REDACTED:key]"

#: 包 `flux.core.mcp.tools` 导出了同名函数，必须按模块路径取模块对象才能 monkeypatch
terminal_tools = importlib.import_module("flux.core.mcp.tools.terminal_tools")


@pytest.fixture()
def literal_settings(settings: Settings, tmp_path: Path) -> Settings:
    """开启可控字面量清单，并给知识库一个临时 docs 目录。"""
    docs = tmp_path / "literal-docs"
    docs.mkdir()
    return settings.model_copy(
        update={
            "redaction_literals": LITERAL,
            "knowledge_docs_dir": str(docs),
        }
    )


def _identity(*, run_id: str | None = None, mode: str = "normal") -> AgentIdentity:
    return AgentIdentity(
        agent_id=str(uuid.uuid4()),
        token_id="test-token",
        scopes=frozenset({Capability.TERMINAL_EXECUTE, Capability.FILE_READ}),
        run_id=run_id,
        permission_mode=mode,
    )


# --- MCP：terminal.execute 审批展示 / 日志 ---


async def test_terminal_approval_display_and_log_redact_configured_literal(
    literal_settings: Settings,
    db_schema: None,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """审批卡里的命令与免审日志都要打码，不能把明文密钥写给用户或写进日志。"""
    container = Container(literal_settings)
    try:
        captured: dict[str, object] = {}

        async def fake_task_uuid(_ctx: ToolContext) -> uuid.UUID:
            return uuid.uuid4()

        async def fake_raise_approval(_container: Container, **kwargs: object) -> dict[str, str]:
            captured.update(kwargs)
            return {"id": "decision-1"}

        async def fake_wait_for_approval(*_args: object, **_kwargs: object) -> str:
            return "allowed"

        monkeypatch.setattr(terminal_tools, "_task_uuid", fake_task_uuid)
        monkeypatch.setattr(terminal_tools, "raise_approval", fake_raise_approval)
        monkeypatch.setattr(terminal_tools, "wait_for_approval", fake_wait_for_approval)

        command = f"rm -rf {LITERAL}"
        ctx = ToolContext(
            container=container,
            identity=_identity(run_id=uuid.uuid4().hex),
            tool_name="terminal.execute",
        )
        await terminal_tools._approve_if_needed(ctx, command)

        detail = str(captured.get("detail") or "")
        meta = captured.get("meta") or {}
        meta_command = str(meta.get("command") or "")  # type: ignore[union-attr]
        assert LITERAL not in detail and PLACEHOLDER in detail
        assert LITERAL not in meta_command and PLACEHOLDER in meta_command

        # 无人值守分支只记日志，也不能把明文写进日志
        caplog.set_level(logging.WARNING)
        unattended = ToolContext(
            container=container,
            identity=_identity(mode="unattended"),
            tool_name="terminal.execute",
        )
        await terminal_tools._approve_if_needed(unattended, command)
        assert LITERAL not in caplog.text
        assert PLACEHOLDER in caplog.text
    finally:
        await container.dispose()


# --- MCP：context_steps 摘要 ---


async def test_context_steps_summary_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """历史 step 的 result_head 回给 Agent 前，也要按配置清单兜底打码。"""
    container = Container(literal_settings)
    try:
        run_id = uuid.uuid4().hex
        await container.context_steps.append(
            run_id=run_id,
            turn_id=f"turn-{run_id}",
            kind=StepKind.COMMAND,
            status=StepStatus.OK,
            tool="bash",
            result_head=f"echo {LITERAL}",
        )
        ctx = ToolContext(
            container=container,
            identity=_identity(run_id=run_id),
            tool_name="context.steps",
        )
        result = await context_steps(ctx, {})
        summary = result["steps"][0]["summary"]
        assert LITERAL not in summary
        assert PLACEHOLDER in summary
    finally:
        await container.dispose()


# --- Context 打包：最近 Run 错误 ---


async def test_context_packager_recent_run_error_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """上一轮 Run 的 error 会回灌下一轮上下文，必须同样按配置清单打码。"""
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="修复登录",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        run_id = uuid.uuid4().hex
        await container.run_repo.create(
            run_id=run_id,
            session_id="s",
            instruction="修复登录",
            status=DshRunStatus.RUNNING,
            task_id=task.id,
        )
        await container.run_repo.set_status(
            run_id,
            DshRunStatus.FAILED,
            error=f"网关拒绝：{LITERAL}",
            finish_reason="error",
            finished=True,
        )

        pack = await package_context(container, task_id=str(task.id))

        assert "最近 Run" in pack["content"]
        assert LITERAL not in pack["content"]
        assert PLACEHOLDER in pack["content"]
    finally:
        await container.dispose()


# --- 知识索引：对话 / docs / 项目记忆 ---


async def test_knowledge_indexer_redacts_configured_literal_before_storage(
    literal_settings: Settings, db_schema: None
) -> None:
    """三类来源入库前都要按配置清单清洗，检索侧不能反查出明文。"""
    docs = Path(literal_settings.knowledge_docs_dir)
    (docs / "secret.md").write_text(f"# 密钥备忘\n\n{LITERAL}\n", encoding="utf-8")
    container = Container(literal_settings)
    try:
        assert await container.knowledge_indexer.index_docs() == 1

        task_id = uuid.uuid4()
        await container.task_repo.create(
            task_id=task_id,
            description="核对密钥",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        await container.task_repo.add_messages(
            task_id, [MessageDraft(role="user", content=f"口令是 {LITERAL}")]
        )
        assert await container.knowledge_indexer.index_conversations() == 1

        project = await container.brain.create_project(name="字面量项目")
        await container.brain.write(
            project.id,
            section=BrainSection.DECISIONS,
            content=f"统一口令 {LITERAL}",
        )
        assert await container.knowledge_indexer.index_brain() == 1

        async with container.session_factory() as session:  # type: ignore[attr-defined]
            chunks = list(await session.scalars(select(KnowledgeChunk)))

        assert {chunk.source_type for chunk in chunks} == {DOC, CONVERSATION, BRAIN}
        assert all(LITERAL not in chunk.content for chunk in chunks)
        assert PLACEHOLDER in "\n".join(chunk.content for chunk in chunks)
    finally:
        await container.dispose()


# --- 工作记忆卡 / DSH 沉淀 ---


def test_memory_card_redacts_configured_literal_in_failure_reason() -> None:
    """显式传入清单时失败原因打码；默认空清单保持旧单测的原文行为。"""
    task = SimpleNamespace(id=uuid.uuid4(), description="修复登录", confirmation=None)
    run = SimpleNamespace(
        id="run-1",
        instruction="## 本轮任务\n修复登录",
        status="failed",
        finish_reason="error",
        error=f"provider quota exhausted: {LITERAL}",
    )

    card = build_memory_card(task=task, run=run, status="failed", literals=(LITERAL,))
    assert LITERAL not in card
    assert PLACEHOLDER in card

    legacy = build_memory_card(task=task, run=run, status="failed")
    assert LITERAL in legacy


async def test_task_bridge_literals_reach_sediment_and_memory_card(
    literal_settings: Settings, db_schema: None
) -> None:
    """Container 装配的 TaskRunBridge 要带清单：Brain 沉淀与工作记忆卡都不能留明文。"""
    container = Container(literal_settings)
    try:
        project = await container.brain.create_project(name="字面量沉淀")
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="修复登录",
            priority=0,
            project_id=project.id,
            agent_id=None,
        )
        run_id = "run-literal-1"
        await container.run_repo.create(
            run_id=run_id,
            session_id="s",
            instruction="修复登录",
            status=DshRunStatus.RUNNING,
            task_id=task.id,
        )
        await container.run_repo.set_status(
            run_id,
            DshRunStatus.FAILED,
            error=f"Run 失败：{LITERAL}",
            finish_reason="error",
            finished=True,
        )

        await container.task_runs._sediment(
            task,
            status=TaskStatus.FAILED,
            run_id=run_id,
            body={"error": f"沉淀错误：{LITERAL}"},
        )
        notes = (await container.brain.sections(project.id))[BrainSection.AGENT_NOTES.value]
        assert len(notes) == 1
        note = str(notes[0]["content"])
        assert LITERAL not in note and PLACEHOLDER in note

        await container.task_runs._write_memory_card(
            task, run_id, status=TaskStatus.FAILED, body={}
        )
        card = await container.task_memory.latest(task.id)
        assert card is not None
        assert LITERAL not in card.content
        assert PLACEHOLDER in card.content
    finally:
        await container.dispose()


# --- 推流：Container 端到端装配 ---


async def test_container_assembled_stream_redacts_configured_literal_everywhere(
    literal_settings: Settings, db_schema: None
) -> None:
    """经 `Container(...)` 装配后的推流：tool 的 detail/output/title 与 thinking/text 全打码。

    既有单测只在 `TaskStreamRelay(hub, literals=...)` 构造函数上直接注入清单；这里走
    Container 端到端，锁定 `FLUX_REDACTION_LITERALS` → 容器装配 → 推流字段的完整链路。
    每个事件用独立的 run / 任务流，避免读到前一条事件。
    """
    container = Container(literal_settings)
    try:

        async def take(event_type: str, **fields: object) -> dict:
            task_id = f"task-{uuid.uuid4().hex}"
            run_id = f"run-{uuid.uuid4().hex}"
            container.task_stream.bind_run(run_id, task_id)
            stream = container.task_stream.stream(task_id)
            await container.bus.publish(
                Events.DSH_EVENT,
                {"run_id": run_id, "event_type": event_type, **fields},
            )
            event = await anext(stream)
            await stream.aclose()
            assert event is not None
            return event.data

        tool = await take(
            "tool_result",
            name="bash",
            detail=f"echo {LITERAL}",
            output=f"stdout: {LITERAL}",
            title=f"title {LITERAL}",
            status="completed",
            call_id="call-e2e",
        )
        assert LITERAL not in json.dumps(tool, ensure_ascii=False)
        assert PLACEHOLDER in tool["detail"]
        assert PLACEHOLDER in tool["output"]
        assert PLACEHOLDER in tool["title"]

        thinking = await take("thinking", text=f"思考 {LITERAL}")
        assert LITERAL not in thinking["text"]
        assert PLACEHOLDER in thinking["text"]

        message = await take("message", text=f"正文 {LITERAL}")
        assert LITERAL not in message["text"]
        assert PLACEHOLDER in message["text"]
    finally:
        await container.dispose()


# --- 第四批：claude_account 统一打码 ---


def test_claude_account_settle_redacts_literal_and_token_via_container(
    literal_settings: Settings, db_schema: None
) -> None:
    """经 Container 装配的 ClaudeAccountService：失败摘要走统一 redact + 配置清单。"""
    container = Container(literal_settings)
    try:
        service = container.claude_account
        session = SimpleNamespace(
            login_id="login-literal",
            status="starting",
            token_captured=False,
            output=f"setup failed: {LITERAL} and also padding",
            message="",
        )
        service._settle(session, 1)  # noqa: SLF001 - 验证明文不进入用户可见摘要
        assert LITERAL not in session.message
        assert PLACEHOLDER in session.message

        # 官方订阅令牌形态也必须继续被挡住（统一规则改为 [REDACTED:key]）
        token_session = SimpleNamespace(
            login_id="login-token",
            status="starting",
            token_captured=False,
            output="token: sk-ant-oat01-abcdefghij",
            message="",
        )
        service._settle(token_session, 1)  # noqa: SLF001
        assert "sk-ant" not in token_session.message
        assert PLACEHOLDER in token_session.message
    finally:
        asyncio.run(container.dispose())


# --- 第四批：任务流 / HTTP 投影打码 ---


def test_task_message_http_and_stream_redact_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """POST /messages 的推流与 HTTP 响应投影都不得带明文；落库对象保持原文。"""
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        created = client.post("/api/v1/tasks", json={"description": "修复登录"}).json()["data"]
        response = client.post(
            f"/api/v1/tasks/{created['id']}/messages",
            json={"content": f"我的口令是 {LITERAL}"},
        )
        assert response.status_code == 200, response.text
        body = json.dumps(response.json(), ensure_ascii=False)
        assert LITERAL not in body
        assert PLACEHOLDER in body

        events = container.task_stream.poll(created["id"])
        streamed = json.dumps([event.to_dict() for event in events], ensure_ascii=False)
        assert LITERAL not in streamed
        assert PLACEHOLDER in streamed


def test_task_assistant_error_event_redacts_configured_literal(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """assistant_error 必须先对完整异常打码再截断，字面量不得进 SSE。"""
    from flux.core.task_engine.assistant import TaskAssistant

    async def boom(self: TaskAssistant, **_kwargs: object) -> None:
        raise RuntimeError(f"provider rejected {LITERAL}")

    monkeypatch.setattr(TaskAssistant, "respond", boom)
    app = create_app(literal_settings)
    with TestClient(app, raise_server_exceptions=False) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        created = client.post("/api/v1/tasks", json={"description": "修复登录"}).json()["data"]
        response = client.post(f"/api/v1/tasks/{created['id']}/messages", json={"content": "hi"})
        assert response.status_code == 500
        events = container.task_stream.poll(created["id"])
        error = next(event for event in events if event.type == "assistant_error")
        assert LITERAL not in error.data["error"]
        assert PLACEHOLDER in error.data["error"]


# --- 第五批：GET 消息列表 / 同类 HTTP 投影打码 ---


def test_task_messages_list_http_projection_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """GET /tasks/{id}/messages 是 HTTP 投影：响应不含明文，落库对象仍是原文。"""
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        created = client.post("/api/v1/tasks", json={"description": "修复登录"}).json()["data"]
        content = f"我的口令是 {LITERAL}"
        posted = client.post(f"/api/v1/tasks/{created['id']}/messages", json={"content": content})
        assert posted.status_code == 200, posted.text

        listed = client.get(f"/api/v1/tasks/{created['id']}/messages")
        assert listed.status_code == 200, listed.text
        body = json.dumps(listed.json(), ensure_ascii=False)
        assert LITERAL not in body
        assert PLACEHOLDER in body

        async def _stored_contents() -> list[str]:
            messages, _ = await container.task_repo.list_messages(
                uuid.UUID(created["id"]), limit=50
            )
            return [message.content for message in messages]

        # 存储语义未变：拦截只发生在 HTTP 投影上。
        assert content in asyncio.run(_stored_contents())


def test_task_confirmation_response_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """PUT /confirmation 的响应同样是 HTTP 消息投影，不能把原文带出去。"""
    app = create_app(literal_settings)
    with TestClient(app) as client:
        created = client.post("/api/v1/tasks", json={"description": "修复登录"}).json()["data"]
        items = [
            {"label": label, "value": f"结论 {LITERAL}" if label == "目标" else "待确认"}
            for label in ("目标", "功能范围", "技术方案", "修改范围", "风险", "需人工审核的环节")
        ]
        response = client.put(f"/api/v1/tasks/{created['id']}/confirmation", json={"items": items})
        assert response.status_code == 200, response.text
        data = response.json()["data"]
        # 消息投影与任务对象投影都不能带明文：嵌套的 task.confirmation 同样是下发副本。
        message = data["message"]
        body = json.dumps(message, ensure_ascii=False)
        assert LITERAL not in body
        assert PLACEHOLDER in body
        task_body = json.dumps(data["task"], ensure_ascii=False)
        assert LITERAL not in task_body
        assert PLACEHOLDER in task_body
        confirmation_body = json.dumps(data["task"]["confirmation"], ensure_ascii=False)
        assert LITERAL not in confirmation_body
        assert PLACEHOLDER in confirmation_body

        # 存储语义未变：任务上的确认卡仍是原文，只有 HTTP 投影被打码。
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        stored = asyncio.run(container.task_repo.get(uuid.UUID(created["id"])))
        stored_body = json.dumps(stored.confirmation, ensure_ascii=False)
        assert LITERAL in stored_body


# --- 第五批：assistant_delta 跨帧片段 ---


def _drive_streamed_reply(
    literal_settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
    chunks: list[str],
) -> tuple[list[str], list[dict[str, object]], dict[str, object]]:
    """经真实 POST 路径驱动一轮流式回复，返回 (增量帧文本, 全部事件, POST 响应体)。"""
    from flux.core.task_engine.assistant import AssistantTurn, TaskAssistant

    reply = "".join(chunks)

    async def fake_respond(self: TaskAssistant, *, on_delta: object = None, **_kwargs: object):
        assert callable(on_delta)
        for chunk in chunks:
            await on_delta(chunk)  # type: ignore[misc]
        return AssistantTurn(reply=reply, provider="fake", model="fake-model")

    monkeypatch.setattr(TaskAssistant, "respond", fake_respond)
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        created = client.post("/api/v1/tasks", json={"description": "修复登录"}).json()["data"]
        response = client.post(f"/api/v1/tasks/{created['id']}/messages", json={"content": "hi"})
        assert response.status_code == 200, response.text
        events = [event.to_dict() for event in container.task_stream.poll(created["id"])]
    deltas = [
        str(event["data"].get("text") or "")
        for event in events
        if event["type"] == "assistant_delta"
    ]
    return deltas, events, response.json()


def test_task_stream_delta_redacts_literal_split_across_frames(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """字面量被拆到相邻两帧时，任一帧与拼接结果都不得出现完整明文或截断片段。"""
    head, tail = LITERAL[:11], LITERAL[11:]
    chunks = ["好的，口令是 ", head, tail, "，请妥善保存。"]
    deltas, events, body = _drive_streamed_reply(literal_settings, monkeypatch, chunks)

    # 跨帧片段：被切开的前后两半、以及历史探针用过的中段。
    fragments = (head, tail, LITERAL[8:])
    for frame in deltas:
        assert LITERAL not in frame
        for fragment in fragments:
            assert fragment not in frame
    joined = "".join(deltas)
    assert LITERAL not in joined
    for fragment in fragments:
        assert fragment not in joined
    assert PLACEHOLDER in joined

    # 最终 assistant_message / HTTP 响应投影仍整体打码。
    for payload in (json.dumps(events, ensure_ascii=False), json.dumps(body, ensure_ascii=False)):
        assert LITERAL not in payload
        assert PLACEHOLDER in payload


def test_task_stream_delta_keeps_streaming_plain_reply(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """对照：普通短句不该被攒成一整段，仍按原帧逐段流出。"""
    chunks = ["这是第一段。", "这是第二段。", "这是第三段。"]
    deltas, _events, _body = _drive_streamed_reply(literal_settings, monkeypatch, chunks)
    assert deltas == chunks


def test_group_message_projection_redacts_configured_literal(
    literal_settings: Settings,
) -> None:
    """群聊消息 HTTP 投影与任务消息同口径打码（路由已下线，直接验证投影函数）。"""
    from flux.api.v1.groups import _message_dict

    message = SimpleNamespace(
        to_dict=lambda: {
            "content": f"口令 {LITERAL}",
            "author_name": f"Bot-{LITERAL}",
            "kind": "text",
            "payload": {"tool": {"output": f"stdout: {LITERAL}"}},
        }
    )
    data = _message_dict(SimpleNamespace(settings=literal_settings), message)
    assert LITERAL not in data["content"]
    assert PLACEHOLDER in data["content"]
    # 整份投影都打码：author_name 与嵌套 payload 同样是下发副本。
    assert LITERAL not in data["author_name"]
    assert PLACEHOLDER in data["author_name"]
    assert LITERAL not in json.dumps(data["payload"], ensure_ascii=False)
    assert PLACEHOLDER in json.dumps(data["payload"], ensure_ascii=False)


# --- 第四批：先打码再截断（字面量跨边界） ---


def _split_window_error(limit: int, cut_into: int = 8) -> tuple[str, str]:
    """构造让完整字面量跨过截断边界的错误文本，返回 (error, 会漏出的片段)。"""
    fragment = LITERAL[cut_into:]
    prefix_len = limit - (len(LITERAL) - cut_into)
    return "P" * prefix_len + LITERAL + "TAIL", fragment


def test_memory_card_redacts_literal_crossing_truncation_boundary() -> None:
    """失败原因先打码再取尾：跨边界的片段也不能漏出。"""
    error, fragment = _split_window_error(MEMORY_ERROR_CHARS)
    task = SimpleNamespace(id=uuid.uuid4(), description="修复登录", confirmation=None)
    run = SimpleNamespace(
        id="run-1",
        instruction="## 本轮任务\n修复登录",
        status="failed",
        finish_reason="error",
        error=error,
    )

    card = build_memory_card(task=task, run=run, status="failed", literals=(LITERAL,))
    assert LITERAL not in card
    assert fragment not in card
    assert PLACEHOLDER in card


def test_memory_card_redacts_goal_and_final_response() -> None:
    """本轮目标与上一轮最终答复同样要打码，不能只打失败原因。"""
    task = SimpleNamespace(id=uuid.uuid4(), description=f"目标 {LITERAL}", confirmation=None)
    card = build_memory_card(
        task=task,
        run=None,
        instruction=f"## 本轮任务\n{LITERAL}",
        final_response=f"答复 {LITERAL}",
        status="completed",
        literals=(LITERAL,),
    )
    assert LITERAL not in card
    assert PLACEHOLDER in card


async def test_dsh_sediment_redacts_literal_crossing_truncation_boundary(
    literal_settings: Settings, db_schema: None
) -> None:
    """DSH 沉淀的任务描述与错误都要先打码再截断。"""
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=f"任务 {LITERAL}",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        error, fragment = _split_window_error(SEDIMENT_ERROR_CHARS)
        text = await container.task_runs._sediment_text(  # noqa: SLF001
            task, status=TaskStatus.FAILED, run_id="run-boundary", body={"error": error}
        )
        assert LITERAL not in text
        assert fragment not in text
        assert PLACEHOLDER in text
    finally:
        await container.dispose()


async def test_context_packager_run_error_crossing_truncation_boundary(
    literal_settings: Settings, db_schema: None
) -> None:
    """近期 Run 错误先对完整原文打码再取尾，跨边界片段不漏出。"""
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="修复登录",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        run_id = uuid.uuid4().hex
        await container.run_repo.create(
            run_id=run_id,
            session_id="s",
            instruction="修复登录",
            status=DshRunStatus.RUNNING,
            task_id=task.id,
        )
        error, fragment = _split_window_error(RUN_ERROR_CONTEXT_CHARS)
        await container.run_repo.set_status(
            run_id,
            DshRunStatus.FAILED,
            error=error,
            finish_reason="error",
            finished=True,
        )

        pack = await package_context(container, task_id=str(task.id))
        assert LITERAL not in pack["content"]
        assert fragment not in pack["content"]
        assert PLACEHOLDER in pack["content"]
    finally:
        await container.dispose()


async def test_context_packager_redacts_task_description_and_proposal_fields(
    literal_settings: Settings, db_schema: None
) -> None:
    """任务描述、提案说明与 apply_error 都要打码，且 apply_error 先打码再截断。"""
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=f"任务描述 {LITERAL}",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        change = await container.workspace.propose(
            file_path="todo_service/store.py",
            original_content="def list_todos():\n    return None\n",
            proposed_content="def list_todos():\n    return []\n",
            task_id=task.id,
            agent_source="developer-agent",
            summary=f"改动说明 {LITERAL}",
        )
        error, fragment = _split_window_error(APPLY_ERROR_CONTEXT_CHARS)
        await container.proposal_repo.set_apply_result(
            change.id,
            status=VirtualChangeStatus.FAILED,
            apply_error=error,
        )

        pack = await package_context(container, task_id=str(task.id))
        content = pack["content"]
        assert LITERAL not in content
        assert fragment not in content
        assert PLACEHOLDER in content
        # 任务描述与提案说明都真的走了打码（各自贡献一个占位符）
        assert content.count(PLACEHOLDER) >= 2
    finally:
        await container.dispose()


# --- 第四批：知识索引标题 / HTTP handoff ---


async def test_knowledge_conversation_title_is_redacted_before_storage(
    literal_settings: Settings, db_schema: None
) -> None:
    """对话标题也会入库并可被检索：入库前要按清单打码。"""
    container = Container(literal_settings)
    try:
        task_id = uuid.uuid4()
        await container.task_repo.create(
            task_id=task_id,
            description=f"标题 {LITERAL}",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        await container.task_repo.add_messages(
            task_id, [MessageDraft(role="user", content="正文不敏感")]
        )
        assert await container.knowledge_indexer.index_conversations() == 1
        documents = await container.knowledge_repo.list_documents(CONVERSATION)
        assert documents and documents[0].title
        assert LITERAL not in documents[0].title
        assert PLACEHOLDER in documents[0].title
    finally:
        await container.dispose()


def test_http_handoff_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """HTTP handoff 不再把 task_messages 原文交给摘要并回传。"""
    seed = Container(literal_settings)
    task_id = uuid.uuid4()
    try:
        asyncio.run(
            seed.task_repo.create(
                task_id=task_id,
                description=f"任务描述 {LITERAL}",
                priority=0,
                project_id=None,
                agent_id=None,
            )
        )
        asyncio.run(
            seed.task_repo.add_messages(
                task_id,
                [
                    MessageDraft(role="user", content=f"用户消息 {LITERAL}"),
                    MessageDraft(role="assistant", content="助手回复"),
                ],
            )
        )
    finally:
        asyncio.run(seed.dispose())

    app = create_app(literal_settings)
    with TestClient(app) as client:
        response = client.post(f"/api/v1/tasks/{task_id}/handoff")
        assert response.status_code == 200, response.text
        body = json.dumps(response.json(), ensure_ascii=False)
        assert LITERAL not in body
        assert LITERAL[8:] not in body  # 截断片段也不得漏出
        assert PLACEHOLDER in body


# --- 第六批：任务对象投影 / 审批与计划推流 / 中途截断 flush ---


def test_task_object_http_projection_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """创建 / 详情 / 列表返回的 task 对象都是投影：description 不得带明文，库里仍是原文。"""
    description = f"修复登录 {LITERAL}"
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        response = client.post("/api/v1/tasks", json={"description": description})
        assert response.status_code == 200, response.text
        created = response.json()["data"]
        assert LITERAL not in json.dumps(created, ensure_ascii=False)
        assert PLACEHOLDER in created["description"]

        detail = client.get(f"/api/v1/tasks/{created['id']}")
        assert detail.status_code == 200, detail.text
        detail_body = json.dumps(detail.json(), ensure_ascii=False)
        assert LITERAL not in detail_body
        assert PLACEHOLDER in detail_body

        listing = client.get("/api/v1/tasks")
        assert listing.status_code == 200, listing.text
        listing_body = json.dumps(listing.json(), ensure_ascii=False)
        assert LITERAL not in listing_body
        assert PLACEHOLDER in listing_body

        stored = asyncio.run(container.task_repo.get(uuid.UUID(created["id"])))
        assert stored.description == description


def test_task_pending_decision_projection_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """decisions / pending_decision 也是任务对象投影的一部分，问题与说明都要打码。"""
    seed = Container(literal_settings)
    task_id = uuid.uuid4()
    decision = {
        "id": "decision-literal-1",
        "question": f"选哪个方案 {LITERAL}",
        "options": [{"label": "A", "description": f"方案说明 {LITERAL}"}],
        "context": f"背景 {LITERAL}",
        "recommendation": None,
        "mode": "manual",
        "status": "pending",
        "chosen": None,
        "note": None,
        "created_at": "2026-01-01T00:00:00+00:00",
        "resolved_at": None,
    }
    try:
        asyncio.run(
            seed.task_repo.create(
                task_id=task_id,
                description="等待选择",
                priority=0,
                project_id=None,
                agent_id=None,
            )
        )
        asyncio.run(seed.task_repo.raise_decision(task_id, decision))
    finally:
        asyncio.run(seed.dispose())

    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        response = client.get(f"/api/v1/tasks/{task_id}")
        assert response.status_code == 200, response.text
        data = response.json()["data"]
        body = json.dumps(data, ensure_ascii=False)
        assert LITERAL not in body
        assert PLACEHOLDER in body
        assert data["pending_decision"] is not None
        assert LITERAL not in json.dumps(data["decisions"], ensure_ascii=False)

        stored = asyncio.run(container.task_repo.get(task_id))
        assert LITERAL in json.dumps(stored.decisions, ensure_ascii=False)


async def test_approval_and_expiry_stream_projections_redact_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """审批卡与超时结果都以消息投影推流：title / detail / decision record 不得带明文。"""
    from flux.core.task_engine.approvals import _expire, raise_approval

    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="审批投影用例",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        record = await raise_approval(
            container,
            task_id=task.id,
            run_id="run-approval-literal",
            kind="high_risk_command",
            title=f"是否允许执行 {LITERAL}",
            detail=f"命令：rm -rf {LITERAL}",
            meta={"command": f"rm -rf {LITERAL}", "rule": "rm -rf"},
        )
        await _expire(
            container,
            task_id=task.id,
            decision_id=record["id"],
            run_id="run-approval-literal",
        )

        events = container.task_stream.poll(task.id)
        streamed = json.dumps([event.to_dict() for event in events], ensure_ascii=False)
        assert LITERAL not in streamed
        assert PLACEHOLDER in streamed

        # 落库消息与决策记录仍是原文，只有推流投影被打码。
        stored = await container.task_repo.get(task.id)
        assert LITERAL in json.dumps(stored.decisions, ensure_ascii=False)
        messages, _ = await container.task_repo.list_messages(task.id)
        assert LITERAL in json.dumps([m.to_dict() for m in messages], ensure_ascii=False)
    finally:
        await container.dispose()


async def test_approval_bus_events_redact_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """EventBus 上的审批事件是订阅者投影：meta 不得带明文，库中决策记录仍是原文。"""
    from flux.core.task_engine.approvals import _expire, raise_approval

    container = Container(literal_settings)
    received: list[tuple[str, dict[str, object]]] = []

    def handler(event: str, payload: dict[str, object]) -> None:
        received.append((event, payload))

    container.bus.subscribe(Events.APPROVAL_REQUESTED, handler)
    container.bus.subscribe(Events.APPROVAL_RESOLVED, handler)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="审批总线投影用例",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        record = await raise_approval(
            container,
            task_id=task.id,
            run_id="run-approval-bus",
            kind="high_risk_command",
            title=f"是否允许执行 {LITERAL}",
            detail=f"命令：rm -rf {LITERAL}",
            meta={"command": f"rm -rf {LITERAL}", "rule": "rm -rf"},
        )
        await _expire(
            container,
            task_id=task.id,
            decision_id=record["id"],
            run_id="run-approval-bus",
        )

        assert [event for event, _payload in received] == [
            Events.APPROVAL_REQUESTED,
            Events.APPROVAL_RESOLVED,
        ]
        requested = received[0][1]
        for event, payload in received:
            body = json.dumps(payload, ensure_ascii=False)
            assert LITERAL not in body, event
        # 审批请求里的 meta.command 走统一占位，订阅者拿不到明文命令。
        requested_meta = json.dumps(requested.get("meta"), ensure_ascii=False)
        assert PLACEHOLDER in requested_meta
        assert str(requested.get("run_id")) == "run-approval-bus"

        # 内部决策记录仍是原文：打码只发生在总线投影上。
        stored = await container.task_repo.get(task.id)
        stored_body = json.dumps(stored.decisions, ensure_ascii=False)
        assert LITERAL in stored_body
        assert "rm -rf " + LITERAL in stored_body
    finally:
        await container.dispose()


async def test_supervisor_conversation_tools_redact_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """Supervisor 的对话工具把任务 / 消息交给模型前必须打码，库里仍是原文。"""
    from flux.core.mcp.tools.supervisor_tools import (
        assistant_conversation,
        assistant_conversations,
    )

    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=f"对话标题 {LITERAL}",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        await container.task_repo.add_messages(
            task.id,
            [
                MessageDraft(
                    role="assistant",
                    kind="text",
                    content=f"口令是 {LITERAL}",
                    payload={"tool": {"output": f"stdout: {LITERAL}"}},
                )
            ],
        )
        ctx = ToolContext(
            container=container,
            identity=_identity(),
            tool_name="assistant.conversation",
        )

        detail = await assistant_conversation(ctx, {"task_id": str(task.id)})
        detail_body = json.dumps(detail, ensure_ascii=False)
        assert LITERAL not in detail_body
        assert PLACEHOLDER in detail_body
        for message in detail["messages"]:
            assert LITERAL not in json.dumps(message, ensure_ascii=False)

        listing = await assistant_conversations(ctx, {"include_assistant": True})
        listing_body = json.dumps(listing, ensure_ascii=False)
        assert LITERAL not in listing_body
        assert PLACEHOLDER in listing_body

        # 存储语义未变：库里任务描述与消息正文仍是原文。
        stored = await container.task_repo.get(task.id)
        assert stored.description == f"对话标题 {LITERAL}"
        messages, _ = await container.task_repo.list_messages(task.id)
        assert messages[0].content == f"口令是 {LITERAL}"
    finally:
        await container.dispose()


async def test_plan_card_stream_projection_redacts_configured_literal(
    literal_settings: Settings,
    db_schema: None,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    """计划卡的 title / steps / markdown 会随消息投影下发，推流前必须打码。"""
    from flux.core.mcp.tools.base import ToolContext

    plan_tools = importlib.import_module("flux.core.mcp.tools.plan_tools")
    monkeypatch.setattr(plan_tools, "PLANS_DIR", tmp_path / "plans")
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="计划卡投影用例",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        run_id = uuid.uuid4().hex
        await container.run_repo.create(
            run_id=run_id,
            session_id="s",
            instruction="写计划",
            status=DshRunStatus.RUNNING,
            task_id=task.id,
        )
        ctx = ToolContext(
            container=container,
            identity=AgentIdentity(
                agent_id=str(uuid.uuid4()),
                token_id="t",
                scopes=frozenset({Capability.PLAN_WRITE}),
                run_id=run_id,
                permission_mode="plan",
            ),
            tool_name="plan.write",
        )
        await plan_tools.plan_write(
            ctx,
            {
                "title": f"计划 {LITERAL}",
                "steps": [{"title": f"步骤 {LITERAL}", "detail": f"说明 {LITERAL}"}],
                "files": [f"{LITERAL}.py"],
                "markdown": f"补充 {LITERAL}",
            },
        )

        events = container.task_stream.poll(task.id)
        streamed = json.dumps([event.to_dict() for event in events], ensure_ascii=False)
        assert LITERAL not in streamed
        assert PLACEHOLDER in streamed

        # 落库计划卡保持原文，供后续执行指令使用。
        messages, _ = await container.task_repo.list_messages(task.id)
        card = next(message for message in messages if message.kind == "plan_card")
        assert LITERAL in json.dumps(card.to_dict(), ensure_ascii=False)
    finally:
        await container.dispose()


def test_agent_switch_stream_projection_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """agent_switch 事件里的 task / message 投影同样不得带任务描述或交接卡明文。"""
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]

        def _agent(name: str) -> str:
            response = client.post(
                "/api/v1/agents", json={"name": name, "role": "developer", "runtime": "dsh"}
            )
            assert response.status_code == 200, response.text
            return str(response.json()["data"]["id"])

        agent_a = _agent(f"builder-{uuid.uuid4().hex[:8]}")
        agent_b = _agent(f"reviewer-{uuid.uuid4().hex[:8]}")
        created = client.post(
            "/api/v1/tasks",
            json={"description": f"切换 Agent {LITERAL}", "agent_id": agent_a},
        ).json()["data"]
        task_id = uuid.UUID(created["id"])

        asyncio.run(container.task_repo.set_status(task_id, TaskStatus.RUNNING))
        queued = client.post(f"/api/v1/tasks/{created['id']}/agent", json={"agent_id": agent_b})
        assert queued.status_code == 200, queued.text
        asyncio.run(container.task_repo.set_status(task_id, TaskStatus.COMPLETED))

        async def _apply_pending() -> None:
            task = await container.task_repo.get(task_id)
            await container.agent_switch.apply_pending(task, "run-switch-literal", "completed")

        asyncio.run(_apply_pending())

        events = [event.to_dict() for event in container.task_stream.poll(created["id"])]
        streamed = json.dumps(events, ensure_ascii=False)
        assert "agent_switched" in [event["type"] for event in events]
        assert LITERAL not in streamed
        assert PLACEHOLDER in streamed

        stored = asyncio.run(container.task_repo.get(task_id))
        assert LITERAL in stored.description


async def test_dsh_bridge_stream_projection_redacts_configured_literal(
    literal_settings: Settings, db_schema: None
) -> None:
    """DSH 收尾消息（含自动重试失败原因）推流前也要走统一投影打码。"""
    container = Container(literal_settings)
    try:
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description="DSH 收尾投影用例",
            priority=0,
            project_id=None,
            agent_id=None,
        )
        await container.task_runs._retry_failed_to_start(  # noqa: SLF001
            task, RuntimeError(f"启动失败 {LITERAL}")
        )
        events = container.task_stream.poll(task.id)
        streamed = json.dumps([event.to_dict() for event in events], ensure_ascii=False)
        assert LITERAL not in streamed
        assert PLACEHOLDER in streamed
        messages, _ = await container.task_repo.list_messages(task.id)
        assert LITERAL in json.dumps([m.to_dict() for m in messages], ensure_ascii=False)
    finally:
        await container.dispose()


def test_streaming_delta_abort_drops_incomplete_literal_prefix(
    literal_settings: Settings,
) -> None:
    """中途截断时，未完成的字面量前半截不能被 flush 当正文放出；正常结束仍放安全尾巴。"""
    from flux.api.v1.tasks import _StreamingDeltaRedactor

    container = Container(literal_settings)
    try:
        prefix = LITERAL[:11]
        redactor = _StreamingDeltaRedactor(container)
        assert redactor.feed(f"安全前缀 {prefix}") == "安全前缀 "
        assert redactor.flush(abort=True) == ""
        # abort 后继续 feed 也不会把之前丢弃的前缀补发出来。
        assert redactor.feed("") == ""

        normal = _StreamingDeltaRedactor(container)
        assert normal.feed("hello") == ""
        assert normal.feed(" world") == "hello "
        assert normal.flush() == "world"
    finally:
        asyncio.run(container.dispose())


def test_task_stream_abort_flush_does_not_leak_incomplete_prefix(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """真实 POST 异常路径：流到字面量前半段后中断，delta 帧与拼接文本都不得有残片。"""
    from flux.core.task_engine.assistant import TaskAssistant

    prefix = LITERAL[:11]
    fragment = LITERAL[8:]

    async def fake_respond(self: TaskAssistant, *, on_delta: object = None, **_kwargs: object):
        assert callable(on_delta)
        await on_delta(f"好的，口令是 {prefix}")  # type: ignore[misc]
        raise RuntimeError("stream interrupted")

    monkeypatch.setattr(TaskAssistant, "respond", fake_respond)
    app = create_app(literal_settings)
    with TestClient(app, raise_server_exceptions=False) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        created = client.post("/api/v1/tasks", json={"description": "中途截断"}).json()["data"]
        response = client.post(f"/api/v1/tasks/{created['id']}/messages", json={"content": "hi"})
        assert response.status_code == 500
        events = [event.to_dict() for event in container.task_stream.poll(created["id"])]

    deltas = [
        str(event["data"].get("text") or "")
        for event in events
        if event["type"] == "assistant_delta"
    ]
    joined = "".join(deltas)
    for frame in deltas:
        assert LITERAL not in frame
        assert prefix not in frame
        assert fragment not in frame
    assert LITERAL not in joined
    assert prefix not in joined
    assert fragment not in joined
    # 前半段之前的安全正文仍可正常流出，不因中断把整段回复吞掉。
    assert joined.strip() == "好的，口令是"


def test_task_stream_flush_releases_safe_deferred_tail(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """正常结束时，被暂缓的普通尾巴仍要 flush 出去（不能为了安全把回复截断）。"""
    deltas, _events, _body = _drive_streamed_reply(
        literal_settings, monkeypatch, ["hello", " world"]
    )
    assert "".join(deltas) == "hello world"


def test_streaming_delta_normal_flush_drops_incomplete_secret_prefixes(
    literal_settings: Settings,
) -> None:
    """正常结束也不能把字面量 / 令牌的未完成前缀当正文放：普通尾巴除外。"""
    from flux.api.v1.tasks import _StreamingDeltaRedactor

    container = Container(literal_settings)
    try:
        prefix = LITERAL[:11]
        literal_tail = _StreamingDeltaRedactor(container)
        assert literal_tail.feed(f"安全前缀 {prefix}") == "安全前缀 "
        assert literal_tail.flush() == ""

        # 正常 flush 也不放 sk- / JWT / Bearer / 未闭合 PEM 的未完成前缀。
        for sneak in (
            "sk-",
            "sk-proj-abc",
            "eyJhbGciOi",
            "Bearer abc123",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE",
        ):
            redactor = _StreamingDeltaRedactor(container)
            redactor.feed(sneak)
            leaked = redactor.flush()
            assert leaked == "", sneak
            for fragment in ("sk-", "sk-proj", "eyJ", "Bearer", "BEGIN RSA"):
                assert fragment not in leaked

        # 对照：普通完整短句正常结束仍应完整流出。
        normal = _StreamingDeltaRedactor(container)
        assert normal.feed("hello") == ""
        assert normal.feed(" world") == "hello "
        assert normal.flush() == "world"
    finally:
        asyncio.run(container.dispose())


def test_task_stream_normal_flush_drops_incomplete_literal_prefix(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """真实 POST 正常结束：停在字面量前半段时，delta 帧与拼接文本都不放残片。"""
    prefix = LITERAL[:11]
    deltas, _events, _body = _drive_streamed_reply(
        literal_settings, monkeypatch, [f"好的，口令是 {prefix}"]
    )
    joined = "".join(deltas)
    assert LITERAL not in joined
    assert prefix not in joined
    assert LITERAL[8:] not in joined
    for frame in deltas:
        assert prefix not in frame
        assert LITERAL[8:] not in frame
    # 前半段之前的安全正文仍完整流出。
    assert joined.strip() == "好的，口令是"


# --- 第八批：Bearer 全小写长片段 / 落库与消息投影的未完成前缀 ---

#: 全小写、无数字/大写/符号的长令牌片段（虚构，不是任何真实凭据）。
BEARER_TOKEN_SAMPLE = "abcdefghijklmnopqrstuvwxyzabcdef"


def _drive_streamed_round(
    literal_settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
    chunks: list[str],
    *,
    user_content: str = "hi",
) -> tuple[list[str], list[dict[str, object]], dict[str, object], list[object], list[dict]]:
    """经真实 POST 路径驱动一轮回复，并带回落库原文、GET 投影与推流事件。"""
    from flux.core.task_engine.assistant import AssistantTurn, TaskAssistant

    reply = "".join(chunks)

    async def fake_respond(self: TaskAssistant, *, on_delta: object = None, **_kwargs: object):
        assert callable(on_delta)
        for chunk in chunks:
            await on_delta(chunk)  # type: ignore[misc]
        return AssistantTurn(reply=reply, provider="fake", model="fake-model")

    monkeypatch.setattr(TaskAssistant, "respond", fake_respond)
    app = create_app(literal_settings)
    with TestClient(app) as client:
        container: Container = client.app.state.container  # type: ignore[attr-defined]
        task = client.post("/api/v1/tasks", json={"description": "打码边界"}).json()["data"]
        response = client.post(
            f"/api/v1/tasks/{task['id']}/messages", json={"content": user_content}
        )
        assert response.status_code == 200, response.text
        events = [event.to_dict() for event in container.task_stream.poll(task["id"])]
        listed = client.get(f"/api/v1/tasks/{task['id']}/messages")
        assert listed.status_code == 200
        rows, _ = asyncio.run(container.task_repo.list_messages(uuid.UUID(task["id"])))
    deltas = [
        str(event["data"].get("text") or "")
        for event in events
        if event["type"] == "assistant_delta"
    ]
    return deltas, events, response.json(), list(rows), listed.json()["data"]


def test_streaming_delta_flush_drops_lowercase_and_punctuated_bearer_tokens(
    literal_settings: Settings,
) -> None:
    """Bearer 大小写与尾随标点都不能绕过 flush；普通业务短词仍照常放出。"""
    from flux.api.v1.tasks import _StreamingDeltaRedactor

    token = BEARER_TOKEN_SAMPLE
    container = Container(literal_settings)
    try:
        for sneak in (
            f"Bearer {token}",
            f"bearer {token}",
            f"Bearer {token}。",
            f"bearer {token}。",
        ):
            normal = _StreamingDeltaRedactor(container)
            normal.feed(sneak)
            assert normal.flush() == "", sneak

            aborted = _StreamingDeltaRedactor(container)
            aborted.feed(sneak)
            leaked = aborted.flush(abort=True)
            assert token not in leaked, sneak

        # 对照：普通短词（含复数）不得被误杀。
        for prose in ("Bearer token", "Bearer tokens"):
            redactor = _StreamingDeltaRedactor(container)
            redactor.feed(prose)
            assert redactor.flush() == prose, prose
    finally:
        asyncio.run(container.dispose())


def test_task_stream_normal_flush_drops_lowercase_bearer_token_everywhere(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """正常结束停在 `bearer <全小写长片段>`：增量、落库、GET 与推流都不得出现该片段。"""
    token = BEARER_TOKEN_SAMPLE
    deltas, events, body, rows, listed = _drive_streamed_round(
        literal_settings, monkeypatch, [f"凭证是 bearer {token}"]
    )

    joined = "".join(deltas)
    assert token not in joined
    assert "凭证是" in joined  # 安全前缀仍正常流出
    for frame in deltas:
        assert token not in frame

    stored = json.dumps([row.to_dict() for row in rows], ensure_ascii=False)  # type: ignore[attr-defined]
    for payload in (
        stored,
        json.dumps(events, ensure_ascii=False),
        json.dumps(body, ensure_ascii=False),
        json.dumps(listed, ensure_ascii=False),
    ):
        assert token not in payload


def test_assistant_storage_and_projection_drop_incomplete_literal_prefix(
    literal_settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """正常结束停在字面量前半段：落库 assistant、GET messages 与最终推流都无残片。"""
    from flux.enums import TaskMessageRole

    prefix = LITERAL[:11]
    fragment = LITERAL[8:]
    user_text = f"用户自己的口令 {LITERAL}"
    deltas, events, body, rows, listed = _drive_streamed_round(
        literal_settings, monkeypatch, [f"好的，口令是 {prefix}"], user_content=user_text
    )

    joined = "".join(deltas)
    assert LITERAL not in joined
    assert prefix not in joined
    assert fragment not in joined
    assert "好的，口令是" in joined
    for frame in deltas:
        assert prefix not in frame
        assert fragment not in frame

    # 落库原文（不经 HTTP 投影）：assistant 无完整字面量、无前缀残片；user 原文不动。
    assistant_rows = [m for m in rows if m.role == TaskMessageRole.ASSISTANT.value]  # type: ignore[attr-defined]
    assert assistant_rows, "assistant 回复应已落库"
    stored = assistant_rows[-1]
    stored_dump = json.dumps(stored.to_dict(), ensure_ascii=False)  # type: ignore[attr-defined]
    assert LITERAL not in stored_dump
    assert prefix not in stored_dump
    assert fragment not in stored_dump
    assert "好的，口令是" in stored.content  # type: ignore[attr-defined]

    user_rows = [m for m in rows if m.role == TaskMessageRole.USER.value]  # type: ignore[attr-defined]
    assert user_rows[-1].content == user_text  # type: ignore[attr-defined]

    # HTTP GET messages：assistant 干净；user 的完整字面量仍按投影打码。
    for row in listed:
        dumped = json.dumps(row, ensure_ascii=False)
        if row["role"] == "assistant":
            assert LITERAL not in dumped
            assert prefix not in dumped
            assert fragment not in dumped
            assert "好的，口令是" in row["content"]
        else:
            assert LITERAL not in dumped
            assert PLACEHOLDER in dumped

    # 推流最终 assistant_message 与 POST 响应同样干净。
    for payload in (
        json.dumps(events, ensure_ascii=False),
        json.dumps(body, ensure_ascii=False),
    ):
        assert LITERAL not in payload
        assert prefix not in payload
        assert fragment not in payload


def test_redacted_message_only_sanitizes_assistant_incomplete_prefix(
    literal_settings: Settings,
) -> None:
    """投影层：assistant 的未完成前缀被去掉（含同正文 payload），user 手打前缀不吞。"""
    from flux.core.context.projection import redacted_message

    prefix = LITERAL[:11]
    user_message = SimpleNamespace(
        to_dict=lambda: {"role": "user", "kind": "text", "content": f"用户输入 {prefix}"}
    )
    user_data = redacted_message(literal_settings, user_message)
    assert user_data["content"] == f"用户输入 {prefix}"

    assistant_message = SimpleNamespace(
        to_dict=lambda: {
            "role": "assistant",
            "kind": "text",
            "content": f"好的，口令是 {prefix}",
            "payload": {"echo": f"好的，口令是 {prefix}", "other": "保留"},
        }
    )
    assistant_data = redacted_message(literal_settings, assistant_message)
    assert prefix not in assistant_data["content"]
    assert "好的，口令是" in assistant_data["content"]
    assert prefix not in json.dumps(assistant_data["payload"], ensure_ascii=False)
    assert assistant_data["payload"]["other"] == "保留"
