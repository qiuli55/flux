"""谷歌 Antigravity（反重力）Adapter（官方 CLI，命令名 `agy`）。

探测规则按官方 CLI 的公开命令接入：
- `agy --version` → 版本号；
- `agy status` → 登录态（官方未固定输出格式，只认明确的登录/未登录词）。

Flux 不实现 Google OAuth、不读取官方凭据文件；`status` 无法识别时，若用户曾在
Flux 连接器里完成过官方登录，则用 Flux 的非机密标记兜底。
"""

from __future__ import annotations

import os
from pathlib import Path

from flux.core.agent_runtime.adapters.generic import GenericCliAdapter


def _marker_exists(env_name: str, default_dir: str) -> bool:
    raw = (os.environ.get(env_name) or default_dir).strip()
    try:
        return (Path(raw).expanduser() / "connected.json").is_file()
    except OSError:
        return False


class AntigravityAdapter(GenericCliAdapter):
    def __init__(self, **overrides: object) -> None:
        config: dict[str, object] = {
            "name": "antigravity",
            "executable": "agy",
            "version_args": ("--version",),
            "auth_args": ("status",),
            "auth_ok_markers": ("logged in", "authenticated", "signed in", "已登录"),
            "auth_missing_markers": ("not logged in", "logged out", "未登录", "not signed in"),
            "run_args": (),
            "capabilities": ("mcp", "stream"),
        }
        config.update(overrides)
        super().__init__(**config)  # type: ignore[arg-type]

    def check_auth(self) -> str:
        status = super().check_auth()
        if status == "unknown" and _marker_exists(
            "FLUX_ANTIGRAVITY_ACCOUNT_HOME", "~/.flux/antigravity-account"
        ):
            return "ok"
        return status


__all__ = ["AntigravityAdapter"]
