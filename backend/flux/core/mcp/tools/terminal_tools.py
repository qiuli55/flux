"""终端执行工具 `terminal.execute`（Agent Terminal Console §4 / §5 / §9）。

这是 Agent 执行命令的**唯一**通道：命令经 Flux 的 Terminal Session 执行，因此
"AI 执行了哪条命令、输出是什么、退出码多少"都落成终端事件，工程师能在终端窗口
实时看到并随时 Stop / Force Stop（§3.3 / §3.4）。

两条硬约束：

- 输出**只回结构化摘要**（取尾部截断），完整输出留在终端事件里，不把长日志回灌
  Agent Context（§9）——`npm install`、编译、测试日志动辄上千行；
- 命令固定在工作区根目录下执行（与 Apply 同源），Agent 不能自选目录。
"""

from __future__ import annotations

from typing import Any

from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.enums import Capability, TerminalEventKind, TerminalSource

#: 回给 Agent 的输出摘要上限。取**尾部**：测试汇总行、报错栈尾、编译结论都在末尾。
MAX_SUMMARY_CHARS = 4000

TERMINAL_EXECUTE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "command": {
            "type": "string",
            "description": "要执行的命令（在工作区根目录下执行；输出进入终端会话供人观察）",
        },
        "session_id": {
            "type": "string",
            "description": "（可空）复用已有终端会话；留空则新建一个并把 session_id 返回给你",
        },
    },
    "required": ["command"],
    "additionalProperties": False,
}


async def terminal_execute(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"command", "session_id"})
    command = require_str(params, "command")
    session_id = optional_str(params, "session_id")
    service = ctx.container.terminal

    if session_id is None:
        session = await service.create_session()
        session_id = str(session.id)
    else:
        session = await service.get_session(session_id)
    # 本次命令产生的事件从这条 seq 之后开始；用它把输出限定在本次命令范围内
    before_seq = session.next_seq - 1

    event = await service.run_command(session_id, command, source=TerminalSource.AI)
    produced = await service.list_events(session_id, after_seq=before_seq)
    output = "".join(
        item.chunk or "" for item in produced if item.kind == TerminalEventKind.OUTPUT.value
    )
    truncated = len(output) > MAX_SUMMARY_CHARS
    return {
        "session_id": session_id,
        "command": command,
        "exit_code": event.exit_code,
        "succeeded": event.exit_code == 0,
        "output_summary": output[-MAX_SUMMARY_CHARS:] if truncated else output,
        "output_truncated": truncated,
        "note": "完整输出只在终端会话事件（terminal.output）里，未回灌 Agent Context",
    }


def terminal_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="terminal.execute",
            title="执行终端命令",
            description=(
                "在工作区根目录下执行一条命令，并把命令与实时输出写进终端会话（工程师可在"
                "终端窗口观察并随时 Stop）。返回 exit_code 与输出摘要；完整输出不回灌上下文。"
            ),
            input_schema=TERMINAL_EXECUTE_SCHEMA,
            capability=Capability.TERMINAL_EXECUTE,
            handler=terminal_execute,
        ),
    )
