"""Supervisor（助理）API：事件入口、观察回放、设置。

权限面与其它 v1 接口一致，统一由 `require_rest_auth` 把关（见 flux.main）。
本组接口只写"事实"：事件入库、设置调整；派活/停止等动作在 P2 起另开端点。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Query

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import SupervisorEventRequest, SupervisorSettingsRequest

router = APIRouter(prefix="/supervisor", tags=["supervisor"])


@router.post("/events")
async def ingest_event(
    payload: SupervisorEventRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """记录一条外部事件（幂等）。重复投递只回同一条记录，metadata.created=false。"""
    data = await container.supervisor.ingest_event(
        source_key=payload.source_key,
        external_id=payload.external_id,
        event_type=payload.event_type,
        actor_id=payload.actor_id,
        actor_platform=payload.actor_platform,
        thread_key=payload.thread_key,
        content=payload.content,
        raw=payload.raw,
        project_id=payload.project_id,
    )
    return ok(data["trigger"], metadata={"created": data["created"]})


@router.get("/observations")
async def list_observations(
    limit: int = Query(default=50, ge=1, le=200),
    status: str | None = Query(default=None, description="按 trigger.status 过滤"),
    project_id: uuid.UUID | None = Query(default=None),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """观察模式回放：事件按时间倒序，带最近一次判断（若已判断）。"""
    data = await container.supervisor.observations(
        limit=limit, status=status, project_id=project_id
    )
    return ok(
        data["items"],
        metadata={
            "count": len(data["items"]),
            "pending_outbox": data["pending_outbox"],
        },
    )


@router.get("/settings")
async def read_supervisor_settings(
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """读取准入模式与计划上限。"""
    return ok(await container.supervisor.read_settings())


@router.put("/settings")
async def update_supervisor_settings(
    payload: SupervisorSettingsRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """局部更新设置；字段缺省/为 null 表示保持原值。"""
    fields = payload.model_dump(exclude_none=True, mode="json")
    return ok(await container.supervisor.update_settings(fields))
