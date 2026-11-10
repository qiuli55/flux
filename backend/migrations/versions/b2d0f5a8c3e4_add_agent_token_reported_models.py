"""agent_tokens 增加 Agent 上报的模型列表

外部 Agent 首次接入时用 `agent.report_models` 上报自己当前支持的模型。
它与 Flux runtime 能执行的模型是两回事：前者属于连接方，后者属于 Flux 的执行器，
不能混在同一个字段里。

Revision ID: b2d0f5a8c3e4
Revises: a1c9e4f7b2d3
Create Date: 2026-11-10 02:10:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "b2d0f5a8c3e4"
down_revision: str | Sequence[str] | None = "a1c9e4f7b2d3"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_tokens",
        sa.Column("reported_models", sa.JSON(), nullable=False, server_default="[]"),
    )


def downgrade() -> None:
    with op.batch_alter_table("agent_tokens") as batch:
        batch.drop_column("reported_models")
