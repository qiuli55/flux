"""配置系统（主规格 §18.4 配置项 / §18.5 API Key 管理）。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# backend/flux/config.py -> backend/flux -> backend -> 仓库根
REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="FLUX_",
        # 固定指向仓库根的 .env：pydantic-settings 默认按进程 cwd 解析相对路径，
        # 而 uvicorn 从仓库根启动、alembic 从 backend/ 启动，若用相对路径两者会
        # 读到不同的配置文件（甚至读不到），导致 make run 与 make migrate 连不同的库。
        env_file=REPO_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- 应用 ---
    app_name: str = "Flux"
    env: str = "local"
    log_level: str = "INFO"
    api_v1_prefix: str = "/api/v1"
    # REST 面单用户令牌（公网暴露前置，见 flux.api.auth）。留空 = 关闭鉴权，
    # 本地开发与桌面端（同机直连）零配置可用；公网部署必须显式设置。
    auth_token: str | None = None

    # --- 存储（主规格 §11.1）---
    database_url: str = "sqlite+aiosqlite:///./flux.db"
    redis_url: str = "redis://localhost:6379/0"

    # --- 模型供应商（主规格 §5.3）---
    # 仅供 Flux 侧基础设施调用（T2 压缩、扫描摘要等），不是 Agent 的模型通道；
    # 密钥仅由对应 Provider 读取，绝不下发给 Agent（§14.3）
    openai_api_key: str | None = None
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-5.5"
    anthropic_api_key: str | None = None
    anthropic_base_url: str = "https://api.anthropic.com"
    anthropic_model: str = "claude-sonnet-5-5"
    deepseek_api_key: str | None = None
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    local_model_base_url: str | None = None
    local_model_name: str = "local-echo"
    # Anthropic 的 max_tokens 是必填字段（做不到 OpenAI / DeepSeek 那样"不传=不限"），
    # 调用方未指定时下发这个值。给足余量：平台内部的长文任务（T2 压缩、摘要）
    # 兜底值偏小会把输出截断；换成输出上限更小的模型时请同步调小。
    anthropic_max_tokens: int = 32000
    default_provider: str = "local"

    # --- Virtual Workspace Apply（主规格 §7.6）---
    # Agent 改动的落盘根目录。留空则 apply 直接报错——绝不默认写到某个"看起来还行"的目录。
    workspace_root: str | None = None
    # Apply 之后要跑的测试命令（如 "pytest -q"）；留空表示不跑测试
    test_command: str | None = None
    test_timeout_seconds: float = 300.0
    # 提案审核 TTL（秒）。> 0 时，新提案带一个 expires_at，超过即失效（pending → expired）；
    # <= 0 表示不启用超时失效——改动的有效性完全由 original_hash 复验来把关。
    proposal_ttl_seconds: int = 86400
    # 平台规则（最终方案 §13）：Agent 的改动是否必须经 Proposal 审核才能落盘。
    # 这是 Flux 的规则，不是 Agent 自己选的；下发在 flux_context.policy 里，Agent 不得绕过。
    proposal_required: bool = True

    # --- Git 集成（主规格 §17.6；实施计划 ⑨）---
    # git 命令超时；Git 操作同样只在 workspace_root 下执行
    git_timeout_seconds: float = 30.0

    # --- Project Scanner（主规格 §5.8；实施计划 ⑩）---
    # 扫描上限：文件数与目录深度都必须有界，避免在巨型仓库上把时间/内存打满。
    # 触顶时画像照常产出，但 truncated=True，让调用方知道结果被裁剪过。
    project_scan_max_files: int = 2000
    project_scan_max_depth: int = 6

    # HTTP 调用策略（§5.1 错误处理：模型失败 / 超时 → 重试）
    model_timeout_seconds: float = 60.0
    model_max_retries: int = 2
    # 平台内部模型调用的单次输出上限；None 表示不设上限，由模型自身的默认输出上限决定。
    model_max_output_tokens: int | None = None

    # --- DSH Agent Runtime（集成方案 §17 配置表；Phase 1 用官方 Python SDK）---
    # 是否启用 DSH Agent Runtime（默认关闭，未启用时 /dsh 接口返回 503）
    dsh_enabled: bool = False
    # DSH_HOME 绝对路径：profiles / plugins / 会话落盘，必须放仓库外
    dsh_home: str = "/opt/flux/dsh-home"
    # DSH Agent 的工作目录（cwd），Phase 1 用独立空目录，不指向真实项目
    dsh_workspace: str = "/opt/flux/dsh-ws"
    # DSH provider 路由
    dsh_provider: str = "deepseek-official"
    # DSH 模型 id
    dsh_model: str = "deepseek-v4-flash"
    # 单次输出 token 上限
    dsh_max_tokens: int = 49152
    # 初始化握手超时（秒）
    dsh_init_timeout_seconds: float = 30.0
    # 单轮超时（秒）；0 表示不设限
    dsh_run_timeout_seconds: float = 0.0

    # --- Run 生命周期看护（修复方案 §2.3~§2.7）---
    # 三类 timeout，全部可关（0 = 关闭该判据）：
    #   startup：进程起来但始终没有进入 RUNNING（构造/握手卡住）的上限
    #   idle：进程还在、但既无输出也无 MCP 活动、状态也不变的上限
    #   hard：无论有无输出都不放行的绝对上限
    dsh_startup_timeout_seconds: float = 120.0
    dsh_idle_timeout_seconds: float = 600.0
    dsh_hard_timeout_seconds: float = 1800.0
    # 心跳周期：写 last_heartbeat_at 并检查三类超时 / 进程存活
    dsh_heartbeat_interval_seconds: float = 5.0
    # Reconciler 对账周期：数据库状态 ↔ 真实进程状态
    dsh_reconcile_interval_seconds: float = 10.0
    # Cancel 优雅期：SIGTERM 进程组后等这么久，仍在则 SIGKILL
    dsh_cancel_grace_seconds: float = 5.0

    # --- DSH × MCP 注入（目标架构 §3；P0-01 真闭环）---
    # 内置 Agent 只能经 MCP 看项目、提提案；启动 DSH 时自动生成 patch 注入 MCP 客户端插件。
    # 关闭后 DSH 会退化成"没有平台的裸 agent"——仅用于本地排查，默认必须开着。
    dsh_mcp_enabled: bool = True
    # Flux MCP 端点（streamable-http）。默认同机默认端口；隔离实例请显式指向自己的端口
    dsh_mcp_url: str = "http://127.0.0.1:8000/mcp"
    # MCP serverName（注入后工具名形如 mcp__flux__workspace.read）
    dsh_mcp_server_name: str = "flux"
    # 内置 Agent 的令牌身份：Flux 用它在 MCP 面盖章 provenance
    dsh_mcp_agent_id: str = "flux-builtin"
    # 单次工具调用超时（毫秒）
    dsh_mcp_tool_timeout_ms: int = 60000

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    """进程内单例。测试中可直接实例化 Settings(...) 覆盖。"""
    return Settings()
