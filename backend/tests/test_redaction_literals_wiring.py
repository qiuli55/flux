"""PR #42 遗留回归：其余裸 `redact` 路径接入 `FLUX_REDACTION_LITERALS`。

验收报告第 5 节第 3 项点名了 MCP 工具、Context 打包、知识索引、工作记忆与 DSH
沉淀仍在用裸 `redact(...)`：内置规则只认高熵 / 带前缀密钥，配置里的无前缀短字面量
会以明文进入审批展示、步骤摘要、上下文、知识库与长期记忆。这里用虚构字面量逐条
锁定：装配层把 `Settings.redaction_literal_list` 注入后，这些路径都应出现
`[REDACTED:key]` 且不再包含明文。

所有测试值均为明显虚构的 `CUSTOM_LITERAL_SMOKE_XYZ`，不含任何真实凭据。
"""

from __future__ import annotations

import importlib
import json
import logging
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
from sqlalchemy import select

from flux.config import Settings
from flux.container import Container
from flux.core.event.bus import Events
from flux.core.knowledge.indexer import BRAIN, CONVERSATION, DOC
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.context_packager import package_context
from flux.core.mcp.tools.base import ToolContext
from flux.core.mcp.tools.read_tools import context_steps
from flux.core.task_engine.repository import MessageDraft
from flux.core.task_engine.work_memory import build_memory_card
from flux.enums import BrainSection, Capability, DshRunStatus, StepKind, StepStatus, TaskStatus
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
