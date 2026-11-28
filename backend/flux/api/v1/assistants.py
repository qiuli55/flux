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
from flux.core.project_assistant import progress_text
from flux.core.task_engine.subtask_dispatcher import status_label
from flux.enums import TaskOrigin
from flux.errors import BadRequestError, NotFoundError
from flux.logging import get_logger
from flux.schemas.api import (
    ProjectAssistantContinueRequest,
    ProjectAssistantCreateRequest,
    ProjectAssistantForkRequest,
)

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


@router.get("/{assistant_id}/progress")
async def get_assistant_progress(
    assistant_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """只读汇总相关对话进度：进行中 / 可能已停住 / 失败 / 已完成。

    数据全部来自服务端已有记录，不启动电脑 Agent、不派子任务。
    """
    assistant = await _require_assistant(container, assistant_id)
    progress = await container.project_assistant_service.progress(assistant)
    return ok(progress, metadata={"text": progress_text(progress)})


@router.get("/{assistant_id}/history")
async def list_assistant_history(
    assistant_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """「读历史」：列出可只读查看的相关对话；只读，不写来源对话。"""
    assistant = await _require_assistant(container, assistant_id)
    history = await container.project_assistant_service.history(assistant)
    return ok(history, metadata={"count": history["count"]})


@router.get("/{assistant_id}/history/{task_id}")
async def read_assistant_history(
    assistant_id: str, task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """「读历史」：只读打开一条相关对话及其消息（输入在客户端禁用）。"""
    assistant = await _require_assistant(container, assistant_id)
    data = await container.project_assistant_service.read_history(assistant, task_id)
    return ok(data)


@router.post("/{assistant_id}/continue")
async def continue_assistant_conversation(
    assistant_id: str,
    payload: ProjectAssistantContinueRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """「接着干」：二次确认后向已有相关对话发送新任务并续跑。未确认则 409、什么都不写。"""
    assistant = await _require_assistant(container, assistant_id)
    data = await container.project_assistant_service.continue_confirmed(
        assistant,
        source_task_id=payload.source_task_id,
        content=payload.content,
        attachment_ids=payload.attachment_ids,
        confirmed=payload.confirmed,
    )
    return ok(data, metadata={"route": "continue", "read_only": False})


@router.post("/{assistant_id}/fork")
async def fork_assistant_conversation(
    assistant_id: str,
    payload: ProjectAssistantForkRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """「开副本」：二次确认后新建独立常驻对话，带上源对话摘要；原文不变。"""
    assistant = await _require_assistant(container, assistant_id)
    data = await container.project_assistant_service.fork_confirmed(
        assistant,
        source_task_id=payload.source_task_id,
        name=payload.name,
        confirmed=payload.confirmed,
    )
    return ok(data, metadata={"route": "fork"})


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
