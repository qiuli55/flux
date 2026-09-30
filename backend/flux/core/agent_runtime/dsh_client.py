"""Flux × DSH Agent Runtime 客户端（集成方案 §10 / §18 Phase 1）。

本模块把官方 Python SDK（`deepseek-harness-sdk`）包一层，向 Flux 暴露
"起一次 Run / 收流式事件 / 中断 / 查状态" 四个动作。Phase 1 不引入 Node 侧产物，
也不落库：Run 记录只存进程内字典，M1 里程碑再持久化。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from deepseek_harness import DeepSeekHarness, Notification

from flux.config import Settings
from flux.core.agent_runtime.dsh_events import to_flux_event
from flux.core.event.bus import EventBus, Events
from flux.enums import DshRunStatus
from flux.errors import ConfigurationError, NotFoundError
from flux.logging import get_logger

logger = get_logger(__name__)

#: 终止态：进入后不再接受 interrupt
TERMINAL_STATUSES = frozenset({DshRunStatus.COMPLETED, DshRunStatus.FAILED, DshRunStatus.CANCELLED})

#: 终态 → 对外发布的事件名
_STATUS_EVENTS = {
    DshRunStatus.COMPLETED: Events.DSH_RUN_COMPLETED,
    DshRunStatus.FAILED: Events.DSH_RUN_FAILED,
    DshRunStatus.CANCELLED: Events.DSH_RUN_CANCELLED,
}


@dataclass
class DshRun:
    """一次 DSH Agent Run 的内存记录（Phase 1 不落库，M1 里程碑再持久化）。"""

    session_id: str
    instruction: str
    run_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    status: DshRunStatus = DshRunStatus.QUEUED
    final_response: str = ""
    finish_reason: str | None = None
    error: str | None = None
    events: list[dict[str, Any]] = field(default_factory=list)
    started_at: datetime | None = None
    finished_at: datetime | None = None
    duration_seconds: float | None = None
    cancel_requested: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "session_id": self.session_id,
            "instruction": self.instruction,
            "status": str(self.status),
            "final_response": self.final_response,
            "finish_reason": self.finish_reason,
            "error": self.error,
            "events": list(self.events),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "finished_at": self.finished_at.isoformat() if self.finished_at else None,
            "duration_seconds": self.duration_seconds,
            "cancel_requested": self.cancel_requested,
        }


class FluxDshClient:
    """DSH Agent Runtime 的薄客户端（集成方案 §10）。

    Run 记录存进程内字典（Phase 1 不落库，M1 里程碑再持久化）。
    `harness_factory` 默认是 SDK 的 `DeepSeekHarness`；测试注入替身即可绕开真实 runtime。
    """

    def __init__(
        self,
        settings: Settings,
        bus: EventBus | None = None,
        harness_factory: Callable[..., DeepSeekHarness] | None = None,
    ) -> None:
        self._settings = settings
        self._bus = bus
        self._harness_factory = harness_factory or DeepSeekHarness
        self._runs: dict[str, DshRun] = {}
        self._harnesses: dict[str, DeepSeekHarness] = {}

    # --- 就绪 ---

    def ensure_ready(self) -> None:
        """确认能力已启用，并确保 DSH_HOME 与工作区目录存在。"""
        if not self._settings.dsh_enabled:
            raise ConfigurationError("DSH Agent Runtime 未启用（FLUX_DSH_ENABLED=false）")
        for path in (self._settings.dsh_home, self._settings.dsh_workspace):
            Path(path).mkdir(parents=True, exist_ok=True)

    # --- 起 Run ---

    async def start_run(self, instruction: str, *, session_id: str | None = None) -> DshRun:
        """建一条 Run 记录、发布 started 事件，并把实际执行交给后台任务。"""
        self.ensure_ready()
        run_id = uuid.uuid4().hex
        run = DshRun(
            run_id=run_id,
            session_id=session_id or f"flux-{run_id}",
            instruction=instruction,
            status=DshRunStatus.RUNNING,
            started_at=datetime.now(timezone.utc),
        )
        self._runs[run.run_id] = run
        await self._publish(Events.DSH_RUN_STARTED, run)
        asyncio.create_task(self._execute(run))
        return run

    async def _execute(self, run: DshRun) -> None:
        """在线程里跑一次 DSH 会话，并把通知转成 Flux 事件。

        SDK 是同步阻塞的（§10.3），必须用 `asyncio.to_thread` 卸载；每次 Run **新建一个**
        harness，因为一个 SDK 实例独占一个 runtime 子进程且只能串行使用，不能共享单实例并发 run。
        """
        loop = asyncio.get_running_loop()

        def _on_notification(notification: Notification) -> None:
            # 回调由 SDK 读线程同步调用，不能直接 await；把发布动作交回事件循环线程。
            event, body = to_flux_event(notification)
            record = {**body, "run_id": run.run_id}
            run.events.append(record)
            if self._bus is not None:
                asyncio.run_coroutine_threadsafe(self._bus.publish(event, record), loop)

        status = DshRunStatus.COMPLETED
        harness: DeepSeekHarness | None = None
        try:
            harness = self._harness_factory(
                dsh_home=self._settings.dsh_home,
                cwd=self._settings.dsh_workspace,
                provider=self._settings.dsh_provider,
                model=self._settings.dsh_model,
                max_tokens=self._settings.dsh_max_tokens,
                initialize_timeout_seconds=self._settings.dsh_init_timeout_seconds,
                request_timeout_seconds=self._settings.dsh_run_timeout_seconds or None,
            )
            self._harnesses[run.run_id] = harness
            result = await asyncio.to_thread(
                harness.run,
                run.instruction,
                session_id=run.session_id,
                on_notification=_on_notification,
            )
        except Exception as exc:  # noqa: BLE001 - 上游任何异常都归到失败终态
            logger.exception("dsh.run 执行失败 run=%s", run.run_id)
            status = DshRunStatus.FAILED
            run.error = str(exc)
        else:
            run.final_response = result.final_response
            run.finish_reason = result.finish_reason
        finally:
            # 构造也可能失败（无合适 runtime 载体等），关闭同上：单点失败不得吞掉终态写入。
            if harness is not None:
                try:
                    harness.close()
                except Exception:  # noqa: BLE001 - 关闭失败不能改变本次 Run 的结果
                    logger.exception("dsh.harness 关闭失败 run=%s", run.run_id)
            self._harnesses.pop(run.run_id, None)

        # 中断优先于完成/失败：只要请求过 cancel，终态一律置 CANCELLED。
        if run.cancel_requested:
            status = DshRunStatus.CANCELLED
        self._finish(run, status)
        await self._publish(_STATUS_EVENTS[status], run)

    def interrupt(self, run_id: str) -> DshRun:
        """请求中断一次 Run：给活跃 harness 发 `session/cancel`，等 _execute 收尾成 CANCELLED。"""
        run = self.get_run(run_id)
        if run.status in TERMINAL_STATUSES:
            return run
        harness = self._harnesses.get(run_id)
        if harness is not None:
            harness.client.notify("session/cancel", {"sessionId": run.session_id})
        run.cancel_requested = True
        return run

    # --- 查询 ---

    def get_run(self, run_id: str) -> DshRun:
        run = self._runs.get(run_id)
        if run is None:
            raise NotFoundError(f"DSH Run {run_id} 不存在", details={"run_id": run_id})
        return run

    def list_runs(self) -> list[DshRun]:
        return list(self._runs.values())

    def status(self) -> dict[str, Any]:
        """对外暴露的 DSH 配置快照（不含任何密钥）。"""
        return {
            "enabled": self._settings.dsh_enabled,
            "home": self._settings.dsh_home,
            "workspace": self._settings.dsh_workspace,
            "provider": self._settings.dsh_provider,
            "model": self._settings.dsh_model,
        }

    def close(self) -> None:
        """尽力关闭所有活跃 harness；单个失败只记日志，不影响其它实例。"""
        for run_id, harness in list(self._harnesses.items()):
            try:
                harness.close()
            except Exception:  # noqa: BLE001 - 关闭失败不能阻塞应用退出
                logger.exception("dsh.harness 关闭失败 run=%s", run_id)
            self._harnesses.pop(run_id, None)

    # --- 内部 ---

    def _finish(self, run: DshRun, status: DshRunStatus) -> None:
        """写终态与耗时；所有路径都走这里，保证 finished_at / duration_seconds 必填。"""
        run.status = status
        run.finished_at = datetime.now(timezone.utc)
        if run.started_at is not None:
            run.duration_seconds = (run.finished_at - run.started_at).total_seconds()

    async def _publish(self, event: str, run: DshRun) -> None:
        if self._bus is not None:
            await self._bus.publish(event, run.to_dict())


__all__ = ["FluxDshClient", "DshRun", "TERMINAL_STATUSES"]
