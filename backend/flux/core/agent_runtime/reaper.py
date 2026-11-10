"""外部 Agent 自动注销（30 天未通过 MCP 调用 Flux）。

外部接入的 Agent 只应保留"还在用"的档案与令牌：超过 `EXTERNAL_AGENT_IDLE_DAYS`
没有任何一次 MCP 调用，就删除它的全部令牌、档案行与内存注册表条目。本地 Agent
由用户显式管理，永远不在清理范围内。

最近一次使用时间取该 Agent 未撤销令牌的 `last_used_at` 最大值；从未用过时退回到
档案的 `created_at`（等于"接入后一直没用"，同样应该过期）。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

from flux.core.agent_runtime.manager import AGENT_KIND_EXTERNAL, AgentManager
from flux.core.agent_runtime.repository import AgentRepository
from flux.core.mcp.auth import AgentTokenService
from flux.logging import get_logger

logger = get_logger(__name__)

#: 外部 Agent 连续未通过 MCP 调用 Flux 多少天后自动注销
EXTERNAL_AGENT_IDLE_DAYS = 30
#: 清理周期（启动先跑一次，之后每 6 小时对账一次）
REAP_INTERVAL_SECONDS = 6 * 60 * 60


def _as_utc(value: datetime) -> datetime:
    """SQLite 读回的 DateTime 可能不带 tzinfo；统一按 UTC 比较。"""
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


class ExternalAgentReaper:
    """周期性注销长期未使用的外部 Agent。"""

    def __init__(
        self,
        *,
        agents: AgentManager,
        repository: AgentRepository,
        tokens: AgentTokenService,
        idle_days: int = EXTERNAL_AGENT_IDLE_DAYS,
        interval_seconds: int = REAP_INTERVAL_SECONDS,
    ) -> None:
        self._agents = agents
        self._repository = repository
        self._tokens = tokens
        self._idle_days = idle_days
        self._interval_seconds = interval_seconds
        self._stopped = asyncio.Event()

    async def reap_once(self, now: datetime | None = None) -> list[str]:
        """扫描一次并注销过期外部 Agent，返回被注销的 agent_id 列表。"""
        current = now or datetime.now(timezone.utc)
        removed: list[str] = []
        for row in await self._repository.list():
            config = getattr(row, "config", None) or {}
            if str(config.get("kind") or "") != AGENT_KIND_EXTERNAL:
                continue
            token_rows = await self._tokens.list(agent_id=str(row.id))
            stamps = [
                _as_utc(token.last_used_at)
                for token in token_rows
                if token.revoked_at is None and token.last_used_at is not None
            ]
            if stamps:
                last_used = max(stamps)
            elif row.created_at is not None:
                last_used = _as_utc(row.created_at)
            else:
                continue
            if current - last_used < timedelta(days=self._idle_days):
                continue
            deleted_tokens = await self._tokens.delete_for_agent(str(row.id))
            await self._repository.delete(row.id)
            self._agents.remove(row.id)
            removed.append(str(row.id))
            logger.info(
                "external_agent.reaped agent=%s name=%s tokens=%d idle_days=%d",
                row.id,
                row.name,
                deleted_tokens,
                self._idle_days,
            )
        return removed

    async def run_forever(self) -> None:
        """启动先清理一次，之后按周期对账；单轮失败只记日志，不终止循环。"""
        while not self._stopped.is_set():
            try:
                await self.reap_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - 清理失败不能拖垮主进程
                logger.exception("external_agent.reap_failed")
            try:
                await asyncio.wait_for(self._stopped.wait(), timeout=self._interval_seconds)
            except asyncio.TimeoutError:
                continue

    def stop(self) -> None:
        self._stopped.set()


__all__ = [
    "EXTERNAL_AGENT_IDLE_DAYS",
    "ExternalAgentReaper",
    "REAP_INTERVAL_SECONDS",
]
