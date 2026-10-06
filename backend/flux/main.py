"""FastAPI 应用工厂。

本地启动：uvicorn flux.main:app --reload --app-dir backend
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from flux.api.auth import require_rest_auth
from flux.api.errors import register_exception_handlers
from flux.api.v1 import api_v1_router, terminal
from flux.config import Settings, get_settings
from flux.container import Container
from flux.core.mcp.server import router as mcp_router
from flux.logging import configure_logging
from flux.version import VERSION


def create_app(settings: Settings | None = None) -> FastAPI:
    app_settings = settings or get_settings()
    configure_logging(app_settings.log_level)
    container = Container(app_settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        await container.agents.load_from_db()
        await container.agent_tokens.migrate_legacy_tokens()
        await container.workspace.recover_interrupted_applies()
        if app_settings.dsh_enabled:
            await container.dsh.supervisor.startup_recovery()
            await container.task_runs.reconcile_orphan_tasks()
            container.dsh.supervisor.ensure_background()
        yield
        # Container.dispose() owns service shutdown ordering, including Human PTY.
        await container.dispose()

    app = FastAPI(
        title=app_settings.app_name,
        version=VERSION,
        description="Flux 后端 API（主规格 §12）",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
    )
    app.state.container = container
    register_exception_handlers(app)
    app.include_router(
        api_v1_router,
        prefix=app_settings.api_v1_prefix,
        dependencies=[Depends(require_rest_auth)],
    )
    # WebSocket 不能复用 Request + HTTPBearer 的 REST 依赖；Human PTY 自己执行
    # same-origin + token/cookie 鉴权，并单独挂到应用，避免依赖注入在 WS 上失效。
    app.include_router(
        terminal.websocket_router,
        prefix=app_settings.api_v1_prefix,
    )
    app.include_router(mcp_router)
    return app


app = create_app()
