"""Supervisor 的后台 worker：消费 outbox → Gate → Assessment → 落判断。

P1 只做"观察"：判断结果写成可回放的 `supervisor_evals`，推进 trigger 状态，
但不创建 ticket、不起 Run。派活（P2）会在 `_evaluate` 的 accept 分支之后追加，
判断层与服务层不需要改。

崩溃安全：outbox 行在领取后被标记 processing；进程被杀时由 `requeue_stale`
在下一轮退回过期行，因此重启后不会丢事件，也不会重复判断（判断只追加）。
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from flux.core.supervisor.assess import Assessment, SupervisorAssessor
from flux.core.supervisor.gate import GateContext, evaluate, select_policy
from flux.core.supervisor.outbox import SupervisorOutbox
from flux.core.supervisor.repository import SupervisorRepository
from flux.enums import AcceptMode, SupervisorDecision
from flux.logging import get_logger
from flux.models.supervisor import SupervisorOutbox as SupervisorOutboxRow
from flux.models.supervisor import SupervisorTrigger

logger = get_logger(__name__)

__all__ = ["SupervisorWorker"]

#: 事件入队时使用的 topic
TRIGGER_TOPIC = "supervisor.trigger_received"


class SupervisorWorker:
    def __init__(
        self,
        repo: SupervisorRepository,
        outbox: SupervisorOutbox,
        assessor: SupervisorAssessor,
        *,
        poll_seconds: float = 5.0,
        batch_size: int = 10,
    ) -> None:
        self._repo = repo
        self._outbox = outbox
        self._assessor = assessor
        self._poll_seconds = max(0.1, float(poll_seconds))
        self._batch_size = max(1, int(batch_size))
        self._task: asyncio.Task[None] | None = None

    # --- 生命周期 ---

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._task = asyncio.create_task(self._loop(), name="flux-supervisor-worker")

    async def stop(self) -> None:
        task, self._task = self._task, None
        if task is None:
            return
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    # --- 单轮处理（测试与手动触发共用）---

    async def run_once(self, *, limit: int | None = None) -> int:
        """领取并处理一批 outbox 行，返回成功处理的行数。"""
        await self._outbox.requeue_stale(older_than_seconds=600)
        rows = await self._outbox.claim(limit=limit or self._batch_size)
        processed = 0
        for row in rows:
            try:
                await self._handle_row(row)
            except Exception as exc:  # noqa: BLE001 - 单行失败不能拖垮整批
                logger.exception("Supervisor outbox 处理失败 id=%s", row.id)
                await self._outbox.fail(row.id, str(exc))
            else:
                await self._outbox.complete(row.id)
                processed += 1
        return processed

    async def assess_now(self, trigger_id: uuid.UUID) -> dict[str, Any]:
        """手动重判：不看 outbox 状态，直接对指定事件跑 Gate + Assessment。

        只追加判断、推进 trigger 状态；不派活，因此人工重判不会产生副作用。
        """
        trigger = await self._repo.get_trigger(trigger_id)
        assessment = await self.evaluate(trigger, force=True)
        return {
            "trigger": trigger.to_dict(),
            "assessment": _assessment_dict(assessment),
        }

    # --- 判断 ---

    async def evaluate(
        self,
        trigger: SupervisorTrigger,
        *,
        force: bool = False,
    ) -> Assessment | None:
        """对一条事件跑完整判断；`force=True` 时即使 off 模式也执行（人工操作）。"""
        settings = await self._repo.get_settings()
        policies = await self._repo.list_policies(
            source_key=trigger.source_key, project_id=trigger.project_id
        )
        policy = select_policy(
            policies, source_key=trigger.source_key, project_id=trigger.project_id
        )
        mode = _accept_mode(
            policy.accept_mode if policy is not None else None, settings.accept_mode
        )
        if mode is None:
            logger.warning(
                "Supervisor 准入模式配置无效，按 off 处理：trigger=%s value=%s",
                trigger.id,
                policy.accept_mode if policy is not None else settings.accept_mode,
            )
            mode = AcceptMode.OFF

        if mode is AcceptMode.OFF and not force:
            await self._repo.set_trigger_status(trigger.id, "ignored")
            return None

        source = await self._repo.get_source(trigger.source_key)
        rules = dict(policy.rules or {}) if policy is not None else {}
        trigger_types = tuple(policy.trigger_types or []) if policy is not None else ()
        recent = await self._recent_count(trigger, rules)
        verdict = evaluate(
            GateContext(
                accept_mode=mode,
                source_enabled=True if source is None else bool(source.enabled),
                trigger_types=trigger_types,
                rules=rules,
                recent_trigger_count=recent,
            ),
            event_type=trigger.event_type,
            actor_id=trigger.actor_id,
            content=trigger.content,
        )
        if verdict.decision is SupervisorDecision.IGNORE and not force:
            await self._repo.record_eval(
                trigger.id,
                decision=SupervisorDecision.IGNORE,
                reason=verdict.reason,
                risk=verdict.detail,
            )
            await self._repo.set_trigger_status(trigger.id, "ignored")
            return None

        await self._repo.set_trigger_status(trigger.id, "gated")
        assessment = await self._assessor.assess(trigger, provider=settings.model_provider)
        await self._repo.record_eval(
            trigger.id,
            decision=assessment.decision,
            reason=assessment.reason,
            confidence=assessment.confidence,
            risk=assessment.risk,
            model_meta={
                **assessment.model_meta,
                "should_dispatch": assessment.should_dispatch,
                "title": assessment.title,
                "gate": verdict.detail,
            },
            latency_ms=assessment.latency_ms,
        )
        await self._repo.set_trigger_status(
            trigger.id,
            "ignored" if assessment.decision is SupervisorDecision.IGNORE else "assessed",
        )
        return assessment

    # --- 内部 ---

    async def _handle_row(self, row: SupervisorOutboxRow) -> None:
        if row.topic != TRIGGER_TOPIC:
            logger.warning("Supervisor 忽略未知 outbox topic：%s", row.topic)
            return
        raw_id = (row.payload or {}).get("trigger_id")
        if not raw_id:
            raise ValueError(f"outbox {row.id} 缺少 trigger_id")
        trigger = await self._repo.get_trigger(uuid.UUID(str(raw_id)))
        await self.evaluate(trigger)

    async def _recent_count(self, trigger: SupervisorTrigger, rules: dict[str, Any]) -> int:
        window = _positive_int(rules.get("window_seconds"))
        if window is None:
            return 0
        since = datetime.now(timezone.utc) - timedelta(seconds=window)
        others = await self._repo.count_triggers_since(
            source_key=trigger.source_key, since=since, exclude_id=trigger.id
        )
        return others + 1

    async def _loop(self) -> None:
        while True:
            try:
                processed = await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - 循环本身不能因单轮异常退出
                logger.exception("Supervisor worker 单轮处理异常")
                processed = 0
            if processed == 0:
                await asyncio.sleep(self._poll_seconds)


def _accept_mode(policy_value: str | None, settings_value: str | None) -> AcceptMode | None:
    value = policy_value or settings_value or AcceptMode.OBSERVE.value
    try:
        return AcceptMode(value)
    except ValueError:
        return None


def _positive_int(value: object) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        parsed = int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _assessment_dict(assessment: Assessment | None) -> dict[str, Any] | None:
    if assessment is None:
        return None
    return {
        "decision": assessment.decision.value,
        "reason": assessment.reason,
        "confidence": assessment.confidence,
        "risk": assessment.risk,
        "should_dispatch": assessment.should_dispatch,
        "title": assessment.title,
        "model_meta": assessment.model_meta,
        "latency_ms": assessment.latency_ms,
    }
