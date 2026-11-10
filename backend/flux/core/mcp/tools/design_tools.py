"""设计工作室工具：让 Agent 的设计产出走 Luma Studio，而不是手写 HTML。

设计稿的权威存储在 Luma Studio（`settings.design_studio_url`，默认 127.0.0.1:8091）。
工具层只做三件事：把参数转成 Luma 的请求、把上游错误翻译成 Flux 领域错误、
把结果整理成 Agent 能直接读的结构。`design.share` 额外把成品写进当前任务对话，
手机端据此渲染可点开的原生画布卡片。

设计稿 id 规则与 Luma / Flux 代理保持一致：小写字母、数字与连字符。
"""

from __future__ import annotations

import asyncio
import hashlib
import re
import time
import uuid
from typing import Any
from urllib.parse import urlencode, urlsplit, urlunsplit

import httpx

from flux.core.agent_runtime.manager import AGENT_KIND_EXTERNAL
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
        raise ValidationError(f"设计稿 id 不合法：{design_id}", details={"param": "design_id"})
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
    message = str(payload.get("error") or payload.get("message") or "设计服务暂时不可用")
    # 上游给的原始状态/错误码要透给 Agent：只报「服务暂时不可用」会让它盲目重试
    details: dict[str, Any] = {"upstream_status": response.status_code}
    if payload.get("upstream_status") is not None:
        details["upstream_status"] = payload["upstream_status"]
    if payload.get("upstream_code"):
        details["upstream_code"] = str(payload["upstream_code"])
    if response.status_code == 404:
        return NotFoundError(message, details=details)
    if response.status_code == 409:
        return ConflictError(
            message,
            code="design_conflict",
            details={**details, "version": payload.get("version")},
        )
    if response.status_code == 429:
        return AIOSError(message, code="rate_limited", http_status=429, details=details)
    if response.status_code >= 500:
        return DesignStudioUnavailable(message, details=details)
    return ValidationError(message, details=details)


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
        raise ValidationError("screens 必须是非空数组（1–20 个画板）", details={"param": "screens"})
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
        _layer_count(screen.get("nodes") or []) for screen in screens if isinstance(screen, dict)
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


def _preview(design: dict[str, Any], screen_id: str | None = None) -> dict[str, Any] | None:
    """卡片缩略图数据：指定画板（默认第一屏）的尺寸、底色与前几十个节点。

    改完立刻能拿到「这块画板现在长什么样」的结构快照，不用再跳去设计工作室确认。
    """
    screens = design.get("screens")
    if not isinstance(screens, list) or not screens:
        return None
    screen: Any = None
    if screen_id:
        for item in screens:
            if isinstance(item, dict) and str(item.get("id")) == str(screen_id):
                screen = item
                break
    if not isinstance(screen, dict):
        screen = screens[0]
    if not isinstance(screen, dict):
        return None
    nodes = screen.get("nodes")
    return {
        "screen_id": screen.get("id"),
        "name": screen.get("name"),
        "w": screen.get("w") or 390,
        "h": screen.get("h") or 844,
        "bg": screen.get("bg") or design.get("bg"),
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


# ---------------------------------------------------------------- 视口上下文


def _flatten_nodes(
    nodes: list[Any],
    origin_x: float,
    origin_y: float,
    parent_id: str,
    depth: int,
    out: list[dict[str, Any]],
) -> None:
    """把节点树摊平成「相对画板」的绝对矩形，供可见性与相邻间距计算。"""
    for node in nodes:
        if not isinstance(node, dict) or node.get("hidden"):
            continue
        try:
            x = origin_x + float(node.get("x") or 0)
            y = origin_y + float(node.get("y") or 0)
            w = float(node.get("w") or 0)
            h = float(node.get("h") or 0)
        except (TypeError, ValueError):
            continue
        entry = {
            "id": str(node.get("id") or ""),
            "name": str(node.get("name") or ""),
            "type": str(node.get("type") or ""),
            "x": round(x, 2),
            "y": round(y, 2),
            "w": round(w, 2),
            "h": round(h, 2),
            "parent_id": parent_id,
            "depth": depth,
        }
        out.append(entry)
        children = node.get("children")
        if isinstance(children, list) and depth < 6:
            _flatten_nodes(children, x, y, entry["id"], depth + 1, out)


def _screen_entries(
    design: dict[str, Any], screen_id: str
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    """取目标画板与它所有可见图层的矩形（坐标相对画板左上角）。"""
    screens = design.get("screens") if isinstance(design.get("screens"), list) else []
    screen: dict[str, Any] | None = None
    for item in screens:
        if isinstance(item, dict) and str(item.get("id") or "") == screen_id:
            screen = item
            break
    if screen is None and screens and isinstance(screens[0], dict):
        screen = screens[0]
    if screen is None:
        return None, []
    entries: list[dict[str, Any]] = []
    nodes = screen.get("nodes")
    if isinstance(nodes, list):
        _flatten_nodes(nodes, 0.0, 0.0, "@screen", 0, entries)
    return screen, entries


def _rect(value: Any) -> dict[str, float]:
    value = value if isinstance(value, dict) else {}

    def number(key: str) -> float:
        try:
            return float(value.get(key) or 0)
        except (TypeError, ValueError):
            return 0.0

    return {"x": number("x"), "y": number("y"), "w": number("w"), "h": number("h")}


def _visible_ids(entries: list[dict[str, Any]], viewport: dict[str, float]) -> list[str]:
    """与可视矩形相交的图层 id，按从上到下排序（最多 120 个）。"""
    x0, y0 = viewport["x"], viewport["y"]
    x1, y1 = x0 + viewport["w"], y0 + viewport["h"]
    hits = [
        entry
        for entry in entries
        if entry["w"] > 0
        and entry["h"] > 0
        and entry["x"] < x1
        and entry["x"] + entry["w"] > x0
        and entry["y"] < y1
        and entry["y"] + entry["h"] > y0
    ]
    hits.sort(key=lambda entry: (entry["y"], entry["x"]))
    return [entry["id"] for entry in hits[:120]]


def _neighbor_gaps(
    entry: dict[str, Any], siblings: list[dict[str, Any]]
) -> dict[str, dict[str, Any] | None]:
    """选中图层与同级邻居的间距：Agent 调间距时不用自己算坐标。"""
    x, y, w, h = entry["x"], entry["y"], entry["w"], entry["h"]
    gaps: dict[str, dict[str, Any] | None] = {
        "above": None,
        "below": None,
        "left": None,
        "right": None,
    }
    for other in siblings:
        if other is entry or other["w"] <= 0 or other["h"] <= 0:
            continue
        # 水平方向有重叠才算上下邻居，垂直方向有重叠才算左右邻居
        overlap_x = other["x"] < x + w and other["x"] + other["w"] > x
        overlap_y = other["y"] < y + h and other["y"] + other["h"] > y
        if overlap_x and other["y"] + other["h"] <= y + 0.5:
            gap = y - (other["y"] + other["h"])
            if gaps["above"] is None or gap < gaps["above"]["gap"]:
                gaps["above"] = {"id": other["id"], "gap": round(max(0.0, gap), 1)}
        if overlap_x and other["y"] >= y + h - 0.5:
            gap = other["y"] - (y + h)
            if gaps["below"] is None or gap < gaps["below"]["gap"]:
                gaps["below"] = {"id": other["id"], "gap": round(max(0.0, gap), 1)}
        if overlap_y and other["x"] + other["w"] <= x + 0.5:
            gap = x - (other["x"] + other["w"])
            if gaps["left"] is None or gap < gaps["left"]["gap"]:
                gaps["left"] = {"id": other["id"], "gap": round(max(0.0, gap), 1)}
        if overlap_y and other["x"] >= x + w - 0.5:
            gap = other["x"] - (x + w)
            if gaps["right"] is None or gap < gaps["right"]["gap"]:
                gaps["right"] = {"id": other["id"], "gap": round(max(0.0, gap), 1)}
    return gaps


def _selection_info(
    entries: list[dict[str, Any]],
    selected_ids: list[str],
    screen_w: float,
    screen_h: float,
) -> list[dict[str, Any]]:
    by_parent: dict[str, list[dict[str, Any]]] = {}
    for entry in entries:
        by_parent.setdefault(entry["parent_id"], []).append(entry)
    index = {entry["id"]: entry for entry in entries}
    out: list[dict[str, Any]] = []
    for node_id in selected_ids[:5]:
        entry = index.get(node_id)
        if entry is None:
            continue
        rect = {"x": entry["x"], "y": entry["y"], "w": entry["w"], "h": entry["h"]}
        out.append(
            {
                "id": entry["id"],
                "name": entry["name"],
                "type": entry["type"],
                "parent_id": entry["parent_id"],
                "rect": rect,
                "gaps": _neighbor_gaps(entry, by_parent.get(entry["parent_id"], [])),
                "edges": {
                    "top": round(max(0.0, rect["y"]), 1),
                    "bottom": round(max(0.0, screen_h - rect["y"] - rect["h"]), 1),
                    "left": round(max(0.0, rect["x"]), 1),
                    "right": round(max(0.0, screen_w - rect["x"] - rect["w"]), 1),
                },
            }
        )
    return out


async def design_view(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """读取用户在 Luma 里当前的视口 / 选中，并把可见图层与相邻间距算好。"""
    reject_unknown(params, {"design_id", "wait_ms"})
    design_id = _require_design_id(params.get("design_id"))
    wait_ms = params.get("wait_ms", 1500)
    if isinstance(wait_ms, bool) or not isinstance(wait_ms, int):
        raise ValidationError("wait_ms 必须是整数", details={"param": "wait_ms"})
    wait_ms = max(0, min(wait_ms, 5000))
    async with _client(ctx) as client:
        body = await _request(client, "GET", f"/api/designs/{design_id}/view")
        deadline = time.monotonic() + wait_ms / 1000
        while not body.get("view") and time.monotonic() < deadline:
            await asyncio.sleep(0.2)
            body = await _request(client, "GET", f"/api/designs/{design_id}/view")
        view = body.get("view")
        if not isinstance(view, dict):
            return {
                "design_id": design_id,
                "view": None,
                "hint": (
                    "用户还没打开这块画布，Luma 网页没有上报视口。"
                    "先 design.get 读现状并用绝对坐标改；用户打开画布后再调 design.view。"
                ),
            }
        loaded = await _request(client, "GET", f"/api/designs/{design_id}")
    design = loaded.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    screen, entries = _screen_entries(design, str(view.get("screen_id") or ""))
    if not isinstance(screen, dict):
        raise ValidationError("设计稿还没有画板", details={"param": "design_id"})
    screen_w = float(screen.get("w") or 390)
    screen_h = float(screen.get("h") or 844)
    viewport = _rect(view.get("viewport"))
    selected = _selection_info(entries, list(view.get("selection") or []), screen_w, screen_h)
    visible = bool(view.get("visible", True))
    stale = bool(body.get("stale")) or not visible
    hint_parts = [
        f"用户当前缩放 {round(float(view.get('zoom') or 1) * 100)}%，"
        f"可视区域在画板「{screen.get('name') or screen.get('id')}」内 "
        f"x={round(viewport['x'], 1)}–{round(viewport['x'] + viewport['w'], 1)}, "
        f"y={round(viewport['y'], 1)}–{round(viewport['y'] + viewport['h'], 1)}。"
    ]
    if selected:
        hint_parts.append(
            "已选中 "
            + "、".join(item["id"] for item in selected)
            + "；gaps 是它与同级邻居的间距，直接用 design.patch 微调即可。"
        )
    else:
        hint_parts.append("当前没有选中图层；改样式前先 design.get 取节点 id。")
    if not visible:
        hint_parts.append("用户目前不在画布页（visible=false），位置信息可能已过时。")
    elif stale:
        hint_parts.append("视口上报超过 30 秒，可能不是最新，改稿前可再看一眼或让用户刷新画布。")
    return {
        "design_id": design_id,
        "version": design.get("version"),
        "view": {
            "zoom": view.get("zoom"),
            "screen_id": screen.get("id"),
            "screen": {
                "id": screen.get("id"),
                "name": screen.get("name"),
                "w": screen_w,
                "h": screen_h,
            },
            "viewport": viewport,
            "selection": list(view.get("selection") or []),
            "visible": visible,
            "age_ms": body.get("age_ms"),
            "seq": view.get("seq"),
            "stale": stale,
        },
        "visible_node_ids": _visible_ids(entries, viewport),
        "selection": selected,
        "hint": "".join(hint_parts),
    }


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


def _merge_screens(current: list[Any], updates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按画板 id 合并：同名画板就地替换，新画板追加，未提到的画板原样保留。

    `design_update` 早先是整份覆盖，改一块画板会把其余画板一起冲掉；
    默认走这里按 id 合并，只有显式要求整份替换时才覆盖。
    """
    merged: list[Any] = list(current)
    positions: dict[str, int] = {}
    for pos, screen in enumerate(merged):
        if isinstance(screen, dict):
            positions[str(screen.get("id"))] = pos
    for screen in updates:
        key = str(screen.get("id"))
        if key in positions:
            merged[positions[key]] = screen
        else:
            positions[key] = len(merged)
            merged.append(screen)
    return merged


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
            "replace_screens",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    screens = _screens(params)
    meta = _design_meta(params)
    base_version = params.get("base_version")
    if base_version is not None and not isinstance(base_version, int):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})
    replace_screens = params.get("replace_screens", False)
    if not isinstance(replace_screens, bool):
        raise ValidationError("replace_screens 必须是布尔值", details={"param": "replace_screens"})
    async with _client(ctx) as client:
        current = await _request(client, "GET", f"/api/designs/{design_id}")
        design = current.get("design")
        if not isinstance(design, dict):
            raise DesignStudioUnavailable("设计服务返回了异常设计稿")
        if screens is None:
            merged_screens = design.get("screens") or []
        elif replace_screens:
            merged_screens = screens
        else:
            merged_screens = _merge_screens(design.get("screens") or [], screens)
        merged = {
            "name": meta.get("name") or design.get("name"),
            "project": meta.get("project") or design.get("project"),
            "project_id": meta.get("project_id") or design.get("project_id"),
            "description": meta.get("description") or design.get("description"),
            "status": meta.get("status") or design.get("status"),
            "bg": meta.get("bg") or design.get("bg"),
            "file": meta.get("file") or design.get("file"),
            "screens": merged_screens,
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
    result: dict[str, Any] = {"created": False, "design": updated, "summary": _summary(updated)}
    if screens is not None:
        result["mode"] = "replace" if replace_screens else "merge"
        result["updated_screens"] = [str(screen.get("id")) for screen in screens]
        result["preview"] = _preview(updated, str(screens[0].get("id")))
    return result


# ---------------------------------------------------------------- AI 局部修改


def _find_node(nodes: list[Any], node_id: str) -> tuple[list[Any], int, dict[str, Any]] | None:
    """在图层树里定位节点，返回 (父数组, 下标, 节点)。"""
    for index, node in enumerate(nodes):
        if not isinstance(node, dict):
            continue
        if node.get("id") == node_id:
            return nodes, index, node
        children = node.get("children")
        if isinstance(children, list):
            hit = _find_node(children, node_id)
            if hit is not None:
                return hit
    return None


def _nodes_by_id(design: dict[str, Any], node_ids: list[str], limit: int = 20) -> list[dict]:
    """按 id 回捞节点，用于增量修改的返回值（不回整份设计稿，省 token）。"""
    screens = design.get("screens") if isinstance(design.get("screens"), list) else []
    found: list[dict] = []
    for node_id in node_ids:
        for screen in screens:
            if not isinstance(screen, dict):
                continue
            nodes = screen.get("nodes")
            if not isinstance(nodes, list):
                continue
            hit = _find_node(nodes, node_id)
            if hit is not None:
                found.append(hit[2])
                break
        if len(found) >= limit:
            break
    return found


async def _commit_ops(
    client: httpx.AsyncClient,
    design_id: str,
    design: dict[str, Any],
    screen_id: str,
    ops: list[dict[str, Any]],
    label: str,
    base_version: int | None,
) -> dict[str, Any]:
    """把 ops 提交到 Luma 的 commits 通道：服务端在最新稿上重放，只提交意图。"""
    committed = await _request(
        client,
        "POST",
        f"/api/designs/{design_id}/commits",
        {
            "author": "flux-agent",
            "role": "agent",
            "label": label[:120],
            "screen": screen_id,
            "base_version": base_version if base_version is not None else design.get("version"),
            "ops": ops,
        },
    )
    updated = committed.get("design")
    if not isinstance(updated, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    applied = committed.get("applied")
    applied = applied if isinstance(applied, int) and not isinstance(applied, bool) else 0
    skipped = committed.get("skipped")
    deferred = committed.get("deferred")
    touched = committed.get("touched")
    return {
        "changed": applied > 0,
        "design_id": design_id,
        "screen_id": screen_id,
        "version": updated.get("version"),
        "applied": applied,
        "skipped": (skipped if isinstance(skipped, list) else [])[:5],
        "deferred": (deferred if isinstance(deferred, list) else [])[:5],
        "rebased": bool(committed.get("rebased")),
        "touched": touched if isinstance(touched, list) else [],
        "nodes": _nodes_by_id(updated, touched if isinstance(touched, list) else []),
        "preview": _preview(updated, screen_id),
        "summary": _summary(updated),
    }


def _pick_screen(design: dict[str, Any], params: dict[str, Any]) -> tuple[dict[str, Any], int]:
    screens = design.get("screens")
    if not isinstance(screens, list) or not screens:
        raise ValidationError("设计稿没有任何画板，AI 无从下手")
    screen_id = optional_str(params, "screen_id")
    if screen_id is not None:
        for index, screen in enumerate(screens):
            if isinstance(screen, dict) and screen.get("id") == screen_id:
                return screen, index
        raise ValidationError(f"画板不存在：{screen_id}", details={"param": "screen_id"})
    index = params.get("screen_index", 0)
    if isinstance(index, bool) or not isinstance(index, int):
        raise ValidationError("screen_index 必须是整数", details={"param": "screen_index"})
    if index < 0 or index >= len(screens):
        raise ValidationError(
            f"screen_index 越界：{index}（共 {len(screens)} 块画板）",
            details={"param": "screen_index"},
        )
    screen = screens[index]
    if not isinstance(screen, dict):
        raise DesignStudioUnavailable("设计服务返回了异常画板")
    return screen, index


def _selected_ids(params: dict[str, Any]) -> list[str]:
    raw = params.get("selected_ids")
    if raw is None:
        return []
    if not isinstance(raw, list) or len(raw) > 40:
        raise ValidationError(
            "selected_ids 必须是最多 40 个 id 的数组", details={"param": "selected_ids"}
        )
    ids: list[str] = []
    for item in raw:
        if not isinstance(item, str) or not item.strip():
            raise ValidationError(
                "selected_ids 里的每一项都必须是非空字符串",
                details={"param": "selected_ids"},
            )
        ids.append(item.strip())
    return ids


_AI_MODES = {"auto", "node", "multi", "screen"}


async def design_ai(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """对设计稿做局部 AI 修改：只改当前画板，服务端应用 ops 并落盘。"""
    if ctx.identity.kind == AGENT_KIND_EXTERNAL:
        raise ValidationError(
            "外部接入的 Agent 不支持 design.ai：请用 design.patch / design.layout 自行修改画布",
            details={"tool": "design.ai", "kind": ctx.identity.kind},
        )
    reject_unknown(
        params,
        {
            "design_id",
            "instruction",
            "screen_index",
            "screen_id",
            "selected_ids",
            "mode",
            "base_version",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    instruction = require_str(params, "instruction").strip()
    if len(instruction) > 2000:
        raise ValidationError(
            "instruction 太长，控制在 2000 字内", details={"param": "instruction"}
        )
    selected_ids = _selected_ids(params)
    mode = optional_str(params, "mode") or "auto"
    if mode not in _AI_MODES:
        raise ValidationError(f"mode 只能是 {sorted(_AI_MODES)}", details={"param": "mode"})
    base_version = params.get("base_version")
    if base_version is not None and (
        isinstance(base_version, bool) or not isinstance(base_version, int)
    ):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})

    # 模型通道由调用方 Agent 决定：自身 runtime 的默认通道 + 密钥库，不读全局默认路由
    try:
        target = await ctx.container.completion_resolver.resolve(agent_id=ctx.identity.agent_id)
    except AIOSError as exc:
        raise AIOSError(
            f"无法为 Agent {ctx.identity.name or ctx.identity.agent_id} 解析可用的模型："
            f"{exc.message}",
            code="ai_step_failed",
            http_status=502,
            details={"agent_id": ctx.identity.agent_id, "cause": exc.message},
        ) from exc

    async with _client(ctx) as client:
        current = await _request(client, "GET", f"/api/designs/{design_id}")
        design = current.get("design")
        if not isinstance(design, dict):
            raise DesignStudioUnavailable("设计服务返回了异常设计稿")
        screen, screen_index = _pick_screen(design, params)
        nodes = screen.get("nodes")
        nodes = nodes if isinstance(nodes, list) else []
        selected: list[dict[str, Any]] = []
        missing: list[str] = []
        for node_id in selected_ids:
            hit = _find_node(nodes, node_id)
            if hit is None:
                missing.append(node_id)
            else:
                selected.append(hit[2])
        if missing:
            raise ValidationError(
                f"选中的图层不在该画板：{'、'.join(missing[:5])}",
                details={"param": "selected_ids"},
            )
        if mode == "auto":
            mode = "screen" if not selected_ids else ("multi" if len(selected_ids) > 1 else "node")
        if mode != "screen" and not selected:
            raise ValidationError(
                "mode=node/multi 时必须传 selected_ids", details={"param": "selected_ids"}
            )
        if not nodes:
            raise ValidationError("该画板还没有图层，先用 design.update 搭出版式再让 AI 调")

        try:
            asked = await _request(
                client,
                "POST",
                "/api/ask",
                {
                    "prompt": instruction,
                    "mode": mode,
                    "nodes": selected,
                    # 调用方 Agent 与解析出的模型通道：Luma 侧的 AI 修改不再用自身默认路由
                    "agent_id": ctx.identity.agent_id,
                    "model": target.model,
                    "key_id": target.key_id or "",
                    # design id 供 Luma 建立多轮修改记录；bg 供其按底色选择深/浅基调
                    "design": design_id,
                    "screen": {
                        "id": screen.get("id"),
                        "name": screen.get("name") or "",
                        "w": screen.get("w") or 390,
                        "h": screen.get("h") or 844,
                        "bg": design.get("bg") or "",
                        "nodes": nodes,
                    },
                },
            )
        except DesignStudioUnavailable as exc:
            # /api/ask 的 502 多半是「AI 连续两次没给出可应用修改」这类可重试失败，
            # 不是设计服务宕机：翻译成 ai_step_failed，让 Agent 看到原因并重试。
            raise AIOSError(
                str(exc), code="ai_step_failed", http_status=502, details=exc.details
            ) from exc
        ops = asked.get("ops")
        if not isinstance(ops, list) or not ops:
            raise ValidationError("AI 没有给出可应用的修改，换个说法或缩小改动范围再试")
        # 提交意图而不是整稿：服务端在最新稿上重放 ops，人同时在改也不会被覆盖
        committed = await _request(
            client,
            "POST",
            f"/api/designs/{design_id}/commits",
            {
                "author": "flux-agent",
                "role": "agent",
                "label": instruction[:120],
                "screen": screen.get("id") or "",
                "base_version": base_version if base_version is not None else design.get("version"),
                "ops": ops,
            },
        )
    updated = committed.get("design")
    if not isinstance(updated, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    applied = committed.get("applied")
    applied = applied if isinstance(applied, int) and not isinstance(applied, bool) else 0
    skipped = committed.get("skipped") if isinstance(committed.get("skipped"), list) else []
    deferred = committed.get("deferred") if isinstance(committed.get("deferred"), list) else []
    if applied == 0:
        raise ValidationError(
            "AI 返回的修改无法应用到当前画板，请重试",
            details={"skipped": skipped[:5], "deferred": deferred[:5]},
        )
    return {
        "changed": True,
        "design_id": design_id,
        "screen_index": screen_index,
        "screen_id": screen.get("id"),
        "version": updated.get("version"),
        "applied": applied,
        "skipped": skipped[:5],
        "deferred": deferred[:5],
        "rebased": bool(committed.get("rebased")),
        "ops": ops,
        "reply": asked.get("reply"),
        "mode": asked.get("mode") or mode,
        "model": asked.get("model"),
        "preview": _preview(updated, str(screen.get("id") or "")),
        "summary": _summary(updated),
        "design": updated,
    }


# ---------------------------------------------------------------- 增量修改 / 布局


_PATCH_MAX_CHANGES = 200
_PATCH_MAX_OPS = 400
_LAYOUT_MAX_NODES = 50
_LAYOUT_ACTIONS = {
    "align-left",
    "align-right",
    "align-top",
    "align-bottom",
    "align-h-center",
    "align-v-center",
    "distribute-h",
    "distribute-v",
    "stack-v",
    "stack-h",
    "equal-width",
    "equal-height",
}


def _require_optional_version(params: dict[str, Any]) -> int | None:
    base_version = params.get("base_version")
    if base_version is not None and (
        isinstance(base_version, bool) or not isinstance(base_version, int)
    ):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})
    return base_version


def _num(value: Any, default: float) -> float:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    return default


def _layout_patches(
    action: str,
    boxes: list[tuple[float, float, float, float]],
    screen_w: float,
    screen_h: float,
    gap: float,
    align_to: str,
    size: float | None,
) -> list[dict[str, float]]:
    """按动作算出每个节点的目标坐标；只返回真正变化的字段。"""
    count = len(boxes)
    xs = [box[0] for box in boxes]
    ys = [box[1] for box in boxes]
    ws = [box[2] for box in boxes]
    hs = [box[3] for box in boxes]
    targets: list[dict[str, float]] = [{} for _ in range(count)]
    if action == "align-left":
        target = 0.0 if align_to == "screen" else min(xs)
        for i in range(count):
            targets[i]["x"] = target
    elif action == "align-right":
        target = (
            screen_w if align_to == "screen" else max(x + w for x, w in zip(xs, ws, strict=True))
        )
        for i in range(count):
            targets[i]["x"] = target - ws[i]
    elif action == "align-h-center":
        center = (
            screen_w / 2
            if align_to == "screen"
            else (min(xs) + max(x + w for x, w in zip(xs, ws, strict=True))) / 2
        )
        for i in range(count):
            targets[i]["x"] = center - ws[i] / 2
    elif action == "align-top":
        target = 0.0 if align_to == "screen" else min(ys)
        for i in range(count):
            targets[i]["y"] = target
    elif action == "align-bottom":
        target = (
            screen_h if align_to == "screen" else max(y + h for y, h in zip(ys, hs, strict=True))
        )
        for i in range(count):
            targets[i]["y"] = target - hs[i]
    elif action == "align-v-center":
        center = (
            screen_h / 2
            if align_to == "screen"
            else (min(ys) + max(y + h for y, h in zip(ys, hs, strict=True))) / 2
        )
        for i in range(count):
            targets[i]["y"] = center - hs[i] / 2
    elif action in ("distribute-h", "distribute-v"):
        if count < 3:
            raise ValidationError("distribute 至少需要 3 个图层", details={"param": "node_ids"})
        positions = xs if action == "distribute-h" else ys
        sizes = ws if action == "distribute-h" else hs
        axis = "x" if action == "distribute-h" else "y"
        order = sorted(range(count), key=lambda i: positions[i])
        span = (positions[order[-1]] + sizes[order[-1]]) - positions[order[0]]
        step = (span - sum(sizes[i] for i in order)) / (count - 1)
        cursor = positions[order[0]]
        for i in order:
            targets[i][axis] = cursor
            cursor += sizes[i] + step
    elif action in ("stack-v", "stack-h"):
        vertical = action == "stack-v"
        axis = "y" if vertical else "x"
        sizes = hs if vertical else ws
        cursor = min(ys) if vertical else min(xs)
        for i in range(count):
            targets[i][axis] = cursor
            cursor += sizes[i] + gap
    elif action == "equal-width":
        target = size if size is not None else max(ws)
        for i in range(count):
            targets[i]["w"] = target
    elif action == "equal-height":
        target = size if size is not None else max(hs)
        for i in range(count):
            targets[i]["h"] = target
    patches: list[dict[str, float]] = []
    for i, target in enumerate(targets):
        current = {"x": xs[i], "y": ys[i], "w": ws[i], "h": hs[i]}
        patches.append(
            {
                key: round(value, 2)
                for key, value in target.items()
                if abs(value - current[key]) > 0.01
            }
        )
    return patches


async def design_patch(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """按节点 id 批量改样式，或提交原生 ops；只提交改动，不覆盖整份画板。"""
    reject_unknown(
        params,
        {"design_id", "screen_id", "screen_index", "changes", "ops", "label", "base_version"},
    )
    design_id = _require_design_id(params.get("design_id"))
    changes = params.get("changes")
    raw_ops = params.get("ops")
    if changes is None and raw_ops is None:
        raise ValidationError("changes 和 ops 至少给一个", details={"param": "changes"})
    ops: list[dict[str, Any]] = []
    if changes is not None:
        if not isinstance(changes, list) or not changes:
            raise ValidationError("changes 必须是非空数组", details={"param": "changes"})
        if len(changes) > _PATCH_MAX_CHANGES:
            raise ValidationError(
                f"changes 最多 {_PATCH_MAX_CHANGES} 条", details={"param": "changes"}
            )
        for index, item in enumerate(changes):
            if not isinstance(item, dict):
                raise ValidationError(f"changes[{index}] 必须是对象", details={"param": "changes"})
            target = item.get("id") or item.get("target")
            if not isinstance(target, str) or not target.strip():
                raise ValidationError(f"changes[{index}] 缺少节点 id", details={"param": "changes"})
            patch = item.get("set")
            if not isinstance(patch, dict) or not patch:
                raise ValidationError(
                    f"changes[{index}].set 必须是非空对象", details={"param": "changes"}
                )
            ops.append({"op": "set", "target": target.strip(), "set": patch})
    if raw_ops is not None:
        if not isinstance(raw_ops, list) or not raw_ops:
            raise ValidationError("ops 必须是非空数组", details={"param": "ops"})
        for index, item in enumerate(raw_ops):
            if not isinstance(item, dict) or not isinstance(item.get("op"), str):
                raise ValidationError(f"ops[{index}] 必须是带 op 的对象", details={"param": "ops"})
            ops.append(item)
    if len(ops) > _PATCH_MAX_OPS:
        raise ValidationError(f"单次最多 {_PATCH_MAX_OPS} 条操作", details={"param": "ops"})
    base_version = _require_optional_version(params)
    label = optional_str(params, "label") or "增量修改"
    async with _client(ctx) as client:
        current = await _request(client, "GET", f"/api/designs/{design_id}")
        design = current.get("design")
        if not isinstance(design, dict):
            raise DesignStudioUnavailable("设计服务返回了异常设计稿")
        screen, _ = _pick_screen(design, params)
        result = await _commit_ops(
            client,
            design_id,
            design,
            str(screen.get("id") or ""),
            ops,
            label,
            base_version,
        )
    if not result["changed"]:
        raise ValidationError(
            "没有任何修改生效：节点 id 不存在或字段不在白名单",
            details={"skipped": result["skipped"], "deferred": result["deferred"]},
        )
    return result


async def design_layout(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """对齐 / 分布 / 堆叠 / 等宽等高：服务端算好坐标再提交，不用整份覆盖。"""
    reject_unknown(
        params,
        {
            "design_id",
            "screen_id",
            "screen_index",
            "node_ids",
            "action",
            "gap",
            "align_to",
            "size",
            "label",
            "base_version",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    action = optional_str(params, "action") or ""
    if action not in _LAYOUT_ACTIONS:
        raise ValidationError(
            f"action 只能是 {sorted(_LAYOUT_ACTIONS)}", details={"param": "action"}
        )
    raw_ids = params.get("node_ids")
    if not isinstance(raw_ids, list) or len(raw_ids) < 2:
        raise ValidationError(
            "node_ids 必须是至少 2 个图层 id 的数组", details={"param": "node_ids"}
        )
    if len(raw_ids) > _LAYOUT_MAX_NODES:
        raise ValidationError(
            f"node_ids 最多 {_LAYOUT_MAX_NODES} 个", details={"param": "node_ids"}
        )
    node_ids: list[str] = []
    for item in raw_ids:
        if not isinstance(item, str) or not item.strip():
            raise ValidationError(
                "node_ids 里每一项都必须是非空字符串", details={"param": "node_ids"}
            )
        node_ids.append(item.strip())
    gap = params.get("gap", 12)
    if isinstance(gap, bool) or not isinstance(gap, (int, float)):
        raise ValidationError("gap 必须是数字", details={"param": "gap"})
    align_to = optional_str(params, "align_to") or "selection"
    if align_to not in {"selection", "screen"}:
        raise ValidationError("align_to 只能是 selection 或 screen", details={"param": "align_to"})
    size = params.get("size")
    if size is not None and (
        isinstance(size, bool) or not isinstance(size, (int, float)) or float(size) <= 0
    ):
        raise ValidationError("size 必须是正数", details={"param": "size"})
    base_version = _require_optional_version(params)
    label = optional_str(params, "label") or f"布局：{action}"
    async with _client(ctx) as client:
        current = await _request(client, "GET", f"/api/designs/{design_id}")
        design = current.get("design")
        if not isinstance(design, dict):
            raise DesignStudioUnavailable("设计服务返回了异常设计稿")
        screen, screen_index = _pick_screen(design, params)
        nodes = screen.get("nodes")
        nodes = nodes if isinstance(nodes, list) else []
        hits: list[tuple[list[Any], int, dict[str, Any]]] = []
        missing: list[str] = []
        for node_id in node_ids:
            hit = _find_node(nodes, node_id)
            if hit is None:
                missing.append(node_id)
            else:
                hits.append(hit)
        if missing:
            raise ValidationError(
                f"图层不在该画板：{'、'.join(missing[:5])}", details={"param": "node_ids"}
            )
        parent = hits[0][0]
        if any(hit[0] is not parent for hit in hits[1:]):
            raise ValidationError(
                "layout 只支持同一父级下的图层；跨分组请先解组或分批调整",
                details={"param": "node_ids"},
            )
        boxes = [
            (
                _num(node.get("x"), 0.0),
                _num(node.get("y"), 0.0),
                _num(node.get("w"), 1.0),
                _num(node.get("h"), 1.0),
            )
            for _, _, node in hits
        ]
        patches = _layout_patches(
            action,
            boxes,
            _num(screen.get("w"), 390.0),
            _num(screen.get("h"), 844.0),
            float(gap),
            align_to,
            float(size) if size is not None else None,
        )
        ops = [
            {"op": "set", "target": str(hits[i][2].get("id") or ""), "set": patch}
            for i, patch in enumerate(patches)
            if patch
        ]
        if not ops:
            raise ValidationError("当前已经是目标状态，无需调整")
        result = await _commit_ops(
            client,
            design_id,
            design,
            str(screen.get("id") or ""),
            ops,
            label,
            base_version,
        )
    if not result["changed"]:
        raise ValidationError(
            "布局没有生效，请检查图层是否被锁定",
            details={"skipped": result["skipped"], "deferred": result["deferred"]},
        )
    result["screen_index"] = screen_index
    result["patches"] = [{"id": op["target"], "set": op["set"]} for op in ops[:_LAYOUT_MAX_NODES]]
    return result


async def design_revisions(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"design_id"})
    design_id = _require_design_id(params.get("design_id"))
    async with _client(ctx) as client:
        body = await _request(client, "GET", f"/api/designs/{design_id}/revisions")
    revisions = body.get("revisions") or []
    if not isinstance(revisions, list):
        raise DesignStudioUnavailable("设计服务返回了异常历史列表")
    return {"design_id": design_id, "count": len(revisions), "revisions": revisions}


async def design_revert(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"design_id", "version", "base_version"})
    design_id = _require_design_id(params.get("design_id"))
    version = params.get("version")
    if isinstance(version, bool) or not isinstance(version, int) or version <= 0:
        raise ValidationError("version 必须是正整数", details={"param": "version"})
    base_version = params.get("base_version")
    if base_version is not None and (
        isinstance(base_version, bool) or not isinstance(base_version, int)
    ):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})
    payload: dict[str, Any] = {"version": version}
    if base_version is not None:
        payload["base_version"] = base_version
    async with _client(ctx) as client:
        restored = await _request(client, "POST", f"/api/designs/{design_id}/revert", payload)
    design = restored.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return {
        "reverted": True,
        "design": design,
        "summary": _summary(design),
        "version": design.get("version"),
    }


# ---------------------------------------------------------------- 组件 / 模板
#
# 组件库与模板库由 Luma Studio 维护：组件是参数化节点子树，模板是整屏画板。
# 绑定（把蓝图 + 参数展开成节点树）只在服务端做一份，人和 Agent 共用同一结果，
# 所以这里不复制任何占位符逻辑，只负责「取目录 → 绑定 → 生成 ops → 走 commits」。

_ASSET_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
#: 实例根节点 id 即绑定前缀：服务端按它确定性生成整棵子树的 id。
_INSTANCE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$")
_TEMPLATE_MODES = {"new-screen", "replace-screen"}
_COMPONENT_SCOPES = {"instance", "all"}


def _require_asset_id(raw: Any) -> str:
    asset_id = require_str({"asset_id": raw}, "asset_id")
    if not _ASSET_ID_RE.match(asset_id):
        raise ValidationError(f"组件 id 不合法：{asset_id}", details={"param": "asset_id"})
    return asset_id


def _instance_prefix(raw: Any) -> str:
    """实例根 id：Agent 可指定（≤20 字符），否则按 inst-xxxxxx 生成。"""
    if raw is None:
        return "inst-" + uuid.uuid4().hex[:6]
    if not isinstance(raw, str) or not _INSTANCE_ID_RE.match(raw.strip()):
        raise ValidationError(
            "node_id 只允许字母/数字/下划线/连字符，最长 20 字符",
            details={"param": "node_id"},
        )
    return raw.strip()


def _params_object(raw: Any) -> dict[str, Any]:
    """组件参数覆盖项：键是组件声明的参数名，未知键由服务端忽略。"""
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ValidationError("params 必须是对象（参数名 → 值）", details={"param": "params"})
    return dict(raw)


def _size(raw: Any, fallback: float = 0.0) -> float:
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return fallback
    return float(raw)


def _round(raw: Any, fallback: float = 0.0) -> int:
    return int(round(_size(raw, fallback)))


def _index_param(raw: Any, upper: int) -> int:
    if isinstance(raw, bool) or not isinstance(raw, int):
        raise ValidationError("index 必须是整数", details={"param": "index"})
    return max(0, min(raw, upper))


def _stack_y(nodes: list[Any]) -> int:
    """新组件的默认纵坐标：叠在现有顶层图层下方，至少留 96 的头部空间。"""
    bottom = 96
    for node in nodes:
        if not isinstance(node, dict) or node.get("hidden"):
            continue
        bottom = max(bottom, _round(_size(node.get("y")) + _size(node.get("h")) + 16))
    return bottom


def _profile(asset: dict[str, Any], kind: str) -> dict[str, Any]:
    """目录里的一项 → Agent 可读摘要（不含蓝图节点树，控制上下文体积）。"""
    params: list[dict[str, Any]] = []
    for spec in asset.get("params") or []:
        if isinstance(spec, dict) and isinstance(spec.get("key"), str):
            params.append(
                {
                    "key": spec["key"],
                    "label": spec.get("label") or spec["key"],
                    "type": spec.get("type") or "text",
                    "default": spec.get("default"),
                }
            )
    profile: dict[str, Any] = {
        "asset_id": asset.get("id"),
        "name": asset.get("name"),
        "group": asset.get("group"),
        "description": asset.get("desc"),
        "params": params,
    }
    if kind == "template":
        screen = asset.get("screen") if isinstance(asset.get("screen"), dict) else {}
        profile["w"] = screen.get("w")
        profile["h"] = screen.get("h")
        profile["layer_count"] = _layer_count(screen.get("nodes") or [])
    else:
        node = asset.get("node") if isinstance(asset.get("node"), dict) else {}
        profile["layer_count"] = _layer_count([node]) if node else 0
        variants = [
            {"id": item["id"], "name": item.get("name") or item["id"]}
            for item in asset.get("variants") or []
            if isinstance(item, dict) and isinstance(item.get("id"), str)
        ]
        if variants:
            profile["variants"] = variants
            profile["default_variant"] = variants[0]["id"]
    return profile


async def _bind_asset(
    client: httpx.AsyncClient,
    asset_id: str,
    kind: str,
    params: dict[str, Any],
    prefix: str,
    variant: str | None = None,
) -> dict[str, Any]:
    """调用服务端 binder：返回绑定结果、生效参数与生效变体。"""
    payload: dict[str, Any] = {
        "asset_id": asset_id,
        "kind": kind,
        "params": params,
        "prefix": prefix,
    }
    if variant:
        payload["variant"] = variant
    body = await _request(client, "POST", "/api/components/render", payload)
    bound = body.get("node") if kind == "component" else body.get("screen")
    if not isinstance(bound, dict):
        raise DesignStudioUnavailable("设计服务返回了异常组件实例")
    merged = body.get("params") if isinstance(body.get("params"), dict) else params
    return {"bound": bound, "params": merged, "variant": body.get("variant")}


def _find_node_anywhere(
    design: dict[str, Any], node_id: str
) -> tuple[dict[str, Any], tuple[list[Any], int, dict[str, Any]]] | None:
    screens = design.get("screens")
    if not isinstance(screens, list):
        return None
    for screen in screens:
        if not isinstance(screen, dict):
            continue
        hit = _find_node(screen.get("nodes") or [], node_id)
        if hit is not None:
            return screen, hit
    return None


def _custom_params(
    origin: dict[str, Any], fallback: dict[str, Any] | None = None
) -> dict[str, Any]:
    """实例上用户真正改过的参数（与 origin.defaults 不同者）。

    切变体时只携带这些值，其余交给新变体默认，避免旧变体默认覆盖新变体默认。
    老实例没有 defaults 时用组件级默认（fallback）兜底，再不行则全部视为默认。
    """
    params = origin.get("params") if isinstance(origin.get("params"), dict) else {}
    defaults = origin.get("defaults")
    if not isinstance(defaults, dict):
        defaults = fallback if isinstance(fallback, dict) else params
    return {key: value for key, value in params.items() if value != defaults.get(key)}


async def _asset_param_defaults(client: httpx.AsyncClient, asset_id: str) -> dict[str, Any]:
    """组件级参数默认值（用于兼容没有 origin.defaults 的老实例）。"""
    try:
        body = await _request(client, "GET", "/api/components")
    except Exception:
        return {}
    for item in body.get("components") or []:
        if not isinstance(item, dict) or item.get("id") != asset_id:
            continue
        out: dict[str, Any] = {}
        for spec in item.get("params") or []:
            if isinstance(spec, dict) and isinstance(spec.get("key"), str):
                out[spec["key"]] = spec.get("default")
        return out
    return {}


def _instances_of(design: dict[str, Any], asset_id: str) -> list[str]:
    """收集全稿同源实例的根 id（含嵌套图层）。"""
    ids: list[str] = []

    def walk(nodes: list[Any]) -> None:
        for node in nodes:
            if not isinstance(node, dict):
                continue
            origin = node.get("origin")
            if isinstance(origin, dict) and origin.get("assetId") == asset_id:
                ids.append(str(node.get("id")))
            children = node.get("children")
            if isinstance(children, list):
                walk(children)

    for screen in design.get("screens") or []:
        if isinstance(screen, dict):
            nodes = screen.get("nodes")
            walk(nodes if isinstance(nodes, list) else [])
    return ids


def _collect_nodes(node: Any, out: dict[str, dict[str, Any]], order: list[str]) -> None:
    if not isinstance(node, dict):
        return
    node_id = node.get("id")
    if isinstance(node_id, str):
        out[node_id] = node
        order.append(node_id)
    children = node.get("children")
    if isinstance(children, list):
        for child in children:
            _collect_nodes(child, out, order)


def _locate_child(node: dict[str, Any], node_id: str) -> tuple[str | None, int] | None:
    children = node.get("children")
    if not isinstance(children, list):
        return None
    for index, child in enumerate(children):
        if not isinstance(child, dict):
            continue
        if child.get("id") == node_id:
            return node.get("id"), index
        hit = _locate_child(child, node_id)
        if hit is not None:
            return hit
    return None


def _subtree_ops(
    old_root: dict[str, Any], fresh_root: dict[str, Any], screen_id: str
) -> list[dict[str, Any]]:
    """重建前后对比：删多余节点 → 加新节点 → 对同 id 节点只 set 变化字段。

    id 由 prefix 确定性生成，所以常规调参只会产生少量 set；只有蓝图结构变了才会
    出现 add / remove。
    """
    old_nodes: dict[str, dict[str, Any]] = {}
    old_order: list[str] = []
    _collect_nodes(old_root, old_nodes, old_order)
    new_nodes: dict[str, dict[str, Any]] = {}
    new_order: list[str] = []
    _collect_nodes(fresh_root, new_nodes, new_order)

    ops: list[dict[str, Any]] = []
    for node_id in reversed(old_order):
        if node_id not in new_nodes:
            ops.append({"op": "remove", "screen": screen_id, "target": node_id})
    for node_id in new_order:
        if node_id in old_nodes:
            continue
        parent_id, at = _locate_child(fresh_root, node_id) or (None, 0)
        # 父节点本身是新增的：整棵子树随父节点的 add 一起进去，不重复添加
        if parent_id is not None and parent_id not in old_nodes:
            continue
        ops.append(
            {
                "op": "add",
                "screen": screen_id,
                "target": parent_id or "@screen",
                "index": at,
                "node": new_nodes[node_id],
            }
        )
    for node_id in new_order:
        old = old_nodes.get(node_id)
        if old is None:
            continue
        fresh = new_nodes[node_id]
        patch: dict[str, Any] = {}
        for key, value in fresh.items():
            if key in ("id", "type", "children"):
                continue
            if old.get(key) != value:
                patch[key] = value
        if patch:
            ops.append({"op": "set", "screen": screen_id, "target": node_id, "set": patch})
    return ops


async def _commit(
    client: httpx.AsyncClient,
    design_id: str,
    ops: list[dict[str, Any]],
    *,
    label: str,
    screen_hint: str,
    base_version: Any,
) -> dict[str, Any]:
    """提交意图而不是整稿：服务端在最新稿上重放，人同时在改也不会被覆盖。"""
    if not ops:
        raise ValidationError("没有产生任何改动")
    if len(ops) > 400:
        raise ValidationError(
            f"本次改动产生 {len(ops)} 条操作，超过单次上限 400；请拆成多次调用或缩小范围",
            details={"ops": len(ops)},
        )
    body = await _request(
        client,
        "POST",
        f"/api/designs/{design_id}/commits",
        {
            "author": "flux-agent",
            "role": "agent",
            "label": label[:120],
            "screen": screen_hint,
            "base_version": base_version,
            "ops": ops,
        },
    )
    design = body.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    applied = body.get("applied")
    applied = applied if isinstance(applied, int) and not isinstance(applied, bool) else 0
    skipped = body.get("skipped") if isinstance(body.get("skipped"), list) else []
    deferred = body.get("deferred") if isinstance(body.get("deferred"), list) else []
    if applied == 0:
        if deferred:
            raise ConflictError(
                "设计稿正被人工编辑（相关图层被软锁），稍后重试",
                code="design_locked",
                details={"deferred": deferred[:10], "skipped": skipped[:10]},
            )
        raise ValidationError("改动没有应用到当前设计稿", details={"skipped": skipped[:10]})
    return {
        "design": design,
        "version": design.get("version"),
        "applied": applied,
        "skipped": skipped[:10],
        "deferred": deferred[:10],
        "rebased": bool(body.get("rebased")),
    }


async def _load_design(client: httpx.AsyncClient, design_id: str) -> dict[str, Any]:
    body = await _request(client, "GET", f"/api/designs/{design_id}")
    design = body.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return design


def _base_version(params: dict[str, Any], design: dict[str, Any]) -> Any:
    raw = params.get("base_version")
    if raw is not None and (isinstance(raw, bool) or not isinstance(raw, int)):
        raise ValidationError("base_version 必须是整数", details={"param": "base_version"})
    return raw if raw is not None else design.get("version")


async def design_component_list(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """列出组件与模板（只读）：只回摘要，蓝图节点树留在服务端。"""
    reject_unknown(params, {"kind", "group"})
    kind = optional_str(params, "kind")
    if kind is not None and kind not in ("component", "template"):
        raise ValidationError("kind 只能是 component 或 template", details={"param": "kind"})
    group = optional_str(params, "group")
    async with _client(ctx) as client:
        body = await _request(client, "GET", "/api/components")
    result: dict[str, Any] = {
        "version": body.get("version"),
        "updated_at": body.get("updated_at"),
    }
    count = 0
    for key, item_kind in (("components", "component"), ("templates", "template")):
        if kind is not None and kind != item_kind:
            continue
        items = [item for item in body.get(key) or [] if isinstance(item, dict)]
        if group is not None:
            items = [item for item in items if item.get("group") == group]
        result[key] = [_profile(item, item_kind) for item in items]
        count += len(items)
    result["count"] = count
    result["hint"] = (
        "组件用 design.component_insert 插入画板并保留可调参数；"
        "模板用 design.template_apply 新建画板或替换现有画板。"
    )
    return result


async def design_component_insert(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params,
        {
            "design_id",
            "asset_id",
            "screen_id",
            "screen_index",
            "params",
            "node_id",
            "x",
            "y",
            "index",
            "variant",
            "base_version",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    asset_id = _require_asset_id(params.get("asset_id"))
    overrides = _params_object(params.get("params"))
    variant = optional_str(params, "variant")
    prefix = _instance_prefix(params.get("node_id"))

    async with _client(ctx) as client:
        design = await _load_design(client, design_id)
        screen, _ = _pick_screen(design, params)
        screen_id = str(screen.get("id"))
        nodes = screen.get("nodes") if isinstance(screen.get("nodes"), list) else []

        bound = await _bind_asset(client, asset_id, "component", overrides, prefix, variant)
        node = bound["bound"]

        width = _size(screen.get("w"))
        height = _size(screen.get("h"))
        node_w = _size(node.get("w"))
        node_h = _size(node.get("h"))
        if params.get("x") is not None:
            node["x"] = _round(params.get("x"))
        else:
            node["x"] = max(8, int(round((width - node_w) / 2)))
        if params.get("y") is not None:
            node["y"] = _round(params.get("y"))
        else:
            node["y"] = min(_stack_y(nodes), max(96, int(height - node_h - 24)))
        at = (
            len(nodes)
            if params.get("index") is None
            else _index_param(params.get("index"), len(nodes))
        )

        committed = await _commit(
            client,
            design_id,
            [{"op": "add", "screen": screen_id, "target": "@screen", "index": at, "node": node}],
            label=f"插入组件 {asset_id}",
            screen_hint=screen_id,
            base_version=_base_version(params, design),
        )
    updated = committed["design"]
    return {
        "inserted": True,
        "design_id": design_id,
        "screen_id": screen_id,
        "node_id": node.get("id"),
        "asset_id": asset_id,
        "params": bound["params"],
        "variant": bound["variant"],
        "x": node.get("x"),
        "y": node.get("y"),
        "version": committed["version"],
        "applied": committed["applied"],
        "skipped": committed["skipped"],
        "deferred": committed["deferred"],
        "rebased": committed["rebased"],
        "summary": _summary(updated),
    }


async def design_component_update(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params,
        {"design_id", "node_id", "params", "variant", "scope", "label", "base_version"},
    )
    design_id = _require_design_id(params.get("design_id"))
    node_id = require_str(params, "node_id")
    overrides = _params_object(params.get("params"))
    variant = optional_str(params, "variant")
    if not overrides and variant is None:
        raise ValidationError("params 与 variant 至少要给一个", details={"param": "params"})
    scope = optional_str(params, "scope") or "instance"
    if scope not in _COMPONENT_SCOPES:
        raise ValidationError(
            f"scope 只能是 {sorted(_COMPONENT_SCOPES)}", details={"param": "scope"}
        )
    label = optional_str(params, "label") or f"更新组件实例 {node_id}"

    async with _client(ctx) as client:
        design = await _load_design(client, design_id)
        found = _find_node_anywhere(design, node_id)
        if found is None:
            raise NotFoundError(f"图层不存在：{node_id}", details={"param": "node_id"})
        origin = found[1][2].get("origin")
        if not isinstance(origin, dict) or not origin.get("assetId"):
            raise ValidationError(
                "该图层不是组件实例（没有 origin），无法按参数更新",
                details={"param": "node_id"},
            )
        asset_id = str(origin["assetId"])
        own = origin.get("params") if isinstance(origin.get("params"), dict) else {}
        # 老实例没有 defaults：取组件级默认，避免旧变体默认被当成用户自定义
        legacy_defaults = None
        if not isinstance(origin.get("defaults"), dict):
            legacy_defaults = await _asset_param_defaults(client, asset_id)
        # 只把「用户自定义 + 本次覆盖」传给 binder，剩余默认值由目标变体决定
        merged = {**_custom_params(origin, legacy_defaults), **overrides}
        effective: dict[str, Any] = {**own, **overrides}
        targets = [node_id] if scope == "instance" else _instances_of(design, asset_id)

        ops: list[dict[str, Any]] = []
        updated_ids: list[str] = []
        applied_variant: Any = variant
        for target_id in targets:
            hit = _find_node_anywhere(design, target_id)
            if hit is None:
                continue
            target_screen, (_, _, old_node) = hit
            target_origin = old_node.get("origin")
            if not isinstance(target_origin, dict) or not target_origin.get("assetId"):
                continue
            # scope=all：把源实例的自定义参数铺到所有同源实例，各自保留自己的变体
            target_custom = _custom_params(target_origin, legacy_defaults)
            wanted = (
                {**target_custom, **merged} if scope == "all" else {**target_custom, **overrides}
            )
            target_variant = variant
            if target_variant is None and isinstance(target_origin.get("variant"), str):
                target_variant = target_origin["variant"]
            bound = await _bind_asset(
                client,
                str(target_origin["assetId"]),
                "component",
                wanted,
                target_id,
                target_variant,
            )
            applied_variant = bound["variant"]
            effective = bound["params"]
            fresh = bound["bound"]
            fresh["x"] = _size(old_node.get("x"))
            fresh["y"] = _size(old_node.get("y"))
            if isinstance(old_node.get("name"), str) and old_node["name"]:
                fresh["name"] = old_node["name"]
            for key in ("hidden", "locked", "rotation", "opacity"):
                if key in old_node:
                    fresh[key] = old_node[key]
            ops.extend(_subtree_ops(old_node, fresh, str(target_screen.get("id") or "")))
            updated_ids.append(target_id)

        if not ops:
            return {
                "changed": False,
                "design_id": design_id,
                "node_id": node_id,
                "asset_id": asset_id,
                "params": effective,
                "variant": applied_variant,
                "updated": updated_ids,
                "version": design.get("version"),
                "note": "参数与当前实例一致，没有产生改动",
            }
        committed = await _commit(
            client,
            design_id,
            ops,
            label=label,
            screen_hint=str(found[0].get("id") or ""),
            base_version=_base_version(params, design),
        )
    return {
        "changed": True,
        "design_id": design_id,
        "node_id": node_id,
        "asset_id": asset_id,
        "scope": scope,
        "params": effective,
        "variant": applied_variant,
        "updated": updated_ids,
        "version": committed["version"],
        "applied": committed["applied"],
        "skipped": committed["skipped"],
        "deferred": committed["deferred"],
        "rebased": committed["rebased"],
        "summary": _summary(committed["design"]),
    }


async def design_template_apply(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(
        params,
        {
            "design_id",
            "asset_id",
            "mode",
            "screen_id",
            "screen_index",
            "params",
            "name",
            "index",
            "base_version",
        },
    )
    design_id = _require_design_id(params.get("design_id"))
    asset_id = _require_asset_id(params.get("asset_id"))
    mode = optional_str(params, "mode") or "new-screen"
    if mode not in _TEMPLATE_MODES:
        raise ValidationError(f"mode 只能是 {sorted(_TEMPLATE_MODES)}", details={"param": "mode"})
    overrides = _params_object(params.get("params"))
    name_override = optional_str(params, "name")

    async with _client(ctx) as client:
        design = await _load_design(client, design_id)
        screens = design.get("screens") if isinstance(design.get("screens"), list) else []

        if mode == "new-screen":
            if len(screens) >= 20:
                raise ValidationError(
                    "设计稿已有 20 块画板，不能再新建；可改用 replace-screen 覆盖现有画板",
                    details={"param": "mode"},
                )
            prefix = "tpl-" + uuid.uuid4().hex[:6]
            bound = await _bind_asset(client, asset_id, "template", overrides, prefix)
            fresh = bound["bound"]
            if name_override:
                fresh["name"] = name_override[:60]
            last = screens[-1] if screens and isinstance(screens[-1], dict) else None
            if last is not None:
                fresh["x"] = _round(_size(last.get("x")) + _size(last.get("w")) + 120)
                fresh["y"] = _round(_size(last.get("y")), 40)
            at = (
                len(screens)
                if params.get("index") is None
                else _index_param(params.get("index"), len(screens))
            )
            screen_id = str(fresh.get("id"))
            ops = [{"op": "add-screen", "screen": screen_id, "index": at, "payload": fresh}]
        else:
            screen, _ = _pick_screen(design, params)
            screen_id = str(screen.get("id"))
            # 画板 id 通常超过 20 字符（实例前缀上限），按它派生一个稳定短前缀
            prefix = "tpl-" + hashlib.sha1(screen_id.encode()).hexdigest()[:8]
            bound = await _bind_asset(client, asset_id, "template", overrides, prefix)
            fresh = bound["bound"]
            fresh_name = (name_override or fresh.get("name") or screen.get("name") or "画板")[:60]
            old_nodes = screen.get("nodes") if isinstance(screen.get("nodes"), list) else []
            ops = []
            patch: dict[str, Any] = {}
            if fresh_name and fresh_name != screen.get("name"):
                patch["name"] = fresh_name
            if isinstance(fresh.get("bg"), str) and fresh["bg"] and fresh["bg"] != screen.get("bg"):
                patch["bg"] = fresh["bg"]
            if patch:
                ops.append({"op": "set-screen", "target": screen_id, "set": patch})
            # 先摘掉旧的顶层图层（子节点随父一起删），再按顺序铺新的顶层图层
            for node in reversed([item for item in old_nodes if isinstance(item, dict)]):
                if isinstance(node.get("id"), str):
                    ops.append({"op": "remove", "screen": screen_id, "target": node["id"]})
            for index, node in enumerate(fresh.get("nodes") or []):
                ops.append(
                    {
                        "op": "add",
                        "screen": screen_id,
                        "target": "@screen",
                        "index": index,
                        "node": node,
                    }
                )

        committed = await _commit(
            client,
            design_id,
            ops,
            label=f"{'新建' if mode == 'new-screen' else '替换'}画板：{asset_id}",
            screen_hint=screen_id,
            base_version=_base_version(params, design),
        )
    updated = committed["design"]
    return {
        "applied_template": True,
        "mode": mode,
        "design_id": design_id,
        "screen_id": screen_id,
        "asset_id": asset_id,
        "params": bound["params"],
        "version": committed["version"],
        "applied": committed["applied"],
        "skipped": committed["skipped"],
        "deferred": committed["deferred"],
        "rebased": committed["rebased"],
        "summary": _summary(updated),
    }


async def _task_for_run(ctx: ToolContext) -> uuid.UUID:
    run_id = getattr(ctx.identity, "run_id", None)
    if not run_id:
        raise ValidationError("design.share 必须在 Flux 任务 Run 内调用（当前调用没有关联 Run）")
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


# ---------------------------------------------------------------- 自定义图标


async def design_icon_create(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """让 Agent 自己画一个 24×24 图标并保存到设计工作室，供 icon 节点引用。"""
    reject_unknown(params, {"name", "label", "parts", "paths", "path"})
    name = require_str(params, "name")
    payload: dict[str, Any] = {"name": name}
    label = optional_str(params, "label")
    if label is not None:
        payload["label"] = label

    parts = params.get("parts")
    paths = params.get("paths")
    path = params.get("path")
    if parts is None and paths is None and path is None:
        raise ValidationError(
            "需要 parts（几何指令数组）或 paths / path（SVG path 的 d 字符串）",
            details={"param": "parts"},
        )
    if parts is not None:
        if not isinstance(parts, list) or not parts:
            raise ValidationError("parts 必须是非空数组", details={"param": "parts"})
        payload["parts"] = parts
    if paths is not None:
        if isinstance(paths, str):
            paths = [paths]
        if not isinstance(paths, list) or not all(isinstance(item, str) for item in paths):
            raise ValidationError("paths 必须是字符串数组", details={"param": "paths"})
        payload["paths"] = paths
    if path is not None:
        if not isinstance(path, str):
            raise ValidationError("path 必须是字符串", details={"param": "path"})
        payload["path"] = path

    async with _client(ctx) as client:
        body = await _request(client, "POST", "/api/icons", payload)
    return {
        "name": body.get("name") or name,
        "label": body.get("label"),
        "parts": body.get("parts"),
        "custom": True,
        "hint": (
            "图标已保存到设计工作室。把节点写成 type=icon、icon=该名称即可；"
            '用 design.patch 时 changes 写 {"id": "icon-1", "set": {"icon": "<name>"}}。'
            "画布、代码生成与 SVG 导出都会使用这套几何。"
        ),
    }


def _api_base(ctx: ToolContext) -> str:
    """从 MCP 端点推导 Flux 自己的 HTTP 基址（同机回环，回环请求免令牌）。"""
    settings = ctx.container.settings
    parsed = urlsplit(str(getattr(settings, "dsh_mcp_url", "") or ""))
    prefix = str(getattr(settings, "api_v1_prefix", "/api/v1") or "/api/v1").rstrip("/")
    if not parsed.scheme or not parsed.netloc:
        return "http://127.0.0.1:8801" + prefix
    return urlunsplit((parsed.scheme, parsed.netloc, "", "", "")) + prefix


def _pick_preview_screen(design: dict[str, Any], screen: str | None) -> dict[str, Any]:
    """按画板 id 或 1 起的序号选画板；缺省第一块，找不到直接报参数错误。"""
    screens = design.get("screens") if isinstance(design.get("screens"), list) else []
    screens = [item for item in screens if isinstance(item, dict)]
    if not screens:
        raise ValidationError("设计稿还没有画板", details={"param": "design_id"})
    wanted = (screen or "").strip()
    if not wanted:
        return screens[0]
    for item in screens:
        if str(item.get("id") or "") == wanted:
            return item
    if wanted.isdigit() and 1 <= int(wanted) <= len(screens):
        return screens[int(wanted) - 1]
    raise ValidationError(f"没有这块画板：{wanted}", details={"param": "screen"})


async def design_preview(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """给 Agent 一个能取到 PNG 的 HTTP 地址（MCP 输出只带文本，图片走 HTTP）。"""
    reject_unknown(params, {"design_id", "screen", "scale", "refresh"})
    design_id = _require_design_id(params.get("design_id"))
    screen = optional_str(params, "screen")
    scale = params.get("scale", 2)
    if isinstance(scale, bool) or not isinstance(scale, (int, float)):
        raise ValidationError("scale 必须是数字", details={"param": "scale"})
    scale = max(1.0, min(float(scale), 3.0))
    refresh = params.get("refresh", False)
    if not isinstance(refresh, bool):
        raise ValidationError("refresh 必须是布尔值", details={"param": "refresh"})

    async with _client(ctx) as client:
        body = await _request(client, "GET", f"/api/designs/{design_id}")
    design = body.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    chosen = _pick_preview_screen(design, screen)
    screen_id = str(chosen.get("id") or "")

    query: dict[str, str] = {"scale": f"{scale:g}"}
    if screen_id:
        query["screen"] = screen_id
    if refresh:
        query["refresh"] = "true"
    url = f"{_api_base(ctx)}/designs/{design_id}/preview.png?{urlencode(query)}"
    filename = f"preview-{design_id}-{screen_id or 'screen'}.png"
    width = int(round(float(chosen.get("w") or 0) * scale))
    height = int(round(float(chosen.get("h") or 0) * scale))
    return {
        "design_id": design_id,
        "version": design.get("version"),
        "screen": {
            "id": screen_id,
            "name": chosen.get("name"),
            "w": chosen.get("w"),
            "h": chosen.get("h"),
        },
        "image": {"format": "png", "width": width, "height": height, "scale": scale},
        "url": url,
        "curl": f"curl -sS -o '{filename}' '{url}'",
        "filename": filename,
        "hint": (
            "只看这个地址看不到画布：必须真的在终端执行上面的 curl，把 PNG 存到工作区，"
            "再用读文件的方式查看图片后才能判断效果。同机回环请求免令牌；没传 screen 时"
            "截第一块画板。改稿后重新调本工具拿地址即可（版本变了缓存自动失效），"
            "要强制重截传 refresh=true。"
        ),
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
            name="design.view",
            title="读取画布视口与选中",
            description=(
                "读取用户此刻在 Luma 画布上的视口：缩放、正在看哪块画板、可视区域坐标、"
                "已选中的图层，并返回可视图层 id 与选中图层相对同级邻居的四向间距（gaps）"
                "和到画板四边的距离（edges）。做「看得见的微调」前先调它，按用户正在看的位置"
                "用 design.patch 改，不要整屏重写。用户还没打开画布时返回 view=null 与提示；"
                "wait_ms 可在用户刚打开页面时等一帧上报（0–5000，默认 1500）。坐标都是相对"
                "当前画板左上角的像素值。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "wait_ms": {
                        "type": "integer",
                        "description": "等待网页首次上报视口的最长毫秒数（0–5000，默认 1500）",
                    },
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_view,
        ),
        ToolSpec(
            name="design.preview",
            title="取设计预览图地址",
            description=(
                "返回一块画板的 PNG 预览图 HTTP 地址（Flux 本机接口，回环免令牌）和一条 curl "
                "命令。MCP 输出只有文本，所以想看画布实际效果时：调本工具 → 在终端执行返回的 "
                "curl 把图存到工作区 → 用读文件的方式查看图片。screen 传画板 id 或 1 起序号，"
                "缺省第一块；scale 1–3（默认 2，越大越清晰）。改稿后版本变化缓存自动失效，"
                "需要强制重截时传 refresh=true。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "screen": {
                        "type": "string",
                        "description": "画板 id 或 1 起的序号；缺省第一块",
                    },
                    "scale": {
                        "type": "number",
                        "description": "像素倍率 1–3（默认 2）",
                    },
                    "refresh": {
                        "type": "boolean",
                        "description": "忽略缓存重新截图（默认 false）",
                    },
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_preview,
        ),
        ToolSpec(
            name="design.create",
            title="新建设计稿",
            description=(
                "在设计工作室新建设计稿并写入画板。做页面/UI/视觉设计必须走这个工具，不要用"
                "自写 HTML、截图或静态图片代替。screens 里每个画板需要 id、name、w、h，"
                "nodes 是图层数组（text/card/pill/circle/decor 等，坐标与尺寸用数字）。"
                "图标用 type=icon + icon 名称；内置图标不够时先调 design.icon_create 画一个。"
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
                "更新设计稿元数据与画板。screens 默认「按画板 id 合并」：同名画板就地替换，"
                "新画板追加，没提到的画板原样保留（所以改一块画板不会把其它画板冲掉）；"
                "只有明确要整份替换时才传 replace_screens=true。只改局部样式时优先用"
                "design.patch（按节点 id）或 design.ai。带 base_version 做乐观锁；不带时按当前"
                "最新版本保存。冲突返回 design_conflict 与当前 version：重新 design.get 后再改，"
                "不要覆盖别人的改动。返回里带 mode（merge/replace）与 preview 快照。"
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
                        "description": (
                            "要写入的画板（1–20 个，每个需 id/name/w/h）。默认按 id 合并；"
                            "省略时保留现有画板"
                        ),
                        "items": {"type": "object"},
                    },
                    "replace_screens": {
                        "type": "boolean",
                        "description": (
                            "true = 用 screens 整份替换所有画板（未列出的画板会被删除）；"
                            "默认 false = 按画板 id 合并"
                        ),
                    },
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_update,
        ),
        ToolSpec(
            name="design.patch",
            title="按节点增量修改",
            description=(
                "按节点 id 做增量修改：changes 批量改现有图层的样式/文案/位置"
                '（每项 {"id": "card-1", "set": {"radius": 16, "h": 96}}），'
                "ops 走原生操作（set / add / remove / reorder，可增删或调整层级）。"
                "只提交改动、不覆盖整份画板，比 design.update 更安全也更省 token。"
                "多选统一改样式优先用它，不要依赖 design.ai 去猜。"
                "字段白名单：x/y/w/h/radius/fill/color/text/fontSize/fontWeight/opacity/"
                "letterSpacing/stroke/lineHeight/align/name/hidden/locked/rotation/"
                "icon（图标名，如 search/home/heart）/sw（图标线宽、分割线粗细）/"
                "shadow（CSS box-shadow 字符串）/maxLines（文本最多几行）/fontFamily 等；"
                "未知字段会被跳过，原因在 skipped 里。node id 从 design.get 的图层树里取。"
                "返回里带 preview（当前画板前 48 个节点的快照），可直接确认改动效果，"
                "不用再跳去设计工作室。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "screen_id": {
                        "type": "string",
                        "description": "目标画板 id（可空，默认第一块画板）",
                    },
                    "screen_index": {
                        "type": "integer",
                        "description": "目标画板下标（screen_id 优先）",
                    },
                    "changes": {
                        "type": "array",
                        "description": "批量样式修改，最多 200 条",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": "string", "description": "节点 id"},
                                "set": {
                                    "type": "object",
                                    "description": '要改的字段，例如 {"h": 96, "radius": 16}',
                                },
                            },
                            "required": ["id", "set"],
                        },
                    },
                    "ops": {
                        "type": "array",
                        "description": (
                            "原生 ops，与 changes 合计最多 400 条。"
                            '例：{"op":"add","target":"@screen","node":{...},"index":0}、'
                            '{"op":"remove","target":"node-id"}、'
                            '{"op":"reorder","target":"node-id","index":2}'
                        ),
                        "items": {"type": "object"},
                    },
                    "label": {"type": "string", "description": "提交说明（可空）"},
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本提交（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_patch,
        ),
        ToolSpec(
            name="design.layout",
            title="对齐 / 分布 / 堆叠图层",
            description=(
                "对同一父级下的多个图层做几何整理，服务端算好坐标后按节点提交，不整份覆盖。"
                "action：align-left / align-right / align-top / align-bottom / "
                "align-h-center / align-v-center、distribute-h / distribute-v（至少 3 个）、"
                "stack-v / stack-h（按传入顺序，gap 默认 12）、equal-width / equal-height。"
                "align_to=selection（默认，以选区包围盒为基准）或 screen（以画板为基准）。"
                "只支持同一父级下的图层；跨分组请先解组或分批调用。"
                "改堆叠间距时直接调 stack-v/stack-h 改 gap，不用自己重算每个节点的 y。"
                "返回里带 preview，可直接确认排版结果。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "screen_id": {"type": "string", "description": "目标画板 id（优先于下标）"},
                    "screen_index": {"type": "integer", "description": "目标画板下标，默认 0"},
                    "node_ids": {
                        "type": "array",
                        "description": "要整理的图层 id（2–50 个，需同一父级）",
                        "items": {"type": "string"},
                    },
                    "action": {
                        "type": "string",
                        "enum": [
                            "align-left",
                            "align-right",
                            "align-top",
                            "align-bottom",
                            "align-h-center",
                            "align-v-center",
                            "distribute-h",
                            "distribute-v",
                            "stack-v",
                            "stack-h",
                            "equal-width",
                            "equal-height",
                        ],
                        "description": "布局动作",
                    },
                    "gap": {
                        "type": "number",
                        "description": "stack 时的间距，默认 12（像素）",
                    },
                    "align_to": {
                        "type": "string",
                        "enum": ["selection", "screen"],
                        "description": "selection 以选区包围盒为基准（默认）；screen 以画板为基准",
                    },
                    "size": {
                        "type": "number",
                        "description": "equal-width/equal-height 的目标尺寸；省略取当前最大值",
                    },
                    "label": {"type": "string", "description": "提交说明（可空）"},
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本提交（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id", "node_ids", "action"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_layout,
        ),
        ToolSpec(
            name="design.component_list",
            title="列出组件与模板",
            description=(
                "列出设计工作室的组件库与模板库（只读）：返回每个组件的参数定义与默认值、"
                "模板的尺寸与图层数。搭版式前先看这里，优先用现成组件/模板，不要手搓重复模块。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "kind": {
                        "type": "string",
                        "enum": ["component", "template"],
                        "description": "只看组件或只看模板；省略则两类都返回",
                    },
                    "group": {"type": "string", "description": "按分组过滤，例如「操作」「系统」"},
                },
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_component_list,
        ),
        ToolSpec(
            name="design.component_insert",
            title="插入组件实例",
            description=(
                "把组件库里的组件按其参数展开成一个实例节点，追加到指定画板顶层。实例带 origin，"
                "后续可用 design.component_update 只改参数或切换变体。组件有 variants 时可用 "
                "variant 指定蓝图（省略用默认变体）。不传 x/y 时自动水平居中并叠在现有图层"
                "下方。返回的 node_id 是实例根图层 id，改参数、选中它都用这个 id。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "asset_id": {
                        "type": "string",
                        "description": "组件 id，例如 button-primary（见 component_list）",
                    },
                    "params": {
                        "type": "object",
                        "description": '组件参数覆盖，例如 {"text": "立即购买", "width": 300}',
                    },
                    "variant": {
                        "type": "string",
                        "description": "变体 id（见 component_list 的 variants）；省略用默认变体",
                    },
                    "screen_id": {"type": "string", "description": "目标画板 id（优先于下标）"},
                    "screen_index": {
                        "type": "integer",
                        "description": "目标画板下标，默认 0",
                    },
                    "node_id": {
                        "type": "string",
                        "description": "自定义实例根 id（≤20 字符）；省略自动生成 inst-xxxxxx",
                    },
                    "x": {"type": "number", "description": "覆盖横坐标（省略自动居中）"},
                    "y": {"type": "number", "description": "覆盖纵坐标（省略自动堆叠）"},
                    "index": {
                        "type": "integer",
                        "description": "在画板顶层图层中的插入位置，默认追加到末尾",
                    },
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本提交（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id", "asset_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_component_insert,
        ),
        ToolSpec(
            name="design.component_update",
            title="调整组件实例参数/变体",
            description=(
                "按参数重建组件实例子树（实例位置、名称、显隐、锁定、旋转、透明度保持不变）。"
                "scope=instance 只改这一个实例；scope=all 把新参数同步到全稿所有同源实例。"
                "params 改参数，variant 切换变体，两者至少给一个；只切变体时可以不带 params。"
                "只能用于带 origin 的组件实例；普通图层要改样式请用 design.ai 或 design.update。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "node_id": {"type": "string", "description": "组件实例的根图层 id"},
                    "params": {
                        "type": "object",
                        "description": '要改的参数，例如 {"text": "已售罄", "fill": "#F87171"}',
                    },
                    "variant": {
                        "type": "string",
                        "description": "要切换到的变体 id，例如 ghost / danger（见列表工具）",
                    },
                    "scope": {
                        "type": "string",
                        "enum": ["instance", "all"],
                        "description": "instance 只改当前实例（默认）；all 同步到所有同源实例",
                    },
                    "label": {"type": "string", "description": "提交说明（可空）"},
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本提交（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id", "node_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_component_update,
        ),
        ToolSpec(
            name="design.template_apply",
            title="应用模板到画板",
            description=(
                "把模板库里的整屏模板铺进设计稿。mode=new-screen 追加一块新画板（排在上一个"
                "画板右侧）；mode=replace-screen 用模板内容整体替换指定画板的图层（保留画板 id"
                "与位置）。模板参数同样可在 params 里覆盖，例如页面标题。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "asset_id": {
                        "type": "string",
                        "description": "模板 id，例如 tpl-settings（从 design.component_list 取）",
                    },
                    "mode": {
                        "type": "string",
                        "enum": ["new-screen", "replace-screen"],
                        "description": "new-screen 新建画板（默认）；replace-screen 替换现有画板",
                    },
                    "screen_id": {
                        "type": "string",
                        "description": "mode=replace-screen 时要替换的画板 id",
                    },
                    "screen_index": {
                        "type": "integer",
                        "description": "mode=replace-screen 时可改用画板下标，默认 0",
                    },
                    "params": {
                        "type": "object",
                        "description": '模板参数覆盖，例如 {"title": "偏好设置"}',
                    },
                    "name": {"type": "string", "description": "画板名称覆盖（可空）"},
                    "index": {
                        "type": "integer",
                        "description": "mode=new-screen 时新画板的插入位置，默认追加到末尾",
                    },
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本提交（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id", "asset_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_template_apply,
        ),
        ToolSpec(
            name="design.ai",
            title="AI 局部修改设计稿",
            description=(
                "让设计工作室的 AI 只改当前画板的局部（改文案/配色/间距、加删单个模块、调整"
                "层级），服务端把改动落盘并返回新版本。指令上限 2000 字，可以一次写多句组合"
                "要求。改已有设计优先用 design.patch（按节点 id 精确改样式）或本工具（让 AI 猜"
                "意图）；对齐/间距用 design.layout。不要 design.get 后整份 design.update 覆盖："
                "既费 token，也容易冲掉别人的改动。mode=auto 时按 selected_ids 推断；"
                "要改整屏可不传选中项。改完要让用户看到效果，调 design.share 把最新设计发进对话。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "instruction": {
                        "type": "string",
                        "description": "修改要求，具体到对象与效果，例如「把标题改成深色并右移 8」",
                    },
                    "screen_index": {
                        "type": "integer",
                        "description": "目标画板下标，默认 0（与 screen_id 二选一）",
                    },
                    "screen_id": {"type": "string", "description": "目标画板 id（优先于下标）"},
                    "selected_ids": {
                        "type": "array",
                        "description": "要改的图层 id（最多 40 个）；mode=node/multi 时必填",
                        "items": {"type": "string"},
                    },
                    "mode": {
                        "type": "string",
                        "enum": ["auto", "node", "multi", "screen"],
                        "description": (
                            "auto 按选中项推断；node 改一个、multi 改多个、screen 改整屏"
                        ),
                    },
                    "base_version": {
                        "type": "integer",
                        "description": "基于哪个版本修改（可空，缺省用刚读到的版本）",
                    },
                },
                "required": ["design_id", "instruction"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_ai,
        ),
        ToolSpec(
            name="design.revisions",
            title="查看设计稿历史",
            description=(
                "列出设计稿的历史快照（只读）。改坏了、想对比或要回滚时先看这里，"
                "拿到 version 再调 design.revert。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                },
                "required": ["design_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=design_revisions,
        ),
        ToolSpec(
            name="design.revert",
            title="回滚设计稿",
            description=(
                "把设计稿恢复到指定历史版本（会生成一个新版本，不删除历史）。"
                "配合 design.revisions 使用；带 base_version 做乐观锁。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "design_id": {"type": "string", "description": "设计稿 id"},
                    "version": {"type": "integer", "description": "要恢复到的历史版本号"},
                    "base_version": {
                        "type": "integer",
                        "description": "当前版本号，用于乐观锁（可空）",
                    },
                },
                "required": ["design_id", "version"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_revert,
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
        ToolSpec(
            name="design.icon_create",
            title="画自定义图标",
            description=(
                "内置图标（search/home/heart 等 90+ 个）没有合适图形时，自己画一个并保存到设计"
                "工作室，随后用 type=icon + icon=名称引用，画布、代码生成与 SVG 导出都会渲染。"
                "几何画在 24×24 视口里，用 parts 描述（线框风格、不填色）："
                '["path", "M3 12h18"]、["circle", cx, cy, r]、["line", x1, y1, x2, y2]、'
                '["rect", x, y, w, h, rx?]、["polyline", "20 6 9 17 4 12"]；'
                "只画 path 时可直接传 paths（d 字符串数组）或 path（单个 d）。"
                "图标名只用小写字母/数字/连字符且不能与内置图标重名；同名重复调用即覆盖更新。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "图标名（小写字母/数字/连字符，1–40 位，如 brand-mark）",
                    },
                    "label": {"type": "string", "description": "中文标签（可空，如「品牌标志」）"},
                    "parts": {
                        "type": "array",
                        "description": "几何指令数组，1–40 条，建议控制在 12 条以内",
                        "items": {"type": "array"},
                    },
                    "paths": {
                        "type": "array",
                        "description": "仅 path 的简写：SVG path 的 d 字符串数组",
                        "items": {"type": "string"},
                    },
                    "path": {
                        "type": "string",
                        "description": "仅一条 path 时的简写：SVG path 的 d 字符串",
                    },
                },
                "required": ["name"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_WRITE,
            handler=design_icon_create,
        ),
    )
