"""Virtual Workspace 持久化模型（主规格 §7.3 Change Object / §11.2）。"""

from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, String, Text, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from aios.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class VirtualChange(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """AI 改动的虚拟提案。落盘与否由审查状态决定（主规格 §7）。"""

    __tablename__ = "virtual_changes"

    project_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("projects.id", ondelete="CASCADE"), index=True
    )
    file_path: Mapped[str] = mapped_column(String(512), index=True)
    original_content: Mapped[str] = mapped_column(Text)
    proposed_content: Mapped[str] = mapped_column(Text)
    diff: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 产出该提案的 Agent 标识
    agent_source: Mapped[str | None] = mapped_column(String(128), nullable=True)
    # 取值见 aios.enums.VirtualChangeStatus
    status: Mapped[str] = mapped_column(String(32), index=True, default="pending")
