"""已导入能力的持久化读写（批次③ §5）。

只做纯 CRUD：冲突判定（fingerprint 比较、keep / replace 决策）属于服务层。
写入时显式给时间戳填 Python 端 UTC 时间（与记忆仓储同因）：SQLite 的
`CURRENT_TIMESTAMP` 只有秒精度，"先导入后替换"在同一秒内也要能排出先后。
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.enums import CapabilityKind
from flux.models.imported_capability import ImportedCapability


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class ImportedCapabilityRepository:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def get(self, kind: CapabilityKind, name: str) -> ImportedCapability | None:
        statement = select(ImportedCapability).where(
            ImportedCapability.kind == kind.value, ImportedCapability.name == name
        )
        async with self._session_factory() as session:
            return await session.scalar(statement)

    async def get_by_id(self, capability_id: str) -> ImportedCapability | None:
        """按 UUID 取一条记录；指定来源优先用 id，找不到再由服务层退回 name。"""
        try:
            parsed = uuid.UUID(str(capability_id))
        except (ValueError, AttributeError, TypeError):
            return None
        async with self._session_factory() as session:
            return await session.get(ImportedCapability, parsed)

    async def list(self, kind: CapabilityKind | None = None) -> list[ImportedCapability]:
        statement = select(ImportedCapability).order_by(
            ImportedCapability.kind, ImportedCapability.name
        )
        if kind is not None:
            statement = statement.where(ImportedCapability.kind == kind.value)
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    async def insert(
        self,
        *,
        kind: CapabilityKind,
        name: str,
        spec: dict[str, Any],
        fingerprint: str,
        source: str = "",
        version: str | None = None,
    ) -> ImportedCapability:
        now = _utcnow()
        record = ImportedCapability(
            kind=kind.value,
            name=name,
            source=source,
            version=version,
            fingerprint=fingerprint,
            spec=spec,
            created_at=now,
            updated_at=now,
        )
        async with self._session_factory() as session:
            session.add(record)
            await session.commit()
            await session.refresh(record)
            return record

    async def update(
        self,
        record: ImportedCapability,
        *,
        spec: dict[str, Any],
        fingerprint: str,
        source: str = "",
        version: str | None = None,
    ) -> ImportedCapability:
        async with self._session_factory() as session:
            stored = await session.get(ImportedCapability, record.id)
            if stored is None:  # pragma: no cover - 同一进程内刚查到，不会消失
                raise RuntimeError(f"导入记录已消失：{record.kind}/{record.name}")
            stored.source = source
            stored.version = version
            stored.fingerprint = fingerprint
            stored.spec = spec
            stored.updated_at = _utcnow()
            await session.commit()
            await session.refresh(stored)
            return stored

    async def set_enabled(
        self, record: ImportedCapability, *, enabled: bool
    ) -> ImportedCapability:
        async with self._session_factory() as session:
            stored = await session.get(ImportedCapability, record.id)
            if stored is None:  # pragma: no cover - 同一进程内刚查到，不会消失
                raise RuntimeError(f"导入记录已消失：{record.kind}/{record.name}")
            stored.enabled = enabled
            stored.updated_at = _utcnow()
            await session.commit()
            await session.refresh(stored)
            return stored

    async def delete(self, record: ImportedCapability) -> None:
        """从注册表移除一条记录；不碰用户磁盘上的源目录。"""
        async with self._session_factory() as session:
            stored = await session.get(ImportedCapability, record.id)
            if stored is not None:
                await session.delete(stored)
                await session.commit()


__all__ = ["ImportedCapabilityRepository"]
