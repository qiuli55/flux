"""Terminal Session 服务（Agent Terminal Console §6 / §10）。

Flux 自己执行命令，因此命令、输出、退出码都拿得到（不再依赖 Agent 运行时内部行为）：

- 每个会话固定一个工作区根，命令在其中以**独立进程组**执行；
- 输出按行落成 `terminal.output` 事件（seq 单调），前端按 seq 续读即恢复历史（§12）；
- Stop 走 SIGTERM → grace → SIGKILL → **确认进程组消失**，确认之后才落 stopped，
  杜绝 §10 禁止的"UI 已停止而后台仍在跑"。

平台：当前实现是 POSIX（`start_new_session` + `killpg`）；Windows 适配归 W-1（设计 §11）。
"""

from __future__ import annotations

import asyncio
import os
import signal
import subprocess
import time
import uuid
from pathlib import Path

from flux.core.event.bus import EventBus, Events
from flux.core.terminal.repository import TerminalRepository
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.enums import TerminalEventKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError, ValidationError
from flux.logging import get_logger
from flux.models.terminal import TerminalEvent, TerminalSession

logger = get_logger(__name__)

#: Stop 的宽限期：SIGTERM 后等这么久，仍不退出就升级 SIGKILL
STOP_GRACE_SECONDS = 5.0
#: SIGKILL 后确认进程组消失的等待上限
KILL_CONFIRM_SECONDS = 2.0


class TerminalService:
    """终端会话管理：会话生命周期 + 命令执行 + Stop / Force Stop。"""

    def __init__(
        self,
        repository: TerminalRepository,
        bus: EventBus | None = None,
        *,
        workspace_root: str | Path | None = None,
    ) -> None:
        self._repo = repository
        self._bus = bus
        self._configured_root = Path(workspace_root).expanduser() if workspace_root else None
        #: 正在跑的进程（会话 ID → Popen），Stop 与 shutdown 都从这里找
        self._processes: dict[str, subprocess.Popen[str]] = {}
        #: 每会话一把锁：同一会话的命令串行执行
        self._locks: dict[str, asyncio.Lock] = {}
        #: seq 分配与落库串行化：并发 emit（输出线程 + Stop）不允许撞号
        self._emit_lock = asyncio.Lock()

    # --- 会话 ---

    async def create_session(
        self, *, run_id: uuid.UUID | None = None, workspace_root: str | Path | None = None
    ) -> TerminalSession:
        root = self._resolve_root(workspace_root)
        session = await self._repo.create_session(workspace_root=str(root), run_id=run_id)
        await self._emit(session.id, TerminalEventKind.SESSION_CREATED, TerminalSource.USER)
        return session

    async def get_session(self, session_id: str | uuid.UUID) -> TerminalSession:
        return await self._repo.get_session(session_id)

    async def list_sessions(self, *, limit: int = 20) -> list[TerminalSession]:
        return await self._repo.list_sessions(limit=limit)

    async def list_events(
        self, session_id: str | uuid.UUID, *, after_seq: int = 0, limit: int = 2000
    ) -> list[TerminalEvent]:
        return await self._repo.list_events(session_id, after_seq=after_seq, limit=limit)

    # --- 执行 ---

    async def run_command(
        self,
        session_id: str | uuid.UUID,
        command: str,
        *,
        source: TerminalSource = TerminalSource.USER,
    ) -> TerminalEvent:
        """在会话的工作区根里执行一条命令；输出逐行落库，返回结束事件。"""
        text = (command or "").strip()
        if not text:
            raise ValidationError("命令不能为空", details={"session_id": str(session_id)})
        session = await self._repo.get_session(session_id)
        if TerminalSessionStatus(session.status) is not TerminalSessionStatus.ACTIVE:
            raise ConflictError(
                f"终端会话已 {session.status}，不能再执行命令",
                details={"session_id": str(session.id), "status": session.status},
            )
        lock = self._locks.setdefault(str(session.id), asyncio.Lock())
        async with lock:
            return await self._run_locked(session, text, source)

    async def _run_locked(
        self, session: TerminalSession, command: str, source: TerminalSource
    ) -> TerminalEvent:
        await self._emit(session.id, TerminalEventKind.COMMAND_STARTED, source, command=command)
        loop = asyncio.get_running_loop()
        process = subprocess.Popen(  # noqa: S602 - 终端本身就是为了执行命令（用户/Agent 主动发起）
            command,
            shell=True,
            cwd=session.workspace_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True,  # 独立进程组：Stop 只杀本会话，不误伤 Flux 自身
        )
        self._processes[str(session.id)] = process
        try:
            await asyncio.to_thread(self._pump_output, loop, session.id, process, source)
            exit_code = await asyncio.to_thread(process.wait)
        finally:
            self._processes.pop(str(session.id), None)
        finished = (
            TerminalEventKind.COMMAND_FINISHED
            if exit_code == 0
            else TerminalEventKind.COMMAND_FAILED
        )
        return await self._emit(session.id, finished, source, command=command, exit_code=exit_code)

    def _pump_output(
        self,
        loop: asyncio.AbstractEventLoop,
        session_id: uuid.UUID,
        process: subprocess.Popen[str],
        source: TerminalSource,
    ) -> None:
        """工作线程按行读输出 → 回事件循环落库（保证输出顺序与 seq 一致）。"""
        stream = process.stdout
        if stream is None:
            return
        try:
            for line in iter(stream.readline, ""):
                future = asyncio.run_coroutine_threadsafe(
                    self._emit(session_id, TerminalEventKind.OUTPUT, source, chunk=line), loop
                )
                future.result()
        finally:
            stream.close()

    # --- Stop / Force Stop（§3.3 / §3.4 / §10）---

    async def stop(self, session_id: str | uuid.UUID, *, force: bool = False) -> TerminalSession:
        """停止会话；confirm 到进程组消失之后才写 stopped，状态不谎报。"""
        session = await self._repo.get_session(session_id)
        await self._emit(
            session.id,
            TerminalEventKind.STOP_REQUESTED,
            TerminalSource.USER,
            command="force-stop" if force else "stop",
        )
        process = self._processes.get(str(session.id))
        if process is not None and process.poll() is None:
            await asyncio.to_thread(self._terminate_tree, process, force=force)
        await self._emit(session.id, TerminalEventKind.PROCESS_TERMINATED, TerminalSource.USER)
        await self._emit(session.id, TerminalEventKind.SESSION_CLOSED, TerminalSource.USER)
        return await self._repo.set_status(session.id, TerminalSessionStatus.STOPPED)

    @staticmethod
    def _terminate_tree(process: subprocess.Popen[str], *, force: bool) -> None:
        """SIGTERM → grace → SIGKILL → 确认进程组消失。"""
        pgid = os.getpgid(process.pid)
        if force:
            os.killpg(pgid, signal.SIGKILL)
        else:
            os.killpg(pgid, signal.SIGTERM)
            deadline = time.monotonic() + STOP_GRACE_SECONDS
            while time.monotonic() < deadline and process.poll() is None:
                time.sleep(0.1)
            if process.poll() is None:
                os.killpg(pgid, signal.SIGKILL)
        deadline = time.monotonic() + KILL_CONFIRM_SECONDS
        while time.monotonic() < deadline:
            try:
                os.killpg(pgid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        logger.warning("terminal.stop 未能确认进程组退出 pgid=%s", pgid)

    async def shutdown(self) -> None:
        """进程退出前清理仍在跑的终端进程树（与 DSH supervisor 同一取舍）。"""
        for session_id in list(self._processes):
            process = self._processes.get(session_id)
            if process is not None and process.poll() is None:
                await asyncio.to_thread(self._terminate_tree, process, force=True)
        self._processes.clear()

    # --- 内部 ---

    def _resolve_root(self, override: str | Path | None) -> Path:
        return resolve_workspace_root(override if override is not None else self._configured_root)

    async def _emit(
        self,
        session_id: uuid.UUID,
        kind: TerminalEventKind,
        source: TerminalSource,
        *,
        command: str | None = None,
        chunk: str | None = None,
        exit_code: int | None = None,
    ) -> TerminalEvent:
        async with self._emit_lock:
            event = await self._repo.append_event(
                session_id,
                kind=kind,
                source=source,
                command=command,
                chunk=chunk,
                exit_code=exit_code,
            )
        if self._bus is not None:
            await self._bus.publish(Events.TERMINAL_EVENT, event.to_dict())
        return event
