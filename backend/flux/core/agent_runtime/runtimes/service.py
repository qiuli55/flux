"""TaskRunService：任务「开始执行」的编排收口（P2-1 §6.2）。

把原先散在 `api/v1/tasks.py` 里的启动编排（确认卡合并 → 计划/指令拼装 → 预登记 run_id →
起 Run → 落 run_started 消息）抽到这里，按 `agent.config["runtime"]` 分发到对应
`AgentRuntime`。对外 API 与消息落库行为保持不变：DSH 路径返回的 Run 快照仍是
`FluxDshClient` 的原物，CLI 路径返回同形快照。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any

from flux.core.agent_runtime.runtimes.base import (
    CLI_RUNTIMES,
    DEFAULT_RUNTIME,
    AgentRuntime,
)
from flux.core.agent_runtime.runtimes.cli import build_cli_runtime
from flux.core.agent_runtime.runtimes.dsh_runtime import DshRuntime
from flux.core.task_engine.assistant import validate_confirmation_items
from flux.core.task_engine.repository import MessageDraft
from flux.enums import AgentInstallStatus, TaskMessageRole, TaskStatus
from flux.errors import AIOSError, ConflictError, NotFoundError
from flux.logging import get_logger
from flux.models.task import Task

if TYPE_CHECKING:
    from flux.container import Container

logger = get_logger(__name__)

#: 一次对话请求带上的历史条数上限（与 tasks.py 同口径）
_HISTORY_LIMIT = 50


@dataclass(slots=True)
class RunStartResult:
    """一次「开始执行」的结果（API 响应三件套）。"""

    task: Task
    run: dict[str, Any]
    message: Any


class TaskRunService:
    """按 runtime 分发任务启动；run 运行时对象的获取延迟到调用时（测试可替换容器组件）。"""

    def __init__(self, container: Container, *, cli_adapters: Any = ()) -> None:
        self._container = container
        self._cli_adapters = tuple(cli_adapters)

    async def start(self, task: Task, confirmation_request: Any = None) -> RunStartResult:
        container = self._container
        task_uuid = task.id

        confirmation: dict[str, Any] | None = task.confirmation
        if confirmation_request is not None:
            items = validate_confirmation_items(
                [item.model_dump() for item in confirmation_request]
            )
            confirmation = {"items": items, "actor": "user", "updated_at": _now_iso()}
            task = await container.task_repo.set_confirmation(task_uuid, confirmation)

        steps = await _latest_plan(container, task_uuid)
        instruction = _execution_instruction(task, confirmation, steps)

        agent = self._agent(task)
        runtime_id = _runtime_of(agent)
        await self._require_ready(runtime_id)
        runtime = self._runtime(runtime_id)

        # 先登记 run_id 与 running，再真正起 Run：倒过来的话，Run 结束事件可能先于登记到达，
        # 任务就永远收不到自己的终态（P0-05）。
        run_id = uuid.uuid4().hex
        previous_status = task.status
        task = await container.task_repo.set_run_id(task_uuid, run_id)
        task = await container.task_repo.set_status(task_uuid, TaskStatus.RUNNING)
        try:
            launch = await runtime.prepare(
                task=task, agent=agent, run_id=run_id, instruction=instruction
            )
            proc = await runtime.start(launch)
        except AIOSError:
            # 起 Run 失败（未启用 / 令牌签发失败 / patch 写不出 / 装配不齐）时撤回预登记：
            # 否则任务卡在"看起来在跑"，用户既看不到 Agent 也再也点不动「开始执行」。
            await container.task_repo.set_run_id(task_uuid, None)
            task = await container.task_repo.set_status(task_uuid, TaskStatus(previous_status))
            raise

        label = "DSH" if runtime_id == DEFAULT_RUNTIME else runtime_id
        message = (
            await container.task_repo.add_messages(
                task_uuid,
                [
                    MessageDraft(
                        role=TaskMessageRole.ASSISTANT.value,
                        kind="run_started",
                        content=(
                            f"已开始执行（{label} Run {run_id}），"
                            "Agent 的改动会以提案形式进入人工审核队列。"
                        ),
                        payload={
                            "run_id": run_id,
                            "instruction": instruction,
                            "runtime": runtime_id,
                        },
                    )
                ],
            )
        )[0]
        return RunStartResult(task=task, run=proc.snapshot, message=message)

    # --- runtime 解析 ---

    def _agent(self, task: Task) -> Any:
        if not task.agent_id:
            return None
        try:
            return self._container.agents.get(task.agent_id)
        except NotFoundError:
            logger.warning(
                "任务 %s 的 Agent %s 不在注册表中，按默认 runtime 处理", task.id, task.agent_id
            )
            return None

    def _runtime(self, runtime_id: str) -> AgentRuntime:
        container = self._container
        if runtime_id == DEFAULT_RUNTIME:
            return DshRuntime(container.dsh, workspace=container.settings.dsh_workspace)
        return build_cli_runtime(
            runtime_id=runtime_id,
            adapters=self._cli_adapters,
            settings=container.settings,
            supervisor=container.dsh.supervisor,
            bus=container.bus,
            token_service=container.agent_tokens,
        )

    async def _require_ready(self, runtime_id: str) -> None:
        """CLI runtime 启动前必须已接入（installation = READY），否则 409 并给出上线指引。"""
        if runtime_id not in CLI_RUNTIMES:
            return
        row = await self._container.installations.get(runtime_id)
        status = row.status if row is not None else None
        if status != AgentInstallStatus.READY.value:
            raise ConflictError(
                f"{runtime_id} 尚未接入 Flux（当前状态：{status or '未发现'}），"
                f"请先运行 `flux agents connect {runtime_id}`",
                details={"runtime": runtime_id, "installation_status": status},
            )


def _runtime_of(agent: Any) -> str:
    """从 Agent 档案取 runtime（默认 dsh）。"""
    if agent is None:
        return DEFAULT_RUNTIME
    spec = getattr(agent, "spec", None)
    runtime = getattr(spec, "runtime", None) or DEFAULT_RUNTIME
    return str(runtime)


async def _latest_plan(container: Container, task_uuid: uuid.UUID) -> list[str]:
    """取最近一轮助手给出的执行计划（开始执行时一并交给 Agent）。"""
    messages, _ = await container.task_repo.list_messages(task_uuid, limit=_HISTORY_LIMIT)
    for message in reversed(messages):
        steps = (message.payload or {}).get("steps")
        if message.role == TaskMessageRole.ASSISTANT.value and isinstance(steps, list) and steps:
            return [str(step) for step in steps]
    return []


def _execution_instruction(
    task: Task, confirmation: dict[str, Any] | None, steps: list[str]
) -> str:
    """拼出交给 Agent 的执行指令：任务描述 + 用户确认过的需求 + 计划 + 能力边界。"""
    lines = [task.description.strip()]
    items = (confirmation or {}).get("items")
    if isinstance(items, list) and items:
        lines += ["", "## 需求确认（用户已确认，以此为准）"]
        lines += [f"- {item['label']}：{item['value']}" for item in items]
    if steps:
        lines += ["", "## 执行计划"]
        lines += [f"{index + 1}. {step}" for index, step in enumerate(steps)]
    lines += [
        "",
        "## 平台身份（调用 Flux MCP 时带上）",
        f"- task_id：{task.id}",
        f"- project_id：{task.project_id or '未关联'}",
        "- 开工先调 `context.get` 并带上 task_id：它会返回项目 Brain（项目结构、技术栈、"
        "入口文件）与近期提案；不确定文件路径时以它为准，再用 workspace.read 核对内容。",
    ]
    lines += [
        "",
        "## 执行约束",
        "- 只能通过 Flux MCP 读取项目上下文并把改动提交为提案；"
        "不要尝试直接写文件、执行 shell 或读取密钥。",
        "- 改动一律进入人工审核队列，落盘由人审通过后经 Apply Engine 执行，并在落盘时跑项目测试。",
    ]
    return "\n".join(lines)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


__all__ = ["RunStartResult", "TaskRunService"]
