"""运行时上下文工具 `flux_context`（最终方案 §6 / §7 / §8）。

与 `context.get` 的分工（D3，职责分离、并存不合并）：

- `flux_context`  = 我在哪里、我能做什么（Runtime / Run / Task / Workspace / Policy / Capabilities）
- `context.get`   = 这个项目是什么（项目事实 / 文档 / 代码上下文 / 项目约束）

本工具同时承担 **Flux Agent Handshake v1** 的落点（§6）：MCP `initialize` 完成协议层握手后，
Agent 调一次 `flux_context` 拿到 `protocol` 回执与能力清单，即视为握手完成。
只读，不改任何状态。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.protocol import (
    FLUX_AGENT_PROTOCOL,
    FLUX_AGENT_PROTOCOL_VERSION,
    RUNTIME_ENTRY,
    RUNTIME_RULES,
)
from flux.core.mcp.tools.base import ToolContext, ToolSpec, optional_str, reject_unknown
from flux.core.task_engine.repository import TaskRepository
from flux.enums import Capability, FluxCapability
from flux.errors import AIOSError, ValidationError
from flux.models.agent_run import AgentRun
from flux.models.task import Task
from flux.version import VERSION

#: Personal MVP 的平台模式
PLATFORM_MODE = "personal"

FLUX_CONTEXT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "run_id": {
            "type": "string",
            "description": "当前 Run 标识；不传则按令牌身份取最新的进行中 Run",
        },
        "task_id": {
            "type": "string",
            "description": "当前任务 UUID；不传则从 Run 推导",
        },
    },
    "additionalProperties": False,
}


async def flux_context(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    """返回 Agent 当前所处的 Flux Runtime 上下文与可用能力（只读）。"""
    reject_unknown(params, {"run_id", "task_id"})
    container = ctx.container
    settings = container.settings

    run = await _resolve_run(
        container, agent_id=ctx.identity.agent_id, run_id=optional_str(params, "run_id")
    )
    task = await _resolve_task(container.task_repo, optional_str(params, "task_id"), run)

    return {
        "protocol": {"name": FLUX_AGENT_PROTOCOL, "version": FLUX_AGENT_PROTOCOL_VERSION},
        "platform": {
            "name": settings.app_name,
            "version": VERSION,
            "mode": PLATFORM_MODE,
        },
        "run": _run_view(run),
        "task": _task_view(task),
        "workspace": _workspace_view(container, settings),
        "agent": {"id": ctx.identity.agent_id, "adapter": None},
        "policy": {
            "proposal_required": bool(settings.proposal_required),
            "direct_apply": False,
        },
        "capabilities": _capabilities(settings),
        # §4.1 契约 rules / entry：与 Runtime Bootstrap（protocol.py）同一份真源——
        # Agent 无论从 prompt 还是从 flux_context 拿到的行为规则必须完全一致。
        "rules": list(RUNTIME_RULES),
        "entry": list(RUNTIME_ENTRY),
    }


def _run_view(run: AgentRun | None) -> dict[str, Any] | None:
    if run is None:
        return None
    return {
        "id": run.id,
        "status": run.status,
        "task_id": str(run.task_id) if run.task_id else None,
    }


def _task_view(task: Task | None) -> dict[str, Any] | None:
    if task is None:
        return None
    # Task 模型没有 title 列，任务描述即其"标题"（§7 的 task.title 语义）
    return {"id": str(task.id), "title": task.description, "status": task.status}


def _workspace_view(container: Any, settings: Settings) -> dict[str, Any]:
    path = settings.workspace_root or settings.dsh_workspace
    branch: str | None = None
    # Git 信息是尽力而为：未配置工作区根或它根本不是仓库时，如实返回 null，不报错。
    if settings.workspace_root:
        try:
            branches = container.git_client.branches()
            branch = branches.current
        except AIOSError:
            branch = None
    return {"path": path, "git_branch": branch, "git_revision": None}


def _capabilities(settings: Settings) -> list[str]:
    """声明当前 Flux 真实具备的运行时能力（最终方案 §8）。

    缺失即意味着该能力当前不可用，Agent 应据此降级，而不是假设 Runtime 全能力。
    `rollback` 尚未实现，因此不在清单里——不虚报能力。
    """
    capabilities: list[FluxCapability] = [FluxCapability.CONTEXT, FluxCapability.PROPOSAL]
    if settings.workspace_root:
        capabilities += [FluxCapability.WORKSPACE, FluxCapability.APPLY, FluxCapability.GIT]
    return [str(capability) for capability in capabilities]


async def _resolve_run(container: Any, *, agent_id: str, run_id: str | None) -> AgentRun | None:
    """解析当前 Run。指定 run_id 时只返回归属该令牌身份的 Run（不越权读别人的上下文）。"""
    if run_id:
        run = await container.run_repo.get(run_id)
        if run is None or run.agent_id != agent_id:
            return None
        return run
    runs = [r for r in await container.run_repo.list_non_terminal() if r.agent_id == agent_id]
    if not runs:
        return None
    return max(runs, key=lambda r: (r.created_at, r.id))


async def _resolve_task(
    task_repo: TaskRepository, task_id: str | None, run: AgentRun | None
) -> Task | None:
    key: uuid.UUID | None = None
    if task_id:
        try:
            key = uuid.UUID(task_id)
        except ValueError as exc:
            raise ValidationError(
                f"task_id 不是合法 UUID：{task_id}", details={"task_id": task_id}
            ) from exc
    elif run is not None and run.task_id is not None:
        key = run.task_id
    if key is None:
        return None
    try:
        return await task_repo.get(key)
    except AIOSError:
        # 任务被删或不在本库：如实返回 null，而不是让整个上下文查询失败
        return None


def runtime_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="flux_context",
            title="获取 Flux 运行时上下文",
            description=(
                "返回当前 Runtime 身份与能力：platform / run / task / workspace / agent / "
                "policy / capabilities / rules / entry，并回执握手协议（flux-agent v1）。"
                "开工时先调它确认自己在哪里、能做哪些事；"
                "能力缺失时应降级，不要假设 Runtime 全能力。"
            ),
            input_schema=FLUX_CONTEXT_SCHEMA,
            capability=Capability.FILE_READ,
            handler=flux_context,
        ),
    )
