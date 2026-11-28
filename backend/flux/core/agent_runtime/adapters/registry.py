"""内置 CLI Agent Adapter 注册表（最终方案 §3.3 / §20-20）。

交付 Generic CLI Adapter + 五个具体 Adapter（OpenCode / Codex / Claude Code /
Cursor / Antigravity）。Cursor 与 Antigravity 的探测命令按官方 CLI 的公开命令接入；
没有安装时如实报告 NOT_INSTALLED，不伪装成可用。
"""

from __future__ import annotations

from flux.core.agent_runtime.adapters.antigravity import AntigravityAdapter
from flux.core.agent_runtime.adapters.base import CliAgentAdapter
from flux.core.agent_runtime.adapters.claude_code import ClaudeCodeAdapter
from flux.core.agent_runtime.adapters.codex import CodexAdapter
from flux.core.agent_runtime.adapters.cursor import CursorAdapter
from flux.core.agent_runtime.adapters.opencode import OpenCodeAdapter


def build_default_adapters() -> tuple[CliAgentAdapter, ...]:
    return (
        OpenCodeAdapter(),
        CodexAdapter(),
        ClaudeCodeAdapter(),
        CursorAdapter(),
        AntigravityAdapter(),
    )


__all__ = ["build_default_adapters"]
