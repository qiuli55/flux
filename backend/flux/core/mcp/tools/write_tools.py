"""写入工具：`proposal.create`（目标架构 §3.3）。

这是 agent 唯一能改变"世界"的通道——而且只改变 Flux 的**虚拟提案**，
不碰用户的真实文件：真实落盘必须经人审 + ApplyEngine（§3.5 硬禁令）。

入参解析复用 `proposal_parser`（与 REST 导入路径同一份校验），
provenance 的 `agent_source` 由服务端用令牌身份盖章，客户端传什么都不作数。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
)
from flux.core.virtual_workspace.proposal_parser import parse_code_change_set
from flux.enums import Capability
from flux.errors import NotFoundError, ValidationError

PROPOSAL_CREATE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "payload": {
            "description": (
                "提案内容：形如 {summary, changes:[{path, content, reason}]} 的 JSON 对象"
                "或其 JSON 文本（也接受 ```json 围栏）。content 必须是改动后的完整文件内容。"
            ),
            "anyOf": [{"type": "object"}, {"type": "string"}],
        },
        "task_id": {"type": "string", "description": "该改动服务的任务 UUID（可空）"},
        "project_id": {"type": "string", "description": "项目 UUID（可空）"},
    },
    "required": ["payload"],
    "additionalProperties": False,
}


async def proposal_create(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"payload", "task_id", "project_id"})
    change_set = parse_code_change_set(_as_text(params.get("payload")), source="proposal.create")

    originals: dict[str, str] = {}
    for change in change_set.changes:
        originals[change.path] = await _original_content(ctx, change.path)

    created = await ctx.container.workspace.propose_changes(
        change_set,
        project_id=optional_str(params, "project_id"),
        task_id=optional_str(params, "task_id"),
        # 服务端盖章：提案归属只看令牌，不看请求体
        agent_source=ctx.identity.agent_id,
        original_files=originals,
    )
    return {
        "count": len(created),
        "agent": ctx.identity.agent_id,
        "changes": [
            {
                "change_id": str(change.id),
                "file_path": change.file_path,
                "status": change.status,
                "added_lines": change.added_lines,
                "removed_lines": change.removed_lines,
                "hunks": change.hunks,
            }
            for change in created
        ],
        "note": "提案已进入人工审核队列，尚未落盘；落盘只在人审通过后由 Apply Engine 执行。",
    }


def _as_text(payload: Any) -> str:
    if isinstance(payload, (dict, list)):
        return json.dumps(payload, ensure_ascii=False)
    if isinstance(payload, str) and payload.strip():
        return payload
    raise ValidationError("参数 payload 必须是提案对象或其 JSON 文本", details={"param": "payload"})


async def _original_content(ctx: ToolContext, path: str) -> str:
    """取提案所依据的原文件内容；文件不存在即视为新建（空串）。"""
    try:
        content = await asyncio.to_thread(ctx.container.files.read, path=path)
    except NotFoundError:
        return ""
    if content.truncated:
        # 读到的只是前 256KB，用它当 original 会生成一份错误的 diff，
        # 人审时看到的"改动"就是假的——宁可明确拒绝。
        raise ValidationError(
            f"文件过大（超过读取上限），无法作为提案基线：{path}",
            details={"path": path},
        )
    return content.content


def write_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="proposal.create",
            title="提交改动提案",
            description=(
                "把一组文件改动作为提案提交进 Flux 的人工审核队列（不落盘）。"
                "每个文件必须给出改动后的完整内容；提案不会直接写入真实文件，"
                "落盘由人审通过后经 Apply Engine 执行。"
            ),
            input_schema=PROPOSAL_CREATE_SCHEMA,
            capability=Capability.FILE_WRITE,
            handler=proposal_create,
            approval_capable=True,
        ),
    )
