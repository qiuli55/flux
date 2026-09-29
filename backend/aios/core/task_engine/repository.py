"""tasks 表的读写入口（主规格 §11.2）。

本增量把 Task Engine 从进程内字典切到数据库，任务状态的唯一权威是 tasks 表。
"""

from __future__ import annotations

import uuid

from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from aios.enums import TaskStatus
from aios.errors import BadRequestError, NotFoundError
from aios.logging import get_logger
from aios.models.task import Task

logger = get_logger(__name__)


class TaskRepository:
    """tasks 表的读写入口（主规格 §11.2）。

    每次调用自开一个会话并提交，即「一次调用一个工作单元」。
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def create(
        self,
        *,
        task_id: uuid.UUID,
        description: str,
        priority: int,
        project_id: uuid.UUID | None,
        agent_id: uuid.UUID | None,
    ) -> Task:
        async with self._session_factory() as session:
            task = Task(
                id=task_id,
                description=description,
                status=TaskStatus.PENDING.value,
                priority=priority,
                project_id=project_id,
                agent_id=agent_id,
            )
            session.add(task)
            await self._commit(session)
            return task

    async def get(self, task_id: uuid.UUID) -> Task:
        """不存在时抛 NotFoundError（消息含 task_id）。"""
        async with self._session_factory() as session:
            task = await session.get(Task, task_id)
            if task is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            return task

    async def set_status(self, task_id: uuid.UUID, status: TaskStatus) -> Task:
        """不存在时抛 NotFoundError。"""
        async with self._session_factory() as session:
            task = await session.get(Task, task_id)
            if task is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            task.status = status.value
            await self._commit(session)
            return task

    async def _commit(self, session: AsyncSession) -> None:
        """提交会话；违反数据库约束时转为 400（不引入全局处理器，见交付说明）。"""
        try:
            await session.commit()
        except IntegrityError as exc:
            await session.rollback()
            logger.warning("任务写入违反数据库约束：%s", exc.orig)
            raise BadRequestError(f"任务写入违反数据库约束：{exc.orig}") from exc
