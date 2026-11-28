"""API v1 路由聚合（主规格 §12）。"""

from fastapi import APIRouter

from flux.api.v1 import (
    agents,
    antigravity_account,
    artifacts,
    billing,
    capabilities,
    claude_account,
    codex_account,
    connectors,
    cursor_account,
    deliveries,
    designs,
    dsh,
    git,
    github,
    health,
    ide,
    im_connectors,
    installations,
    keys,
    mcp_connectors,
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
# 群聊已从产品下线：`core/group` 暂时保留，但不再对外挂载 /api/v1/groups。
api_v1_router.include_router(workspace.router)
api_v1_router.include_router(terminal.router)
api_v1_router.include_router(ide.router)
api_v1_router.include_router(git.router)
api_v1_router.include_router(github.router)
api_v1_router.include_router(projects.router)
api_v1_router.include_router(memory.router)
api_v1_router.include_router(models.router)
api_v1_router.include_router(connectors.router)
api_v1_router.include_router(dsh.router)
api_v1_router.include_router(capabilities.router)
api_v1_router.include_router(designs.router)
api_v1_router.include_router(artifacts.router)
api_v1_router.include_router(deliveries.router)
api_v1_router.include_router(uploads.router)
api_v1_router.include_router(codex_account.router)
api_v1_router.include_router(claude_account.router)
api_v1_router.include_router(cursor_account.router)
api_v1_router.include_router(antigravity_account.router)
api_v1_router.include_router(mcp_connectors.router)
api_v1_router.include_router(im_connectors.router)
api_v1_router.include_router(supervisor.router)

__all__ = ["api_v1_router"]
