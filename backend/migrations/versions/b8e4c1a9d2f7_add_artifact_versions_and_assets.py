"""Artifacts 对齐 Claude Code：描述 / 图标 / 短名 / 能力声明 / 钉选、版本历史与资产库

artifacts 表补齐官方 publish 的元数据字段；新增 artifact_versions（每次正文写入的
完整快照）与 artifact_assets（资产元数据，实体文件落 uploads 目录）。
已有 page / doc 行通过 server_default 平滑升级，design 仍不写入本表。

Revision ID: b8e4c1a9d2f7
Revises: a4d8e2c91f36
Create Date: 2026-11-27 10:00:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "b8e4c1a9d2f7"
down_revision: str | Sequence[str] | None = "a4d8e2c91f36"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    with op.batch_alter_table("artifacts", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("description", sa.String(length=500), nullable=False, server_default="")
        )
        batch_op.add_column(
            sa.Column("icon", sa.String(length=32), nullable=False, server_default="")
        )
        batch_op.add_column(
            sa.Column("label", sa.String(length=60), nullable=False, server_default="")
        )
        batch_op.add_column(sa.Column("contract", sa.String(length=64), nullable=True))
        batch_op.add_column(
            sa.Column("capabilities", sa.JSON(), nullable=False, server_default="{}")
        )
        batch_op.add_column(
            sa.Column("pinned", sa.Boolean(), nullable=False, server_default=sa.false())
        )

    op.create_table(
        "artifact_versions",
        sa.Column("artifact_id", sa.Uuid(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("title", sa.String(length=160), nullable=False),
        sa.Column("content", sa.Text(), nullable=False, server_default=""),
        sa.Column("description", sa.String(length=500), nullable=False, server_default=""),
        sa.Column("icon", sa.String(length=32), nullable=False, server_default=""),
        sa.Column("label", sa.String(length=60), nullable=False, server_default=""),
        sa.Column("contract", sa.String(length=64), nullable=True),
        sa.Column("capabilities", sa.JSON(), nullable=False, server_default="{}"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["artifact_id"], ["artifacts.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("artifact_id", "version", name="uq_artifact_versions_artifact_version"),
    )
    with op.batch_alter_table("artifact_versions", schema=None) as batch_op:
        batch_op.create_index(
            batch_op.f("ix_artifact_versions_artifact_id"), ["artifact_id"], unique=False
        )

    op.create_table(
        "artifact_assets",
        sa.Column("artifact_id", sa.Uuid(), nullable=False),
        sa.Column("file_name", sa.String(length=200), nullable=False),
        sa.Column("content_type", sa.String(length=128), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("storage_path", sa.String(length=500), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["artifact_id"], ["artifacts.id"], ondelete="CASCADE"),
    )
    with op.batch_alter_table("artifact_assets", schema=None) as batch_op:
        batch_op.create_index(
            batch_op.f("ix_artifact_assets_artifact_id"), ["artifact_id"], unique=False
        )


def downgrade() -> None:
    """Downgrade schema."""
    with op.batch_alter_table("artifact_assets", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_artifact_assets_artifact_id"))
    op.drop_table("artifact_assets")

    with op.batch_alter_table("artifact_versions", schema=None) as batch_op:
        batch_op.drop_index(batch_op.f("ix_artifact_versions_artifact_id"))
    op.drop_table("artifact_versions")

    with op.batch_alter_table("artifacts", schema=None) as batch_op:
        batch_op.drop_column("pinned")
        batch_op.drop_column("capabilities")
        batch_op.drop_column("contract")
        batch_op.drop_column("label")
        batch_op.drop_column("icon")
        batch_op.drop_column("description")
