"""个人微信：Flux 自己实现的腾讯官方 iLink / 微信 ClawBot 协议客户端。

协议依据（2026-11 拉取的官方仓库）：

- https://github.com/Tencent/openclaw-weixin `docs/protocol_zh_CN.md`
- `src/api/api.ts`、`src/auth/login-qr.ts`、`src/monitor/monitor.ts`、`src/messaging/send.ts`

Flux **不引入** OpenClaw、不使用 npm 插件、不把 Flux 注册成 OpenClaw 渠道；
这里只是一份 Flux 自己的 Python 实现：

- 只出站 HTTPS（扫码绑定 + getupdates 长轮询），不打开公网入站端口；
- 二维码绑定、`get_updates_buf` 游标、context_token 都持久化在 0600 文件里；
- 只处理绑定账号的 1:1 文本消息，群消息与其它发送者直接忽略；
- 回复必须原样带回入站消息的 context_token，缺失时绝不编造；
- 错误码 -14 视为登录过期，提示重新扫码，不静默失败。
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import secrets
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from flux.core.im.assistant import ImAssistant
from flux.core.im.store import ImConnectorStore
from flux.core.supervisor.channels import MAX_TEXT_CHARS
from flux.logging import get_logger

logger = get_logger(__name__)

__all__ = [
    "WechatApiError",
    "WechatIlinkClient",
    "WechatImService",
    "WechatLoginManager",
    "WechatPoller",
    "WechatSessionExpired",
]

STALE_TOKEN_ERRCODE = -14
_QR_REFRESH_LIMIT = 3
_MESSAGE_TYPE_USER = 1
_MESSAGE_TYPE_BOT = 2
_ITEM_TYPE_TEXT = 1
_ITEM_TYPE_VOICE = 3


class WechatApiError(RuntimeError):
    """iLink 请求失败（HTTP 或业务码）。消息中不包含 token。"""


class WechatSessionExpired(WechatApiError):
    """官方返回 -14：会话过期，需要重新扫码。"""


# ---------------------------------------------------------------- 协议客户端


class WechatIlinkClient:
    """官方 iLink HTTP JSON 协议的最小客户端。"""

    def __init__(
        self,
        *,
        api_base: str = "https://ilinkai.weixin.qq.com",
        channel_version: str = "0.9.19",
        bot_agent: str = "Flux/0.9.19",
        bot_type: int = 3,
        poll_timeout_seconds: float = 35.0,
        qr_poll_timeout_seconds: float = 35.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.api_base = api_base.rstrip("/")
        self.channel_version = channel_version
        self.bot_agent = bot_agent
        self.bot_type = int(bot_type)
        self.poll_timeout = max(5.0, float(poll_timeout_seconds))
        self.qr_poll_timeout = max(5.0, float(qr_poll_timeout_seconds))
        self._client = client
        self._owns_client = client is None

    # --- 二维码绑定 ---

    async def get_bot_qrcode(self) -> dict[str, Any]:
        response = await self._request(
            "POST",
            "/ilink/bot/get_bot_qrcode",
            params={"bot_type": self.bot_type},
            headers=self._headers(auth_token=None, json_body=True),
            json={"local_token_list": []},
            timeout=15.0,
        )
        return _json(response, "获取微信绑定二维码")

    async def get_qrcode_status(
        self,
        qrcode: str,
        *,
        verify_code: str | None = None,
        base_url: str | None = None,
    ) -> dict[str, Any]:
        params = {"qrcode": qrcode}
        if verify_code:
            params["verify_code"] = verify_code
        response = await self._request(
            "GET",
            "/ilink/bot/get_qrcode_status",
            params=params,
            headers=self._headers(auth_token=None, json_body=False, base_url=base_url),
            timeout=self.qr_poll_timeout,
        )
        return _json(response, "查询微信绑定状态")

    # --- Bot API ---

    async def get_updates(
        self, *, token: str, cursor: str, base_url: str | None = None
    ) -> dict[str, Any]:
        response = await self._request(
            "POST",
            "/ilink/bot/getupdates",
            headers=self._headers(auth_token=token, json_body=True, base_url=base_url),
            json={"get_updates_buf": cursor or "", "base_info": self._base_info()},
            timeout=self.poll_timeout,
        )
        return _json(response, "收取微信消息", allow_errcode=True)

    async def send_text(
        self,
        *,
        token: str,
        to_user_id: str,
        text: str,
        context_token: str,
        base_url: str | None = None,
    ) -> dict[str, Any]:
        if not (context_token or "").strip():
            # 官方限制：回复必须回带入站消息的 context_token。缺失时宁可失败，
            # 也不编造一个 token 去试探服务端。
            raise WechatApiError("缺少 context_token，无法回复微信消息")
        body = {
            "msg": {
                "from_user_id": "",
                "to_user_id": to_user_id,
                "client_id": f"flux-wechat-{uuid.uuid4().hex}",
                "message_type": _MESSAGE_TYPE_BOT,
                "message_state": 2,
                "context_token": context_token,
                "item_list": [
                    {
                        "type": _ITEM_TYPE_TEXT,
                        "text_item": {"text": (text or "")[:MAX_TEXT_CHARS]},
                    }
                ],
            },
            "base_info": self._base_info(),
        }
        response = await self._request(
            "POST",
            "/ilink/bot/sendmessage",
            headers=self._headers(auth_token=token, json_body=True, base_url=base_url),
            json=body,
            timeout=15.0,
        )
        return _json(response, "发送微信消息", allow_errcode=True)

    async def notify_start(self, *, token: str, base_url: str | None = None) -> None:
        await self._notify("/ilink/bot/msg/notifystart", token, base_url)

    async def notify_stop(self, *, token: str, base_url: str | None = None) -> None:
        await self._notify("/ilink/bot/msg/notifystop", token, base_url)

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
        self._client = None

    # --- 内部 ---

    async def _notify(self, path: str, token: str, base_url: str | None) -> None:
        try:
            response = await self._request(
                "POST",
                path,
                headers=self._headers(auth_token=token, json_body=True, base_url=base_url),
                json={"base_info": self._base_info()},
                timeout=10.0,
            )
            _json(response, "通知微信后端")
        except Exception as exc:  # noqa: BLE001 - 生命周期通知失败不阻断主流程
            logger.debug("微信 iLink 生命周期通知失败 path=%s err=%s", path, type(exc).__name__)

    def _base_info(self) -> dict[str, str]:
        return {"channel_version": self.channel_version, "bot_agent": self.bot_agent}

    def _common_headers(self) -> dict[str, str]:
        return {
            "iLink-App-Id": "bot",
            "iLink-App-ClientVersion": str(_client_version(self.channel_version)),
        }

    def _headers(
        self,
        *,
        auth_token: str | None,
        json_body: bool,
        base_url: str | None = None,
    ) -> dict[str, str]:
        headers = self._common_headers()
        if json_body:
            headers["Content-Type"] = "application/json"
            headers["AuthorizationType"] = "ilink_bot_token"
            headers["X-WECHAT-UIN"] = base64.b64encode(
                str(secrets.randbits(32)).encode("ascii")
            ).decode("ascii")
        if auth_token:
            headers["Authorization"] = f"Bearer {auth_token}"
        return headers

    async def _request(
        self,
        method: str,
        path: str,
        *,
        base_url: str | None = None,
        **kwargs: Any,
    ) -> httpx.Response:
        client = self._client
        if client is None:
            client = httpx.AsyncClient()
            self._client = client
            self._owns_client = True
        base = (base_url or self.api_base).rstrip("/")
        _ensure_outbound_https(base)
        return await client.request(method, f"{base}{path}", **kwargs)


def _ensure_outbound_https(base: str) -> None:
    """iLink 只走出站 HTTPS；本机回环调试除外，其余 http 一律拒绝。"""
    from urllib.parse import urlparse

    parsed = urlparse(base)
    if parsed.scheme == "https":
        return
    if parsed.scheme == "http" and (parsed.hostname or "") in {"127.0.0.1", "localhost", "::1"}:
        return
    raise WechatApiError("微信 iLink 只允许 HTTPS 出站；请检查 wechat_im_api_base / baseurl 配置")


def _client_version(version: str) -> int:
    parts: list[int] = []
    for part in (version or "0.0.0").split(".")[:3]:
        try:
            parts.append(int("".join(ch for ch in part if ch.isdigit()) or 0))
        except ValueError:
            parts.append(0)
    while len(parts) < 3:
        parts.append(0)
    major, minor, patch = (value & 0xFF for value in parts[:3])
    return (major << 16) | (minor << 8) | patch


def _json(
    response: httpx.Response,
    label: str,
    *,
    allow_errcode: bool = False,
) -> dict[str, Any]:
    try:
        data = response.json()
    except ValueError as exc:
        raise WechatApiError(f"{label}失败：响应不是 JSON（HTTP {response.status_code}）") from exc
    if not isinstance(data, dict):
        raise WechatApiError(f"{label}失败：响应结构异常（HTTP {response.status_code}）")
    if response.status_code >= 400:
        raise WechatApiError(f"{label}失败：HTTP {response.status_code}")
    ret = data.get("ret")
    errcode = data.get("errcode")
    codes = [code for code in (ret, errcode) if isinstance(code, int) and code != 0]
    if STALE_TOKEN_ERRCODE in codes:
        raise WechatSessionExpired(f"{label}失败：登录已过期（-14），请重新扫码")
    if codes:
        code = codes[0]
        if allow_errcode or ret not in (None, 0):
            raise WechatApiError(f"{label}失败：ret/errcode={code}")
    return data


# ---------------------------------------------------------------- 二维码绑定


@dataclass
class _Login:
    login_id: str
    qrcode: str
    qrcode_image: str
    matrix: list[str]
    expires_at: float
    state: str = "pending"
    message: str = "等待扫码"
    needs_verify_code: bool = False
    pending_code: str | None = None
    poll_base: str | None = None
    refresh_count: int = 0
    error: str = ""
    code_event: asyncio.Event = field(default_factory=asyncio.Event)

    def public(self) -> dict[str, Any]:
        # 注意：qrcode 原值（可换取绑定凭证）不返回给客户端；
        # 只返回用于展示的图片内容/矩阵与备份链接。
        backup_url = (
            self.qrcode_image if self.qrcode_image.startswith(("http://", "https://")) else None
        )
        return {
            "login_id": self.login_id,
            "state": self.state,
            "message": self.message,
            "qrcode_image": self.qrcode_image,
            "qrcode_matrix": list(self.matrix),
            "backup_url": backup_url,
            "expires_at": int(self.expires_at),
            "needs_verify_code": self.needs_verify_code,
            "error": self.error or None,
        }

    @property
    def active(self) -> bool:
        return self.state in {"pending", "scaned", "need_verifycode"}


class WechatLoginManager:
    """二维码绑定会话：取码、轮询状态、PIN、保存凭据。"""

    def __init__(
        self,
        *,
        client: WechatIlinkClient,
        store: ImConnectorStore,
        on_connected: Callable[[], Awaitable[None]] | None = None,
        qr_ttl_seconds: int = 300,
        auto_poll: bool = True,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.client = client
        self.store = store
        self.on_connected = on_connected
        self.qr_ttl_seconds = max(60, int(qr_ttl_seconds))
        self.auto_poll = auto_poll
        self._sleep = sleep
        self._logins: dict[str, _Login] = {}
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

    async def start_login(self) -> dict[str, Any]:
        data = await self.client.get_bot_qrcode()
        qrcode = str(data.get("qrcode") or "").strip()
        image = str(data.get("qrcode_img_content") or "").strip()
        if not qrcode or not image:
            raise WechatApiError("官方没有返回二维码，请稍后再试")
        login = _Login(
            login_id=uuid.uuid4().hex,
            qrcode=qrcode,
            qrcode_image=image,
            matrix=_qr_matrix(image),
            expires_at=time.time() + self.qr_ttl_seconds,
        )
        async with self._lock:
            self._logins[login.login_id] = login
        if self.auto_poll:
            self._tasks[login.login_id] = asyncio.create_task(
                self._poll_loop(login.login_id), name=f"flux-wechat-login-{login.login_id}"
            )
        logger.info("微信 iLink 绑定二维码已生成 login_id=%s", login.login_id)
        return login.public()

    def status(self, login_id: str) -> dict[str, Any] | None:
        login = self._logins.get(login_id)
        return login.public() if login is not None else None

    def latest(self) -> dict[str, Any] | None:
        active = [login for login in self._logins.values() if login.active]
        if not active:
            return None
        return max(active, key=lambda item: item.expires_at).public()

    def submit_verify_code(self, login_id: str, code: str) -> dict[str, Any]:
        login = self._logins.get(login_id)
        if login is None:
            raise WechatApiError("绑定会话不存在或已结束，请重新获取二维码")
        value = "".join(ch for ch in (code or "") if ch.isdigit())
        if not value:
            raise WechatApiError("请输入手机微信显示的数字")
        login.pending_code = value
        login.needs_verify_code = False
        login.state = "pending"
        login.message = "已提交数字，正在等待微信确认…"
        login.code_event.set()
        return login.public()

    async def cancel_all(self) -> None:
        tasks = list(self._tasks.values())
        self._tasks.clear()
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        self._logins.clear()

    # --- 轮询 ---

    async def _poll_loop(self, login_id: str) -> None:
        while True:
            login = self._logins.get(login_id)
            if login is None or not login.active:
                return
            if login.needs_verify_code and login.pending_code is None:
                # 等用户把手机上的数字填进来；不阻塞事件循环。
                try:
                    await asyncio.wait_for(login.code_event.wait(), timeout=1.0)
                except asyncio.TimeoutError:
                    continue
                login.code_event.clear()
                continue
            await self.poll_once(login_id)
            current = self._logins.get(login_id)
            if current is None or not current.active:
                return
            # 正常等待 1 秒；网络/网关错误时退避，避免把失败请求打成高频轮询。
            await self._sleep(3.0 if current.error else 1.0)

    async def poll_once(self, login_id: str) -> dict[str, Any]:
        """执行一次状态查询（测试直接驱动；生产由 `_poll_loop` 调用）。"""
        login = self._logins.get(login_id)
        if login is None:
            raise WechatApiError("绑定会话不存在或已结束，请重新获取二维码")
        if not login.active:
            return login.public()
        try:
            data = await self.client.get_qrcode_status(
                login.qrcode,
                verify_code=login.pending_code,
                base_url=login.poll_base,
            )
        except WechatSessionExpired as exc:
            self._fail(login, str(exc))
            return login.public()
        except WechatApiError as exc:
            login.error = str(exc)
            login.message = "暂时连不上微信后端，正在重试…"
            return login.public()

        status = str(data.get("status") or "wait")
        login.error = ""
        if "need_verifycode" in status:
            login.needs_verify_code = True
            login.pending_code = None
            login.state = "need_verifycode"
            login.message = "手机微信显示了数字，请在 Flux 里输入以继续绑定。"
            return login.public()
        if status == "scaned":
            login.pending_code = None
            login.state = "scaned"
            login.message = "已扫码，请在手机上确认绑定。"
            return login.public()
        if status == "scaned_but_redirect":
            host = str(data.get("redirect_host") or "").strip()
            if host:
                login.poll_base = f"https://{host}"
            login.state = "scaned"
            login.message = "已扫码，正在切换到绑定服务…"
            return login.public()
        if status in {"expired", "verify_code_blocked"}:
            login.pending_code = None
            await self._refresh(login)
            return login.public()
        if status == "binded_redirect":
            self._fail(
                login,
                "这个微信 ClawBot 已绑定到另一个后端。请先在原后端解绑，"
                "否则消息可能被分流；然后再重新扫码。",
            )
            return login.public()
        if status == "confirmed":
            await self._confirm(login, data)
            return login.public()
        login.state = "pending"
        login.message = "等待扫码确认…"
        return login.public()

    async def _refresh(self, login: _Login) -> None:
        login.refresh_count += 1
        if login.refresh_count > _QR_REFRESH_LIMIT:
            self._fail(login, "二维码多次失效，请稍后重新开始连接。")
            return
        try:
            data = await self.client.get_bot_qrcode()
            qrcode = str(data.get("qrcode") or "").strip()
            image = str(data.get("qrcode_img_content") or "").strip()
            if not qrcode or not image:
                raise WechatApiError("官方没有返回新的二维码")
        except WechatApiError as exc:
            self._fail(login, f"刷新二维码失败：{exc}")
            return
        login.qrcode = qrcode
        login.qrcode_image = image
        login.matrix = _qr_matrix(image)
        login.expires_at = time.time() + self.qr_ttl_seconds
        login.state = "pending"
        login.message = "二维码已刷新，请重新扫码。"
        login.poll_base = None

    async def _confirm(self, login: _Login, data: dict[str, Any]) -> None:
        bot_token = str(data.get("bot_token") or "").strip()
        ilink_bot_id = str(data.get("ilink_bot_id") or "").strip()
        ilink_user_id = str(data.get("ilink_user_id") or "").strip()
        baseurl = str(data.get("baseurl") or "").strip() or self.client.api_base
        if not bot_token or not ilink_bot_id or not ilink_user_id:
            self._fail(login, "微信确认绑定但缺少必要凭证，请重新扫码。")
            return
        self.store.update_wechat(
            bot_token=bot_token,
            ilink_bot_id=ilink_bot_id,
            baseurl=baseurl,
            ilink_user_id=ilink_user_id,
            bound_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            get_updates_buf="",
            last_context_token="",
            pending_notices=[],
            needs_reauth=False,
            last_error="",
        )
        login.state = "confirmed"
        login.message = (
            "已连接。请先在微信「微信 ClawBot」会话里发一句话，"
            "否则 Flux 拿不到 context_token，回复发不出去。"
        )
        login.pending_code = None
        logger.info("微信 ClawBot 绑定成功 ilink_bot_id=%s", ilink_bot_id)
        if self.on_connected is not None:
            try:
                await self.on_connected()
            except Exception as exc:  # noqa: BLE001 - 轮询启动失败不能回滚已完成绑定
                logger.warning("微信绑定成功后启动轮询失败：%s", type(exc).__name__)

    @staticmethod
    def _fail(login: _Login, message: str) -> None:
        login.state = "failed"
        login.message = message
        login.error = message


def _qr_matrix(content: str) -> list[str]:
    """把二维码内容编码成 0/1 行，供三端直接绘制，避免依赖图片组件。"""
    try:
        import qrcode

        qr = qrcode.QRCode(border=2, box_size=1)
        qr.add_data(content)
        qr.make(fit=True)
        matrix = qr.get_matrix()
        return ["".join("1" if cell else "0" for cell in row) for row in matrix]
    except Exception:  # noqa: BLE001 - 缺少 qrcode 依赖时退回「只给链接」
        logger.warning("生成微信绑定二维码矩阵失败，将只提供备份链接")
        return []


# ---------------------------------------------------------------- 消息轮询


class WechatPoller:
    """常驻 getupdates 长轮询：游标持久化、去重、回复原会话。"""

    def __init__(
        self,
        *,
        client: WechatIlinkClient,
        store: ImConnectorStore,
        assistant: ImAssistant,
        on_reauth: Callable[[str], None] | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self.client = client
        self.store = store
        self.assistant = assistant
        self.on_reauth = on_reauth
        self._sleep = sleep
        self._task: asyncio.Task[None] | None = None
        self._stopped = asyncio.Event()
        self._cursor = ""
        self.running = False
        self.connected = False
        self.last_error = ""
        self.last_poll_at: float | None = None

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._stopped = asyncio.Event()
        self._task = asyncio.create_task(self._run(), name="flux-wechat-im-poll")

    async def stop(self) -> None:
        self._stopped.set()
        task = self._task
        self._task = None
        if task is not None and not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        self.running = False
        self.connected = False

    async def _run(self) -> None:
        credentials = self.store.wechat_credentials()
        if credentials is None:
            return
        with contextlib.suppress(Exception):
            await self.client.notify_start(
                token=credentials.bot_token, base_url=credentials.baseurl
            )
        self._cursor = str(self.store.wechat().get("get_updates_buf") or "")
        self.running = True
        try:
            while not self._stopped.is_set():
                outcome = await self.poll_once()
                if outcome in {"reauth", "stopped"}:
                    return
                if outcome == "retry":
                    await self._sleep(2.0)
        finally:
            self.running = False
            self.connected = False
            with contextlib.suppress(Exception):
                await self.client.notify_stop(
                    token=credentials.bot_token, base_url=credentials.baseurl
                )

    async def poll_once(self) -> str:
        """执行一轮 getupdates；返回 ok / retry / reauth / stopped。

        生产由 `_run` 循环调用；测试可直接驱动，不依赖后台任务与真实网络。
        """
        credentials = self.store.wechat_credentials()
        if credentials is None:
            return "stopped"
        try:
            data = await self.client.get_updates(
                token=credentials.bot_token,
                cursor=self._cursor,
                base_url=credentials.baseurl,
            )
        except WechatSessionExpired:
            self._mark_reauth("登录已过期（-14），请重新扫码连接微信。")
            return "reauth"
        except Exception as exc:  # noqa: BLE001
            self.connected = False
            self.last_error = type(exc).__name__
            return "retry"

        self.last_poll_at = time.time()
        ret = data.get("ret")
        errcode = data.get("errcode")
        if errcode == STALE_TOKEN_ERRCODE or ret == STALE_TOKEN_ERRCODE:
            self._mark_reauth("登录已过期（-14），请重新扫码连接微信。")
            return "reauth"
        if (ret not in (None, 0)) or (errcode not in (None, 0)):
            self.connected = False
            self.last_error = f"ret/errcode={errcode if errcode is not None else ret}"
            return "retry"

        self.connected = True
        self.last_error = ""
        messages = data.get("msgs")
        messages = messages if isinstance(messages, list) else []
        try:
            for message in messages:
                if isinstance(message, dict):
                    await self._process_message(
                        message, credentials.ilink_user_id, credentials.bot_token
                    )
        except WechatSessionExpired:
            self._mark_reauth("登录已过期（-14），请重新扫码连接微信。")
            return "reauth"
        except Exception as exc:  # noqa: BLE001 - 不推进游标，让服务端重投
            self.last_error = type(exc).__name__
            logger.warning("微信 IM 单条消息处理失败，将重试：%s", type(exc).__name__)
            return "retry"

        next_cursor = str(data.get("get_updates_buf") or "")
        if next_cursor and next_cursor != self._cursor:
            self._cursor = next_cursor
            self.store.update_wechat(get_updates_buf=self._cursor)
        return "ok"

    async def _process_message(
        self, message: dict[str, Any], bound_user_id: str, token: str
    ) -> None:
        if message.get("group_id"):
            logger.info("微信 IM 忽略群消息")
            return
        message_type = message.get("message_type")
        if message_type == _MESSAGE_TYPE_BOT:
            return
        # 官方类型里 message_type 是可选字段；缺省时仍按用户消息处理，
        # 但明确标记为 Bot（2）的消息一律跳过，避免自问自答。
        if message_type not in (None, _MESSAGE_TYPE_USER):
            return
        from_user_id = str(message.get("from_user_id") or "").strip()
        if not from_user_id or from_user_id != bound_user_id:
            logger.info("微信 IM 忽略非绑定发送者")
            return
        text = _message_text(message)
        context_token = str(message.get("context_token") or "").strip()
        if not context_token:
            # 官方限制：没有 context_token 发不出回复；不编造。
            logger.info("微信 IM 入站消息缺少 context_token，跳过回复")
            return
        if not text:
            await self.client.send_text(
                token=token,
                to_user_id=from_user_id,
                text="目前只支持文字消息，请用文字告诉我你的需求。",
                context_token=context_token,
            )
            return
        self.store.update_wechat(last_context_token=context_token)
        message_id = (
            str(message.get("message_id") or "").strip()
            or str(message.get("client_id") or "").strip()
            or f"{message.get('seq')}-{message.get('create_time_ms')}"
        )
        reply = await self.assistant.respond(
            session_key=from_user_id,
            sender_id=from_user_id,
            message_id=message_id,
            text=text,
        )
        if not reply.duplicate and reply.text:
            await self.client.send_text(
                token=token,
                to_user_id=from_user_id,
                text=reply.text,
                context_token=context_token,
            )
        await self._flush_pending(from_user_id, context_token, token)

    async def _flush_pending(self, to_user_id: str, context_token: str, token: str) -> None:
        notices = self.store.take_pending_notices()
        if not notices:
            return
        for notice in notices:
            try:
                await self.client.send_text(
                    token=token,
                    to_user_id=to_user_id,
                    text=notice,
                    context_token=context_token,
                )
            except Exception:  # noqa: BLE001 - 补发失败就继续留到下一条消息
                self.store.add_pending_notice(notice)

    def _mark_reauth(self, message: str) -> None:
        self.last_error = message
        self.connected = False
        self.store.update_wechat(needs_reauth=True, last_error=message)
        if self.on_reauth is not None:
            self.on_reauth(message)
        logger.warning("微信 IM 需要重新扫码：%s", message)


def _message_text(message: dict[str, Any]) -> str:
    items = message.get("item_list")
    if not isinstance(items, list):
        return ""
    texts: list[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        if item.get("type") == _ITEM_TYPE_TEXT:
            text_item = item.get("text_item")
            if isinstance(text_item, dict) and isinstance(text_item.get("text"), str):
                value = text_item["text"].strip()
                if value:
                    texts.append(value)
        elif item.get("type") == _ITEM_TYPE_VOICE:
            voice = item.get("voice_item")
            if isinstance(voice, dict) and isinstance(voice.get("text"), str):
                value = voice["text"].strip()
                if value:
                    texts.append(value)
    return "\n".join(texts).strip()


# ---------------------------------------------------------------- 连接器服务


class WechatImService:
    """个人微信连接器门面：状态、扫码绑定、轮询启停、通知补发。"""

    source_key = "wechat-im"

    def __init__(
        self,
        *,
        enabled: bool,
        api_base: str,
        assistant: ImAssistant,
        store: ImConnectorStore,
        client: WechatIlinkClient,
        qr_ttl_seconds: int = 300,
        auto_poll: bool = True,
        poller: WechatPoller | None = None,
        login_manager: WechatLoginManager | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._enabled = enabled
        self.assistant = assistant
        self.store = store
        self.client = client
        self.poller = poller or WechatPoller(
            client=client, store=store, assistant=assistant, sleep=sleep
        )
        self.login_manager = login_manager or WechatLoginManager(
            client=client,
            store=store,
            on_connected=self.poller.start,
            qr_ttl_seconds=qr_ttl_seconds,
            auto_poll=auto_poll,
            sleep=sleep,
        )

    async def start(self) -> None:
        if not self._enabled:
            return
        state = self.store.wechat()
        if self.store.wechat_credentials() is None:
            return
        if bool(state.get("needs_reauth")):
            return
        await self.poller.start()

    async def stop(self) -> None:
        await self.poller.stop()
        await self.login_manager.cancel_all()

    async def aclose(self) -> None:
        await self.stop()
        await self.client.aclose()

    async def connect(self) -> dict[str, Any]:
        if not self._enabled:
            raise WechatApiError("个人微信连接已在服务端关闭。")
        return await self.login_manager.start_login()

    def login_status(self, login_id: str) -> dict[str, Any]:
        state = self.login_manager.status(login_id)
        if state is None:
            raise WechatApiError("绑定会话不存在或已结束，请重新获取二维码")
        return state

    def submit_code(self, login_id: str, code: str) -> dict[str, Any]:
        return self.login_manager.submit_verify_code(login_id, code)

    async def disconnect(self) -> dict[str, Any]:
        await self.poller.stop()
        await self.login_manager.cancel_all()
        self.store.clear_wechat()
        return self.status()

    async def notify(self, text: str) -> bool:
        """向绑定的微信单聊推送提醒；推不出去就排队等用户下次开口。"""
        credentials = self.store.wechat_credentials()
        if credentials is None or not self._enabled:
            return False
        state = self.store.wechat()
        if bool(state.get("needs_reauth")):
            return False
        context_token = str(state.get("last_context_token") or "").strip()
        if not context_token:
            self.store.add_pending_notice(text)
            return False
        try:
            await self.client.send_text(
                token=credentials.bot_token,
                to_user_id=credentials.ilink_user_id,
                text=text,
                context_token=context_token,
            )
            return True
        except WechatSessionExpired:
            self._mark_reauth("登录已过期（-14），请重新扫码连接微信。")
        except Exception as exc:  # noqa: BLE001 - 推不出去不阻塞 App 内通知
            logger.info("微信 IM 通知推送失败，已留待下次对话补发：%s", type(exc).__name__)
        self.store.add_pending_notice(text)
        return False

    def _mark_reauth(self, message: str) -> None:
        self.store.update_wechat(needs_reauth=True, last_error=message)
        self.poller.connected = False
        self.poller.last_error = message

    def status(self) -> dict[str, Any]:
        state = self.store.wechat()
        credentials = self.store.wechat_credentials()
        needs_reauth = bool(state.get("needs_reauth"))
        connected = bool(credentials) and not needs_reauth
        pending_login = self.login_manager.latest()
        if not self._enabled:
            message = "个人微信连接已在服务端关闭。"
        elif needs_reauth:
            message = str(state.get("last_error") or "微信登录已过期，请重新扫码。")
        elif pending_login is not None:
            message = str(pending_login.get("message") or "等待扫码确认…")
        elif connected:
            message = "已连接。首次使用请先在微信 ClawBot 会话里发一句话。"
        else:
            message = (
                "尚未连接。请确认微信已启用「微信 ClawBot」插件，然后点「连接个人微信」扫码绑定。"
            )
        return {
            "id": "wechat",
            "enabled": self._enabled,
            "configured": self._enabled,
            "connected": connected,
            "needs_reauth": needs_reauth,
            "running": bool(getattr(self.poller, "running", False)),
            "polling": bool(getattr(self.poller, "connected", False)),
            "has_context_token": bool(str(state.get("last_context_token") or "").strip()),
            "pending_notices": len(state.get("pending_notices") or []),
            "pending_login": pending_login,
            "error": str(state.get("last_error") or "") or None,
            "message": message,
            "capabilities": {
                "chat": True,
                "notifications": True,
                "group": False,
                "proactive_window_note": (
                    "微信只在你最近互动后允许主动消息；推不出去时提醒会保留，下次你说话后补发。"
                ),
            },
        }
