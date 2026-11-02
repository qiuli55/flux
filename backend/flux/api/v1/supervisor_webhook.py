"""Supervisor 的平台回调入口（飞书事件订阅）。

这里**不**走 `require_rest_auth`：飞书服务器没有本机令牌，验签靠回调体里的
verification token（见 `flux.core.supervisor.feishu`，未配置时一律拒绝）。
响应必须是飞书约定的原始 JSON（challenge 回显），不能套 `ok()` 信封。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from flux.api.deps import get_container
from flux.container import Container

router = APIRouter(prefix="/supervisor", tags=["supervisor"])


@router.post("/channels/feishu")
async def feishu_callback(
    request: Request,
    container: Container = Depends(get_container),
) -> JSONResponse:
    """飞书事件回调：验签 → 解析文本消息 → 幂等入库。"""
    body = await request.body()
    result = await container.supervisor_feishu.handle(body)
    return JSONResponse(result.payload, status_code=result.status_code)
