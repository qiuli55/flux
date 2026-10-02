"""只读工具：`context.get` / `workspace.read` / `workspace.diff`（目标架构 §3.3）。

三个工具都不写盘、不改状态，因此在令牌能力层面只要 `file.read`；
它们背后分别是打包器、Project File Explorer（只读、含软链防护）与 Virtual Workspace——
本层不重复实现任何文件访问，避免出现第二份"能读用户文件"的代码。
"""

from __future__ import annotations

import asyncio
from pathlib import PurePosixPath
from typing import Any

from flux.core.mcp.context_packager import DEFAULT_BUDGET_CHARS, package_context
from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.core.virtual_workspace.diff_engine import content_hash
from flux.core.virtual_workspace.path_guard import (
    ensure_not_flux_internal,
    ensure_not_secret_path,
)
from flux.enums import Capability
from flux.errors import ValidationError

CONTEXT_GET_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "task_id": {"type": "string", "description": "任务 UUID；带上它才会打包 L1 任务上下文"},
        "project_id": {"type": "string", "description": "项目 UUID；不传则从任务自动推导"},
        "budget": {
            "type": "integer",
            "minimum": 1,
            "description": f"可投喂字符预算，默认 {DEFAULT_BUDGET_CHARS}",
        },
    },
    "additionalProperties": False,
}


async def context_get(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"task_id", "project_id", "budget"})
    budget = params.get("budget", DEFAULT_BUDGET_CHARS)
    if isinstance(budget, bool) or not isinstance(budget, int):
        raise ValidationError("参数 budget 必须是整数", details={"param": "budget"})
    return await package_context(
        ctx.container,
        task_id=optional_str(params, "task_id"),
        project_id=optional_str(params, "project_id"),
        budget=budget,
    )


WORKSPACE_READ_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "path": {
            "type": "string",
            "description": "相对工作区根的文件路径，例如 todo_service/store.py",
        }
    },
    "required": ["path"],
    "additionalProperties": False,
}


async def workspace_read(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"path"})
    path = require_str(params, "path")
    # Secret 与 Flux 内部数据（备份/锁）是 MCP 面的硬禁令（§3.5 / §7）：直接拒绝，
    # 不因为"只是读一下"而放行
    ensure_not_secret_path(path)
    ensure_not_flux_internal(PurePosixPath(path.replace("\\", "/")))
    # workspace_root 不接受外部传入：能读到哪个目录由 Flux 配置决定，不由调用方指定
    content = await asyncio.to_thread(ctx.container.files.read, path=path)
    return {
        "path": content.path,
        "content": content.content,
        "size": content.size,
        "truncated": content.truncated,
        "sha256": content_hash(content.content),
    }


WORKSPACE_DIFF_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "change_id": {
            "type": "string",
            "description": "提案 UUID（从 workspace.changed 事件或提案列表里拿到）",
        }
    },
    "required": ["change_id"],
    "additionalProperties": False,
}


async def workspace_diff(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"change_id"})
    change_id = require_str(params, "change_id")
    change = await ctx.container.workspace.get(change_id)
    # 历史提案可能是在密钥禁令收紧之前落库的，这里按当前策略复核一次：
    # diff 里就是完整文件内容，不能借"看 diff"把密钥读出去
    ensure_not_secret_path(change.file_path)
    ensure_not_flux_internal(PurePosixPath(change.file_path.replace("\\", "/")))
    return {
        "change_id": str(change.id),
        "file_path": change.file_path,
        "status": change.status,
        "diff": change.diff or "",
        "added_lines": change.added_lines,
        "removed_lines": change.removed_lines,
        "hunks": change.hunks,
        "original_hash": change.original_hash,
        "proposed_hash": content_hash(change.proposed_content or ""),
    }


def read_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="context.get",
            title="拉取任务上下文",
            description=(
                "按预算拉取 Flux 侧存档的工程上下文（L0 项目 Brain / L1 任务 / L2 近期提案）。"
                "开工第一步应调用它；内容为快照，动手前用 workspace.read 核对磁盘当前状态。"
            ),
            input_schema=CONTEXT_GET_SCHEMA,
            capability=Capability.FILE_READ,
            handler=context_get,
        ),
        ToolSpec(
            name="workspace.read",
            title="读取工作区文件",
            description="读取工作区内某个文件的当前内容及其 sha256（只读，不落盘）。",
            input_schema=WORKSPACE_READ_SCHEMA,
            capability=Capability.FILE_READ,
            handler=workspace_read,
        ),
        ToolSpec(
            name="workspace.diff",
            title="查看提案差异",
            description="查看某条提案的 unified diff 与增删行统计（提案尚未落盘，等人审）。",
            input_schema=WORKSPACE_DIFF_SCHEMA,
            capability=Capability.FILE_READ,
            handler=workspace_diff,
        ),
    )
