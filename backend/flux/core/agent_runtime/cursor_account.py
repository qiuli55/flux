"""Cursor 账号（官方 Cursor CLI 浏览器授权）。

主路径是官方 `agent login`：Flux 设置 `NO_OPEN_BROWSER=1`，把 CLI 打印的官方授权
链接交给手机/浏览器；登录态与凭据由官方 CLI 自己保存，Flux 不保存、不解析任何
Cursor 令牌。`agent status` 查状态，`agent logout` 退出。

API key（`CURSOR_API_KEY`）不是连接器主路径：登录时会被清掉，避免 CLI 跳过浏览器授权。
"""

from __future__ import annotations

from flux.config import Settings
from flux.core.agent_runtime.browser_cli_account import (
    BrowserCliAccountConfig,
    BrowserCliAccountService,
)

RUNTIME_ID = "cursor"
DISPLAY_NAME = "Cursor"

CONFIG = BrowserCliAccountConfig(
    runtime_id=RUNTIME_ID,
    display_name=DISPLAY_NAME,
    binary_env="FLUX_CURSOR_CLI_BINARY",
    default_binary="agent",
    home_setting="cursor_account_home",
    # 官方文档：`agent login`；无界面时 NO_OPEN_BROWSER=1 会打印授权链接。
    login_args=("login",),
    logout_args=("logout",),
    status_args=("status",),
    url_domains=("cursor.com", "cursor.sh"),
    install_hint=(
        "请先在电脑上安装官方 Cursor CLI（命令名 agent），或设置 FLUX_CURSOR_CLI_BINARY 指向它。"
    ),
    api_key_envs=("CURSOR_API_KEY",),
)


class CursorAccountService(BrowserCliAccountService):
    """Cursor 官方 CLI 登录态。"""

    def __init__(self, settings: Settings, **kwargs: object) -> None:
        super().__init__(settings, CONFIG, **kwargs)


__all__ = ["CONFIG", "CursorAccountService", "DISPLAY_NAME", "RUNTIME_ID"]
