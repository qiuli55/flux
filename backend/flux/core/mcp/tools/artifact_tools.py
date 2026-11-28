"""Artifacts 工具：让 Agent 把页面 / 文档发布成对话里的成品卡片。

与 `design.share` 同一套路：产物落 `artifacts` 表，聊天里只写一条 `artifact_card`
结构化消息，手机端据此渲染「类型 + 标题 + 已发布」卡片。产物默认私有，只有
当前账号能在 App 里看到，因此工具不返回任何可分享 URL，也要求 Agent 不要在
回复正文里粘贴整段内容或链接。

对齐 Claude Code 2.1.323 的点到即止版本：
- 发布支持 description / icon / label / capabilities / contract / base_version / force / pin；
- 版本冲突时把当前 live 正文一并交回（force 才覆盖，且应由用户明确要求）；
- list 支持分页；新增 list_types / pin / unpin 与资产库四个工具；
- watch / unwatch / status 属于桌面 / IDE 的实时订阅，本次不实现，也不假装已连接。

设计稿不走这里：设计类产物由 Luma Studio 拥有，发布仍用 `design.share`。
"""

from __future__ import annotations

import base64
import binascii
import uuid
from typing import Any

from flux.core.artifacts.content import TYPE_DESCRIPTIONS
from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.core.task_engine.repository import MessageDraft
from flux.enums import Capability, TaskMessageRole
from flux.errors import ConflictError, ValidationError

#: 本工具能发布的类型；design 是 Luma 的投影，创建仍走设计工作室。
_PUBLISH_TYPES = {"page", "doc"}

_PUBLISH_FIELDS = {
    "type",
    "title",
    "content",
    "artifact_id",
    "description",
    "icon",
    "label",
    "capabilities",
    "contract",
    "base_version",
    "force",
    "pin",
}


def _require_publish_type(raw: Any) -> str:
    kind = str(raw or "page").strip().lower()
    if kind not in _PUBLISH_TYPES:
        raise ValidationError(
            "type 只支持 page / doc；设计稿请用 design.share 发布",
            details={"param": "type"},
        )
    return kind


def _content(params: dict[str, Any], *, required: bool) -> str | None:
    """正文原样保留（HTML / Markdown 的空白有意义），只拿 strip 后的结果判空。"""
    value = params.get("content")
    if value is None:
        if required:
            raise ValidationError("content 不能为空", details={"param": "content"})
        return None
    if not isinstance(value, str):
        raise ValidationError("content 必须是字符串", details={"param": "content"})
    if required and not value.strip():
        raise ValidationError("content 不能为空", details={"param": "content"})
    return value


def _optional_int(params: dict[str, Any], key: str) -> int | None:
    value = params.get(key)
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValidationError(f"{key} 必须是整数", details={"param": key})
    return value


def _optional_bool(params: dict[str, Any], key: str) -> bool:
    value = params.get(key, False)
    if not isinstance(value, bool):
        raise ValidationError(f"{key} 必须是布尔值", details={"param": key})
    return value


def _capabilities(params: dict[str, Any]) -> dict | None:
    """省略 → None（沿用）；空对象 → {}（清空）；其它必须是对象。"""
    if "capabilities" not in params:
        return None
    value = params.get("capabilities")
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValidationError("capabilities 必须是 JSON 对象", details={"param": "capabilities"})
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
        raise ValidationError(f"{param} 无效", details={"param": param}) from exc
    if value < 0:
        raise ValidationError(f"{param} 无效", details={"param": param})
    return value


def _limit(params: dict[str, Any], *, default: int, maximum: int) -> int:
    value = params.get("limit", default)
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ValidationError("limit 必须是正整数", details={"param": "limit"})
    if value > maximum:
        raise ValidationError(f"limit 最多 {maximum}", details={"param": "limit", "max": maximum})
    return value


async def _task_for_run(ctx: ToolContext) -> uuid.UUID:
    run_id = getattr(ctx.identity, "run_id", None)
    if not run_id:
        raise ValidationError(
            "artifact.publish 必须在 Flux 任务 Run 内调用（当前调用没有关联 Run）"
        )
    run = await ctx.container.run_repo.get(run_id)
    if run is None or run.task_id is None:
        raise ValidationError(
            "artifact.publish 找不到当前 Run 归属的任务，无法写入对话",
            details={"run_id": run_id},
        )
    return run.task_id


async def _publish_message(ctx: ToolContext, artifact, task_id: uuid.UUID) -> str:
    """把成品写成一条卡片消息；正文只给一句提示，内容本身留在产物详情里。"""
    title = artifact.title
    content = f"已发布{_type_label(artifact.type)}「{title}」，点开查看完整内容"
    data = artifact.to_dict(include_content=False)
    # 与 design_card 的 design_id 同口径：卡片载荷用 artifact_id 而不是裸 id
    payload = {
        "artifact_id": data["id"],
        "type": data["type"],
        "title": data["title"],
        "summary": data["summary"],
        "description": data.get("description") or "",
        "icon": data.get("icon") or "",
        "label": data.get("label") or "",
        "pinned": bool(data.get("pinned")),
        "version": data["version"],
        "metadata": data.get("metadata") or {},
    }
    message = (
        await ctx.container.task_repo.add_messages(
            task_id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="artifact_card",
                    content=content,
                    payload=payload,
                )
            ],
        )
    )[0]
    return str(message.id)


def _type_label(kind: str) -> str:
    return "文档" if kind == "doc" else "页面"


def _conflict_result(exc: ConflictError, artifact_id: str) -> dict[str, Any]:
    """冲突时把当前 live 版本交回给调用方合并，而不是只报 409 编号。"""
    details = exc.details if isinstance(exc.details, dict) else {}
    current = details.get("current") if isinstance(details.get("current"), dict) else {}
    return {
        "published": False,
        "conflict": True,
        "artifact_id": artifact_id,
        "base_version": details.get("base_version"),
        "version": details.get("version") or current.get("version"),
        "title": details.get("title") or current.get("title") or "",
        "content": details.get("content") or current.get("content") or "",
        "description": details.get("description") or current.get("description") or "",
        "icon": details.get("icon") or current.get("icon") or "",
        "label": details.get("label") or current.get("label") or "",
        "capabilities": details.get("capabilities") or current.get("capabilities") or {},
        "contract": details.get("contract") or current.get("contract"),
        "note": (
            "产物已被更新，本次没有发布。请把改动合并到上面的当前正文后重新发布；"
            "只有用户明确要求丢弃这个新版本时才带 force=true。"
        ),
    }


async def artifact_publish(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, _PUBLISH_FIELDS)
    artifact_id = optional_str(params, "artifact_id")
    # 产物要记住自己来自哪段对话，App 详情页才能跳回来源任务；更新时保留既有字段。
    task_id = await _task_for_run(ctx)
    source_meta = {"task_id": str(task_id)}
    pin = _optional_bool(params, "pin")
    fields = {
        "description": optional_str(params, "description"),
        "icon": optional_str(params, "icon"),
        "label": optional_str(params, "label"),
        "contract": optional_str(params, "contract"),
        "capabilities": _capabilities(params),
    }

    if artifact_id is not None:
        if artifact_id.startswith("design:"):
            raise ValidationError("设计稿请用 design.share 发布", details={"param": "artifact_id"})
        content = _content(params, required=False)
        title = optional_str(params, "title")
        base_version = _optional_int(params, "base_version")
        force = _optional_bool(params, "force")
        # 更新时 type 可省略；显式给了就不能和已有产物的类型打架（先校验再落库）
        if params.get("type") is not None:
            existing = await ctx.container.artifacts.get(artifact_id)
            kind = _require_publish_type(params.get("type"))
            if kind != existing.type:
                raise ValidationError(
                    f"产物类型是 {existing.type}，不能按 {kind} 更新",
                    details={"param": "type"},
                )
        try:
            artifact = await ctx.container.artifacts.update(
                artifact_id,
                title=title,
                content=content,
                base_version=base_version,
                force=force,
                meta=source_meta,
                **fields,
            )
        except ConflictError as exc:
            return _conflict_result(exc, artifact_id)
        if pin:
            artifact = await ctx.container.artifacts.set_pinned(artifact_id, True)
        updated = True
    else:
        kind = _require_publish_type(params.get("type"))
        content = _content(params, required=True) or ""
        # title 可省略：page 从 <title> 取，doc 从首个 Markdown 标题回退。
        title = optional_str(params, "title")
        artifact = await ctx.container.artifacts.create(
            type=kind,
            title=title,
            content=content,
            pinned=pin,
            meta=source_meta,
            **fields,
        )
        updated = False

    message_id = await _publish_message(ctx, artifact, task_id)
    return {
        "published": True,
        "updated": updated,
        "artifact_id": str(artifact.id),
        "type": artifact.type,
        "title": artifact.title,
        "description": artifact.description or "",
        "icon": artifact.icon or "",
        "label": artifact.label or "",
        "contract": artifact.contract,
        "capabilities": artifact.capabilities or {},
        "pinned": bool(artifact.pinned),
        "version": artifact.version,
        "message_id": message_id,
        "note": (
            "成品已发布并写入当前对话。产物默认私有，用户点开卡片即可查看；"
            "回复里不要再粘贴完整内容或任何链接。"
        ),
    }


async def artifact_list(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"type", "limit", "page_token"})
    kind = params.get("type")
    if kind is not None:
        kind = _require_publish_type(kind)
    limit = _limit(params, default=25, maximum=100)
    offset = _decode_offset(optional_str(params, "page_token"))
    rows = await ctx.container.artifacts.list(kind)
    total = len(rows)
    page = rows[offset : offset + limit]
    next_offset = offset + limit
    return {
        "count": len(page),
        "total": total,
        "type": kind or "all",
        "artifacts": [row.to_dict(include_content=False) for row in page],
        "next_page_token": _encode_offset(next_offset) if next_offset < total else None,
    }


async def artifact_get(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    # prompt 是官方给「别人的产物」做隔离摘要用的；Flux 没有跨账号共享模型，接受但忽略。
    reject_unknown(params, {"artifact_id", "prompt"})
    artifact_id = require_str(params, "artifact_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿请用 design.get 读取", details={"param": "artifact_id"})
    artifact = await ctx.container.artifacts.get(artifact_id)
    return {"artifact": artifact.to_dict(include_content=True)}


async def artifact_list_types(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, set())
    return {
        "types": list(TYPE_DESCRIPTIONS),
        "note": "page / doc 可发布；design 是设计工作室的只读投影，仍用 design.* 工具。",
    }


async def _set_pin(ctx: ToolContext, params: dict[str, Any], *, pinned: bool) -> dict[str, Any]:
    reject_unknown(params, {"artifact_id"})
    artifact_id = require_str(params, "artifact_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿不支持钉选", details={"param": "artifact_id"})
    artifact = await ctx.container.artifacts.set_pinned(artifact_id, pinned)
    return {
        "action": "pin" if pinned else "unpin",
        "artifact_id": str(artifact.id),
        "title": artifact.title,
        "pinned": bool(artifact.pinned),
        "version": artifact.version,
        "note": "钉选是用户私有标记，不改变产物可见性。",
    }


async def artifact_pin(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    return await _set_pin(ctx, params, pinned=True)


async def artifact_unpin(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    return await _set_pin(ctx, params, pinned=False)


async def artifact_upload_asset(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params, {"artifact_id", "file_name", "content_base64", "content", "content_type"}
    )
    artifact_id = require_str(params, "artifact_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿没有资产库", details={"param": "artifact_id"})
    file_name = require_str(params, "file_name")
    has_base64 = params.get("content_base64") is not None
    has_text = params.get("content") is not None
    if has_base64 == has_text:
        raise ValidationError(
            "content_base64 与 content 必须二选一（二进制用 base64，文本用 content）",
            details={"param": "content_base64"},
        )
    if has_base64:
        encoded = params.get("content_base64")
        if not isinstance(encoded, str):
            raise ValidationError(
                "content_base64 必须是字符串", details={"param": "content_base64"}
            )
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValidationError(
                "content_base64 不是合法的 base64", details={"param": "content_base64"}
            ) from exc
    else:
        text = params.get("content")
        if not isinstance(text, str):
            raise ValidationError("content 必须是字符串", details={"param": "content"})
        data = text.encode("utf-8")
    asset = await ctx.container.artifacts.add_asset(
        artifact_id,
        file_name=file_name,
        content_type=optional_str(params, "content_type"),
        data=data,
    )
    usage = await ctx.container.artifacts.asset_usage(artifact_id)
    return {"asset": asset.to_dict(), "usage": usage}


async def artifact_list_assets(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"artifact_id", "limit", "after"})
    artifact_id = require_str(params, "artifact_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿没有资产库", details={"param": "artifact_id"})
    limit = _limit(params, default=50, maximum=50)
    offset = _decode_offset(optional_str(params, "after"), param="after")
    rows = await ctx.container.artifacts.list_assets(artifact_id)
    usage = await ctx.container.artifacts.asset_usage(artifact_id)
    page = rows[offset : offset + limit]
    next_offset = offset + limit
    return {
        "artifact_id": artifact_id,
        "assets": [row.to_dict() for row in page],
        "usage": usage,
        "next": _encode_offset(next_offset) if next_offset < len(rows) else None,
    }


async def artifact_get_asset(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"artifact_id", "asset_id"})
    artifact_id = require_str(params, "artifact_id")
    asset_id = require_str(params, "asset_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿没有资产库", details={"param": "artifact_id"})
    asset, data = await ctx.container.artifacts.read_asset(artifact_id, asset_id)
    return {
        "asset": asset.to_dict(),
        "encoding": "base64",
        "content_base64": base64.b64encode(data).decode("ascii"),
    }


async def artifact_delete_asset(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"artifact_id", "asset_id"})
    artifact_id = require_str(params, "artifact_id")
    asset_id = require_str(params, "asset_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿没有资产库", details={"param": "artifact_id"})
    asset = await ctx.container.artifacts.delete_asset(artifact_id, asset_id)
    usage = await ctx.container.artifacts.asset_usage(artifact_id)
    return {"asset_id": asset.id.hex, "deleted": True, "usage": usage}


def artifact_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="artifact.publish",
            title="发布页面 / 文档产物",
            description=(
                "把完成的可交互页面（type=page，完整 HTML，用户能点按操作）或 Markdown 文档"
                "（type=doc）发布成 Artifacts 里的成品，并在当前对话写一张可点开的卡片。"
                "若用户要的是画布上的视觉稿、或要改布局 / 样式 / 组件，请用 design.*，"
                "完成后 design.share；不要把设计画布当页面发布。"
                "产物默认私有、只有用户能在 App 内查看：不要在回复正文里粘贴整段 HTML、"
                "文档全文或任何链接。改已有产物时传 artifact_id，并先 artifact.get 取当前"
                "内容与 version；带 base_version 冲突时工具会把当前正文交回，请合并后重发，"
                "只有用户明确要求丢弃新版本时才带 force=true。title 可省略：page 从正文"
                "前 8KB 的 <title> 取，doc 从首个 Markdown 标题回退；有 <title> 时参数不覆盖。"
                "capabilities 是运行时能力声明（空对象清空，省略沿用），contract 是运行时版本，"
                "只存储声明、不执行。type=page 是可交互页面，type=doc 是 Markdown 文档；"
                "设计稿不要用本工具，仍用 design.share。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "type": {
                        "type": "string",
                        "enum": ["page", "doc"],
                        "description": "page = 单文件 HTML；doc = Markdown",
                    },
                    "title": {"type": "string", "description": "产物标题（缺省从正文提取）"},
                    "content": {
                        "type": "string",
                        "description": "完整内容：HTML 源码或 Markdown 正文（新建时必填）",
                    },
                    "artifact_id": {
                        "type": "string",
                        "description": "更新已有产物时传其 id（可空，表示新建）",
                    },
                    "description": {
                        "type": "string",
                        "description": "画廊卡片副标题：一句话说明这个页面是什么",
                    },
                    "icon": {
                        "type": "string",
                        "description": "浏览器标签图标词：chart / calendar / code / map 等短通用词",
                    },
                    "label": {
                        "type": "string",
                        "description": "本次发布的短名，最多 60 字符",
                    },
                    "capabilities": {
                        "type": "object",
                        "description": "运行时能力声明；空对象清空，省略则沿用",
                    },
                    "contract": {
                        "type": "string",
                        "description": "运行时版本：可省略保持，或 'latest'/具体版本；只存储声明",
                    },
                    "base_version": {
                        "type": "integer",
                        "description": "更新时带上读到的 version，用于乐观锁冲突检测",
                    },
                    "force": {
                        "type": "boolean",
                        "description": "仅当用户明确要求丢弃更新版本时覆盖冲突",
                    },
                    "pin": {
                        "type": "boolean",
                        "description": "发布后钉选到用户的私有列表",
                    },
                },
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=artifact_publish,
        ),
        ToolSpec(
            name="artifact.list",
            title="列出已发布产物",
            description=(
                "列出当前账号下的页面 / 文档产物（只读，不含正文），支持 type 筛选与"
                " limit/page_token 分页。开工前先看这里，避免重复创建同一份产物；"
                "设计稿用 design.list。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "type": {
                        "type": "string",
                        "enum": ["page", "doc"],
                        "description": "只看某一类；缺省返回全部",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "最多返回多少条（默认 25，最多 100）",
                    },
                    "page_token": {
                        "type": "string",
                        "description": "上一页返回的 next_page_token",
                    },
                },
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=artifact_list,
        ),
        ToolSpec(
            name="artifact.get",
            title="读取产物内容",
            description=(
                "读取一份页面 / 文档产物的标题、version 与完整内容（只读）。"
                "准备用 artifact.publish 更新前先调它，基于最新内容修改。"
                "prompt 参数为官方兼容保留，当前没有跨账号产物摘要，会被忽略。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string", "description": "产物 id"},
                    "prompt": {
                        "type": "string",
                        "description": "官方摘要参数；本次忽略（产物默认私有）",
                    },
                },
                "required": ["artifact_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=artifact_get,
        ),
        ToolSpec(
            name="artifact.list_types",
            title="列出产物类型",
            description=(
                "列出支持的产物类型：page=可交互的单文件 HTML 页面，doc=Markdown 文档，"
                "design=Luma 设计稿投影（只读聚合；视觉稿的创建仍走 design.*）。"
            ),
            input_schema={"type": "object", "properties": {}, "additionalProperties": False},
            capability=Capability.FILE_READ,
            handler=artifact_list_types,
        ),
        ToolSpec(
            name="artifact.pin",
            title="钉选产物",
            description="把一个页面 / 文档产物钉到用户私有列表；不改变可见性。",
            input_schema={
                "type": "object",
                "properties": {"artifact_id": {"type": "string", "description": "产物 id"}},
                "required": ["artifact_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=artifact_pin,
        ),
        ToolSpec(
            name="artifact.unpin",
            title="取消钉选产物",
            description="取消一个产物在用户私有列表里的钉选。",
            input_schema={
                "type": "object",
                "properties": {"artifact_id": {"type": "string", "description": "产物 id"}},
                "required": ["artifact_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=artifact_unpin,
        ),
        ToolSpec(
            name="artifact.upload_asset",
            title="上传产物资产",
            description=(
                "给一个产物挂图片 / 视频 / PDF / 字体 / CSS / JS / 文本资产。"
                "二进制内容用 content_base64，纯文本用 content，两者二选一；"
                "单个文件 ≤16 MiB，每个产物 ≤50 个且总量 ≤64 MiB。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string", "description": "产物 id"},
                    "file_name": {"type": "string", "description": "原始文件名（含扩展名）"},
                    "content_base64": {
                        "type": "string",
                        "description": "二进制内容的 base64；与 content 二选一",
                    },
                    "content": {
                        "type": "string",
                        "description": "纯文本（CSV / Markdown / JSON / 文本）；与 base64 二选一",
                    },
                    "content_type": {
                        "type": "string",
                        "description": "MIME；缺省按文件名推断",
                    },
                },
                "required": ["artifact_id", "file_name"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=artifact_upload_asset,
        ),
        ToolSpec(
            name="artifact.list_assets",
            title="列出产物资产",
            description=(
                "列出一个产物资产库里的文件与用量（files/bytes 及上限）；after 用于继续上一页。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string", "description": "产物 id"},
                    "limit": {"type": "integer", "description": "最多返回多少条（默认 50）"},
                    "after": {"type": "string", "description": "上一页返回的 next"},
                },
                "required": ["artifact_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=artifact_list_assets,
        ),
        ToolSpec(
            name="artifact.get_asset",
            title="读取产物资产",
            description=(
                "读取一个资产的内容（base64）。当前会话不直接落盘；"
                "需要把内容写进文件时由 Agent 自己解码后处理。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string", "description": "产物 id"},
                    "asset_id": {"type": "string", "description": "32 位 hex 资产 id"},
                },
                "required": ["artifact_id", "asset_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=artifact_get_asset,
        ),
        ToolSpec(
            name="artifact.delete_asset",
            title="删除产物资产",
            description="永久删除一个产物资产；返回删除后的资产用量。",
            input_schema={
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string", "description": "产物 id"},
                    "asset_id": {"type": "string", "description": "32 位 hex 资产 id"},
                },
                "required": ["artifact_id", "asset_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=artifact_delete_asset,
        ),
    )
