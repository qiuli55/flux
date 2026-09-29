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
from flux.logging import configure_logging

VERSION = "0.1.0"


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
    return app


app = create_app()
