"""Terminal Session 服务（Agent Terminal Console §6 / §10）。

Flux 自己执行命令，因此命令、输出、退出码都拿得到（不再依赖 Agent 运行时内部行为）：

- 每个会话固定一个工作区根，命令在其中以**独立进程组**执行；
- 输出按行落成 `terminal.output` 事件（seq 单调），前端按 seq 续读即恢复历史（§12）；
- Stop 走 SIGTERM → grace → SIGKILL → **确认进程组消失**，确认之后才落 stopped，
  杜绝 §10 禁止的"UI 已停止而后台仍在跑"。

平台：进程组隔离与终止走 platforms 原语（W-1：POSIX `setsid`/`killpg`，Windows
`CREATE_NEW_PROCESS_GROUP`/`taskkill /T /F`），本模块不再直接触碰平台 API。
"""

from __future__ import annotations

import asyncio
import subprocess
import uuid
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from flux.core.agent_runtime import platforms
from flux.core.event.bus import EventBus, Events
from flux.core.terminal.repository import TerminalRepository
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.enums import TerminalEventKind, TerminalSessionKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError, ValidationError
from flux.models.terminal import TerminalEvent, TerminalSession

#: Stop 的宽限期：SIGTERM 后等这么久，仍不退出就升级 SIGKILL
STOP_GRACE_SECONDS = 5.0
#: SIGKILL 后确认进程组消失的等待上限
KILL_CONFIRM_SECONDS = 2.0
#: 实时流无事件时的心跳间隔（秒）；到点发一个 None 哨兵，API 层转成 SSE 注释行
STREAM_HEARTBEAT_SECONDS = 15.0
#: 补历史时的分页大小
STREAM_REPLAY_PAGE = 1000


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
        #: 正在执行的命令 task；Stop 必须等它写完 command.finished/failed 再关闭会话。
        self._command_tasks: dict[str, asyncio.Task[TerminalEvent]] = {}
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
        session = await self._repo.get_session(session_id)
        self._ensure_agent_session(session)
        return session

    async def list_sessions(
        self,
        *,
        limit: int = 20,
        kind: TerminalSessionKind = TerminalSessionKind.AGENT,
    ) -> list[TerminalSession]:
        """List sessions owned by the requested terminal runtime."""
        return await self._repo.list_sessions(limit=limit, kind=kind)

    async def list_events(
        self, session_id: str | uuid.UUID, *, after_seq: int = 0, limit: int = 2000
    ) -> list[TerminalEvent]:
        session = await self.get_session(session_id)
        return await self._repo.list_events(session.id, after_seq=after_seq, limit=limit)

    async def stream_events(
        self, session_id: str | uuid.UUID, *, after_seq: int = 0
    ) -> AsyncIterator[dict[str, Any] | None]:
        """历史续读 + 实时增量（§11 窗口同步 / §12 历史恢复）。

        顺序上先订阅总线再补历史：两者之间到达的事件会进订阅缓冲，补历史后按 seq 去重
        合并，因此既不重复也不丢。会话已结束（session.closed）时推完即收流；仍在跑则保持
        连接，直到客户端断开（关闭终端窗口不停 Agent，§11）。产出 `None` 表示心跳哨兵。
        """
        session = await self.get_session(session_id)
        key = str(session.id)
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

        async def on_event(_event: str, payload: dict[str, Any]) -> None:
            if str(payload.get("session_id")) == key:
                queue.put_nowait(payload)

        if self._bus is not None:
            self._bus.subscribe(Events.TERMINAL_EVENT, on_event)
        last_seq = after_seq
        closed = False
        try:
            # 1) 补历史：分页读到追上为止
            while True:
                batch = await self._repo.list_events(
                    session.id, after_seq=last_seq, limit=STREAM_REPLAY_PAGE
                )
                if not batch:
                    break
                for event in batch:
                    if event.seq <= last_seq:
                        continue
                    last_seq = event.seq
                    payload = event.to_dict()
                    yield payload
                    if payload["kind"] == TerminalEventKind.SESSION_CLOSED.value:
                        closed = True
                if len(batch) < STREAM_REPLAY_PAGE or closed:
                    break
            # 2) 合并补历史期间缓冲的实时事件（按 seq 去重）
            while not closed and not queue.empty():
                payload = queue.get_nowait()
                seq = payload.get("seq")
                if not isinstance(seq, int) or seq <= last_seq:
                    continue
                last_seq = seq
                yield payload
                if payload["kind"] == TerminalEventKind.SESSION_CLOSED.value:
                    closed = True
            # 会话已结束且历史已补完：不可能再有新事件，直接收流（重连时 after_seq 已在末尾）
            if (
                not closed
                and TerminalSessionStatus(session.status) is not TerminalSessionStatus.ACTIVE
                and last_seq >= session.next_seq - 1
                and queue.empty()
            ):
                return
            # 3) 实时推送
            while not closed:
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=STREAM_HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:  # noqa: UP041 - 3.10 下与内置 TimeoutError 不同源
                    # 总线不可用时也能推进：直接查库补漏
                    if self._bus is None:
                        for event in await self._repo.list_events(
                            session.id, after_seq=last_seq, limit=STREAM_REPLAY_PAGE
                        ):
                            last_seq = event.seq
                            yield event.to_dict()
                    if not closed:
                        yield None
                    continue
                seq = payload.get("seq")
                if not isinstance(seq, int) or seq <= last_seq:
                    continue
                last_seq = seq
                yield payload
                if payload["kind"] == TerminalEventKind.SESSION_CLOSED.value:
                    closed = True
        finally:
            if self._bus is not None:
                self._bus.unsubscribe(Events.TERMINAL_EVENT, on_event)

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

    @staticmethod
    def _ensure_agent_session(session: TerminalSession) -> None:
        if session.kind != TerminalSessionKind.AGENT.value:
            raise ConflictError(
                "该会话属于 Human Terminal，不是 Agent Terminal",
                details={"session_id": str(session.id), "kind": session.kind},
            )

    async def _run_locked(
        self, session: TerminalSession, command: str, source: TerminalSource
    ) -> TerminalEvent:
        task = asyncio.current_task()
        if task is None:
            raise RuntimeError("Terminal command must run inside an asyncio task")
        key = str(session.id)
        self._command_tasks[key] = task
        try:
            await self._emit(
                session.id, TerminalEventKind.COMMAND_STARTED, source, command=command
            )
            loop = asyncio.get_running_loop()
            process = subprocess.Popen(  # noqa: S602 - 终端本身就是为了执行命令（用户/Agent 主动发起）
                command,
                shell=True,
                cwd=session.workspace_root,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                **platforms.popen_kwargs(),  # 独立进程组：Stop 只杀本会话，不误伤 Flux 自身
            )
            self._processes[key] = process
            try:
                await asyncio.to_thread(self._pump_output, loop, session.id, process, source)
                exit_code = await asyncio.to_thread(process.wait)
            finally:
                self._processes.pop(key, None)
            finished = (
                TerminalEventKind.COMMAND_FINISHED
                if exit_code == 0
                else TerminalEventKind.COMMAND_FAILED
            )
            return await self._emit(
                session.id,
                finished,
                source,
                command=command,
                exit_code=exit_code,
            )
        finally:
            self._command_tasks.pop(key, None)

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
        key = str(session.id)
        process = self._processes.get(key)
        if process is not None and process.poll() is None:
            await asyncio.to_thread(self._terminate_tree, process, force=force)

        command_task = self._command_tasks.get(key)
        if command_task is not None and command_task is not asyncio.current_task():
            try:
                await asyncio.wait_for(
                    asyncio.shield(command_task),
                    timeout=STOP_GRACE_SECONDS + KILL_CONFIRM_SECONDS + 1.0,
                )
            except asyncio.TimeoutError as exc:
                raise ConflictError(
                    "终端命令未能在停止后完成收尾",
                    details={"session_id": key},
                ) from exc

        if process is not None:
            await self._emit(session.id, TerminalEventKind.PROCESS_TERMINATED, TerminalSource.USER)
        await self._emit(session.id, TerminalEventKind.SESSION_CLOSED, TerminalSource.USER)
        return await self._repo.set_status(session.id, TerminalSessionStatus.STOPPED)

    @staticmethod
    def _terminate_tree(process: subprocess.Popen[str], *, force: bool) -> bool:
        """优雅信号 → grace → 强杀 → 确认进程树消失（平台原语，见 platforms）。"""
        return platforms.terminate_process_tree(
            process,
            force=force,
            grace=STOP_GRACE_SECONDS,
            confirm=KILL_CONFIRM_SECONDS,
        )

    async def shutdown(self) -> None:
        """进程退出前清理仍在跑的终端进程树并等待命令 task 收尾。"""
        for session_id in list(self._processes):
            process = self._processes.get(session_id)
            if process is not None and process.poll() is None:
                await asyncio.to_thread(self._terminate_tree, process, force=True)
        tasks = [
            task
            for task in self._command_tasks.values()
            if task is not asyncio.current_task()
        ]
        if tasks:
            await asyncio.gather(
                *(asyncio.wait_for(asyncio.shield(task), timeout=10.0) for task in tasks),
                return_exceptions=True,
            )
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
