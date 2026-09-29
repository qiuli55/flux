"""API v1 路由聚合（主规格 §12）。"""

from fastapi import APIRouter

from flux.api.v1 import agents, connectors, health, models, tasks, workspace

api_v1_router = APIRouter()
api_v1_router.include_router(health.router)
api_v1_router.include_router(agents.router)
api_v1_router.include_router(tasks.router)
api_v1_router.include_router(workspace.router)
api_v1_router.include_router(models.router)
api_v1_router.include_router(connectors.router)

__all__ = ["api_v1_router"]
