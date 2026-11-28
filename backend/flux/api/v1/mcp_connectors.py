"""设置页「连接器」API（内置目录 + 自定义 MCP 服务器）。

与 `/api/v1/connectors/execute`（Agent 执行 connector action，主规格 §12.7）分离：
本路由只做连接器设置，不改旧 action API 的契约。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import McpConnectorCreateRequest

router = APIRouter(prefix="/mcp-connectors", tags=["mcp-connectors"])


@router.get("")
async def list_connectors(container: Container = Depends(get_container)) -> dict[str, object]:
    """内置连接器目录 + 用户自定义 MCP 连接器（GitHub / Cursor / Antigravity 状态实时映射）。"""
    return ok(
        container.mcp_connectors.list_all(
            container.github.status(),
            cursor_status=container.cursor_account.status(),
            antigravity_status=container.antigravity_account.status(),
            im_items=container.im_connectors.connector_items(),
        )
    )


@router.post("")
async def add_connector(
    payload: McpConnectorCreateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """探测自定义 MCP 服务器；initialize + tools/list 通过后才保存并标记已连接。"""
    return ok(await container.mcp_connectors.add(payload.name, payload.url))


@router.post("/{connector_id}/probe")
async def probe_connector(
    connector_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """重新测试已保存的连接器，更新状态与工具清单。"""
    return ok(await container.mcp_connectors.probe(connector_id))


@router.delete("/{connector_id}")
async def delete_connector(
    connector_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """移除自定义连接器。"""
    return ok(container.mcp_connectors.remove(connector_id))


__all__ = ["router"]
