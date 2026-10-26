"""设计工作室工具：让 Agent 的设计产出走 Luma Studio，而不是手写 HTML。

设计稿的权威存储在 Luma Studio（`settings.design_studio_url`，默认 127.0.0.1:8091）。
工具层只做三件事：把参数转成 Luma 的请求、把上游错误翻译成 Flux 领域错误、
把结果整理成 Agent 能直接读的结构。`design.share` 额外把成品写进当前任务对话，
手机端据此渲染可点开的原生画布卡片。

设计稿 id 规则与 Luma / Flux 代理保持一致：小写字母、数字与连字符。
"""

from __future__ import annotations

import re
import uuid
from typing import Any

import httpx

from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.core.task_engine.repository import MessageDraft
from flux.enums import Capability, TaskMessageRole
from flux.errors import AIOSError, ConflictError, NotFoundError, ValidationError

_DESIGN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")

#: 聊天卡片里回带的节点上限：与 Luma `design_summary` 同口径，控制消息体积。
_PREVIEW_NODE_LIMIT = 48


class DesignStudioUnavailable(AIOSError):
    """设计服务不可达或上游故障；Agent 可以稍后重试，不暴露内网地址。"""

    code = "design_studio_unavailable"
    http_status = 503


def _require_design_id(raw: Any) -> str:
    design_id = require_str({"design_id": raw}, "design_id")
    if not _DESIGN_ID_RE.match(design_id):
        raise ValidationError(
            f"设计稿 id 不合法：{design_id}", details={"param": "design_id"}
        )
    return design_id


def _client(ctx: ToolContext) -> httpx.AsyncClient:
    """短连接客户端：与 Flux 代理同一份超时配置，测试可替换本函数。"""
    settings = ctx.container.settings
    timeout = httpx.Timeout(settings.design_studio_timeout_seconds, connect=3.0)
    return httpx.AsyncClient(
        base_url=settings.design_studio_url.rstrip("/"),
        timeout=timeout,
        # 同 designs 代理：本机地址不走系统代理
        trust_env=False,
    )


def _upstream_error(response: httpx.Response) -> AIOSError:
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    message = str(payload.get("error") or "设计服务暂时不可用")
    if response.status_code == 404:
        return NotFoundError(message)
    if response.status_code == 409:
        return ConflictError(
            message,
            code="design_conflict",
            details={"version": payload.get("version")},
        )
    if response.status_code == 429:
        return AIOSError(message, code="rate_limited", http_status=429)
    if response.status_code >= 500:
        return DesignStudioUnavailable(message)
    return ValidationError(message, details={"upstream_status": response.status_code})


async def _request(
    client: httpx.AsyncClient,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    try:
        response = await client.request(method, path, json=payload)
    except httpx.HTTPError as exc:
        raise DesignStudioUnavailable("设计服务连接失败") from exc
    if response.status_code >= 400:
        raise _upstream_error(response)
    try:
        body = response.json()
    except ValueError as exc:
        raise DesignStudioUnavailable("设计服务返回了非 JSON 响应") from exc
    if not isinstance(body, dict) or not body.get("ok"):
        raise DesignStudioUnavailable("设计服务返回了异常响应")
    return body


def _design_meta(params: dict[str, Any]) -> dict[str, Any]:
    """把可选的元数据字段收成 Luma 接受的形状；未传的键不出现。"""
    meta: dict[str, Any] = {}
    for key in ("name", "project", "project_id", "description", "status", "bg", "file"):
        value = optional_str(params, key)
        if value is not None:
            meta[key] = value
    return meta


def _screens(params: dict[str, Any]) -> list[dict[str, Any]] | None:
    screens = params.get("screens")
    if screens is None:
        return None
    if not isinstance(screens, list) or not screens:
        raise ValidationError(
            "screens 必须是非空数组（1–20 个画板）", details={"param": "screens"}
        )
    if len(screens) > 20:
        raise ValidationError("screens 最多 20 个画板", details={"param": "screens"})
    for screen in screens:
        if not isinstance(screen, dict):
            raise ValidationError("每个画板必须是对象", details={"param": "screens"})
        for key in ("id", "name"):
            if not isinstance(screen.get(key), str) or not screen.get(key, "").strip():
                raise ValidationError(
                    f"画板缺少字段 {key}", details={"param": "screens", "field": key}
                )
        for key in ("w", "h"):
            if not isinstance(screen.get(key), (int, float)):
                raise ValidationError(
                    f"画板缺少尺寸字段 {key}", details={"param": "screens", "field": key}
                )
        if not isinstance(screen.get("nodes", []), list):
            raise ValidationError(
                f"画板 {screen.get('id')} 的 nodes 必须是数组", details={"param": "screens"}
            )
    return screens


def _layer_count(nodes: list[Any]) -> int:
    total = 0
    for node in nodes:
        if not isinstance(node, dict):
            continue
        total += 1
        children = node.get("children")
        if isinstance(children, list):
            total += _layer_count(children)
    return total


def _summary(design: dict[str, Any]) -> dict[str, Any]:
    screens = design.get("screens") if isinstance(design.get("screens"), list) else []
    layers = sum(
        _layer_count(screen.get("nodes") or [])
        for screen in screens
        if isinstance(screen, dict)
    )
    return {
        "design_id": design.get("id"),
        "name": design.get("name"),
        "project": design.get("project"),
        "project_id": design.get("project_id"),
        "description": design.get("description"),
        "status": design.get("status"),
        "bg": design.get("bg"),
        "version": design.get("version"),
        "updated_at": design.get("updated_at"),
        "screen_count": len(screens),
        "layer_count": layers,
    }


def _preview(design: dict[str, Any]) -> dict[str, Any] | None:
    """卡片缩略图数据：第一屏的尺寸、底色与前几十个节点。"""
    screens = design.get("screens")
    if not isinstance(screens, list) or not screens:
        return None
    first = screens[0]
    if not isinstance(first, dict):
        return None
    nodes = first.get("nodes")
    return {
        "w": first.get("w") or 390,
        "h": first.get("h") or 844,
        "bg": design.get("bg"),
        "nodes": (nodes if isinstance(nodes, list) else [])[:_PREVIEW_NODE_LIMIT],
    }


async def _project_from_run(ctx: ToolContext) -> str | None:
    run_id = getattr(ctx.identity, "run_id", None)
    if not run_id:
        return None
    run = await ctx.container.run_repo.get(run_id)
    if run is None or run.task_id is None:
        return None
    try:
        task = await ctx.container.task_repo.get(run.task_id)
    except NotFoundError:
        return None
    return str(task.project_id) if task.project_id else None


# ---------------------------------------------------------------- 工具实现


async def design_list(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"project_id"})
    project_id = optional_str(params, "project_id")
    if project_id is None:
        project_id = await _project_from_run(ctx)
    async with _client(ctx) as client:
        body = await _request(client, "GET", "/api/designs")
    designs = body.get("designs") or []
    if not isinstance(designs, list):
        raise DesignStudioUnavailable("设计服务返回了异常列表")
    if project_id is not None:
        designs = [
            item
            for item in designs
            if isinstance(item, dict) and item.get("project_id") == project_id
        ]
    return {"count": len(designs), "project_id": project_id, "designs": designs}


async def design_get(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"design_id"})
    design_id = _require_design_id(params.get("design_id"))
    async with _client(ctx) as client:
        body = await _request(client, "GET", f"/api/designs/{design_id}")
    design = body.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return {"design": design, "summary": _summary(design)}


async def design_create(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params,
        {"name", "project", "project_id", "description", "status", "bg", "file", "screens"},
    )
    name = require_str(params, "name")
    screens = _screens(params)
    meta = _design_meta(params)
    meta["name"] = name
    async with _client(ctx) as client:
        # Luma 的 POST 只能建空白稿：先建，再整份写入画板，避免留下半成品
        created = await _request(client, "POST", "/api/designs", meta)
        blank = created.get("design")
        if not isinstance(blank, dict) or not blank.get("id"):
            raise DesignStudioUnavailable("设计服务没有返回新建设计稿")
        design_id = str(blank["id"])
        if screens is None:
            return {"created": True, "design": blank, "summary": _summary(blank)}
        saved = await _request(
            client,
            "PUT",
            f"/api/designs/{design_id}",
            {
                "base_version": blank.get("version") or 1,
                "design": {**meta, "screens": screens},
            },
        )
    design = saved.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return {"created": True, "design": design, "summary": _summary(design)}


async def design_update(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params,
        {
            "design_id",
            "base_version",
            "name",
            "project",
            "project_id",
            "description",
            "status",
            "bg",
            "file",
            "screens",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    screens = _screens(params)
    meta = _design_meta(params)
    base_version = params.get("base_version")
    if base_version is not None and not isinstance(base_version, int):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})
    async with _client(ctx) as client:
        current = await _request(client, "GET", f"/api/designs/{design_id}")
        design = current.get("design")
        if not isinstance(design, dict):
            raise DesignStudioUnavailable("设计服务返回了异常设计稿")
        merged = {
            "name": meta.get("name") or design.get("name"),
            "project": meta.get("project") or design.get("project"),
            "project_id": meta.get("project_id") or design.get("project_id"),
            "description": meta.get("description") or design.get("description"),
            "status": meta.get("status") or design.get("status"),
            "bg": meta.get("bg") or design.get("bg"),
            "file": meta.get("file") or design.get("file"),
            "screens": screens if screens is not None else design.get("screens") or [],
        }
        saved = await _request(
            client,
            "PUT",
            f"/api/designs/{design_id}",
            {
                "base_version": base_version if base_version is not None else design.get("version"),
                "design": merged,
            },
        )
    updated = saved.get("design")
    if not isinstance(updated, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return {"created": False, "design": updated, "summary": _summary(updated)}


async def _task_for_run(ctx: ToolContext) -> uuid.UUID:
    run_id = getattr(ctx.identity, "run_id", None)
    if not run_id:
        raise ValidationError(
            "design.share 必须在 Flux 任务 Run 内调用（当前调用没有关联 Run）"
        )
    run = await ctx.container.run_repo.get(run_id)
    if run is None or run.task_id is None:
        raise ValidationError(
            "design.share 找不到当前 Run 归属的任务，无法写入对话",
            details={"run_id": run_id},
        )
    return run.task_id


async def design_share(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"design_id", "note"})
    design_id = _require_design_id(params.get("design_id"))
    note = optional_str(params, "note")
    task_id = await _task_for_run(ctx)
    async with _client(ctx) as client:
        body = await _request(client, "GET", f"/api/designs/{design_id}")
    design = body.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")

    summary = _summary(design)
    name = str(design.get("name") or "未命名设计")
    content = note or f"设计稿「{name}」已生成，点开查看原生画布预览"
    payload = {
        **summary,
        "preview": _preview(design),
    }
    message = (
        await ctx.container.task_repo.add_messages(
            task_id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="design_card",
                    content=content,
                    payload=payload,
                )
            ],
        )
    )[0]
    return {
        "shared": True,
        "task_id": str(task_id),
        "message_id": str(message.id),
        "design_id": design_id,
        "name": name,
        "note": "设计稿卡片已写入当前任务对话，用户点开后进入原生画布预览。",
    }


# ---------------------------------------------------------------- 注册


def design_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="design.list",
            title="列出设计稿",
            description=(
                "列出设计工作室里的设计稿（只读）。不传 project_id 时按当前任务所属项目过滤；"
                "设计类任务开工前先看这里，避免重复造稿。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "project_id": {
                        "type": "string",
                        "description": "项目 UUID；缺省从当前 Run 的任务推导",
                    }
                },
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_list,
        ),
        ToolSpec(
            name="design.get",
            title="读取设计稿",
            description=(
                "取整份设计稿（画板与图层），供修改前读取现状。返回的 design 里 screens 是完整"
                "节点树，summary 是画板/图层数与版本。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {
                        "type": "string",
                        "description": "设计稿 id（小写字母/数字/连字符）",
                    }
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_get,
        ),
        ToolSpec(
            name="design.create",
            title="新建设计稿",
            description=(
                "在设计工作室新建设计稿并写入画板。做页面/UI/视觉设计必须走这个工具，不要用"
                "自写 HTML、截图或静态图片代替。screens 里每个画板需要 id、name、w、h，"
                "nodes 是图层数组（text/card/pill/circle/decor 等，坐标与尺寸用数字）。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "设计稿名称"},
                    "project": {"type": "string", "description": "项目显示名，例如「Flux 移动端」"},
                    "project_id": {
                        "type": "string",
                        "description": "关联的 Flux 项目 UUID（可空）",
                    },
                    "description": {"type": "string", "description": "一句话说明，例如「浅色版」"},
                    "bg": {"type": "string", "description": "画布底色，例如 #F4F6F9"},
                    "screens": {
                        "type": "array",
                        "description": "1–20 个画板；省略时只创建一块空白画板",
                        "items": {"type": "object"},
                    },
                },
                "required": ["name"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_create,
        ),
        ToolSpec(
            name="design.update",
            title="更新设计稿",
            description=(
                "整份更新设计稿（传 screens 则替换画板，否则只改元数据）。修改已有设计前先"
                "design.get。带 base_version 做乐观锁；不带时按当前最新版本保存。冲突返回"
                "design_conflict 与当前 version：重新 design.get 后再改，不要覆盖别人的改动。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "base_version": {"type": "integer", "description": "基于哪个版本修改（可空）"},
                    "name": {"type": "string", "description": "新名称（可空）"},
                    "project": {"type": "string", "description": "项目显示名（可空）"},
                    "project_id": {"type": "string", "description": "项目 UUID（可空）"},
                    "description": {"type": "string", "description": "说明（可空）"},
                    "status": {
                        "type": "string",
                        "description": "draft / review / approved 等（可空）",
                    },
                    "bg": {"type": "string", "description": "画布底色（可空）"},
                    "screens": {
                        "type": "array",
                        "description": "替换全部画板；省略时保留现有画板",
                        "items": {"type": "object"},
                    },
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_update,
        ),
        ToolSpec(
            name="design.share",
            title="分享设计稿到对话",
            description=(
                "把设计稿作为卡片消息写进当前任务对话。设计任务收尾时必须调用一次，"
                "用户会在对话里看到可点开的卡片并进入原生画布预览。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "要分享的设计稿 id"},
                    "note": {"type": "string", "description": "卡片上的一句话说明（可空）"},
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_share,
        ),
    )
