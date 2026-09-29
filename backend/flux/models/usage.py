"""用量与成本记录（主规格 §11.2 / §15.4）。"""

from __future__ import annotations

import uuid

from sqlalchemy import Float, ForeignKey, Integer, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class UsageRecord(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """单次模型调用的用量。

    cost 为 None 表示该供应商未配置计价——**不写入猜测值**（主规格 §15.4）。
    """

    __tablename__ = "usage_records"

    provider: Mapped[str] = mapped_column(String(32), index=True)
    model: Mapped[str] = mapped_column(String(128), index=True)
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    total_tokens: Mapped[int] = mapped_column(Integer, default=0)
    cost: Mapped[float | None] = mapped_column(Float, nullable=True)
    task_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("tasks.id", ondelete="SET NULL"), index=True, nullable=True
    )
    agent_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True), index=True, nullable=True
    )
