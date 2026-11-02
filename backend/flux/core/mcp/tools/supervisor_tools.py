"""助理（Supervisor）只读工具：让子 Agent 能问"现在整体在做什么"。

四个工具全是只读，能力统一按 `file.read` 计费：它们只回放平台已经记录的事实
（事件、工单、报告），不接受任何"帮我派活/改状态"的入参——派发与停止仍然只有
人通过 REST 接口才能触发（观察模式的红线：模型不越过授权面）。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.core.mcp.tools.base import ToolContext, ToolSpec, optional_str, reject_unknown
from flux.enums import Capability
from flux.errors import ValidationError

__all__ = ["supervisor_tools"]

_LIMIT_SCHEMA: dict[str, Any] = {
    "type": "integer",
    "minimum": 1,
    "maximum": 200,
    "description": "返回条数上限（默认 20）",
}


async def assistant_overview(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"limit"})
    return await ctx.container.supervisor.overview(limit=_limit(params))


async def assistant_ticket(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"ticket_id"})
    ticket_id = _uuid(params.get("ticket_id"), "ticket_id")
    return await ctx.container.supervisor.ticket_detail(ticket_id)


async def assistant_reports(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"limit"})
    return {"reports": await ctx.container.supervisor.reports(limit=_limit(params))}


async def assistant_timeline(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"thread_id", "project_id", "limit"})
    thread_id = _optional_uuid(optional_str(params, "thread_id"), "thread_id")
    project_id = _optional_uuid(optional_str(params, "project_id"), "project_id")
    events = await ctx.container.supervisor.timeline(
        thread_id=thread_id, project_id=project_id, limit=_limit(params)
    )
    return {"events": events}


def _limit(params: dict[str, Any]) -> int:
    value = params.get("limit", 20)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError("limit 必须是整数", details={"param": "limit"})
    return max(1, min(value, 200))


def _uuid(value: object, key: str) -> uuid.UUID:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"参数 {key} 必须是非空字符串", details={"param": key})
    try:
        return uuid.UUID(value.strip())
    except ValueError as exc:
        raise ValidationError(f"{key} 不是合法 UUID：{value}", details={"param": key}) from exc


def _optional_uuid(value: str | None, key: str) -> uuid.UUID | None:
    if value is None:
        return None
    return _uuid(value, key)


def supervisor_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="assistant.overview",
            title="查看助理看板",
            description=(
                "查看 Flux 助理此刻掌握的整体进度（只读）：设置快照、各阶段工单计数、"
                "最近工单（含最新派活与报告摘要）。回答“现在在做什么 / 卡在哪”时先看它。"
            ),
            input_schema={
                "type": "object",
                "properties": {"limit": _LIMIT_SCHEMA},
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=assistant_overview,
        ),
        ToolSpec(
            name="assistant.ticket",
            title="查看单张工单",
            description=(
                "查看一张工单的完整事实（只读）：派生阶段、全部派活记录、全部报告与订阅目标。"
                "阶段由任务/派活/报告的真实状态派生，不采信任何自报进度。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "ticket_id": {"type": "string", "description": "工单 UUID"},
                },
                "required": ["ticket_id"],
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=assistant_ticket,
        ),
        ToolSpec(
            name="assistant.reports",
            title="查看最近报告",
            description=(
                "按时间倒序读取最近的工单报告（只读），用于汇总“哪些事情已经收尾、"
                "结论是什么”。报告只由平台按任务真实终态写入，不是子 Agent 自报。"
            ),
            input_schema={
                "type": "object",
                "properties": {"limit": _LIMIT_SCHEMA},
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=assistant_reports,
        ),
        ToolSpec(
            name="assistant.timeline",
            title="查看事件时间线",
            description=(
                "把外部事件、工单、报告合成一条时间倒序的时间线（只读），可按线程或项目过滤。"
                "用于还原“这件事从哪来、走到哪一步”。"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "thread_id": {"type": "string", "description": "线程 UUID（可选）"},
                    "project_id": {"type": "string", "description": "项目 UUID（可选）"},
                    "limit": _LIMIT_SCHEMA,
                },
                "additionalProperties": False,
            },
            capability=Capability.FILE_READ,
            handler=assistant_timeline,
        ),
    )
