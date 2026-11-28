"""HTTP / 任务流投影的统一打码入口。

任务对象、任务消息与任务流事件都是"给人看"的投影：`to_dict()` 里的正文、嵌套的
确认卡 / 决策记录 / 计划步骤仍可能是用户原文。密钥与配置字面量不该随投影离开进程，
但数据库里的原文必须保持不动（内部查询、重试与审计仍以库为准）。

API 层（`flux.api.v1.tasks`）与 core 层（审批、计划卡、Agent 切换、DSH 收尾）都走这里，
避免各处各写一份深拷贝逻辑，也避免 core 反向 import API 层造成循环依赖。

`source` 可以是 `Container`（取其 `settings`）、`Settings` 本身，或直接是字面量序列
（例如 `TaskRunBridge` 只持有 `redaction_literal_list` 的元组）。
"""

from __future__ import annotations

from typing import Any

from flux.core.context.redact import redact


def projection_literals(source: Any) -> tuple[str, ...]:
    """从 container / Settings / 字面量序列中取出精确字面量清单。"""
    if source is None:
        return ()
    settings = getattr(source, "settings", source)
    literals = getattr(settings, "redaction_literal_list", None)
    if literals is None and isinstance(settings, (list, tuple, set, frozenset)):
        literals = settings
    return tuple(literals or ())


def redact_text(source: Any, text: str) -> str:
    """按来源对应的字面量清单给一段准备下发的文本打码。"""
    return redact(text, literals=projection_literals(source)).text


def redact_projection(source: Any, value: Any) -> Any:
    """对投影做深拷贝式打码，不改动 ORM / 已落库对象。"""
    if isinstance(value, str):
        return redact_text(source, value)
    if isinstance(value, list):
        return [redact_projection(source, item) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_projection(source, item) for item in value)
    if isinstance(value, dict):
        return {key: redact_projection(source, item) for key, item in value.items()}
    return value


def redacted_message(source: Any, message: Any) -> dict[str, Any]:
    """消息投影（副本）：推流 / HTTP 响应统一走这里，落库对象保持原文。"""
    return redact_projection(source, message.to_dict())


def task_projection(source: Any, task: Any) -> dict[str, Any]:
    """任务 HTTP / 事件投影（副本）：正文、确认卡、决策记录等一并打码。"""
    return redact_projection(source, task.to_dict())
