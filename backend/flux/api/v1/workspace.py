"""Virtual Workspace API（主规格 §12.5）。

提案由 agent 经 MCP `proposal.create` 进入（Phase 2）；Flux 不代 agent 生成提案，
本组接口只负责人工审查动作（列出 / 通过 / 应用 / 拒绝）。
`accept` 与 `apply` 是两步：accept 只是批准（pending → accepted），会把改动写进用户
真实文件的是 apply（经 Apply Engine，§7.6）。直接调 apply 时若提案仍是 pending，
服务层会先自动转 accepted —— 调用 apply 本身即视为人工批准（§7.5）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import ChangeIdsRequest, RejectRequest

router = APIRouter(prefix="/workspace", tags=["workspace"])


@router.get("/changes")
async def list_changes(
    project_id: str | None = None,
    task_id: str | None = None,
    status: str | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    changes = await container.workspace.list(project_id=project_id, task_id=task_id, status=status)
    return ok([c.to_dict() for c in changes], metadata={"count": len(changes)})


@router.get("/changes/{change_id}")
async def get_change(
    change_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    change = await container.workspace.get(change_id)
    return ok(change.to_dict())


@router.post("/accept")
async def accept_changes(
    payload: ChangeIdsRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """批准提案但不落盘：pending → accepted，等人工再决定何时 apply。"""
    accepted = []
    for change_id in payload.change_ids:
        proposal = await container.workspace.accept(change_id)
        accepted.append(proposal.to_dict())
    return ok(accepted, metadata={"count": len(accepted)})


@router.post("/apply")
async def apply_changes(
    payload: ChangeIdsRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    applied = []
    for change_id in payload.change_ids:
        proposal = await container.workspace.apply(change_id)
        applied.append(proposal.to_dict())
    return ok(applied, metadata={"count": len(applied)})


@router.post("/reject")
async def reject_changes(
    payload: RejectRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    rejected = []
    for change_id in payload.change_ids:
        proposal = await container.workspace.reject(change_id, reason=payload.reason)
        rejected.append(proposal.to_dict())
    return ok(rejected, metadata={"count": len(rejected), "reason": payload.reason})
