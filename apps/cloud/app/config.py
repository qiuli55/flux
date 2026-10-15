"""Flux 云服务配置。

所有敏感值只从环境变量读取，部署时写在 /etc/flux-cloud.env（0600，root 属主），
代码里不出现任何明文密钥。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """运行期配置；实例在模块底部创建，导入即生效。"""

    app_name: str = "Flux Cloud"
    env: str = _env("FLUX_CLOUD_ENV", "prod")

    # 对外地址：OAuth 回调、Cookie 域、前端跳转都基于它
    public_base_url: str = _env("FLUX_CLOUD_PUBLIC_BASE_URL", "http://127.0.0.1:8500")

    # 数据落盘位置：SQLite 库 + 用户上传的日志包
    data_dir: Path = Path(_env("FLUX_CLOUD_DATA_DIR", "/var/lib/flux-cloud"))

    # 签名密钥（access token / state / 一次性口令 cookie 都用它）
    secret_key: str = _env("FLUX_CLOUD_SECRET_KEY", "")

    # 令牌有效期
    access_ttl_min: int = _env_int("FLUX_CLOUD_ACCESS_TTL_MIN", 30)
    refresh_ttl_days: int = _env_int("FLUX_CLOUD_REFRESH_TTL_DAYS", 30)

    # Cookie 策略
    cookie_domain: str = _env("FLUX_CLOUD_COOKIE_DOMAIN", "")
    cookie_secure: bool = _env("FLUX_CLOUD_COOKIE_SECURE", "1") == "1"

    # OAuth
    github_client_id: str = _env("FLUX_CLOUD_GITHUB_CLIENT_ID", "")
    github_client_secret: str = _env("FLUX_CLOUD_GITHUB_CLIENT_SECRET", "")
    google_client_id: str = _env("FLUX_CLOUD_GOOGLE_CLIENT_ID", "")
    google_client_secret: str = _env("FLUX_CLOUD_GOOGLE_CLIENT_SECRET", "")

    # 日志上传上限（单包）
    max_log_mb: int = _env_int("FLUX_CLOUD_MAX_LOG_MB", 64)

    # 允许跨域调用 API 的前端（Flux 工作台）；逗号分隔，* 表示放开
    allow_origins: tuple[str, ...] = field(
        default_factory=lambda: tuple(
            item for item in _env("FLUX_CLOUD_ALLOW_ORIGINS", "*").split(",") if item
        )
    )

    @property
    def database_url(self) -> str:
        explicit = _env("FLUX_CLOUD_DATABASE_URL")
        if explicit:
            return explicit
        return f"sqlite:///{self.data_dir / 'flux-cloud.db'}"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs"

    def oauth_redirect_uri(self, provider: str) -> str:
        return f"{self.public_base_url}/api/auth/oauth/{provider}/callback"

    def provider_enabled(self, provider: str) -> bool:
        if provider == "github":
            return bool(self.github_client_id and self.github_client_secret)
        if provider == "google":
            return bool(self.google_client_id and self.google_client_secret)
        return False


settings = Settings()

# 目录在导入时就准备好：SQLite 需要父目录存在，日志目录也需要
settings.data_dir.mkdir(parents=True, exist_ok=True)
settings.log_dir.mkdir(parents=True, exist_ok=True)
