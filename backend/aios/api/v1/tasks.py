"""Task API（主规格 §12.4）。

任务状态落 tasks 表（本增量起），进程内的调度队列是易失结构、不承担状态权威。
"""

from __future__ import annotations

import contextlib
import uuid

from fastapi import APIRouter, Depends

from aios.api.deps import get_container
from aios.api.response import ok
from aios.container import Container
from aios.core.event.bus import Events
from aios.enums import TaskStatus
from aios.errors import BadRequestError, ConflictError, NotFoundError
from aios.models.task import Task
from aios.schemas.api import TaskCreateRequest

router = APIRouter(prefix="/tasks", tags=["tasks"])

_FINAL_STATUSES = {TaskStatus.COMPLETED.value, TaskStatus.CANCELLED.value, TaskStatus.FAILED.value}


def _task_dict(task: Task) -> dict[str, object]:
    """把 ORM 对象转成 M0 冻结的 7 键响应体（不含 cost / created_at）。"""
    return {
        "id": str(task.id),
        "description": task.description,
        "status": task.status,
        "priority": task.priority,
        "agent_id": str(task.agent_id) if task.agent_id is not None else None,
        "project_id": str(task.project_id) if task.project_id is not None else None,
        "result": task.result,
    }


def _parse_uuid(value: str, *, field: str) -> uuid.UUID:
    """请求体里的标识串必须是合法 UUID，否则按 400 拒绝。"""
    try:
        return uuid.UUID(value)
    except ValueError as exc:
        raise BadRequestError(f"{field} 不是合法 UUID：{value}", details={field: value}) from exc


def _task_id(value: str) -> uuid.UUID:
    """路径参数里的任务标识非法时按「任务不存在」处理，与 M0 的 404 语义一致（不返回 422）。"""
    try:
        return uuid.UUID(value)
    except ValueError as exc:
        raise NotFoundError(f"任务 {value} 不存在", details={"task_id": value}) from exc


@router.post("")
async def create_task(
    payload: TaskCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    project_id = _parse_uuid(payload.project_id, field="project_id") if payload.project_id else None
    agent_id = _parse_uuid(payload.agent_id, field="agent_id") if payload.agent_id else None
    if agent_id is not None:
        # Agent 注册表是 Agent 存在性的唯一权威：API 边界 fail-closed。
        container.agents.get(payload.agent_id)

    task = await container.task_repo.create(
        task_id=uuid.uuid4(),
        description=payload.description,
        priority=payload.priority,
        project_id=project_id,
        agent_id=agent_id,
    )
    container.scheduler.submit(
        str(task.id),
        task.description,
        priority=task.priority,
        agent_id=str(agent_id) if agent_id is not None else None,
    )
    result = _task_dict(task)
    await container.bus.publish(Events.TASK_CREATED, result)
    return ok(result)


@router.get("/{task_id}")
async def get_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    task = await container.task_repo.get(_task_id(task_id))
    return ok(_task_dict(task))


@router.post("/{task_id}/cancel")
async def cancel_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，无法取消",
            details={"task_id": task_id, "status": task.status},
        )
    # 队列是进程内的易失结构，数据库才是任务状态的权威：
    # 任务在库中但不在队列里（进程重启过、或已被 next() 取出）时取消仍必须成功。
    with contextlib.suppress(NotFoundError):
        container.scheduler.cancel(str(task.id))
    task = await container.task_repo.set_status(task_uuid, TaskStatus.CANCELLED)
    return ok(_task_dict(task))
