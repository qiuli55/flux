"""常驻 Bot（Bot 模式）。

与「对话」的区别不是身份，而是生命周期：对话是一轮一轮问、终态即结束；
Bot 是常驻在机器上的一个执行者，有自己的工作目录、自己的 Agent 与权限模式，
可以按 `schedule`（5 段 cron）周期性开工，也可以在需要时手动触发一次。

每次开工都是一条 `tasks` 记录（`tasks.bot_id` 指回本表），所以执行、上下文、
计费、消息都复用既有链路，Bot 只负责「什么时候、在哪个目录、让谁去跑」。
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import DateTime, String, Text, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value is not None else None


class Bot(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "bots"

    name: Mapped[str] = mapped_column(String(64), nullable=False)
    #: 这个 Bot 的职责/固定作业内容。定时触发时它就是本轮的执行指令。
    description: Mapped[str] = mapped_column(Text, nullable=False, default="", server_default="")
    #: 头像与对话一致：只由「形状 × 颜色」确定性渲染（flux.core.group.avatar）
    avatar_shape: Mapped[str] = mapped_column(
        String(32), nullable=False, default="circle", server_default="circle"
    )
    avatar_color: Mapped[str] = mapped_column(
        String(32), nullable=False, default="violet", server_default="violet"
    )
    #: 固定由哪个 Agent 执行；留空 = 平台默认 runtime
    agent_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    api_key_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    #: 无人值守的 Bot 默认免审核（PermissionMode.UNATTENDED）；
    #: 需要人工把关的场景由用户显式改成 normal / plan。
    permission_mode: Mapped[str] = mapped_column(
        String(16), nullable=False, default="unattended", server_default="unattended"
    )
    decision_mode: Mapped[str] = mapped_column(
        String(16), nullable=False, default="auto", server_default="auto"
    )
    #: 独立工作目录（绝对路径）。留空 = `<dsh_home>/bots/<id>/workspace`。
    #: 每次开工都会把解析后的路径快照进 tasks.workspace，历史记录不随配置漂移。
    workspace: Mapped[str | None] = mapped_column(String(512), nullable=True)
    #: 取值见 flux.enums.BotStatus；paused 的 Bot 不会被定时触发
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, default="active", server_default="active", index=True
    )
    #: 5 段 cron（分 时 日 月 周）。留空 = 只手动触发。
    schedule: Mapped[str | None] = mapped_column(String(64), nullable=True)
    #: 下一次自动开工时间；调度器按它取到期任务，落库保证重启不丢。
    next_run_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, index=True
    )
    last_run_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    #: 最近一次开工的任务；不设外键，任务被清理后 Bot 仍可查
    last_task_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": str(self.id),
            "name": self.name,
            "description": self.description or "",
            "avatar_shape": self.avatar_shape or "circle",
            "avatar_color": self.avatar_color or "violet",
            "agent_id": str(self.agent_id) if self.agent_id is not None else None,
            "model": self.model,
            "permission_mode": self.permission_mode or "unattended",
            "decision_mode": self.decision_mode or "auto",
            "workspace": self.workspace,
            "status": self.status or "active",
            "schedule": self.schedule,
            "next_run_at": _iso(self.next_run_at),
            "last_run_at": _iso(self.last_run_at),
            "last_task_id": str(self.last_task_id) if self.last_task_id is not None else None,
            "created_at": _iso(self.created_at),
            "updated_at": _iso(self.updated_at),
        }
