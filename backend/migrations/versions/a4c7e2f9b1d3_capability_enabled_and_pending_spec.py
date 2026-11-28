"""能力启用状态 + 任务暂存的「本次指定」

- imported_capabilities.enabled：停用的技能不再自动进入执行指令；桌面 / Web / 手机
  共用同一张注册表，默认 true。
- tasks.pending_specified_capabilities：新建对话时通过 TaskCreateRequest 传入的
  「本次指定技能 / 连接器」，只在首次 /start 消费一次并清空，不是任务级长期锁定。

Revision ID: a4c7e2f9b1d3
Revises: b8e4c1a9d2f7
Create Date: 2026-11-28 10:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a4c7e2f9b1d3"
down_revision: str | Sequence[str] | None = "b8e4c1a9d2f7"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("imported_capabilities", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column(
                "enabled",
                sa.Boolean(),
                nullable=False,
                server_default=sa.true(),
            )
        )
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        batch_op.add_column(sa.Column("pending_specified_capabilities", sa.JSON(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        batch_op.drop_column("pending_specified_capabilities")
    with op.batch_alter_table("imported_capabilities", schema=None) as batch_op:
        batch_op.drop_column("enabled")
