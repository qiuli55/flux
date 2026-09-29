"""数据库会话与引擎（主规格 §11.1 存储选型 / §17.3 优先异步）。"""

from __future__ import annotations

from collections.abc import AsyncIterator

from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from aios.logging import get_logger

logger = get_logger(__name__)


def create_engine(database_url: str) -> AsyncEngine:
    """创建异步引擎。

    SQLite（本地开发）不支持连接池参数，故按方言分支。
    """
    if database_url.startswith("sqlite"):
        engine = create_async_engine(database_url, future=True)

        # SQLite 默认不强制外键，本地会静默吞掉违反外键的写入；开启后与 PostgreSQL 行为一致。
        @event.listens_for(engine.sync_engine, "connect")
        def _enable_sqlite_fk(dbapi_connection, _record):  # pragma: no cover - 驱动回调
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

        return engine
    return create_async_engine(database_url, future=True, pool_pre_ping=True, pool_size=5)


def create_session_factory(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False)


async def check_database(engine: AsyncEngine) -> bool:
    """就绪探针：能否真正连上库。"""
    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:  # noqa: BLE001 - 探针需要把任何连接异常都转为不可用
        logger.warning("数据库就绪探针失败", exc_info=True)
        return False


async def session_scope(
    factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with factory() as session:
        yield session
