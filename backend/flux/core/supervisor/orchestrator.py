"""Supervisor 的编排层：把"判断通过"翻译成"工单树 + 派发 + 回收"。

三件事分开做，谁都不越权：

- 规划（planner）：模型只提建议，平台做硬校验，失败就降级成单工单；
- 落库（repository）：同一条 trigger 重复规划不会拆出第二棵树（plan_key 前缀幂等）；
- 派发（dispatcher）：角色必须真实存在，任务/运行状态一律以 Task Engine 为准。

本层不持有状态，所有事实都在 supervisor_* 与 tasks 表里，进程重启可继续。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.core.supervisor.dispatcher import DispatchOutcome, SupervisorDispatcher, derive_stage
from flux.core.supervisor.outbox import SupervisorOutbox
from flux.core.supervisor.planner import SupervisorPlanner, TicketPlan
from flux.core.supervisor.repository import SupervisorRepository
from flux.enums import TicketStage
from flux.logging import get_logger
from flux.models.supervisor import SupervisorTicket, SupervisorTrigger

logger = get_logger(__name__)

__all__ = ["SupervisorOrchestrator"]

#: 占用并发位的阶段：running / waiting（等用户决策时 Run 可能还活着）
_ACTIVE_STAGE_VALUES = frozenset({TicketStage.RUNNING.value, TicketStage.WAITING.value})


class SupervisorOrchestrator:
    """规划 → 落库 → 派发 → 对账的唯一入口。"""

    def __init__(
        self,
        repo: SupervisorRepository,
        planner: SupervisorPlanner,
        dispatcher: SupervisorDispatcher,
        *,
        outbox: SupervisorOutbox | None = None,
    ) -> None:
        self._repo = repo
        self._planner = planner
        self._dispatcher = dispatcher
        self._outbox = outbox

    # --- 规划 ---

    async def plan_trigger(
        self,
        trigger: SupervisorTrigger,
        *,
        title: str = "",
        dispatch: bool = False,
    ) -> dict[str, Any]:
        """为一条已确认的事件产出工单树；`dispatch=True` 时顺手派发可执行工单。"""
        settings = await self._repo.get_settings()
        thread = await self._repo.ensure_thread(
            thread_key=_thread_key(trigger),
            source_key=trigger.source_key,
            external_id=trigger.external_id,
            project_id=trigger.project_id,
        )
        plan = await self._planner.plan(
            trigger,
            title=title,
            roles=self._dispatcher.roles(),
            max_tickets=settings.max_tickets_per_thread,
            max_depth=settings.max_ticket_depth,
            provider=settings.model_provider,
        )
        tickets = await self._repo.create_tickets(
            thread_id=thread.id,
            project_id=trigger.project_id or thread.project_id,
            plan=plan,
            key_prefix=trigger.id.hex[:8],
        )
        await self._repo.set_trigger_status(trigger.id, "planned")
        outcomes: list[DispatchOutcome] = []
        if dispatch:
            outcomes = await self.dispatch_thread(
                thread.id, max_concurrent_runs=settings.max_concurrent_runs
            )
        return {
            "thread": thread.to_dict(),
            "plan": _plan_dict(plan),
            "tickets": await self._ticket_items(tickets),
            "dispatch": [outcome.to_dict() for outcome in outcomes],
        }

    # --- 派发 ---

    async def dispatch_thread(
        self,
        thread_id: uuid.UUID,
        *,
        max_concurrent_runs: int | None = None,
    ) -> list[DispatchOutcome]:
        """按 sequence 派发依赖已完成的工单，并受并发位约束。"""
        await self._repo.get_thread(thread_id)
        tickets = await self._repo.list_tickets(thread_id=thread_id, limit=200)
        tickets.sort(key=lambda ticket: (ticket.sequence, str(ticket.id)))
        await self._refresh(tickets)
        items = await self._ticket_items(tickets)

        stages = {item["id"]: item["stage"] for item in items}
        by_id = {str(ticket.id): ticket for ticket in tickets}
        plan_keys = {ticket.id: ticket.plan_key for ticket in tickets}
        active = sum(1 for item in items if item["stage"] in _ACTIVE_STAGE_VALUES)
        if max_concurrent_runs is None:
            settings = await self._repo.get_settings()
            max_concurrent_runs = settings.max_concurrent_runs
        available = max(0, int(max_concurrent_runs) - active)

        outcomes: list[DispatchOutcome] = []
        for item in items:
            if available <= 0:
                break
            if item["stage"] != TicketStage.PLANNED.value:
                continue
            ticket = by_id[item["id"]]
            if not _dependencies_done(ticket, plan_keys, stages):
                continue
            outcome = await self._dispatcher.dispatch_ticket(ticket)
            outcomes.append(outcome)
            if outcome.dispatched:
                available -= 1
        return outcomes

    async def retry_ticket(self, ticket_id: uuid.UUID) -> dict[str, Any]:
        ticket = await self._repo.get_ticket(ticket_id)
        outcome = await self._dispatcher.retry_ticket(ticket)
        return outcome.to_dict()

    async def dispatch_ticket(self, ticket_id: uuid.UUID) -> dict[str, Any]:
        """单张工单手动派发；已有 task 的工单不会被重复派活。"""
        ticket = await self._repo.get_ticket(ticket_id)
        return (await self._dispatcher.dispatch_ticket(ticket)).to_dict()

    async def stop_ticket(self, ticket_id: uuid.UUID, *, reason: str = "") -> dict[str, Any]:
        ticket = await self._repo.get_ticket(ticket_id)
        return await self._dispatcher.stop_ticket(ticket, reason=reason)

    # --- 对账 / 查询 ---

    async def reconcile(self, *, limit: int = 50) -> int:
        """把任务的真实终态物化成报告；worker 每轮与读接口都会调用。"""
        tickets = await self._repo.list_tickets(limit=limit)
        await self._refresh(tickets)
        return len(tickets)

    async def overview(self, *, limit: int = 20) -> dict[str, Any]:
        settings = await self._repo.get_settings()
        tickets = await self._repo.list_tickets(limit=limit)
        await self._refresh(tickets)
        items = await self._ticket_items(tickets)
        counts: dict[str, int] = {stage.value: 0 for stage in TicketStage}
        for item in items:
            counts[item["stage"]] = counts.get(item["stage"], 0) + 1
        return {
            "settings": settings.to_dict(),
            "counts": counts,
            "tickets": items,
            "pending_outbox": await self._outbox.pending_count() if self._outbox else 0,
        }

    async def tickets(
        self,
        *,
        thread_id: uuid.UUID | None = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        tickets = await self._repo.list_tickets(thread_id=thread_id, limit=limit)
        await self._refresh(tickets)
        return await self._ticket_items(tickets)

    async def ticket_detail(self, ticket_id: uuid.UUID) -> dict[str, Any]:
        ticket = await self._repo.get_ticket(ticket_id)
        await self._refresh([ticket])
        item = (await self._ticket_items([ticket]))[0]
        item["assignments"] = [
            assignment.to_dict() for assignment in await self._repo.list_assignments(ticket_id)
        ]
        item["reports"] = [report.to_dict() for report in await self._repo.list_reports(ticket_id)]
        return item

    # --- 内部 ---

    async def _refresh(self, tickets: list[SupervisorTicket]) -> None:
        """只对"在途"的工单物化报告，已收尾的工单不重复写。"""
        if not tickets:
            return
        latest = await self._repo.latest_assignments([ticket.id for ticket in tickets])
        for ticket in tickets:
            assignment = latest.get(ticket.id)
            if assignment is not None and assignment.status in {"dispatched", "running"}:
                await self._dispatcher.sync_ticket(ticket)

    async def _ticket_items(self, tickets: list[SupervisorTicket]) -> list[dict[str, Any]]:
        if not tickets:
            return []
        ticket_ids = [ticket.id for ticket in tickets]
        assignments = await self._repo.latest_assignments(ticket_ids)
        reports = await self._repo.latest_reports(ticket_ids)
        task_ids = [
            assignment.task_id
            for assignment in assignments.values()
            if assignment.task_id is not None
        ]
        task_statuses = await self._repo.task_statuses(task_ids)
        items: list[dict[str, Any]] = []
        for ticket in tickets:
            assignment = assignments.get(ticket.id)
            report = reports.get(ticket.id)
            stage = derive_stage(
                task_status=(
                    task_statuses.get(assignment.task_id)
                    if assignment is not None and assignment.task_id is not None
                    else None
                ),
                assignment_status=assignment.status if assignment is not None else None,
                report_status=report.status if report is not None else None,
            )
            item = ticket.to_dict(stage=stage.value)
            item["assignment"] = assignment.to_dict() if assignment is not None else None
            item["report"] = report.to_dict() if report is not None else None
            items.append(item)
        return items


def _thread_key(trigger: SupervisorTrigger) -> str:
    """线程键：平台给了就用平台的，没给就按单条事件隔离，避免串会话。"""
    key = (trigger.thread_key or "").strip()
    return key or f"{trigger.source_key}:{trigger.id.hex}"


def _plan_dict(plan: TicketPlan) -> dict[str, Any]:
    return {
        "degraded": plan.degraded,
        "reason": plan.reason,
        "latency_ms": plan.latency_ms,
        "model_meta": plan.model_meta,
        "tickets": [
            {
                "key": ticket.key,
                "title": ticket.title,
                "role": ticket.role,
                "depends_on": list(ticket.depends_on),
                "sequence": ticket.sequence,
            }
            for ticket in plan.tickets
        ],
    }


def _dependencies_done(
    ticket: SupervisorTicket,
    plan_keys: dict[uuid.UUID, str],
    stages: dict[str, str],
) -> bool:
    """依赖的工单必须全部 done；plan_key → ticket id 反查用同一份快照。"""
    if not ticket.depends_on:
        return True
    by_key = {plan_key: ticket_id for ticket_id, plan_key in plan_keys.items()}
    for dependency in ticket.depends_on:
        dependency_id = by_key.get(dependency)
        if dependency_id is None:
            continue
        if stages.get(str(dependency_id)) != TicketStage.DONE.value:
            return False
    return True
