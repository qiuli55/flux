"""助理入口 API（2026-11-28）。

「对话」页右上角在「主助理」和已创建的项目助理之间切换。临时子任务不出现在这里。
创建项目助理必须 `confirmed=true`：用户确认前什么都不创建，取消就是不创建。
"""

from __future__ import annotations

import contextlib
import uuid

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.task_engine.subtask_dispatcher import status_label
from flux.enums import TaskOrigin
from flux.errors import BadRequestError, NotFoundError
from flux.logging import get_logger
from flux.schemas.api import ProjectAssistantCreateRequest

router = APIRouter(prefix="/assistants", tags=["assistants"])
logger = get_logger(__name__)

_MASTER_LABEL = "主助理"


@router.get("")
async def list_assistants(container: Container = Depends(get_container)) -> dict[str, object]:
    """切换器的可选项：「主助理」+ 每个已创建的项目助理；不含临时子任务。"""
    master = await _master_summary(container)
    projects = [_project_summary(item) for item in await container.project_assistants.list_all()]
    return ok({"master": master, "projects": projects}, metadata={"count": len(projects)})


@router.get("/suggestions")
async def suggest_assistants(container: Container = Depends(get_container)) -> dict[str, object]:
    """从全部已有对话自动建议可创建的项目助理；只建议，不创建。"""
    ideas = await container.project_assistant_service.suggestions()
    return ok(ideas, metadata={"count": len(ideas)})


@router.post("")
async def create_assistant(
    payload: ProjectAssistantCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """用户确认后创建项目助理（含它自己的常驻对话）；未确认时 409，什么都不创建。"""
    assistant = await container.project_assistant_service.create_confirmed(
        name=payload.name,
        source_task_ids=payload.source_task_ids,
        project_id=payload.project_id,
        confirmed=payload.confirmed,
    )
    logger.info("创建项目助理 id=%s name=%s", assistant.id, assistant.name)
    return ok(_project_summary(assistant))


@router.get("/{assistant_id}")
async def get_assistant(
    assistant_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    assistant = await _require_assistant(container, assistant_id)
    return ok(_project_summary(assistant))


@router.delete("/{assistant_id}")
async def delete_assistant(
    assistant_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """删除项目助理记录；它自己的常驻对话一并删除（来源对话不动）。"""
    assistant = await _require_assistant(container, assistant_id)
    conversation_id = assistant.conversation_task_id
    await container.project_assistants.delete(assistant.id)
    # 常驻对话是项目助理自己的空间，随助理一起删除；来源对话保持原样
    with contextlib.suppress(NotFoundError):
        await container.task_repo.delete(conversation_id)
    return ok({"id": assistant_id, "deleted": True})


async def _require_assistant(container: Container, assistant_id: str):
    try:
        return await container.project_assistants.get(uuid.UUID(assistant_id))
    except ValueError as exc:
        raise BadRequestError(
            f"assistant_id 不是合法 UUID：{assistant_id}", details={"assistant_id": assistant_id}
        ) from exc


async def _master_summary(container: Container) -> dict[str, object]:
    """主助理入口：沿用最近一条 origin=assistant 的对话；没有则返回空 id。"""
    tasks = await container.task_repo.list(origin=TaskOrigin.ASSISTANT, limit=1)
    task = tasks[0] if tasks else None
    return {
        "id": "master",
        "name": _MASTER_LABEL,
        "kind": "master",
        "conversation_task_id": str(task.id) if task is not None else None,
        "status": task.status if task is not None else None,
        "status_label": status_label(task.status) if task is not None else None,
    }


def _project_summary(assistant) -> dict[str, object]:
    return {
        "id": str(assistant.id),
        "name": assistant.name,
        "kind": "project",
        "project_id": str(assistant.project_id) if assistant.project_id else None,
        "conversation_task_id": str(assistant.conversation_task_id),
        "source_task_ids": assistant.source_ids(),
        "created_at": assistant.created_at.isoformat() if assistant.created_at else None,
    }
