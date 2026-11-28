"""临时子任务调度（服务端权威）。

主助理不干活：用户提出执行需求时，由这里立即创建一条子任务（真实 Task），
把父对话当下的 agent / model / api_key_id 快照过去，在主对话写一张可点开的卡片，
然后在**后台**启动子任务的 Agent。父请求绝不等待子任务跑完。

子任务终态（完成/失败/取消）会回写一条父对话消息并推父对话 SSE——结果不能只烂在子任务里。
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from typing import Any

from flux.core.task_engine.repository import MessageDraft
from flux.enums import TaskMessageRole, TaskOrigin, TaskStatus
from flux.errors import NotFoundError
from flux.logging import get_logger
from flux.models.task import Task

logger = get_logger(__name__)

#: 子任务仍在进行中的状态：更换时停掉的就是这些。
_ACTIVE_STATUSES = {
    TaskStatus.PENDING.value,
    TaskStatus.RUNNING.value,
    TaskStatus.WAITING_FOR_USER_DECISION.value,
}

#: 电脑 / runtime 不可用时给用户的中文说明（子任务与父对话都要说清）。
UNAVAILABLE_MESSAGE = "电脑现在不可用，这件事暂时办不了；等电脑回来我再继续，你先跟我聊别的。"


def status_label(status: str) -> str:
    return {
        TaskStatus.PENDING.value: "已派出",
        TaskStatus.RUNNING.value: "进行中",
        TaskStatus.WAITING_FOR_USER_DECISION.value: "等你决定",
        TaskStatus.COMPLETED.value: "已完成",
        TaskStatus.FAILED.value: "失败",
        TaskStatus.CANCELLED.value: "已取消",
    }.get(status, status)


def subtask_payload(task: Task, *, result: str | None = None) -> dict[str, Any]:
    """卡片 / 结果消息的统一 payload：客户端据此渲染状态与跳转入口。"""
    payload: dict[str, Any] = {
        "subtask_id": str(task.id),
        "parent_task_id": str(task.parent_task_id) if task.parent_task_id else None,
        "status": task.status,
        "status_label": status_label(task.status),
        "agent_id": str(task.agent_id) if task.agent_id else None,
        # 用 model_name 而不是 model：助手回复的 payload.model 是对象，
        # 同一 kind 命名空间里复用一个键会让三端解析歧义。
        "model_name": task.model,
        "api_key_id": str(task.api_key_id) if task.api_key_id else None,
        "title": (task.bot_name or task.description or "临时子任务").strip()[:120],
    }
    if result is not None:
        payload["result"] = result[:2000]
    return payload


class SubtaskDispatcher:
    """创建 / 更换 / 并行新增子任务，并在后台启动它们。"""

    def __init__(self, container: Any) -> None:
        self._container = container
        #: 后台启动协程的强引用，避免 create_task 后被 GC 回收
        self._jobs: set[asyncio.Task[Any]] = set()

    # --- 对外动作 ---

    async def dispatch(
        self,
        parent: Task,
        text: str,
        *,
        agent_id: uuid.UUID | None = None,
        model: str | None = None,
        api_key_id: uuid.UUID | None = None,
        replace: bool = False,
        note: str | None = None,
    ) -> tuple[Task, list[Any]]:
        """派一个子任务，返回 (子任务, 父对话新增消息)。父请求不等待执行。"""
        container = self._container
        cancelled: list[Task] = []
        if replace:
            cancelled = await self.cancel_active(parent)
        # 更换是「接着做同一需求」：新子任务沿用被替换任务的原始需求，
        # 而不是把「换用某某做」这句指令本身当成需求。
        requirement = text.strip()
        if replace and cancelled:
            requirement = (cancelled[-1].description or requirement).strip()

        child = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=requirement or (parent.description or "临时子任务"),
            priority=parent.priority,
            project_id=parent.project_id,
            # 默认快照父对话当下的选择；用户明确指定时才覆盖
            agent_id=agent_id if agent_id is not None else parent.agent_id,
            decision_mode=parent.decision_mode,
            permission_mode=parent.permission_mode,
            model=model if model is not None else parent.model,
            api_key_id=api_key_id if api_key_id is not None else parent.api_key_id,
            origin=TaskOrigin.SUBTASK,
            bot_name=(requirement or "临时子任务")[:40],
            parent_task_id=parent.id,
        )

        if note is None and replace:
            label = self._selection_label(
                agent_id if agent_id is not None else parent.agent_id,
                model if model is not None else parent.model,
            )
            note = f"已按你的要求更换为 {label}，原任务已停止，新任务已派出。"
        headline = note or "已交给子任务处理，你可以继续跟我说。"
        drafts = [
            MessageDraft(
                role=TaskMessageRole.ASSISTANT.value,
                kind="subtask",
                content=headline,
                payload=subtask_payload(child),
            )
        ]
        for old in cancelled:
            drafts.append(
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="subtask_result",
                    content=f"已停止上一个子任务（{status_label(old.status)}）。",
                    payload=subtask_payload(old, result="已被更换"),
                )
            )
        messages = await container.task_repo.add_messages(parent.id, drafts)
        await container.task_stream.publish(
            parent.id,
            "subtask_created",
            {
                "message": messages[0].to_dict(),
                "subtask": _task_dict(child),
                "replaced": [str(item.id) for item in cancelled],
            },
        )
        for old in cancelled:
            await container.task_stream.publish(
                parent.id,
                "subtask_updated",
                {"subtask_id": str(old.id), "status": old.status},
            )

        # 后台启动：父请求在这里就返回，绝不 await 子任务跑完
        job = asyncio.create_task(self._start_child(child.id))
        self._jobs.add(job)
        job.add_done_callback(self._jobs.discard)
        logger.info(
            "已派子任务 parent=%s child=%s replace=%s agent=%s model=%s",
            parent.id,
            child.id,
            replace,
            child.agent_id,
            child.model,
        )
        return child, messages

    async def cancel_active(self, parent: Task) -> list[Task]:
        """停止「最近一个进行中的」子任务（更换时用），返回被停止的任务。

        用户可能同时开了多个并行子任务；没说指定哪一个时，按产品决定只更换最近一个，
        其余并行任务继续跑。
        """
        container = self._container
        active = [
            child
            for child in await container.task_repo.list_children(parent.id)
            if child.status in _ACTIVE_STATUSES
        ]
        if not active:
            return []
        child = active[-1]
        with contextlib.suppress(NotFoundError):
            container.scheduler.cancel(str(child.id))
        if child.run_id:
            with contextlib.suppress(Exception):
                await container.dsh.cancel(child.run_id)
        updated = await container.task_repo.set_status(child.id, TaskStatus.CANCELLED)
        await container.task_repo.add_messages(
            child.id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="run_finished",
                    content="子任务已被更换，本轮停止。",
                    payload={"status": str(TaskStatus.CANCELLED), "reason": "replaced"},
                )
            ],
        )
        return [updated]

    def _selection_label(self, agent_id: uuid.UUID | None, model: str | None) -> str:
        """给用户看的选择说明：Agent 名（认不出时用 id 前缀）+ 模型。"""
        parts: list[str] = []
        if agent_id is not None:
            try:
                parts.append(str(self._container.agents.get(str(agent_id)).spec.name))
            except Exception:  # noqa: BLE001 - 注册表里没有就退回 id 前缀，不阻塞调度
                parts.append(f"Agent {str(agent_id)[:8]}")
        else:
            parts.append("内置执行器")
        if model:
            parts.append(f"模型 {model}")
        return " · ".join(parts)

    # --- 后台启动 ---

    async def _start_child(self, child_id: uuid.UUID) -> None:
        container = self._container
        try:
            child = await container.task_repo.get(child_id)
            # 派出后、后台真正启动前可能已被「更换」取消：终态子任务不能再起 Run
            if child.status in {
                TaskStatus.CANCELLED.value,
                TaskStatus.COMPLETED.value,
                TaskStatus.FAILED.value,
            }:
                return
            await container.task_run_service.start(child)
        except Exception as exc:  # noqa: BLE001 - 起不来要如实失败，不能让父对话一直等
            await self._fail_to_start(child_id, exc)

    async def _fail_to_start(self, child_id: uuid.UUID, exc: Exception) -> None:
        container = self._container
        try:
            child = await container.task_repo.get(child_id)
        except NotFoundError:
            return
        if child.status == TaskStatus.CANCELLED.value:
            # 用户已经更换/停掉它：不要用「启动失败」覆盖取消终态
            return
        reason = str(exc)[:300]
        logger.warning("子任务启动失败 child=%s error=%s", child_id, reason)
        with contextlib.suppress(Exception):
            child = await container.task_repo.set_status(child_id, TaskStatus.FAILED)
        with contextlib.suppress(Exception):
            await container.task_repo.add_messages(
                child.id,
                [
                    MessageDraft(
                        role=TaskMessageRole.ASSISTANT.value,
                        kind="run_finished",
                        content=UNAVAILABLE_MESSAGE,
                        payload={"status": str(TaskStatus.FAILED), "error": reason},
                    )
                ],
            )
            await container.task_stream.publish(
                child.id,
                "run_finished",
                {"status": str(TaskStatus.FAILED), "message": UNAVAILABLE_MESSAGE},
            )
        await self.on_child_terminal(child, TaskStatus.FAILED, UNAVAILABLE_MESSAGE)

    # --- 终态回写父对话 ---

    async def on_child_terminal(self, child: Task, status: TaskStatus, result: str) -> None:
        """子任务结束时在父对话写一句简短结果，并推父对话 SSE。"""
        if child.parent_task_id is None:
            return
        container = self._container
        try:
            parent = await container.task_repo.get(child.parent_task_id)
        except NotFoundError:
            return
        text = _terminal_text(status, result)
        payload = subtask_payload(child, result=text)
        payload["status"] = str(status)
        payload["status_label"] = status_label(str(status))
        message = (
            await container.task_repo.add_messages(
                parent.id,
                [
                    MessageDraft(
                        role=TaskMessageRole.ASSISTANT.value,
                        kind="subtask_result",
                        content=text,
                        payload=payload,
                    )
                ],
            )
        )[0]
        await container.task_stream.publish(
            parent.id,
            "subtask_updated",
            {
                "subtask_id": str(child.id),
                "status": str(status),
                "message": message.to_dict(),
            },
        )
        logger.info(
            "子任务终态已回写父对话 parent=%s child=%s status=%s", parent.id, child.id, status
        )


def _terminal_text(status: TaskStatus, result: str) -> str:
    body = (result or "").strip()
    if status is TaskStatus.COMPLETED:
        return f"子任务完成：{body[:600]}" if body else "子任务已完成，点开可以看过程。"
    if status is TaskStatus.CANCELLED:
        return f"子任务已取消：{body[:300]}" if body else "子任务已取消。"
    return f"子任务失败：{body[:400]}" if body else UNAVAILABLE_MESSAGE


def _task_dict(task: Task) -> dict[str, Any]:
    return task.to_dict()


__all__ = ["SubtaskDispatcher", "UNAVAILABLE_MESSAGE", "status_label", "subtask_payload"]
