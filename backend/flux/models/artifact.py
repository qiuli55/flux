"""Artifacts：对话产出的可复用成品（页面 / 文档）。

设计稿不落这张表：设计类 artifact 是 Luma Studio 的只读投影（id 形如 `design:<id>`），
本表只存 Flux 自己拥有的页面与文档，避免把设计数据复制两份。
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import JSON, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class Artifact(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "artifacts"

    #: page = 单文件 HTML；doc = Markdown。design 由 Luma 聚合，不写入本表。
    type: Mapped[str] = mapped_column(String(16), index=True)
    title: Mapped[str] = mapped_column(String(160))
    content: Mapped[str] = mapped_column(Text, default="")
    version: Mapped[int] = mapped_column(Integer, default=1)
    meta: Mapped[dict] = mapped_column("metadata", JSON, default=dict)

    def to_dict(self, *, include_content: bool = True) -> dict[str, Any]:
        data: dict[str, Any] = {
            "id": str(self.id),
            "type": self.type,
            "title": self.title,
            "version": self.version,
            "metadata": self.meta or {},
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }
        if include_content:
            data["content"] = self.content or ""
        else:
            data["summary"] = _excerpt(self.content)
        return data


def _excerpt(content: str | None, limit: int = 140) -> str:
    """列表卡片的一句话摘要：取第一段非空文本，压缩空白后截断。"""
    for line in (content or "").splitlines():
        text = " ".join(line.split())
        if text:
            return text[:limit]
    return ""
