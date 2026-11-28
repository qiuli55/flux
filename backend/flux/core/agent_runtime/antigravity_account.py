"""谷歌 Antigravity（反重力）账号（官方 `agy` CLI 浏览器授权）。

个人 Google 账号 / Antigravity 订阅的配额不能授权给第三方自造客户端，因此 Flux
**不实现 Google OAuth**、也不换订阅令牌：只在用户电脑上拉起官方 `agy` 登录，
凭据留在官方 CLI 的安全存储里；Flux 仅调度官方 CLI。

`GEMINI_API_KEY` 不是连接器主路径：登录时会被清掉，避免 CLI 跳过浏览器授权。
官方 CLI 若没有可解析的 `status` 子命令，Flux 以「本机曾成功完成过登录」的非机密
标记兜底，并在状态里标出 `official_status=unknown`，不假装拿到了官方账号信息。
"""

from __future__ import annotations

from flux.config import Settings
from flux.core.agent_runtime.browser_cli_account import (
    BrowserCliAccountConfig,
    BrowserCliAccountService,
)

RUNTIME_ID = "antigravity"
DISPLAY_NAME = "谷歌 / Antigravity"

CONFIG = BrowserCliAccountConfig(
    runtime_id=RUNTIME_ID,
    display_name=DISPLAY_NAME,
    binary_env="FLUX_ANTIGRAVITY_CLI_BINARY",
    default_binary="agy",
    home_setting="antigravity_account_home",
    # 官方 CLI 的登录子命令按 `agy login` 接入；不同版本可用
    # FLUX_ANTIGRAVITY_LOGIN_ARGS 覆盖（例如 "acp --login"）。
    login_args=("login",),
    logout_args=("logout",),
    status_args=("status",),
    url_domains=("accounts.google.com", "antigravity.google", "google.com"),
    install_hint=(
        "请先在电脑上安装官方 Antigravity CLI（命令名 agy），"
        "或设置 FLUX_ANTIGRAVITY_CLI_BINARY 指向它。"
    ),
    api_key_envs=("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GENAI_API_KEY"),
    # Antigravity 的 status 输出格式未在官方文档固定：只认明确的登录/未登录词，
    # 其余按 unknown 处理，由 Flux 的成功标记兜底。
    missing_markers=("not logged in", "logged out", "未登录", "not signed in"),
)


class AntigravityAccountService(BrowserCliAccountService):
    """谷歌 / Antigravity 官方 CLI 登录态。"""

    def __init__(self, settings: Settings, **kwargs: object) -> None:
        super().__init__(settings, CONFIG, **kwargs)


__all__ = ["CONFIG", "AntigravityAccountService", "DISPLAY_NAME", "RUNTIME_ID"]
