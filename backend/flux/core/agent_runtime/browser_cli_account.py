"""官方 CLI 浏览器登录的账号态管理（Cursor / 谷歌 Antigravity 共用）。

Cursor 与 Antigravity 的个人订阅登录都由**官方 CLI 自己**完成：

- Cursor：`NO_OPEN_BROWSER=1 agent login` 打印官方授权链接，用户在手机/浏览器完成
  授权，登录态由官方 CLI 保存；`agent status` / `agent logout` 查状态与退出。
- Antigravity：`agy login` 拉起官方浏览器 OAuth，凭据留在官方 CLI 的安全存储；
  Flux 不实现、也不逆向 Google OAuth，更不保存任何第三方令牌。

本模块只做一件事：在**用户电脑**上拉起官方登录命令、把它打印的授权链接交给客户端、
轮询进程与 `status` 的结果。响应与日志里不出现 access token / refresh token / cookie /
API key；Flux 只在自己的目录里写一个「曾经由 Flux 成功完成过登录」的非机密标记，
用于官方 `status` 命令不可用时的兜底判断。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import re
import shlex
import shutil
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.adapters.base import run_probe
from flux.core.agent_runtime.platforms import executable_argv, popen_kwargs, secure_file
from flux.errors import ConfigurationError, NotFoundError, ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

#: 普通 URL：官方 CLI 可能把授权链接做成终端超链接，也可能是裸文本
_URL_RE = re.compile(r"https://[^\s\x1b\x07\"'<>]+")
#: ANSI / OSC 控制序列
_ESCAPE_RE = re.compile(
    r"\x1b\][^\x07]*(?:\x07|\x1b\\)"  # OSC
    r"|\x1b\[[0-9;?]*[ -/]*[@-~]"  # CSI
    r"|\x1b[@-_]"  # 其它双字节转义
)
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
#: 失败摘要里要抹掉的敏感片段（CLI 正常不会打印；防御性处理）
_SECRET_RE = re.compile(
    r"(?:sk-[A-Za-z0-9._-]{8,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})"
)

#: 等官方 CLI 打印授权链接的上限；正常网络下是秒级
_PROMPT_TIMEOUT_SECONDS = 25.0
#: 一次浏览器登录的最长寿命（官方授权码通常 10 分钟过期，留余量兜底）
_LOGIN_MAX_SECONDS = 20 * 60.0
#: 已结束的登录会话在内存里保留多久（够前端轮询到终态）
_LOGIN_KEEP_SECONDS = 10 * 60.0
#: 状态探测缓存：连接器列表会频繁读取，避免每次都同步拉起 CLI
_STATUS_CACHE_SECONDS = 15.0

_PENDING_STATUSES = ("starting", "pending")

_CODE_PROMPT_MARKERS = (
    "paste",
    "authorization code",
    "authorisation code",
    "enter code",
    "verification code",
    "授权码",
    "验证码",
)
_SUCCESS_MARKERS = (
    "logged in",
    "logged-in",
    "authenticated",
    "signed in",
    "login successful",
    "success",
    "已登录",
    "登录成功",
)
_FAILURE_MARKERS = (
    "not logged in",
    "failed",
    "failure",
    "cancelled",
    "canceled",
    "denied",
    "error",
    "未登录",
    "失败",
    "取消",
)


@dataclass(frozen=True)
class BrowserCliAccountConfig:
    """一个「官方 CLI 浏览器登录」运行时的差异配置。"""

    runtime_id: str
    display_name: str
    binary_env: str
    default_binary: str
    home_setting: str
    login_args: tuple[str, ...]
    logout_args: tuple[str, ...]
    status_args: tuple[str, ...]
    url_domains: tuple[str, ...]
    install_hint: str
    #: 登录前要清掉的 API key 环境变量：否则官方 CLI 可能判定「已有凭据」而跳过登录，
    #: 或者把 Flux 进程里的密钥通道混进订阅登录。
    api_key_envs: tuple[str, ...] = ()
    ok_markers: tuple[str, ...] = _SUCCESS_MARKERS
    missing_markers: tuple[str, ...] = (
        "not logged in",
        "logged out",
        "未登录",
        "not authenticated",
    )


def strip_ansi(text: str) -> str:
    """去掉终端控制序列，保留可读文本。"""
    cleaned = _ESCAPE_RE.sub("", text or "")
    cleaned = _CONTROL_RE.sub("", cleaned)
    return cleaned.replace("\r", "\n")


def parse_login_url(text: str, domains: tuple[str, ...]) -> str:
    """从官方 CLI 输出里取授权页链接；只接受配置里的官方域名。

    终端宽度较窄时链接可能折行，这里取同一域名下最长的一条；取不到返回空串。
    """
    plain = strip_ansi(text or "")
    candidates: list[str] = []
    for match in _URL_RE.finditer(plain):
        value = match.group(0).rstrip(".,;:)]}>")
        host = ""
        with contextlib.suppress(ValueError):
            host = value.split("://", 1)[1].split("/", 1)[0].split(":", 1)[0].lower()
        if not host:
            continue
        if any(host == domain or host.endswith(f".{domain}") for domain in domains):
            candidates.append(value)
    if not candidates:
        return ""
    return max(candidates, key=len)


def parse_needs_code(text: str) -> bool:
    """官方 CLI 是否在要求用户回填授权码（而不是 API token）。"""
    lowered = strip_ansi(text or "").lower()
    return any(marker in lowered for marker in _CODE_PROMPT_MARKERS)


def _redact(text: str, limit: int = 400) -> str:
    """给用户看的失败摘要：去控制序列、抹掉可能的令牌、只留最后几行。"""
    cleaned = _SECRET_RE.sub("[已隐藏令牌]", strip_ansi(text))
    # 失败输出里可能带一次性授权链接（含 challenge/token）；链接已单独返回，摘要里不再重复。
    cleaned = _URL_RE.sub("[链接已隐藏]", cleaned)
    lines = [line.strip() for line in cleaned.splitlines() if line.strip()]
    return " | ".join(lines[-4:])[-limit:]


def _env_args(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    """允许部署环境覆盖官方子命令（CLI 版本差异时不必改代码）。"""
    raw = (os.environ.get(name) or "").strip()
    if not raw:
        return default
    try:
        parsed = tuple(shlex.split(raw))
    except ValueError:
        logger.warning("忽略无法解析的 %s=%r", name, raw)
        return default
    return parsed or default


def account_home(settings: Settings, config: BrowserCliAccountConfig) -> Path:
    raw = str(getattr(settings, config.home_setting, "") or "").strip()
    if raw:
        return Path(raw).expanduser()
    return Path.home() / ".flux" / f"{config.runtime_id}-account"


class BrowserCliAccountService:
    """官方 CLI 登录态、浏览器授权与退出（Cursor / Antigravity 共用实现）。"""

    def __init__(
        self,
        settings: Settings,
        config: BrowserCliAccountConfig,
        *,
        probe_runner: Any = run_probe,
    ) -> None:
        self._settings = settings
        self._config = config
        self._probe = probe_runner
        self._logins: dict[str, _LoginSession] = {}
        self._status_cache: tuple[float, dict[str, Any]] | None = None
        #: 登录成功后的回调（容器注入：刷新 installation 到 READY，让它进入可选 Agent 列表）
        self._activation: Any = None

    def set_activation(self, callback: Any) -> None:
        self._activation = callback

    # --- 路径 / 可执行文件 ---

    @property
    def config(self) -> BrowserCliAccountConfig:
        return self._config

    @property
    def home(self) -> Path:
        return account_home(self._settings, self._config)

    @property
    def marker_path(self) -> Path:
        return self.home / "connected.json"

    def binary_path(self) -> str:
        override = (os.environ.get(self._config.binary_env) or "").strip()
        if override:
            return override
        return shutil.which(self._config.default_binary) or self._config.default_binary

    def has_marker(self) -> bool:
        return self.marker_path.is_file()

    def _login_args(self) -> tuple[str, ...]:
        return _env_args(
            f"FLUX_{self._config.runtime_id.upper()}_LOGIN_ARGS", self._config.login_args
        )

    def _logout_args(self) -> tuple[str, ...]:
        return _env_args(
            f"FLUX_{self._config.runtime_id.upper()}_LOGOUT_ARGS", self._config.logout_args
        )

    def _status_args(self) -> tuple[str, ...]:
        return _env_args(
            f"FLUX_{self._config.runtime_id.upper()}_STATUS_ARGS", self._config.status_args
        )

    # --- 登录态 ---

    def _probe_auth(self) -> str:
        """问官方 CLI 当前是否已登录："ok" / "missing" / "unknown"。"""
        binary = self.binary_path()
        if shutil.which(binary) is None:
            return "missing"
        try:
            _, output = self._probe((binary, *self._status_args()))
        except (OSError, TimeoutError):
            return "unknown"
        text = strip_ansi(output).lower()
        if any(marker in text for marker in self._config.missing_markers):
            return "missing"
        if any(marker in text for marker in self._config.ok_markers):
            return "ok"
        if not text.strip():
            return "unknown"
        # 退出码在这一层不可见；无标记但输出像账号信息时仍按 unknown，不假装已登录。
        return "unknown"

    def status(self, *, probe: bool = True) -> dict[str, Any]:
        """当前账号态；不返回任何令牌/凭据内容。

        `probe=False` 用于连接器列表的轻量读取（只看 Flux 的成功标记）；
        账号页与登录轮询用默认值，会问一次官方 CLI。
        """
        cacheable = probe
        if cacheable and self._status_cache is not None:
            age = time.monotonic() - self._status_cache[0]
            if age < _STATUS_CACHE_SECONDS:
                return dict(self._status_cache[1])

        binary = self.binary_path()
        binary_found = shutil.which(binary) is not None
        marker = self.has_marker()
        # 没装官方 CLI 就不可能「已登录」；旧标记只说明曾经装过/登过。
        auth = "missing" if not binary_found else (self._probe_auth() if probe else "unknown")
        logged_in = binary_found and (auth == "ok" or (auth == "unknown" and marker))
        stale = bool(binary_found and marker and auth == "missing")
        payload: dict[str, Any] = {
            "logged_in": logged_in,
            "auth_mode": "official-cli" if logged_in else None,
            "home": str(self.home),
            "binary": binary,
            "binary_found": binary_found,
            "official_status": auth,
            "stale_login": stale,
            "pending_login": self._latest_pending(),
        }
        if marker:
            with contextlib.suppress(OSError, ValueError):
                data = json.loads(self.marker_path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    payload["last_login_at"] = data.get("at")
        if cacheable:
            self._status_cache = (time.monotonic(), dict(payload))
        return payload

    def _write_marker(self) -> None:
        home = self.home
        home.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(OSError):
            os.chmod(home, 0o700)
        tmp = home / ".connected.json.tmp"
        tmp.write_text(
            json.dumps(
                {
                    "runtime": self._config.runtime_id,
                    "at": datetime.now(tz=timezone.utc).isoformat(),
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        secure_file(tmp)
        os.replace(tmp, self.marker_path)
        secure_file(self.marker_path)

    # --- 登录 ---

    async def start_login(self) -> dict[str, Any]:
        """在电脑上拉起官方 CLI 登录，等到授权链接出现或进程提前退出。"""
        self._reap()
        self._status_cache = None
        binary = self.binary_path()
        if shutil.which(binary) is None:
            raise ConfigurationError(
                f"没有找到 {self._config.display_name} 命令行，无法发起官方授权。"
                f"{self._config.install_hint}",
                details={
                    "binary": binary,
                    "hint": self._config.install_hint,
                    "runtime": self._config.runtime_id,
                },
            )
        existing = self._latest_pending()
        if existing is not None:
            return existing

        home = self.home
        home.mkdir(parents=True, exist_ok=True)
        with contextlib.suppress(OSError):
            os.chmod(home, 0o700)
        try:
            process = await asyncio.create_subprocess_exec(
                *executable_argv((binary, *self._login_args())),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                env=self._login_env(),
                cwd=str(home),
                **popen_kwargs(),
            )
        except OSError as exc:
            raise ConfigurationError(
                f"{self._config.display_name} 登录进程启动失败：{exc}",
                details={"binary": binary, "runtime": self._config.runtime_id},
            ) from exc

        session = _LoginSession(login_id=uuid.uuid4().hex[:12], process=process)
        self._logins[session.login_id] = session
        logger.info(
            "%s 账号登录开始 login=%s home=%s pid=%s",
            self._config.runtime_id,
            session.login_id,
            home,
            getattr(process, "pid", None),
        )
        session.pump = asyncio.create_task(self._pump(session))
        session.watchdog = asyncio.create_task(self._enforce_deadline(session))
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(session.ready.wait(), timeout=_PROMPT_TIMEOUT_SECONDS)
        if session.status in _PENDING_STATUSES and not session.url and not session.message:
            session.message = f"等待 {self._config.display_name} 输出授权链接…"
        return session.public()

    def login_state(self, login_id: str) -> dict[str, Any] | None:
        session = self._logins.get((login_id or "").strip())
        if session is None:
            return None
        if session.status in _PENDING_STATUSES and session.process.returncode is not None:
            self._settle(session, session.process.returncode)
        return session.public()

    async def submit_code(self, login_id: str, code: str) -> dict[str, Any]:
        """仅当官方 CLI 自己要求回填授权码时使用；不是 API token 入口。"""
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
        stdin = getattr(session.process, "stdin", None)
        if stdin is None:
            raise ConfigurationError("登录进程没有可写入的输入通道")
        try:
            stdin.write((value + "\n").encode("utf-8"))
            await stdin.drain()
        except (OSError, ValueError) as exc:
            raise ConfigurationError("登录进程已退出，无法提交授权码") from exc
        session.code_submitted = True
        if session.status == "starting":
            session.status = "pending"
        session.message = "已提交授权码，等待官方 CLI 验证…"
        session.ready.set()
        logger.info("%s 账号登录已提交授权码 login=%s", self._config.runtime_id, session.login_id)
        return session.public()

    def _login_env(self) -> dict[str, str]:
        env = {
            key: value for key, value in os.environ.items() if key not in self._config.api_key_envs
        }
        env.update(
            {
                # 不让 CLI 在服务器/容器里尝试拉起桌面浏览器：授权链接交给客户端打开
                "NO_OPEN_BROWSER": "1",
                "BROWSER": "" if os.name == "nt" else "/bin/false",
                "TERM": "dumb",
                "NO_COLOR": "1",
            }
        )
        return env

    async def _pump(self, session: _LoginSession) -> None:
        process = session.process
        try:
            stream = process.stdout
            while stream is not None:
                raw = await stream.readline()
                if not raw:
                    break
                self._consume(session, raw.decode("utf-8", errors="replace"))
            returncode = await process.wait()
            self._settle(session, returncode)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - 读流出错也要给前端落终态
            logger.exception(
                "%s 账号登录输出读取失败 login=%s", self._config.runtime_id, session.login_id
            )
            if session.status in _PENDING_STATUSES:
                session.status = "failed"
                session.message = "登录进程异常退出"
        finally:
            session.ready.set()
            if session.watchdog is not None:
                session.watchdog.cancel()

    def _consume(self, session: _LoginSession, chunk: str) -> None:
        if not chunk:
            return
        session.output = (session.output + chunk)[-8000:]
        if not session.url:
            url = parse_login_url(session.output, self._config.url_domains)
            if url:
                session.url = url
                if session.status == "starting":
                    session.status = "pending"
                    session.message = ""
                session.ready.set()
        if not session.needs_code and parse_needs_code(session.output):
            session.needs_code = True
            session.message = "请在浏览器完成授权，并把官方页面给出的授权码填回这里。"
            session.ready.set()

    def _settle(self, session: _LoginSession, returncode: int | None) -> None:
        if session.status not in _PENDING_STATUSES:
            return
        if session.timed_out:
            session.status = "failed"
            return
        text = strip_ansi(session.output).lower()
        if returncode == 0 and not any(marker in text for marker in _FAILURE_MARKERS):
            auth = self._probe_auth()
            if auth == "missing":
                session.status = "failed"
                session.message = (
                    f"{self._config.display_name} 官方 CLI 报告未登录，请重新发起授权。"
                )
                return
            try:
                self._write_marker()
            except OSError as exc:
                session.status = "failed"
                session.message = f"保存登录标记失败：{exc}"
                return
            session.status = "success"
            session.message = ""
            self._status_cache = None
            logger.info("%s 账号登录成功 login=%s", self._config.runtime_id, session.login_id)
            self._schedule_activation()
            return
        session.status = "failed"
        detail = _redact(session.output) or (
            f"{self._config.display_name} 登录进程退出码 {returncode}"
        )
        session.message = f"{self._config.display_name} 官方授权失败：{detail}"
        logger.warning(
            "%s 账号登录失败 login=%s rc=%s tail=%s",
            self._config.runtime_id,
            session.login_id,
            returncode,
            _redact(session.output),
        )

    def _schedule_activation(self) -> None:
        """登录成功 → 让 installation 前进到 READY，使该 runtime 立刻可选。"""
        callback = self._activation
        if callback is None:
            return

        async def activate() -> None:
            try:
                result = callback()
                if asyncio.iscoroutine(result):
                    await result
            except Exception:  # noqa: BLE001 - 激活失败不影响登录本身
                logger.warning(
                    "%s 登录后激活 installation 失败", self._config.runtime_id, exc_info=True
                )

        try:
            task = asyncio.create_task(activate())
        except RuntimeError:
            logger.warning(
                "没有事件循环，跳过 %s 登录后的 installation 激活", self._config.runtime_id
            )
            return
        task.add_done_callback(lambda _: None)

    async def _enforce_deadline(self, session: _LoginSession) -> None:
        with contextlib.suppress(asyncio.CancelledError):
            await asyncio.sleep(_LOGIN_MAX_SECONDS)
            if session.status not in _PENDING_STATUSES:
                return
            logger.warning("%s 账号登录超时 login=%s", self._config.runtime_id, session.login_id)
            session.timed_out = True
            session.message = "登录超时（20 分钟），请重新发起"
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.terminate()
            with contextlib.suppress(asyncio.TimeoutError, ProcessLookupError, OSError):
                await asyncio.wait_for(session.process.wait(), timeout=5)
            with contextlib.suppress(ProcessLookupError, OSError):
                session.process.kill()
            self._settle(session, -1)

    def _latest_pending(self) -> dict[str, Any] | None:
        pending = [item for item in self._logins.values() if item.status in _PENDING_STATUSES]
        if not pending:
            return None
        return max(pending, key=lambda item: item.created_at).public()

    def _reap(self) -> None:
        now = time.monotonic()
        for login_id, session in list(self._logins.items()):
            finished = session.status not in _PENDING_STATUSES
            if finished and now - session.created_at > _LOGIN_KEEP_SECONDS:
                self._logins.pop(login_id, None)

    # --- 退出 ---

    async def logout(self) -> dict[str, Any]:
        """调官方 CLI 退出，并清掉 Flux 自己的非机密标记；绝不碰官方凭据文件。"""
        for session in list(self._logins.values()):
            if session.status in _PENDING_STATUSES:
                with contextlib.suppress(ProcessLookupError, OSError):
                    session.process.terminate()
                session.status = "cancelled"
                session.message = "已退出登录"
        binary = self.binary_path()
        cli_ok = False
        if shutil.which(binary) is not None:
            try:
                returncode, _ = await asyncio.to_thread(self._probe, (binary, *self._logout_args()))
                cli_ok = returncode == 0
            except (OSError, TimeoutError):
                logger.warning("%s 官方 CLI 退出命令执行失败", self._config.runtime_id)
        removed = False
        try:
            if self.marker_path.exists():
                self.marker_path.unlink()
                removed = True
        except OSError as exc:
            raise ConfigurationError(
                f"删除 {self._config.display_name} 登录标记失败",
                details={"path": str(self.marker_path)},
            ) from exc
        self._status_cache = None
        logger.info("%s 账号已退出 removed=%s cli_ok=%s", self._config.runtime_id, removed, cli_ok)
        return {
            "removed": removed or cli_ok,
            "status": self.status(probe=False),
        }


@dataclass
class _LoginSession:
    login_id: str
    process: Any
    created_at: float = field(default_factory=time.monotonic)
    status: str = "starting"
    url: str = ""
    message: str = ""
    output: str = ""
    needs_code: bool = False
    code_submitted: bool = False
    timed_out: bool = False
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    pump: asyncio.Task[None] | None = None
    watchdog: asyncio.Task[None] | None = None

    def public(self) -> dict[str, Any]:
        return {
            "login_id": self.login_id,
            "status": self.status,
            "url": self.url,
            "message": self.message,
            "needs_code": self.needs_code,
            "expires_in": max(0, int(_LOGIN_MAX_SECONDS - (time.monotonic() - self.created_at))),
        }


__all__ = [
    "BrowserCliAccountConfig",
    "BrowserCliAccountService",
    "account_home",
    "parse_login_url",
    "parse_needs_code",
    "strip_ansi",
]
