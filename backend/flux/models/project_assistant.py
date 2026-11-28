"""项目助理模型（2026-11-28）。

项目助理是「对话」页右上角与主助理同级的入口：每个项目助理有自己的常驻对话，
用来按项目跟踪进度。它只读已有对话作为上下文，不创建临时子任务，也不往
来源对话里写消息——进度汇报写在它自己的常驻对话里。
"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import JSON, ForeignKey, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class ProjectAssistant(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "project_assistants"

    name: Mapped[str] = mapped_column(String(64), nullable=False)
    #: 关联的 Flux 项目（可空：用户也可能只想按一组对话跟进度）。
    project_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("projects.id", ondelete="SET NULL"),
        index=True,
        nullable=True,
    )
    #: 项目助理自己的常驻对话（一条真实 Task，origin=project_assistant）。
    conversation_task_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("tasks.id", ondelete="CASCADE"),
        unique=True,
        index=True,
        nullable=False,
    )
    #: 作为只读上下文使用的已有对话 id 列表（字符串 UUID，便于直接回给客户端）。
    source_task_ids: Mapped[list | None] = mapped_column(JSON, nullable=True)

    def source_ids(self) -> list[str]:
        return [str(item) for item in (self.source_task_ids or []) if item]

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": str(self.id),
            "name": self.name,
            "project_id": str(self.project_id) if self.project_id is not None else None,
            "conversation_task_id": str(self.conversation_task_id),
            "source_task_ids": self.source_ids(),
            "created_at": self.created_at.isoformat() if self.created_at is not None else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at is not None else None,
        }
