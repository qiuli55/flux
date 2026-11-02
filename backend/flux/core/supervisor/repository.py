"""Supervisor 持久化读写。

唯一真相源分三类：

- 外部事实：`supervisor_triggers`（幂等键 = 来源 + 事件类型 + external_id）
- 判断事实：`supervisor_evals`（只追加，用于回放"当时为什么这么判"）
- 编排状态：thread / ticket / assignment / report

本文件在 P0 只覆盖"外部事实落库 + 线程收敛"，判断与派发分别由 P1 / P2 追加。
每次调用自开一个会话并提交，与仓库内其它 repository 的约定一致。
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.enums import AcceptMode, SupervisorDecision
from flux.errors import BadRequestError, NotFoundError, ValidationError
from flux.logging import get_logger
from flux.models.project import Project
from flux.models.supervisor import (
    SupervisorEval,
    SupervisorOutbox,
    SupervisorPolicy,
    SupervisorSettings,
    SupervisorSource,
    SupervisorThread,
    SupervisorTrigger,
)

logger = get_logger(__name__)

#: 可在设置接口里修改的列（白名单，避免把 singleton / 时间戳交给调用方）
_SETTINGS_FIELDS = frozenset(
    {
        "accept_mode",
        "model_provider",
        "model_name",
        "max_tickets_per_thread",
        "max_ticket_depth",
        "max_concurrent_runs",
    }
)


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def dedupe_key(source_key: str, event_type: str, external_id: str) -> str:
    """同来源同类型同外部 id 的稳定幂等键（64 位十六进制）。"""
    raw = "\x1f".join((source_key, event_type, external_id)).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class SupervisorRepository:
    """supervisor_* 表的读写入口。"""

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    # --- 设置 ---

    async def get_settings(self) -> SupervisorSettings:
        """读取单行配置；首次访问时创建默认行（observe 模式）。"""
        async with self._session_factory() as session:
            settings = await session.scalar(
                select(SupervisorSettings).where(SupervisorSettings.singleton == 1)
            )
            if settings is None:
                settings = SupervisorSettings(id=uuid.uuid4(), singleton=1)
                session.add(settings)
                await self._commit(session, "Supervisor 设置")
                await session.refresh(settings)
            return settings

    async def update_settings(self, **fields: Any) -> SupervisorSettings:
        """按白名单更新设置；未知字段直接拒绝，避免静默丢参数。"""
        unknown = set(fields) - _SETTINGS_FIELDS
        if unknown:
            raise ValidationError(
                "不支持的 Supervisor 设置项",
                details={"unknown": sorted(unknown)},
            )
        if "accept_mode" in fields:
            _require_accept_mode(fields["accept_mode"])
        async with self._session_factory() as session:
            settings = await session.scalar(
                select(SupervisorSettings).where(SupervisorSettings.singleton == 1)
            )
            if settings is None:
                settings = SupervisorSettings(id=uuid.uuid4(), singleton=1)
                session.add(settings)
            for key, value in fields.items():
                setattr(settings, key, value)
            await self._commit(session, "Supervisor 设置")
            await session.refresh(settings)
            return settings

    # --- 来源 ---

    async def ensure_source(self, key: str, *, kind: str, name: str = "") -> SupervisorSource:
        """按 key 取来源，不存在则登记（事件先到、配置后补是常态）。"""
        async with self._session_factory() as session:
            source = await session.scalar(
                select(SupervisorSource).where(SupervisorSource.key == key)
            )
            if source is None:
                source = SupervisorSource(id=uuid.uuid4(), key=key, kind=kind, name=name or key)
                session.add(source)
                await self._commit(session, "Supervisor 来源")
                await session.refresh(source)
            return source

    async def get_source(self, key: str) -> SupervisorSource | None:
        async with self._session_factory() as session:
            return await session.scalar(select(SupervisorSource).where(SupervisorSource.key == key))

    async def list_sources(self) -> list[SupervisorSource]:
        async with self._session_factory() as session:
            return list(
                await session.scalars(select(SupervisorSource).order_by(SupervisorSource.key))
            )

    # --- 策略 ---

    async def list_policies(
        self,
        *,
        source_key: str,
        project_id: uuid.UUID | None = None,
    ) -> list[SupervisorPolicy]:
        """取候选策略：项目/来源为空的策略视为通配，由 gate.select_policy 排优先级。"""
        statement = select(SupervisorPolicy).where(SupervisorPolicy.enabled.is_(True))
        statement = statement.where(
            (SupervisorPolicy.source_key.is_(None)) | (SupervisorPolicy.source_key == source_key)
        )
        if project_id is None:
            statement = statement.where(SupervisorPolicy.project_id.is_(None))
        else:
            statement = statement.where(
                (SupervisorPolicy.project_id.is_(None))
                | (SupervisorPolicy.project_id == project_id)
            )
        statement = statement.order_by(SupervisorPolicy.created_at, SupervisorPolicy.id)
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    # --- 事件（幂等）---

    async def ingest(
        self,
        *,
        source_key: str,
        external_id: str,
        event_type: str = "message",
        actor_id: str = "",
        actor_platform: str = "",
        thread_key: str = "",
        content: str = "",
        raw: dict | None = None,
        project_id: uuid.UUID | None = None,
        source_kind: str = "external",
    ) -> tuple[SupervisorTrigger, bool]:
        """落一条外部事实。

        返回 `(trigger, created)`：`created=False` 表示该事件此前已入库（重复投递），
        此时绝不重复写 outbox —— 这是"重启不重放、重投不重复派活"的唯一依据。
        """
        key = dedupe_key(source_key, event_type, external_id)
        now = _utcnow()
        async with self._session_factory() as session:
            existing = await session.scalar(
                select(SupervisorTrigger).where(SupervisorTrigger.dedupe_key == key)
            )
            if existing is not None:
                return existing, False

            await self._require_project(session, project_id)
            await self._ensure_source_row(session, source_key, source_kind)

            trigger = SupervisorTrigger(
                id=uuid.uuid4(),
                source_key=source_key,
                external_id=external_id,
                event_type=event_type,
                dedupe_key=key,
                actor_id=actor_id,
                actor_platform=actor_platform,
                thread_key=thread_key,
                project_id=project_id,
                content=content,
                raw=raw or {},
                status="received",
                received_at=now,
            )
            session.add(trigger)
            if thread_key:
                await self._touch_thread(
                    session,
                    thread_key=thread_key,
                    source_key=source_key,
                    external_id=external_id,
                    project_id=project_id,
                    now=now,
                )
            # 与事件同事务入队：进程在提交后崩溃也能按 outbox 重放后续动作。
            session.add(
                SupervisorOutbox(
                    id=uuid.uuid4(),
                    topic="supervisor.trigger_received",
                    payload={"trigger_id": str(trigger.id)},
                    status="pending",
                    attempts=0,
                    available_at=now,
                )
            )
            try:
                await self._commit(session, "Supervisor 事件")
            except BadRequestError:
                # 并发投递同一事件：唯一约束把后到者挡下，回读既有行即可。
                await session.rollback()
                duplicate = await session.scalar(
                    select(SupervisorTrigger).where(SupervisorTrigger.dedupe_key == key)
                )
                if duplicate is not None:
                    return duplicate, False
                raise
            await session.refresh(trigger)
            return trigger, True

    async def list_triggers(
        self,
        *,
        limit: int = 50,
        status: str | None = None,
        project_id: uuid.UUID | None = None,
    ) -> list[SupervisorTrigger]:
        statement = select(SupervisorTrigger).order_by(
            SupervisorTrigger.created_at.desc(), SupervisorTrigger.id.desc()
        )
        if status is not None:
            statement = statement.where(SupervisorTrigger.status == status)
        if project_id is not None:
            statement = statement.where(SupervisorTrigger.project_id == project_id)
        statement = statement.limit(max(1, min(limit, 200)))
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    async def get_trigger(self, trigger_id: uuid.UUID) -> SupervisorTrigger:
        async with self._session_factory() as session:
            trigger = await session.get(SupervisorTrigger, trigger_id)
            if trigger is None:
                raise NotFoundError(
                    f"Supervisor 事件 {trigger_id} 不存在", details={"trigger_id": str(trigger_id)}
                )
            return trigger

    async def set_trigger_status(self, trigger_id: uuid.UUID, status: str) -> None:
        """推进 trigger 的处理状态（received → gated → assessed / ignored）。"""
        async with self._session_factory() as session:
            trigger = await session.get(SupervisorTrigger, trigger_id)
            if trigger is None:
                raise NotFoundError(
                    f"Supervisor 事件 {trigger_id} 不存在", details={"trigger_id": str(trigger_id)}
                )
            trigger.status = status
            await self._commit(session, "Supervisor 事件状态")

    async def count_triggers_since(
        self,
        *,
        source_key: str,
        since: datetime,
        exclude_id: uuid.UUID | None = None,
    ) -> int:
        """统计窗口内同一来源的事件数（Gate 的速率规则用）。"""
        statement = (
            select(func.count())
            .select_from(SupervisorTrigger)
            .where(
                SupervisorTrigger.source_key == source_key,
                SupervisorTrigger.received_at >= since,
            )
        )
        if exclude_id is not None:
            statement = statement.where(SupervisorTrigger.id != exclude_id)
        async with self._session_factory() as session:
            return int(await session.scalar(statement) or 0)

    # --- 判断（只追加）---

    async def record_eval(
        self,
        trigger_id: uuid.UUID,
        *,
        decision: SupervisorDecision,
        reason: str = "",
        confidence: float | None = None,
        risk: dict | None = None,
        model_meta: dict | None = None,
        latency_ms: int | None = None,
    ) -> SupervisorEval:
        evaluation = SupervisorEval(
            id=uuid.uuid4(),
            trigger_id=trigger_id,
            decision=decision.value,
            confidence=confidence,
            reason=reason,
            risk=risk or {},
            model_meta=model_meta or {},
            latency_ms=latency_ms,
        )
        async with self._session_factory() as session:
            session.add(evaluation)
            await self._commit(session, "Supervisor 判断")
            await session.refresh(evaluation)
            return evaluation

    async def latest_evals(self, trigger_ids: list[uuid.UUID]) -> dict[uuid.UUID, SupervisorEval]:
        """按 trigger 取最新一条判断；没有判断的事件不出现在结果里。"""
        if not trigger_ids:
            return {}
        statement = (
            select(SupervisorEval)
            .where(SupervisorEval.trigger_id.in_(trigger_ids))
            .order_by(SupervisorEval.created_at, SupervisorEval.id)
        )
        latest: dict[uuid.UUID, SupervisorEval] = {}
        async with self._session_factory() as session:
            for evaluation in await session.scalars(statement):
                latest[evaluation.trigger_id] = evaluation
        return latest

    async def list_evals(self, trigger_id: uuid.UUID) -> list[SupervisorEval]:
        """一条事件的全部判断历史（升序）；手动重判会追加新行，旧结论不覆盖。"""
        statement = (
            select(SupervisorEval)
            .where(SupervisorEval.trigger_id == trigger_id)
            .order_by(SupervisorEval.created_at, SupervisorEval.id)
        )
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    # --- 内部 ---

    @staticmethod
    async def _require_project(session: AsyncSession, project_id: uuid.UUID | None) -> None:
        if project_id is None:
            return
        exists = await session.scalar(select(Project.id).where(Project.id == project_id))
        if exists is None:
            raise NotFoundError(
                f"项目 {project_id} 不存在", details={"project_id": str(project_id)}
            )

    @staticmethod
    async def _ensure_source_row(session: AsyncSession, key: str, kind: str) -> None:
        exists = await session.scalar(
            select(SupervisorSource.id).where(SupervisorSource.key == key)
        )
        if exists is None:
            session.add(SupervisorSource(id=uuid.uuid4(), key=key, kind=kind, name=key))

    @staticmethod
    async def _touch_thread(
        session: AsyncSession,
        *,
        thread_key: str,
        source_key: str,
        external_id: str,
        project_id: uuid.UUID | None,
        now: datetime,
    ) -> None:
        thread = await session.scalar(
            select(SupervisorThread).where(SupervisorThread.thread_key == thread_key)
        )
        if thread is None:
            session.add(
                SupervisorThread(
                    id=uuid.uuid4(),
                    thread_key=thread_key,
                    source_key=source_key,
                    external_id=external_id,
                    project_id=project_id,
                    last_activity_at=now,
                )
            )
            return
        thread.last_activity_at = now
        if project_id is not None:
            thread.project_id = project_id

    @staticmethod
    async def _commit(session: AsyncSession, label: str) -> None:
        try:
            await session.commit()
        except IntegrityError as exc:
            await session.rollback()
            logger.warning("%s写入违反数据库约束：%s", label, exc.orig)
            raise BadRequestError(f"{label}写入违反数据库约束：{exc.orig}") from exc


def _require_accept_mode(value: object) -> None:
    try:
        AcceptMode(str(value))
    except ValueError as exc:
        raise ValidationError(
            "accept_mode 只能是 off / observe / auto",
            details={"accept_mode": str(value)},
        ) from exc
