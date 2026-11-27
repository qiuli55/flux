"""Artifacts：对话产出的可复用成品（页面 / 文档）及其版本历史、资产库。

设计稿不落这张表：设计类 artifact 是 Luma Studio 的只读投影（id 形如 `design:<id>`），
本表只存 Flux 自己拥有的页面与文档，避免把设计数据复制两份。

`version` 既是乐观锁，也是当前版本号；每次成功写入正文 / 标题 / 描述 / 图标 / 短名 /
能力声明 / 运行时版本，都会在 `artifact_versions` 追加一份快照，绝不只改整数不留历史。
资产（图片 / 视频 / PDF / 字体 / CSS / JS / 文本）从属于某个产物，元数据落
`artifact_assets`，实体文件按 `<uploads_dir>/artifacts/<artifact_id>/` 存放。
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Artifact(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "artifacts"

    #: page = 单文件 HTML；doc = Markdown。design 由 Luma 聚合，不写入本表。
    type: Mapped[str] = mapped_column(String(16), index=True)
    title: Mapped[str] = mapped_column(String(160))
    content: Mapped[str] = mapped_column(Text, default="")
    #: 画廊 / 列表副标题，一句话说明这个页面是什么。
    description: Mapped[str] = mapped_column(String(500), default="")
    #: 浏览器标签图标词（chart / calendar / code / map 等），不是新的产物类型。
    icon: Mapped[str] = mapped_column(String(32), default="")
    #: 本次发布的短名，≤60 字符。
    label: Mapped[str] = mapped_column(String(60), default="")
    #: 运行时版本字符串；只存储声明，不在本次实现执行。
    contract: Mapped[str | None] = mapped_column(String(64), nullable=True)
    #: 运行时能力声明；空对象表示清空。
    capabilities: Mapped[dict] = mapped_column(JSON, default=dict)
    #: 用户钉选（私有，不影响可见性）。
    pinned: Mapped[bool] = mapped_column(Boolean, default=False)
    #: 乐观锁 + 当前版本号。
    version: Mapped[int] = mapped_column(Integer, default=1)
    meta: Mapped[dict] = mapped_column("metadata", JSON, default=dict)

    def to_dict(self, *, include_content: bool = True) -> dict[str, Any]:
        data: dict[str, Any] = {
            "id": str(self.id),
            "type": self.type,
            "title": self.title,
            "description": self.description or "",
            "icon": self.icon or "",
            "label": self.label or "",
            "contract": self.contract,
            "capabilities": self.capabilities or {},
            "pinned": bool(self.pinned),
            "version": self.version,
            "metadata": self.meta or {},
            "summary": _excerpt(self.content),
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }
        if include_content:
            data["content"] = self.content or ""
        return data


class ArtifactVersion(UUIDPrimaryKeyMixin, Base):
    """一份产物在某个版本的完整快照（不含资产：资产属于产物，不随版本复制）。"""

    __tablename__ = "artifact_versions"
    __table_args__ = (
        UniqueConstraint("artifact_id", "version", name="uq_artifact_versions_artifact_version"),
    )

    artifact_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("artifacts.id", ondelete="CASCADE"), index=True
    )
    version: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(160))
    content: Mapped[str] = mapped_column(Text, default="")
    description: Mapped[str] = mapped_column(String(500), default="")
    icon: Mapped[str] = mapped_column(String(32), default="")
    label: Mapped[str] = mapped_column(String(60), default="")
    contract: Mapped[str | None] = mapped_column(String(64), nullable=True)
    capabilities: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    #: 快照不可变，但主规格 §11.5 要求每张表都有 created_at / updated_at。
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    def to_dict(self, *, include_content: bool = True) -> dict[str, Any]:
        data: dict[str, Any] = {
            "artifact_id": str(self.artifact_id),
            "version": self.version,
            "title": self.title,
            "description": self.description or "",
            "icon": self.icon or "",
            "label": self.label or "",
            "contract": self.contract,
            "capabilities": self.capabilities or {},
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }
        if include_content:
            data["content"] = self.content or ""
        return data


class ArtifactAsset(UUIDPrimaryKeyMixin, Base):
    """产物资产库中的一项；实体文件落在 uploads 目录，DB 只存元数据与绝对路径。"""

    __tablename__ = "artifact_assets"

    artifact_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("artifacts.id", ondelete="CASCADE"), index=True
    )
    file_name: Mapped[str] = mapped_column(String(200))
    content_type: Mapped[str] = mapped_column(String(128))
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    sha256: Mapped[str] = mapped_column(String(64))
    storage_path: Mapped[str] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    def to_dict(self) -> dict[str, Any]:
        # 对外 id 用 32 位 hex（与官方 asset_id 同口径）；不暴露服务器磁盘路径。
        return {
            "id": self.id.hex,
            "artifact_id": str(self.artifact_id),
            "file_name": self.file_name,
            "content_type": self.content_type,
            "size_bytes": self.size_bytes,
            "sha256": self.sha256,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }


def _excerpt(content: str | None, limit: int = 140) -> str:
    """列表卡片的一句话摘要：取第一段非空文本，压缩空白后截断。"""
    for line in (content or "").splitlines():
        text = " ".join(line.split())
        if text:
            return text[:limit]
    return ""


__all__ = ["Artifact", "ArtifactAsset", "ArtifactVersion"]
