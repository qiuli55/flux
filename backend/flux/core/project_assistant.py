"""项目助理：数据读写、自动建议与只读上下文装配。

产品边界（2026-11-28）：
- 项目助理与主助理同级，但**没有**创建临时子任务的能力；
- 只使用已有对话作为只读上下文，进度写在项目助理自己的常驻对话里；
- 不自动创建、不在未经确认时写任何对话；创建/发言都要用户明确确认。
"""

from __future__ import annotations

import contextlib
import uuid
from collections import defaultdict
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.core.agent_runtime.run_repository import as_utc, utcnow
from flux.core.attachment_store import resolve_uploads, size_label
from flux.core.task_engine.repository import MessageDraft
from flux.enums import TaskMessageRole, TaskOrigin, TaskStatus
from flux.errors import BadRequestError, ConflictError, NotFoundError, ValidationError
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
#: 「可能已停住」阈值：对话仍在进行中，但超过这么多小时没有新消息。
#: 写成可测常量——接口会把它原样返回，客户端与测试都按同一个数展示。
STALLED_AFTER_HOURS = 6
_STALLED_AFTER_SECONDS = STALLED_AFTER_HOURS * 3600
#: 读历史时一次最多带上的消息条数（只读查看，不写入）。
_HISTORY_MESSAGE_LIMIT = 200
#: 开副本时摘要最多带的来源消息条数 / 字符数（确定性摘要，不调用外部模型）。
_FORK_SOURCE_MESSAGES = 24
_FORK_SUMMARY_CHARS = 4000
#: 进度汇总里每段对话最多列出的摘录字数。
_PROGRESS_EXCERPT = 80

#: 进行中的任务状态：这些状态可能被归入「进行中」或「可能已停住」。
_ACTIVE_STATUSES = {
    TaskStatus.PENDING.value,
    TaskStatus.RUNNING.value,
    TaskStatus.WAITING_FOR_USER_DECISION.value,
}
_BUCKET_LABELS = {
    "running": "进行中",
    "possibly_stalled": "可能已停住",
    "failed": "失败",
    "completed": "已完成",
    "cancelled": "已取消",
    "unknown": "状态未知",
}


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

    # --- 进度汇总 / 读历史 / 接着干 / 开副本（2026-11-28 补齐） ---

    async def progress(self, assistant: ProjectAssistant) -> dict[str, Any]:
        """只读汇总相关对话的进度：进行中 / 可能已停住 / 失败 / 已完成。

        全部数据来自服务端已有记录（SELECT only）：不启动电脑 Agent、不派子任务，
        也不要求用户的电脑一直开着。来源对话缺失时跳过并记在 `missing_sources`。
        """
        container = self._container
        now = utcnow()
        items: list[dict[str, Any]] = []
        missing: list[str] = []
        counts = {key: 0 for key in _BUCKET_LABELS}
        for task_id in assistant.source_ids():
            try:
                task_uuid = uuid.UUID(task_id)
                task = await container.task_repo.get(task_uuid)
            except (ValueError, NotFoundError):
                missing.append(task_id)
                continue
            messages, _ = await container.task_repo.list_messages(task_uuid, limit=1)
            last_message = messages[-1] if messages else None
            last_activity = _last_activity(task, last_message)
            idle_seconds = _idle_seconds(last_activity, now)
            bucket = _progress_bucket(task.status, idle_seconds)
            counts[bucket] += 1
            items.append(
                {
                    "task_id": str(task.id),
                    "title": _task_title(task),
                    "status": task.status,
                    "status_label": _task_status_label(task.status),
                    "bucket": bucket,
                    "bucket_label": _BUCKET_LABELS[bucket],
                    "idle_hours": round(idle_seconds / 3600, 1),
                    "last_activity_at": last_activity.isoformat(),
                    "last_message": _excerpt(
                        last_message.content if last_message is not None else ""
                    ),
                    "message_count": await _message_count(container, task_uuid),
                    "read_only": True,
                }
            )

        # 进行中的排在前面，其余按最后活动时间倒序：用户先看到需要决定的事。
        order = {"possibly_stalled": 0, "running": 1, "failed": 2, "cancelled": 3, "unknown": 4}
        items.sort(key=lambda item: (order.get(item["bucket"], 9), -item["idle_hours"]))
        return {
            "assistant_id": str(assistant.id),
            "assistant_name": assistant.name,
            "generated_at": now.isoformat(),
            "stalled_after_hours": STALLED_AFTER_HOURS,
            "counts": counts,
            "conversations": items,
            "missing_sources": missing,
        }

    async def history(self, assistant: ProjectAssistant) -> dict[str, Any]:
        """列出可「读历史」的相关对话（只读元数据，不写任何来源对话）。"""
        progress = await self.progress(assistant)
        return {
            "assistant_id": str(assistant.id),
            "assistant_name": assistant.name,
            "read_only": True,
            "count": len(progress["conversations"]),
            "conversations": progress["conversations"],
            "missing_sources": progress["missing_sources"],
        }

    async def read_history(
        self, assistant: ProjectAssistant, source_task_id: str
    ) -> dict[str, Any]:
        """只读打开一条相关对话及其消息；这里绝不写入来源对话。"""
        task = await self._related_task(assistant, source_task_id)
        container = self._container
        messages, has_more = await container.task_repo.list_messages(
            task.id, limit=_HISTORY_MESSAGE_LIMIT
        )
        return {
            "assistant_id": str(assistant.id),
            "read_only": True,
            "has_more": has_more,
            "task": task.to_dict(),
            "messages": [message.to_dict() for message in messages],
        }

    async def continue_confirmed(
        self,
        assistant: ProjectAssistant,
        *,
        source_task_id: str,
        content: str,
        attachment_ids: list[str] | None,
        confirmed: bool,
    ) -> dict[str, Any]:
        """「接着干」：二次确认后，把新任务发进已有相关对话并续跑。

        `confirmed=False` / 缺失时直接 409，什么都不写；目标对话原样不动。
        只有真正「进行中」（这一轮还在跑、更新尚未过期）才 409；「可能已停住」
        在用户确认后先安全结束旧的一轮，再开始新一轮，避免并行写入。
        这不是临时子任务：目标对话仍是普通对话，不设 parent_task_id。
        """
        if not confirmed:
            raise ConflictError("接着干需要用户再次确认；未确认时不会向原对话发送任何内容")
        task = await self._related_task(assistant, source_task_id)
        container = self._container
        files = resolve_uploads(container.settings, attachment_ids or [])
        text = (content or "").strip()
        if not text and not files:
            raise ValidationError(
                "接着干需要带上要发送的内容", details={"source_task_id": source_task_id}
            )

        # 与进度汇总同一套分桶：只有 recently active 的「进行中」才挡；可能已停住放行。
        messages, _ = await container.task_repo.list_messages(task.id, limit=1)
        last_message = messages[-1] if messages else None
        idle_seconds = _idle_seconds(_last_activity(task, last_message), utcnow())
        bucket = _progress_bucket(task.status, idle_seconds)
        if bucket == "running":
            raise ConflictError(
                "这段对话还在进行中；等这一轮结束后再接着干，或先看它的进度",
                details={"task_id": str(task.id), "status": task.status},
            )

        previous_status = task.status
        if bucket == "possibly_stalled":
            await self._end_stalled_run(task)
            # 旧的一轮已结束：起不来时退回「已取消」，不留下没有 Run 的 running 僵尸。
            fallback_status = TaskStatus.CANCELLED
        else:
            fallback_status = TaskStatus(previous_status)
        task = await container.task_repo.reopen(task.id)
        user_message = (
            await container.task_repo.add_messages(
                task.id,
                [MessageDraft(role=TaskMessageRole.USER.value, content=text)],
            )
        )[0]
        await container.task_stream.publish(
            task.id, "user_message", {"message": user_message.to_dict()}
        )
        if files:
            attachment_message = (
                await container.task_repo.add_messages(
                    task.id,
                    [
                        MessageDraft(
                            role=TaskMessageRole.USER.value,
                            kind="attachments",
                            content="、".join(item.name for item in files),
                            payload={"files": [item.to_dict() for item in files]},
                        )
                    ],
                )
            )[0]
            await container.task_stream.publish(
                task.id, "user_message", {"message": attachment_message.to_dict()}
            )
        followup = text or (
            "请看我随消息附上的文件："
            + "、".join(f"{item.name}（{size_label(item.size)}）" for item in files)
        )
        try:
            result = await container.task_run_service.start(task, followup=followup)
        except Exception:
            # 起不来时退回原终态：用户消息保留，修好 runtime 后可以重试，
            # 不留下没有 Run 支撑的 running 僵尸。
            current = await container.task_repo.get(task.id)
            if current.run_id is None and current.status in _ACTIVE_STATUSES:
                await container.task_repo.set_status(task.id, fallback_status)
            raise
        logger.info("接着干：项目助理=%s 目标对话=%s 附件=%d", assistant.id, task.id, len(files))
        return {
            "task": result.task.to_dict(),
            "messages": [user_message.to_dict()],
            "read_only": False,
        }

    async def fork_confirmed(
        self,
        assistant: ProjectAssistant,
        *,
        source_task_id: str,
        name: str | None,
        confirmed: bool,
    ) -> dict[str, Any]:
        """「开副本」：二次确认后新建一条独立常驻对话，带上源对话的思路/上下文。

        原文一条消息都不改。副本是普通对话（origin=chat、无 parent_task_id），
        既不是临时子任务，也不会进右上角切换器。
        """
        if not confirmed:
            raise ConflictError("开副本需要用户再次确认；未确认时不会创建任何内容")
        source = await self._related_task(assistant, source_task_id)
        summary = await self._source_digest(source)
        if not summary:
            raise ConflictError(
                "这段对话还没有可以带过去的内容，先在原对话里聊几句再开副本",
                details={"source_task_id": source_task_id},
            )
        clean_name = (name or "").strip() or f"{_task_title(source)}（副本）"
        clean_name = clean_name[:64]
        container = self._container
        fork = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=clean_name,
            priority=source.priority,
            project_id=source.project_id,
            agent_id=source.agent_id,
            decision_mode=source.decision_mode,
            permission_mode=source.permission_mode,
            model=source.model,
            api_key_id=source.api_key_id,
            # 副本是常驻普通对话：不是临时子任务，也不挂到主助理子任务列表
            origin=TaskOrigin.CHAT,
            bot_name=clean_name,
            parent_task_id=None,
        )
        await container.task_repo.add_messages(
            fork.id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="summary",
                    content=summary,
                    payload={"from_task_id": str(source.id), "source": "project_assistant_fork"},
                )
            ],
        )
        logger.info(
            "开副本：项目助理=%s 源对话=%s 副本=%s 摘要字数=%d",
            assistant.id,
            source.id,
            fork.id,
            len(summary),
        )
        return {
            "task": fork.to_dict(),
            "source_task_id": str(source.id),
            "summary_chars": len(summary),
            "parent_task_id": None,
            "origin": TaskOrigin.CHAT.value,
        }

    async def _end_stalled_run(self, task: Task) -> None:
        """安全结束「可能已停住」的一轮：取消排队/还在的旧 Run，再标记为已取消。

        先标终态再 reopen：旧 Run 的终态事件即便晚到，也会因为任务已不是终态前的
        active 状态（且随后 run_id 被清掉）而不会把新一轮写回 running。
        """
        container = self._container
        with contextlib.suppress(NotFoundError):
            container.scheduler.cancel(str(task.id))
        if task.run_id:
            # Run 可能早已不存在或已属终态：结束失败不应挡住用户接着干。
            with contextlib.suppress(Exception):
                await container.dsh.cancel(task.run_id)
        await container.task_repo.set_status(task.id, TaskStatus.CANCELLED)

    async def _related_task(self, assistant: ProjectAssistant, source_task_id: str) -> Task:
        """校验目标对话确实属于这个项目助理的来源集合，且是一条普通对话。"""
        try:
            task_uuid = uuid.UUID(str(source_task_id))
        except ValueError as exc:
            raise BadRequestError(
                f"source_task_id 不是合法 UUID：{source_task_id}",
                details={"source_task_id": source_task_id},
            ) from exc
        if str(task_uuid) not in assistant.source_ids():
            raise NotFoundError(
                "这段对话不在这个项目助理的相关对话里",
                details={"assistant_id": str(assistant.id), "task_id": str(task_uuid)},
            )
        task = await self._container.task_repo.get(task_uuid)
        if task.origin != TaskOrigin.CHAT.value or task.parent_task_id is not None:
            raise ConflictError(
                "项目助理只能读普通对话；临时子任务和助理对话不能作为目标",
                details={"task_id": str(task_uuid), "origin": task.origin},
            )
        return task

    async def _source_digest(self, source: Task) -> str:
        """生成确定性的只读前情摘要（不调用外部模型，原文不动）。

        只带用户与助理/Agent 的正文；密钥、附件记录等跳过。
        """
        messages, _ = await self._container.task_repo.list_messages(
            source.id, limit=_FORK_SOURCE_MESSAGES
        )
        lines = [f"来自已有对话「{_task_title(source)}」的前情摘要："]
        used = 0
        for message in messages:
            if message.role not in {"user", "assistant"}:
                continue
            if message.kind in {"secure_input", "secure_input_result", "attachments"}:
                continue
            text = " ".join((message.content or "").split())
            if not text:
                continue
            speaker = "用户" if message.role == "user" else "助理/Agent"
            line = f"- {speaker}：{text[:400]}"
            if used + len(line) > _FORK_SUMMARY_CHARS:
                lines.append("…（已截断）")
                break
            lines.append(line)
            used += len(line)
        if len(lines) <= 1:
            return ""
        return "\n".join(lines)


def _task_title(task: Task) -> str:
    return (task.bot_name or task.description or "未命名对话").strip()[:60]


def _task_status_label(status: str) -> str:
    return {
        TaskStatus.PENDING.value: "已派出",
        TaskStatus.RUNNING.value: "进行中",
        TaskStatus.WAITING_FOR_USER_DECISION.value: "等你决定",
        TaskStatus.COMPLETED.value: "已完成",
        TaskStatus.FAILED.value: "失败",
        TaskStatus.CANCELLED.value: "已取消",
    }.get(status, status)


def _last_activity(task: Task, last_message: Any | None) -> Any:
    """最后一次活动时间：有消息看消息时间，没消息看任务更新时间。"""
    last_activity = last_message.created_at if last_message is not None else task.updated_at
    return last_activity if last_activity is not None else task.created_at


def _idle_seconds(last_activity: Any, now: Any) -> int:
    return max(0, int((now - as_utc(last_activity)).total_seconds()))


def _progress_bucket(status: str, idle_seconds: int) -> str:
    """把任务状态 + 静默时长归入用户能理解的进度分组。"""
    if status in _ACTIVE_STATUSES:
        return "possibly_stalled" if idle_seconds >= _STALLED_AFTER_SECONDS else "running"
    if status == TaskStatus.FAILED.value:
        return "failed"
    if status == TaskStatus.COMPLETED.value:
        return "completed"
    if status == TaskStatus.CANCELLED.value:
        return "cancelled"
    return "unknown"


def _excerpt(text: str, limit: int = _PROGRESS_EXCERPT) -> str:
    return " ".join((text or "").split())[:limit]


async def _message_count(container: Any, task_id: uuid.UUID) -> int:
    """来源对话的消息条数：用于「读历史」列表呈现，不额外拉全量消息。"""
    try:
        messages, has_more = await container.task_repo.list_messages(
            task_id, limit=_HISTORY_MESSAGE_LIMIT
        )
    except NotFoundError:
        return 0
    # has_more 只表示「还有更早的消息」，这里按至少再多一条计数即可。
    return len(messages) + (1 if has_more else 0)


def progress_text(progress: dict[str, Any]) -> str:
    """把进度汇总写成 Flux 自己的简体中文短报（项目助理回复先给这份）。"""
    counts = progress.get("counts", {})
    stalled_hours = progress.get("stalled_after_hours", STALLED_AFTER_HOURS)
    lines = [
        "进度汇总：",
        f"- 进行中：{counts.get('running', 0)}",
        f"- 可能已停住：{counts.get('possibly_stalled', 0)}（超过 {stalled_hours} 小时没有新消息）",
        f"- 失败：{counts.get('failed', 0)}",
        f"- 已完成：{counts.get('completed', 0)}",
    ]
    if counts.get("cancelled"):
        lines.append(f"- 已取消：{counts['cancelled']}")
    conversations = progress.get("conversations") or []
    if not conversations:
        lines.append("还没有关联任何已有对话。")
    else:
        lines.append("相关对话：")
        for item in conversations[:8]:
            lines.append(
                f"- [{item['bucket_label']}] {item['title']}"
                f"（最后更新 {item['idle_hours']} 小时前）"
            )
    lines.append(
        "接下来可以：读历史（只读查看）、接着干（确认后继续原对话）、"
        "开副本（确认后另开一条常驻对话）。"
    )
    return "\n".join(lines)


__all__ = [
    "ProjectAssistantRepository",
    "ProjectAssistantService",
    "STALLED_AFTER_HOURS",
    "progress_text",
]
