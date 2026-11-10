"""agent_tokens.last_used_at + 标记外部 Agent

外部接入的 Agent 连续 30 天未通过 MCP 调用 Flux 会被自动注销，因此需要记录
每枚令牌最近一次被使用的时间。同时把已接入的 Grok 连接器档案标记为 external：
它只经 MCP 接入，不显示在前端，也不能被 Flux 执行。

Revision ID: d9e2f4a6b813
Revises: b2d0f5a8c3e4
Create Date: 2026-11-10 12:30:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "d9e2f4a6b813"
down_revision: str | Sequence[str] | None = "b2d0f5a8c3e4"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _mark_external_agents() -> None:
    """把已存在的 Grok 连接器档案回填为 external（其余档案保持 local 语义）。"""
    bind = op.get_bind()
    agents = sa.table(
        "agents",
        sa.column("id", sa.String),
        sa.column("name", sa.String),
        sa.column("config", sa.JSON),
    )
    rows = bind.execute(sa.select(agents.c.id, agents.c.name, agents.c.config)).all()
    for row in rows:
        config = dict(row.config or {})
        if config.get("kind") == "external":
            continue
        name = str(row.name or "").strip().lower()
        description = str(config.get("description") or "").lower()
        if name != "grok" and not ("grok" in description and "mcp" in description):
            continue
        config["kind"] = "external"
        bind.execute(sa.update(agents).where(agents.c.id == row.id).values(config=config))


def upgrade() -> None:
    op.add_column(
        "agent_tokens",
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
    )
    _mark_external_agents()


def downgrade() -> None:
    with op.batch_alter_table("agent_tokens") as batch:
        batch.drop_column("last_used_at")
