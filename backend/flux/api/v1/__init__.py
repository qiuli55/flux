"""API v1 路由聚合（主规格 §12）。"""

from fastapi import APIRouter

from flux.api.v1 import (
    agents,
    billing,
    capabilities,
    codex_account,
    connectors,
    designs,
    dsh,
    git,
    health,
    ide,
    installations,
    keys,
    memory,
    models,
    projects,
    supervisor,
    tasks,
    terminal,
    uploads,
    workspace,
)

api_v1_router = APIRouter()
api_v1_router.include_router(health.router)
api_v1_router.include_router(agents.router)
api_v1_router.include_router(billing.router)
api_v1_router.include_router(keys.router)
api_v1_router.include_router(installations.router)
api_v1_router.include_router(tasks.router)
# Bot / 群聊已独立成 Flux Bot 项目，对话产品不再暴露这些接口。
api_v1_router.include_router(workspace.router)
api_v1_router.include_router(terminal.router)
api_v1_router.include_router(ide.router)
api_v1_router.include_router(git.router)
api_v1_router.include_router(projects.router)
api_v1_router.include_router(memory.router)
api_v1_router.include_router(models.router)
api_v1_router.include_router(connectors.router)
api_v1_router.include_router(dsh.router)
api_v1_router.include_router(capabilities.router)
api_v1_router.include_router(designs.router)
api_v1_router.include_router(uploads.router)
api_v1_router.include_router(codex_account.router)
api_v1_router.include_router(supervisor.router)

__all__ = ["api_v1_router"]
