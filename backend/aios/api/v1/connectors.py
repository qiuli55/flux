"""Connector API（主规格 §12.7）。

调用方的权限在服务端按 agent_id 解析——客户端不能自行声明自己有哪些能力（§14.5 最小权限）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from aios.api.deps import get_container
from aios.api.response import ok
from aios.container import Container
from aios.enums import Capability
from aios.schemas.api import ConnectorExecuteRequest

router = APIRouter(prefix="/connectors", tags=["connectors"])


@router.post("/execute")
async def execute_connector(
    payload: ConnectorExecuteRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    granted: frozenset[Capability] = frozenset()
    if payload.agent_id:
        # Agent 不存在会抛 NotFoundError（404）
        granted = container.agents.get(payload.agent_id).spec.permissions
    entry = await container.connectors.execute(
        payload.connector,
        payload.action,
        payload.parameters,
        agent_id=payload.agent_id,
        granted=granted,
    )
    return ok(entry)
