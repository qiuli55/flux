"""Virtual Workspace API（主规格 §12.5）。

注意：提案的产生不经过本组接口——Agent 执行产出提案（M2），
这里只暴露"列出 / 应用 / 拒绝"三个人工审查动作，与 §12.5 完全一致。
调用 /workspace/apply 即视为人工批准（§7.5 审查操作）。
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
    status: str | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    changes = container.workspace.list(project_id=project_id, status=status)
    return ok([c.to_dict() for c in changes], metadata={"count": len(changes)})


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
        proposal = await container.workspace.reject(change_id)
        rejected.append(proposal.to_dict())
    return ok(rejected, metadata={"count": len(rejected), "reason": payload.reason})
