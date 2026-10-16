"""只读工具：`context.get` / `context.fetch` / `workspace.read` / `workspace.diff`（§3.3）。

四个工具都不写盘、不改状态，因此在令牌能力层面只要 `file.read`；
它们背后分别是打包器、内容寻址原文库、Project File Explorer（只读、含软链防护）
与 Virtual Workspace——本层不重复实现任何文件访问，避免出现第二份"能读用户文件"的代码。
"""

from __future__ import annotations

import asyncio
from pathlib import PurePosixPath
from typing import Any

from flux.core.context.catalog import catalog_line
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
from flux.errors import NotFoundError, ValidationError

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
    # budget 缺省交给打包器按 §6.1 推导，这里只拒绝"传了但不是整数"
    budget = params.get("budget")
    if budget is not None and (isinstance(budget, bool) or not isinstance(budget, int)):
        raise ValidationError("参数 budget 必须是整数", details={"param": "budget"})
    return await package_context(
        ctx.container,
        task_id=optional_str(params, "task_id"),
        project_id=optional_str(params, "project_id"),
        budget=budget,
    )


#: 单次 fetch 的默认字符预算。远小于打包预算（24_000）：第三档降级只投递 step_id，
#: agent 真需要原文时按需取回；§6.7 要求 range + budget 并存，正是为了防"一不确定
#: 就把整段历史拉回来"——默认给得小，让它被迫分页而不是整段吞。
DEFAULT_FETCH_CHARS = 8_000

CONTEXT_FETCH_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "step_id": {
            "type": "integer",
            "description": "目录行里的 #编号，例如 context.fetch(step_id=42)",
        },
        "range": {
            "type": "array",
            "items": {"type": "integer", "minimum": 0},
            "minItems": 2,
            "maxItems": 2,
            "description": "字符区间 [start, end)，左闭右开；不传则从 0 开始",
        },
        "budget": {
            "type": "integer",
            "minimum": 1,
            "description": f"本次最多返回的字符数，默认 {DEFAULT_FETCH_CHARS}",
        },
    },
    "required": ["step_id"],
    "additionalProperties": False,
}


def _required_int(params: dict[str, Any], key: str) -> int:
    if key not in params:
        raise ValidationError(f"缺少必填参数：{key}", details={"param": key})
    return _as_int(params[key], key)


def _optional_int(params: dict[str, Any], key: str, *, default: int) -> int:
    if key not in params:
        return default
    return _as_int(params[key], key)


def _as_int(value: Any, key: str) -> int:
    # bool 是 int 的子类，显式排除，避免 True 被当成 1
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError(f"参数 {key} 必须是整数", details={"param": key})
    return value


def _parse_range(params: dict[str, Any]) -> tuple[int, int | None]:
    """解析 [start, end)：缺省 end 表示"到末尾"。"""
    raw = params.get("range")
    if raw is None:
        return 0, None
    if not isinstance(raw, (list, tuple)) or len(raw) != 2:
        raise ValidationError(
            "参数 range 必须是 [start, end] 两元素数组", details={"param": "range"}
        )
    start = _as_int(raw[0], "range")
    end = _as_int(raw[1], "range")
    if start < 0 or end < 0:
        raise ValidationError("参数 range 的两端必须是非负整数", details={"param": "range"})
    if end < start:
        raise ValidationError("参数 range 的 end 不能小于 start", details={"param": "range"})
    return start, end


async def context_fetch(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """按 step_id 取回某一步的原文片段（§6.7 / §7.1 第三档的消费方）。

    这是三档降级里"只给引用"那一档的出口：目录行露出 `#编号`，agent 需要细节时
    用本工具按 range + budget 分页取回，而不是要求打包器把整段历史塞进投喂。
    """
    reject_unknown(params, {"step_id", "range", "budget"})
    step_id = _required_int(params, "step_id")
    start, end = _parse_range(params)
    budget = _optional_int(params, "budget", default=DEFAULT_FETCH_CHARS)
    if budget < 1:
        raise ValidationError("参数 budget 必须是正整数", details={"param": "budget"})

    step = await ctx.container.context_steps.get(step_id)
    if not step.result_sha256:
        raise NotFoundError(f"step {step_id} 没有可取的原文（未记录结果）")
    raw = await ctx.container.content_store.get(step.result_sha256)
    text = raw.decode("utf-8", errors="replace")

    total = len(text)
    start = min(start, total)
    stop = total if end is None else min(end, total)
    stop = min(stop, start + budget)
    chunk = text[start:stop]
    truncated = stop < total
    return {
        "step_id": step.id,
        "catalog_line": catalog_line(step),
        "kind": step.kind,
        "status": step.status,
        "sha256": step.result_sha256,
        "size_bytes": step.result_bytes,
        "total_chars": total,
        "offset": start,
        "returned_chars": len(chunk),
        "truncated": truncated,
        "next_offset": stop if truncated else None,
        "content": chunk,
        "facts": step.facts,
    }


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
            name="context.fetch",
            title="取回某步原文",
            description=(
                "按 step_id 取回历史某一步的原文片段（目录行里的 #编号）。"
                "上下文包里的 dropped_refs / 目录行只给引用，需要细节时用本工具按需取回，"
                "用 range 与 budget 分页，避免整段拉回。"
            ),
            input_schema=CONTEXT_FETCH_SCHEMA,
            capability=Capability.FILE_READ,
            handler=context_fetch,
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
