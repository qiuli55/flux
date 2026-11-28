"""api_keys 补每个模型的连通性探测状态

保存密钥 / 供应商 / 模型配置后按模型做一次轻量连通检查，结果持久化在这里，
供密钥库列表与三端模型选择器持续显示「不通」标记与原因。

Revision ID: c4e8d1a7b9f2
Revises: b8e4c1a9d2f7
Create Date: 2026-11-28 12:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c4e8d1a7b9f2"
down_revision: str | Sequence[str] | None = "b8e4c1a9d2f7"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("api_keys", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column(
                "model_connectivity",
                sa.JSON(),
                nullable=False,
                server_default=sa.text("'{}'"),
            )
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("api_keys", schema=None) as batch_op:
        batch_op.drop_column("model_connectivity")
