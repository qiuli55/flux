"""Context 打包测试（文档 §6：L0/L1/L2、裁剪、失效、任务间污染）。

Context 是 Flux 的基础设施，Agent 的主路径靠 `context.get` 拿工程事实，而不是把
历史对话无条件塞进 prompt。这里验证打包器的三条底线：

1. 分层清楚：任务（L1）、项目 Brain（L0）、近期提案（L2）各归各的 entry；
2. 预算受控：放不下就截断 / 退化成引用清单，绝不"超预算也要塞进去"；
3. 不串味：A 任务的提案不会出现在 B 任务的上下文里；过期提案带着状态出现，
   提醒 Agent 它是快照而不是事实。
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from datetime import datetime, timedelta, timezone

import pytest

from flux.config import Settings
from flux.container import Container
from flux.core.mcp.context_packager import (
    DEFAULT_BUDGET_CHARS,
    PROVENANCE_NOTE,
    package_context,
)
from flux.core.virtual_workspace.service import EXPIRE_REASON_TIMEOUT
from flux.enums import DecisionMode
from flux.models.task import Task

ORIGINAL = "def list_todos():\n    return None\n"
PROPOSED = "def list_todos():\n    return []\n"


@pytest.fixture()
async def ctx_container(settings: Settings, db_schema: None) -> AsyncIterator[Container]:
    """用完显式 dispose：避免 aiosqlite 的连接线程活过事件循环。"""
    container = Container(settings)
    try:
        yield container
    finally:
        await container.dispose()


async def _project(container: Container, *, name: str = "待办服务") -> uuid.UUID:
    project = await container.brain.create_project(name=name, repository="/srv/todo")
    return project.id


async def _memory(container: Container, project_id: uuid.UUID, content: str) -> None:
    from flux.enums import BrainSection

    await container.brain.write(project_id, section=BrainSection.CODING_RULES, content=content)


async def _task(container: Container, project_id: uuid.UUID, *, description: str) -> Task:
    return await container.task_repo.create(
        task_id=uuid.uuid4(),
        description=description,
        priority=0,
        project_id=project_id,
        agent_id=None,
        decision_mode=DecisionMode.AUTO,
    )


async def _propose(
    container: Container,
    *,
    file_path: str,
    task_id: uuid.UUID | None = None,
    project_id: uuid.UUID | None = None,
) -> str:
    """落一条提案并返回它的 file_path（打包结果里靠路径辨认）。"""
    change = await container.workspace.propose(
        file_path=file_path,
        original_content=ORIGINAL,
        proposed_content=PROPOSED,
        task_id=task_id,
        project_id=project_id,
        agent_source="developer-agent",
        summary=f"改动 {file_path}",
    )
    return change.file_path


async def test_pack_carries_l1_l0_l2_with_provenance(ctx_container: Container) -> None:
    """一个任务包同时带上任务事实、项目 Brain 与近期提案，并声明采信规则。"""
    project = await _project(ctx_container)
    await _memory(ctx_container, project, "测试命令固定为 python3 -m pytest")
    task = await _task(ctx_container, project, description="给待办服务加按完成状态过滤")
    await _propose(ctx_container, file_path="todo_service/store.py", task_id=task.id)

    pack = await package_context(ctx_container, task_id=str(task.id))

    # 任务自带项目归属：不传 project_id 也自动带上该项目的 Brain
    assert pack["task_id"] == str(task.id)
    assert pack["project_id"] == str(project)
    assert [entry["kind"] for entry in pack["entries"]] == ["task", "brain", "proposals"]
    assert [entry["ref"].split(":")[0] for entry in pack["entries"]] == [
        "task",
        "brain",
        "proposals",
    ]

    assert "# 当前任务（L1）" in pack["content"]
    assert "python3 -m pytest" in pack["content"]
    assert "todo_service/store.py" in pack["content"]
    assert pack["budget"] == DEFAULT_BUDGET_CHARS
    assert pack["used"] <= pack["budget"]
    assert pack["dropped_count"] == 0 and pack["dropped_refs"] == []
    assert pack["note"] == PROVENANCE_NOTE
    assert "断言不等于事实" in pack["content"] or "断言不等于事实" in pack["note"]


async def test_tight_budget_truncates_the_first_entry_and_references_the_rest(
    ctx_container: Container,
) -> None:
    """预算紧张时：第一条就地截断（保证调用方至少拿到东西），其余退化成引用清单。"""
    project = await _project(ctx_container)
    await _memory(ctx_container, project, "测试命令固定为 python3 -m pytest")
    task = await _task(ctx_container, project, description="给待办服务加按完成状态过滤")
    await _propose(ctx_container, file_path="todo_service/store.py", task_id=task.id)

    pack = await package_context(ctx_container, task_id=str(task.id), budget=64)

    assert pack["used"] <= 64
    assert pack["entries"][0]["kind"] == "task"
    assert pack["entries"][0]["truncated"] is True
    assert pack["entries"][0]["chars"] <= 64
    # 被裁掉的不是"消失"，而是以 ref 形式留在清单里，Agent 知道还有什么没拿到
    assert pack["dropped_count"] >= 1
    assert pack["dropped_refs"] and all(":" in ref for ref in pack["dropped_refs"])


async def test_one_task_proposal_never_leaks_into_another(ctx_container: Container) -> None:
    """连续做多个相关任务时，C 任务不该继承 A 任务的提案（§6 测试重点）。"""
    project = await _project(ctx_container)
    task_a = await _task(ctx_container, project, description="任务 A：改 store")
    task_c = await _task(ctx_container, project, description="任务 C：改接口层")
    await _propose(ctx_container, file_path="todo_service/store.py", task_id=task_a.id)
    await _propose(ctx_container, file_path="api/routes.py", task_id=task_c.id)

    pack_a = await package_context(ctx_container, task_id=str(task_a.id))
    pack_c = await package_context(ctx_container, task_id=str(task_c.id))

    assert "todo_service/store.py" in pack_a["content"]
    assert "api/routes.py" not in pack_a["content"]
    assert "api/routes.py" in pack_c["content"]
    assert "todo_service/store.py" not in pack_c["content"]
    # Project Brain 是项目级事实，同一项目的任务共享它是预期行为
    assert "待办服务" in pack_a["content"] and "待办服务" in pack_c["content"]


async def test_expired_proposal_is_shown_as_stale(ctx_container: Container) -> None:
    """过期提案仍出现在 L2 里，但带着 expired 状态——快照就是快照，不能当事实用。"""
    project = await _project(ctx_container)
    task = await _task(ctx_container, project, description="给待办服务加过滤")
    change = await ctx_container.workspace.propose(
        file_path="todo_service/store.py",
        original_content=ORIGINAL,
        proposed_content=PROPOSED,
        task_id=task.id,
        agent_source="developer-agent",
    )
    past = datetime.now(timezone.utc) - timedelta(seconds=1)
    await ctx_container.proposal_repo.set_expires_at(str(change.id), past)
    expired = await ctx_container.workspace.expire(str(change.id), reason=EXPIRE_REASON_TIMEOUT)
    assert expired.status == "expired"

    pack = await package_context(ctx_container, task_id=str(task.id))

    assert "todo_service/store.py" in pack["content"]
    assert "[expired]" in pack["content"]


async def test_pack_without_task_only_carries_project_facts(ctx_container: Container) -> None:
    """只给 project_id 时不带任何任务上下文——调用方要什么就给什么，不擅自补。"""
    project = await _project(ctx_container)
    await _memory(ctx_container, project, "测试命令固定为 python3 -m pytest")
    await _task(ctx_container, project, description="不该出现在包里的任务")
    # 项目级提案（没挂任务）：只给 project_id 时应能带上，但不带任何任务上下文
    await _propose(ctx_container, file_path="todo_service/store.py", project_id=project)

    pack = await package_context(ctx_container, project_id=str(project))

    assert pack["task_id"] is None
    assert [entry["kind"] for entry in pack["entries"]] == ["brain", "proposals"]
    assert "不该出现在包里的任务" not in pack["content"]
    assert "todo_service/store.py" in pack["content"]
