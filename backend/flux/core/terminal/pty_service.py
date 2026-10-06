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
import pty
import signal
import struct
import termios
import uuid
from collections.abc import AsyncIterator
from contextlib import suppress
from pathlib import Path

from flux.core.event.bus import EventBus, Events
from flux.core.terminal.repository import TerminalRepository
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.enums import TerminalEventKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError
from flux.models.terminal import TerminalSession

DEFAULT_COLS = 120
DEFAULT_ROWS = 32
SCROLLBACK_BYTES = 256 * 1024
QUEUE_SIZE = 256


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
        self._closed: set[str] = set()
        self._buffers: dict[str, bytearray] = {}
        self._subscribers: dict[str, set[asyncio.Queue[bytes | None]]] = {}
        self._reader_tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

    async def create_session(
        self, *, cols: int = DEFAULT_COLS, rows: int = DEFAULT_ROWS
    ) -> TerminalSession:
        root = resolve_workspace_root(self._configured_root)
        session = await self._repo.create_session(workspace_root=str(root), run_id=None)
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
        self._reader_tasks[key] = asyncio.create_task(self._read_loop(key), name=f"flux-pty-{key}")
        await self._emit(session.id, TerminalEventKind.SESSION_CREATED)
        return session

    async def get_session(self, session_id: str | uuid.UUID) -> TerminalSession:
        return await self._repo.get_session(session_id)

    async def send_input(self, session_id: str | uuid.UUID, data: str) -> None:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None or key in self._closed:
            raise ConflictError("终端会话已结束", details={"session_id": key})
        if data:
            await asyncio.to_thread(os.write, fd, data.encode("utf-8", errors="replace"))

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

    async def stop(self, session_id: str | uuid.UUID, *, force: bool = False) -> None:
        key = str(session_id)
        pid = self._pids.get(key)
        if pid is None:
            return
        if key not in self._closed:
            try:
                sig = signal.SIGKILL if force else signal.SIGTERM
                await asyncio.to_thread(os.killpg, os.getpgid(pid), sig)
            except ProcessLookupError:
                pass
        await self._finish(key)

    async def shutdown(self) -> None:
        for key in list(self._pids):
            await self.stop(key, force=True)

    def _spawn(self, cwd: str, cols: int, rows: int) -> tuple[int, int]:
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
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        with suppress(AttributeError):
            termios.tcsetwinsize(fd, (rows, cols))
        try:
            import fcntl

            fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)
        except (AttributeError, OSError):
            pass

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
                    # A stalled browser must not backpressure the PTY. Drop queued
                    # chunks for that subscriber and let its next reconnect receive
                    # the bounded scrollback instead.
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
            # PTY cleanup is already complete; database/event cleanup must not leak
            # a process or break the server shutdown path.
            return

    async def _emit(self, session_id: uuid.UUID, kind: TerminalEventKind) -> None:
        event = await self._repo.append_event(
            session_id,
            kind=kind,
            source=TerminalSource.USER,
        )
        if self._bus is not None:
            await self._bus.publish(Events.TERMINAL_EVENT, event.to_dict())
