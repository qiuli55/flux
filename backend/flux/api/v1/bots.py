"""常驻 Bot 管理 API（Bot 模式）。

Bot 与「对话」共用同一套执行链路：每次开工都落一条 tasks 记录（`tasks.bot_id`），
这里只负责任务之外的配置面——名称/头像/Agent/权限/工作目录/定时。
"""

from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.enums import BotStatus, TaskStatus
from flux.errors import BadRequestError, NotFoundError
from flux.models.bot import Bot
from flux.schemas.api import BotCreateRequest, BotRunRequest, BotUpdateRequest

router = APIRouter(prefix="/bots", tags=["bots"])


def _bot_id(value: str) -> uuid.UUID:
    """路径参数非法时按「Bot 不存在」处理（与任务接口的 404 语义一致）。"""
    try:
        return uuid.UUID(value)
    except ValueError as exc:
        raise NotFoundError(f"Bot {value} 不存在", details={"bot_id": value}) from exc


def _bot_dict(container: Container, bot: Bot) -> dict[str, Any]:
    data = bot.to_dict()
    # 默认工作目录是派生值，不进库；接口里直接给前端可读的绝对路径
    data["workspace_path"] = str(container.bots.workspace_path(bot))
    return data


@router.get("")
async def list_bots(
    status: str | None = None,
    limit: int = 100,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出全部 Bot；status=active / paused 用于分组展示。"""
    if status is not None:
        try:
            BotStatus(status)
        except ValueError as exc:
            raise BadRequestError(
                f"status 不是合法的 Bot 状态：{status}",
                details={"status": status, "allowed": [s.value for s in BotStatus]},
            ) from exc
    if limit < 1 or limit > 200:
        raise BadRequestError("limit 必须在 1~200 之间", details={"limit": limit})
    bots = await container.bots.list(status=status, limit=limit)
    return ok(
        [_bot_dict(container, bot) for bot in bots],
        metadata={"count": len(bots)},
    )


@router.post("")
async def create_bot(
    payload: BotCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """创建 Bot。带 schedule 时立刻算出 next_run_at，后台调度器随即接管。"""
    bot = await container.bots.create(
        name=payload.name,
        description=payload.description,
        avatar_shape=payload.avatar_shape,
        avatar_color=payload.avatar_color,
        agent_id=payload.agent_id,
        model=payload.model,
        key_id=payload.key_id,
        permission_mode=payload.permission_mode,
        decision_mode=payload.decision_mode,
        workspace=payload.workspace,
        schedule=payload.schedule,
    )
    return ok(_bot_dict(container, bot))


@router.get("/{bot_id}")
async def get_bot(bot_id: str, container: Container = Depends(get_container)) -> dict[str, object]:
    bot = await container.bots.get(_bot_id(bot_id))
    return ok(_bot_dict(container, bot))


@router.patch("/{bot_id}")
async def update_bot(
    bot_id: str,
    payload: BotUpdateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """部分更新：只改请求里出现的字段（exclude_unset 保留「没传」与「传 null」的区别）。"""
    changes = payload.model_dump(exclude_unset=True)
    bot = await container.bots.update(_bot_id(bot_id), changes)
    return ok(_bot_dict(container, bot))


@router.delete("/{bot_id}")
async def delete_bot(
    bot_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """删除 Bot；历史开工任务保留（tasks.bot_id 由外键置空）。"""
    target = _bot_id(bot_id)
    await container.bots.delete(target)
    return ok({"deleted": str(target)})


@router.post("/{bot_id}/run")
async def run_bot(
    bot_id: str,
    payload: BotRunRequest | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """手动开工一次。`instruction` 留空则用 Bot 自己的 description。"""
    target = _bot_id(bot_id)
    task = await container.bots.run_now(
        target, instruction=(payload.instruction if payload else None)
    )
    bot = await container.bots.get(target)
    return ok({"bot": _bot_dict(container, bot), "task": task.to_dict()})


@router.get("/{bot_id}/tasks")
async def list_bot_tasks(
    bot_id: str,
    status: str | None = None,
    limit: int = 50,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """某个 Bot 的开工记录（按创建时间倒序）。"""
    target = await container.bots.get(_bot_id(bot_id))
    wanted: TaskStatus | None = None
    if status:
        try:
            wanted = TaskStatus(status)
        except ValueError as exc:
            raise BadRequestError(
                f"status 不是合法的任务状态：{status}",
                details={"status": status, "allowed": [s.value for s in TaskStatus]},
            ) from exc
    if limit < 1 or limit > 200:
        raise BadRequestError("limit 必须在 1~200 之间", details={"limit": limit})
    tasks = await container.task_repo.list(bot_id=target.id, status=wanted, limit=limit)
    return ok([t.to_dict() for t in tasks], metadata={"count": len(tasks)})


__all__ = ["router"]
