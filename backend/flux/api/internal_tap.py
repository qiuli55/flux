"""内部旁路端点：只给本机 Agent runtime 用，不挂 REST 鉴权。

DSH 的模型 base_url 会在一次 Run 内临时指到这里（见 llm_stream_tap 模块说明）。
token 是一次性随机串、Run 结束即注销；但仅靠路径里的 token 不够——nginx 曾把
整个 ASGI 反代到公网 `/mobile/`，路径一旦泄露就能被外部当开放代理用。因此入口
额外要求**同机直连**（回环且无转发头），公网来的请求一律当不存在（404）。
"""

from __future__ import annotations

import json
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response, StreamingResponse

from flux.api.auth import is_trusted_local
from flux.core.agent_runtime.llm_stream_tap import (
    filter_request_headers,
    filter_response_headers,
    is_agent_request,
    llm_stream_tap,
)
from flux.logging import get_logger

logger = get_logger(__name__)

router = APIRouter(tags=["internal"], include_in_schema=False)


@router.post("/internal/llm-tap/{token}/{path:path}")
async def llm_tap(token: str, path: str, request: Request) -> Response:
    """把 DSH 对模型 API 的请求原样转发，并顺流把增量发到事件总线。"""
    # 即使路径 token 正确也不给公网用：这个端点能拿 Flux 的模型上游做开放代理。
    if not is_trusted_local(request):
        raise HTTPException(status_code=404, detail="Not Found")

    target = llm_stream_tap.target(token)
    if target is None:
        raise HTTPException(status_code=404, detail="tap 未登记或已注销")

    body = await request.body()
    payload: Any = None
    if body:
        try:
            payload = json.loads(body)
        except ValueError:
            payload = None
    tap = isinstance(payload, dict) and is_agent_request(payload)

    headers = filter_request_headers(request.headers)
    # 关掉压缩：要边转发边解析 SSE，压缩流解不干净
    headers["accept-encoding"] = "identity"
    url = target.join(path, request.url.query)

    client = llm_stream_tap.client()
    upstream_request = client.build_request(request.method, url, headers=headers, content=body)
    try:
        upstream = await client.send(upstream_request, stream=True)
    except httpx.HTTPError as exc:
        logger.warning("llm-tap 上游不可达 run=%s: %s", target.run_id, exc)
        raise HTTPException(status_code=502, detail=f"上游模型不可达：{exc}") from exc

    response_headers = filter_response_headers(upstream.headers)
    return StreamingResponse(
        llm_stream_tap.relay(target, upstream, tap=tap),
        status_code=upstream.status_code,
        headers=response_headers,
    )


__all__ = ["router"]
