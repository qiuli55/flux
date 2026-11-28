"""「连接器」里的飞书 / 个人微信能力汇总。

容器只装配这一个对象：它负责两条连接的生命周期、连接器列表投影、以及任务终态
通知。飞书与微信各自的协议细节留在 `feishu.py` / `wechat_ilink.py`。
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from typing import Any

from flux.config import Settings
from flux.core.event.bus import EventBus, Events
from flux.core.im.assistant import PLATFORM_FEISHU, PLATFORM_WECHAT, ImAssistant
from flux.core.im.feishu import FeishuClient, FeishuImService, FeishuLongConnection
from flux.core.im.store import ImConnectorStore
from flux.core.im.wechat_ilink import (
    WechatIlinkClient,
    WechatImService,
    WechatLoginManager,
    WechatPoller,
)
from flux.core.knowledge.retriever import KnowledgeRetriever
from flux.core.model_gateway.router import ModelRouter
from flux.core.supervisor.wechat_repository import WechatSessionRepository
from flux.core.task_engine.repository import TaskRepository
from flux.enums import TaskStatus
from flux.logging import get_logger

logger = get_logger(__name__)

__all__ = ["ImConnectorService"]

NOTICE_COMPLETED = "任务已完成：{description}。回到 Flux 可以查看结果。"
NOTICE_FAILED = "任务执行失败：{description}。回到 Flux 可以查看失败原因。"
_DESCRIPTION_CHARS = 80


class ImConnectorService:
    def __init__(
        self,
        settings: Settings,
        *,
        router: ModelRouter,
        tasks: TaskRepository,
        sessions: WechatSessionRepository,
        knowledge: KnowledgeRetriever | None = None,
        store: ImConnectorStore | None = None,
        feishu_client: FeishuClient | None = None,
        feishu_connection: FeishuLongConnection | None = None,
        wechat_client: WechatIlinkClient | None = None,
        wechat_poller: WechatPoller | None = None,
        wechat_login_manager: WechatLoginManager | None = None,
        wechat_auto_poll: bool = True,
    ) -> None:
        self.settings = settings
        self.tasks = tasks
        self.store = store or ImConnectorStore(settings.im_connectors_path)
        #: 终态通知协程的强引用；发送失败/超时都不能拖慢任务收尾。
        self._notice_jobs: set[asyncio.Task[Any]] = set()
        history_limit = settings.supervisor_wechat_history_limit
        knowledge_limit = settings.supervisor_wechat_knowledge_limit
        self.feishu = FeishuImService(
            app_id=settings.feishu_im_app_id,
            app_secret=settings.feishu_im_app_secret,
            api_base=settings.feishu_im_api_base,
            enabled=settings.feishu_im_enabled,
            assistant=ImAssistant(
                router,
                tasks,
                sessions,
                platform=PLATFORM_FEISHU,
                source_key="feishu-im",
                knowledge=knowledge,
                history_limit=history_limit,
                knowledge_limit=knowledge_limit,
            ),
            store=self.store,
            client=feishu_client,
            connection=feishu_connection,
        )
        self.wechat = WechatImService(
            enabled=settings.wechat_im_enabled,
            api_base=settings.wechat_im_api_base,
            assistant=ImAssistant(
                router,
                tasks,
                sessions,
                platform=PLATFORM_WECHAT,
                source_key="wechat-im",
                knowledge=knowledge,
                history_limit=history_limit,
                knowledge_limit=knowledge_limit,
            ),
            store=self.store,
            client=wechat_client
            or WechatIlinkClient(
                api_base=settings.wechat_im_api_base,
                channel_version=settings.wechat_im_channel_version,
                bot_agent=settings.wechat_im_bot_agent,
                bot_type=settings.wechat_im_bot_type,
                poll_timeout_seconds=settings.wechat_im_poll_timeout_seconds,
                qr_poll_timeout_seconds=settings.wechat_im_qr_poll_timeout_seconds,
            ),
            qr_ttl_seconds=settings.wechat_im_qr_ttl_seconds,
            auto_poll=wechat_auto_poll,
            poller=wechat_poller,
            login_manager=wechat_login_manager,
        )

    # --- 生命周期 ---

    async def start(self) -> None:
        """lifespan 拉起常驻能力；未配置/未绑定时是空操作。"""
        await self.feishu.start()
        await self.wechat.start()

    async def stop(self) -> None:
        await self.feishu.stop()
        await self.wechat.stop()

    async def aclose(self) -> None:
        jobs = list(self._notice_jobs)
        self._notice_jobs.clear()
        for job in jobs:
            job.cancel()
        for job in jobs:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await job
        await self.feishu.aclose()
        await self.wechat.aclose()

    def attach(self, bus: EventBus) -> None:
        """任务终态通知：只推送 completed / failed，不接 Supervisor 盯梢。"""
        bus.subscribe(Events.TASK_COMPLETED, self._on_task_terminal)
        bus.subscribe(Events.TASK_FAILED, self._on_task_terminal)

    async def _on_task_terminal(self, _event: str, payload: dict[str, Any]) -> None:
        raw = payload.get("task_id")
        if not isinstance(raw, str) or not raw:
            return
        try:
            task_id = uuid.UUID(raw)
        except ValueError:
            return
        status = str(payload.get("status") or "")
        job = asyncio.create_task(self.notify_task_terminal(task_id, status))
        self._notice_jobs.add(job)
        job.add_done_callback(self._notice_jobs.discard)

    # --- API 门面 ---

    def status(self) -> dict[str, Any]:
        return {"feishu": self.feishu.status(), "wechat": self.wechat.status()}

    async def connect_feishu(self) -> dict[str, Any]:
        return await self.feishu.connect()

    async def disconnect_feishu(self) -> dict[str, Any]:
        return await self.feishu.disconnect()

    async def connect_wechat(self) -> dict[str, Any]:
        return await self.wechat.connect()

    def wechat_login_status(self, login_id: str) -> dict[str, Any]:
        return self.wechat.login_status(login_id)

    def submit_wechat_code(self, login_id: str, code: str) -> dict[str, Any]:
        return self.wechat.submit_code(login_id, code)

    async def disconnect_wechat(self) -> dict[str, Any]:
        return await self.wechat.disconnect()

    def connector_items(self) -> list[dict[str, Any]]:
        """连接器列表投影（与 `mcp-connectors` 的条目字段保持一致）。"""
        feishu = self.feishu.status()
        wechat = self.wechat.status()
        return [
            {
                "id": "feishu",
                "name": "飞书",
                "kind": "builtin",
                "description": "在飞书单聊里和 Flux 主助理对话，并接收任务通知",
                "available": True,
                "status": _feishu_connector_status(feishu),
                "account": "飞书单聊已绑定" if feishu.get("bound") else None,
                "error": feishu.get("error"),
                "featured": False,
                "action": "feishu",
            },
            {
                "id": "wechat",
                "name": "个人微信",
                "kind": "builtin",
                "description": "通过官方微信 ClawBot 在微信里和 Flux 主助理单聊",
                "available": True,
                "status": _wechat_connector_status(wechat),
                "account": "微信 ClawBot 已绑定" if wechat.get("connected") else None,
                "error": wechat.get("error"),
                "featured": False,
                "action": "wechat",
            },
        ]

    # --- 通知 ---

    async def notify_task_terminal(self, task_id: uuid.UUID, status: str) -> None:
        """任务终态推送：App 内通知仍然独立存在，这里只是额外的 IM 触达。"""
        if status not in {TaskStatus.COMPLETED.value, TaskStatus.FAILED.value}:
            return
        # 没有连接任何 IM 时不做无谓的查库 / 网络请求。
        if not self.store.feishu().get("chat_id") and self.store.wechat_credentials() is None:
            return
        try:
            task = await self.tasks.get(task_id)
        except Exception:  # noqa: BLE001 - 任务已删或库不可用：没有可通知的事实
            return
        description = str(task.description or "").strip().replace("\n", " ")
        if len(description) > _DESCRIPTION_CHARS:
            description = description[:_DESCRIPTION_CHARS] + "…"
        if not description:
            description = "未命名任务"
        template = NOTICE_COMPLETED if status == TaskStatus.COMPLETED.value else NOTICE_FAILED
        text = template.format(description=description)
        results = await self.push_notice(text)
        if not any(results.values()):
            logger.info("任务终态 IM 通知未送达 task=%s status=%s", task_id, status)

    async def push_notice(self, text: str) -> dict[str, bool]:
        """向两条已连接通道推送纯文本；单条失败不影响另一条。"""
        result = {"feishu": False, "wechat": False}
        try:
            result["feishu"] = await self.feishu.notify(text)
        except Exception as exc:  # noqa: BLE001
            logger.warning("飞书 IM 通知失败：%s", type(exc).__name__)
        try:
            result["wechat"] = await self.wechat.notify(text)
        except Exception as exc:  # noqa: BLE001
            logger.warning("微信 IM 通知失败：%s", type(exc).__name__)
        return result


def _feishu_connector_status(status: dict[str, Any]) -> str:
    if status.get("connected"):
        return "connected"
    if status.get("error"):
        return "error"
    return "disconnected"


def _wechat_connector_status(status: dict[str, Any]) -> str:
    if status.get("connected"):
        return "connected"
    if status.get("needs_reauth"):
        return "needs_reauth"
    if status.get("error"):
        return "error"
    return "disconnected"
