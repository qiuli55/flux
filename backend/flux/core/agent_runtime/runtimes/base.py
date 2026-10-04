"""Agent Runtime 统一契约（P2-1，设计 §6）。

`Task → AgentRuntime → {DSH, Codex, OpenCode}`：平台只认这一份契约，启动、身份注入、
MCP 接入、事件流、取消各运行时各管各的实现细节，编排层（`TaskRunService`）不出现
任何 runtime 专属分支。

统一事件词表与 DSH 侧对齐（`dsh_events.py`）：`status / message / tool_call /
tool_result / final / error`。终态由既有 `RunSupervisor` 裁决（CLI 退出码 0 → completed、
非 0 → failed、cancel/timeout 由看护器升级链路判定），本契约不新增第二套生命周期。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

#: 支持的 runtime 标识（也是 Agent 档案里 `config["runtime"]` 的合法取值）
RUNTIME_DSH = "dsh"
RUNTIME_CODEX = "codex"
RUNTIME_OPENCODE = "opencode"
DEFAULT_RUNTIME = RUNTIME_DSH
KNOWN_RUNTIMES: tuple[str, ...] = (RUNTIME_DSH, RUNTIME_CODEX, RUNTIME_OPENCODE)
#: 需要 Flux 主动拉起本机 CLI 的 runtime（安装状态必须是 READY）
CLI_RUNTIMES: tuple[str, ...] = (RUNTIME_CODEX, RUNTIME_OPENCODE)

#: 统一事件词表
EVENT_STATUS = "status"
EVENT_MESSAGE = "message"
EVENT_TOOL_CALL = "tool_call"
EVENT_TOOL_RESULT = "tool_result"
EVENT_FINAL = "final"
EVENT_ERROR = "error"
RUNTIME_EVENT_TYPES: frozenset[str] = frozenset(
    {
        EVENT_STATUS,
        EVENT_MESSAGE,
        EVENT_TOOL_CALL,
        EVENT_TOOL_RESULT,
        EVENT_FINAL,
        EVENT_ERROR,
    }
)


@dataclass(slots=True)
class RuntimeLaunch:
    """一次 Run 启动所需的全部事实（由 `AgentRuntime.prepare` 组装）。"""

    runtime_id: str
    run_id: str
    instruction: str
    env: dict[str, str] = field(default_factory=dict)
    cwd: str = ""
    session_id: str = ""
    task_id: str | None = None
    agent_id: str | None = None
    #: CLI runtime 专用：run 私有目录（含该 CLI 的隔离配置）
    run_dir: Path | None = None
    #: CLI runtime 专用：完整启动 argv
    argv: tuple[str, ...] = ()
    #: Run 结束即撤销的一次性令牌（明文不落任何日志/证据）
    token_id: str | None = None


@dataclass(slots=True)
class RuntimeProcess:
    """一个已登记的 Run 进程（pid/pgid 由 runtimes 回填给看护器）。"""

    runtime_id: str
    run_id: str
    pid: int | None = None
    pgid: int | None = None
    #: 运行时自己的句柄（DSH 是 DshRun，CLI 是 CliProcess）；编排层不解释它
    handle: Any = None
    #: 对外 Run 快照（字段与 DshRun.to_dict 对齐，供 API 响应）
    snapshot: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class RuntimeEvent:
    """统一事件（词表见模块 docstring）。"""

    type: str
    run_id: str
    data: dict[str, Any] = field(default_factory=dict)


@runtime_checkable
class AgentRuntime(Protocol):
    """所有 Agent Runtime 的实现契约（设计 §6.2）。"""

    runtime_id: str

    async def prepare(
        self,
        *,
        task: Any,
        agent: Any,
        run_id: str,
        instruction: str,
    ) -> RuntimeLaunch:
        """准备一次 Run：指令已由编排层拼好，这里补齐 env / cwd / run 目录 / MCP 注入 / 令牌。"""

    async def start(self, launch: RuntimeLaunch) -> RuntimeProcess:
        """拉起 Run 并返回进程句柄；进程组隔离与看护登记在此完成。"""

    def events(self, proc: RuntimeProcess) -> AsyncIterator[RuntimeEvent]:
        """产出统一事件流（status / message / tool_call / tool_result / final / error）。"""

    async def cancel(self, proc: RuntimeProcess) -> None:
        """取消一次 Run（实际升级链路复用既有 RunSupervisor）。"""


__all__ = [
    "CLI_RUNTIMES",
    "DEFAULT_RUNTIME",
    "EVENT_ERROR",
    "EVENT_FINAL",
    "EVENT_MESSAGE",
    "EVENT_STATUS",
    "EVENT_TOOL_CALL",
    "EVENT_TOOL_RESULT",
    "KNOWN_RUNTIMES",
    "RUNTIME_CODEX",
    "RUNTIME_DSH",
    "RUNTIME_EVENT_TYPES",
    "RUNTIME_OPENCODE",
    "AgentRuntime",
    "RuntimeEvent",
    "RuntimeLaunch",
    "RuntimeProcess",
]
