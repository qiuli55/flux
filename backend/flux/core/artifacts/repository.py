"""Artifacts 持久化：页面 / 文档成品的读写、版本历史与资产库。

表里只有 Flux 自己拥有的 page / doc；设计稿是 Luma 的投影（`design:<id>`），
由 API 层聚合，仓储层不感知。`version` 既是乐观锁也是当前版本号：客户端带
`base_version` 更新时版本不一致直接 409（带 `force` 才覆盖），避免两个入口
（聊天 Agent / App 编辑）互相覆盖；每次成功写入都会追加一条版本快照。

资产元数据落 `artifact_assets`，实体文件落 `<uploads_dir>/artifacts/<id>/`；
删除产物时一并清理版本、资产行与文件。
"""

from __future__ import annotations

import hashlib
import shutil
import uuid
from pathlib import Path

from sqlalchemy import delete as sql_delete
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.config import Settings
from flux.core.artifacts.content import (
    ASSET_MAX_FILES,
    ASSET_MAX_TOTAL_BYTES,
    asset_suffix,
    clean_capabilities,
    clean_contract,
    clean_description,
    clean_icon,
    clean_label,
    create_title,
    update_title,
    validate_asset,
    validate_content,
)
from flux.core.attachment_store import safe_name
from flux.errors import ConflictError, NotFoundError, ValidationError
from flux.models.artifact import Artifact, ArtifactAsset, ArtifactVersion


def _as_uuid(value: str | uuid.UUID) -> uuid.UUID:
    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise NotFoundError(f"产物 {value} 不存在", details={"artifact_id": str(value)}) from exc


def _snapshot(artifact: Artifact) -> ArtifactVersion:
    return ArtifactVersion(
        id=uuid.uuid4(),
        artifact_id=artifact.id,
        version=artifact.version,
        title=artifact.title,
        content=artifact.content or "",
        description=artifact.description or "",
        icon=artifact.icon or "",
        label=artifact.label or "",
        contract=artifact.contract,
        capabilities=artifact.capabilities or {},
    )


def _usage(files: int, total_bytes: int) -> dict[str, int]:
    return {
        "files": int(files or 0),
        "bytes": int(total_bytes or 0),
        "max_files": ASSET_MAX_FILES,
        "max_bytes": ASSET_MAX_TOTAL_BYTES,
    }


class ArtifactRepository:
    """`artifacts` / `artifact_versions` / `artifact_assets` 的读写入口。

    每次调用自开一个会话并提交；跨表的写入（产物 + 版本快照）在同一事务里完成。
    """

    def __init__(
        self, session_factory: async_sessionmaker[AsyncSession], settings: Settings
    ) -> None:
        self._session_factory = session_factory
        self._settings = settings

    # ---------------------------------------------------------------- 产物

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
        title: str | None = None,
        content: str = "",
        description: str | None = None,
        icon: str | None = None,
        label: str | None = None,
        contract: str | None = None,
        capabilities: dict | None = None,
        pinned: bool = False,
        meta: dict | None = None,
    ) -> Artifact:
        validate_content(type, content)
        artifact = Artifact(
            id=uuid.uuid4(),
            type=type,
            title=create_title(type, content, title),
            content=content,
            description=clean_description(description),
            icon=clean_icon(icon),
            label=clean_label(label),
            contract=clean_contract(contract),
            capabilities=clean_capabilities(capabilities) if capabilities is not None else {},
            pinned=bool(pinned),
            version=1,
            meta=meta or {},
        )
        async with self._session_factory() as session:
            session.add(artifact)
            session.add(_snapshot(artifact))
            await session.commit()
            await session.refresh(artifact)
            return artifact

    async def update(
        self,
        artifact_id: str | uuid.UUID,
        *,
        title: str | None = None,
        content: str | None = None,
        description: str | None = None,
        icon: str | None = None,
        label: str | None = None,
        contract: str | None = None,
        capabilities: dict | None = None,
        base_version: int | None = None,
        force: bool = False,
        meta: dict | None = None,
    ) -> Artifact:
        async with self._session_factory() as session:
            artifact = await session.get(Artifact, _as_uuid(artifact_id))
            if artifact is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
            if base_version is not None and artifact.version != base_version and not force:
                current = artifact.to_dict(include_content=True)
                raise ConflictError(
                    "产物已被更新，请把改动合并到当前版本后重试；只有明确要丢弃新版本时才带 force",
                    code="artifact_conflict",
                    details={
                        "artifact_id": str(artifact.id),
                        "version": artifact.version,
                        "base_version": base_version,
                        "title": artifact.title,
                        "content": artifact.content or "",
                        "description": artifact.description or "",
                        "icon": artifact.icon or "",
                        "label": artifact.label or "",
                        "contract": artifact.contract,
                        "capabilities": artifact.capabilities or {},
                        "pinned": bool(artifact.pinned),
                        "current": current,
                    },
                )

            new_content = artifact.content or ""
            if content is not None:
                validate_content(artifact.type, content)
                new_content = content
            artifact.content = new_content
            artifact.title = update_title(
                artifact.type,
                content=new_content,
                incoming_title=title,
                existing_title=artifact.title,
                content_changed=content is not None,
            )
            if description is not None:
                artifact.description = clean_description(description)
            if icon is not None:
                artifact.icon = clean_icon(icon)
            if label is not None:
                artifact.label = clean_label(label)
            if contract is not None:
                artifact.contract = clean_contract(contract)
            if capabilities is not None:
                artifact.capabilities = clean_capabilities(capabilities)
            if meta:
                # 合并而不是覆盖：更新正文时不能把已有来源字段（如 task_id）弄丢。
                artifact.meta = {**(artifact.meta or {}), **meta}
            artifact.version += 1
            session.add(_snapshot(artifact))
            await session.commit()
            await session.refresh(artifact)
            return artifact

    async def set_pinned(self, artifact_id: str | uuid.UUID, pinned: bool) -> Artifact:
        """只改钉选，不递增版本、不追加快照（钉选不是内容变更）。"""
        async with self._session_factory() as session:
            artifact = await session.get(Artifact, _as_uuid(artifact_id))
            if artifact is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
            artifact.pinned = bool(pinned)
            await session.commit()
            await session.refresh(artifact)
            return artifact

    async def delete(self, artifact_id: str | uuid.UUID) -> None:
        artifact_uuid = _as_uuid(artifact_id)
        async with self._session_factory() as session:
            asset_rows = await session.execute(
                select(ArtifactAsset.storage_path).where(ArtifactAsset.artifact_id == artifact_uuid)
            )
            paths = [Path(item) for item in asset_rows.scalars().all()]
            await session.execute(
                sql_delete(ArtifactVersion).where(ArtifactVersion.artifact_id == artifact_uuid)
            )
            await session.execute(
                sql_delete(ArtifactAsset).where(ArtifactAsset.artifact_id == artifact_uuid)
            )
            result = await session.execute(sql_delete(Artifact).where(Artifact.id == artifact_uuid))
            await session.commit()
            if result.rowcount == 0:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
        for path in paths:
            path.unlink(missing_ok=True)
        shutil.rmtree(self._asset_folder(artifact_uuid), ignore_errors=True)

    # ---------------------------------------------------------------- 版本历史

    async def list_versions(
        self,
        artifact_id: str | uuid.UUID,
        *,
        limit: int | None = None,
        offset: int = 0,
    ) -> tuple[list[ArtifactVersion], int]:
        artifact_uuid = _as_uuid(artifact_id)
        async with self._session_factory() as session:
            artifact = await session.get(Artifact, artifact_uuid)
            if artifact is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 不存在", details={"artifact_id": str(artifact_id)}
                )
            total = await session.scalar(
                select(func.count(ArtifactVersion.id)).where(
                    ArtifactVersion.artifact_id == artifact_uuid
                )
            )
            statement = (
                select(ArtifactVersion)
                .where(ArtifactVersion.artifact_id == artifact_uuid)
                .order_by(ArtifactVersion.version.desc())
                .offset(max(offset, 0))
            )
            if limit is not None:
                statement = statement.limit(limit)
            rows = await session.execute(statement)
            return list(rows.scalars().all()), int(total or 0)

    async def get_version(self, artifact_id: str | uuid.UUID, version: int) -> ArtifactVersion:
        async with self._session_factory() as session:
            row = await session.scalar(
                select(ArtifactVersion).where(
                    ArtifactVersion.artifact_id == _as_uuid(artifact_id),
                    ArtifactVersion.version == version,
                )
            )
            if row is None:
                raise NotFoundError(
                    f"产物 {artifact_id} 没有版本 {version}",
                    details={"artifact_id": str(artifact_id), "version": version},
                )
            return row

    # ---------------------------------------------------------------- 资产库

    def _asset_folder(self, artifact_uuid: uuid.UUID) -> Path:
        return Path(self._settings.uploads_dir).expanduser() / "artifacts" / artifact_uuid.hex

    async def asset_usage(self, artifact_id: str | uuid.UUID) -> dict[str, int]:
        artifact_uuid = _as_uuid(artifact_id)
        async with self._session_factory() as session:
            row = (
                await session.execute(
                    select(
                        func.count(ArtifactAsset.id),
                        func.coalesce(func.sum(ArtifactAsset.size_bytes), 0),
                    ).where(ArtifactAsset.artifact_id == artifact_uuid)
                )
            ).one()
            return _usage(row[0], row[1])

    async def add_asset(
        self,
        artifact_id: str | uuid.UUID,
        *,
        file_name: str,
        content_type: str | None,
        data: bytes,
    ) -> ArtifactAsset:
        artifact_uuid = _as_uuid(artifact_id)
        await self.get(artifact_uuid)
        resolved_type = validate_asset(file_name, content_type or "", data)
        async with self._session_factory() as session:
            row = (
                await session.execute(
                    select(
                        func.count(ArtifactAsset.id),
                        func.coalesce(func.sum(ArtifactAsset.size_bytes), 0),
                    ).where(ArtifactAsset.artifact_id == artifact_uuid)
                )
            ).one()
            usage = _usage(row[0], row[1])
            if usage["files"] >= usage["max_files"]:
                raise ValidationError(
                    f"该产物的资产数量已达上限（{usage['max_files']} 个）",
                    details={"reason": "asset_limit_reached", **usage},
                )
            if usage["bytes"] + len(data) > usage["max_bytes"]:
                raise ValidationError(
                    "该产物的资产库已满，请先删除不再使用的资产",
                    details={
                        "reason": "storage_limit_reached",
                        **usage,
                        "incoming_bytes": len(data),
                    },
                )

            asset_id = uuid.uuid4()
            original_name = safe_name(file_name)
            folder = self._asset_folder(artifact_uuid)
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / f"{asset_id.hex}{asset_suffix(original_name)}"
            target.write_bytes(data)
            asset = ArtifactAsset(
                id=asset_id,
                artifact_id=artifact_uuid,
                file_name=original_name,
                content_type=resolved_type,
                size_bytes=len(data),
                sha256=hashlib.sha256(data).hexdigest(),
                storage_path=str(target),
            )
            try:
                session.add(asset)
                await session.commit()
                await session.refresh(asset)
            except Exception:
                target.unlink(missing_ok=True)
                raise
            return asset

    async def list_assets(self, artifact_id: str | uuid.UUID) -> list[ArtifactAsset]:
        artifact_uuid = _as_uuid(artifact_id)
        await self.get(artifact_uuid)
        async with self._session_factory() as session:
            rows = await session.execute(
                select(ArtifactAsset)
                .where(ArtifactAsset.artifact_id == artifact_uuid)
                .order_by(ArtifactAsset.created_at.asc())
            )
            return list(rows.scalars().all())

    async def get_asset(self, artifact_id: str | uuid.UUID, asset_id: str) -> ArtifactAsset:
        asset = await self._get_asset_row(_as_uuid(artifact_id), asset_id)
        return asset

    async def read_asset(
        self, artifact_id: str | uuid.UUID, asset_id: str
    ) -> tuple[ArtifactAsset, bytes]:
        asset = await self._get_asset_row(_as_uuid(artifact_id), asset_id)
        path = Path(asset.storage_path)
        try:
            return asset, path.read_bytes()
        except OSError as exc:
            raise NotFoundError(
                "资产文件已丢失", details={"asset_id": asset.id.hex, "reason": "missing_file"}
            ) from exc

    async def _get_asset_row(self, artifact_uuid: uuid.UUID, asset_id: str) -> ArtifactAsset:
        try:
            asset_uuid = uuid.UUID(str(asset_id))
        except ValueError as exc:
            raise NotFoundError(
                f"资产 {asset_id} 不存在", details={"asset_id": str(asset_id)}
            ) from exc
        async with self._session_factory() as session:
            asset = await session.scalar(
                select(ArtifactAsset).where(
                    ArtifactAsset.id == asset_uuid,
                    ArtifactAsset.artifact_id == artifact_uuid,
                )
            )
            if asset is None:
                raise NotFoundError(f"资产 {asset_id} 不存在", details={"asset_id": str(asset_id)})
            return asset

    async def delete_asset(self, artifact_id: str | uuid.UUID, asset_id: str) -> ArtifactAsset:
        artifact_uuid = _as_uuid(artifact_id)
        asset = await self._get_asset_row(artifact_uuid, asset_id)
        async with self._session_factory() as session:
            await session.execute(sql_delete(ArtifactAsset).where(ArtifactAsset.id == asset.id))
            await session.commit()
        Path(asset.storage_path).unlink(missing_ok=True)
        return asset


__all__ = ["ArtifactRepository"]
