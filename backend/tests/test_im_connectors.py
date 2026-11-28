"""连接器 · 飞书官方长连接 / 个人微信官方 iLink。

全部使用假 HTTP / 假 WebSocket / 假平台客户端，不访问飞书或腾讯服务器；
断言里也不会出现真实密钥（测试值都是明显的假串）。
"""

from __future__ import annotations

import asyncio
import json
import os
import stat
import uuid
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.event.bus import EventBus, Events
from flux.core.im.assistant import ImReply
from flux.core.im.feishu import (
    FeishuApiError,
    FeishuImService,
    FeishuLongConnection,
    Frame,
    ack_frame,
    decode_frame,
    encode_frame,
    parse_message_event,
)
from flux.core.im.store import ImConnectorStore
from flux.core.im.wechat_ilink import (
    WechatApiError,
    WechatIlinkClient,
    WechatImService,
    WechatLoginManager,
    WechatPoller,
    WechatSessionExpired,
)
from flux.main import create_app

# ---------------------------------------------------------------- 夹具


class FakeAssistant:
    def __init__(self, *, text: str = "好的，收到。", duplicate: bool = False) -> None:
        self.text = text
        self.duplicate = duplicate
        self.calls: list[dict] = []

    async def respond(self, *, session_key, sender_id, message_id, text):  # noqa: ANN001
        self.calls.append(
            {
                "session_key": session_key,
                "sender_id": sender_id,
                "message_id": message_id,
                "text": text,
            }
        )
        # 真实 assistant 在判重时会回放上次回复；假实现保持一致。
        return ImReply(text=self.text, duplicate=self.duplicate)


class FakeFeishuClient:
    def __init__(self) -> None:
        self.validated = 0
        self.replies: list[tuple[str, str]] = []
        self.sent: list[tuple[str, str]] = []

    async def validate(self) -> None:
        self.validated += 1

    async def reply_text(self, message_id: str, text: str) -> None:
        self.replies.append((message_id, text))

    async def send_text(self, chat_id: str, text: str) -> None:
        self.sent.append((chat_id, text))

    async def aclose(self) -> None:
        return None


class FakeConnection:
    def __init__(self) -> None:
        self.running = False
        self.last_error = ""
        self.started = 0
        self.stopped = 0

    async def start(self) -> None:
        self.started += 1
        self.running = True

    async def stop(self) -> None:
        self.stopped += 1
        self.running = False

    async def aclose(self) -> None:
        await self.stop()


def _feishu_payload(
    *, chat_type: str = "p2p", text: str = "帮我看看进度", event_id: str = "evt-1"
) -> dict:
    return {
        "schema": "2.0",
        "header": {
            "event_id": event_id,
            "event_type": "im.message.receive_v1",
            "token": "verification-token",
            "app_id": "cli_test",
        },
        "event": {
            "sender": {"sender_id": {"open_id": "ou_user_1"}},
            "message": {
                "message_id": f"om_{event_id}",
                "chat_id": "oc_chat_1",
                "chat_type": chat_type,
                "message_type": "text",
                "content": json.dumps({"text": text}, ensure_ascii=False),
            },
        },
    }


def _wechat_message(
    *,
    message_id: str = "1001",
    from_user_id: str = "wxid_owner",
    text: str = "现在做到哪了？",
    context_token: str = "ctx-token-1",
    group_id: str | None = None,
    message_type: int = 1,
) -> dict:
    item = {"type": 1, "text_item": {"text": text}}
    message = {
        "seq": 1,
        "message_id": message_id,
        "from_user_id": from_user_id,
        "to_user_id": "bot",
        "message_type": message_type,
        "message_state": 0,
        "context_token": context_token,
        "item_list": [item],
    }
    if group_id:
        message["group_id"] = group_id
    return message


class FakeWechatClient:
    def __init__(
        self,
        *,
        qr: list[dict] | None = None,
        statuses: list[dict] | None = None,
        updates: list[dict] | None = None,
    ) -> None:
        self.qr = list(qr or [])
        self.statuses = list(statuses or [])
        self.updates = list(updates or [])
        self.sent: list[dict] = []
        self.cursors: list[str] = []
        self.status_calls: list[str | None] = []
        self.notify_started = 0
        self.notify_stopped = 0

    async def get_bot_qrcode(self) -> dict:
        return self.qr.pop(0) if self.qr else {"qrcode": "qr", "qrcode_img_content": "qr"}

    async def get_qrcode_status(self, qrcode, *, verify_code=None, base_url=None):  # noqa: ANN001
        self.status_calls.append(verify_code)
        if not self.statuses:
            return {"status": "wait"}
        return self.statuses.pop(0)

    async def get_updates(self, *, token, cursor, base_url=None):  # noqa: ANN001
        self.cursors.append(cursor)
        if not self.updates:
            raise WechatSessionExpired("-14")
        item = self.updates.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    async def send_text(self, *, token, to_user_id, text, context_token, base_url=None):  # noqa: ANN001
        self.sent.append(
            {
                "token": token,
                "to_user_id": to_user_id,
                "text": text,
                "context_token": context_token,
            }
        )
        return {"ret": 0}

    async def notify_start(self, *, token, base_url=None):  # noqa: ANN001
        self.notify_started += 1

    async def notify_stop(self, *, token, base_url=None):  # noqa: ANN001
        self.notify_stopped += 1

    async def aclose(self) -> None:
        return None


def _seed_wechat(store: ImConnectorStore) -> None:
    store.update_wechat(
        bot_token="fake-bot-token",
        ilink_bot_id="fake-bot-id",
        baseurl="https://ilink.example",
        ilink_user_id="wxid_owner",
        get_updates_buf="",
        needs_reauth=False,
    )


# ---------------------------------------------------------------- 飞书


def test_feishu_frame_roundtrip_and_ack() -> None:
    frame = Frame(
        seq_id=9,
        log_id=3,
        service=7,
        method=1,
        headers={"type": "event", "message_id": "m-1", "trace_id": "t-1", "sum": "1", "seq": "0"},
        payload=b'{"schema":"2.0"}',
        payload_encoding="json",
    )
    decoded = decode_frame(encode_frame(frame))
    assert decoded.seq_id == 9
    assert decoded.service == 7
    assert decoded.method == 1
    assert decoded.headers["message_id"] == "m-1"
    assert decoded.payload == b'{"schema":"2.0"}'
    ack = decode_frame(encode_frame(ack_frame(decoded, 200)))
    assert json.loads(ack.payload) == {"code": 200}
    assert ack.headers["biz_rt"] == "0"


def test_feishu_long_connection_combines_split_frames() -> None:
    connection = FeishuLongConnection(app_id="cli_test", app_secret="secret")
    headers = {"type": "event", "message_id": "m-split", "trace_id": "t", "sum": "2"}
    first = Frame(method=1, headers={**headers, "seq": "0"}, payload=b'{"a":')
    second = Frame(method=1, headers={**headers, "seq": "1"}, payload=b"1}")
    assert connection._combined_payload(first) is None
    assert connection._combined_payload(second) == b'{"a":1}'


def test_feishu_parse_event_only_p2p_text() -> None:
    inbound = parse_message_event(_feishu_payload())
    assert inbound is not None
    assert inbound.is_p2p is True
    assert inbound.is_text is True
    assert inbound.text == "帮我看看进度"
    group = parse_message_event(_feishu_payload(chat_type="group"))
    assert group is not None and group.is_p2p is False
    assert parse_message_event({"header": {"event_type": "other"}}) is None


async def test_feishu_service_replies_only_to_p2p(tmp_path: Path) -> None:
    assistant = FakeAssistant()
    client = FakeFeishuClient()
    store = ImConnectorStore(tmp_path / "im.json")
    service = FeishuImService(
        app_id="cli_test",
        app_secret="secret",
        api_base="https://open.feishu.example",
        enabled=True,
        assistant=assistant,
        store=store,
        client=client,
        connection=FakeConnection(),
    )
    await service.handle_event(_feishu_payload(chat_type="group"))
    assert assistant.calls == []
    assert client.replies == []

    await service.handle_event(_feishu_payload(text="帮我看看进度"))
    assert len(assistant.calls) == 1
    assert assistant.calls[0]["session_key"] == "oc_chat_1"
    assert client.replies == [("om_evt-1", "好的，收到。")]

    # 同一 event_id 重投（典型场景：上次回复发送失败）：assistant 判重并回放上次回复，
    # 出站仍按「至少一次」再发一遍，避免用户永远收不到。
    assistant.duplicate = True
    await service.handle_event(_feishu_payload(text="帮我看看进度"))
    assert len(client.replies) == 2
    assert store.feishu()["chat_id"] == "oc_chat_1"


async def test_feishu_connect_without_credentials_explains_next_step(tmp_path: Path) -> None:
    service = FeishuImService(
        app_id=None,
        app_secret=None,
        api_base="https://open.feishu.example",
        enabled=True,
        assistant=FakeAssistant(),
        store=ImConnectorStore(tmp_path / "im.json"),
        client=FakeFeishuClient(),
        connection=FakeConnection(),
    )
    with pytest.raises(FeishuApiError) as excinfo:
        await service.connect()
    message = str(excinfo.value)
    assert "FLUX_FEISHU_IM_APP_ID" in message
    assert service.status()["configured"] is False
    assert service.status()["connected"] is False


async def test_feishu_connect_surfaces_authorization_failure(tmp_path: Path) -> None:
    class RejectingClient(FakeFeishuClient):
        async def validate(self) -> None:
            raise FeishuApiError("HTTP 401 code=10003 invalid app_secret")

    service = FeishuImService(
        app_id="cli_test",
        app_secret="wrong",
        api_base="https://open.feishu.example",
        enabled=True,
        assistant=FakeAssistant(),
        store=ImConnectorStore(tmp_path / "im.json"),
        client=RejectingClient(),
        connection=FakeConnection(),
    )
    with pytest.raises(FeishuApiError) as excinfo:
        await service.connect()
    message = str(excinfo.value)
    assert "应用授权校验失败" in message
    assert "已发布" in message
    assert "权限" in message


async def test_feishu_long_connection_receives_and_acks() -> None:
    received: list[dict] = []
    inbound: asyncio.Queue[bytes | None] = asyncio.Queue()
    sent: list[bytes] = []

    class FakeWS:
        async def recv(self):
            return await inbound.get()

        async def send(self, data: bytes) -> None:
            sent.append(data)

        async def close(self) -> None:
            await inbound.put(None)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/callback/ws/endpoint"
        return httpx.Response(
            200,
            json={
                "code": 0,
                "data": {
                    "URL": "wss://example.invalid/ws?service_id=42&device_id=d1",
                    "ClientConfig": {"PingInterval": 999},
                },
            },
        )

    async def ws_connect(url: str) -> FakeWS:
        assert "service_id=42" in url
        return FakeWS()

    async def on_event(payload: dict) -> None:
        received.append(payload)

    transport = httpx.MockTransport(handler)
    connection = FeishuLongConnection(
        app_id="cli_test",
        app_secret="secret",
        api_base="https://open.feishu.example",
        on_event=on_event,
        http_client=httpx.AsyncClient(transport=transport),
        ws_connect=ws_connect,
    )
    await connection.start()
    for _ in range(50):
        if connection.running:
            break
        await asyncio.sleep(0.01)
    assert connection.running is True

    payload = _feishu_payload()
    frame = Frame(
        service=42,
        method=1,
        headers={
            "type": "event",
            "message_id": "m-1",
            "trace_id": "t-1",
            "sum": "1",
            "seq": "0",
        },
        payload=json.dumps(payload).encode(),
    )
    await inbound.put(encode_frame(frame))
    for _ in range(50):
        if sent:
            break
        await asyncio.sleep(0.01)
    assert received == [payload]
    ack = decode_frame(sent[0])
    assert json.loads(ack.payload) == {"code": 200}
    await connection.stop()
    await connection.aclose()
    assert connection.running is False


# ---------------------------------------------------------------- 微信登录


def _login_manager(tmp_path: Path, client: FakeWechatClient) -> WechatLoginManager:
    store = ImConnectorStore(tmp_path / "im.json")
    return WechatLoginManager(
        client=client,  # type: ignore[arg-type]
        store=store,
        auto_poll=False,
        qr_ttl_seconds=300,
    )


async def test_wechat_qr_login_and_confirm(tmp_path: Path) -> None:
    client = FakeWechatClient(
        qr=[
            {
                "qrcode": "qr-1",
                "qrcode_img_content": "https://weixin.example/bind/qr-1",
            }
        ],
        statuses=[
            {"status": "wait"},
            {"status": "scaned"},
            {
                "status": "confirmed",
                "bot_token": "fake-bot-token",
                "ilink_bot_id": "fake-bot-id",
                "baseurl": "https://ilink.example",
                "ilink_user_id": "wxid_owner",
            },
        ],
    )
    manager = _login_manager(tmp_path, client)
    started = await manager.start_login()
    assert started["state"] == "pending"
    assert started["qrcode_matrix"]
    assert started["backup_url"] == "https://weixin.example/bind/qr-1"
    assert "qrcode" not in started  # 原始二维码值不返回给客户端

    assert (await manager.poll_once(started["login_id"]))["state"] == "pending"
    assert (await manager.poll_once(started["login_id"]))["state"] == "scaned"
    confirmed = await manager.poll_once(started["login_id"])
    assert confirmed["state"] == "confirmed"
    assert "context_token" in confirmed["message"]
    credentials = manager.store.wechat_credentials()
    assert credentials is not None
    assert credentials.bot_token == "fake-bot-token"
    assert credentials.ilink_user_id == "wxid_owner"
    # 公开状态不包含 bot_token。
    assert "fake-bot-token" not in json.dumps(confirmed, ensure_ascii=False)


async def test_wechat_login_requires_mobile_code(tmp_path: Path) -> None:
    client = FakeWechatClient(
        qr=[{"qrcode": "qr-1", "qrcode_img_content": "https://weixin.example/qr-1"}],
        statuses=[{"status": "need_verifycode"}, {"status": "scaned"}],
    )
    manager = _login_manager(tmp_path, client)
    started = await manager.start_login()
    state = await manager.poll_once(started["login_id"])
    assert state["state"] == "need_verifycode"
    assert state["needs_verify_code"] is True

    manager.submit_verify_code(started["login_id"], "123456")
    state = await manager.poll_once(started["login_id"])
    assert state["state"] == "scaned"
    assert client.status_calls[-1] == "123456"


async def test_wechat_qr_expired_refreshes(tmp_path: Path) -> None:
    client = FakeWechatClient(
        qr=[
            {"qrcode": "qr-1", "qrcode_img_content": "https://weixin.example/qr-1"},
            {"qrcode": "qr-2", "qrcode_img_content": "https://weixin.example/qr-2"},
        ],
        statuses=[{"status": "expired"}],
    )
    manager = _login_manager(tmp_path, client)
    started = await manager.start_login()
    refreshed = await manager.poll_once(started["login_id"])
    assert refreshed["state"] == "pending"
    assert refreshed["backup_url"] == "https://weixin.example/qr-2"


async def test_wechat_binded_redirect_fails_clearly(tmp_path: Path) -> None:
    client = FakeWechatClient(
        qr=[{"qrcode": "qr-1", "qrcode_img_content": "https://weixin.example/qr-1"}],
        statuses=[{"status": "binded_redirect"}],
    )
    manager = _login_manager(tmp_path, client)
    started = await manager.start_login()
    failed = await manager.poll_once(started["login_id"])
    assert failed["state"] == "failed"
    assert "另一个后端" in failed["message"]


# ---------------------------------------------------------------- 微信收发


async def test_wechat_poller_replies_with_exact_context_token(
    container: Container,
) -> None:
    store = container.im_connectors.store
    _seed_wechat(store)
    client = FakeWechatClient(
        updates=[
            {
                "ret": 0,
                "get_updates_buf": "cursor-2",
                "msgs": [_wechat_message(context_token="ctx-1")],
            }
        ]
    )
    poller = WechatPoller(
        client=client,  # type: ignore[arg-type]
        store=store,
        assistant=container.im_connectors.wechat.assistant,
    )
    assert await poller.poll_once() == "ok"
    assert client.sent
    assert client.sent[0]["context_token"] == "ctx-1"
    assert client.sent[0]["to_user_id"] == "wxid_owner"
    assert "[local-echo]" in client.sent[0]["text"]
    assert store.wechat()["get_updates_buf"] == "cursor-2"
    assert store.wechat()["last_context_token"] == "ctx-1"

    # 同一条消息重投（上次发送失败 / 崩溃在游标推进前）：assistant 幂等回放，
    # 出站按「至少一次」再发一遍，避免用户永远收不到。
    client.updates = [
        {
            "ret": 0,
            "get_updates_buf": "cursor-3",
            "msgs": [_wechat_message(context_token="ctx-1")],
        }
    ]
    assert await poller.poll_once() == "ok"
    assert len(client.sent) == 2
    assert client.sent[1]["context_token"] == "ctx-1"


async def test_wechat_poller_ignores_group_and_other_senders(
    container: Container,
) -> None:
    store = container.im_connectors.store
    _seed_wechat(store)
    client = FakeWechatClient(
        updates=[
            {
                "ret": 0,
                "get_updates_buf": "cursor-2",
                "msgs": [
                    _wechat_message(group_id="group-1", text="群消息"),
                    _wechat_message(from_user_id="wxid_stranger", text="陌生人"),
                    _wechat_message(message_type=2, text="机器人自己的消息"),
                ],
            }
        ]
    )
    poller = WechatPoller(
        client=client,  # type: ignore[arg-type]
        store=store,
        assistant=container.im_connectors.wechat.assistant,
    )
    assert await poller.poll_once() == "ok"
    assert client.sent == []


async def test_wechat_poller_marks_reauth_on_minus_14(container: Container) -> None:
    store = container.im_connectors.store
    _seed_wechat(store)
    client = FakeWechatClient()
    client.get_updates = _raise_expired  # type: ignore[method-assign]
    service = WechatImService(
        enabled=True,
        api_base="https://ilink.example",
        assistant=container.im_connectors.wechat.assistant,
        store=store,
        client=client,  # type: ignore[arg-type]
        poller=WechatPoller(
            client=client,  # type: ignore[arg-type]
            store=store,
            assistant=container.im_connectors.wechat.assistant,
        ),
    )
    assert await service.poller.poll_once() == "reauth"
    status = service.status()
    assert status["needs_reauth"] is True
    assert status["connected"] is False
    assert "重新扫码" in status["message"]


async def _raise_expired(*args, **kwargs):  # noqa: ANN002, ANN003
    raise WechatSessionExpired("-14")


async def test_wechat_restart_resumes_persisted_cursor(tmp_path: Path) -> None:
    store = ImConnectorStore(tmp_path / "im.json")
    _seed_wechat(store)
    store.update_wechat(get_updates_buf="cursor-from-disk")
    client = FakeWechatClient(updates=[{"ret": 0, "msgs": [], "get_updates_buf": "next"}])
    poller = WechatPoller(
        client=client,  # type: ignore[arg-type]
        store=store,
        assistant=FakeAssistant(),
    )
    await poller._run()
    # 第一轮用磁盘游标；第二轮 fake 抛 -14 结束循环，不会回到空游标。
    assert client.cursors[0] == "cursor-from-disk"
    assert store.wechat()["get_updates_buf"] == "next"


async def test_wechat_notification_queues_and_flushes(container: Container) -> None:
    store = container.im_connectors.store
    _seed_wechat(store)
    client = FakeWechatClient(
        updates=[
            {
                "ret": 0,
                "get_updates_buf": "cursor-2",
                "msgs": [_wechat_message(context_token="ctx-live")],
            }
        ]
    )
    service = WechatImService(
        enabled=True,
        api_base="https://ilink.example",
        assistant=container.im_connectors.wechat.assistant,
        store=store,
        client=client,  # type: ignore[arg-type]
        poller=WechatPoller(
            client=client,  # type: ignore[arg-type]
            store=store,
            assistant=container.im_connectors.wechat.assistant,
        ),
    )
    # 互动窗口外 / 还没有 context_token：入队，不假装推送成功。
    assert await service.notify("任务已完成：示例") is False
    assert store.wechat()["pending_notices"] == ["任务已完成：示例"]

    await service.poller.poll_once()
    texts = [item["text"] for item in client.sent]
    assert "[local-echo]" in texts[0]
    assert "任务已完成：示例" in texts
    assert store.wechat()["pending_notices"] == []


# ---------------------------------------------------------------- API / 安全


def test_im_api_never_returns_wechat_token(
    settings: Settings, db_schema: None, tmp_path: Path
) -> None:
    secret = "fake-bot-token-must-not-leak"
    store = ImConnectorStore(settings.im_connectors_path)
    store.update_wechat(
        bot_token=secret,
        ilink_bot_id="fake-bot-id",
        baseurl="https://ilink.example",
        ilink_user_id="wxid_owner",
        last_context_token="fake-context-token",
    )
    configured = settings.model_copy(update={"wechat_im_enabled": False})
    app = create_app(configured)
    with TestClient(app) as client:
        status = client.get("/api/v1/im-connectors")
        assert status.status_code == 200
        body = status.text
        assert secret not in body
        assert "fake-context-token" not in body
        listing = client.get("/api/v1/mcp-connectors")
        assert listing.status_code == 200
        assert secret not in listing.text
        ids = {item["id"] for item in listing.json()["data"]["items"]}
        assert {"feishu", "wechat"} <= ids

    mode = stat.S_IMODE(os.stat(settings.im_connectors_path).st_mode)
    assert mode == 0o600


def test_im_api_connect_feishu_without_config_explains(settings: Settings, db_schema: None) -> None:
    app = create_app(settings)
    with TestClient(app) as client:
        response = client.post("/api/v1/im-connectors/feishu/connect")
        assert response.status_code == 422
        assert "FLUX_FEISHU_IM_APP_ID" in response.json()["message"]


# ---------------------------------------------------------------- 任务通知


async def test_task_terminal_event_pushes_to_connected_im(
    container: Container,
) -> None:
    service = container.im_connectors
    feishu_client = FakeFeishuClient()
    wechat_client = FakeWechatClient()
    service.feishu.client = feishu_client  # type: ignore[assignment]
    service.wechat.client = wechat_client  # type: ignore[assignment]
    service.store.update_feishu(chat_id="oc_chat_1")
    _seed_wechat(service.store)
    service.store.update_wechat(last_context_token="ctx-1")

    task = await container.task_repo.create(
        task_id=uuid.uuid4(),
        description="修复登录缺陷",
        priority=100,
        project_id=None,
        agent_id=None,
    )
    bus = EventBus()
    service.attach(bus)
    await bus.publish(
        Events.TASK_COMPLETED,
        {"task_id": str(task.id), "status": "completed", "description": task.description},
    )
    # 通知在后台任务里发送，不阻塞任务收尾。
    for _ in range(50):
        if feishu_client.sent and wechat_client.sent:
            break
        await asyncio.sleep(0.01)
    assert feishu_client.sent and feishu_client.sent[0][0] == "oc_chat_1"
    assert "任务已完成" in feishu_client.sent[0][1]
    assert wechat_client.sent and "任务已完成" in wechat_client.sent[0]["text"]
    assert wechat_client.sent[0]["context_token"] == "ctx-1"


async def test_wechat_ilink_client_headers_and_defaults(tmp_path: Path) -> None:
    """协议客户端不把 Authorization 加到二维码状态查询上，也不编造 context_token。"""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"qrcode": "q", "qrcode_img_content": "https://x"})

    client = WechatIlinkClient(
        api_base="https://ilink.example",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    await client.get_bot_qrcode()
    assert seen[0].headers["iLink-App-Id"] == "bot"
    assert seen[0].headers["AuthorizationType"] == "ilink_bot_token"
    assert "Authorization" not in seen[0].headers

    with pytest.raises(WechatApiError):
        await client.send_text(
            token="fake",
            to_user_id="wxid_owner",
            text="hi",
            context_token="",
        )
    await client.aclose()
