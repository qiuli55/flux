"""Agent API（主规格 §12.3）：Agent 档案的注册与查询。

Flux 不执行 Agent（目标架构 §1）——这里只登记 agent 的身份与权限边界；
执行由各 agent 自己的运行时完成，能力（上下文 / 工具 / Skill）经 Flux MCP 面获得。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.agent_runtime.manager import AgentHandle, AgentSpec
from flux.schemas.api import AgentCreateRequest

router = APIRouter(prefix="/agents", tags=["agents"])


@router.post("")
async def create_agent(
    payload: AgentCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    spec = AgentSpec(
        name=payload.name,
        role=payload.role,
        description=payload.description,
        skills=tuple(payload.skills),
        tools=tuple(payload.tools),
        permissions=frozenset(payload.permissions),
    )
    handle: AgentHandle = await container.agents.create(spec)
    return ok(handle.to_dict())


@router.get("")
async def list_agents(container: Container = Depends(get_container)) -> dict[str, object]:
    handles = container.agents.list()
    return ok([h.to_dict() for h in handles], metadata={"count": len(handles)})


@router.get("/{agent_id}")
async def get_agent(
    agent_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    return ok(container.agents.get(agent_id).to_dict())


__all__ = ["router"]
