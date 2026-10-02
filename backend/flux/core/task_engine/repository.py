"""tasks 表的读写入口（主规格 §11.2）。

本增量把 Task Engine 从进程内字典切到数据库，任务状态的唯一权威是 tasks 表。
任务对话消息（task_messages）同库同事务写入：聊天记录跨进程重启不丢。
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.enums import DecisionMode, DecisionStatus, TaskStatus
from flux.errors import BadRequestError, NotFoundError
from flux.logging import get_logger
from flux.models.project import Project
from flux.models.task import Task, TaskMessage

logger = get_logger(__name__)


@dataclass(frozen=True)
class MessageDraft:
    """待写入的一条任务消息（seq 由仓储在同一事务里分配）。"""

    role: str
    content: str
    kind: str = "text"
    payload: dict[str, Any] | None = None


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
        decision_mode: DecisionMode = DecisionMode.AUTO,
    ) -> Task:
        """写入一条 pending 任务。project_id 指向不存在的项目时抛 NotFoundError。

        与 agent_id 的边界校验保持同一语义：合法 UUID 但不存在的关联实体一律 404，
        而不是留给数据库外键去抛约束错误（那会退化成 400，同一类问题两种错误码）。
        """
        async with self._session_factory() as session:
            if project_id is not None:
                exists = await session.scalar(select(Project.id).where(Project.id == project_id))
                if exists is None:
                    raise NotFoundError(
                        f"项目 {project_id} 不存在", details={"project_id": str(project_id)}
                    )
            task = Task(
                id=task_id,
                description=description,
                status=TaskStatus.PENDING.value,
                priority=priority,
                project_id=project_id,
                agent_id=agent_id,
                decision_mode=str(decision_mode),
            )
            session.add(task)
            await self._commit(session)
            # created_at 由数据库 server_default 生成：离开会话前取回，响应体才能带上真实时间
            await session.refresh(task)
            return task

    async def get(self, task_id: uuid.UUID) -> Task:
        """不存在时抛 NotFoundError（消息含 task_id）。"""
        async with self._session_factory() as session:
            task = await session.get(Task, task_id)
            if task is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            return task

    async def list(
        self,
        *,
        project_id: uuid.UUID | None = None,
        status: TaskStatus | None = None,
        limit: int = 50,
    ) -> list[Task]:
        """按创建时间倒序列出任务；project_id / status 为可选过滤条件（列表页用）。"""
        statement = select(Task).order_by(Task.created_at.desc(), Task.id.desc()).limit(limit)
        if project_id is not None:
            statement = statement.where(Task.project_id == project_id)
        if status is not None:
            statement = statement.where(Task.status == status.value)
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    async def set_status(self, task_id: uuid.UUID, status: TaskStatus) -> Task:
        """不存在时抛 NotFoundError。"""
        async with self._session_factory() as session:
            task = await session.get(Task, task_id)
            if task is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            task.status = status.value
            await self._commit(session)
            return task

    async def set_decision_mode(self, task_id: uuid.UUID, mode: DecisionMode) -> Task:
        """切换任务级决策策略（文档 §5）：只影响后续遇到的决策点，不改历史记录。"""
        async with self._session_factory() as session:
            task = await self._require(session, task_id)
            task.decision_mode = str(mode)
            await self._commit(session)
            return task

    async def set_confirmation(self, task_id: uuid.UUID, confirmation: dict[str, Any]) -> Task:
        """写入需求确认（P0-06）。整体覆盖：确认卡的权威副本就是最后一次写入的那份。"""
        async with self._session_factory() as session:
            task = await self._require(session, task_id)
            task.confirmation = confirmation
            await self._commit(session)
            return task

    async def set_run_id(self, task_id: uuid.UUID, run_id: str | None) -> Task:
        """记下该任务触发的 DSH Run（P0-05），Run 终态靠它回写任务状态。

        传 None 是"撤回预登记"——起 Run 失败时用它把任务退回"还没执行"的状态。
        """
        async with self._session_factory() as session:
            task = await self._require(session, task_id)
            task.run_id = run_id
            await self._commit(session)
            return task

    async def get_by_run_id(self, run_id: str) -> Task | None:
        """按 DSH Run 反查任务；没有对应任务时返回 None（Run 可以是独立发起的）。"""
        async with self._session_factory() as session:
            return await session.scalar(select(Task).where(Task.run_id == run_id))

    # --- 决策点（文档 §5）---

    async def raise_decision(self, task_id: uuid.UUID, decision: dict[str, Any]) -> Task:
        """追加一条决策点记录。读取与写入在同一会话里完成，避免并发下丢记录。"""
        async with self._session_factory() as session:
            task = await self._require(session, task_id)
            task.decisions = [*(task.decisions or []), decision]
            await self._commit(session)
            return task

    async def resolve_decision(
        self,
        task_id: uuid.UUID,
        decision_id: str,
        *,
        status: DecisionStatus,
        chosen: str | None,
        note: str | None,
        resolved_at: str,
    ) -> tuple[Task, dict[str, Any]]:
        """把某条决策点置为终态；返回任务与更新后的记录。决策点不存在时抛 NotFoundError。"""
        async with self._session_factory() as session:
            task = await self._require(session, task_id)
            records = list(task.decisions or [])
            updated: dict[str, Any] | None = None
            for index, record in enumerate(records):
                if isinstance(record, dict) and record.get("id") == decision_id:
                    updated = {
                        **record,
                        "status": str(status),
                        "chosen": chosen,
                        "note": (note or "").strip() or None,
                        "resolved_at": resolved_at,
                    }
                    records[index] = updated
                    break
            if updated is None:
                raise NotFoundError(
                    f"决策点 {decision_id} 不存在",
                    details={"task_id": str(task_id), "decision_id": decision_id},
                )
            task.decisions = records
            await self._commit(session)
            return task, updated

    async def _require(self, session: AsyncSession, task_id: uuid.UUID) -> Task:
        task = await session.get(Task, task_id)
        if task is None:
            raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
        return task

    # --- 任务对话消息（task_messages）---

    async def add_messages(
        self, task_id: uuid.UUID, drafts: Sequence[MessageDraft]
    ) -> list[TaskMessage]:
        """向任务追加一批消息，返回落库后的消息（seq 连续、含 created_at）。

        整批消息在同一事务里写入：一轮对话（用户消息 + 助手回复）要么都落库、要么都不落，
        不会出现"只存了用户那句、回复丢了"的半个回合。任务不存在时抛 NotFoundError。
        """
        async with self._session_factory() as session:
            if await session.get(Task, task_id) is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            last_seq = await session.scalar(
                select(func.max(TaskMessage.seq)).where(TaskMessage.task_id == task_id)
            )
            seq = int(last_seq or 0)
            created: list[TaskMessage] = []
            for draft in drafts:
                seq += 1
                message = TaskMessage(
                    id=uuid.uuid4(),
                    task_id=task_id,
                    seq=seq,
                    role=draft.role,
                    kind=draft.kind,
                    content=draft.content,
                    payload=draft.payload,
                )
                session.add(message)
                created.append(message)
            await self._commit(session)
            for message in created:
                # 同 create()：created_at 来自 server_default，离开会话前取回
                await session.refresh(message)
            return created

    async def list_messages(
        self, task_id: uuid.UUID, *, limit: int = 30, before: int | None = None
    ) -> tuple[list[TaskMessage], bool]:
        """按 seq 升序返回一段消息，以及"是否还有更早的消息"。

        `before` 是向上懒加载的游标：只取 seq < before 的消息。
        默认取最新的一段——先按 seq 倒序多取一条判 has_more，再翻回升序给调用方。
        """
        async with self._session_factory() as session:
            if await session.get(Task, task_id) is None:
                raise NotFoundError(f"任务 {task_id} 不存在", details={"task_id": str(task_id)})
            statement = select(TaskMessage).where(TaskMessage.task_id == task_id)
            if before is not None:
                statement = statement.where(TaskMessage.seq < before)
            rows = list(
                await session.scalars(statement.order_by(TaskMessage.seq.desc()).limit(limit + 1))
            )
        has_more = len(rows) > limit
        window = rows[:limit]
        window.reverse()
        return window, has_more

    async def _commit(self, session: AsyncSession) -> None:
        """提交会话；违反数据库约束时转为 400（不引入全局处理器，见交付说明）。"""
        try:
            await session.commit()
        except IntegrityError as exc:
            await session.rollback()
            logger.warning("任务写入违反数据库约束：%s", exc.orig)
            raise BadRequestError(f"任务写入违反数据库约束：{exc.orig}") from exc
