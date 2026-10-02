"""DSH Agent Runtime API（集成方案 §18 Phase 1）。

只暴露 Run 生命周期与配置快照；真实模型往返由 SDK 子进程完成，响应统一走 ok() 包装，
预期异常（未启用 / Run 不存在）由 flux.api.errors 的处理器映射成统一错误码。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.dsh import DshRunCreateRequest

router = APIRouter(prefix="/dsh", tags=["dsh"])


@router.post("/runs")
async def start_run(
    payload: DshRunCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    run = await container.dsh.start_run(payload.instruction, session_id=payload.session_id)
    return ok(run.to_dict())


@router.get("/runs")
async def list_runs(container: Container = Depends(get_container)) -> dict[str, object]:
    runs = container.dsh.list_runs()
    return ok([r.to_dict() for r in runs], metadata={"count": len(runs)})


@router.get("/runs/{run_id}")
async def get_run(run_id: str, container: Container = Depends(get_container)) -> dict[str, object]:
    return ok((await container.dsh.get_run_async(run_id)).to_dict())


@router.post("/runs/{run_id}/interrupt")
async def interrupt_run(
    run_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """请求取消一次 Run：进程树确认清理干净才返回 CANCELLED（P2-15 §2.5）。"""
    return ok((await container.dsh.cancel(run_id)).to_dict())


@router.get("/status")
async def status(container: Container = Depends(get_container)) -> dict[str, object]:
    return ok(container.dsh.status())


__all__ = ["router"]
