"""Virtual Workspace API（主规格 §12.5）。

注意：提案的产生原本不经过本组接口——Agent 执行产出提案（M2）。
⑫ 最小 IDE 需要"让 AI 改"这一个真正的写入口：一句需求 → Developer Agent →
`CodeChangeSet` → 逐文件落成 Proposal(pending)，见 `flow.py` 与 §12.5 的落地说明。
审查动作（列出 / 通过 / 应用 / 拒绝）与 §12.5 一致。
`accept` 与 `apply` 是两步：accept 只是批准（pending → accepted），会把改动写进用户
真实文件的是 apply（经 Apply Engine，§7.6）。直接调 apply 时若提案仍是 pending，
服务层会先自动转 accepted —— 调用 apply 本身即视为人工批准（§7.5）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import ChangeIdsRequest, GenerateProposalsRequest, RejectRequest

router = APIRouter(prefix="/workspace", tags=["workspace"])


@router.post("/generate")
async def generate_proposals(
    payload: GenerateProposalsRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """让 Developer Agent 依据一句需求产出提案，落成 pending 提案（⑫）。"""
    outcome = await container.dev_flow.produce(
        payload.instruction,
        paths=payload.paths,
        task_id=payload.task_id,
        project_id=payload.project_id,
    )
    return ok(outcome.to_dict())


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
