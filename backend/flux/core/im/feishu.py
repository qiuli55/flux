"""飞书官方应用机器人：长连接收事件 + 单聊收发 + 任务通知。

本模块是「连接器」能力，与 `flux.core.supervisor.feishu` 的 webhook 完全独立：

- 入站走飞书官方**长连接**（服务端主动向飞书建立 WebSocket），不需要公网回调端口；
- 只处理 `chat_type=p2p` 的单聊文本，群消息直接忽略并打安全日志（不含正文/密钥）；
- 出站用官方 `tenant_access_token` + 消息 API，令牌自动刷新；
- 事件帧的 protobuf 编解码在本文件内手写（不引入飞书 SDK 运行时），
  帧格式以飞书官方 Python SDK 的 `pbbp2.proto` 为准。

凭据只从环境变量读取（`FLUX_FEISHU_IM_APP_ID` / `..._APP_SECRET`），
不落盘、不进日志、不进 API 响应。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
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
    "FeishuClient",
    "FeishuImService",
    "FeishuInbound",
    "FeishuLongConnection",
    "Frame",
    "decode_frame",
    "encode_frame",
    "parse_message_event",
]

MESSAGE_EVENT_TYPE = "im.message.receive_v1"
_HEADER_TYPE = "type"
_HEADER_MESSAGE_ID = "message_id"
_HEADER_TRACE_ID = "trace_id"
_HEADER_SUM = "sum"
_HEADER_SEQ = "seq"
_HEADER_BIZ_RT = "biz_rt"

_FRAME_CONTROL = 0
_FRAME_DATA = 1


class FeishuApiError(RuntimeError):
    """飞书 API 返回了非零业务码 / 非 2xx。消息已脱敏。"""


# ---------------------------------------------------------------- protobuf 帧
#
# pbbp2.proto（official Python SDK）：
#   message Header { required string key = 1; required string value = 2; }
#   message Frame {
#     required uint64 SeqID = 1; required uint64 LogID = 2;
#     required int32 service = 3; required int32 method = 4;
#     repeated Header headers = 5; optional string payload_encoding = 6;
#     optional string payload_type = 7; optional bytes payload = 8;
#     optional string LogIDNew = 9;
#   }


def _varint(value: int) -> bytes:
    number = int(value)
    if number < 0:
        number &= (1 << 64) - 1
    out = bytearray()
    while True:
        byte = number & 0x7F
        number >>= 7
        if number:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _read_varint(data: bytes, pos: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise ValueError("varint 越界")
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint 过长")


def _field_bytes(number: int, value: bytes) -> bytes:
    return _varint((number << 3) | 2) + _varint(len(value)) + value


def _field_varint(number: int, value: int) -> bytes:
    return _varint(number << 3) + _varint(value)


@dataclass
class Frame:
    seq_id: int = 0
    log_id: int = 0
    service: int = 0
    method: int = 0
    headers: dict[str, str] = field(default_factory=dict)
    payload_encoding: str = ""
    payload_type: str = ""
    payload: bytes = b""
    log_id_new: str = ""
    #: 未知字段的原始编码，ack 时原样带回，避免丢服务端字段。
    extra_fields: list[bytes] = field(default_factory=list)

    def header(self, key: str) -> str:
        return self.headers.get(key, "")


def decode_frame(data: bytes) -> Frame:
    """解析一帧 pbbp2.Frame；未知字段保留原始字节。"""
    frame = Frame()
    pos = 0
    while pos < len(data):
        start = pos
        key, pos = _read_varint(data, pos)
        number = key >> 3
        wire = key & 0x07
        if wire == 0:
            value, pos = _read_varint(data, pos)
        elif wire == 2:
            length, pos = _read_varint(data, pos)
            value = data[pos : pos + length]
            pos += length
        elif wire == 5:
            value = data[pos : pos + 4]
            pos += 4
        elif wire == 1:
            value = data[pos : pos + 8]
            pos += 8
        else:
            raise ValueError(f"不支持的 protobuf wire type：{wire}")

        if number == 1 and wire == 0:
            frame.seq_id = int(value)
        elif number == 2 and wire == 0:
            frame.log_id = int(value)
        elif number == 3 and wire == 0:
            frame.service = int(value)
        elif number == 4 and wire == 0:
            frame.method = int(value)
        elif number == 5 and wire == 2:
            frame.headers.update(_decode_header(value))
        elif number == 6 and wire == 2:
            frame.payload_encoding = bytes(value).decode("utf-8", "replace")
        elif number == 7 and wire == 2:
            frame.payload_type = bytes(value).decode("utf-8", "replace")
        elif number == 8 and wire == 2:
            frame.payload = bytes(value)
        elif number == 9 and wire == 2:
            frame.log_id_new = bytes(value).decode("utf-8", "replace")
        else:
            frame.extra_fields.append(data[start:pos])
    return frame


def _decode_header(data: bytes) -> dict[str, str]:
    key = ""
    value = ""
    pos = 0
    while pos < len(data):
        field_key, pos = _read_varint(data, pos)
        number = field_key >> 3
        wire = field_key & 0x07
        if wire != 2:
            if wire == 0:
                _, pos = _read_varint(data, pos)
            elif wire == 5:
                pos += 4
            elif wire == 1:
                pos += 8
            else:
                raise ValueError("Header 字段格式异常")
            continue
        length, pos = _read_varint(data, pos)
        raw = data[pos : pos + length]
        pos += length
        if number == 1:
            key = raw.decode("utf-8", "replace")
        elif number == 2:
            value = raw.decode("utf-8", "replace")
    return {key: value} if key else {}


def encode_frame(frame: Frame) -> bytes:
    parts = [
        _field_varint(1, frame.seq_id),
        _field_varint(2, frame.log_id),
        _field_varint(3, frame.service),
        _field_varint(4, frame.method),
    ]
    for key, value in frame.headers.items():
        header = _field_bytes(1, key.encode("utf-8")) + _field_bytes(2, value.encode("utf-8"))
        parts.append(_field_bytes(5, header))
    if frame.payload_encoding:
        parts.append(_field_bytes(6, frame.payload_encoding.encode("utf-8")))
    if frame.payload_type:
        parts.append(_field_bytes(7, frame.payload_type.encode("utf-8")))
    if frame.payload:
        parts.append(_field_bytes(8, frame.payload))
    if frame.log_id_new:
        parts.append(_field_bytes(9, frame.log_id_new.encode("utf-8")))
    parts.extend(frame.extra_fields)
    return b"".join(parts)


def ping_frame(service_id: int) -> Frame:
    frame = Frame(
        service=service_id,
        method=_FRAME_CONTROL,
        headers={_HEADER_TYPE: "ping"},
    )
    return frame


def ack_frame(frame: Frame, code: int = 200) -> Frame:
    payload = json.dumps({"code": int(code)}, ensure_ascii=False).encode("utf-8")
    headers = dict(frame.headers)
    headers[_HEADER_BIZ_RT] = str(max(0, int(headers.get(_HEADER_BIZ_RT) or 0)))
    return Frame(
        seq_id=frame.seq_id,
        log_id=frame.log_id,
        service=frame.service,
        method=frame.method,
        headers=headers,
        payload_encoding=frame.payload_encoding,
        payload_type=frame.payload_type,
        payload=payload,
        log_id_new=frame.log_id_new,
        extra_fields=list(frame.extra_fields),
    )


# ---------------------------------------------------------------- 事件解析


@dataclass(frozen=True)
class FeishuInbound:
    event_id: str
    message_id: str
    chat_id: str
    chat_type: str
    sender_id: str
    message_type: str
    text: str

    @property
    def is_p2p(self) -> bool:
        return self.chat_type == "p2p"

    @property
    def is_text(self) -> bool:
        return self.message_type == "text" and bool(self.text)


def parse_message_event(payload: dict[str, Any]) -> FeishuInbound | None:
    """解析飞书 v2 事件；不是消息事件返回 None。

    群消息也会返回对象（`is_p2p == False`），由调用方记录忽略原因——
    解析层不替业务决定是否处理，避免把群聊语义混进这里。
    """
    header = payload.get("header")
    header = header if isinstance(header, dict) else {}
    if str(header.get("event_type") or "") != MESSAGE_EVENT_TYPE:
        return None
    event = payload.get("event")
    event = event if isinstance(event, dict) else {}
    message = event.get("message")
    message = message if isinstance(message, dict) else {}
    sender = event.get("sender")
    sender = sender if isinstance(sender, dict) else {}
    sender_id_obj = sender.get("sender_id")
    sender_id_obj = sender_id_obj if isinstance(sender_id_obj, dict) else {}

    event_id = str(header.get("event_id") or message.get("message_id") or "").strip()
    message_id = str(message.get("message_id") or "").strip()
    chat_id = str(message.get("chat_id") or "").strip()
    chat_type = str(message.get("chat_type") or "").strip()
    message_type = str(message.get("message_type") or "").strip()
    sender_id = str(sender_id_obj.get("open_id") or sender_id_obj.get("user_id") or "").strip()
    text = _text_content(message.get("content"))
    if not event_id or not message_id or not chat_id:
        return None
    return FeishuInbound(
        event_id=event_id,
        message_id=message_id,
        chat_id=chat_id,
        chat_type=chat_type,
        sender_id=sender_id,
        message_type=message_type,
        text=text,
    )


def _text_content(raw: object) -> str:
    if not isinstance(raw, str) or not raw.strip():
        return ""
    try:
        data = json.loads(raw)
    except ValueError:
        return ""
    if not isinstance(data, dict):
        return ""
    text = data.get("text")
    return text.strip() if isinstance(text, str) else ""


# ---------------------------------------------------------------- 官方 API


class FeishuClient:
    """tenant_access_token 缓存 + 单聊文本收发。"""

    def __init__(
        self,
        *,
        app_id: str,
        app_secret: str,
        api_base: str = "https://open.feishu.cn",
        timeout_seconds: float = 15.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._app_id = app_id
        self._app_secret = app_secret
        self._api_base = api_base.rstrip("/")
        self._timeout = max(1.0, float(timeout_seconds))
        self._client = client
        self._owns_client = client is None
        self._token: str | None = None
        self._token_expires_at = 0.0
        self._lock = asyncio.Lock()

    async def validate(self) -> None:
        """验证 App ID / Secret 是否可用；失败抛带下一步说明的异常。"""
        await self._tenant_token()

    async def reply_text(self, message_id: str, text: str) -> None:
        token = await self._tenant_token()
        response = await self._request(
            "POST",
            f"/open-apis/im/v1/messages/{message_id}/reply",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "msg_type": "text",
                "content": json.dumps({"text": (text or "")[:MAX_TEXT_CHARS]}, ensure_ascii=False),
            },
        )
        _raise_for_feishu(response, "回复飞书单聊消息")

    async def send_text(self, chat_id: str, text: str) -> None:
        token = await self._tenant_token()
        response = await self._request(
            "POST",
            "/open-apis/im/v1/messages",
            params={"receive_id_type": "chat_id"},
            headers={"Authorization": f"Bearer {token}"},
            json={
                "receive_id": chat_id,
                "msg_type": "text",
                "content": json.dumps({"text": (text or "")[:MAX_TEXT_CHARS]}, ensure_ascii=False),
            },
        )
        _raise_for_feishu(response, "发送飞书单聊消息")

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
        self._client = None

    # --- 内部 ---

    async def _tenant_token(self) -> str:
        if self._token is not None and time.monotonic() < self._token_expires_at:
            return self._token
        async with self._lock:
            if self._token is not None and time.monotonic() < self._token_expires_at:
                return self._token
            response = await self._request(
                "POST",
                "/open-apis/auth/v3/tenant_access_token/internal",
                json={"app_id": self._app_id, "app_secret": self._app_secret},
            )
            data = _payload(response, "获取飞书 tenant_access_token")
            token = str(data.get("tenant_access_token") or "")
            if not token:
                raise FeishuApiError("飞书 tenant_access_token 响应缺少令牌")
            expire = int(data.get("expire") or 7200)
            self._token = token
            self._token_expires_at = time.monotonic() + max(60, expire - 60)
            return token

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        client = self._client
        if client is None:
            client = httpx.AsyncClient(timeout=self._timeout)
            self._client = client
            self._owns_client = True
        return await client.request(method, f"{self._api_base}{path}", **kwargs)


def _payload(response: httpx.Response, label: str) -> dict[str, Any]:
    try:
        data = response.json()
    except ValueError as exc:
        raise FeishuApiError(f"{label}失败：响应不是 JSON（HTTP {response.status_code}）") from exc
    if not isinstance(data, dict):
        raise FeishuApiError(f"{label}失败：响应结构异常（HTTP {response.status_code}）")
    code = data.get("code")
    if response.status_code >= 400 or code not in (0, None):
        message = data.get("msg") or data.get("message") or ""
        raise FeishuApiError(
            f"{label}失败：HTTP {response.status_code} code={code} {message}"[:500]
        )
    return data


def _raise_for_feishu(response: httpx.Response, label: str) -> None:
    _payload(response, label)


# ---------------------------------------------------------------- 长连接


class FeishuLongConnection:
    """官方长连接客户端：取接入点 → WebSocket → 收事件 → 回 ack。

    网络与协议细节都可注入，测试用假 HTTP / 假 WebSocket，不访问飞书。
    """

    def __init__(
        self,
        *,
        app_id: str,
        app_secret: str,
        api_base: str = "https://open.feishu.cn",
        on_event: Callable[[dict[str, Any]], Awaitable[None]] | None = None,
        http_client: httpx.AsyncClient | None = None,
        ws_connect: Callable[[str], Awaitable[Any]] | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        user_agent: str = "flux-im/0.9.19",
    ) -> None:
        self._app_id = app_id
        self._app_secret = app_secret
        self._api_base = api_base.rstrip("/")
        self._on_event = on_event
        self._http_client = http_client
        self._owns_http = http_client is None
        self._ws_connect = ws_connect
        self._sleep = sleep
        self._user_agent = user_agent
        self._task: asyncio.Task[None] | None = None
        self._stopped = asyncio.Event()
        #: 分片事件缓存（sum > 1 时官方会把同一 message_id 拆成多帧）。
        self._chunks: dict[str, list[bytes | None]] = {}
        self._conn: Any = None
        self._service_id = 0
        self._ping_interval = 120.0
        self.running = False
        self.connected_at: float | None = None
        self.last_event_at: float | None = None
        self.last_error: str = ""

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return
        self._stopped = asyncio.Event()
        self._task = asyncio.create_task(self._run_forever(), name="flux-feishu-im-ws")

    async def stop(self) -> None:
        self._stopped.set()
        conn = self._conn
        self._conn = None
        if conn is not None:
            close = getattr(conn, "close", None)
            if close is not None:
                with contextlib.suppress(Exception):  # 关闭失败不影响停机
                    await close()
        task = self._task
        self._task = None
        if task is not None and not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        self.running = False

    async def aclose(self) -> None:
        await self.stop()
        if self._http_client is not None and self._owns_http:
            await self._http_client.aclose()
        self._http_client = None

    # --- 连接循环 ---

    async def _run_forever(self) -> None:
        backoff = 2.0
        while not self._stopped.is_set():
            try:
                url = await self._endpoint_url()
                ws = await self._connect(url)
                self._conn = ws
                self._service_id = _service_id(url)
                self._chunks.clear()
                self.running = True
                self.connected_at = time.time()
                self.last_error = ""
                logger.info("飞书 IM 长连接已建立 service_id=%s", self._service_id)
                pinger = asyncio.create_task(self._ping_loop())
                try:
                    await self._receive_loop(ws)
                finally:
                    pinger.cancel()
                    with contextlib.suppress(asyncio.CancelledError, Exception):
                        await pinger
                    self._conn = None
                    self.running = False
                backoff = 2.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - 连接失败要退避重试
                self.last_error = _safe_error(exc)
                self.running = False
                logger.warning("飞书 IM 长连接断开，将重试：%s", self.last_error)
                try:
                    await self._sleep(backoff)
                except asyncio.CancelledError:
                    raise
                backoff = min(backoff * 2, 60.0)

    async def _endpoint_url(self) -> str:
        client = self._http_client
        if client is None:
            client = httpx.AsyncClient(timeout=15.0)
            self._http_client = client
            self._owns_http = True
        response = await client.post(
            f"{self._api_base}/callback/ws/endpoint",
            json={"AppID": self._app_id, "AppSecret": self._app_secret},
            headers={"locale": "zh", "User-Agent": self._user_agent},
        )
        data = _payload(response, "获取飞书长连接接入点")
        endpoint = data.get("data")
        endpoint = endpoint if isinstance(endpoint, dict) else {}
        url = str(endpoint.get("URL") or endpoint.get("url") or "").strip()
        if not url:
            raise FeishuApiError("飞书长连接接入点响应缺少 URL")
        self._configure(endpoint.get("ClientConfig") or endpoint.get("client_config"))
        return url

    def _configure(self, config: object) -> None:
        if not isinstance(config, dict):
            return
        try:
            interval = int(config.get("PingInterval") or config.get("ping_interval") or 0)
        except (TypeError, ValueError):
            interval = 0
        if interval > 0:
            self._ping_interval = float(interval)

    async def _connect(self, url: str) -> Any:
        if self._ws_connect is not None:
            return await self._ws_connect(url)
        import websockets

        try:
            return await websockets.connect(url, proxy=None)
        except TypeError:
            return await websockets.connect(url)

    async def _ping_loop(self) -> None:
        while not self._stopped.is_set():
            try:
                await self._sleep(self._ping_interval)
                if self._stopped.is_set():
                    return
                conn = self._conn
                if conn is None:
                    return
                await conn.send(encode_frame(ping_frame(self._service_id)))
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                logger.debug("飞书 IM ping 失败：%s", _safe_error(exc))
                return

    async def _receive_loop(self, conn: Any) -> None:
        while not self._stopped.is_set():
            message = await conn.recv()
            if message is None:
                raise ConnectionError("飞书长连接已关闭")
            if isinstance(message, str):
                message = message.encode("utf-8")
            try:
                frame = decode_frame(bytes(message))
                await self._handle_frame(conn, frame)
            except Exception as exc:  # noqa: BLE001 - 单帧失败不能让连接停摆
                logger.warning("飞书 IM 帧处理失败：%s", _safe_error(exc))

    async def _handle_frame(self, conn: Any, frame: Frame) -> None:
        if frame.method == _FRAME_CONTROL:
            message_type = frame.header(_HEADER_TYPE).lower()
            if message_type == "pong" and frame.payload:
                with contextlib.suppress(ValueError):
                    self._configure(json.loads(frame.payload.decode("utf-8")))
            return
        if frame.method != _FRAME_DATA:
            return
        message_type = frame.header(_HEADER_TYPE).lower()
        if message_type != "event":
            return
        combined = self._combined_payload(frame)
        if combined is None:
            # 分片还没收齐：先不 ack，等齐了再处理。
            return
        code = 200
        try:
            payload = json.loads(combined.decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("事件不是 JSON 对象")
            if self._on_event is not None:
                await self._on_event(payload)
            self.last_event_at = time.time()
        except Exception as exc:  # noqa: BLE001 - 返回 500 让飞书重推
            code = 500
            logger.warning("飞书 IM 事件处理失败：%s", _safe_error(exc))
        await conn.send(encode_frame(ack_frame(frame, code)))

    def _combined_payload(self, frame: Frame) -> bytes | None:
        """合并 sum>1 的分片；未收齐返回 None。"""
        try:
            total = int(frame.header(_HEADER_SUM) or 1)
            seq = int(frame.header(_HEADER_SEQ) or 0)
        except ValueError:
            return frame.payload
        if total <= 1:
            return frame.payload
        message_id = frame.header(_HEADER_MESSAGE_ID)
        if not message_id or seq < 0 or seq >= total:
            return frame.payload
        if len(self._chunks) > 64:
            self._chunks.pop(next(iter(self._chunks)), None)
        buffer = self._chunks.setdefault(message_id, [None] * total)
        if len(buffer) != total:
            buffer = [None] * total
            self._chunks[message_id] = buffer
        buffer[seq] = frame.payload
        if any(chunk is None for chunk in buffer):
            return None
        self._chunks.pop(message_id, None)
        return b"".join(chunk or b"" for chunk in buffer)


def _service_id(url: str) -> int:
    try:
        from urllib.parse import parse_qs, urlparse

        values = parse_qs(urlparse(url).query).get("service_id")
        return int(values[0]) if values else 0
    except (TypeError, ValueError):
        return 0


def _safe_error(exc: BaseException) -> str:
    """只保留异常类型与简短描述，避免把 URL / 凭据写进日志。"""
    text = str(exc).replace("\n", " ").strip()
    return f"{type(exc).__name__}: {text[:200]}" if text else type(exc).__name__


# ---------------------------------------------------------------- 连接器服务


class FeishuImService:
    """飞书的「连接器」门面：状态、启停、入站处理、任务通知。"""

    source_key = "feishu-im"

    def __init__(
        self,
        *,
        app_id: str | None,
        app_secret: str | None,
        api_base: str,
        enabled: bool,
        assistant: ImAssistant,
        store: ImConnectorStore,
        client: FeishuClient | None = None,
        connection: FeishuLongConnection | None = None,
    ) -> None:
        self._app_id = (app_id or "").strip()
        self._app_secret = (app_secret or "").strip()
        self._api_base = api_base
        self._enabled = enabled
        self.assistant = assistant
        self.store = store
        self.client = client
        if self.client is None:
            self.client = FeishuClient(
                app_id=self._app_id,
                app_secret=self._app_secret,
                api_base=api_base,
            )
        self.connection = connection
        if self.connection is None:
            self.connection = FeishuLongConnection(
                app_id=self._app_id,
                app_secret=self._app_secret,
                api_base=api_base,
                on_event=self.handle_event,
            )

    @property
    def configured(self) -> bool:
        return bool(self._app_id and self._app_secret)

    async def start(self) -> None:
        if not self._enabled or not self.configured:
            return
        await self.connection.start()

    async def stop(self) -> None:
        await self.connection.stop()

    async def aclose(self) -> None:
        await self.connection.aclose()
        if self.client is not None:
            await self.client.aclose()

    async def connect(self) -> dict[str, Any]:
        """校验凭据并启动长连接；未配置时返回清楚的下一步说明。"""
        if not self.configured:
            raise FeishuApiError(
                "尚未配置飞书应用。请先在飞书开放平台创建企业自建应用，"
                "把 App ID / App Secret 配到 Flux 服务端的 "
                "FLUX_FEISHU_IM_APP_ID / FLUX_FEISHU_IM_APP_SECRET，再回来连接。"
            )
        try:
            await self.client.validate()
        except FeishuApiError as exc:
            raise FeishuApiError(
                f"飞书应用授权校验失败：{exc}。请确认应用已发布、App ID/Secret 正确，"
                "并已开通单聊消息读取与发送权限。"
            ) from exc
        await self.connection.start()
        return self.status()

    async def disconnect(self) -> dict[str, Any]:
        await self.connection.stop()
        self.store.clear_feishu()
        return self.status()

    async def handle_event(self, payload: dict[str, Any]) -> None:
        inbound = parse_message_event(payload)
        if inbound is None:
            return
        if not inbound.is_p2p:
            # 只处理单聊；群消息不进入 Flux，也不记录正文。
            logger.info("飞书 IM 忽略非单聊消息 chat_type=%s", inbound.chat_type or "unknown")
            return
        if not inbound.is_text:
            logger.info("飞书 IM 忽略非文本单聊消息 message_type=%s", inbound.message_type)
            return
        self.store.update_feishu(
            chat_id=inbound.chat_id,
            sender_open_id=inbound.sender_id,
            bound_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        )
        reply = await self.assistant.respond(
            session_key=inbound.chat_id,
            sender_id=inbound.sender_id or inbound.chat_id,
            message_id=inbound.event_id,
            text=inbound.text,
        )
        if reply.duplicate or not reply.text:
            return
        await self.client.reply_text(inbound.message_id, reply.text)

    async def notify(self, text: str) -> bool:
        """向已绑定的飞书单聊推送纯文本；未绑定返回 False。"""
        state = self.store.feishu()
        chat_id = str(state.get("chat_id") or "").strip()
        if not chat_id:
            return False
        await self.client.send_text(chat_id, text)
        return True

    def status(self) -> dict[str, Any]:
        state = self.store.feishu()
        bound = bool(str(state.get("chat_id") or "").strip())
        running = bool(getattr(self.connection, "running", False))
        last_error = str(getattr(self.connection, "last_error", "") or "").strip()
        if not self.configured:
            message = (
                "尚未配置飞书应用。请先在飞书开放平台创建企业自建应用，"
                "配置 App ID / App Secret 后重启 Flux。"
            )
        elif not self._enabled:
            message = "飞书连接已在服务端关闭。"
        elif last_error:
            message = "飞书长连接异常，正在自动重试；请检查应用权限与发布状态。"
        elif bound:
            message = "已连接。可以在飞书里继续和 Flux 单聊。"
        elif running:
            message = "长连接已启动。请在飞书里给机器人发第一条单聊消息，收到后才会记为已连接。"
        else:
            message = "飞书长连接尚未启动。"
        return {
            "id": "feishu",
            "configured": self.configured,
            "enabled": self._enabled,
            "running": running,
            "bound": bound,
            "connected": bool(bound and running),
            "waiting_for_message": bool(running and not bound),
            "error": last_error or None,
            "message": message,
            "capabilities": {
                "chat": True,
                "notifications": bound,
                "group": False,
            },
        }
