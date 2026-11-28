"""项目助理：数据读写、自动建议与只读上下文装配。

产品边界（2026-11-28）：
- 项目助理与主助理同级，但**没有**创建临时子任务的能力；
- 只使用已有对话作为只读上下文，进度写在项目助理自己的常驻对话里；
- 不自动创建、不在未经确认时写任何对话；创建/发言都要用户明确确认。
"""

from __future__ import annotations

import uuid
from collections import defaultdict
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.enums import TaskOrigin
from flux.errors import ConflictError, NotFoundError
from flux.logging import get_logger
from flux.models.project_assistant import ProjectAssistant
from flux.models.task import Task

logger = get_logger(__name__)

#: 项目助理读取来源对话时，每个来源最多带上的消息条数（只读汇总，不搬全量）。
_SOURCE_MESSAGE_LIMIT = 40
#: 汇总进提示词的总字符上限：再长的历史也只保留最近的、能读得完的一段。
_CONTEXT_CHAR_LIMIT = 12000
#: 建议里每个项目最多列出的对话条数（只是建议卡片的摘要，不是全量）。
_SUGGESTION_PREVIEW = 5


class ProjectAssistantRepository:
    """project_assistants 表的读写入口。"""

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def create(
        self,
        *,
        name: str,
        conversation_task_id: uuid.UUID,
        project_id: uuid.UUID | None = None,
        source_task_ids: list[str] | None = None,
    ) -> ProjectAssistant:
        async with self._session_factory() as session:
            assistant = ProjectAssistant(
                id=uuid.uuid4(),
                name=name.strip()[:64],
                project_id=project_id,
                conversation_task_id=conversation_task_id,
                source_task_ids=[str(item) for item in (source_task_ids or [])],
            )
            session.add(assistant)
            try:
                await session.commit()
            except Exception as exc:  # noqa: BLE001 - 会话任务唯一约束冲突按 409 说明
                await session.rollback()
                raise ConflictError(
                    "这个项目助理的常驻对话已经被占用",
                    details={"conversation_task_id": str(conversation_task_id)},
                ) from exc
            await session.refresh(assistant)
            return assistant

    async def get(self, assistant_id: uuid.UUID) -> ProjectAssistant:
        async with self._session_factory() as session:
            assistant = await session.get(ProjectAssistant, assistant_id)
            if assistant is None:
                raise NotFoundError(
                    f"项目助理 {assistant_id} 不存在", details={"assistant_id": str(assistant_id)}
                )
            return assistant

    async def get_by_conversation(self, task_id: uuid.UUID) -> ProjectAssistant | None:
        async with self._session_factory() as session:
            return await session.scalar(
                select(ProjectAssistant).where(ProjectAssistant.conversation_task_id == task_id)
            )

    async def list_all(self) -> list[ProjectAssistant]:
        async with self._session_factory() as session:
            return list(
                await session.scalars(
                    select(ProjectAssistant).order_by(
                        ProjectAssistant.created_at.asc(), ProjectAssistant.id.asc()
                    )
                )
            )

    async def delete(self, assistant_id: uuid.UUID) -> uuid.UUID | None:
        """删除项目助理记录，返回它自己的常驻对话 id（由调用方决定是否一并删对话）。"""
        async with self._session_factory() as session:
            assistant = await session.get(ProjectAssistant, assistant_id)
            if assistant is None:
                raise NotFoundError(
                    f"项目助理 {assistant_id} 不存在", details={"assistant_id": str(assistant_id)}
                )
            conversation_id = assistant.conversation_task_id
            await session.delete(assistant)
            await session.commit()
            return conversation_id


class ProjectAssistantService:
    """建议 / 创建 / 只读上下文：不碰任何写路径，除非调用方明确要求创建。"""

    def __init__(self, container: Any) -> None:
        self._container = container

    async def suggestions(self, *, limit: int = 20) -> list[dict[str, Any]]:
        """从全部已有对话自动建议可创建的项目助理；只返回建议，不创建任何东西。

        规则（确定性、可解释）：按对话所属项目分组；没有项目的对话按「最近一条
        用户可见标题」聚成一个「未归档对话」建议。已有项目助理覆盖的项目不再建议。
        """
        container = self._container
        conversations = await container.task_repo.list_conversations(
            origin=TaskOrigin.CHAT, limit=200
        )
        existing = await container.project_assistants.list_all()
        covered_projects = {str(item.project_id) for item in existing if item.project_id}
        covered_sources = {sid for item in existing for sid in item.source_ids()}

        projects = {
            str(project.id): project.name for project in await container.brain.list_projects()
        }
        grouped: dict[str, list[Task]] = defaultdict(list)
        for summary in conversations:
            task = summary.task
            if str(task.id) in covered_sources:
                continue
            key = str(task.project_id) if task.project_id is not None else "__unfiled__"
            grouped[key].append(task)

        ideas: list[dict[str, Any]] = []
        for key, tasks in grouped.items():
            if key in covered_projects:
                continue
            if key == "__unfiled__":
                name = "未归档项目"
                project_id = None
            else:
                name = projects.get(key) or "未命名项目"
                project_id = key
            if not tasks:
                continue
            ideas.append(
                {
                    "name": name,
                    "project_id": project_id,
                    "conversation_count": len(tasks),
                    "conversation_ids": [str(task.id) for task in tasks[:_SUGGESTION_PREVIEW]],
                    "titles": [
                        (task.bot_name or task.description or "未命名对话").strip()[:40]
                        for task in tasks[:_SUGGESTION_PREVIEW]
                    ],
                }
            )
        ideas.sort(key=lambda item: (-item["conversation_count"], item["name"]))
        return ideas[:limit]

    async def read_only_context(self, assistant: ProjectAssistant) -> str:
        """把来源对话压成只读上下文（服务端已有记录，不启动电脑 Agent、不派子任务）。

        只读是硬约束：这里只 SELECT，不向任何来源对话写消息。
        """
        container = self._container
        blocks: list[str] = []
        used = 0
        for task_id in assistant.source_ids():
            try:
                task_uuid = uuid.UUID(task_id)
                task = await container.task_repo.get(task_uuid)
            except (ValueError, NotFoundError):
                continue
            messages, _ = await container.task_repo.list_messages(
                task_uuid, limit=_SOURCE_MESSAGE_LIMIT
            )
            lines = [f"## 对话：{(task.bot_name or task.description or '未命名')[:60]}"]
            for message in messages:
                if message.role not in {"user", "assistant"}:
                    continue
                if message.kind in {"secure_input", "secure_input_result"}:
                    continue  # 密钥内容绝不进汇总上下文
                text = " ".join((message.content or "").split())
                if not text:
                    continue
                speaker = "用户" if message.role == "user" else "助理/Agent"
                lines.append(f"- {speaker}：{text[:600]}")
            block = "\n".join(lines)
            if used + len(block) > _CONTEXT_CHAR_LIMIT:
                remaining = max(0, _CONTEXT_CHAR_LIMIT - used)
                if remaining <= 0:
                    break
                block = block[:remaining] + "\n…（已截断）"
            blocks.append(block)
            used += len(block)
        return "\n\n".join(blocks)

    async def create_confirmed(
        self,
        *,
        name: str,
        source_task_ids: list[str] | None,
        project_id: str | None,
        confirmed: bool,
    ) -> ProjectAssistant:
        """用户确认后才创建项目助理（含它自己的常驻对话）。

        `confirmed=False` 时什么都不创建，直接 409——这是产品要求的「取消就是不创建」。
        """
        if not confirmed:
            raise ConflictError("创建项目助理需要用户确认；未确认时不会创建任何内容")
        clean_name = (name or "").strip()
        if not clean_name:
            raise ConflictError("项目助理需要先起一个名字")
        container = self._container
        project_uuid: uuid.UUID | None = None
        if project_id:
            try:
                project_uuid = uuid.UUID(project_id)
            except ValueError as exc:
                raise ConflictError(f"project_id 不是合法 UUID：{project_id}") from exc

        sources: list[str] = []
        for raw in source_task_ids or []:
            try:
                task_uuid = uuid.UUID(raw)
            except ValueError:
                continue
            try:
                task = await container.task_repo.get(task_uuid)
            except NotFoundError:
                continue
            # 只允许把普通对话当来源；项目助理/子任务的对话不进来源集合
            if task.origin == TaskOrigin.CHAT.value and task.parent_task_id is None:
                sources.append(str(task_uuid))

        # 常驻对话是项目助理自己的空间：用户确认创建即确认了这条必要的新对话。
        conversation = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=f"项目助理：{clean_name}",
            priority=100,
            project_id=project_uuid,
            agent_id=None,
            origin=TaskOrigin.PROJECT_ASSISTANT,
            bot_name=clean_name,
        )
        assistant = await container.project_assistants.create(
            name=clean_name,
            conversation_task_id=conversation.id,
            project_id=project_uuid,
            source_task_ids=sources,
        )
        logger.info(
            "已创建项目助理 id=%s name=%s 来源对话=%d conversation=%s",
            assistant.id,
            clean_name,
            len(sources),
            conversation.id,
        )
        return assistant


__all__ = ["ProjectAssistantRepository", "ProjectAssistantService"]
