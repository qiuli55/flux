"""Human Terminal PTY service.

Human Terminal is intentionally separate from Agent Terminal. A human terminal is an
interactive shell backed by a real pseudo-terminal: input is written to the PTY, output
keeps ANSI/control sequences, and resize changes the PTY window size.

The service owns one reader per PTY and fans output out to websocket subscribers. This
is important: a websocket reconnect must never create a second competing reader on the
same PTY. Sessions live in the process and are reaped by Container.dispose().

Linux/macOS use Python's native pty module. A Windows desktop build needs a ConPTY-backed
implementation before this service is enabled there.
"""

from __future__ import annotations

import asyncio
import errno
import os
import select
import signal
import struct
import uuid
from collections.abc import AsyncIterator
from contextlib import suppress
from pathlib import Path

from flux.core.event.bus import EventBus, Events
from flux.core.terminal.repository import TerminalRepository
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.enums import TerminalEventKind, TerminalSessionKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError
from flux.models.terminal import TerminalSession

if os.name != "nt":
    import pty
    import termios

DEFAULT_COLS = 120
DEFAULT_ROWS = 32
SCROLLBACK_BYTES = 256 * 1024
QUEUE_SIZE = 256
STOP_GRACE_SECONDS = 1.0
KILL_CONFIRM_SECONDS = 1.0


class HumanPtyService:
    """Manage interactive user-owned PTY sessions."""

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
        self._fds: dict[str, int] = {}
        self._pids: dict[str, int] = {}
        self._write_locks: dict[str, asyncio.Lock] = {}
        self._closed: set[str] = set()
        self._buffers: dict[str, bytearray] = {}
        self._subscribers: dict[str, set[asyncio.Queue[bytes | None]]] = {}
        self._reader_tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

    async def create_session(
        self, *, cols: int = DEFAULT_COLS, rows: int = DEFAULT_ROWS
    ) -> TerminalSession:
        if os.name == "nt":
            raise ConflictError("Windows 暂不支持 Human Terminal PTY，请等待 ConPTY 后端")
        root = resolve_workspace_root(self._configured_root)
        session = await self._repo.create_session(
            workspace_root=str(root),
            run_id=None,
            kind=TerminalSessionKind.HUMAN,
        )
        try:
            # pty.fork() must run on the event-loop thread. Forking from an asyncio
            # worker thread can inherit partially locked runtime state.
            pid, fd = self._spawn(str(root), cols, rows)
        except Exception:
            await self._repo.set_status(session.id, TerminalSessionStatus.CLOSED)
            raise

        key = str(session.id)
        self._pids[key] = pid
        self._fds[key] = fd
        self._buffers[key] = bytearray()
        self._subscribers[key] = set()

        # Persist session.created before starting the reader so a very fast shell exit
        # can never make session.closed appear before session.created in the event stream.
        try:
            await self._emit(session.id, TerminalEventKind.SESSION_CREATED)
        except Exception:
            await self._cleanup_spawned_process(key, pid, fd)
            with suppress(Exception):
                await self._repo.set_status(session.id, TerminalSessionStatus.CLOSED)
            raise

        self._reader_tasks[key] = asyncio.create_task(
            self._read_loop(key), name=f"flux-pty-{key}"
        )
        return session

    async def recover_orphaned_sessions(self) -> int:
        """Mark persisted Human PTYs from a previous process as closed.

        PTY processes are process-local and intentionally not resurrected after a Flux
        restart. The database must therefore not advertise stale active sessions.
        """
        sessions = await self._repo.list_sessions(
            limit=10000,
            kind=TerminalSessionKind.HUMAN,
            status=TerminalSessionStatus.ACTIVE,
        )
        recovered = 0
        for session in sessions:
            if str(session.id) in self._fds:
                continue
            await self._repo.set_status(session.id, TerminalSessionStatus.CLOSED)
            await self._emit(session.id, TerminalEventKind.SESSION_CLOSED)
            recovered += 1
        return recovered

    async def get_session(self, session_id: str | uuid.UUID) -> TerminalSession:
        session = await self._repo.get_session(session_id)
        if session.kind != TerminalSessionKind.HUMAN.value:
            raise ConflictError(
                "该会话属于 Agent Terminal，不是 Human Terminal",
                details={"session_id": str(session.id), "kind": session.kind},
            )
        return session

    async def list_sessions(self, *, limit: int = 20) -> list[TerminalSession]:
        """List only live Human Terminal sessions owned by this Flux process."""
        sessions = await self._repo.list_sessions(
            limit=limit,
            kind=TerminalSessionKind.HUMAN,
            status=TerminalSessionStatus.ACTIVE,
        )
        return [session for session in sessions if str(session.id) in self._fds]

    async def send_input(self, session_id: str | uuid.UUID, data: str) -> None:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None or key in self._closed:
            raise ConflictError("终端会话已结束", details={"session_id": key})
        if not data:
            return
        payload = data.encode("utf-8", errors="replace")
        lock = self._write_locks.setdefault(key, asyncio.Lock())
        async with lock:
            try:
                await asyncio.to_thread(self._write_all, fd, payload)
            except (OSError, ValueError) as exc:
                raise ConflictError(
                    "终端输入写入失败",
                    details={"session_id": key},
                ) from exc

    async def resize(self, session_id: str | uuid.UUID, cols: int, rows: int) -> None:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None or key in self._closed:
            raise ConflictError("终端会话已结束", details={"session_id": key})
        cols = max(20, min(int(cols), 500))
        rows = max(5, min(int(rows), 200))
        await asyncio.to_thread(self._resize_fd, fd, cols, rows)

    async def stream_output(self, session_id: str | uuid.UUID) -> AsyncIterator[str]:
        """Subscribe to one PTY's output without creating a competing reader."""
        key = str(session_id)
        if key not in self._fds:
            raise ConflictError("PTY 会话不存在或已结束", details={"session_id": key})

        queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=QUEUE_SIZE)
        subscribers = self._subscribers.setdefault(key, set())
        subscribers.add(queue)
        try:
            buffered = bytes(self._buffers.get(key, b""))
            if buffered:
                yield buffered.decode("utf-8", errors="replace")
            while key not in self._closed:
                chunk = await queue.get()
                if chunk is None:
                    break
                yield chunk.decode("utf-8", errors="replace")
        finally:
            subscribers.discard(queue)

    async def stop(
        self, session_id: str | uuid.UUID, *, force: bool = False
    ) -> TerminalSession:
        key = str(session_id)
        pid = self._pids.get(key)
        if pid is None:
            return await self.get_session(session_id)
        try:
            pgid = os.getpgid(pid)
        except ProcessLookupError:
            await self._finish(key)
            return await self.get_session(session_id)

        try:
            signal_value = signal.SIGKILL if force else signal.SIGTERM
            await asyncio.to_thread(os.killpg, pgid, signal_value)
        except ProcessLookupError:
            await self._finish(key)
            return await self.get_session(session_id)

        if not force:
            confirmed = await self._wait_for_group_exit(pgid, pid, STOP_GRACE_SECONDS)
            if not confirmed:
                with suppress(ProcessLookupError):
                    await asyncio.to_thread(os.killpg, pgid, signal.SIGKILL)
                confirmed = await self._wait_for_group_exit(pgid, pid, KILL_CONFIRM_SECONDS)
            if not confirmed:
                raise ConflictError(
                    "无法确认 Human Terminal 进程组已退出",
                    details={"session_id": key, "pgid": pgid},
                )
        else:
            confirmed = await self._wait_for_group_exit(pgid, pid, KILL_CONFIRM_SECONDS)
            if not confirmed:
                raise ConflictError(
                    "无法确认 Human Terminal 进程组已退出",
                    details={"session_id": key, "pgid": pgid},
                )
        await self._finish(key)
        return await self.get_session(session_id)

    async def shutdown(self) -> None:
        for key in list(self._pids):
            await self.stop(key, force=True)

    async def _cleanup_spawned_process(self, key: str, pid: int, fd: int) -> None:
        self._closed.add(key)
        self._pids.pop(key, None)
        self._fds.pop(key, None)
        self._write_locks.pop(key, None)
        self._subscribers.pop(key, None)
        self._buffers.pop(key, None)
        try:
            pgid = os.getpgid(pid)
            with suppress(ProcessLookupError):
                await asyncio.to_thread(os.killpg, pgid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        with suppress(OSError):
            await asyncio.to_thread(os.close, fd)
        with suppress(ChildProcessError, ProcessLookupError):
            await asyncio.to_thread(os.waitpid, pid, 0)

    def _spawn(self, cwd: str, cols: int, rows: int) -> tuple[int, int]:
        if os.name == "nt":
            raise ConflictError("Windows 暂不支持 Human Terminal PTY，请使用 ConPTY 实现")
        shell = os.environ.get("SHELL") or "/bin/bash"
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(cwd)
            env = os.environ.copy()
            env.setdefault("TERM", "xterm-256color")
            env.setdefault("COLORTERM", "truecolor")
            os.execvpe(shell, [shell, "-l"], env)
        self._resize_fd(fd, cols, rows)
        os.set_blocking(fd, False)
        return pid, fd

    @staticmethod
    def _resize_fd(fd: int, cols: int, rows: int) -> None:
        if os.name == "nt":
            return
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        with suppress(AttributeError):
            termios.tcsetwinsize(fd, (rows, cols))
        try:
            import fcntl

            fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)
        except (AttributeError, OSError):
            pass

    @staticmethod
    async def _wait_for_group_exit(pgid: int, pid: int, timeout: float) -> bool:
        """Confirm the leader is reaped before checking whether any process remains in the group."""
        deadline = asyncio.get_running_loop().time() + timeout
        leader_reaped = False
        while asyncio.get_running_loop().time() < deadline:
            if not leader_reaped:
                try:
                    result = await asyncio.to_thread(os.waitpid, pid, os.WNOHANG)
                    leader_reaped = result[0] == pid
                except ChildProcessError:
                    leader_reaped = True

            try:
                os.killpg(pgid, 0)
            except ProcessLookupError:
                return leader_reaped
            except PermissionError:
                return False

            # A zombie leader has been reaped above, so killpg(..., 0) now reflects
            # live group membership instead of the unreaped child itself.
            await asyncio.sleep(0.05)

        if not leader_reaped:
            try:
                result = await asyncio.to_thread(os.waitpid, pid, os.WNOHANG)
                leader_reaped = result[0] == pid
            except ChildProcessError:
                leader_reaped = True
        if not leader_reaped:
            return False
        try:
            os.killpg(pgid, 0)
        except ProcessLookupError:
            return True
        except PermissionError:
            return False
        return False

    @staticmethod
    def _write_all(fd: int, payload: bytes) -> None:
        view = memoryview(payload)
        while view:
            try:
                written = os.write(fd, view)
                if written <= 0:
                    raise OSError("PTY write returned no progress")
                view = view[written:]
            except BlockingIOError:
                _ready, writable, _exceptional = select.select([], [fd], [], 0.2)
                if not writable:
                    continue

    async def _read_loop(self, key: str) -> None:
        fd = self._fds.get(key)
        if fd is None:
            return
        loop = asyncio.get_running_loop()
        done = asyncio.Event()

        def on_readable() -> None:
            try:
                data = os.read(fd, 64 * 1024)
            except BlockingIOError:
                return
            except OSError as exc:
                if exc.errno not in {errno.EIO, errno.EBADF}:
                    return
                done.set()
                return
            if not data:
                done.set()
                return
            buffer = self._buffers.setdefault(key, bytearray())
            buffer.extend(data)
            if len(buffer) > SCROLLBACK_BYTES:
                del buffer[:-SCROLLBACK_BYTES]
            for queue in tuple(self._subscribers.get(key, ())):
                try:
                    queue.put_nowait(data)
                except asyncio.QueueFull:
                    with suppress(asyncio.QueueEmpty):
                        queue.get_nowait()
                    with suppress(asyncio.QueueFull):
                        queue.put_nowait(data)

        loop.add_reader(fd, on_readable)
        try:
            await done.wait()
        finally:
            with suppress(Exception):
                loop.remove_reader(fd)
            await self._finish(key)

    async def _finish(self, key: str) -> None:
        async with self._lock:
            if key in self._closed and key not in self._fds:
                return
            self._closed.add(key)
            fd = self._fds.pop(key, None)
            pid = self._pids.pop(key, None)
            reader = self._reader_tasks.pop(key, None)
            subscribers = self._subscribers.pop(key, set())
            self._buffers.pop(key, None)
            self._write_locks.pop(key, None)

        if fd is not None:
            with suppress(OSError):
                asyncio.get_running_loop().remove_reader(fd)
            with suppress(OSError):
                os.close(fd)
        for queue in subscribers:
            with suppress(asyncio.QueueFull):
                queue.put_nowait(None)

        current = asyncio.current_task()
        if reader is not None and reader is not current:
            reader.cancel()
            with suppress(asyncio.CancelledError):
                await reader

        if pid is not None:
            with suppress(ChildProcessError, ProcessLookupError):
                await asyncio.to_thread(os.waitpid, pid, 0)

        try:
            session = await self._repo.get_session(key)
            if TerminalSessionStatus(session.status) is TerminalSessionStatus.ACTIVE:
                await self._repo.set_status(session.id, TerminalSessionStatus.STOPPED)
            await self._emit(session.id, TerminalEventKind.SESSION_CLOSED)
        except Exception:
            return

    async def _emit(self, session_id: uuid.UUID, kind: TerminalEventKind) -> None:
        event = await self._repo.append_event(
            session_id,
            kind=kind,
            source=TerminalSource.USER,
        )
        if self._bus is not None:
            await self._bus.publish(Events.TERMINAL_EVENT, event.to_dict())
