"""agent_tokens 增加 Agent 自报名

MCP 客户端首次接入时用 `agent.choose_name` 给自己取一个显示名。名字必须与
管理员写的令牌备注（label）分开：备注是给人看的接入说明，自报名属于 Agent
本次连接的身份，两者混用会让"首次接入还没取名"的判断失真。

Revision ID: a1c9e4f7b2d3
Revises: b7d1a9c4e320
Create Date: 2026-11-10 02:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a1c9e4f7b2d3"
down_revision: str | Sequence[str] | None = "b7d1a9c4e320"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_tokens",
        sa.Column("self_name", sa.String(length=64), nullable=False, server_default=""),
    )


def downgrade() -> None:
    with op.batch_alter_table("agent_tokens") as batch:
        batch.drop_column("self_name")
