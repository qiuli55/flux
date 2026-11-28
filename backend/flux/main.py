"""FastAPI 应用工厂。

本地启动：uvicorn flux.main:app --reload --app-dir backend
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from flux.api.auth import require_rest_auth, validate_auth_configuration
from flux.api.errors import register_exception_handlers
from flux.api.internal_tap import router as llm_tap_router
from flux.api.v1 import api_v1_router, ide, supervisor_webhook, terminal
from flux.api.v1 import cloud as cloud_api
from flux.cloud.owner import load_owner
from flux.cloud.session import load_session
from flux.cloud.uploader import upload_previous_run
from flux.config import Settings, get_settings
from flux.container import Container
from flux.core.agent_runtime.llm_stream_tap import llm_stream_tap
from flux.core.agent_runtime.reaper import ExternalAgentReaper
from flux.core.mcp.server import router as mcp_router
from flux.logging import configure_logging, get_logger
from flux.version import VERSION

logger = get_logger(__name__)


def _warn_legacy_session_without_owner(settings: Settings) -> None:
    """遗留的 `cloud.json` 会话**不会**被自动升为本机主人，只提示需要重新鉴权绑定。

    历史事实：#41 修复**之前**，未鉴权的公网 `/cloud/exchange` 等就能把任意账号写进
    服务器单例 `~/.flux/cloud.json`。因此从旧版本升级时，这份残留会话不能证明写它的
    就是本机主人；若在启动期直接 `save_owner`，等于把当时写进该文件的账号（可能是
    攻击者）升成持久本机主人。绑定只应发生在本机主人鉴权之后（回环直连或有效
    `FLUX_AUTH_TOKEN`，见 `flux.api.v1.cloud._remember_server_session`）。

    这里只做告警，不写任何文件，也不把残留会话当作合法主人。
    """
    if load_owner(settings.cloud_owner_path) is not None:
        return
    if settings.cloud_allowed_user_ids:
        # 已配置允许名单：合法用户有明确的授权来源，残留会话无需处理。
        return
    session = load_session(settings.cloud_session_path)
    if session is None:
        return
    user_id = (session.user_id or "").strip()
    if not user_id:
        return
    logger.warning(
        "发现遗留账号云会话（%s，user_id=%s），但本机尚未绑定主人：不会自动把该"
        "会话升为本机主人。请在本机回环（或带 FLUX_AUTH_TOKEN）重新登录完成绑定，"
        "或配置 FLUX_CLOUD_ALLOWED_USERS。",
        settings.cloud_session_path,
        user_id,
    )


def create_app(settings: Settings | None = None) -> FastAPI:
    app_settings = settings or get_settings()
    # 启动闸门：server / 非回环监听下空 FLUX_AUTH_TOKEN 直接拒绝启动（P1-1）。
    validate_auth_configuration(app_settings)
    configure_logging(app_settings.log_level, app_settings.log_dir_path)
    # 升级提示：遗留 cloud.json 会话不会被自动升为本机主人；绑定只在本机主人鉴权后进行。
    _warn_legacy_session_without_owner(app_settings)
    container = Container(app_settings)
    log_upload_task: asyncio.Task[None] | None = None
    memory_sync_task: asyncio.Task[None] | None = None
    billing_sync_task: asyncio.Task[None] | None = None
    context_sync_task: asyncio.Task[None] | None = None
    agent_reaper_task: asyncio.Task[None] | None = None

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        nonlocal log_upload_task, memory_sync_task, billing_sync_task
        nonlocal context_sync_task, agent_reaper_task
        # P3-16：Agent 档案的权威在 agents 表，启动时重建内存注册表（canonical UUID 不因重启而变），
        # 并把历史"自由字符串"令牌迁移到 canonical id 上。
        await container.agents.load_from_db()
        await container.agent_tokens.migrate_legacy_tokens()
        # 外部 Agent 超过 30 天未通过 MCP 调用 Flux 即自动注销：启动先跑一次，之后周期对账。
        agent_reaper = ExternalAgentReaper(
            agents=container.agents,
            repository=container.agent_repo,
            tokens=container.agent_tokens,
        )
        agent_reaper_task = asyncio.create_task(agent_reaper.run_forever())
        # P0-1：对账上一轮进程被杀时中断的 Apply（按磁盘事实恢复，幂等），
        # 与 Run 的 startup_recovery 并列——崩在落盘中途的提案不能永远停在 applying。
        await container.workspace.recover_interrupted_applies()
        await container.human_pty.recover_orphaned_sessions()
        # FINDING-2：上一进程被 SIGKILL 后，命令进程组 id 还留在库里——据此核实并回收
        # 遗留的孤儿命令进程（比对 /proc cmdline 才动手，避免误杀复用 id 的无关进程）。
        await container.terminal.recover_orphan_commands()
        if app_settings.dsh_enabled:
            # P2-15 §2.7：对账上一轮遗留的非终态 Run（属主已死的孤儿进程一并清理），
            # 再拉起心跳/对账循环——否则重启后卡死的 Run 永远停在 running。
            recovered_runs = await container.dsh.supervisor.startup_recovery()
            # FINDING-1：对账出来的终态 Run 名下若有派生终端会话，一并收摊（幂等；事件订阅
            # 也会触发一次，这里显式补一遍以覆盖订阅时序边界）。
            for run_id in recovered_runs:
                await container.terminal.stop_run_sessions(run_id)
            # P2-04 / TC-502：Run 对完账后收尾"没有活 Run 支撑的 running 任务"，
            # 崩溃遗留的僵尸任务在重启后立刻退回待执行或落终态，不再永久 running。
            await container.task_runs.reconcile_orphan_tasks()
            container.dsh.supervisor.ensure_background()
        # 自动上传「上一轮运行日志」到账号云：后台跑，不阻塞启动，失败只记日志。
        # log_dir 未配置时整体关闭（测试与本地零配置不触发）。
        if app_settings.log_dir_path is not None:
            log_upload_task = asyncio.create_task(upload_previous_run(app_settings))
        # 账号级用户记忆：启动先拉一次云端快照，之后每 15 分钟对账；未配置设备凭据时
        # 循环内部直接跳过，不产生任何网络请求。
        if app_settings.memory_cloud_sync:
            memory_sync_task = asyncio.create_task(container.memory_cloud.start())
        # 跨设备套餐用量归集：增量上传本机账本 + 拉取「全部设备」合计；未配置设备
        # 凭据时循环内部直接跳过，不产生任何网络请求。
        if app_settings.billing_cloud_sync:
            billing_sync_task = asyncio.create_task(container.billing.start_cloud_sync())
        # 对话上下文上云：隐私输入以外的消息增量备份到账号云；未配置设备凭据时
        # 循环内部直接跳过，不产生任何网络请求。
        if app_settings.context_cloud_sync:
            context_sync_task = asyncio.create_task(container.context_cloud.start())
        # 助理（Supervisor）后台判断：消费 outbox → Gate → Assessment；观察模式只记判断，
        # 不派活。没有事件进入就永远空转等待，不产生任何副作用。
        if app_settings.supervisor_worker_enabled:
            await container.supervisor_worker.start()
        # 知识库后台索引：对话 / docs / 项目记忆增量入库，供助理 assistant.search 检索。
        # 默认每 5 分钟一轮；测试与本地零配置可用 knowledge_enabled / worker 开关关掉。
        if app_settings.knowledge_enabled and app_settings.knowledge_worker_enabled:
            await container.knowledge_worker.start()
        yield
        if log_upload_task is not None and not log_upload_task.done():
            log_upload_task.cancel()
        if memory_sync_task is not None and not memory_sync_task.done():
            memory_sync_task.cancel()
        if billing_sync_task is not None and not billing_sync_task.done():
            billing_sync_task.cancel()
        if context_sync_task is not None and not context_sync_task.done():
            context_sync_task.cancel()
        if agent_reaper_task is not None and not agent_reaper_task.done():
            agent_reaper.stop()
            agent_reaper_task.cancel()
        # 先停助理 worker，再 dispose 容器：避免停库后仍有判断在写 outbox。
        await container.supervisor_worker.stop()
        # 知识库索引同样先停：索引事务不能在引擎 dispose 之后还在写。
        await container.knowledge_worker.stop()
        # Container.dispose() owns service shutdown ordering, including Human PTY.
        await container.dispose()
        # 内部旁路的上游连接池随应用一起关闭
        await llm_stream_tap.aclose()

    # 生产（env=server）关闭 /docs、/openapi.json：它们会暴露完整内部接口面，
    # 而公网 /mobile/ 反代只需要 /api/v1。local/test 保留，方便开发自测。
    server_mode = (app_settings.env or "").strip().lower() == "server"
    if (
        (app_settings.cloud_secret_key or "").strip()
        and not app_settings.cloud_allowed_user_ids
        and load_owner(app_settings.cloud_owner_path) is None
    ):
        # 不阻断启动：本机首次绑定需要在没有主人的状态下完成。但要明确告诉运维，
        # 在配置 FLUX_CLOUD_ALLOWED_USERS 或完成本机绑定之前，云令牌不能授权 /api/v1。
        logger.warning(
            "已配置 FLUX_CLOUD_SECRET_KEY，但既没有 FLUX_CLOUD_ALLOWED_USERS，"
            "也还没有绑定本机主人（%s）：云令牌在完成其一之前不能授权 /api/v1。"
            "公网官方 Web 无法自行完成首次绑定——请先在本机回环（或带 FLUX_AUTH_TOKEN）"
            "登录一次绑定主人，或配置 FLUX_CLOUD_ALLOWED_USERS。",
            app_settings.cloud_owner_path,
        )
    app = FastAPI(
        title=app_settings.app_name,
        version=VERSION,
        description="Flux 后端 API（主规格 §12）",
        lifespan=lifespan,
        docs_url=None if server_mode else "/docs",
        redoc_url=None,
        openapi_url=None if server_mode else "/openapi.json",
    )
    app.state.container = container
    register_exception_handlers(app)
    app.include_router(
        api_v1_router,
        prefix=app_settings.api_v1_prefix,
        dependencies=[Depends(require_rest_auth)],
    )
    # 账号云接入面单独挂载（不经 require_rest_auth）：浏览器在未登录时也要能读到
    # 登录入口、并用一次性 code 换本机会话——这正是"先有鸡还是先有蛋"的那一步。
    app.include_router(cloud_api.router, prefix=app_settings.api_v1_prefix)
    # 助理的平台回调（飞书事件订阅）同样免 REST 鉴权：平台服务器没有本机令牌，
    # 安全性由回调体里的 verification token 把关（未配置时 fail-closed）。
    app.include_router(supervisor_webhook.router, prefix=app_settings.api_v1_prefix)
    # WebSocket 不能复用 Request + HTTPBearer 的 REST 依赖；Human PTY 自己执行
    # same-origin + token/cookie 鉴权，并单独挂到应用，避免依赖注入在 WS 上失效。
    app.include_router(
        terminal.websocket_router,
        prefix=app_settings.api_v1_prefix,
    )
    # IDE 代码智能（LSP 网关）：同样是 WS，鉴权与 terminal 复用同一套
    # same-origin + token/cookie 检查，故与 REST 路由分开挂载。
    app.include_router(
        ide.websocket_router,
        prefix=app_settings.api_v1_prefix,
    )
    # MCP 能力面（目标架构 §3.1）：与 REST 同进程同生命周期，但不挂在 /api/v1 下——
    # 它是给 agent 用的协议端点，不是给前端用的业务接口。
    app.include_router(mcp_router)
    # 内置 Agent 的 token 流式旁路：只给本机 DSH 子进程用，无 REST 鉴权（回环 + 一次性 token）
    app.include_router(llm_tap_router)
    return app


app = create_app()
