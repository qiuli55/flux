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

    # --- 存储（主规格 §11.1）---
    database_url: str = "sqlite+aiosqlite:///./flux.db"
    redis_url: str = "redis://localhost:6379/0"

    # --- 模型供应商（主规格 §5.3）---
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
    default_provider: str = "local"

    # --- Virtual Workspace Apply（主规格 §7.6）---
    # Agent 改动的落盘根目录。留空则 apply 直接报错——绝不默认写到某个"看起来还行"的目录。
    workspace_root: str | None = None
    # Apply 之后要跑的测试命令（如 "pytest -q"）；留空表示不跑测试
    test_command: str | None = None
    test_timeout_seconds: float = 300.0

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

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")


@lru_cache
def get_settings() -> Settings:
    """进程内单例。测试中可直接实例化 Settings(...) 覆盖。"""
    return Settings()
