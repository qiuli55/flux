"""Artifacts API：对话产出的成品统一入口。

设计类产物不复制数据：id 形如 `design:<luma-id>`，列表与详情都实时读 Luma Studio。
页面 / 文档落在 Flux 自己的 artifacts 表里。设计稿的改名、删除、导入仍在
`/designs` 下面，这里只负责"把它们摆进同一个产物列表"。
"""

from __future__ import annotations

from typing import Any

import httpx
from fastapi import APIRouter, Body, Depends, Query

from flux.api.deps import get_container
from flux.api.response import ok
from flux.api.v1.designs import design_client, load_design
from flux.container import Container
from flux.errors import BadRequestError, NotFoundError
from flux.logging import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/artifacts", tags=["artifacts"])

DESIGN_PREFIX = "design:"
#: 本表能存的类型；design 是 Luma 的投影，创建仍走 /designs。
STORED_TYPES = {"page", "doc"}
ALL_TYPES = STORED_TYPES | {"design"}


def _require_type(value: Any) -> str:
    kind = str(value or "page").strip().lower()
    if kind not in STORED_TYPES:
        raise BadRequestError("产物类型只支持 page / doc；设计稿请走设计工作室")
    return kind


def _design_item(design: dict[str, Any]) -> dict[str, Any] | None:
    design_id = str(design.get("id") or "").strip()
    if not design_id:
        return None
    screens = design.get("screens")
    screen_count = design.get("screen_count")
    if not isinstance(screen_count, int):
        screen_count = len(screens) if isinstance(screens, list) else 0
    return {
        "id": f"{DESIGN_PREFIX}{design_id}",
        "type": "design",
        "title": str(design.get("name") or "").strip() or "未命名设计稿",
        "summary": str(design.get("description") or "").strip(),
        "version": _as_int(design.get("version")),
        "screen_count": screen_count,
        "project_id": design.get("project_id"),
        "updated_at": design.get("updated_at") or "",
        "preview": design.get("preview"),
    }


def _as_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


async def _design_items(client: httpx.AsyncClient) -> list[dict[str, Any]]:
    """读取设计稿列表；Luma 不可达时只跳过设计，不让整个产物列表 503。"""
    try:
        response = await client.get("/api/designs")
    except httpx.HTTPError:
        logger.warning("artifacts 列表读取设计稿失败，跳过 design 类产物")
        return []
    if response.status_code >= 400:
        logger.warning("artifacts 列表读取设计稿返回 %s", response.status_code)
        return []
    try:
        payload = response.json()
    except ValueError:
        return []
    designs = payload.get("designs") if isinstance(payload, dict) else None
    if not isinstance(designs, list):
        return []
    items: list[dict[str, Any]] = []
    for design in designs:
        if isinstance(design, dict):
            item = _design_item(design)
            if item is not None:
                items.append(item)
    return items


@router.get("")
async def list_artifacts(
    type: str | None = Query(default=None, description="design / page / doc；缺省返回全部"),
    container: Container = Depends(get_container),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    kind = type.strip().lower() if isinstance(type, str) and type.strip() else None
    if kind is not None and kind not in ALL_TYPES:
        raise BadRequestError("产物类型只支持 design / page / doc")

    items: list[dict[str, Any]] = []
    if kind in (None, "design"):
        items.extend(await _design_items(client))
    if kind is None or kind in STORED_TYPES:
        rows = await container.artifacts.list(kind)
        items.extend(row.to_dict(include_content=False) for row in rows)

    # 两侧都是 ISO 时间串：按更新时间倒序，缺失时间的排最后。
    items.sort(key=lambda item: str(item.get("updated_at") or ""), reverse=True)
    return ok(items, metadata={"count": len(items), "type": kind or "all"})


@router.post("")
async def create_artifact(
    payload: dict[str, Any] = Body(...),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    kind = _require_type(payload.get("type"))
    title = str(payload.get("title") or "").strip()
    if not title:
        raise BadRequestError("产物标题不能为空")
    artifact = await container.artifacts.create(
        type=kind,
        title=title[:160],
        content=str(payload.get("content") or ""),
        meta=payload.get("metadata") if isinstance(payload.get("metadata"), dict) else None,
    )
    return ok(artifact.to_dict())


@router.get("/{artifact_id}")
async def get_artifact(
    artifact_id: str,
    container: Container = Depends(get_container),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    if artifact_id.startswith(DESIGN_PREFIX):
        design_id = artifact_id[len(DESIGN_PREFIX) :]
        design = await load_design(client, design_id)
        item = _design_item(design)
        if item is None:
            raise NotFoundError("设计稿不存在")
        return ok(item)
    artifact = await container.artifacts.get(artifact_id)
    return ok(artifact.to_dict())


@router.put("/{artifact_id}")
async def update_artifact(
    artifact_id: str,
    payload: dict[str, Any] = Body(...),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    if artifact_id.startswith(DESIGN_PREFIX):
        raise BadRequestError("设计稿请在设计工作室里修改")
    title = payload.get("title")
    content = payload.get("content")
    base_version = payload.get("base_version")
    if title is not None and not isinstance(title, str):
        raise BadRequestError("title 必须是字符串")
    if content is not None and not isinstance(content, str):
        raise BadRequestError("content 必须是字符串")
    if base_version is not None and not isinstance(base_version, int):
        raise BadRequestError("base_version 必须是整数")
    artifact = await container.artifacts.update(
        artifact_id,
        title=title,
        content=content,
        base_version=base_version,
    )
    return ok(artifact.to_dict())


@router.delete("/{artifact_id}")
async def delete_artifact(
    artifact_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    if artifact_id.startswith(DESIGN_PREFIX):
        raise BadRequestError("设计稿请在设计工作室里删除")
    await container.artifacts.delete(artifact_id)
    return ok({"id": artifact_id})
