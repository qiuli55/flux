"""Claude Code 订阅（claude.ai）登录态管理。

背景：Flux 的 Claude Code Run 直接调用用户机器上的 `claude` CLI。密钥库通道会给
子进程注入 `ANTHROPIC_API_KEY`；但如果用户想用 Claude 订阅额度，就必须让 CLI 进入
登录态。Flux 不读写用户全局 `~/.claude`，而是让官方 CLI 把订阅登录做成一份长期令牌，
存在 Flux 自己的隔离目录（`~/.flux/claude-account/token`，0600），Run 时只以
`CLAUDE_CODE_OAUTH_TOKEN` 注入子进程环境。

登录方式（官方、可在远程服务器使用）：`claude setup-token`。它会在终端打印一个
可在手机/任意浏览器打开的授权链接；用户登录 claude.ai 后，页面给出一段
`code#state`，粘回终端即可。CLI 随后打印一年期 OAuth 令牌。本模块只解析并保存这枚
令牌，绝不在日志、状态接口或异常信息里输出令牌本身。
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import re
import shutil
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.platforms import secure_file
from flux.errors import ConfigurationError, NotFoundError, ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

#: 官方 setup-token 产出的订阅令牌前缀（`sk-ant-oat01-...`）
_TOKEN_RE = re.compile(r"sk-ant-oat[0-9]*-[A-Za-z0-9._-]+")
#: 授权页链接（claude.ai OAuth，带 PKCE）；只取这两个官方域名
_AUTHORIZE_URL_RE = re.compile(
    r"https://(?:claude\.com/cai/oauth/authorize|platform\.claude\.com/oauth/authorize)"
    r"[^\s\x1b\x07\"'<>]*"
)
#: OSC 8 超链接：`ESC ] 8 ; params ; URL BEL`（CLI 会把授权链接做成可点击文本）
_OSC8_RE = re.compile(r"\x1b\]8;[^;]*;([^\x07\x1b]*)(?:\x07|\x1b\\)")
#: 其余 ANSI / OSC 控制序列
_ESCAPE_RE = re.compile(
    r"\x1b\][^\x07]*(?:\x07|\x1b\\)"  # OSC
    r"|\x1b\[[0-9;?]*[ -/]*[@-~]"  # CSI
    r"|\x1b[@-_]"  # 其它双字节转义
)
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

#: 等 CLI 打印授权链接的上限；正常网络下是秒级
_PROMPT_TIMEOUT_SECONDS = 25.0
#: 一次登录会话的最长寿命（OAuth 授权码通常 10 分钟过期，这里留余量兜底）
_LOGIN_MAX_SECONDS = 20 * 60.0
#: 已结束的登录会话在内存里保留多久（够前端轮询到终态）
_LOGIN_KEEP_SECONDS = 10 * 60.0
#: 令牌文件读取上限（正常只有几十字节）
_MAX_TOKEN_BYTES = 4096

_PENDING_STATUSES = ("starting", "pending")


def claude_account_home(settings: Settings) -> Path:
    """订阅登录态目录（`FLUX_CLAUDE_ACCOUNT_HOME`）。"""
    raw = (settings.claude_account_home or "").strip()
    if raw:
        return Path(raw).expanduser()
    return Path.home() / ".flux" / "claude-account"


def strip_terminal_escapes(text: str) -> str:
    """去掉 ANSI / OSC 控制序列，保留可读文本（不含任何令牌清洗）。"""
    cleaned = _OSC8_RE.sub("", text or "")
    cleaned = _ESCAPE_RE.sub("", cleaned)
    cleaned = _CONTROL_RE.sub("", cleaned)
    return cleaned.replace("\r", "\n")


def parse_authorize_url(text: str) -> str:
    """从 CLI 输出里取官方授权页链接；取不到返回空串。

    优先读 OSC 8 超链接里的完整 URL（终端宽度较窄时可见文本会被折行），
    再退回到普通文本里的官方域名。取最长的一条，避免截断。
    """
    body = text or ""
    candidates: list[str] = []
    for match in _OSC8_RE.finditer(body):
        value = match.group(1).strip()
        if _AUTHORIZE_URL_RE.fullmatch(value):
            candidates.append(value)
    plain = strip_terminal_escapes(body)
    candidates.extend(match.group(0) for match in _AUTHORIZE_URL_RE.finditer(plain))
    if not candidates:
        return ""
    # 去掉链接末尾常见标点（终端输出里可能紧跟句号/引号）
    cleaned = [item.rstrip(".,;:)]}>") for item in candidates]
    return max(cleaned, key=len)


def parse_subscription_token(text: str) -> str:
    """从 CLI 输出里取长期令牌；取不到返回空串。"""
    match = _TOKEN_RE.search(text or "")
    return match.group(0) if match else ""


def _redact(text: str, limit: int = 400) -> str:
    """给用户看的失败摘要：去控制序列、抹掉令牌、只留最后几行。"""
    cleaned = _TOKEN_RE.sub("[已隐藏令牌]", strip_terminal_escapes(text))
    lines = [line.strip() for line in cleaned.splitlines() if line.strip()]
    return " | ".join(lines[-4:])[-limit:]


@dataclass
class _LoginSession:
    login_id: str
    process: Any
    master_fd: int | None = None
    using_pty: bool = False
    created_at: float = field(default_factory=time.monotonic)
    status: str = "starting"
    url: str = ""
    message: str = ""
    output: str = ""
    code_submitted: bool = False
    token_captured: bool = False
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    pump: asyncio.Task[None] | None = None
    watchdog: asyncio.Task[None] | None = None

    def public(self) -> dict[str, Any]:
        return {
            "login_id": self.login_id,
            "status": self.status,
            "url": self.url,
            "message": self.message,
            "needs_code": True,
            "expires_in": max(0, int(_LOGIN_MAX_SECONDS - (time.monotonic() - self.created_at))),
        }


class ClaudeAccountService:
    """Claude Code 订阅登录态、官方 setup-token 登录与退出。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._logins: dict[str, _LoginSession] = {}

    # --- 路径 / 令牌 ---

    @property
    def home(self) -> Path:
        return claude_account_home(self._settings)

    @property
    def token_path(self) -> Path:
        return self.home / "token"

    @property
    def cli_config_dir(self) -> Path:
        """登录过程给 CLI 的隔离配置目录，避免读写用户全局 `~/.claude`。"""
        return self.home / "cli-config"

    def binary_path(self) -> str:
        override = (os.environ.get("FLUX_CLAUDE_CODE_CLI_BINARY") or "").strip()
        if override:
            return override
        return shutil.which("claude") or "claude"

    def token(self) -> str | None:
        """读取订阅令牌；不存在或格式异常返回 None。绝不记录令牌内容。"""
        path = self.token_path
        try:
            if not path.is_file() or path.stat().st_size > _MAX_TOKEN_BYTES:
                return None
            value = path.read_text(encoding="utf-8").strip()
        except OSError:
            logger.warning("claude 订阅令牌不可读 path=%s", path)
            return None
        if not value.startswith("sk-ant-"):
            return None
        return value

    def has_token(self) -> bool:
        return self.token() is not None

    def _save_token(self, token: str) -> None:
        value = (token or "").strip()
        if not value.startswith("sk-ant-") or len(value) < 24:
            raise ValidationError("Claude 返回的订阅令牌格式无法识别")
        home = self.home
        home.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(OSError):
            os.chmod(home, 0o700)
        tmp = home / ".token.tmp"
        tmp.write_text(value + "\n", encoding="utf-8")
        secure_file(tmp)
        os.replace(tmp, self.token_path)
        secure_file(self.token_path)

    # --- 状态 ---

    def status(self) -> dict[str, Any]:
        binary = self.binary_path()
        has_token = self.has_token()
        payload: dict[str, Any] = {
            "logged_in": has_token,
            "auth_mode": "subscription" if has_token else None,
            "home": str(self.home),
            "binary": binary,
            "binary_found": shutil.which(binary) is not None,
            "created_at": None,
            "pending_login": self._latest_pending(),
        }
        if has_token:
            with contextlib.suppress(OSError):
                payload["created_at"] = datetime.fromtimestamp(
                    self.token_path.stat().st_mtime, tz=timezone.utc
                ).isoformat()
        return payload

    # --- 登录 ---

    async def start_login(self) -> dict[str, Any]:
        """启动官方 `claude setup-token`，等到授权链接出现或进程提前退出。"""
        self._reap()
        binary = self.binary_path()
        if shutil.which(binary) is None:
            raise ConfigurationError(
                "没有找到 Claude Code 命令行，无法登录订阅账号",
                details={
                    "binary": binary,
                    "hint": "安装 Claude Code，或设置 FLUX_CLAUDE_CODE_CLI_BINARY",
                },
            )
        existing = self._latest_pending()
        if existing is not None:
            return existing

        self.cli_config_dir.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(OSError):
            os.chmod(self.home, 0o700)
            os.chmod(self.cli_config_dir, 0o700)

        process, master_fd, using_pty = self._spawn(binary)
        session = _LoginSession(
            login_id=uuid.uuid4().hex[:12],
            process=process,
            master_fd=master_fd,
            using_pty=using_pty,
        )
        self._logins[session.login_id] = session
        logger.info(
            "claude 订阅登录开始 login=%s home=%s pid=%s pty=%s",
            session.login_id,
            self.home,
            getattr(process, "pid", None),
            using_pty,
        )
        session.pump = asyncio.create_task(self._pump(session))
        session.watchdog = asyncio.create_task(self._enforce_deadline(session))
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(session.ready.wait(), timeout=_PROMPT_TIMEOUT_SECONDS)
        if session.status in _PENDING_STATUSES and not session.url and not session.message:
            session.message = "等待 Claude Code 输出授权链接…"
        return session.public()

    def _spawn(self, binary: str) -> tuple[Any, int | None, bool]:
        """启动登录进程。优先 PTY（CLI 的交互式授权页需要 TTY），失败回退管道。"""
        env = self._login_env()
        argv = (binary, "setup-token")
        try:
            import pty
            import subprocess

            master_fd, slave_fd = pty.openpty()
            os.set_blocking(master_fd, False)
            process = subprocess.Popen(
                argv,
                stdin=slave_fd,
                stdout=slave_fd,
                stderr=slave_fd,
                env=env,
                cwd=str(self.cli_config_dir),
                start_new_session=True,
                close_fds=True,
            )
            os.close(slave_fd)
            return process, master_fd, True
        except (ImportError, OSError, ValueError) as exc:
            logger.warning("claude setup-token 无法创建 PTY，回退管道模式：%s", exc)
        try:
            import subprocess

            process = subprocess.Popen(
                argv,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                env=env,
                cwd=str(self.cli_config_dir),
                start_new_session=os.name != "nt",
                close_fds=True,
            )
            return process, None, False
        except OSError as exc:
            raise ConfigurationError(
                f"claude setup-token 进程启动失败：{exc}",
                details={"binary": binary},
            ) from exc

    def _login_env(self) -> dict[str, str]:
        # 清掉所有 Anthropic / Claude Code 凭据变量：否则 CLI 会判定"已登录"而跳过
        # setup-token，或让父进程的密钥通道混进订阅登录。
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("ANTHROPIC_", "CLAUDE_CODE_"))
        }
        env.update(
            {
                "CLAUDE_CONFIG_DIR": str(self.cli_config_dir),
                "TERM": "dumb",
                "NO_COLOR": "1",
                "BROWSER": "/bin/false" if os.name != "nt" else "",
            }
        )
        return env

    def login_state(self, login_id: str) -> dict[str, Any] | None:
        session = self._logins.get((login_id or "").strip())
        if session is None:
            return None
        if session.status in _PENDING_STATUSES and session.process.poll() is not None:
            self._settle(session, session.process.returncode)
        return session.public()

    def _latest_pending(self) -> dict[str, Any] | None:
        pending = [item for item in self._logins.values() if item.status in _PENDING_STATUSES]
        if not pending:
            return None
        return max(pending, key=lambda item: item.created_at).public()

    async def submit_code(self, login_id: str, code: str) -> dict[str, Any]:
        """把用户在浏览器里拿到的 `code#state` 粘回 CLI 的授权提示。"""
        session = self._logins.get((login_id or "").strip())
        if session is None:
            raise NotFoundError(
                f"登录会话不存在或已过期：{login_id}", details={"login_id": login_id}
            )
        value = (code or "").strip()
        if not value:
            raise ValidationError("授权码不能为空")
        if session.status not in _PENDING_STATUSES:
            raise ValidationError("这次登录已经结束，请重新发起")
        if self._write_input(session, value + "\r") is False:
            raise ConfigurationError("登录进程已退出，无法提交授权码")
        session.code_submitted = True
        if session.status == "starting":
            session.status = "pending"
        session.message = "已提交授权码，等待 Claude 验证…"
        session.ready.set()
        logger.info("claude 订阅登录已提交授权码 login=%s", session.login_id)
        return session.public()

    def _write_input(self, session: _LoginSession, text: str) -> bool:
        process = session.process
        try:
            if session.master_fd is not None:
                os.write(session.master_fd, text.encode("utf-8"))
                return True
            stdin = getattr(process, "stdin", None)
            if stdin is None:
                return False
            stdin.write(text.encode("utf-8"))
            stdin.flush()
            return True
        except (OSError, ValueError):
            return False

    async def _pump(self, session: _LoginSession) -> None:
        process = session.process
        try:
            while True:
                if session.master_fd is not None:
                    chunk = self._read_pty(session.master_fd)
                    if chunk:
                        self._consume(session, chunk)
                else:
                    stream = getattr(process, "stdout", None)
                    if stream is not None:
                        line = await asyncio.to_thread(stream.readline)
                        if line:
                            self._consume(session, line.decode("utf-8", errors="replace"))
                returncode = process.poll()
                if returncode is not None:
                    # 退出前把 PTY 里剩余的输出读干净
                    if session.master_fd is not None:
                        while True:
                            chunk = self._read_pty(session.master_fd)
                            if not chunk:
                                break
                            self._consume(session, chunk)
                    self._settle(session, returncode)
                    return
                await asyncio.sleep(0.05)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - 读流出错也要给前端落终态
            logger.exception("claude 订阅登录输出读取失败 login=%s", session.login_id)
            if session.status in _PENDING_STATUSES:
                session.status = "failed"
                session.message = "登录进程异常退出"
        finally:
            session.ready.set()
            if session.watchdog is not None:
                session.watchdog.cancel()

    @staticmethod
    def _read_pty(master_fd: int) -> str:
        try:
            data = os.read(master_fd, 65536)
        except BlockingIOError:
            return ""
        except OSError:
            return ""
        return data.decode("utf-8", errors="replace") if data else ""

    def _consume(self, session: _LoginSession, chunk: str) -> None:
        if not chunk:
            return
        # 只保留最近一段输出用于解析；令牌在保存后立刻从可展示文本里抹掉。
        session.output = (session.output + chunk)[-8000:]
        if not session.url:
            url = parse_authorize_url(session.output)
            if url:
                session.url = url
                if session.status == "starting":
                    session.status = "pending"
                    session.message = ""
                session.ready.set()
        if not session.token_captured:
            token = parse_subscription_token(session.output)
            if token:
                try:
                    self._save_token(token)
                except (OSError, ValidationError) as exc:
                    session.status = "failed"
                    session.message = f"订阅令牌保存失败：{exc}"
                    session.output = _TOKEN_RE.sub("[已隐藏令牌]", session.output)
                    session.ready.set()
                    return
                session.token_captured = True
                session.status = "success"
                session.message = ""
                # 成功输出里可能还带着令牌：不要留在内存/日志的可见文本里。
                session.output = _TOKEN_RE.sub("[已隐藏令牌]", session.output)
                session.ready.set()
                logger.info("claude 订阅登录成功 login=%s", session.login_id)
                asyncio.create_task(self._stop_after_success(session))

    async def _stop_after_success(self, session: _LoginSession) -> None:
        """令牌已落盘，给 CLI 一点时间收尾后结束进程，避免登录进程常驻。"""
        await asyncio.sleep(1.0)
        if session.process.poll() is None:
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.terminate()
            await asyncio.sleep(1.0)
        if session.process.poll() is None:
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.kill()

    def _settle(self, session: _LoginSession, returncode: int | None) -> None:
        if session.status not in _PENDING_STATUSES:
            return
        if session.token_captured and self.has_token():
            session.status = "success"
            session.message = ""
            return
        # 有些 CLI 版本在退出前才打印令牌；再扫一遍完整输出。
        token = parse_subscription_token(session.output)
        if token:
            try:
                self._save_token(token)
                session.token_captured = True
                session.status = "success"
                session.message = ""
                session.output = _TOKEN_RE.sub("[已隐藏令牌]", session.output)
                logger.info("claude 订阅登录成功 login=%s rc=%s", session.login_id, returncode)
                return
            except (OSError, ValidationError):
                pass
        session.status = "failed"
        session.message = _redact(session.output) or f"claude setup-token 退出码 {returncode}"
        logger.warning(
            "claude 订阅登录失败 login=%s rc=%s tail=%s",
            session.login_id,
            returncode,
            _redact(session.output),
        )

    async def _enforce_deadline(self, session: _LoginSession) -> None:
        with contextlib.suppress(asyncio.CancelledError):
            await asyncio.sleep(_LOGIN_MAX_SECONDS)
            if session.status not in _PENDING_STATUSES:
                return
            logger.warning("claude 订阅登录超时 login=%s", session.login_id)
            session.message = "登录超时（20 分钟），请重新发起"
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.terminate()
            with contextlib.suppress(asyncio.TimeoutError, ProcessLookupError, OSError):
                await asyncio.wait_for(asyncio.to_thread(session.process.wait), timeout=5)
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.kill()
            self._settle(session, -1)

    def _reap(self) -> None:
        now = time.monotonic()
        for login_id, session in list(self._logins.items()):
            finished = session.status not in _PENDING_STATUSES
            if finished and now - session.created_at > _LOGIN_KEEP_SECONDS:
                self._logins.pop(login_id, None)

    # --- 退出 ---

    async def logout(self) -> dict[str, Any]:
        """删除 Flux 保存的订阅令牌，并终止进行中的登录。"""
        for session in list(self._logins.values()):
            if session.status in _PENDING_STATUSES:
                with contextlib.suppress(ProcessLookupError, OSError):
                    session.process.terminate()
                session.status = "cancelled"
                session.message = "已退出登录"
        removed = False
        try:
            if self.token_path.exists():
                self.token_path.unlink()
                removed = True
        except OSError as exc:
            raise ConfigurationError(
                "删除 Claude 订阅登录态失败", details={"path": str(self.token_path)}
            ) from exc
        # 登录过程的隔离配置也一并清掉（只删 Flux 自己的目录）。
        with contextlib.suppress(OSError):
            shutil.rmtree(self.cli_config_dir)
        logger.info("claude 订阅已退出 removed=%s path=%s", removed, self.token_path)
        return {"removed": removed, "status": self.status()}


def claude_account_env(settings: Settings) -> dict[str, str]:
    """Run 注入：有订阅令牌时返回 `CLAUDE_CODE_OAUTH_TOKEN` 环境变量。

    这是 Claude Code 官方支持的无头/无人值守订阅通道。令牌只进子进程环境，
    不写 run 配置、不进日志。
    """
    token = ClaudeAccountService(settings).token()
    if not token:
        return {}
    return {"CLAUDE_CODE_OAUTH_TOKEN": token}


__all__ = [
    "ClaudeAccountService",
    "claude_account_env",
    "claude_account_home",
    "parse_authorize_url",
    "parse_subscription_token",
    "strip_terminal_escapes",
]
