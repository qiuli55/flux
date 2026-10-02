"""任务模型（主规格 §11.2 / §5.2）。"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import JSON, Float, ForeignKey, Integer, String, Text, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class Task(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "tasks"

    project_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid(as_uuid=True), ForeignKey("projects.id", ondelete="CASCADE"), index=True, nullable=True
    )
    description: Mapped[str] = mapped_column(Text)
    # 取值见 flux.enums.TaskStatus
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
    # 任务级决策策略（文档 §5）：auto = Agent 按默认方案自行拍板，manual = 遇决策点暂停等用户。
    # 取值见 flux.enums.DecisionMode；列级默认与 DecisionMode.AUTO 一致。
    decision_mode: Mapped[str] = mapped_column(
        String(16), nullable=False, default="auto", server_default="auto"
    )
    # 需求确认六维度（文档 §4 P0-06）。结构 {"items": [{label, value}], "actor": ...}，
    # actor 取 assistant / user；用户改过之后以最后一次写入为准，执行时用的就是最终版本。
    confirmation: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # 决策点历史（文档 §5）：一个列表，每个元素是一个决策点记录；status=pending 的那条是"正等人选"。
    decisions: Mapped[list | None] = mapped_column(JSON, nullable=True)
    # 该任务触发的 DSH Run（P0-05）。Run 结束时靠它把终态回写到任务上。
    run_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)

    def pending_decision(self) -> dict | None:
        """当前正等用户选择的那条决策点记录；没有则返回 None。"""
        for record in reversed(self.decisions or []):
            if isinstance(record, dict) and record.get("status") == "pending":
                return record
        return None

    def to_dict(self) -> dict[str, Any]:
        """任务的标准响应体：M0 冻结的 7 个键 + created_at + Solo 生命周期字段。

        created_at / decision_mode / confirmation / decisions / pending_decision / run_id
        都是后续增列，属向后兼容的加字段——调用方原有字段不变。cost 仍不外露（未配置计价时为 None）。
        """
        return {
            "id": str(self.id),
            "description": self.description,
            "status": self.status,
            "priority": self.priority,
            "agent_id": str(self.agent_id) if self.agent_id is not None else None,
            "project_id": str(self.project_id) if self.project_id is not None else None,
            "result": self.result,
            "created_at": self.created_at.isoformat() if self.created_at is not None else None,
            "decision_mode": self.decision_mode,
            "confirmation": self.confirmation,
            "decisions": list(self.decisions or []),
            "pending_decision": self.pending_decision(),
            "run_id": self.run_id,
        }


class TaskMessage(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """任务对话消息（Solo 任务执行中心的聊天记录）。

    落库而不是留在前端内存：刷新页面、后端重启后聊天记录都不丢。
    `seq` 是同一任务内的单调序号（从 1 起），向上懒加载更早消息时用它做游标——
    不依赖 created_at（SQLite 的时间戳只到秒，同一秒内的多条消息无法排序）。
    """

    __tablename__ = "task_messages"

    task_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True),
        ForeignKey("tasks.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    seq: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    # 说话方：user / assistant（取值见 flux.enums.TaskMessageRole）
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    # 消息类型：本增量只有 text；agent 事件类消息（执行步骤、状态变更）待真实事件源接入后扩展
    kind: Mapped[str] = mapped_column(
        String(32), nullable=False, default="text", server_default="text"
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 结构化附加数据：澄清结论、执行计划步骤、模型调用元信息；无附加数据时为 NULL
    payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": str(self.id),
            "task_id": str(self.task_id),
            "seq": self.seq,
            "role": self.role,
            "kind": self.kind,
            "content": self.content,
            "payload": self.payload,
            "created_at": self.created_at.isoformat() if self.created_at is not None else None,
        }
