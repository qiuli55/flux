"""Bot 生命周期编排：创建 / 改配置 / 手动开工 / 定时开工。

设计要点（与对话模式的唯一区别是生命周期）：
- 每次开工都是一条普通 `tasks` 记录（`tasks.bot_id` 指回 Bot），执行、计费、消息
  全部复用既有链路；
- 工作目录独立（`tasks.workspace` 快照），CLI runtime 据此把子进程 cwd 放进 Bot 目录；
- 定时靠落库的 `next_run_at`：后台循环按到期取 Bot，重启后从库里恢复，不丢计划；
- 同一时刻只允许一轮（上一轮未终态时跳过，防止定时撞车）。
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any

from flux.core.agent_runtime.runtimes.base import DEFAULT_RUNTIME
from flux.core.bot.cron import CronSchedule, parse_cron
from flux.core.bot.repository import BotRepository
from flux.core.group.avatar import normalize_color, normalize_shape
from flux.enums import BotStatus, DecisionMode, PermissionMode, TaskStatus
from flux.errors import BadRequestError, ConflictError
from flux.logging import get_logger
from flux.models.bot import Bot
from flux.models.task import Task

if TYPE_CHECKING:
    from flux.container import Container

logger = get_logger(__name__)

__all__ = ["BotService"]

#: 定时开工任务的优先级（与普通对话同档）
_DEFAULT_PRIORITY = 100


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _as_utc(value: datetime | None) -> datetime | None:
    """数据库可能返回 naive datetime（SQLite），统一按 UTC 解读。"""
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


class BotService:
    def __init__(
        self,
        container: Container,
        *,
        interval_seconds: float = 30.0,
        initial_delay_seconds: float = 5.0,
    ) -> None:
        self._container = container
        self._repo = BotRepository(container.session_factory)  # type: ignore[attr-defined]
        self._interval_seconds = max(5.0, float(interval_seconds))
        self._initial_delay_seconds = max(0.0, float(initial_delay_seconds))
        self._task: asyncio.Task[None] | None = None

    # --- 查询 ---

    async def list(self, *, status: str | None = None, limit: int = 100) -> list[Bot]:
        return await self._repo.list(status=status, limit=limit)

    async def get(self, bot_id: uuid.UUID) -> Bot:
        return await self._repo.get(bot_id)

    # --- 配置 ---

    async def create(
        self,
        *,
        name: str,
        description: str = "",
        avatar_shape: str | None = None,
        avatar_color: str | None = None,
        agent_id: str | None = None,
        model: str | None = None,
        key_id: str | None = None,
        permission_mode: PermissionMode = PermissionMode.UNATTENDED,
        decision_mode: DecisionMode = DecisionMode.AUTO,
        workspace: str | None = None,
        schedule: str | None = None,
    ) -> Bot:
        cleaned = (name or "").strip()
        if not cleaned:
            raise BadRequestError("Bot 名称不能为空")
        agent_uuid = self._agent_uuid(agent_id)
        resolved_model, key_uuid = await self._resolve_model(agent_uuid, model=model, key_id=key_id)
        cron = self._cron(schedule)
        now = _utcnow()
        return await self._repo.create(
            name=cleaned,
            description=description,
            avatar_shape=normalize_shape(avatar_shape),
            avatar_color=normalize_color(avatar_color),
            agent_id=agent_uuid,
            model=resolved_model,
            api_key_id=key_uuid,
            permission_mode=str(permission_mode),
            decision_mode=str(decision_mode),
            workspace=self._clean_workspace(workspace),
            status=BotStatus.ACTIVE.value,
            schedule=cron.expression if cron else None,
            next_run_at=cron.next_after(now) if cron else None,
        )

    async def update(self, bot_id: uuid.UUID, changes: dict[str, Any]) -> Bot:
        """部分更新：`changes` 只含调用方显式给出的字段（API 层用 exclude_unset）。"""
        bot = await self._repo.get(bot_id)
        values: dict[str, Any] = {}

        if "name" in changes:
            name = (changes["name"] or "").strip()
            if not name:
                raise BadRequestError("Bot 名称不能为空")
            values["name"] = name[:64]
        if "description" in changes:
            values["description"] = (changes["description"] or "").strip()

        if "avatar_shape" in changes:
            values["avatar_shape"] = normalize_shape(changes["avatar_shape"])
        if "avatar_color" in changes:
            values["avatar_color"] = normalize_color(changes["avatar_color"])

        agent_changed = "agent_id" in changes
        if agent_changed:
            agent_uuid = self._agent_uuid(changes["agent_id"])
            values["agent_id"] = agent_uuid
            # 与任务切换 Agent 同一口径：旧 Agent 的模型/密钥不一定在新 Agent 上可用
            values["model"] = None
            values["api_key_id"] = None
        elif "model" in changes or "key_id" in changes:
            model, key_uuid = await self._resolve_model(
                bot.agent_id,
                model=changes.get("model", bot.model),
                key_id=changes.get("key_id", str(bot.api_key_id) if bot.api_key_id else None),
            )
            values["model"] = model
            values["api_key_id"] = key_uuid

        if "permission_mode" in changes:
            raw = changes["permission_mode"]
            values["permission_mode"] = str(raw or PermissionMode.UNATTENDED)
        if "decision_mode" in changes:
            raw = changes["decision_mode"]
            values["decision_mode"] = str(raw or DecisionMode.AUTO)
        if "workspace" in changes:
            values["workspace"] = self._clean_workspace(changes["workspace"])

        schedule_changed = "schedule" in changes
        cron = self._cron(bot.schedule)
        if schedule_changed:
            cron = self._cron(changes["schedule"])
            values["schedule"] = cron.expression if cron else None

        status_changed = changes.get("status") is not None
        status = BotStatus(changes["status"]) if status_changed else BotStatus(bot.status)
        if status_changed:
            values["status"] = status.value
        if status == BotStatus.PAUSED:
            values["next_run_at"] = None
        elif schedule_changed or status_changed:
            values["next_run_at"] = cron.next_after(_utcnow()) if cron else None

        if not values:
            return bot
        return await self._repo.update(bot_id, values)

    async def delete(self, bot_id: uuid.UUID) -> None:
        await self._repo.delete(bot_id)

    # --- 开工 ---

    async def run_now(self, bot_id: uuid.UUID, *, instruction: str | None = None) -> Task:
        """手动触发一次开工（与定时开工共用同一条链路）。"""
        bot = await self._repo.get(bot_id)
        if await self._repo.is_busy(bot.id):
            raise ConflictError(
                f"Bot {bot.name} 上一轮还没跑完，等它结束再触发",
                details={"bot_id": str(bot.id)},
            )
        return await self._launch(bot, instruction=instruction, now=_utcnow())

    async def tick(self, now: datetime | None = None) -> list[str]:
        """扫一轮到期的 Bot，返回本次开工的任务 id。异常不外抛（后台循环不该死）。"""
        moment = _as_utc(now) or _utcnow()
        due = await self._repo.list_due(moment)
        started: list[str] = []
        for bot in due:
            try:
                if await self._repo.is_busy(bot.id):
                    # 上一轮还在跑：跳过本轮，顺延到下一个触发点
                    cron = self._cron(bot.schedule)
                    await self._repo.update(
                        bot.id,
                        {"next_run_at": cron.next_after(moment) if cron else None},
                    )
                    logger.info("Bot %s 上一轮未结束，跳过本次定时", bot.id)
                    continue
                task = await self._launch(bot, instruction=None, now=moment)
                started.append(str(task.id))
            except Exception as exc:  # noqa: BLE001 - 单个 Bot 失败不影响其他 Bot
                logger.warning("Bot %s 定时开工失败：%s", bot.id, exc)
        return started

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._task = asyncio.create_task(self._loop(), name="flux-bot-worker")

    async def stop(self) -> None:
        task, self._task = self._task, None
        if task is None:
            return
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    # --- 内部 ---

    def workspace_path(self, bot: Bot) -> Path:
        """Bot 的执行目录：配置的绝对路径，或 `<dsh_home>/bots/<id>/workspace`。"""
        if bot.workspace:
            return Path(bot.workspace).expanduser()
        dsh_home = Path(self._container.settings.dsh_home).expanduser()
        return dsh_home / "bots" / str(bot.id) / "workspace"

    async def _launch(self, bot: Bot, *, instruction: str | None, now: datetime) -> Task:
        prompt = (instruction or "").strip() or (bot.description or "").strip()
        if not prompt:
            raise BadRequestError(
                f"Bot {bot.name} 还没有作业内容：先写 description，或手动触发时带上 instruction",
                details={"bot_id": str(bot.id)},
            )
        cron = self._cron(bot.schedule)
        next_run_at = cron.next_after(now) if cron else None
        workspace = str(self.workspace_path(bot))
        container = self._container
        task = await container.task_repo.create(
            task_id=uuid.uuid4(),
            description=prompt,
            priority=_DEFAULT_PRIORITY,
            project_id=None,
            agent_id=bot.agent_id,
            decision_mode=DecisionMode(bot.decision_mode or DecisionMode.AUTO),
            permission_mode=PermissionMode(bot.permission_mode or PermissionMode.UNATTENDED),
            model=bot.model,
            api_key_id=bot.api_key_id,
            bot_name=bot.name,
            avatar_shape=bot.avatar_shape,
            avatar_color=bot.avatar_color,
            bot_id=bot.id,
            workspace=workspace,
        )
        # 先推进 next_run_at 再起 Run：起 Run 期间调度器再扫到也不会重复开工
        await self._repo.mark_run(bot.id, task_id=task.id, started_at=now, next_run_at=next_run_at)
        try:
            await container.task_run_service.start(task)
        except Exception:
            # 起不来就把任务收成 failed：任务停在 pending 会让 Bot 永远「忙」
            await container.task_repo.set_status(task.id, TaskStatus.FAILED)
            logger.warning("Bot %s 第 %s 轮起 Run 失败", bot.id, task.id, exc_info=True)
            raise
        return task

    async def _loop(self) -> None:
        if self._initial_delay_seconds:
            await asyncio.sleep(self._initial_delay_seconds)
        while True:
            try:
                await self.tick()
            except Exception:  # noqa: BLE001 - 循环本身不因单轮失败退出
                logger.warning("Bot 调度循环本轮失败", exc_info=True)
            await asyncio.sleep(self._interval_seconds)

    def _cron(self, expression: str | None) -> CronSchedule | None:
        """解析 cron；空表示只手动触发。非法表达式在写入口就按 400 拒绝。"""
        text = (expression or "").strip()
        if not text:
            return None
        try:
            return parse_cron(text)
        except ValueError as exc:
            raise BadRequestError(
                f"schedule 不是合法的 cron：{exc}", details={"schedule": text}
            ) from exc

    def _clean_workspace(self, workspace: str | None) -> str | None:
        text = (workspace or "").strip()
        if not text:
            return None
        path = Path(text).expanduser()
        if not path.is_absolute():
            raise BadRequestError("workspace 必须是绝对路径", details={"workspace": text})
        return str(path)

    def _agent_uuid(self, agent_id: str | uuid.UUID | None) -> uuid.UUID | None:
        """Agent 注册表是存在性的唯一权威：非法标识与不存在的 Agent 都是 404。"""
        if not agent_id:
            return None
        return self._container.agents.get(agent_id).id

    async def _resolve_model(
        self,
        agent_id: uuid.UUID | None,
        *,
        model: str | None,
        key_id: str | None,
    ) -> tuple[str | None, uuid.UUID | None]:
        """与建任务同一套模型/密钥校验；Agent 决定 runtime 原生模型目录。"""
        runtime = DEFAULT_RUNTIME
        if agent_id is not None:
            with contextlib.suppress(Exception):
                agent = self._container.agents.get(agent_id)
                runtime = str(getattr(getattr(agent, "spec", None), "runtime", DEFAULT_RUNTIME))
        return await self._container.key_vault.resolve_selection(
            runtime=runtime, model=model or "", key_id=key_id or None
        )
