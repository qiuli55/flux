"""连接器 · 飞书 / 个人微信 API。

与 `/api/v1/supervisor/channels/*` 完全独立：这里不接收任何平台回调、不要求
OpenClaw 转发器，也不读写 Supervisor 的 trigger。飞书走服务端主动长连接，
微信走服务端主动长轮询，客户端只负责展示状态、扫码与断开。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.im.feishu import FeishuApiError
from flux.core.im.wechat_ilink import WechatApiError
from flux.errors import ValidationError
from flux.schemas.api import ImWechatCodeRequest

router = APIRouter(prefix="/im-connectors", tags=["im-connectors"])


@router.get("")
async def list_im_connectors(container: Container = Depends(get_container)) -> dict[str, object]:
    """飞书 / 个人微信连接器状态（不含任何 token / QR 原值）。"""
    return ok(container.im_connectors.status())


@router.post("/feishu/connect")
async def connect_feishu(container: Container = Depends(get_container)) -> dict[str, object]:
    """校验飞书应用凭据并启动长连接；随后用户在飞书里发第一条单聊消息。"""
    try:
        return ok(await container.im_connectors.connect_feishu())
    except FeishuApiError as exc:
        raise ValidationError(str(exc)) from exc


@router.post("/feishu/disconnect")
async def disconnect_feishu(container: Container = Depends(get_container)) -> dict[str, object]:
    """断开飞书长连接并清除已绑定的单聊（服务端 App 凭据保留）。"""
    return ok(await container.im_connectors.disconnect_feishu())


@router.post("/wechat/connect")
async def connect_wechat(container: Container = Depends(get_container)) -> dict[str, object]:
    """向官方 iLink 申请绑定二维码；返回可直接绘制的矩阵与备份链接。"""
    try:
        return ok(await container.im_connectors.connect_wechat())
    except WechatApiError as exc:
        raise ValidationError(str(exc)) from exc


@router.get("/wechat/login/{login_id}")
async def wechat_login_status(
    login_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """查询扫码绑定进度（pending / scaned / need_verifycode / confirmed / failed）。"""
    try:
        return ok(container.im_connectors.wechat_login_status(login_id))
    except WechatApiError as exc:
        raise ValidationError(str(exc)) from exc


@router.post("/wechat/login/{login_id}/verify-code")
async def wechat_verify_code(
    login_id: str,
    payload: ImWechatCodeRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """提交手机微信显示的绑定数字（官方 PIN 流程）。"""
    try:
        return ok(container.im_connectors.submit_wechat_code(login_id, payload.code))
    except WechatApiError as exc:
        raise ValidationError(str(exc)) from exc


@router.post("/wechat/disconnect")
async def disconnect_wechat(container: Container = Depends(get_container)) -> dict[str, object]:
    """停止轮询并清除本机保存的微信绑定凭据。"""
    return ok(await container.im_connectors.disconnect_wechat())


__all__ = ["router"]
