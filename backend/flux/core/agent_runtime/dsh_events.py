"""DSH 通知 → Flux 事件映射（集成方案 §18 Phase 1 第 6 步）。

Flux 前端不消费 DSH 原始协议，故在此把一条 DSH Notification 压扁成 Flux 事件体：
保留 `method` 与 `session_id` 作为关联键，把 `session.event` 里真正有业务含义的
`event_type` / `text` / `finish_reason` 提到顶层，前端订阅 `dsh.event` 即可消费。
"""

from __future__ import annotations

from typing import Any

from deepseek_harness import Notification

from flux.core.event.bus import Events


def to_flux_event(notification: Notification) -> tuple[str, dict[str, Any]]:
    """把一条 DSH 通知压成 Flux 事件体，返回 (事件名, 事件体)。

    无法识别的通知同样返回一条（`event_type` 为 None），绝不吞掉上游消息。
    """
    payload = notification.payload
    body: dict[str, Any] = {"method": notification.method, "event_type": None}
    session_id = payload.get("sessionId")
    if isinstance(session_id, str):
        body["session_id"] = session_id

    if notification.method == "session.event":
        event = payload.get("event")
        if isinstance(event, dict):
            event_type = event.get("type")
            if isinstance(event_type, str):
                body["event_type"] = event_type
            data = event.get("data")
            if event_type == "assistant/message":
                body["text"] = _assistant_text(data)
            elif event_type == "turn/end":
                body["finish_reason"] = _finish_reason(data)

    return Events.DSH_EVENT, body


def _assistant_text(data: Any) -> str:
    """按 SDK 同款规则取助手文本：data.message.content 或 data.content 里 type=text 的块。"""
    if not isinstance(data, dict):
        return ""
    message = data.get("message")
    content_owner = message if isinstance(message, dict) else data
    content = content_owner.get("content")
    if not isinstance(content, list):
        return ""
    parts = [
        str(block.get("text") or "")
        for block in content
        if isinstance(block, dict) and block.get("type") == "text"
    ]
    return "".join(parts)


def _finish_reason(data: Any) -> str | None:
    """取 turn/end 的结束原因：data.reason.kind。"""
    reason = data.get("reason") if isinstance(data, dict) else None
    kind = reason.get("kind") if isinstance(reason, dict) else None
    return kind if isinstance(kind, str) else None


__all__ = ["to_flux_event"]
