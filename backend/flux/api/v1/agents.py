"""Agent API（主规格 §12.3）。"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.agent_runtime.context import AgentContext, AgentHandle, AgentSpec
from flux.schemas.api import AgentCreateRequest, AgentExecuteRequest

router = APIRouter(prefix="/agents", tags=["agents"])


@router.post("")
async def create_agent(
    payload: AgentCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    spec = AgentSpec(
        name=payload.name,
        role=payload.role,
        model_provider=payload.model_provider,
        model_name=payload.model_name,
        description=payload.description,
        system_prompt=payload.system_prompt,
        skills=tuple(payload.skills),
        tools=tuple(payload.tools),
        permissions=frozenset(payload.permissions),
    )
    handle: AgentHandle = container.agents.create(spec)
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


@router.post("/{agent_id}/execute")
async def execute_agent(
    agent_id: str,
    payload: AgentExecuteRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    result = await container.agents.execute(agent_id, payload.instruction, task_id=payload.task_id)
    return ok(result.to_dict())


__all__ = ["AgentContext", "router"]
