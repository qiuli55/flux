"""tasks 增加订阅账号与思考档

多账号选模：任务要记住「用的是哪个订阅账号」（与 api_key_id 互斥），
思考控制：任务要记住当前模型上的思考档，下一轮 Run 生效。

Revision ID: a1c7e5d9f204
Revises: b8e4c1a9d2f7
Create Date: 2026-11-28 10:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a1c7e5d9f204"
down_revision: str | Sequence[str] | None = "b8e4c1a9d2f7"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        batch_op.add_column(sa.Column("subscription_account_id", sa.String(length=64), nullable=True))
        batch_op.add_column(sa.Column("thinking_level", sa.String(length=32), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        batch_op.drop_column("thinking_level")
        batch_op.drop_column("subscription_account_id")
