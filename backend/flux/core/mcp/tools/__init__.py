"""MCP 工具注册表（目标架构 §3.3 Phase 1：4 个工具）。

这里是工具面的**唯一真源**：`tools/list` 返回什么、调用时要求什么能力，
全部由本模块的 `TOOLS` 决定。不在表里的能力（apply / git.push / secret / shell）
不是"忘了注册"，而是硬禁令（§3.5），因此也没有任何开关能打开它。
"""

from __future__ import annotations

from flux.core.mcp.tools.base import ToolContext, ToolSpec
from flux.core.mcp.tools.read_tools import read_tools
from flux.core.mcp.tools.write_tools import write_tools

ALL_TOOLS: tuple[ToolSpec, ...] = (*read_tools(), *write_tools())

TOOLS: dict[str, ToolSpec] = {tool.name: tool for tool in ALL_TOOLS}

#: 面上一律不暴露的硬禁令（§3.5）。写在这里是为了让测试能断言"确实没有"。
FORBIDDEN_TOOL_NAMES: frozenset[str] = frozenset(
    {
        "workspace.apply",
        "git.push",
        "git.commit",
        "secret.read",
        "terminal.execute",
        "shell.exec",
    }
)

__all__ = ["ALL_TOOLS", "FORBIDDEN_TOOL_NAMES", "TOOLS", "ToolContext", "ToolSpec"]
