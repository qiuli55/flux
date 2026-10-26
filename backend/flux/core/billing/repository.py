"""计费账本与官方额度快照的持久化。

写入只追加（usage_records 是明细账，不做更新）；读取以「套餐 × 窗口起点」聚合，
不建物化表——窗口只是时间切片，随时可以从明细重算。
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.models.usage import UsageRecord, UsageSnapshot


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _parse_uuid(value: str | uuid.UUID | None) -> uuid.UUID | None:
    if value is None:
        return None
    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError):
        return None


class BillingRepository:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    # --- 明细账 ---

    async def add_record(
        self,
        *,
        provider: str,
        model: str,
        plan_key: str | None,
        input_tokens: int = 0,
        output_tokens: int = 0,
        cache_read_tokens: int = 0,
        cache_write_tokens: int = 0,
        cost: float | None = None,
        run_id: str | None = None,
        task_id: str | uuid.UUID | None = None,
        agent_id: str | uuid.UUID | None = None,
        occurred_at: datetime | None = None,
    ) -> None:
        total = int(input_tokens) + int(output_tokens)
        row = UsageRecord(
            provider=provider[:32],
            model=model[:128],
            plan_key=plan_key,
            input_tokens=int(input_tokens),
            output_tokens=int(output_tokens),
            cache_read_tokens=int(cache_read_tokens),
            cache_write_tokens=int(cache_write_tokens),
            total_tokens=total,
            cost=float(cost) if cost is not None else None,
            run_id=run_id,
            task_id=_parse_uuid(task_id),
            agent_id=_parse_uuid(agent_id),
            occurred_at=occurred_at or _utcnow(),
        )
        async with self._session_factory() as session:
            session.add(row)
            await session.commit()

    async def totals(self, *, plan_key: str | None, since: datetime) -> dict[str, float]:
        """某套餐自 `since` 起的本地用量合计。"""
        statement = select(
            func.count(UsageRecord.id),
            func.coalesce(func.sum(UsageRecord.input_tokens), 0),
            func.coalesce(func.sum(UsageRecord.output_tokens), 0),
            func.coalesce(func.sum(UsageRecord.cache_read_tokens), 0),
            func.coalesce(func.sum(UsageRecord.cache_write_tokens), 0),
            func.coalesce(func.sum(UsageRecord.total_tokens), 0),
            func.coalesce(func.sum(UsageRecord.cost), 0.0),
        ).where(UsageRecord.occurred_at >= since)
        if plan_key:
            statement = statement.where(UsageRecord.plan_key == plan_key)
        async with self._session_factory() as session:
            row = (await session.execute(statement)).one()
        return {
            "records": int(row[0] or 0),
            "input_tokens": int(row[1] or 0),
            "output_tokens": int(row[2] or 0),
            "cache_read_tokens": int(row[3] or 0),
            "cache_write_tokens": int(row[4] or 0),
            "total_tokens": int(row[5] or 0),
            "cost": round(float(row[6] or 0.0), 6),
        }

    async def records_by_run(self, run_id: str, *, limit: int = 200) -> list[UsageRecord]:
        statement = (
            select(UsageRecord)
            .where(UsageRecord.run_id == run_id)
            .order_by(UsageRecord.occurred_at)
            .limit(max(limit, 1))
        )
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    # --- 官方快照 ---

    async def add_snapshot(
        self,
        *,
        plan_key: str,
        window_key: str,
        used: float | None = None,
        quota: float | None = None,
        percent: float | None = None,
        unit: str = "percent",
        status: str = "ok",
        resets_at: datetime | None = None,
        source: str = "",
        error: str | None = None,
        fetched_at: datetime | None = None,
    ) -> None:
        row = UsageSnapshot(
            plan_key=plan_key,
            window_key=window_key,
            used=used,
            quota=quota,
            percent=percent,
            unit=unit,
            status=status,
            resets_at=resets_at,
            source=source,
            error=error,
            fetched_at=fetched_at or _utcnow(),
        )
        async with self._session_factory() as session:
            session.add(row)
            await session.commit()

    async def latest_snapshots(self, plan_key: str, *, lookback_days: int = 2) -> list[dict]:
        """每个窗口取最新一条（按 fetched_at 倒序去重）。"""
        since = _utcnow() - timedelta(days=max(lookback_days, 1))
        statement = (
            select(UsageSnapshot)
            .where(UsageSnapshot.plan_key == plan_key, UsageSnapshot.fetched_at >= since)
            .order_by(UsageSnapshot.fetched_at.desc())
        )
        async with self._session_factory() as session:
            rows = list(await session.scalars(statement))
        latest: dict[str, dict] = {}
        for row in rows:
            if row.window_key in latest:
                continue
            latest[row.window_key] = row.to_dict()
        return list(latest.values())


__all__ = ["BillingRepository"]
