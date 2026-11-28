"""api_keys 增加每个模型的思考模式

密钥库登记的模型要保存自己的思考模式（类型 + 档位 + 默认档），
对话里选中该模型时按这份配置展示和调节，而不再走订阅模型的自动识别。

Revision ID: c3e9f1a7b604
Revises: a1c7e5d9f204
Create Date: 2026-11-28 12:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c3e9f1a7b604"
down_revision: str | Sequence[str] | None = "a1c7e5d9f204"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("api_keys", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("model_thinking", sa.JSON(), nullable=False, server_default="{}")
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("api_keys", schema=None) as batch_op:
        batch_op.drop_column("model_thinking")
