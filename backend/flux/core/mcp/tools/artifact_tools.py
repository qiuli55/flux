"""Artifacts 工具：让 Agent 把页面 / 文档发布成对话里的成品卡片。

与 `design.share` 同一套路：产物落 `artifacts` 表，聊天里只写一条 `artifact_card`
结构化消息，手机端据此渲染「类型 + 标题 + 已发布」卡片。产物默认私有，只有
当前账号能在 App 里看到，因此工具不返回任何可分享 URL，也要求 Agent 不要在
回复正文里粘贴整段内容或链接。

设计稿不走这里：设计类产物由 Luma Studio 拥有，发布仍用 `design.share`。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.core.task_engine.repository import MessageDraft
from flux.enums import Capability, TaskMessageRole
from flux.errors import ValidationError

#: 本工具能发布的类型；design 是 Luma 的投影，创建仍走设计工作室。
_PUBLISH_TYPES = {"page", "doc"}


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


async def _publish_message(ctx: ToolContext, artifact) -> str:
    """把成品写成一条卡片消息；正文只给一句提示，内容本身留在产物详情里。"""
    task_id = await _task_for_run(ctx)
    title = artifact.title
    content = f"已发布{_type_label(artifact.type)}「{title}」，点开查看完整内容"
    data = artifact.to_dict(include_content=False)
    # 与 design_card 的 design_id 同口径：卡片载荷用 artifact_id 而不是裸 id
    payload = {
        "artifact_id": data["id"],
        "type": data["type"],
        "title": data["title"],
        "summary": data["summary"],
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


async def artifact_publish(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"type", "title", "content", "artifact_id"})
    artifact_id = optional_str(params, "artifact_id")

    if artifact_id is not None:
        if artifact_id.startswith("design:"):
            raise ValidationError("设计稿请用 design.share 发布", details={"param": "artifact_id"})
        content = _content(params, required=False)
        title = optional_str(params, "title")
        # 更新时 type 可省略；显式给了就不能和已有产物的类型打架（先校验再落库）
        if params.get("type") is not None:
            existing = await ctx.container.artifacts.get(artifact_id)
            kind = _require_publish_type(params.get("type"))
            if kind != existing.type:
                raise ValidationError(
                    f"产物类型是 {existing.type}，不能按 {kind} 更新",
                    details={"param": "type"},
                )
        artifact = await ctx.container.artifacts.update(artifact_id, title=title, content=content)
    else:
        kind = _require_publish_type(params.get("type"))
        title = require_str(params, "title")
        content = _content(params, required=True) or ""
        artifact = await ctx.container.artifacts.create(
            type=kind, title=title[:160], content=content
        )

    message_id = await _publish_message(ctx, artifact)
    return {
        "published": True,
        "artifact_id": str(artifact.id),
        "type": artifact.type,
        "title": artifact.title,
        "version": artifact.version,
        "message_id": message_id,
        "note": (
            "成品已发布并写入当前对话。产物默认私有，用户点开卡片即可查看；"
            "回复里不要再粘贴完整内容或任何链接。"
        ),
    }


async def artifact_list(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"type"})
    kind = params.get("type")
    if kind is not None:
        kind = _require_publish_type(kind)
    rows = await ctx.container.artifacts.list(kind)
    return {
        "count": len(rows),
        "type": kind or "all",
        "artifacts": [row.to_dict(include_content=False) for row in rows],
    }


async def artifact_get(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"artifact_id"})
    artifact_id = require_str(params, "artifact_id")
    if artifact_id.startswith("design:"):
        raise ValidationError("设计稿请用 design.get 读取", details={"param": "artifact_id"})
    artifact = await ctx.container.artifacts.get(artifact_id)
    return {"artifact": artifact.to_dict(include_content=True)}


def artifact_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="artifact.publish",
            title="发布页面 / 文档产物",
            description=(
                "把完成的单文件页面（type=page，完整 HTML）或 Markdown 文档（type=doc）"
                "发布成 Artifacts 里的成品，并在当前对话写一张可点开的卡片。"
                "产物默认私有、只有用户能在 App 内查看：不要在回复正文里粘贴整段 HTML、"
                "文档全文或任何链接。改已有产物时传 artifact_id（先 artifact.get 取当前"
                "内容与 version）。设计稿不要用本工具，仍用 design.share。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "type": {
                        "type": "string",
                        "enum": ["page", "doc"],
                        "description": "page = 单文件 HTML；doc = Markdown",
                    },
                    "title": {"type": "string", "description": "产物标题（新建时必填）"},
                    "content": {
                        "type": "string",
                        "description": "完整内容：HTML 源码或 Markdown 正文（新建时必填）",
                    },
                    "artifact_id": {
                        "type": "string",
                        "description": "更新已有产物时传其 id（可空，表示新建）",
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
                "列出当前账号下的页面 / 文档产物（只读，不含正文）。开工前先看这里，"
                "避免重复创建同一份产物；设计稿用 design.list。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "type": {
                        "type": "string",
                        "enum": ["page", "doc"],
                        "description": "只看某一类；缺省返回全部",
                    }
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
            ),
            input_schema={
                "type": "object",
                "properties": {"artifact_id": {"type": "string", "description": "产物 id"}},
                "required": ["artifact_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=artifact_get,
        ),
    )
