"""任务模型（主规格 §11.2 / §5.2）。"""

from __future__ import annotations

import uuid

from sqlalchemy import JSON, Float, ForeignKey, Integer, String, Text, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from aios.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class Task(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "tasks"

    project_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("projects.id", ondelete="CASCADE"), index=True, nullable=True
    )
    description: Mapped[str] = mapped_column(Text)
    # 取值见 aios.enums.TaskStatus
    status: Mapped[str] = mapped_column(String(32), index=True, default="pending")
    # 累计成本；未配置供应商计价时为 None（不臆造价格，主规格 §15.4）
    cost: Mapped[float | None] = mapped_column(Float, nullable=True)
    result: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # 调度优先级：数字越小越优先（主规格 §5.1 调度依据）。本列不在 §11.2 原始定义内，
    # 属附录 A 裁决 A20 的增列：API 已暴露 priority，不落库会导致重启后取值不一致。
    priority: Mapped[int] = mapped_column(
        Integer, nullable=False, default=100, server_default="100"
    )
    # 执行该任务的 Agent。附录 A 裁决 A21：暂不设外键——M1 的 agents 表尚无写入路径，
    # 加 FK 会让带 agent_id 的建任务在 PostgreSQL 上必然失败；待 Agent 持久化落地后补 FK。
    agent_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
