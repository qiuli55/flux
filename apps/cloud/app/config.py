"""Flux 云服务配置。

所有敏感值只从环境变量读取，部署时写在 /etc/flux-cloud.env（0600，root 属主），
代码里不出现任何明文密钥。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def url_origin(url: str) -> str:
    """取 URL 的 scheme://host[:port]，非 http(s) 或缺少主机名时返回空串。"""
    parts = urlsplit((url or "").strip())
    if parts.scheme not in {"http", "https"} or not parts.netloc:
        return ""
    return f"{parts.scheme}://{parts.netloc}".lower()


# 原生应用回环重定向（RFC 8252 §7.3）：桌面端每次启动都用随机端口起本地服务，
# 端口无法预先登记，所以只校验协议与主机名，端口一律放行。
_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})


def is_loopback_redirect(url: str) -> bool:
    """是否是原生应用的本地回环回调地址（http + 回环主机名，端口不限）。"""
    parts = urlsplit((url or "").strip())
    if parts.scheme != "http" or not parts.hostname:
        return False
    return parts.hostname.lower() in _LOOPBACK_HOSTS


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

    # 允许登录后回跳的外部站点（Flux 本体工作台等）；逗号分隔的 scheme://host[:port]
    trusted_return_origins: tuple[str, ...] = field(
        default_factory=lambda: tuple(
            origin
            for origin in (
                url_origin(item) for item in _env("FLUX_CLOUD_TRUSTED_RETURN_ORIGINS").split(",")
            )
            if origin
        )
    )

    # 跨站回跳用的一次性登录码有效期（秒）
    login_code_ttl_sec: int = _env_int("FLUX_CLOUD_LOGIN_CODE_TTL_SEC", 120)

    # 原生 Android 客户端的 OAuth 回跳地址（自定义 scheme，RFC 8252 §7.1）
    mobile_redirect_uri: str = _env("FLUX_CLOUD_MOBILE_REDIRECT", "flux://auth")

    # 仅第三方登录（GitHub / Google）走的出站代理；留空 = 直连。
    # 不让全局 HTTP_PROXY 生效：云服务其余流量（日志、健康检查）保持直连。
    oauth_proxy: str = _env("FLUX_CLOUD_OAUTH_PROXY", "")

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

    def is_trusted_return_origin(self, url: str) -> bool:
        origin = url_origin(url)
        return bool(origin) and origin in self.trusted_return_origins

    def is_mobile_redirect(self, url: str) -> bool:
        """原生 App 的自定义 scheme 回跳（如 flux://auth）。"""
        return bool(url) and url.rstrip("/") == self.mobile_redirect_uri.rstrip("/")

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
