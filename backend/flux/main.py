"""FastAPI 应用工厂。

本地启动：uvicorn flux.main:app --reload --app-dir backend
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI

from flux.api.errors import register_exception_handlers
from flux.api.v1 import api_v1_router
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
        yield
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
    app.include_router(api_v1_router, prefix=app_settings.api_v1_prefix)
    # MCP 能力面（目标架构 §3.1）：与 REST 同进程同生命周期，但不挂在 /api/v1 下——
    # 它是给 agent 用的协议端点，不是给前端用的业务接口。
    app.include_router(mcp_router)
    return app


app = create_app()
