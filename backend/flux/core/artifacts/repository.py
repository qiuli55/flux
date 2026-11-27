"""Artifacts 持久化：页面 / 文档成品的读写与乐观锁。

表里只有 Flux 自己拥有的 page / doc；设计稿是 Luma 的投影（`design:<id>`），
由 API 层聚合，仓储层不感知。`version` 是乐观锁：客户端带 `base_version`
更新时版本不一致直接 409，避免两个入口（聊天 Agent / App 编辑）互相覆盖。
"""

from __future__ import annotations

import uuid

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.errors import ConflictError, NotFoundError
from flux.models.artifact import Artifact


def _as_uuid(value: str | uuid.UUID) -> uuid.UUID:
    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise NotFoundError(f"产物 {value} 不存在", details={"artifact_id": str(value)}) from exc


class ArtifactRepository:
    """`artifacts` 表的读写入口。每次调用自开一个会话并提交。"""

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def list(self, kind: str | None = None) -> list[Artifact]:
        statement = select(Artifact)
        if kind is not None:
            statement = statement.where(Artifact.type == kind)
        statement = statement.order_by(Artifact.updated_at.desc())
        async with self._session_factory() as session:
            rows = await session.execute(statement)
            return list(rows.scalars().all())

    async def get(self, artifact_id: str | uuid.UUID) -> Artifact:
        async with self._session_factory() as session:
            artifact = await session.get(Artifact, _as_uuid(artifact_id))
            if artifact is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
            return artifact

    async def create(
        self,
        *,
        type: str,
        title: str,
        content: str = "",
        meta: dict | None = None,
    ) -> Artifact:
        artifact = Artifact(
            id=uuid.uuid4(),
            type=type,
            title=title,
            content=content,
            version=1,
            meta=meta or {},
        )
        async with self._session_factory() as session:
            session.add(artifact)
            await session.commit()
            await session.refresh(artifact)
            return artifact

    async def update(
        self,
        artifact_id: str | uuid.UUID,
        *,
        title: str | None = None,
        content: str | None = None,
        base_version: int | None = None,
        meta: dict | None = None,
    ) -> Artifact:
        async with self._session_factory() as session:
            artifact = await session.get(Artifact, _as_uuid(artifact_id))
            if artifact is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
            if base_version is not None and artifact.version != base_version:
                raise ConflictError(
                    "产物已被更新，请刷新后再改",
                    code="artifact_conflict",
                    details={
                        "artifact_id": str(artifact.id),
                        "version": artifact.version,
                        "base_version": base_version,
                    },
                )
            if title is not None:
                artifact.title = title[:160]
            if content is not None:
                artifact.content = content
            if meta:
                # 合并而不是覆盖：更新正文时不能把已有来源字段（如 task_id）弄丢。
                artifact.meta = {**(artifact.meta or {}), **meta}
            artifact.version += 1
            await session.commit()
            await session.refresh(artifact)
            return artifact

    async def delete(self, artifact_id: str | uuid.UUID) -> None:
        async with self._session_factory() as session:
            result = await session.execute(
                delete(Artifact).where(Artifact.id == _as_uuid(artifact_id))
            )
            await session.commit()
            if result.rowcount == 0:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )


__all__ = ["ArtifactRepository"]
