"""把 DSH Run 的终态回写到它所属的任务（P0-05「开始执行」链路的收尾）。

平台自己不起 Run——Run 由 FluxDshClient 交给后台任务跑。Run 结束时会发
`dsh.run_completed / failed / cancelled` 事件，本桥接订阅这三个事件，
按 `tasks.run_id` 找到任务并落终态 + 追加一条真实的结果消息。

两条不做的事，都很明确：

1. 不认识的 run_id 一律忽略——DSH Run 也可以由 `/dsh/runs` 独立发起，没有对应任务很正常；
2. 任务已经停在 `waiting_for_user_decision` 时不动状态——那是用户要求的暂停，
   平台不能因为 run 线程结束就把"等人决策"改写成完成/失败（文档 §5 必须测试项）。

事件是"顺路"送达的：Flux 崩溃时事件会丢，任务就可能永久停在 running（P2-04 / TC-502）。
因此本模块还提供 `reconcile_orphan_tasks()`，把"没有活的 Run 支撑的 running 任务"收尾。
"""

from __future__ import annotations

from typing import Any

from flux.core.agent_runtime.run_repository import AgentRunRepository, as_utc, is_terminal, utcnow
from flux.core.event.bus import EventBus, Events
from flux.core.task_engine.repository import MessageDraft, TaskRepository
from flux.enums import DshRunStatus, TaskMessageRole, TaskStatus
from flux.logging import get_logger

logger = get_logger(__name__)

#: 已是终态的任务不再被 Run 事件改写
_FINAL_STATUSES = frozenset(
    {TaskStatus.COMPLETED.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value}
)

_STATUS_BY_EVENT = {
    Events.DSH_RUN_COMPLETED: TaskStatus.COMPLETED,
    Events.DSH_RUN_FAILED: TaskStatus.FAILED,
    Events.DSH_RUN_CANCELLED: TaskStatus.CANCELLED,
    # P2-15：超时与「重启后接管」都不是成功，任务按失败落终态，不留 running 僵尸
    Events.DSH_RUN_TIMEOUT: TaskStatus.FAILED,
    Events.DSH_RUN_INTERRUPTED: TaskStatus.FAILED,
}


#: 对账宽限期（秒）：任务刚被推进 running 时，Run 的行可能还没建好（start_task 先登记
#: run_id 再真正起 Run）。只看"安静了足够久"的 running 任务，避免把正在起跑的 Run 判成孤儿。
_ORPHAN_GRACE_SECONDS = 120.0

#: Run 终态 → 任务终态（对账用；超时/中断都不是成功，按失败落）
_TASK_STATUS_BY_RUN = {
    DshRunStatus.COMPLETED: TaskStatus.COMPLETED,
    DshRunStatus.CANCELLED: TaskStatus.CANCELLED,
    DshRunStatus.FAILED: TaskStatus.FAILED,
    DshRunStatus.TIMEOUT: TaskStatus.FAILED,
    DshRunStatus.INTERRUPTED: TaskStatus.FAILED,
}


class TaskRunBridge:
    """DSH Run 终态 → 任务状态 / 结果消息。"""

    def __init__(self, tasks: TaskRepository, runs: AgentRunRepository | None = None) -> None:
        self._tasks = tasks
        # 对账要拿 Run 的权威状态；纯事件转发（单测）可以不装配
        self._runs = runs

    def attach(self, bus: EventBus) -> None:
        for event in _STATUS_BY_EVENT:
            bus.subscribe(event, self._on_run_settled)

    async def reconcile_orphan_tasks(self) -> list[str]:
        """收尾"没有活 Run 支撑"的 running 任务（P2-04 / TC-502）。

        任务的 running 只有在一个仍然活着的 Run 支撑时才成立，两种情况都要收尾：

        - `run_id` 为空：从没有 Run 被真正起过（例如对话把 pending 推进了 running），
          任务没有在执行 → 退回 `pending`，用户仍可再点「开始执行」；
        - `run_id` 指向的 Run 已终态或已不存在：Run 结束事件在崩溃时丢了 →
          按 Run 的真实终态落任务终态，不再留在 running。
        """
        fixed: list[str] = []
        cutoff = utcnow().timestamp() - _ORPHAN_GRACE_SECONDS
        for task in await self._tasks.list(status=TaskStatus.RUNNING, limit=200):
            if as_utc(task.updated_at).timestamp() > cutoff:
                continue  # 刚动过：可能正在起跑，给它时间
            run_id = task.run_id
            if not run_id:
                await self._tasks.set_status(task.id, TaskStatus.PENDING)
                note = "任务此前未真正开始执行（没有关联的 Agent Run），状态已退回待执行。"
                await self._tasks.add_messages(
                    task.id,
                    [
                        MessageDraft(
                            role=TaskMessageRole.ASSISTANT.value,
                            kind="run_finished",
                            content=note,
                            payload={"status": str(TaskStatus.PENDING), "reason": "orphan_no_run"},
                        )
                    ],
                )
                logger.warning("任务对账修正 task=%s running → pending（无关联 Run）", task.id)
                fixed.append(str(task.id))
                continue
            run = await self._runs.get(run_id) if self._runs is not None else None
            if run is not None and not is_terminal(run.status):
                continue  # Run 还活着：任务确实在执行，不动
            status = (
                _TASK_STATUS_BY_RUN.get(DshRunStatus(run.status), TaskStatus.FAILED)
                if run is not None
                else TaskStatus.FAILED
            )
            error = run.error if run is not None else None
            await self._tasks.set_status(task.id, status)
            await self._tasks.add_messages(
                task.id,
                [
                    MessageDraft(
                        role=TaskMessageRole.ASSISTANT.value,
                        kind="run_finished",
                        content=_summary(status, {"run_id": run_id, "error": error}),
                        payload={
                            "run_id": run_id,
                            "status": str(status),
                            "error": error,
                            "reason": "orphan_reconciled",
                        },
                    )
                ],
            )
            logger.warning(
                "任务对账修正 task=%s running → %s（Run %s 已终态/不存在）",
                task.id,
                status,
                run_id,
            )
            fixed.append(str(task.id))
        return fixed

    async def _on_run_settled(self, event: str, body: dict[str, Any]) -> None:
        run_id = body.get("run_id")
        if not isinstance(run_id, str) or not run_id:
            return
        task = await self._tasks.get_by_run_id(run_id)
        if task is None:
            return
        if task.status in _FINAL_STATUSES:
            return
        if task.status == TaskStatus.WAITING_FOR_USER_DECISION.value:
            logger.info("dsh.run 结束但任务正等人决策，保留等待态 task=%s run=%s", task.id, run_id)
            return
        status = _STATUS_BY_EVENT[event]
        await self._tasks.set_status(task.id, status)
        await self._tasks.add_messages(
            task.id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="run_finished",
                    content=_summary(status, body),
                    payload={
                        "run_id": run_id,
                        "status": str(status),
                        "finish_reason": body.get("finish_reason"),
                        "error": body.get("error"),
                    },
                )
            ],
        )


def _summary(status: TaskStatus, body: dict[str, Any]) -> str:
    """结果消息正文：优先用 Agent 的最终回复原文，没有才用状态兜底文案。"""
    if status is TaskStatus.COMPLETED:
        response = body.get("final_response")
        if isinstance(response, str) and response.strip():
            return response.strip()
        return "Agent 执行完成。"
    if status is TaskStatus.FAILED:
        error = body.get("error")
        if error:
            return f"Agent 执行失败：{error}"
        timeout_kind = body.get("timeout_kind")
        if timeout_kind:
            return f"Agent 执行超时（{timeout_kind}），已终止。"
        return "Agent 执行失败。"
    return "Agent 执行已取消。"


__all__ = ["TaskRunBridge"]
