"""事件总线（主规格 §5.4 事件命名格式 resource.action）。

M0 实现为进程内发布/订阅，接口与生产形态一致；按 §15.7 换 Redis pub/sub 时只替换本模块实现。
"""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from typing import Any

from flux.logging import get_logger

logger = get_logger(__name__)

EventHandler = Callable[[str, dict[str, Any]], "Awaitable[None] | None"]

WILDCARD = "*"


class Events:
    """事件名常量（主规格 §5.4 / §17.9 事件命名规范）。"""

    AGENT_STARTED = "agent.started"
    AGENT_COMPLETED = "agent.completed"
    AGENT_STATE_CHANGED = "agent.state_changed"
    TASK_CREATED = "task.created"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    CONNECTOR_EXECUTED = "connector.executed"
    WORKSPACE_CHANGED = "workspace.changed"
    GIT_COMMITTED = "git.committed"
    USAGE_RECORDED = "usage.recorded"


class EventBus:
    def __init__(self) -> None:
        self._handlers: dict[str, list[EventHandler]] = {}
        self._history: list[tuple[str, dict[str, Any]]] = []

    def subscribe(self, event: str, handler: EventHandler) -> None:
        self._handlers.setdefault(event, []).append(handler)

    def unsubscribe(self, event: str, handler: EventHandler) -> None:
        if handler in self._handlers.get(event, []):
            self._handlers[event].remove(handler)

    async def publish(self, event: str, payload: dict[str, Any] | None = None) -> int:
        """发布事件，返回被调用的处理器数量。处理器异常只记录，不中断其他订阅者。"""
        body = dict(payload or {})
        self._history.append((event, body))
        handlers = [*self._handlers.get(event, []), *self._handlers.get(WILDCARD, [])]
        for handler in handlers:
            try:
                outcome = handler(event, body)
                if inspect.isawaitable(outcome):
                    await outcome
            except Exception:  # noqa: BLE001 - 单个订阅者失败不应影响主流程
                logger.exception("事件处理器执行失败 event=%s", event)
        return len(handlers)

    @property
    def history(self) -> list[tuple[str, dict[str, Any]]]:
        return list(self._history)

    def clear(self) -> None:
        self._history.clear()
