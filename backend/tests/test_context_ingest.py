"""产品主循环上下文摄入回归（issue #3 / P1-1）。

验收核心：**不经过 Flux MCP** 时，`context_steps` 与 `context_blobs` 仍然增长。

覆盖四条入口：
1. 用户消息（`TaskRepository.add_messages` 钩子）；
2. 助手最终答复（含 `run_finished(completed)`；失败状态摘要跳过）；
3. runtime 原生工具/命令收尾（`TaskStreamRelay`，不经 `/mcp` tools/call）；
4. Run 尚未创建时的稳定任务级归属键 `task:<task_uuid>`。

每条入口都同时核对：step 落库、blob 可按 sha256 取回、原文密钥不在 step head / blob 里。
"""

from __future__ import annotations

import uuid

from flux.container import Container
from flux.core.event.bus import Events
from flux.core.task_engine.repository import MessageDraft
from flux.enums import StepKind, TaskMessageRole

#: 虚构密钥：命中 `sk-` 前缀规则，足够长
_SECRET = "sk-TEST_SECRET_12345abcdefghijklmnop"


async def _new_task(container: Container, *, run_id: str | None = None) -> uuid.UUID:
    task = await container.task_repo.create(
        task_id=uuid.uuid4(),
        description="回归：上下文摄入",
        priority=1,
        project_id=None,
        agent_id=None,
    )
    if run_id is not None:
        await container.task_repo.set_run_id(task.id, run_id)
    return task.id


async def test_user_and_assistant_messages_are_ingested_under_run(
    apply_container: Container,
) -> None:
    """用户正文与助手可见正文都进 step；密钥只以占位符出现在 head / blob。"""
    run_id = uuid.uuid4().hex
    task_id = await _new_task(apply_container, run_id=run_id)

    await apply_container.task_repo.add_messages(
        task_id,
        [
            MessageDraft(
                role=TaskMessageRole.USER.value,
                content=f"请检查这个 key：{_SECRET}",
            ),
            MessageDraft(
                role=TaskMessageRole.ASSISTANT.value,
                content="收到，已在本地复核；密钥不会写进对话。",
            ),
        ],
    )

    steps = await apply_container.context_steps.list_by_run(run_id)

    assert len(steps) == 2
    assert [step.kind for step in steps] == [StepKind.MODEL_CALL.value] * 2
    assert [step.tool for step in steps] == ["user_message", "assistant_message"]
    assert all(step.result_sha256 for step in steps)

    user_step = steps[0]
    assert _SECRET not in (user_step.result_head or "")
    assert "[REDACTED:key]" in (user_step.result_head or "")
    blob = await apply_container.content_store.get(user_step.result_sha256 or "")
    assert _SECRET.encode() not in blob
    assert b"[REDACTED:key]" in blob


async def test_assistant_final_answer_is_ingested_but_status_summary_is_skipped(
    apply_container: Container,
) -> None:
    """`run_finished(completed)` 可能承载最终答复 → 摄入；失败/取消的状态摘要 → 跳过。"""
    run_id = uuid.uuid4().hex
    task_id = await _new_task(apply_container, run_id=run_id)

    await apply_container.task_repo.add_messages(
        task_id,
        [
            MessageDraft(
                role=TaskMessageRole.ASSISTANT.value,
                kind="run_finished",
                content="最终答复：修复完成，测试通过。",
                payload={"status": "completed", "run_id": run_id},
            )
        ],
    )
    after_final = await apply_container.context_steps.list_by_run(run_id)
    assert len(after_final) == 1
    assert after_final[0].tool == "assistant_message"

    await apply_container.task_repo.add_messages(
        task_id,
        [
            MessageDraft(
                role=TaskMessageRole.ASSISTANT.value,
                kind="run_finished",
                content="Agent 执行失败：上游超时。",
                payload={"status": "failed", "run_id": run_id},
            )
        ],
    )
    # 状态摘要不是最终答复，不新增 step，避免和 stream / 消息路径重复
    assert len(await apply_container.context_steps.list_by_run(run_id)) == 1


async def test_user_message_without_run_uses_stable_task_key(
    apply_container: Container,
) -> None:
    """Run 尚未创建时用 `task:<task_uuid>` 归属，而不是丢弃或编造 Run id。"""
    task_id = await _new_task(apply_container)

    await apply_container.task_repo.add_messages(
        task_id,
        [MessageDraft(role=TaskMessageRole.USER.value, content="开始前先说明需求")],
    )

    key = f"task:{task_id}"
    steps = await apply_container.context_steps.list_by_run(key)
    assert len(steps) == 1
    assert steps[0].kind == StepKind.MODEL_CALL.value
    assert steps[0].turn_id == key


async def test_native_tool_event_is_ingested_without_mcp(apply_container: Container) -> None:
    """只发 runtime 原生工具收尾（不调 /mcp），step 与 blob 仍然增长且已打码。"""
    run_id = uuid.uuid4().hex
    task_id = await _new_task(apply_container, run_id=run_id)
    apply_container.task_stream.bind_run(run_id, str(task_id))

    event = {
        "run_id": run_id,
        "event_type": "tool_result",
        "name": "Bash",
        "detail": "curl -H 'Authorization: Bearer token'",
        "output": f"token={_SECRET}\n",
        "status": "completed",
        "call_id": "call-1",
    }
    await apply_container.bus.publish(Events.DSH_EVENT, event)

    steps = await apply_container.context_steps.list_by_run(run_id)
    assert len(steps) == 1
    assert steps[0].kind == StepKind.COMMAND.value
    assert steps[0].tool == "Bash"
    assert _SECRET not in (steps[0].result_head or "")
    blob = await apply_container.content_store.get(steps[0].result_sha256 or "")
    assert _SECRET.encode() not in blob
    assert b"[REDACTED:key]" in blob

    # 同一 call_id 的重复收尾不再写第二条 step
    await apply_container.bus.publish(Events.DSH_EVENT, event)
    assert len(await apply_container.context_steps.list_by_run(run_id)) == 1


async def test_ingest_without_any_owner_is_skipped(apply_container: Container) -> None:
    """无法归属到 Run / 任务的正文直接跳过——不制造与归属单元无关的噪声。"""
    result = await apply_container.context_ingestor.ingest(
        run_id="",
        kind=StepKind.TOOL_CALL,
        text="orphan output",
    )
    assert result is None
    assert await apply_container.context_steps.list_by_run("") == []
