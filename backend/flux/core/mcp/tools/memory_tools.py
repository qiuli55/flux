"""只读记忆工具 `memory.recall`（批次② §4.2 / §4.3）。

三层记忆的统一读取口（D3：与 `context.get` / `flux_context` 并存不合并）：

- `flux_context` = 我在哪里、我能做什么（运行时身份与能力）；
- `context.get`  = 这个项目是什么（项目事实，只读现状）；
- `memory.recall` = Flux 记住的结论（Environment / User / Project 三层，跨会话）。

只读，不改任何状态；Agent 没有任何记忆直写接口——写入必须走受控路径
（User Memory 由用户确认，Project Memory 由扫描与工作沉淀产生）。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.core.mcp.tools.base import ToolContext, ToolSpec, optional_str, reject_unknown
from flux.enums import Capability
from flux.errors import ValidationError

MEMORY_RECALL_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "project_id": {
            "type": "string",
            "description": "项目 UUID；不传时若给了 task_id 则从任务推导，都拿不到就只返回两层",
        },
        "task_id": {"type": "string", "description": "任务 UUID；用于推导所属项目"},
    },
    "additionalProperties": False,
}


async def memory_recall(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"project_id", "task_id"})
    project_id = optional_str(params, "project_id")
    if project_id is None:
        task_id = optional_str(params, "task_id")
        if task_id is not None:
            project_id = await _project_from_task(ctx, task_id)
    return await ctx.container.memory.recall(project_id=project_id)


async def _project_from_task(ctx: ToolContext, task_id: str) -> str | None:
    try:
        key = uuid.UUID(task_id)
    except ValueError as exc:
        raise ValidationError(
            f"task_id 不是合法 UUID：{task_id}", details={"task_id": task_id}
        ) from exc
    task = await ctx.container.task_repo.get(key)
    return str(task.project_id) if task.project_id is not None else None


def memory_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="memory.recall",
            title="读取三层长期记忆",
            description=(
                "读取 Flux 侧存档的长期记忆（只读）：Environment（平台运行规则，优先）/ "
                "User（跨 Workspace 的偏好与约定）/ Project（当前项目 Brain）。"
                "记忆是被记住的结论（快照），不替代事实核对——事实用 context.get / "
                "workspace.read 核实。"
            ),
            input_schema=MEMORY_RECALL_SCHEMA,
            capability=Capability.FILE_READ,
            handler=memory_recall,
        ),
    )
