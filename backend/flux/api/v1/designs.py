"""设计工作室 API（Luma Studio 的鉴权代理）。

设计稿的持久化、AI 改组件都在本机 Luma Studio（默认 127.0.0.1:8091）。那套接口只监听
回环，公网路径已被 nginx 屏蔽；手机端不能直连，只能带 Flux 登录态走这里。这样设计稿
与 Flux 项目共用同一身份，不需要另建账号体系，也不在 Flux 库里复制一份设计数据。

代理只做三件事：转发、解包统一响应体、把上游错误码翻译成 Flux 的领域错误。
"""

from __future__ import annotations

import re
from collections.abc import AsyncIterator
from typing import Any

import httpx
from fastapi import APIRouter, Body, Depends, Query

from flux.api.deps import get_settings_dep
from flux.api.response import ok
from flux.config import Settings
from flux.core.design_render import render_design_html
from flux.errors import AIOSError, BadRequestError, ConflictError, NotFoundError

router = APIRouter(prefix="/designs", tags=["designs"])

#: 与 Luma Studio 同一条规则：设计稿 id 只允许小写字母、数字与连字符。
_DESIGN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


class DesignStudioUnavailable(AIOSError):
    """设计服务不可达或上游故障；手机端可提示"稍后再试"，不暴露内网地址。"""

    code = "design_studio_unavailable"
    http_status = 503


async def design_client(
    settings: Settings = Depends(get_settings_dep),
) -> AsyncIterator[httpx.AsyncClient]:
    """每次请求一个短连接客户端；测试通过 dependency_overrides 注入 MockTransport。"""
    timeout = httpx.Timeout(settings.design_studio_timeout_seconds, connect=3.0)
    base_url = settings.design_studio_url.rstrip("/")
    # Luma Studio 永远是本机/内网地址：不走系统代理，否则开着 HTTP_PROXY 的机器会 503。
    async with httpx.AsyncClient(
        base_url=base_url, timeout=timeout, trust_env=False
    ) as client:
        yield client


def _require_design_id(design_id: str) -> str:
    if not _DESIGN_ID_RE.match(design_id):
        raise BadRequestError("设计稿 id 不合法")
    return design_id


def _upstream_error(response: httpx.Response) -> AIOSError:
    """把 Luma 的 {ok:false,error} 翻译成带错误码的领域异常。"""
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    message = str(payload.get("error") or "设计服务暂时不可用")
    if response.status_code == 404:
        return NotFoundError(message)
    if response.status_code == 409:
        return ConflictError(
            message,
            code="design_conflict",
            details={"version": payload.get("version")},
        )
    if response.status_code == 429:
        return AIOSError(message, code="rate_limited", http_status=429)
    if response.status_code >= 500:
        return DesignStudioUnavailable(message)
    return BadRequestError(message)


async def _read(response: httpx.Response) -> dict[str, Any]:
    """校验上游状态码并返回 JSON 对象。"""
    if response.status_code >= 400:
        raise _upstream_error(response)
    try:
        payload = response.json()
    except ValueError as exc:
        raise DesignStudioUnavailable("设计服务返回了非 JSON 响应") from exc
    if not isinstance(payload, dict) or not payload.get("ok"):
        raise DesignStudioUnavailable("设计服务返回了异常响应")
    return payload


async def _request(
    client: httpx.AsyncClient,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    try:
        response = await client.request(method, path, json=payload)
    except httpx.HTTPError as exc:
        raise DesignStudioUnavailable("设计服务连接失败") from exc
    return await _read(response)


async def load_design(client: httpx.AsyncClient, design_id: str) -> dict[str, Any]:
    """取整份设计稿；预览渲染只读上游，不复制设计数据。"""
    design_id = _require_design_id(design_id)
    payload = await _request(client, "GET", f"/api/designs/{design_id}")
    design = payload.get("design")
    if not isinstance(design, dict):
        raise DesignStudioUnavailable("设计服务返回了异常设计稿")
    return design


@router.get("")
async def list_designs(
    project_id: str | None = Query(default=None, description="按 Flux 项目过滤；缺省返回全部"),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    payload = await _request(client, "GET", "/api/designs")
    designs = payload.get("designs") or []
    if not isinstance(designs, list):
        raise DesignStudioUnavailable("设计服务返回了异常列表")
    if project_id is not None:
        designs = [
            item
            for item in designs
            if isinstance(item, dict) and item.get("project_id") == project_id
        ]
    return ok(designs, metadata={"count": len(designs)})


@router.post("")
async def create_design(
    payload: dict[str, Any] = Body(...),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    created = await _request(client, "POST", "/api/designs", payload)
    return ok(created.get("design"), metadata={"summary": created.get("summary")})


@router.get("/{design_id}")
async def get_design(
    design_id: str,
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    return ok(await load_design(client, design_id))


@router.get("/{design_id}/preview")
async def preview_design(
    design_id: str,
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    """把设计稿渲染成可交互单文件 HTML，供 App 内 WebView 离线预览。"""
    design = await load_design(client, design_id)
    html = render_design_html(design)
    screens = design.get("screens")
    try:
        version = int(design.get("version") or 0)
    except (TypeError, ValueError):
        version = 0
    preview = {
        "design_id": str(design.get("id") or ""),
        "name": str(design.get("name") or "").strip() or "未命名设计稿",
        "version": version,
        "screen_count": len(screens) if isinstance(screens, list) else 0,
        "size_bytes": len(html.encode("utf-8")),
        "html": html,
    }
    return ok(preview, metadata={"screen_count": preview["screen_count"]})


@router.put("/{design_id}")
async def save_design(
    design_id: str,
    payload: dict[str, Any] = Body(...),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    """保存整份设计稿；带 base_version 时做乐观锁，冲突返回 409 + 当前版本。"""
    saved = await _request(client, "PUT", f"/api/designs/{_require_design_id(design_id)}", payload)
    return ok(saved.get("design"), metadata={"version": (saved.get("design") or {}).get("version")})


@router.delete("/{design_id}")
async def delete_design(
    design_id: str,
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    """删除整份设计稿；不可恢复，手机端需要长按确认后才调用。"""
    design_id = _require_design_id(design_id)
    deleted = await _request(client, "DELETE", f"/api/designs/{design_id}")
    return ok({"id": deleted.get("id") or design_id})


@router.post("/{design_id}/ai")
async def ask_design_ai(
    design_id: str,
    payload: dict[str, Any] = Body(...),
    client: httpx.AsyncClient = Depends(design_client),
) -> dict[str, object]:
    """对单个组件提修改要求；上游把模型输出收敛成只作用于该节点的属性补丁。"""
    _require_design_id(design_id)
    result = await _request(client, "POST", "/api/ask", payload)
    return ok(
        {"reply": result.get("reply"), "ops": result.get("ops"), "model": result.get("model")},
        metadata={"design_id": design_id, "ops": len(result.get("ops") or [])},
    )
