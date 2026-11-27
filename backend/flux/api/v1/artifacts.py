"""Artifacts API：对话产出的成品统一入口。

设计类产物不复制数据：id 形如 `design:<luma-id>`，列表与详情都实时读 Luma Studio。
页面 / 文档落在 Flux 自己的 artifacts 表里，带版本历史、资产库与钉选；设计稿的改名、
删除、导入仍在 `/designs` 下面，这里只负责"把它们摆进同一个产物列表"。

协议对齐 Claude Code 2.1.323：页面 / 文档是唯一两种可发布源类型，design 是只读投影；
版本号既是乐观锁也是当前版本号；每次写入追加历史快照；资产从属于产物。
"""

from __future__ import annotations

import base64
import binascii
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Body, Depends, Query, Request, Response

from flux.api.deps import get_container
from flux.api.response import ok
from flux.api.v1.designs import design_client, load_design
from flux.container import Container
from flux.core.artifacts.content import ASSET_MAX_FILE_BYTES, TYPE_DESCRIPTIONS
from flux.errors import BadRequestError, NotFoundError, ValidationError
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


def _require_stored(artifact_id: str) -> None:
    if artifact_id.startswith(DESIGN_PREFIX):
        raise BadRequestError("设计稿请在设计工作室里操作")


def _optional_text(payload: dict[str, Any], key: str) -> str | None:
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise BadRequestError(f"{key} 必须是字符串")
    return value


def _optional_bool(payload: dict[str, Any], key: str, *, default: bool = False) -> bool:
    value = payload.get(key, default)
    if not isinstance(value, bool):
        raise BadRequestError(f"{key} 必须是布尔值")
    return value


def _optional_int(payload: dict[str, Any], key: str) -> int | None:
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool):
        raise BadRequestError(f"{key} 必须是整数")
    return value


def _capabilities(payload: dict[str, Any]) -> dict | None:
    """省略 → None（沿用）；空对象 → {}（清空）；其它必须是对象。"""
    if "capabilities" not in payload:
        return None
    value = payload.get("capabilities")
    if value is None:
        return None
    if not isinstance(value, dict):
        raise BadRequestError("capabilities 必须是 JSON 对象")
    return value


def _encode_offset(offset: int) -> str:
    raw = base64.urlsafe_b64encode(str(max(offset, 0)).encode("ascii")).decode("ascii")
    return raw.rstrip("=")


def _decode_offset(token: str | None, *, param: str = "page_token") -> int:
    if not token:
        return 0
    try:
        padded = token + "=" * (-len(token) % 4)
        value = int(base64.urlsafe_b64decode(padded.encode("ascii")).decode("ascii"))
    except (ValueError, UnicodeDecodeError, binascii.Error) as exc:
        raise BadRequestError(f"{param} 无效") from exc
    if value < 0:
        raise BadRequestError(f"{param} 无效")
    return value


def _design_item(design: dict[str, Any]) -> dict[str, Any] | None:
    design_id = str(design.get("id") or "").strip()
    if not design_id:
        return None
    screens = design.get("screens")
    screen_count = design.get("screen_count")
    if not isinstance(screen_count, int):
        screen_count = len(screens) if isinstance(screens, list) else 0
    description = str(design.get("description") or "").strip()
    return {
        "id": f"{DESIGN_PREFIX}{design_id}",
        "type": "design",
        "title": str(design.get("name") or "").strip() or "未命名设计稿",
        "description": description,
        "icon": "",
        "label": "",
        "contract": None,
        "capabilities": {},
        "pinned": False,
        "summary": description,
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


async def _read_limited_body(request: Request) -> bytes:
    declared = request.headers.get("content-length")
    if declared:
        try:
            if int(declared) > ASSET_MAX_FILE_BYTES:
                raise ValidationError(
                    f"单个资产不能超过 {ASSET_MAX_FILE_BYTES // (1024 * 1024)} MiB",
                    details={"reason": "file_too_large", "max_bytes": ASSET_MAX_FILE_BYTES},
                )
        except ValueError:
            raise BadRequestError("Content-Length 无效") from None
    buffer = bytearray()
    async for chunk in request.stream():
        buffer.extend(chunk)
        if len(buffer) > ASSET_MAX_FILE_BYTES:
            raise ValidationError(
                f"单个资产不能超过 {ASSET_MAX_FILE_BYTES // (1024 * 1024)} MiB",
                details={"reason": "file_too_large", "max_bytes": ASSET_MAX_FILE_BYTES},
            )
    return bytes(buffer)


@router.get("/types")
async def list_artifact_types() -> dict[str, object]:
    """支持的类型说明；page / doc 可发布，design 是 Luma 的只读投影。"""
    return ok(list(TYPE_DESCRIPTIONS), metadata={"count": len(TYPE_DESCRIPTIONS)})


@router.get("")
async def list_artifacts(
    type: str | None = Query(default=None, description="design / page / doc；缺省返回全部"),
    limit: int | None = Query(default=None, ge=1, le=200, description="分页大小；缺省返回全部"),
    page_token: str | None = Query(default=None, description="上一页返回的 next_page_token"),
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
    total = len(items)
    offset = _decode_offset(page_token)
    if limit is None:
        page = items[offset:]
        next_token = None
    else:
        page = items[offset : offset + limit]
        next_offset = offset + limit
        next_token = _encode_offset(next_offset) if next_offset < total else None
    return ok(
        page,
        metadata={
            "count": len(page),
            "total": total,
            "type": kind or "all",
            "next_page_token": next_token,
        },
    )


@router.post("")
async def create_artifact(
    payload: dict[str, Any] = Body(...),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    kind = _require_type(payload.get("type"))
    content = payload.get("content")
    if content is None:
        content = ""
    if not isinstance(content, str):
        raise BadRequestError("content 必须是字符串")
    pinned = _optional_bool(payload, "pinned")
    artifact = await container.artifacts.create(
        type=kind,
        title=_optional_text(payload, "title"),
        content=content,
        description=_optional_text(payload, "description"),
        icon=_optional_text(payload, "icon"),
        label=_optional_text(payload, "label"),
        contract=_optional_text(payload, "contract"),
        capabilities=_capabilities(payload),
        pinned=pinned,
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
    _require_stored(artifact_id)
    content = payload.get("content")
    if content is not None and not isinstance(content, str):
        raise BadRequestError("content 必须是字符串")
    artifact = await container.artifacts.update(
        artifact_id,
        title=_optional_text(payload, "title"),
        content=content,
        description=_optional_text(payload, "description"),
        icon=_optional_text(payload, "icon"),
        label=_optional_text(payload, "label"),
        contract=_optional_text(payload, "contract"),
        capabilities=_capabilities(payload),
        base_version=_optional_int(payload, "base_version"),
        force=_optional_bool(payload, "force"),
    )
    return ok(artifact.to_dict())


@router.delete("/{artifact_id}")
async def delete_artifact(
    artifact_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    await container.artifacts.delete(artifact_id)
    return ok({"id": artifact_id})


@router.post("/{artifact_id}/pin")
async def pin_artifact(
    artifact_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    artifact = await container.artifacts.set_pinned(artifact_id, True)
    return ok({"id": str(artifact.id), "pinned": True, "version": artifact.version})


@router.post("/{artifact_id}/unpin")
async def unpin_artifact(
    artifact_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    artifact = await container.artifacts.set_pinned(artifact_id, False)
    return ok({"id": str(artifact.id), "pinned": False, "version": artifact.version})


@router.get("/{artifact_id}/versions")
async def list_artifact_versions(
    artifact_id: str,
    limit: int = Query(default=50, ge=1, le=200),
    page_token: str | None = Query(default=None),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    offset = _decode_offset(page_token)
    rows, total = await container.artifacts.list_versions(artifact_id, limit=limit, offset=offset)
    next_offset = offset + limit
    return ok(
        [row.to_dict(include_content=False) for row in rows],
        metadata={
            "count": len(rows),
            "total": total,
            "next_page_token": _encode_offset(next_offset) if next_offset < total else None,
        },
    )


@router.get("/{artifact_id}/versions/{version}")
async def get_artifact_version(
    artifact_id: str,
    version: int,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    row = await container.artifacts.get_version(artifact_id, version)
    return ok(row.to_dict(include_content=True))


@router.post("/{artifact_id}/assets")
async def upload_artifact_asset(
    artifact_id: str,
    request: Request,
    name: str = Query(..., min_length=1, max_length=200, description="原始文件名"),
    mime: str = Query(default="", max_length=200, description="客户端给的 MIME，可留空"),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """上传一个资产。沿用聊天附件的约定：裸请求体 + 查询参数传文件名。"""
    _require_stored(artifact_id)
    data = await _read_limited_body(request)
    asset = await container.artifacts.add_asset(
        artifact_id, file_name=name, content_type=mime, data=data
    )
    usage = await container.artifacts.asset_usage(artifact_id)
    return ok(asset.to_dict(), metadata={"usage": usage})


@router.get("/{artifact_id}/assets")
async def list_artifact_assets(
    artifact_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    assets = await container.artifacts.list_assets(artifact_id)
    usage = await container.artifacts.asset_usage(artifact_id)
    return ok(
        {"assets": [asset.to_dict() for asset in assets], "usage": usage},
        metadata={"count": len(assets)},
    )


@router.get("/{artifact_id}/assets/{asset_id}")
async def download_artifact_asset(
    artifact_id: str,
    asset_id: str,
    container: Container = Depends(get_container),
) -> Response:
    _require_stored(artifact_id)
    asset, data = await container.artifacts.read_asset(artifact_id, asset_id)
    fallback = asset.file_name.encode("ascii", "ignore").decode("ascii") or "asset"
    quoted = quote(asset.file_name, safe="")
    return Response(
        content=data,
        media_type=asset.content_type,
        headers={
            "Content-Disposition": (
                f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quoted}"
            ),
            "Content-Length": str(len(data)),
            "X-Artifact-Asset-Id": asset.id.hex,
            "X-Artifact-Asset-Sha256": asset.sha256,
        },
    )


@router.delete("/{artifact_id}/assets/{asset_id}")
async def delete_artifact_asset(
    artifact_id: str,
    asset_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    _require_stored(artifact_id)
    asset = await container.artifacts.delete_asset(artifact_id, asset_id)
    usage = await container.artifacts.asset_usage(artifact_id)
    return ok({"id": asset.id.hex, "deleted": True}, metadata={"usage": usage})
