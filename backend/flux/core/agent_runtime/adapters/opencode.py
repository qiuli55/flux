"""OpenCode Adapter（最终方案 §3.3 / §15 点名的第一个具体 Adapter）。

探测规则来自 OpenCode 1.18.x 的真实 CLI 行为：
- `opencode --version` → 纯版本号（如 `1.18.29`）
- `opencode auth list` → 无凭据时输出 `0 credentials`，有则列出若干条
- `opencode run <message>` → 非交互跑一条指令
"""

from __future__ import annotations

from flux.core.agent_runtime.adapters.generic import GenericCliAdapter


class OpenCodeAdapter(GenericCliAdapter):
    def __init__(self, **overrides: object) -> None:
        config: dict[str, object] = {
            "name": "opencode",
            "executable": "opencode",
            "version_args": ("--version",),
            "auth_args": ("auth", "list"),
            "auth_ok_markers": ("credentials",),
            "auth_missing_markers": ("0 credentials",),
            "run_args": ("run",),
            "capabilities": ("mcp", "stream"),
        }
        config.update(overrides)
        super().__init__(**config)  # type: ignore[arg-type]
