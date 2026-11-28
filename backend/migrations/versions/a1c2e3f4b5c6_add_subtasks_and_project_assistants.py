"""主助理调度员：临时子任务 + 项目助理

2026-11-28 产品决定：
- tasks 增加 parent_task_id：临时子任务是一条真实 Task，挂在主助理对话下；
  默认不出现在「对话」列表，删除父对话时一并删除。
- 新增 project_assistants：项目助理与主助理同级，有自己的常驻对话 conversation_task_id，
  source_task_ids 只作只读上下文，不写入来源对话。

Revision ID: a1c2e3f4b5c6
Revises: b8e4c1a9d2f7
Create Date: 2026-11-28 18:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a1c2e3f4b5c6"
down_revision: str | Sequence[str] | None = "b8e4c1a9d2f7"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        # origin 新增 project_assistant（17 字符），原 String(16) 在 PostgreSQL 会截断/报错
        batch_op.alter_column(
            "origin",
            existing_type=sa.String(length=16),
            type_=sa.String(length=32),
            existing_nullable=False,
            existing_server_default="chat",
        )
        batch_op.add_column(sa.Column("parent_task_id", sa.Uuid(), nullable=True))
        batch_op.create_index("ix_tasks_parent_task_id", ["parent_task_id"])
        batch_op.create_foreign_key(
            "fk_tasks_parent_task_id", "tasks", ["parent_task_id"], ["id"], ondelete="CASCADE"
        )

    op.create_table(
        "project_assistants",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=64), nullable=False),
        sa.Column("project_id", sa.Uuid(), nullable=True),
        sa.Column("conversation_task_id", sa.Uuid(), nullable=False),
        sa.Column("source_task_ids", sa.JSON(), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["conversation_task_id"], ["tasks.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("conversation_task_id"),
    )
    op.create_index("ix_project_assistants_project_id", "project_assistants", ["project_id"])
    op.create_index(
        "ix_project_assistants_conversation_task_id",
        "project_assistants",
        ["conversation_task_id"],
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index("ix_project_assistants_conversation_task_id", table_name="project_assistants")
    op.drop_index("ix_project_assistants_project_id", table_name="project_assistants")
    op.drop_table("project_assistants")
    with op.batch_alter_table("tasks", schema=None) as batch_op:
        batch_op.drop_constraint("fk_tasks_parent_task_id", type_="foreignkey")
        batch_op.drop_index("ix_tasks_parent_task_id")
        batch_op.drop_column("parent_task_id")
        batch_op.alter_column(
            "origin",
            existing_type=sa.String(length=32),
            type_=sa.String(length=16),
            existing_nullable=False,
            existing_server_default="chat",
        )
