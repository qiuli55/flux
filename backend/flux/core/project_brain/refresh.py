"""Apply 成功后自动刷新项目 Brain（上下文闭环）。

Brain 的 Overview / Tech Stack 是扫描快照；提案落盘后工作区已经变了，快照不刷新
就会让下一轮 `context.get` 给出过期事实。这里订阅 `workspace.changed`：只要有提案
进入 applied，就按项目触发一次重新扫描。

三条边界：

- 只认 `to == applied`，其余状态（proposed / accepted / failed …）不触发；
- 同一项目节流（默认 300 秒）：批量 Apply 会连续发多条事件，不能放大成 N 次扫描；
- 任何失败只记日志，绝不影响 Apply 主流程——Brain 是辅助记忆，不是写入路径的门。

没有项目归属的提案直接跳过：Brain 按项目存，猜一个项目比不刷新更糟。
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Callable
from typing import Any

from flux.core.event.bus import EventBus, Events
from flux.enums import VirtualChangeStatus
from flux.logging import get_logger

logger = get_logger(__name__)

#: 同一项目两次自动扫描的最小间隔（秒）：批量 Apply 不应触发 N 次全量扫描
DEFAULT_REFRESH_INTERVAL_SECONDS = 300.0


class BrainRefresher:
    """`workspace.changed(applied)` → 项目 Brain 重新扫描（best-effort）。"""

    def __init__(
        self,
        *,
        brain: Any,
        tasks: Any,
        workspace: Any,
        bus: EventBus,
        interval_seconds: float = DEFAULT_REFRESH_INTERVAL_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._brain = brain
        self._tasks = tasks
        self._workspace = workspace
        self._bus = bus
        self._interval = max(interval_seconds, 0.0)
        self._clock = clock
        self._last_scan: dict[str, float] = {}

    def attach(self) -> None:
        self._bus.subscribe(Events.WORKSPACE_CHANGED, self.on_change)

    async def on_change(self, event: str, payload: dict[str, Any]) -> None:
        """事件入口：只处理"已落盘"，其余原样放过；异常一律吞在日志里。"""
        if payload.get("to") != VirtualChangeStatus.APPLIED.value:
            return
        change_id = payload.get("change_id")
        if not isinstance(change_id, str) or not change_id:
            return
        try:
            project_id = await self._project_of(change_id)
            if project_id is None:
                logger.debug("Brain 自动刷新跳过：提案无项目归属 change=%s", change_id)
                return
            await self.refresh(project_id, change_id=change_id)
        except Exception:  # noqa: BLE001 - Brain 刷新失败不能阻塞 Apply 主流程
            logger.warning("Brain 自动刷新失败 change=%s", change_id, exc_info=True)

    async def refresh(self, project_id: uuid.UUID | str, *, change_id: str) -> bool:
        """按项目节流后重新扫描；返回本次是否真的扫了。

        节流标记在扫描前占位，避免并发事件同时起跑；扫描失败时撤回占位，
        让下一次 Apply 仍有机会重试——失败不该被当成"刚扫过"。
        """
        key = str(project_id)
        now = self._clock()
        last = self._last_scan.get(key)
        if last is not None and now - last < self._interval:
            logger.debug("Brain 自动刷新节流 project=%s change=%s", project_id, change_id)
            return False
        self._last_scan[key] = now
        try:
            outcome = await self._brain.scan(project_id, record=True)
        except Exception:
            self._last_scan.pop(key, None)
            raise
        logger.info(
            "项目 Brain 已随 Apply 自动刷新 project=%s change=%s files=%s",
            project_id,
            change_id,
            outcome.profile.files_scanned,
        )
        return True

    async def _project_of(self, change_id: str) -> uuid.UUID | str | None:
        """提案自身没项目时，沿 task_id 找任务的项目——两者都没有就不刷新。"""
        change = await self._workspace.get(change_id)
        if change.project_id is not None:
            return change.project_id
        if change.task_id is None:
            return None
        task = await self._tasks.get(change.task_id)
        return task.project_id if task is not None else None


__all__ = ["BrainRefresher", "DEFAULT_REFRESH_INTERVAL_SECONDS"]
