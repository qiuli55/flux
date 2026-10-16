"""数据模型契约测试：代码里的表必须与主规格 §11.2 完全一致。"""

from __future__ import annotations

import asyncio
import sqlite3
import subprocess
import sys
from pathlib import Path

from sqlalchemy import inspect

from flux.db.session import create_engine
from flux.models import Base

BACKEND_ROOT = Path(__file__).resolve().parents[1]

# 主规格 §11.2 表清单（裁决 A13 已把 agent_logs 并入 agent_execution_logs）
SPEC_TABLES = {
    "users",
    "provider_accounts",
    "organizations",
    "organization_members",
    "agents",
    "agent_skills",
    "agent_execution_logs",
    "projects",
    "project_memory",
    "tasks",
    "virtual_changes",
    "connectors",
    "connector_logs",
    "usage_records",
    # §11.2 之外的新增表：MCP 能力面的鉴权凭据（目标架构 §3.2）。
    # 它不是业务实体，因此不入主规格表清单，但必须与其余表一样受本文件全部约束。
    "agent_tokens",
    # §11.2 之外的新增表：Solo 任务执行中心的对话消息（任务消息/步骤落库）。
    # 会话数据依附于任务，删除任务即级联清理，同样受本文件全部约束。
    "task_messages",
    # §11.2 之外的新增表：DSH Agent Run 的生命周期权威记录（修复方案 §2.2）。
    # Run 状态、心跳、进程归属（pid/pgid/owner_pid）落库，重启与多实例共享同库时可对账。
    "agent_runs",
    # §11.2 之外的新增表：本机 CLI Agent 的发现与接入状态（最终方案 §3.2 / §4）。
    # 与 agents 表（身份/权限）分离：这里只记"装没装、哪个版本、接没接入"。
    "agent_installations",
    # §11.2 之外的新增表：三层记忆的 User / Environment 层（批次② §4.2）。
    # Project 层复用 project_memory（Project Brain）；本表同样受本文件全部约束。
    "memories",
    # §11.2 之外的新增表：能力导入注册表（批次③ §5）。
    # 同一 (kind, name) 唯一，fingerprint 判定 unchanged / keep / replace。
    "imported_capabilities",
    # §11.2 之外的新增表：Apply 事务日志（P0-1 崩溃恢复；P1-2 整批回滚的分组依据）。
    # 一行 = 一次 apply_many，必须在任何磁盘操作之前落库，重启后据此对账。
    "apply_batches",
    # §11.2 之外的新增表：终端会话与终端事件（Agent Terminal Console §6 / §8）。
    # 会话是"观察 + 控制"的载体，事件按会话内单调 seq 落库以支持历史恢复与 SSE 重连。
    "terminal_sessions",
    "terminal_events",
    # FLUX_CONTEXT_MECHANISM §3.3 / §3.5 的上下文机制最小闭环。
    # context_steps 是压缩 / 检索 / 原子事实的最小单元（有意用自增整数主键，
    # 见 flux/models/context.py 的说明）；context_blobs 是内容寻址原文库
    # （sha256 去重 + zstd/lzma 无损压缩）。
    "context_steps",
    "context_blobs",
    # FLUX_CONTEXT_MECHANISM §7.2 的反向索引：文件 / 命令 / URL / 错误关键字 → step_id。
    # 正向 fetch 需要 id，而接管 agent 手里往往只有线索；没有本表回看通道等于没有入口。
    "context_step_tokens",
}


def test_metadata_matches_spec_table_list() -> None:
    assert set(Base.metadata.tables) == SPEC_TABLES


def test_every_table_has_uuid_pk_and_timestamps() -> None:
    """§11.5：主键一律 UUID，每张表必须有 created_at / updated_at。"""
    for name, table in Base.metadata.tables.items():
        assert "id" in table.c, f"{name} 缺少主键 id"
        assert table.c["id"].primary_key, f"{name}.id 不是主键"
        for column in ("created_at", "updated_at"):
            assert column in table.c, f"{name} 缺少 {column}"


def test_schema_can_be_created_on_sqlite(tmp_path) -> None:
    """DDL 必须真的能建出来，而不只是元数据里列着。"""
    engine = create_engine(f"sqlite+aiosqlite:///{tmp_path / 'schema.db'}")

    async def _create() -> set[str]:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        async with engine.connect() as conn:
            return set(await conn.run_sync(lambda sync_conn: inspect(sync_conn).get_table_names()))

    try:
        created = asyncio.run(_create())
    finally:
        asyncio.run(engine.dispose())

    assert created >= SPEC_TABLES


def test_indexes_cover_spec_query_columns() -> None:
    """§11.4：project_id、task status、agent status、created_at 需要有索引。"""
    expectations = {
        "tasks": ["project_id", "status"],
        "agents": ["status", "role"],
        "virtual_changes": ["project_id", "status"],
        "projects": ["organization_id"],
    }
    for table_name, columns in expectations.items():
        table = Base.metadata.tables[table_name]
        for column in columns:
            assert table.c[column].index, f"{table_name}.{column} 应有索引"


def test_migrated_schema_matches_metadata(tmp_path) -> None:
    """把 Alembic 迁移跑出来的表结构与 Base.metadata 逐列对比。

    防止「模型加了列但忘了写迁移」这种本地测得过、线上炸掉的漂移：
    verify.sh 的迁移往返只验能不能跑，不验与模型是否一致。
    """
    db_path = tmp_path / "migrated.db"
    url = f"sqlite+aiosqlite:///{db_path}"
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "alembic",
            "-c",
            str(BACKEND_ROOT / "alembic.ini"),
            "-x",
            f"db_url={url}",
            "upgrade",
            "head",
        ],
        cwd=BACKEND_ROOT,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr

    connection = sqlite3.connect(db_path)
    try:
        for table_name, table in Base.metadata.tables.items():
            rows = connection.execute(f'PRAGMA table_info("{table_name}")').fetchall()
            migrated_columns = {row[1] for row in rows}
            assert rows, f"迁移后的库里缺少元数据表 {table_name}"
            metadata_columns = {column.name for column in table.columns}
            assert migrated_columns == metadata_columns, (
                f"{table_name} 列不一致："
                f"仅元数据有 {metadata_columns - migrated_columns}，"
                f"仅迁移有 {migrated_columns - metadata_columns}"
            )
    finally:
        connection.close()
