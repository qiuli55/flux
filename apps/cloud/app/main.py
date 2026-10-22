"""Flux 云服务入口。

本地起法：
    uvicorn app.main:app --host 127.0.0.1 --port 8500
生产由 systemd 拉起（deploy/flux-cloud.service），nginx 反代 443。
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from . import __version__
from .config import settings
from .db import get_db, init_db
from .deps import get_optional_user
from .routers import auth, devices, logs
from .schemas import HealthOut

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("flux_cloud")

WEB_DIR = Path(__file__).parent / "web"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    log.info(
        "Flux Cloud 启动 env=%s base=%s data=%s github=%s google=%s",
        settings.env,
        settings.public_base_url,
        settings.data_dir,
        settings.provider_enabled("github"),
        settings.provider_enabled("google"),
    )
    if not settings.secret_key:
        log.error("FLUX_CLOUD_SECRET_KEY 未配置：令牌签发会失败，请先写入 /etc/flux-cloud.env")
    yield


app = FastAPI(
    title=settings.app_name,
    version=__version__,
    lifespan=lifespan,
    docs_url="/api/docs",
    openapi_url="/api/openapi.json",
)

_origins = list(settings.allow_origins)
if "*" in _origins:
    # 桌面客户端来源不固定（file:// 或 localhost:随机端口），用正则反射 Origin
    app.add_middleware(
        CORSMiddleware,
        allow_origin_regex=".*",
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
else:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

app.include_router(auth.router)
app.include_router(devices.router)
app.include_router(logs.router)


def _providers() -> dict[str, bool]:
    return {
        "github": settings.provider_enabled("github"),
        "google": settings.provider_enabled("google"),
    }


@app.get("/health", response_model=HealthOut)
def health() -> HealthOut:
    return HealthOut(
        app=settings.app_name,
        env=settings.env,
        time=datetime.now(timezone.utc).replace(microsecond=0).isoformat() + "Z",
        providers=_providers(),
    )


@app.get("/api/health", response_model=HealthOut)
def api_health() -> HealthOut:
    return health()


@app.get("/", include_in_schema=False)
def index(request: Request, db: Session = Depends(get_db)) -> RedirectResponse:
    user = get_optional_user(request, db)
    return RedirectResponse("/account" if user else "/login")


@app.get("/login", include_in_schema=False)
def login_page() -> FileResponse:
    return FileResponse(WEB_DIR / "login.html", media_type="text/html")


@app.get("/account", include_in_schema=False)
def account_page() -> FileResponse:
    return FileResponse(WEB_DIR / "account.html", media_type="text/html")


if WEB_DIR.is_dir():
    app.mount("/assets", StaticFiles(directory=WEB_DIR), name="assets")
