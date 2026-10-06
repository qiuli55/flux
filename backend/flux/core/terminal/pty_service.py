"""Human Terminal PTY service.

This is deliberately separate from the Agent Terminal command runner. A human terminal
is an interactive shell backed by a real pseudo-terminal: input is written byte-for-byte
to the PTY, output contains ANSI/control sequences, and resize changes the PTY window size.

Linux/macOS use Python's native pty module. The current implementation targets the Unix
runtime used by Flux's server/desktop Linux environment; Windows support should use a
ConPTY-backed implementation before shipping a Windows desktop build.
"""

from __future__ import annotations

import asyncio
import os
import pty
import signal
import struct
import termios
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

from flux.core.event.bus import EventBus, Events
from flux.core.terminal.repository import TerminalRepository
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.enums import TerminalEventKind, TerminalSessionStatus, TerminalSource
from flux.errors import ConflictError, ValidationError
from flux.models.terminal import TerminalSession


DEFAULT_COLS = 120
DEFAULT_ROWS = 32
READ_CHUNK = 64 * 1024


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
        self._lock = asyncio.Lock()

    async def create_session(
        self, *, cols: int = DEFAULT_COLS, rows: int = DEFAULT_ROWS
    ) -> TerminalSession:
        root = resolve_workspace_root(self._configured_root)
        session = await self._repo.create_session(workspace_root=str(root), run_id=None)
        try:
            pid, fd = await asyncio.to_thread(self._spawn, str(session.id), str(root), cols, rows)
        except Exception:
            await self._repo.set_status(session.id, TerminalSessionStatus.CLOSED)
            raise
        self._pids[str(session.id)] = pid
        self._fds[str(session.id)] = fd
        await self._emit(session.id, TerminalEventKind.SESSION_CREATED)
        return session

    async def get_session(self, session_id: str | uuid.UUID) -> TerminalSession:
        return await self._repo.get_session(session_id)

    async def send_input(self, session_id: str | uuid.UUID, data: str) -> None:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None or key in self._closed:
            raise ConflictError("终端会话已结束", details={"session_id": key})
        if not data:
            return
        await asyncio.to_thread(os.write, fd, data.encode("utf-8", errors="replace"))

    async def resize(self, session_id: str | uuid.UUID, cols: int, rows: int) -> None:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None or key in self._closed:
            return
        cols = max(20, min(int(cols), 500))
        rows = max(5, min(int(rows), 200))
        await asyncio.to_thread(self._resize_fd, fd, cols, rows)

    async def stream_output(self, session_id: str | uuid.UUID) -> AsyncIterator[str]:
        key = str(session_id)
        fd = self._fds.get(key)
        if fd is None:
            raise ConflictError("PTY 会话不存在或已结束", details={"session_id": key})
        while key not in self._closed:
            try:
                data = await asyncio.to_thread(os.read, fd, READ_CHUNK)
            except OSError:
                break
            if not data:
                break
            yield data.decode("utf-8", errors="replace")
        await self._finish(key)

    async def stop(self, session_id: str | uuid.UUID, *, force: bool = False) -> None:
        key = str(session_id)
        pid = self._pids.get(key)
        if pid is None:
            return
        self._closed.add(key)
        try:
            sig = signal.SIGKILL if force else signal.SIGTERM
            await asyncio.to_thread(os.killpg, os.getpgid(pid), sig)
        except ProcessLookupError:
            pass
        finally:
            await self._finish(key)

    async def shutdown(self) -> None:
        for key in list(self._pids):
            await self.stop(key, force=True)

    def _spawn(self, session_id: str, cwd: str, cols: int, rows: int) -> tuple[int, int]:
        shell = os.environ.get("SHELL") or "/bin/bash"
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(cwd)
            env = os.environ.copy()
            env.setdefault("TERM", "xterm-256color")
            env.setdefault("COLORTERM", "truecolor")
            os.execvpe(shell, [shell, "-l"], env)
        self._resize_fd(fd, cols, rows)
        os.set_blocking(fd, True)
        return pid, fd

    @staticmethod
    def _resize_fd(fd: int, cols: int, rows: int) -> None:
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        termios.tcsetwinsize(fd, (rows, cols))
        # tcsetwinsize is the preferred API on modern Python; the ioctl fallback keeps
        # this compatible with older Python versions supported by Flux.
        try:
            import fcntl

            fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)
        except (AttributeError, OSError):
            pass

    async def _finish(self, key: str) -> None:
        async with self._lock:
            if key in self._closed and key not in self._fds:
                return
            self._closed.add(key)
            fd = self._fds.pop(key, None)
            self._pids.pop(key, None)
        if fd is not None:
            with contextlib_suppress(OSError):
                os.close(fd)
        try:
            session = await self._repo.get_session(key)
            if TerminalSessionStatus(session.status) is TerminalSessionStatus.ACTIVE:
                await self._repo.set_status(session.id, TerminalSessionStatus.STOPPED)
            await self._emit(session.id, TerminalEventKind.SESSION_CLOSED)
        except Exception:
            # The PTY is already gone; cleanup must not turn a normal process exit into
            # an application-level failure.
            return

    async def _emit(self, session_id: uuid.UUID, kind: TerminalEventKind) -> None:
        event = await self._repo.append_event(
            session_id,
            kind=kind,
            source=TerminalSource.USER,
        )
        if self._bus is not None:
            await self._bus.publish(Events.TERMINAL_EVENT, event.to_dict())


class contextlib_suppress:
    """Tiny local suppress helper to keep the PTY module dependency-free."""

    def __init__(self, *exceptions: type[BaseException]) -> None:
        self._exceptions = exceptions

    def __enter__(self) -> "contextlib_suppress":
        return self

    def __exit__(self, exc_type: type[BaseException] | None, exc: BaseException | None, tb: object) -> bool:
        return exc_type is not None and issubclass(exc_type, self._exceptions)
