"""FastAPI 应用工厂。

本地启动：uvicorn flux.main:app --reload --app-dir backend
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from flux.api.auth import require_rest_auth
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
        # P3-16：Agent 档案的权威在 agents 表，启动时重建内存注册表（canonical UUID 不因重启而变），
        # 并把历史"自由字符串"令牌迁移到 canonical id 上。
        await container.agents.load_from_db()
        await container.agent_tokens.migrate_legacy_tokens()
        # P0-1：对账上一轮进程被杀时中断的 Apply（按磁盘事实恢复，幂等），
        # 与 Run 的 startup_recovery 并列——崩在落盘中途的提案不能永远停在 applying。
        await container.workspace.recover_interrupted_applies()
        if app_settings.dsh_enabled:
            # P2-15 §2.7：对账上一轮遗留的非终态 Run（属主已死的孤儿进程一并清理），
            # 再拉起心跳/对账循环——否则重启后卡死的 Run 永远停在 running。
            await container.dsh.supervisor.startup_recovery()
            # P2-04 / TC-502：Run 对完账后收尾"没有活 Run 支撑的 running 任务"，
            # 崩溃遗留的僵尸任务在重启后立刻退回待执行或落终态，不再永久 running。
            await container.task_runs.reconcile_orphan_tasks()
            container.dsh.supervisor.ensure_background()
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
    # 鉴权挂在整棵 /api/v1 上（见 flux.api.auth）：REST 面能执行命令、落盘、提交 git，
    # 公网暴露必须整体设防，不能靠"记得给每个新路由加依赖"。MCP 面走自己的 Agent 令牌。
    app.include_router(
        api_v1_router,
        prefix=app_settings.api_v1_prefix,
        dependencies=[Depends(require_rest_auth)],
    )
    # MCP 能力面（目标架构 §3.1）：与 REST 同进程同生命周期，但不挂在 /api/v1 下——
    # 它是给 agent 用的协议端点，不是给前端用的业务接口。
    app.include_router(mcp_router)
    return app


app = create_app()
