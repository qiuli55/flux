"""对话上下文占用度量（手机端 / 桌面端「上下文快满」提醒的同一份口径）。

这里度量的不是模型标称窗口，而是**平台助手每一轮真正能带上的那段上下文**：
`TaskAssistant` 只带最近 `history_message_limit()` 条消息（条数由 §6.1 的字符预算
折算而来），更早的消息不会进模型。所以「上下文快满」的真实含义是：
再聊几轮，最早的消息就要被挤出模型视野。

两个信号取较大者，任一先到阈值都算快满：

- 条数：已用条数 / 可带条数；
- 体积：最近这段消息的字符数 / 分给对话历史的字符预算。

两个分母都由 `flux.core.context.budget` 推导，不在这里另写常量，
换模型或调预算时只改那一处。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from flux.core.context.budget import (
    DEFAULT_BUDGET_CHARS,
    ESTIMATED_MESSAGE_CHARS,
    HISTORY_BUDGET_SHARE,
    history_message_limit,
)

#: 进入模型上下文的消息类型：正文消息与上一段对话的前情摘要。
#: 助手历史、上下文度量、摘要注入必须共用这一份清单，否则三处口径会漂移。
CONTEXT_MESSAGE_KINDS: tuple[str, ...] = ("text", "summary")

#: 达到这个占用比例就提醒「上下文快满了，要不要新建对话」。
CONTEXT_WARNING_RATIO = 0.8

#: 占用等级：normal / warning / full。
LEVEL_NORMAL = "normal"
LEVEL_WARNING = "warning"
LEVEL_FULL = "full"


def context_budget_chars() -> int:
    """分给对话历史的字符预算（§6.1 的可投喂预算 × 历史占比）。"""
    return int(DEFAULT_BUDGET_CHARS * HISTORY_BUDGET_SHARE)


def context_message_limit() -> int:
    """一次能带进模型的消息条数上限（与 API 取历史的 limit 同源）。"""
    return history_message_limit()


@dataclass(frozen=True, slots=True)
class ContextUsage:
    """一段对话的上下文占用快照（API 响应体）。"""

    #: 一次能带上的消息条数
    limit: int
    #: 对话里可进上下文的消息总数
    message_count: int
    #: 实际进入下一轮的条数（超过 limit 时等于 limit）
    used_messages: int
    #: 实际进入下一轮的字符数
    used_chars: int
    #: 对话历史的字符预算
    budget_chars: int
    #: 原始占用比例（可能 > 1，客户端展示时再夹到 100%）
    ratio: float
    #: 展示用百分比（0~100）
    percent: int
    #: normal / warning / full
    level: str
    #: 每条消息的估算字符数（前端解释口径用）
    message_chars: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "limit": self.limit,
            "message_count": self.message_count,
            "used_messages": self.used_messages,
            "used_chars": self.used_chars,
            "budget_chars": self.budget_chars,
            "ratio": self.ratio,
            "percent": self.percent,
            "level": self.level,
            "message_chars": self.message_chars,
        }


def measure_context(
    messages: list[Any],
    *,
    message_count: int,
    limit: int | None = None,
) -> ContextUsage:
    """按最近 `limit` 条消息算占用。`messages` 为按 seq 升序的尾部消息。

    `message_count` 是任务内可进上下文的消息总数（由仓储统计，不受本次取数
    limit 影响）；两者在条数维度上不混用——否则「只取了 30 条」会被误判成
    「整段对话只有 30 条」。
    """
    effective_limit = max(1, limit if limit is not None else context_message_limit())
    window = messages[-effective_limit:]
    used_chars = sum(len((message.content or "").strip()) for message in window)
    budget_chars = max(1, context_budget_chars())

    used_messages = min(max(message_count, 0), effective_limit)
    message_ratio = used_messages / effective_limit
    char_ratio = used_chars / budget_chars
    raw_ratio = max(message_ratio, char_ratio)

    if message_ratio >= 1.0 or char_ratio >= 1.0:
        level = LEVEL_FULL
    elif raw_ratio >= CONTEXT_WARNING_RATIO:
        level = LEVEL_WARNING
    else:
        level = LEVEL_NORMAL

    return ContextUsage(
        limit=effective_limit,
        message_count=max(message_count, 0),
        used_messages=used_messages,
        used_chars=used_chars,
        budget_chars=budget_chars,
        ratio=round(raw_ratio, 3),
        percent=min(100, int(round(raw_ratio * 100))),
        level=level,
        message_chars=ESTIMATED_MESSAGE_CHARS,
    )


__all__ = [
    "CONTEXT_MESSAGE_KINDS",
    "CONTEXT_WARNING_RATIO",
    "LEVEL_FULL",
    "LEVEL_NORMAL",
    "LEVEL_WARNING",
    "ContextUsage",
    "context_budget_chars",
    "context_message_limit",
    "measure_context",
]
