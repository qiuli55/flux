"""账号名下的设备注册与云端中转。

设备（Flux 实例：电脑 / 服务器）启动时用账号 access token 注册，拿到 device_token
（明文只回一次）；之后用它保持一条 WebSocket。手机登录后列出同账号设备，请求打到
`/api/devices/{device_id}/p/...`，由云端沿着这条 WebSocket 转发到设备并回传响应。

为什么是这个形状：
- 电脑通常在 NAT 后面，手机无法直连；设备主动外连云端，就不需要开端口或穿透。
- 云端只做转发，不解析业务语义；设备侧怎么处理请求由 Flux 自己决定。
- 中转按「请求-响应」成对，SSE 这类单向长流不在首版范围内（终端实时输出仍受限）。

安全边界：
- 注册/列表/注销用用户 access token（Cookie 或 Bearer 均可）。
- 中转 WebSocket 与心跳用 device_token，库里只存 sha256。
- 中转 HTTP 入口校验设备属于当前用户；设备离线时明确 503，不静默失败。
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import secrets
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import settings  # noqa: F401  （保留统一配置入口，部署时按需读取）
from ..db import SessionLocal, get_db
from ..deps import get_current_user
from ..models import Device, User, utcnow
from ..schemas import DeviceOut, DeviceRegisteredOut, DeviceRegisterIn
from ..security import sha256_hex

router = APIRouter(prefix="/api/devices", tags=["devices"])
log = logging.getLogger("flux_cloud.devices")

#: 在线设备 → 当前 WebSocket（同一设备重连时以最新一条为准）
_relay: dict[str, WebSocket] = {}
#: 请求 id → 等待中的响应 future
_pending: dict[str, asyncio.Future[dict]] = {}

#: 中转超时（秒）：设备侧跑 Apply / 读大文件时可能慢，给到 60s
RELAY_TIMEOUT_SEC = 60
#: 心跳超过这个时长没有更新就视为离线
ONLINE_WINDOW_SEC = 90


def _device_from_token(db: Session, device_id: str, token: str) -> Device | None:
    device = db.scalar(select(Device).where(Device.device_id == device_id))
    if device is None or device.revoked_at is not None or not token:
        return None
    if not secrets.compare_digest(device.token_hash, sha256_hex(token)):
        return None
    return device


def _owned_device(db: Session, user: User, device_id: str) -> Device:
    device = db.scalar(
        select(Device).where(Device.device_id == device_id, Device.user_id == user.id)
    )
    if device is None or device.revoked_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "设备不存在")
    return device


def _is_online(device: Device) -> bool:
    if device.device_id in _relay:
        return True
    if device.last_seen_at is None:
        return False
    return (utcnow() - device.last_seen_at).total_seconds() <= ONLINE_WINDOW_SEC


def _to_out(device: Device) -> DeviceOut:
    return DeviceOut(
        device_id=device.device_id,
        name=device.name,
        platform=device.platform,
        version=device.version,
        status="online" if _is_online(device) else "offline",
        last_seen_at=device.last_seen_at,
        created_at=device.created_at,
    )


@router.post("", response_model=DeviceRegisteredOut, summary="注册或重连一台设备")
def register_device(
    payload: DeviceRegisterIn,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> DeviceRegisteredOut:
    name = payload.name.strip()
    token = secrets.token_urlsafe(32)
    device = db.scalar(select(Device).where(Device.user_id == user.id, Device.name == name))
    if device is None:
        device = Device(
            user_id=user.id,
            device_id=f"dev_{uuid.uuid4().hex[:12]}",
            name=name,
            platform=payload.platform,
            version=payload.version,
            token_hash=sha256_hex(token),
            status="offline",
        )
        db.add(device)
    else:
        # 同名重注册：轮换 device_token，旧连接自然失效
        device.platform = payload.platform
        device.version = payload.version
        device.token_hash = sha256_hex(token)
        device.revoked_at = None
    db.commit()
    db.refresh(device)
    log.info("设备注册 user_id=%s device=%s platform=%s", user.id, device.device_id, device.platform)
    return DeviceRegisteredOut(**_to_out(device).model_dump(), device_token=token)


@router.get("", response_model=list[DeviceOut], summary="列出当前账号的设备")
def list_devices(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[DeviceOut]:
    rows = db.scalars(
        select(Device)
        .where(Device.user_id == user.id, Device.revoked_at.is_(None))
        .order_by(Device.created_at)
    ).all()
    return [_to_out(row) for row in rows]


@router.post("/{device_id}/revoke", summary="注销设备（同时断开中转连接）")
def revoke_device(
    device_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> dict[str, bool]:
    device = _owned_device(db, user, device_id)
    device.revoked_at = utcnow()
    device.status = "offline"
    db.commit()
    log.info("设备注销 user_id=%s device=%s", user.id, device_id)
    return {"ok": True}


@router.post("/{device_id}/heartbeat", summary="设备心跳")
async def heartbeat(
    device_id: str,
    request: Request,
    db: Session = Depends(get_db),
) -> dict[str, bool]:
    token = _bearer(request.headers.get("authorization", ""))
    device = _device_from_token(db, device_id, token)
    if device is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "设备令牌无效")
    device.last_seen_at = utcnow()
    device.status = "online" if device_id in _relay else "offline"
    db.commit()
    return {"ok": True}


@router.websocket("/{device_id}/relay")
async def relay(websocket: WebSocket, device_id: str) -> None:
    """设备侧主动连上来的中转通道。首帧由云端发 ping，设备回 pong 证明可用。"""
    token = _bearer(websocket.headers.get("authorization", "")) or websocket.query_params.get(
        "token", ""
    )
    db = SessionLocal()
    try:
        device = _device_from_token(db, device_id, token)
        if device is None:
            await websocket.close(code=4401)
            return
        device.status = "online"
        device.last_seen_at = utcnow()
        db.commit()
    finally:
        db.close()

    await websocket.accept()
    previous = _relay.get(device_id)
    _relay[device_id] = websocket
    if previous is not None and previous is not websocket:
        try:
            await previous.close(code=4000)
        except RuntimeError:
            pass
    log.info("中转通道建立 device=%s", device_id)

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if message.get("type") == "res":
                future = _pending.get(str(message.get("id") or ""))
                if future is not None and not future.done():
                    future.set_result(message)
    except WebSocketDisconnect:
        pass
    finally:
        if _relay.get(device_id) is websocket:
            _relay.pop(device_id, None)
            db = SessionLocal()
            try:
                device = db.scalar(select(Device).where(Device.device_id == device_id))
                if device is not None:
                    device.status = "offline"
                    db.commit()
            finally:
                db.close()
        log.info("中转通道断开 device=%s", device_id)


@router.api_route(
    "/{device_id}/p/{path:path}",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"],
    summary="经云端中转访问设备的 Flux API",
)
async def proxy(
    device_id: str,
    path: str,
    request: Request,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> Response:
    _owned_device(db, user, device_id)
    websocket = _relay.get(device_id)
    if websocket is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "设备离线，请先让设备端连上账号")

    body = await request.body()
    request_id = uuid.uuid4().hex
    payload = {
        "type": "req",
        "id": request_id,
        "method": request.method,
        "path": path,
        "query": request.url.query,
        "headers": {
            key: value
            for key, value in request.headers.items()
            if key.lower() in {"content-type", "accept", "accept-language"}
        },
        "body_b64": base64.b64encode(body).decode("ascii"),
    }

    loop = asyncio.get_running_loop()
    future: asyncio.Future[dict] = loop.create_future()
    _pending[request_id] = future
    try:
        await websocket.send_text(json.dumps(payload, ensure_ascii=False))
        result = await asyncio.wait_for(future, timeout=RELAY_TIMEOUT_SEC)
    except asyncio.TimeoutError:
        raise HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, "设备响应超时")
    except (RuntimeError, WebSocketDisconnect):
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "设备连接已断开")
    finally:
        _pending.pop(request_id, None)

    content = base64.b64decode(str(result.get("body_b64") or ""))
    headers = {
        key: value
        for key, value in (result.get("headers") or {}).items()
        if key.lower() in {"content-type", "cache-control", "etag"}
    }
    return Response(
        content=content,
        status_code=int(result.get("status") or 502),
        headers=headers,
    )


def _bearer(header: str) -> str:
    value = (header or "").strip()
    if value.lower().startswith("bearer "):
        return value[7:].strip()
    return ""


__all__ = ["router", "RELAY_TIMEOUT_SEC", "ONLINE_WINDOW_SEC"]
