"""Supervisor 服务层（P0：事件入库与观察回放）。

这里刻意不做判断、不派活：准入（P1）与规划（P2）各自成模块，服务层只负责
把外部事实收敛成结构化记录，并保证幂等。
"""

from __future__ import annotations

import uuid
from typing import Any

from flux.core.supervisor.outbox import SupervisorOutbox
from flux.core.supervisor.repository import SupervisorRepository
from flux.errors import ValidationError


class SupervisorService:
    def __init__(self, repo: SupervisorRepository, outbox: SupervisorOutbox) -> None:
        self._repo = repo
        self._outbox = outbox

    # --- 事件 ---

    async def ingest_event(
        self,
        *,
        source_key: str,
        external_id: str,
        event_type: str = "message",
        actor_id: str = "",
        actor_platform: str = "",
        thread_key: str = "",
        content: str = "",
        raw: dict | None = None,
        project_id: uuid.UUID | None = None,
    ) -> dict[str, Any]:
        """记录一条外部事件；重复投递返回 `created=False` 且不产生新动作。"""
        source_key = source_key.strip()
        external_id = external_id.strip()
        event_type = (event_type or "message").strip() or "message"
        if not source_key:
            raise ValidationError("source_key 不能为空", details={"param": "source_key"})
        if not external_id:
            raise ValidationError("external_id 不能为空", details={"param": "external_id"})
        trigger, created = await self._repo.ingest(
            source_key=source_key,
            external_id=external_id,
            event_type=event_type,
            actor_id=actor_id.strip(),
            actor_platform=actor_platform.strip(),
            thread_key=thread_key.strip(),
            content=content,
            raw=raw or {},
            project_id=project_id,
        )
        return {"trigger": trigger.to_dict(), "created": created}

    async def observations(
        self,
        *,
        limit: int = 50,
        status: str | None = None,
        project_id: uuid.UUID | None = None,
    ) -> dict[str, Any]:
        """观察模式回放：按时间倒序列出事件，并带上最近一次判断（若已判断）。"""
        triggers = await self._repo.list_triggers(limit=limit, status=status, project_id=project_id)
        evals = await self._repo.latest_evals([trigger.id for trigger in triggers])
        items = []
        for trigger in triggers:
            item = trigger.to_dict()
            evaluation = evals.get(trigger.id)
            item["eval"] = evaluation.to_dict() if evaluation is not None else None
            items.append(item)
        return {
            "items": items,
            "pending_outbox": await self._outbox.pending_count(),
        }

    # --- 设置 ---

    async def read_settings(self) -> dict[str, Any]:
        return (await self._repo.get_settings()).to_dict()

    async def update_settings(self, fields: dict[str, Any]) -> dict[str, Any]:
        """只更新显式给出的字段；None 表示"不改"，避免一次 PUT 清空配置。"""
        changes = {key: value for key, value in fields.items() if value is not None}
        if not changes:
            return await self.read_settings()
        return (await self._repo.update_settings(**changes)).to_dict()
