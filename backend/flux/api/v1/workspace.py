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
from flux.enums import RecoveryResolution
from flux.schemas.api import (
    ChangeIdsRequest,
    ExpireRequest,
    GroupRequest,
    RecoveryResolveRequest,
    RejectRequest,
)

router = APIRouter(prefix="/workspace", tags=["workspace"])


@router.get("/changes")
async def list_changes(
    project_id: str | None = None,
    task_id: str | None = None,
    group_id: str | None = None,
    file_path: str | None = None,
    status: str | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出提案。group_id 看整组、file_path 看单个文件，都支持（P0-02 按文件/按组审核）。"""
    changes = await container.workspace.list(
        project_id=project_id,
        task_id=task_id,
        group_id=group_id,
        file_path=file_path,
        status=status,
    )
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
    """批准提案但不落盘：pending → accepted，等人工再决定何时 apply。

    整批先校验再执行（P0-03）：列表里有一个坏 ID 或非法状态，整批都不动。
    """
    accepted = [c.to_dict() for c in await container.workspace.accept_many(payload.change_ids)]
    return ok(accepted, metadata={"count": len(accepted)})


@router.post("/apply")
async def apply_changes(
    payload: ChangeIdsRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """原子批量落盘（P0-03）：要么全部成功，要么全部恢复原样并标记 failed。"""
    applied = [c.to_dict() for c in await container.workspace.apply_many(payload.change_ids)]
    return ok(applied, metadata={"count": len(applied)})


@router.post("/reject")
async def reject_changes(
    payload: RejectRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    rejected = [
        c.to_dict()
        for c in await container.workspace.reject_many(payload.change_ids, reason=payload.reason)
    ]
    return ok(rejected, metadata={"count": len(rejected), "reason": payload.reason})


@router.post("/accept-group")
async def accept_group(
    payload: GroupRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """整组批准：一次提交（group_id）下的全部提案一起进入 accepted。"""
    accepted = [c.to_dict() for c in await container.workspace.accept_group(payload.group_id)]
    return ok(accepted, metadata={"count": len(accepted), "group_id": payload.group_id})


@router.post("/reject-group")
async def reject_group(
    payload: GroupRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    rejected = [
        c.to_dict()
        for c in await container.workspace.reject_group(payload.group_id, reason=payload.reason)
    ]
    return ok(rejected, metadata={"count": len(rejected), "group_id": payload.group_id})


@router.post("/apply-group")
async def apply_group(
    payload: GroupRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """整组原子落盘：任一条失败则整组恢复原样（与 apply 同一语义）。"""
    applied = [c.to_dict() for c in await container.workspace.apply_group(payload.group_id)]
    return ok(applied, metadata={"count": len(applied), "group_id": payload.group_id})


@router.post("/expire")
async def expire_changes(
    payload: ExpireRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """显式让一批提案失效（P0-02）：pending/accepted → expired 终态。

    过期的提案不能再被批准或落盘，只能由 agent 重新提交新提案。
    """
    expired = [
        c.to_dict()
        for c in await container.workspace.expire_many(payload.change_ids, reason=payload.reason)
    ]
    return ok(expired, metadata={"count": len(expired)})


@router.post("/expire-stale")
async def expire_stale(container: Container = Depends(get_container)) -> dict[str, object]:
    """清理所有已超过审核有效期的提案（幂等，可被定时任务/人工调用）。"""
    expired = [c.to_dict() for c in await container.workspace.expire_stale()]
    return ok(expired, metadata={"count": len(expired)})


@router.get("/apply-batches")
async def list_apply_batches(
    recent: int = 0,
    limit: int = 20,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出最近的 Apply 批（最新在前）；`recent=1` 只返回可回滚的 applied 批。

    `recent=1` 驱动前端"回滚上一次"按钮：取第一项即最近一次成功 Apply。
    """
    batches = await container.workspace.list_recent_batches(recent=bool(recent), limit=limit)
    return ok([b.to_dict() for b in batches], metadata={"count": len(batches)})


@router.post("/apply-batches/{batch_id}/rollback")
async def rollback_batch(
    batch_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """整批回滚一次 Apply（P1-2）：先全量预检，再按批内逆序还原；不产生任何 git 提交。"""
    batch = await container.workspace.rollback_batch(batch_id)
    return ok(batch.to_dict(), metadata={"batch_id": str(batch.id)})


@router.post("/changes/{change_id}/rollback")
async def rollback_change(
    change_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """单条回滚：撤销一条已落盘提案对文件的改动。"""
    change = await container.workspace.rollback_change(change_id)
    return ok(change.to_dict(), metadata={"change_id": str(change.id)})


@router.get("/recovery")
async def list_recovery(container: Container = Depends(get_container)) -> dict[str, object]:
    """列出所有待人工决策的崩溃恢复项（P0-1 §2.2）：只读，不改任何状态。

    每项含三个版本——备份里的改动前原文 / 当前磁盘真实内容 / Flux 原本要写入的提案内容，
    以及 `backup_available`（决定能否选"覆盖备份"）与 `disk_state`。
    """
    items = await container.workspace.list_pending_recovery_items()
    return ok([item.to_dict() for item in items], metadata={"count": len(items)})


@router.post("/recovery/resolve")
async def resolve_recovery(
    payload: RecoveryResolveRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """人工决策一条挂起的恢复项：cover = 覆盖备份（还原原文）；keep = 保持现状（提案作废）。"""
    change = await container.workspace.resolve_recovery(
        payload.change_id, RecoveryResolution(payload.action)
    )
    return ok(change.to_dict(), metadata={"action": payload.action})
