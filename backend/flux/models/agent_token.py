"""Agent 接入令牌（目标架构 §3.2）。

这是 MCP 面唯一的鉴权凭据：明文形如 `fxt_` + 32 字节随机 hex，
库里只存 sha256——数据库泄露也拿不到可用令牌（明文只在签发那一刻返回一次）。

agent_id 不设外键：内置档案来自 AgentManager 的内存注册表，
外部 agent（codex / claude-code / opencode）本就不在 agents 表里，
加外键会逼着"为了发令牌先往 agents 表塞一行假档案"。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class AgentToken(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "agent_tokens"

    # Agent 档案标识（目标架构 §3.2 的注册表：flux-builtin / codex / claude-code / opencode）
    agent_id: Mapped[str] = mapped_column(String(128), index=True)
    # 人类可读备注，便于在「接入 agent」界面上分辨多枚令牌
    label: Mapped[str] = mapped_column(String(128), default="")
    # sha256(明文令牌) 的十六进制，唯一索引；明文不落库
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    # 该令牌被授予的能力，取值见 flux.enums.Capability（SECRET_ACCESS 永不在其中，§3.5）
    scopes: Mapped[list[Any]] = mapped_column(JSON, default=list)
    # 撤销时间；非空即失效，鉴权时现查现判，不做进程内缓存
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    @property
    def revoked(self) -> bool:
        return self.revoked_at is not None

    def to_dict(self) -> dict[str, Any]:
        """对外表示**不含** token_hash：哈希也是机密，不进任何 API 响应。"""
        return {
            "id": str(self.id),
            "agent_id": self.agent_id,
            "label": self.label,
            "scopes": [str(scope) for scope in (self.scopes or [])],
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "revoked_at": self.revoked_at.isoformat() if self.revoked_at else None,
            "revoked": self.revoked,
        }
