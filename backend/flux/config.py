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
    # Electron 桌面端用于“打开文件夹”的一次运行期能力令牌；普通 Web 客户端不能使用。
    desktop_control_token: str | None = None

    # --- 存储（主规格 §11.1）---
    database_url: str = "sqlite+aiosqlite:///./flux.db"
    redis_url: str = "redis://localhost:6379/0"

    # --- 模型供应商（主规格 §5.3）---
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
    anthropic_max_tokens: int = 32000
    default_provider: str = "local"

    # --- Virtual Workspace Apply（主规格 §7.6）---
    workspace_root: str | None = None
    test_command: str | None = None
    test_timeout_seconds: float = 300.0
    proposal_ttl_seconds: int = 86400
    proposal_required: bool = True

    # --- Git 集成 ---
    git_timeout_seconds: float = 30.0

    # --- Project Scanner ---
    project_scan_max_files: int = 2000
    project_scan_max_depth: int = 6

    # --- HTTP 调用策略 ---
    model_timeout_seconds: float = 60.0
    model_max_retries: int = 2
    model_max_output_tokens: int | None = None

    # --- DSH Agent Runtime ---
    dsh_enabled: bool = False
    dsh_home: str = "/opt/flux/dsh-home"
    dsh_workspace: str = "/opt/flux/dsh-ws"
    dsh_provider: str = "deepseek-official"
    dsh_model: str = "deepseek-v4-flash"
    dsh_max_tokens: int = 49152
    dsh_init_timeout_seconds: float = 30.0
    dsh_run_timeout_seconds: float = 0.0
    dsh_startup_timeout_seconds: float = 120.0
    dsh_idle_timeout_seconds: float = 600.0
    dsh_hard_timeout_seconds: float = 1800.0
    dsh_heartbeat_interval_seconds: float = 5.0
    dsh_reconcile_interval_seconds: float = 10.0
    dsh_cancel_grace_seconds: float = 5.0
    dsh_mcp_enabled: bool = True
    dsh_mcp_url: str = "http://127.0.0.1:8000/mcp"
    dsh_mcp_server_name: str = "flux"
    dsh_mcp_agent_id: str = "flux-builtin"
    dsh_mcp_tool_timeout_ms: int = 60000

    # --- 外部 CLI Agent ---
    codex_provider: str = "openai"
    codex_model: str = ""
    codex_model_catalog: str = ""

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    """进程内单例。测试中可直接实例化 Settings(...) 覆盖。"""
    return Settings()
