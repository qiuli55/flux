"""CliRuntime：Flux 主动拉起外部 CLI Agent（P2-1 §6 + P2-2 §7）。

复用既有 `CliAgentAdapter`（`build_run_argv` / `parse_event`）与平台原语
（`platforms.popen_kwargs` 进程组隔离、`platforms.signal_group_graceful` 优雅中断），
并把进程登记进**现有的** `RunSupervisor`——pid/pgid/心跳/超时/对账/取消升级链路全部复用，
不新建第二套生命周期机制。

每次 Run 有自己的私有目录 `<FLUX_DSH_HOME>/runs/<run_id>/`（0700）：里面是该 CLI 的隔离
配置（0600），注入 Flux MCP 端点与一枚一次性 Bearer 令牌（scopes 仅 file.read / file.write /
terminal.execute——没有 apply / git / secret）。
令牌明文只出现在该配置文件里，不进日志、不进任何文档/证据；Run 结束立即撤销。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import re
import subprocess
import threading
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.codex_account import (
    codex_account_home,
    codex_proxy_env,
    sync_account_auth,
)
from flux.core.agent_runtime.model_catalog import PROVIDER_TITLES, wire_api_for
from flux.core.agent_runtime.platforms import kill_group, secure_file, signal_group_graceful
from flux.core.agent_runtime.protocol import build_runtime_env, compose_instruction
from flux.core.agent_runtime.runtimes.base import (
    EVENT_ERROR,
    EVENT_FINAL,
    EVENT_MESSAGE,
    EVENT_STATUS,
    EVENT_THINKING,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    RUNTIME_CODEX,
    RUNTIME_NATIVE_STREAMING,
    RUNTIME_OPENCODE,
    RuntimeEvent,
    RuntimeLaunch,
    RuntimeProcess,
)
from flux.core.agent_runtime.runtimes.codex_server import (
    CodexAppServerSession,
    app_server_argv,
)
from flux.core.agent_runtime.runtimes.opencode_server import (
    OpenCodeServerClient,
    pick_port,
    serve_argv,
)
from flux.core.agent_runtime.supervisor import RunSupervisor
from flux.core.billing.sources import resolve_default_model
from flux.core.event.bus import EventBus, Events
from flux.core.mcp.auth import AgentTokenService
from flux.core.permissions.modes import normalize_mode, scopes_for_mode
from flux.core.permissions.risk import LEVEL_HIGH, LEVEL_LOW, LEVEL_PROTECTED, classify_command
from flux.core.task_engine.approvals import (
    APPROVAL_KIND_HIGH_RISK,
    APPROVAL_KIND_PROTECTED,
    raise_approval,
    wait_for_approval,
)
from flux.enums import Capability, DshRunStatus, PermissionMode
from flux.errors import ConfigurationError
from flux.logging import get_logger

logger = get_logger(__name__)

#: CLI Agent 的 MCP 令牌能力：只读文件 + 提交提案（proposal.create 需要 file.write）
#: + 终端执行（proposal.preview 在临时副本里跑测试需要 terminal.execute）。
#: 仍没有 apply / git / secret 能力——外部 CLI 无法直接落盘，preview 也不写真实工作区。
CLI_TOKEN_SCOPES: tuple[Capability, ...] = (
    Capability.FILE_READ,
    Capability.FILE_WRITE,
    Capability.TERMINAL_EXECUTE,
)

#: 事件流结束哨兵
_SENTINEL = object()

#: 落终态前等 stderr 读完的上限（秒）：进程已退出，正常情况下立即 EOF
_STDERR_JOIN_SECONDS = 3.0

#: 上游「不会自愈」的错误特征：命中就不必再等，直接判失败。
#: 只认额度/计费/鉴权——网络抖动这类错误运行时自己会重试。
_FATAL_UPSTREAM_HINTS: tuple[str, ...] = (
    "usage limit",
    "insufficient",
    "quota",
    "billing",
    "subscription",
    "unauthorized",
    "invalid api key",
    "authentication",
    "余额",
    "额度",
)

#: opencode 的日志是 logfmt：`ts=... level=ERROR ... small=false agent=build error.error="..."`
_LOG_SMALL_RE = re.compile(r"\bsmall=(true|false)\b")
_LOG_ERROR_RE = re.compile(r'error\.error="([^"]*)"')
_LOG_MODEL_RE = re.compile(r"\bmodelID=(\S+)")


def _upstream_failure(line: str, runtime_id: str) -> str | None:
    """判断一行 stderr 日志是不是「不会自愈的上游错误」，是则给出给用户看的原因。

    opencode 把额度/鉴权错误只写进自己的日志：stdout 一行不吐、进程也不退出，
    不在这里识别，用户只能干等到 idle 超时（实测 1.18.29，600 秒）。
    只认主模型（small=false）：标题生成等小模型报错不影响本轮答复，误杀会打断正常 Run。
    """
    if runtime_id != RUNTIME_OPENCODE or "level=ERROR" not in line:
        return None
    small = _LOG_SMALL_RE.search(line)
    if small is not None and small.group(1) == "true":
        return None
    detail = _LOG_ERROR_RE.search(line)
    if detail is None:
        return None
    message = detail.group(1).strip()
    lowered = message.lower()
    if not any(hint in lowered for hint in _FATAL_UPSTREAM_HINTS):
        return None
    model = _LOG_MODEL_RE.search(line)
    label = f"（模型 {model.group(1)}）" if model else ""
    return (
        f"模型额度或鉴权异常，本轮已立即终止{label}：{message}。"
        "请切换其他模型或补充额度后重新发送。"
    )


#: CLI 原始事件类型 → 统一词表
_EVENT_TYPE_MAP: dict[str, str] = {
    "status": EVENT_STATUS,
    "message": EVENT_MESSAGE,
    "text": EVENT_MESSAGE,
    "assistant": EVENT_MESSAGE,
    "assistant/message": EVENT_MESSAGE,
    "tool": EVENT_TOOL_CALL,
    "tool_call": EVENT_TOOL_CALL,
    "tool-call": EVENT_TOOL_CALL,
    "tool_use": EVENT_TOOL_CALL,
    "tool_start": EVENT_TOOL_CALL,
    "tool_result": EVENT_TOOL_RESULT,
    "tool-result": EVENT_TOOL_RESULT,
    "tool_output": EVENT_TOOL_RESULT,
    "tool_end": EVENT_TOOL_RESULT,
    "final": EVENT_FINAL,
    "result": EVENT_FINAL,
    "done": EVENT_FINAL,
    "error": EVENT_ERROR,
}


def _int_of(value: Any) -> int:
    """用量字段一律转非负整数：运行时版本差异/缺字段不能把汇总写成 None。"""
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _float_of(value: Any) -> float:
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        return 0.0


def _normalized_usage(raw: dict[str, Any]) -> dict[str, Any]:
    """把各运行时的用量字段名收敛成同一套键（codex 用 input_tokens，opencode 用 input）。"""
    cache = raw.get("cache") if isinstance(raw.get("cache"), dict) else {}
    return {
        "input": raw.get("input", raw.get("input_tokens")),
        "output": raw.get("output", raw.get("output_tokens")),
        "cache_read": cache.get(
            "read", raw.get("cached_input_tokens", raw.get("cache_read_input_tokens"))
        ),
        "cache_write": cache.get("write", raw.get("cache_write_input_tokens")),
    }


@dataclass
class _RunUsage:
    """一次 Run 的原生用量累计（Flux 自己记，不依赖运行时自己的会话库）。"""

    steps: int = 0
    tool_calls: int = 0
    tool_errors: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    cost: float = 0.0

    def add_step(self, usage: dict[str, Any], cost: Any = None) -> None:
        self.steps += 1
        self.input_tokens += _int_of(usage.get("input"))
        self.output_tokens += _int_of(usage.get("output"))
        self.cache_read_tokens += _int_of(usage.get("cache_read"))
        self.cache_write_tokens += _int_of(usage.get("cache_write"))
        self.cost += _float_of(cost)

    def add_tool(self, state: str) -> None:
        self.tool_calls += 1
        if state.strip().lower() in {"error", "failed"}:
            self.tool_errors += 1

    def to_dict(self, *, runtime_id: str, model: str) -> dict[str, Any]:
        return {
            "event_type": "run_metrics",
            "runtime": runtime_id,
            "model": model,
            "steps": self.steps,
            "tool_calls": self.tool_calls,
            "tool_errors": self.tool_errors,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "cache_read_tokens": self.cache_read_tokens,
            "cache_write_tokens": self.cache_write_tokens,
            "total_tokens": self.input_tokens + self.output_tokens,
            "cost": round(self.cost, 6),
        }


def _accumulate_usage(
    usage: _RunUsage, event: RuntimeEvent, parsed: dict[str, Any]
) -> dict[str, Any] | None:
    """把一条已归一化事件折算进 Run 用量，返回本步增量（供计费落账）。

    OpenCode 每步结束发 step-finish（带 tokens/cost）；Codex 只在 turn.completed 带 usage。
    两者都算一步，工具状态为 error/failed 时另计一次失败。
    返回 None 表示这条事件没有用量（不是模型调用）。
    """
    if event.type == EVENT_TOOL_CALL:
        usage.add_tool(str(event.data.get("status") or event.data.get("state") or ""))
    raw_type = str(parsed.get("type") or "").strip().lower()
    if event.data.get("step_finished"):
        payload = event.data.get("usage")
        step = payload if isinstance(payload, dict) else {}
        usage.add_step(step, event.data.get("cost"))
        return {
            "input": _int_of(step.get("input")),
            "output": _int_of(step.get("output")),
            "cache_read": _int_of(step.get("cache_read")),
            "cache_write": _int_of(step.get("cache_write")),
            "cost": _float_of(event.data.get("cost")),
        }
    if raw_type in ("turn.completed", "thread.completed"):
        payload = parsed.get("usage")
        normalized = _normalized_usage(payload) if isinstance(payload, dict) else {}
        usage.add_step(normalized)
        return {
            "input": _int_of(normalized.get("input")),
            "output": _int_of(normalized.get("output")),
            "cache_read": _int_of(normalized.get("cache_read")),
            "cache_write": _int_of(normalized.get("cache_write")),
            "cost": 0.0,
        }
    return None


@dataclass(frozen=True)
class _CliSpec:
    runtime_id: str
    adapter_name: str
    binary_env: str
    extra_args: tuple[str, ...]


#: 两个外部 CLI 的启动差异（argv 按本机实测版本校准：codex-cli 0.157.1 / opencode 1.18.29
#: [2026-10-05 快照]）
_SPECS: dict[str, _CliSpec] = {
    RUNTIME_CODEX: _CliSpec(
        runtime_id=RUNTIME_CODEX,
        adapter_name="codex",
        binary_env="FLUX_CODEX_CLI_BINARY",
        # 非交互执行：跳过 git 仓库检查、禁彩、输出 JSONL 事件
        extra_args=("--skip-git-repo-check", "--color", "never", "--json"),
    ),
    RUNTIME_OPENCODE: _CliSpec(
        runtime_id=RUNTIME_OPENCODE,
        adapter_name="opencode",
        binary_env="FLUX_OPENCODE_CLI_BINARY",
        # 结构化 JSON 事件流（默认格式化输出无法可靠识别 tool_call）。
        # `--print-logs --log-level ERROR` 把内部错误日志放到 stderr：上游额度/鉴权错误
        # 只写在 opencode 自己的日志里（实测 1.18.29），不读 stderr 就只能干等超时。
        extra_args=("--format", "json", "--print-logs", "--log-level", "ERROR"),
    ),
}


class CliRuntime:
    """外部 CLI Agent 的运行时（Codex / OpenCode 共用同一实现）。"""

    def __init__(
        self,
        *,
        runtime_id: str,
        adapter: Any,
        settings: Settings,
        supervisor: RunSupervisor,
        bus: EventBus | None = None,
        token_service: AgentTokenService | None = None,
        container: Any = None,
    ) -> None:
        if runtime_id not in _SPECS:
            raise ConfigurationError(
                f"未知的 CLI runtime：{runtime_id}",
                details={"runtime": runtime_id, "known": list(_SPECS)},
            )
        self.runtime_id = runtime_id
        #: 契约：所有 CLI runtime 都必须原生逐 token（见 base.RUNTIME_NATIVE_STREAMING）
        self.native_streaming = RUNTIME_NATIVE_STREAMING.get(runtime_id, False)
        self._spec = _SPECS[runtime_id]
        self._adapter = adapter
        self._settings = settings
        self._supervisor = supervisor
        self._bus = bus
        self._token_service = token_service
        #: 容器：原生命令审批要用 task_repo / task_stream / bus 走同一套审批卡
        self._container = container
        self._queues: dict[str, asyncio.Queue[Any]] = {}
        self._tokens: dict[str, str] = {}
        self._tasks: dict[str, asyncio.Task[None]] = {}
        #: run_id → stderr 文件句柄（进程退出、Run 落终态后关闭）
        self._stderr_files: dict[str, Any] = {}
        #: run_id → stderr 读取线程（落盘 + 上游错误识别）
        self._stderr_threads: dict[str, threading.Thread] = {}
        #: run_id → 已识别的上游致命错误（落终态时作为失败原因，替代「退出码 -9」）
        self._fail_reasons: dict[str, str] = {}
        #: run_id → 原生用量累计（终态时写进 run 记录，供预算报表读取）
        self._usage: dict[str, _RunUsage] = {}
        #: run_id → opencode serve 监听端口（server 模式下不再走 run 子命令）
        self._server_ports: dict[str, int] = {}

    # --- AgentRuntime ---

    async def prepare(
        self,
        *,
        task: Any,
        agent: Any,
        run_id: str,
        instruction: str,
        channel: Any = None,
    ) -> RuntimeLaunch:
        agent_id = getattr(agent, "id_str", None)
        if not agent_id:
            raise ConfigurationError(
                f"{self.runtime_id} runtime 需要一个 Agent 档案作为令牌身份",
                details={"runtime": self.runtime_id},
            )
        # 本轮权限模式快照：切换模式只影响下一轮，正在跑的这一轮按启动时的模式跑完
        mode = normalize_mode(getattr(task, "permission_mode", None))
        # 任务级模型覆盖（移动端切换模型）；留空 = 用 runtime 配置里的默认模型
        model_override = str(getattr(task, "model", "") or "").strip()
        # 计费归因要的是「本轮真实模型」：没覆盖时取 runtime 的配置默认值
        # （opencode 是 opencode.json 顶层 model，codex 是 FLUX_CODEX_MODEL），
        # 它决定消耗哪个套餐的额度。
        effective_model = model_override or resolve_default_model(self._settings, self.runtime_id)
        workspace = self._settings.workspace_root or self._settings.dsh_workspace
        run_dir = Path(self._settings.dsh_home).expanduser() / "runs" / run_id
        # codex 的 thread 存在 CODEX_HOME 里：按任务固定目录才能跨轮 thread/resume；
        # 登录态与 config 每轮仍由 _write_runtime_config 重写，隔离性不降级。
        config_dir = run_dir
        task_key = str(getattr(task, "id", "") or "")
        if self.runtime_id == RUNTIME_CODEX and task_key:
            config_dir = Path(self._settings.dsh_home).expanduser() / "runs" / f"task-{task_key}"
        token_id: str | None = None
        try:
            run_dir.mkdir(parents=True, exist_ok=True)
            with contextlib.suppress(OSError):
                os.chmod(run_dir, 0o700)
            if config_dir != run_dir:
                config_dir.mkdir(parents=True, exist_ok=True)
                with contextlib.suppress(OSError):
                    os.chmod(config_dir, 0o700)
            token_id, token_raw = await self._issue_token(agent_id, run_id, mode)
            runtime_env = self._write_runtime_config(
                config_dir, token_raw, run_id, model=model_override, channel=channel, mode=mode
            )
        except Exception:
            await self._revoke(token_id)
            raise

        env = {**os.environ}
        env.update(
            build_runtime_env(
                run_id=run_id,
                workspace=workspace,
                task_id=str(getattr(task, "id", "")) or None,
                agent_id=agent_id,
                mcp_endpoint=(
                    self._settings.dsh_mcp_url if self._settings.dsh_mcp_enabled else None
                ),
                permission_mode=mode,
            )
        )
        env.update(runtime_env)
        # 子进程 cwd 变了但 PWD 仍是 Flux 服务进程的旧值；OpenCode 用它判定项目目录
        # （会话会落到错误 workspace，原生文件工具也会看错仓库），必须同步覆盖。
        env["PWD"] = str(workspace)
        if self.runtime_id == RUNTIME_OPENCODE:
            # server 模式：起一个按 Run 隔离的 opencode serve，再经 HTTP/SSE 拿 token 增量
            port = pick_port()
            self._server_ports[run_id] = port
            argv = serve_argv(self._binary_path(), port)
        elif self.runtime_id == RUNTIME_CODEX:
            # app-server 模式：JSON-RPC over stdio，订阅 item/*/delta 拿 token 增量
            argv = app_server_argv(self._binary_path())
        else:
            argv = self._build_argv(instruction, mode)
        return RuntimeLaunch(
            runtime_id=self.runtime_id,
            run_id=run_id,
            instruction=instruction,
            env=env,
            cwd=workspace,
            session_id=f"flux-task-{getattr(task, 'id', run_id)}",
            task_id=str(getattr(task, "id", "")) or None,
            agent_id=agent_id,
            model=effective_model,
            run_dir=run_dir,
            argv=argv,
            token_id=token_id,
            channel=channel,
            permission_mode=mode,
        )

    async def start(self, launch: RuntimeLaunch) -> RuntimeProcess:
        loop = asyncio.get_running_loop()
        stderr_target, stderr_log = self._open_stderr(launch)
        if stderr_log is not None:
            self._stderr_files[launch.run_id] = stderr_log
        try:
            await self._supervisor.create_run(
                run_id=launch.run_id,
                session_id=launch.session_id,
                instruction=launch.instruction,
                agent_id=launch.agent_id,
                task_id=launch.task_id,
                permission_mode=launch.permission_mode,
            )
            await self._supervisor.mark_starting(launch.run_id)
            process = await asyncio.to_thread(
                self._adapter.start,
                launch.argv,
                env=launch.env,
                cwd=launch.cwd,
                stderr=stderr_target,
                # codex app-server 要一条 stdin 管道收发 JSON-RPC
                stdin=subprocess.PIPE if self.runtime_id == RUNTIME_CODEX else None,
            )
        except Exception:
            self._close_stderr(launch.run_id)
            await self._revoke(launch.token_id)
            raise

        # 管道必须马上有人读：不读会让子进程写日志时堵死（stderr 缓冲区满）
        self._start_stderr_watcher(launch, process, loop)
        if self.runtime_id == RUNTIME_OPENCODE:
            # server 模式的 stdout 不是事件流（事件走 SSE），但仍要排空，避免管道写满
            self._start_stdout_drain(launch, process)

        if self._supervisor.is_settled(launch.run_id):
            # 取消/超时抢先落定：不要再跑，直接清理
            await asyncio.to_thread(self._adapter.stop, process)
            await self._revoke(launch.token_id)
            self._close_stderr(launch.run_id)
            return self._process(launch, process)

        await self._supervisor.mark_running(launch.run_id, pid=process.pid, pgid=process.pgid)
        self._supervisor.register_interrupt(
            launch.run_id, lambda: signal_group_graceful(process.pgid)
        )
        queue: asyncio.Queue[Any] = asyncio.Queue()
        self._queues[launch.run_id] = queue
        self._usage[launch.run_id] = _RunUsage()
        if launch.token_id:
            self._tokens[launch.run_id] = launch.token_id
        self._tasks[launch.run_id] = asyncio.create_task(
            self._supervisor_for(process, launch, queue),
            name=f"cli-run-{launch.run_id}",
        )
        return self._process(launch, process)

    def _supervisor_for(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> Any:
        """按 runtime 选执行方式：两种 server 模式各自有原生增量通道。"""
        if self.runtime_id == RUNTIME_OPENCODE:
            return self._supervise_server(process, launch, queue)
        if self.runtime_id == RUNTIME_CODEX:
            return self._supervise_codex_server(process, launch, queue)
        return self._supervise(process, launch, queue)

    async def events(self, proc: RuntimeProcess) -> AsyncIterator[RuntimeEvent]:
        queue = self._queues.get(proc.run_id)
        if queue is None:
            return
        while True:
            item = await queue.get()
            if item is _SENTINEL:
                return
            yield item

    async def cancel(self, proc: RuntimeProcess) -> None:
        if not self._supervisor.is_settled(proc.run_id):
            await self._supervisor.cancel(proc.run_id)
        await self._revoke(self._tokens.pop(proc.run_id, None))

    # --- 启动差异 ---

    def _build_argv(self, instruction: str, mode: str = "normal") -> tuple[str, ...]:
        builder = getattr(self._adapter, "build_run_argv", None)
        if builder is not None:
            base = tuple(builder(instruction, mode=mode))
        else:  # pragma: no cover - 内置 Adapter 都有 build_run_argv
            base = (getattr(self._adapter, "_path", None) or self._spec.adapter_name, instruction)
        override = os.environ.get(self._spec.binary_env)
        if override:
            base = (override, *base[1:])
        executable, *rest = base
        prompt = rest[-1] if rest else instruction
        run_args = rest[:-1]
        return (executable, *run_args, *self._spec.extra_args, prompt)

    def _binary_path(self) -> str:
        """CLI 可执行文件：环境变量覆盖优先，否则用 Adapter 探测到的绝对路径。"""
        override = os.environ.get(self._spec.binary_env)
        if override:
            return override
        return getattr(self._adapter, "_path", None) or self._spec.adapter_name

    async def _issue_token(
        self, agent_id: str, run_id: str, mode: str = "normal"
    ) -> tuple[str | None, str | None]:
        if not self._settings.dsh_mcp_enabled:
            logger.warning(
                "%s：MCP 注入已关闭，本次 Run 的 Agent 将看不到项目、也提不了提案",
                self.runtime_id,
            )
            return None, None
        if self._token_service is None:
            raise ConfigurationError(
                "已启用 MCP 注入，但未装配 AgentTokenService",
                details={"hint": "由容器把 container.agent_tokens 传给 CliRuntime"},
            )
        token, raw = await self._token_service.issue(
            agent_id=agent_id,
            # Plan 只签 file.read + plan.write：令牌层先兜一道，即使 MCP 白名单被绕过也
            # 没有 terminal.execute / file.write 能力位。
            scopes=scopes_for_mode(mode),
            label=f"{self.runtime_id} run {run_id}（Flux 一次性令牌 / {mode}）",
        )
        return str(token.id), raw

    def _write_runtime_config(
        self,
        run_dir: Path,
        token_raw: str | None,
        run_id: str,
        *,
        model: str = "",
        channel: Any = None,
        mode: str = "normal",
    ) -> dict[str, str]:
        url = self._settings.dsh_mcp_url.strip()
        if token_raw is not None and not url.startswith(("http://", "https://")):
            raise ConfigurationError(f"MCP 端点必须是 http(s) URL：{url!r}", details={"url": url})
        if self.runtime_id == RUNTIME_CODEX:
            home = run_dir / "codex-home"
            home.mkdir(parents=True, exist_ok=True)
            config = home / "config.toml"
            config.write_text(
                _codex_config(
                    url,
                    token_raw,
                    settings=self._settings,
                    run_id=run_id,
                    model=model,
                    channel=channel,
                ),
                encoding="utf-8",
            )
            self._secure(config)
            env = {"CODEX_HOME": str(home)}
            if channel is None:
                # 隔离 CODEX_HOME 默认没有 auth.json；把 Flux 账号目录的登录态复制一份进去，
                # ChatGPT 订阅额度才能生效。密钥库通道（channel）优先，不注入账号。
                self._inject_account_auth(home)
            else:
                # 密钥只进子进程环境：config.toml 里只写 env_key 变量名
                env["FLUX_MODEL_API_KEY"] = channel.api_key
            env.update(codex_proxy_env(self._settings))
            return env
        config = run_dir / "opencode.json"
        config.write_text(
            _opencode_config(
                url,
                token_raw,
                run_id,
                model=model,
                uploads_dir=str(self._settings.uploads_dir),
                mode=mode,
            ),
            encoding="utf-8",
        )
        self._secure(config)
        env = {"OPENCODE_CONFIG": str(config)}
        if channel is not None:
            # 含密钥的完整配置只经环境变量交给本次 Run，不落盘；文件里的 MCP 配置仍保留
            env["OPENCODE_CONFIG_CONTENT"] = _opencode_config(
                url,
                token_raw,
                run_id,
                model=model,
                channel=channel,
                uploads_dir=str(self._settings.uploads_dir),
                mode=mode,
            )
        return env

    def _inject_account_auth(self, home: Path) -> None:
        """把 Flux 账号目录的 ChatGPT 登录态复制进本次 Run 的隔离 CODEX_HOME。

        默认供应商（openai / 空）：有账号就注入，订阅额度优先；没有账号则维持原行为
        （OPENAI_API_KEY 或 codex 自带默认），不阻断既有通道。
        显式 `chatgpt` 供应商：没有账号直接报错——否则要等 Run 跑起来才看到「未登录」。
        """
        provider = (self._settings.codex_provider or "").strip().lower()
        account_home = codex_account_home(self._settings)
        if sync_account_auth(account_home, home):
            logger.info("%s：已注入 ChatGPT 账号登录态 account=%s", self.runtime_id, account_home)
            return
        if provider == "chatgpt":
            raise ConfigurationError(
                "还没有登录 ChatGPT 账号，无法使用 Codex 订阅额度",
                details={
                    "hint": "先在「设置 → ChatGPT 账号」完成设备码登录",
                    "account_home": str(account_home),
                },
            )

    def _secure(self, path: Path) -> None:
        # 平台原语：POSIX chmod 0600 / Windows icacls 收权；失败如实记 warning，不静默降级
        if not secure_file(path):
            logger.warning(
                "%s 配置权限弱化（未能收紧到仅属主可访问）path=%s", self.runtime_id, path
            )

    # --- 执行与事件 ---

    async def _supervise(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> None:
        loop = asyncio.get_running_loop()
        try:
            await asyncio.to_thread(self._pump, process, launch, loop, queue)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - 读流线程异常也要收尾，不能留 Run 挂起
            logger.exception("cli.run 事件读取失败 run=%s", launch.run_id)
            await self._finalize(launch, DshRunStatus.FAILED, None, "", queue)

    async def _supervise_server(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> None:
        """opencode server 模式：HTTP 发一轮、SSE 收 token 增量，再按结果落终态。"""
        try:
            await self._run_opencode_server(process, launch, queue)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - 服务端起不来/接口异常都按失败收尾
            logger.exception("opencode server run 失败 run=%s", launch.run_id)
            message = str(exc).strip() or "opencode 服务端执行失败"
            self._fail_reasons.setdefault(launch.run_id, message)
            await self._stop_process(process)
            await self._finalize(launch, DshRunStatus.FAILED, None, "", queue)

    async def _run_opencode_server(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> None:
        run_id = launch.run_id
        port = self._server_ports.pop(run_id, 0)
        usage = self._usage.setdefault(run_id, _RunUsage())

        async def emit(event: RuntimeEvent, parsed: dict[str, Any]) -> None:
            record = {
                **event.data,
                "run_id": run_id,
                "runtime_id": self.runtime_id,
                "event_type": event.type,
            }
            delta = _accumulate_usage(usage, event, parsed)
            queue.put_nowait(event)
            await self._on_event(event, record)
            if delta is not None:
                await self._record_usage(launch, delta)

        async def on_text(text: str) -> None:
            await emit(RuntimeEvent(EVENT_MESSAGE, run_id, {"text": text}), {"type": "text"})

        async def on_thinking(text: str, phase: str) -> None:
            data: dict[str, Any] = {"text": text}
            if phase:
                data["phase"] = phase
            await emit(RuntimeEvent(EVENT_THINKING, run_id, data), {"type": "reasoning"})

        async def on_tool(part: dict[str, Any]) -> None:
            parsed = {"type": "tool_use", "part": part}
            await emit(_normalize_opencode_event(parsed, run_id, self.runtime_id), parsed)

        async def on_step(part: dict[str, Any]) -> None:
            parsed = {"type": "step_finish", "part": part}
            await emit(_normalize_opencode_event(parsed, run_id, self.runtime_id), parsed)

        async def on_error(message: str) -> None:
            await emit(
                RuntimeEvent(EVENT_ERROR, run_id, {"message": message, "phase": "upstream"}),
                {"type": "error"},
            )

        async def on_native_approval(command: str) -> bool:
            return await self._approve_native_command(
                run_id=run_id, command=command, mode=launch.permission_mode
            )

        client = OpenCodeServerClient(
            f"http://127.0.0.1:{port}",
            on_text=on_text,
            on_thinking=on_thinking,
            on_tool=on_tool,
            on_step=on_step,
            on_error=on_error,
            process_alive=lambda: process.popen.poll() is None,
            approval_handler=on_native_approval,
        )
        # 跨轮复用：记住上一轮的会话 id，本轮优先续聊（会话失效时客户端会自动新建）。
        # 按 Agent 隔离：换了 Agent 就不复用上一个 Agent 的会话。
        previous = await self._stored_session(launch.task_id, launch.agent_id)
        result = await client.run(
            compose_instruction(launch.instruction, launch.permission_mode),
            title=f"flux-task-{launch.task_id or run_id}",
            session_id=previous,
            # 任务级模型必须每轮显式下发：会话会记住创建时的模型，
            # 续聊不传就沿用旧模型（切到别的模型后表现为「没切成功」）。
            model=launch.model,
        )
        logger.info("opencode 本轮模型 run=%s model=%s", run_id, launch.model or "默认")
        if result.session_id:
            await self._remember_session(launch.task_id, result.session_id, launch.agent_id)
            logger.info(
                "opencode 会话绑定 run=%s task=%s %s session=%s",
                run_id,
                launch.task_id or "未关联",
                "复用" if previous and previous == result.session_id else "新建",
                result.session_id,
            )
        await self._stop_process(process)
        if result.error:
            logger.warning("opencode server 本轮出错 run=%s %s", run_id, result.error)
            self._fail_reasons.setdefault(run_id, result.error)
            await self._finalize(launch, DshRunStatus.FAILED, None, result.text, queue)
            return
        await self._finalize(launch, DshRunStatus.COMPLETED, 0, result.text, queue)

    async def _supervise_codex_server(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> None:
        """codex app-server 模式：JSON-RPC 一轮，订阅 delta 拿 token 增量。"""
        try:
            await self._run_codex_server(process, launch, queue)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - 握手失败/进程退出都按失败收尾
            logger.exception("codex app-server run 失败 run=%s", launch.run_id)
            message = str(exc).strip() or "codex app-server 执行失败"
            self._fail_reasons.setdefault(launch.run_id, message)
            await self._stop_process(process)
            await self._finalize(launch, DshRunStatus.FAILED, None, "", queue)

    async def _run_codex_server(
        self, process: Any, launch: RuntimeLaunch, queue: asyncio.Queue[Any]
    ) -> None:
        run_id = launch.run_id
        usage = self._usage.setdefault(run_id, _RunUsage())
        texts: list[str] = []

        async def emit(event: RuntimeEvent, parsed: dict[str, Any]) -> None:
            record = {
                **event.data,
                "run_id": run_id,
                "runtime_id": self.runtime_id,
                "event_type": event.type,
            }
            delta = _accumulate_usage(usage, event, parsed)
            queue.put_nowait(event)
            await self._on_event(event, record)
            if delta is not None:
                await self._record_usage(launch, delta)

        async def on_text(text: str) -> None:
            texts.append(text)
            await emit(RuntimeEvent(EVENT_MESSAGE, run_id, {"text": text}), {"type": "text"})

        async def on_thinking(text: str) -> None:
            await emit(RuntimeEvent(EVENT_THINKING, run_id, {"text": text}), {"type": "reasoning"})

        async def on_tool(fields: dict[str, Any], state: str) -> None:
            parsed = {"type": "tool_use", "part": fields}
            event = RuntimeEvent(EVENT_TOOL_CALL, run_id, dict(fields))
            if state == "completed":
                event = RuntimeEvent(EVENT_TOOL_RESULT, run_id, dict(fields))
            await emit(event, parsed)

        async def on_native_approval(command: str) -> bool:
            return await self._approve_native_command(
                run_id=run_id, command=command, mode=launch.permission_mode
            )

        session = CodexAppServerSession(
            process,
            cwd=launch.cwd,
            on_text=on_text,
            on_thinking=on_thinking,
            on_tool=on_tool,
            idle_timeout=self._settings.dsh_idle_timeout_seconds,
            permission_mode=launch.permission_mode,
            approval_handler=on_native_approval,
        )
        previous = await self._stored_session(launch.task_id, launch.agent_id)
        result = await session.run(
            compose_instruction(launch.instruction, launch.permission_mode),
            thread_id=previous,
            # 任务级模型选择必须显式覆盖：codex thread 会记住创建时的模型，
            # 续聊不传就沿用旧模型（切到别的模型后表现为「没切成功」）。
            model=launch.model,
            model_provider=_codex_provider_override(self._settings, launch.channel),
        )
        if result.thread_id:
            await self._remember_session(launch.task_id, result.thread_id, launch.agent_id)
            logger.info(
                "codex thread 绑定 run=%s task=%s %s thread=%s",
                run_id,
                launch.task_id or "未关联",
                "复用" if previous and previous == result.thread_id else "新建",
                result.thread_id,
            )
        await self._stop_process(process)
        if result.error or result.status != "completed":
            reason = result.error or f"codex 本轮以 {result.status} 结束"
            logger.warning("codex app-server 本轮异常 run=%s %s", run_id, reason)
            self._fail_reasons.setdefault(run_id, reason)
            await self._finalize(launch, DshRunStatus.FAILED, None, result.text, queue)
            return
        # 与 exec 模式对齐：turn.completed 在总线上留一条 final（用量随事件累计）
        await emit(
            RuntimeEvent(
                EVENT_FINAL,
                run_id,
                {"status": "completed", "runtime_id": self.runtime_id, "usage": result.usage},
            ),
            {"type": "turn.completed", "usage": result.usage},
        )
        await self._finalize(
            launch, DshRunStatus.COMPLETED, 0, result.text or "".join(texts), queue
        )

    async def _stored_session(self, task_id: str | None, agent_id: str | None) -> str:
        """取该任务在当前 Agent + runtime 上记住的外部会话 id；没有/读失败时返回空串。"""
        if not task_id:
            return ""
        repo = getattr(self._container, "runtime_sessions", None)
        if repo is None:
            return ""
        try:
            return await repo.get(task_id, self.runtime_id, agent_id)
        except Exception:  # noqa: BLE001 - 会话绑定不可用时退回每轮新建
            logger.warning(
                "读取外部会话绑定失败 task=%s agent=%s runtime=%s",
                task_id,
                agent_id,
                self.runtime_id,
                exc_info=True,
            )
            return ""

    async def _remember_session(
        self, task_id: str | None, session_id: str, agent_id: str | None
    ) -> None:
        """记住本轮实际使用的会话 id，供同一 Agent 的下一轮续聊。"""
        if not task_id or not session_id:
            return
        repo = getattr(self._container, "runtime_sessions", None)
        if repo is None:
            return
        try:
            await repo.set(task_id, self.runtime_id, session_id, agent_id)
        except Exception:  # noqa: BLE001 - 写失败只影响下一轮是否复用，不影响本轮
            logger.warning(
                "保存外部会话绑定失败 task=%s agent=%s runtime=%s",
                task_id,
                agent_id,
                self.runtime_id,
                exc_info=True,
            )

    async def _stop_process(self, process: Any) -> None:
        with contextlib.suppress(Exception):
            await asyncio.to_thread(self._adapter.stop, process)

    async def _approve_native_command(self, *, run_id: str, command: str, mode: str) -> bool:
        """CLI 原生命令的审批：与 `terminal.execute` 共用同一套风险分类与审批卡。"""
        return await approve_native_command(
            self._container,
            runtime_id=self.runtime_id,
            run_id=run_id,
            command=command,
            mode=mode,
        )

    def _pump(
        self,
        process: Any,
        launch: RuntimeLaunch,
        loop: asyncio.AbstractEventLoop,
        queue: asyncio.Queue[Any],
    ) -> None:
        """线程内逐行读 stdout → 统一事件；进程退出后按退出码落终态。"""
        texts: list[str] = []
        usage = self._usage.setdefault(launch.run_id, _RunUsage())
        stream = process.popen.stdout
        if stream is not None:
            for line in stream:
                parsed = self._adapter.parse_event(line)
                if parsed is None:
                    continue
                event = self._normalize(parsed, launch.run_id)
                delta = _accumulate_usage(usage, event, parsed)
                if event.type == EVENT_MESSAGE:
                    text = _text_of(event.data)
                    if text:
                        texts.append(text)
                record = {
                    **event.data,
                    "run_id": launch.run_id,
                    "runtime_id": self.runtime_id,
                    "event_type": event.type,
                }
                loop.call_soon_threadsafe(queue.put_nowait, event)
                asyncio.run_coroutine_threadsafe(self._on_event(event, record), loop)
                if delta is not None:
                    asyncio.run_coroutine_threadsafe(self._record_usage(launch, delta), loop)
        code = process.popen.wait()
        status = DshRunStatus.COMPLETED if code == 0 else DshRunStatus.FAILED
        asyncio.run_coroutine_threadsafe(
            self._finalize(launch, status, code, "\n".join(texts), queue), loop
        )

    async def _on_event(self, event: RuntimeEvent, record: dict[str, Any]) -> None:
        if self._bus is not None:
            await self._bus.publish(Events.DSH_EVENT, record)
        if not self._supervisor.is_settled(event.run_id):
            await self._supervisor.note_output(
                event.run_id, {"event_type": event.type, "runtime_id": self.runtime_id}
            )

    async def _record_usage(self, launch: RuntimeLaunch, delta: dict[str, Any]) -> None:
        """把一步用量写进计费账本（任务级归因）；失败只留痕，不影响 Run。"""
        billing = getattr(self._container, "billing", None) if self._container else None
        if billing is None:
            return
        try:
            await billing.record_step(
                run_id=launch.run_id,
                model=launch.model,
                runtime_id=self.runtime_id,
                input_tokens=delta.get("input", 0),
                output_tokens=delta.get("output", 0),
                cache_read_tokens=delta.get("cache_read", 0),
                cache_write_tokens=delta.get("cache_write", 0),
                cost=delta.get("cost"),
                task_id=launch.task_id,
                agent_id=launch.agent_id,
            )
        except Exception:  # noqa: BLE001 - 账本是旁路，绝不能让 Run 因此中断
            logger.warning("cli.run 计费落账异常 run=%s", launch.run_id, exc_info=True)

    def _normalize(self, parsed: dict[str, Any], run_id: str) -> RuntimeEvent:
        """按 runtime 归一化事件：codex 的 `--json` 是 item / turn 两级结构，与统一词表不同源。"""
        if self.runtime_id == RUNTIME_CODEX:
            return _normalize_codex_event(parsed, run_id, self.runtime_id)
        if self.runtime_id == RUNTIME_OPENCODE:
            return _normalize_opencode_event(parsed, run_id, self.runtime_id)
        return _normalize_event(parsed, run_id, self.runtime_id)

    def _open_stderr(self, launch: RuntimeLaunch) -> tuple[Any, Any]:
        """stderr 走管道单独读：既落盘留证据，也要即时识别上游错误。

        直接把文件句柄交给子进程只能事后取证——opencode 的额度/鉴权错误只写自己的日志，
        stdout 一行不吐、进程也不退出，手机端就只会长时间没有输出（实测 1.18.29）。
        返回 (交给子进程的 stderr 目标, 日志句柄)；日志打不开时仍然接管道。
        """
        try:
            handle = (Path(launch.run_dir) / "stderr.log").open("ab")
        except OSError:
            logger.warning(
                "cli.run 打不开 stderr 日志，本次 Run 的 CLI 日志只做错误识别不落盘 run=%s",
                launch.run_id,
            )
            handle = None
        return subprocess.PIPE, handle

    def _start_stderr_watcher(
        self, launch: RuntimeLaunch, process: Any, loop: asyncio.AbstractEventLoop
    ) -> None:
        thread = threading.Thread(
            target=self._drain_stderr,
            args=(launch, process, loop),
            name=f"cli-stderr-{launch.run_id}",
            daemon=True,
        )
        self._stderr_threads[launch.run_id] = thread
        thread.start()

    def _start_stdout_drain(self, launch: RuntimeLaunch, process: Any) -> None:
        """server 模式的 stdout 不是事件流：起线程读空，避免管道写满把服务端堵死。"""
        stream = getattr(process.popen, "stdout", None)
        if stream is None:
            return
        threading.Thread(
            target=self._drain_stdout,
            args=(stream,),
            name=f"cli-stdout-{launch.run_id}",
            daemon=True,
        ).start()

    @staticmethod
    def _drain_stdout(stream: Any) -> None:
        try:
            for _ in stream:
                pass
        except Exception:  # noqa: BLE001 - 进程被杀时读流中断属正常
            pass
        finally:
            with contextlib.suppress(Exception):
                stream.close()

    def _drain_stderr(
        self, launch: RuntimeLaunch, process: Any, loop: asyncio.AbstractEventLoop
    ) -> None:
        """线程内逐行读 stderr：原样落盘 + 识别不会自愈的上游错误。"""
        pipe = getattr(process.popen, "stderr", None)
        log = self._stderr_files.get(launch.run_id)
        if pipe is None:
            return
        try:
            for line in pipe:
                if log is not None:
                    with contextlib.suppress(ValueError, OSError):
                        # 日志句柄是二进制追加模式：不转码会在非 ASCII 行上写失败
                        log.write(line.encode("utf-8", "replace"))
                        log.flush()
                reason = _upstream_failure(line, self.runtime_id)
                if reason is not None and launch.run_id not in self._fail_reasons:
                    self._fail_reasons[launch.run_id] = reason
                    asyncio.run_coroutine_threadsafe(
                        self._fail_fast(launch, reason, getattr(process, "pgid", 0)), loop
                    )
        except Exception:  # noqa: BLE001 - 读 stderr 失败不能影响 Run 主链路
            logger.debug("cli.run stderr 读取中断 run=%s", launch.run_id, exc_info=True)
        finally:
            with contextlib.suppress(Exception):
                pipe.close()

    async def _fail_fast(self, launch: RuntimeLaunch, reason: str, pgid: int) -> None:
        """上游致命错误 → 立刻推错误事件并杀进程，不让用户干等到 idle 超时。"""
        if self._supervisor.is_settled(launch.run_id):
            return
        logger.warning("cli.run 上游错误，立即终止 run=%s %s", launch.run_id, reason)
        await self._on_event(
            RuntimeEvent(EVENT_ERROR, launch.run_id, {"message": reason, "phase": "upstream"}),
            {
                "run_id": launch.run_id,
                "runtime_id": self.runtime_id,
                "event_type": EVENT_ERROR,
                "message": reason,
            },
        )
        if pgid:
            await asyncio.to_thread(kill_group, pgid)

    def _join_stderr(self, run_id: str) -> None:
        """等 stderr 读完：证据文件要完整，失败原因也才能落进终态。"""
        thread = self._stderr_threads.pop(run_id, None)
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=_STDERR_JOIN_SECONDS)

    def _close_stderr(self, run_id: str) -> None:
        self._join_stderr(run_id)
        handle = self._stderr_files.pop(run_id, None)
        if handle is not None:
            with contextlib.suppress(OSError):
                handle.close()

    async def _finalize(
        self,
        launch: RuntimeLaunch,
        status: DshRunStatus,
        exit_code: int | None,
        response: str,
        queue: asyncio.Queue[Any],
    ) -> None:
        # 先等 stderr 收完：失败原因可能就在最后几行日志里
        self._join_stderr(launch.run_id)
        reason = self._fail_reasons.pop(launch.run_id, None)
        error: str | None = None
        finish_reason: str | None = None
        if status is DshRunStatus.FAILED:
            error = reason or f"{self.runtime_id} CLI 进程以退出码 {exit_code} 结束"
            finish_reason = "upstream_error" if reason else str(exit_code)
        metrics = self._usage.pop(launch.run_id, _RunUsage()).to_dict(
            runtime_id=self.runtime_id, model=launch.model
        )
        try:
            final = await self._supervisor.finish(
                launch.run_id,
                status,
                final_response=response,
                finish_reason=finish_reason,
                error=error,
            )
        finally:
            await self._revoke(self._tokens.pop(launch.run_id, launch.token_id))
            self._close_stderr(launch.run_id)
        if final is not status:
            # 看护器改写了终态（取消/超时先落定）：本线程算出的失败原因不串进结果
            error = None
            finish_reason = None
        try:
            await self._supervisor.record_metrics(launch.run_id, metrics)
        except Exception:  # noqa: BLE001 - 指标写失败不能改变 Run 终态，但必须留痕
            logger.exception("cli.run 记录用量失败 run=%s", launch.run_id)
        logger.info(
            "cli.run 用量 run=%s runtime=%s model=%s steps=%d tools=%d/%d "
            "input=%d output=%d cache_read=%d cost=%s",
            launch.run_id,
            self.runtime_id,
            launch.model or "unknown",
            metrics["steps"],
            metrics["tool_calls"],
            metrics["tool_errors"],
            metrics["input_tokens"],
            metrics["output_tokens"],
            metrics["cache_read_tokens"],
            metrics["cost"],
        )
        self._supervisor.release(launch.run_id)
        self._tasks.pop(launch.run_id, None)
        queue.put_nowait(
            RuntimeEvent(
                EVENT_FINAL,
                launch.run_id,
                {
                    "status": str(final),
                    "exit_code": exit_code,
                    "final_response": response,
                    "metrics": metrics,
                },
            )
        )
        queue.put_nowait(_SENTINEL)

    async def _revoke(self, token_id: str | None) -> None:
        if not token_id or self._token_service is None:
            return
        try:
            await self._token_service.revoke(token_id)
        except Exception:  # noqa: BLE001 - 撤销失败不能改变 Run 终态，但必须留痕
            logger.exception("cli.run 撤销令牌失败 token_id=%s", token_id)

    # --- 快照 ---

    def _process(self, launch: RuntimeLaunch, process: Any) -> RuntimeProcess:
        return RuntimeProcess(
            runtime_id=self.runtime_id,
            run_id=launch.run_id,
            pid=process.pid,
            pgid=process.pgid,
            handle=process,
            snapshot=self._snapshot(launch, str(DshRunStatus.RUNNING), process),
        )

    @staticmethod
    def _snapshot(launch: RuntimeLaunch, status: str, process: Any = None) -> dict[str, Any]:
        """构造与 DshRun.to_dict 同形的 Run 快照（对外 API 形态一致）。"""
        pid = getattr(process, "pid", None)
        pgid = getattr(process, "pgid", None)
        return {
            "run_id": launch.run_id,
            "session_id": launch.session_id,
            "instruction": launch.instruction,
            "status": status,
            "final_response": "",
            "finish_reason": None,
            "error": None,
            "events": [],
            "started_at": None,
            "finished_at": None,
            "duration_seconds": None,
            "cancel_requested": False,
            "pid": pid,
            "pgid": pgid,
            "timeout_kind": None,
            "agent_id": launch.agent_id,
            "task_id": launch.task_id,
            "last_state_change_at": None,
            "runtime": launch.runtime_id,
        }


#: 工具参数/输出进时间线的展示上限：太长会把手机端执行轨迹撑爆，也拖慢 SSE
_TOOL_DETAIL_CHARS = 600
_TOOL_OUTPUT_CHARS = 2000


def _clip(value: Any, limit: int) -> str:
    """把任意值压成限长文本；None / 空值一律给空串（前端据此判断字段缺省）。"""
    if value is None:
        return ""
    text = (
        value
        if isinstance(value, str)
        else json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)
    )
    text = text.strip()
    if len(text) > limit:
        return text[: limit - 1] + "…"
    return text


def _tool_fields(part: dict[str, Any], *, name: str = "") -> dict[str, Any]:
    """从工具 part 里抽出时间线要展示的字段（名称 / 命令 / 输出 / 状态 / 调用 id）。

    CLI 原生事件把工具的入参与产出埋在 `state` 里，之前只取了状态，
    手机端就只能看到「Agent 正在执行…」。这里统一摊平，交给 SSE 转发层。
    """
    state = part.get("state") if isinstance(part.get("state"), dict) else {}
    payload = state.get("input") if isinstance(state.get("input"), dict) else {}
    command = payload.get("command") if isinstance(payload.get("command"), str) else ""
    metadata = state.get("metadata") if isinstance(state.get("metadata"), dict) else {}
    output = state.get("output")
    if not isinstance(output, str) or not output.strip():
        output = metadata.get("output") if isinstance(metadata.get("output"), str) else ""
    fields: dict[str, Any] = {
        "name": str(name or part.get("tool") or part.get("name") or "tool"),
        "detail": _clip(command or payload, _TOOL_DETAIL_CHARS),
        "output": _clip(output, _TOOL_OUTPUT_CHARS),
        "status": str(state.get("status") or ""),
    }
    title = state.get("title")
    if isinstance(title, str) and title.strip():
        fields["title"] = _clip(title, _TOOL_DETAIL_CHARS)
    call_id = part.get("callID") or part.get("call_id") or part.get("id")
    if isinstance(call_id, str) and call_id.strip():
        fields["call_id"] = call_id
    return fields


def _codex_item_fields(item: dict[str, Any]) -> dict[str, Any]:
    """codex item → 时间线字段（命令执行的 command/output、MCP 调用的 arguments）。"""
    item_type = str(item.get("type") or "").strip().lower()
    if item_type == "command_execution":
        fields: dict[str, Any] = {
            "name": "shell",
            "detail": _clip(item.get("command"), _TOOL_DETAIL_CHARS),
            "output": _clip(
                item.get("aggregated_output") or item.get("output"), _TOOL_OUTPUT_CHARS
            ),
            "status": str(item.get("status") or ""),
        }
        if isinstance(item.get("id"), str):
            fields["call_id"] = item["id"]
        return fields
    if "tool_call" in item_type:
        fields: dict[str, Any] = {
            "name": str(item.get("tool") or item.get("name") or item_type),
            "detail": _clip(item.get("arguments") or item.get("input"), _TOOL_DETAIL_CHARS),
            "output": _clip(item.get("result") or item.get("output"), _TOOL_OUTPUT_CHARS),
            "status": str(item.get("status") or ""),
        }
        if isinstance(item.get("id"), str):
            fields["call_id"] = item["id"]
        return fields
    return {}


def _reasoning_text(item: dict[str, Any]) -> str:
    """codex reasoning item → 思考文本：有的版本给 text，有的给 summary 块列表。"""
    text = item.get("text")
    if isinstance(text, str) and text.strip():
        return text
    summary = item.get("summary")
    if isinstance(summary, list):
        parts = [
            str(block.get("text") or "")
            for block in summary
            if isinstance(block, dict) and str(block.get("text") or "").strip()
        ]
        return "".join(parts)
    return ""


#: opencode `run --format json` 真实事件（1.18.29 实测，2026-10-23）：
#:   {"type":"step_start","part":{...}}
#:   {"type":"text","part":{"type":"text","text":"..."}}
#:   {"type":"tool_use","part":{"type":"tool","tool":"bash","state":{...}}}
#:   {"type":"step_finish","part":{"type":"step-finish","tokens":{...},"cost":...}}
#: 有效载荷都在 `part` 里，顶层没有 text/tool；不按 part 归一化会把答复写成空串。
def _normalize_opencode_event(parsed: dict[str, Any], run_id: str, runtime_id: str) -> RuntimeEvent:
    raw_type = str(parsed.get("type") or "").strip().lower()
    part = parsed.get("part")
    part = part if isinstance(part, dict) else {}
    part_type = str(part.get("type") or "").strip().lower()
    data = {**parsed, "runtime_id": runtime_id}
    text = str(part.get("text") or "")

    if part_type == "text" or raw_type == "text":
        # 空文本块（工具调用前的占位）只算状态，不能当成答复
        if text.strip():
            return RuntimeEvent(EVENT_MESSAGE, run_id, {**data, "text": text})
        return RuntimeEvent(EVENT_STATUS, run_id, data)
    if part_type == "reasoning" or raw_type == "reasoning":
        # 推理摘要：手机端时间线里单独一张「思考」卡，不混进最终答复
        return RuntimeEvent(EVENT_THINKING, run_id, {**data, "text": text})
    if raw_type == "step_start" or part_type == "step-start":
        # 每一轮模型调用的起点：没有推理文本时也留一个「思考中」刻度
        return RuntimeEvent(EVENT_THINKING, run_id, {**data, "text": "", "phase": "step_start"})
    if part_type == "tool" or raw_type in ("tool_use", "tool_call", "tool-call"):
        return RuntimeEvent(
            EVENT_TOOL_CALL,
            run_id,
            {**data, **_tool_fields(part)},
        )
    if part_type == "step-finish" or raw_type in ("step_finish", "step-finish"):
        # 每步的 token 与 cost 都在这里；归一成统一键后由 Run 用量累计器汇总
        tokens = part.get("tokens") if isinstance(part.get("tokens"), dict) else {}
        return RuntimeEvent(
            EVENT_STATUS,
            run_id,
            {
                **data,
                "step_finished": True,
                "usage": _normalized_usage(tokens),
                "cost": part.get("cost"),
            },
        )
    if raw_type == "error" or part_type == "error":
        return RuntimeEvent(EVENT_ERROR, run_id, {**data, "message": text or "opencode error"})
    return RuntimeEvent(EVENT_STATUS, run_id, data)


def _normalize_event(parsed: dict[str, Any], run_id: str, runtime_id: str) -> RuntimeEvent:
    raw_type = str(parsed.get("type") or "").strip().lower()
    event_type = _EVENT_TYPE_MAP.get(raw_type)
    if event_type is None:
        # 未知类型不吞：有 text 归 message，否则按 status 记录原始事件
        event_type = EVENT_MESSAGE if _text_of(parsed) else EVENT_STATUS
    return RuntimeEvent(event_type, run_id, {**parsed, "runtime_id": runtime_id})


#: codex `exec --json` 真实事件（codex-cli 0.157.1 实测，2026-10-06）：
#:   {"type":"thread.started","thread_id":...} / {"type":"turn.started"}
#:   {"type":"turn.completed","usage":{...}}
#:   {"type":"item.completed","item":{"type":"agent_message","text":"..."}}
#:   {"type":"item.completed","item":{"type":"error","message":"..."}}
#:   {"type":"item.completed","item":{"type":"mcp_tool_call"|"command_execution", ...}}
#: 只有 agent_message 进答复；其余按工具/状态/错误归类——答复里绝不掺 CLI 的日志噪声。
def _normalize_codex_event(parsed: dict[str, Any], run_id: str, runtime_id: str) -> RuntimeEvent:
    raw_type = str(parsed.get("type") or "").strip().lower()
    item = parsed.get("item")
    item = item if isinstance(item, dict) else {}
    item_type = str(item.get("type") or "").strip().lower()
    data = {**parsed, "runtime_id": runtime_id}

    if parsed.get("non_json"):
        # parse_event 的兜底标记：CLI 的裸日志/提示只当状态，绝不冒充答复
        return RuntimeEvent(EVENT_STATUS, run_id, data)
    if raw_type in ("turn.failed", "thread.failed", "error"):
        return RuntimeEvent(EVENT_ERROR, run_id, {**data, "message": _text_of(parsed) or raw_type})
    if raw_type in ("turn.completed", "thread.completed"):
        return RuntimeEvent(EVENT_FINAL, run_id, {**data, "status": "completed"})
    if raw_type.startswith("item."):
        if item_type == "agent_message":
            return RuntimeEvent(
                EVENT_MESSAGE, run_id, {**data, "text": str(item.get("text") or "")}
            )
        if item_type == "error":
            return RuntimeEvent(
                EVENT_ERROR, run_id, {**data, "message": str(item.get("message") or "")}
            )
        if item_type == "reasoning":
            # 推理摘要单独成「思考」事件：时间线可见，但不进最终答复
            return RuntimeEvent(EVENT_THINKING, run_id, {**data, "text": _reasoning_text(item)})
        if "tool_call" in item_type or item_type == "command_execution":
            return RuntimeEvent(
                EVENT_TOOL_CALL,
                run_id,
                {**data, **_codex_item_fields(item)},
            )
        if "tool" in item_type:
            return RuntimeEvent(EVENT_TOOL_RESULT, run_id, data)
        # 其它 item：只记状态，不进答复
        return RuntimeEvent(EVENT_STATUS, run_id, {**data, "phase": item_type or raw_type})
    mapped = _EVENT_TYPE_MAP.get(raw_type)
    if mapped is not None:
        # 兜底认通用词表（包装脚本/其它 CLI 可能直接吐统一词表的行）
        return RuntimeEvent(mapped, run_id, data)
    # 前后未识别的一律 status（codex 的 INFO 日志与提示走 stderr，不该出现在这里）
    return RuntimeEvent(EVENT_STATUS, run_id, data)


def _text_of(data: dict[str, Any]) -> str:
    for key in ("text", "content", "message"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _codex_config(
    url: str,
    token: str | None,
    *,
    settings: Settings,
    run_id: str = "",
    model: str = "",
    channel: Any = None,
) -> str:
    """Codex 隔离配置（本机 codex-cli 0.157.1 实测字段：mcp_servers.<name>.url + http_headers）。

    隔离 CODEX_HOME 里没有用户的 auth.json，codex 会判定「未登录」；所以模型通道必须自带
    凭据来源：第三方供应商写 [model_providers.<name>]（只写 env_key 环境变量名，密钥不落盘）。

    `channel` 非空时走密钥库通道：provider 块由该条 key 的 base_url / wire_api 生成，
    env_key 指向 `FLUX_MODEL_API_KEY`（真实值由启动环境注入，不写进本文件）。
    """
    lines = ["# Flux 自动生成：为本次 Run 注入 Flux MCP（含一次性令牌，权限 0600）。"]
    lines += _codex_provider_lines(settings, model=model, channel=channel)
    if token is not None:
        lines += [
            "[mcp_servers.flux]",
            f'url = "{url}"',
            f'http_headers = {{ Authorization = "Bearer {token}", X-Flux-Run-Id = "{run_id}" }}',
            # codex exec 的审批策略是 never，MCP 工具调用默认仍要审批——不显式放行，
            # Agent 会判定「MCP 不可用」退回自己读文件（2026-10-06 真实 Run 记录）。
            # 取值实测只有 auto / prompt / writes / approve（codex 0.157.1 解析报错原文）。
            'default_tools_approval_mode = "approve"',
            "",
        ]
    return "\n".join(lines)


def _codex_provider_override(settings: Settings, channel: Any) -> str:
    """本轮 codex thread 应使用的 provider id（覆盖 thread 里存的历史值）。

    provider 与模型同时决定「走哪个通道、扣谁的额度」：只覆盖模型不覆盖 provider，
    从原生 ChatGPT 切到密钥库模型（或反向）时仍会按旧 provider 发请求。
    """
    if channel is not None:
        return _key_provider_id(channel)
    provider = (settings.codex_provider or "").strip().lower()
    if provider in ("", "openai", "chatgpt"):
        # ChatGPT 订阅通道与内置 openai 供应商同构，codex 侧的 provider id 是 openai
        return "openai"
    return provider


def _codex_provider_lines(settings: Settings, *, model: str = "", channel: Any = None) -> list[str]:
    """codex 的模型通道：openai（内置供应商）不写，第三方供应商写一块 model_providers。

    `model` 是任务级覆盖；留空用 `FLUX_CODEX_MODEL`，再留空用 codex 自带默认。
    `channel` 非空时由密钥库条目决定 base_url / wire_api / env_key。
    """
    if channel is not None:
        return _codex_channel_lines(channel, model=model)
    provider = (settings.codex_provider or "").strip().lower()
    configured_model = (model or settings.codex_model or "").strip()
    catalog = (settings.codex_model_catalog or "").strip()
    if provider in ("", "openai", "chatgpt"):
        # ChatGPT 订阅通道（chatgpt）与内置 openai 供应商同构：不写 model_provider 块，
        # 登录态由 Run 目录里的 auth.json 提供（见 _inject_account_auth）。
        # 本机 codex-cli 0.157.1 不识别 preferred_auth_method（未知键被静默忽略），故不写。
        # 空 model = 用 codex 自带默认模型
        return [f'model = "{configured_model}"', ""] if configured_model else []
    if provider != "deepseek":
        raise ConfigurationError(
            f"codex 不支持该模型供应商：{provider}（已知：openai / chatgpt / deepseek）",
            details={"provider": provider, "hint": "FLUX_CODEX_PROVIDER"},
        )
    if not configured_model:
        raise ConfigurationError(
            "codex 走第三方供应商必须显式指定模型（FLUX_CODEX_MODEL）",
            details={"provider": provider},
        )
    env_key = "DEEPSEEK_API_KEY"
    if not os.environ.get(env_key):
        # 提前拦：等 codex 自己报「未登录」时，排查要从 Run 日志里翻，不如这里说清楚
        raise ConfigurationError(
            f"codex 走 {provider} 需要环境变量 {env_key}（Flux 只写变量名，不下发密钥）",
            details={"provider": provider, "env_key": env_key},
        )
    lines = [f'model = "{configured_model}"', f'model_provider = "{provider}"']
    # DeepSeek 没有 codex 内置的联网搜索工具，留着会把请求打回 400
    lines += ['web_search = "disabled"']
    if catalog:
        lines += [f'model_catalog_json = "{catalog}"']
    lines += [
        "",
        f"[model_providers.{provider}]",
        'name = "DeepSeek"',
        f'base_url = "{settings.deepseek_base_url.strip()}"',
        # DeepSeek 原生支持 Responses API，不需要 chat 兼容层
        'wire_api = "responses"',
        f'env_key = "{env_key}"',
        "",
    ]
    return lines


def _codex_channel_lines(channel: Any, *, model: str) -> list[str]:
    """密钥库通道的 codex provider 块：base_url / wire_api 来自该条 key。"""
    provider = str(getattr(channel, "provider", "") or "").strip().lower()
    if provider not in ("deepseek", "openai", "openai_compatible"):
        raise ConfigurationError(
            f"codex 不支持该供应商协议的密钥：{provider}",
            details={"provider": provider, "known": ["deepseek", "openai", "openai_compatible"]},
        )
    configured_model = (model or "").strip()
    if not configured_model:
        raise ConfigurationError(
            "使用密钥库模型时必须在任务上指定模型 id",
            details={"key_id": getattr(channel, "key_id", "")},
        )
    provider_id = _key_provider_id(channel)
    label = str(getattr(channel, "label", "") or "") or PROVIDER_TITLES.get(provider, provider)
    base_url = str(getattr(channel, "base_url", "") or "").strip()
    wire_api = wire_api_for(provider, str(getattr(channel, "wire_api", "") or ""))
    lines = [f'model = "{configured_model}"', f'model_provider = "{provider_id}"']
    if provider == "deepseek":
        # DeepSeek 没有 codex 内置的联网搜索工具，留着会把请求打回 400
        lines += ['web_search = "disabled"']
    lines += [
        "",
        f"[model_providers.{provider_id}]",
        f"name = {json.dumps(label, ensure_ascii=False)}",
        f"base_url = {json.dumps(base_url, ensure_ascii=False)}",
        f'wire_api = "{wire_api}"',
        # 只写变量名；真实密钥由 Flux 注入子进程环境，不落盘
        'env_key = "FLUX_MODEL_API_KEY"',
        "",
    ]
    return lines


def _key_provider_id(channel: Any) -> str:
    """密钥库 provider 的唯一标识：codex/opencode 的 provider 块名。"""
    key_id = str(getattr(channel, "key_id", "") or "").replace("-", "")
    return f"fluxkey{key_id[:8] or 'run'}"


def _opencode_config(
    url: str,
    token: str | None,
    run_id: str = "",
    model: str = "",
    channel: Any = None,
    uploads_dir: str = "",
    mode: str = "normal",
) -> str:
    """OpenCode 合并式配置（本机 opencode 1.18.29 实测：仅 mcp 块，全局 provider 保留）。

    `X-Flux-Run-Id` 把这条 MCP 连接绑到本次 Run：context 摄入、失败步记录、
    `context.steps` 的默认归属都依赖它。少了这个头，CLI 通道下的 step 日志会被整段跳过
    （2026-10-23 基准复跑时发现：Agent 只能自己从环境变量里翻 run_id 再显式传参）。

    `model` 是任务级覆盖：写进这份按 Run 隔离的配置，只影响本 Run，
    不改用户的全局 OpenCode 默认模型（实测 1.18.29 会与全局配置合并）。
    `channel` 非空时追加密钥库 provider 块；这份配置只经 `OPENCODE_CONFIG_CONTENT`
    环境变量交给子进程，不写盘（文件版只保留 MCP）。

    `mode=plan` 时把 opencode 自带的 bash / edit / write 工具整体关掉：Plan 模式"只读"
    不能只靠 MCP 白名单——CLI 的原生工具不经过 Flux 的闸门，不禁用就等于没有只读。
    """
    payload: dict[str, Any] = {"$schema": "https://opencode.ai/config.json"}
    if channel is not None:
        provider = str(getattr(channel, "provider", "") or "").strip().lower()
        configured_model = (model or "").strip()
        if not configured_model:
            raise ConfigurationError(
                "使用密钥库模型时必须在任务上指定模型 id",
                details={"key_id": getattr(channel, "key_id", "")},
            )
        provider_id = _key_provider_id(channel)
        npm = "@ai-sdk/anthropic" if provider == "anthropic" else "@ai-sdk/openai-compatible"
        payload["provider"] = {
            provider_id: {
                "npm": npm,
                "name": str(getattr(channel, "label", "") or "")
                or PROVIDER_TITLES.get(provider, provider),
                "options": {
                    "baseURL": str(getattr(channel, "base_url", "") or ""),
                    "apiKey": str(getattr(channel, "api_key", "") or ""),
                },
                "models": {configured_model: {"name": configured_model}},
            }
        }
        payload["model"] = f"{provider_id}/{configured_model}"
    elif model:
        payload["model"] = model
    permission: dict[str, Any] = {}
    if uploads_dir:
        # 聊天附件落在 uploads 目录（工作区之外）：不开这条权限 opencode 会以
        # 「external_directory 未授权」直接拒绝读文件，模型拿不到内容就只会干巴巴收尾。
        permission["external_directory"] = {f"{uploads_dir.rstrip('/')}/*": "allow"}
    if normalize_mode(mode) == PermissionMode.PLAN.value:
        # Plan 模式只读：opencode 的原生 bash / edit / write 不受 Flux 闸门约束，直接禁用
        # （实测 1.18.29：deny 后这些工具不再出现在 Agent 的工具面上）。
        permission.update({"bash": "deny", "edit": "deny", "write": "deny"})
    else:
        # Normal / Full：原生 bash 走 ask，由 Flux 按同一套风险分类裁决
        # （低风险自动放行、Normal 高风险问用户、Full 只问受保护对象）。
        permission["bash"] = "ask"
    if permission:
        payload["permission"] = permission
    if token is not None:
        payload["mcp"] = {
            "flux": {
                "type": "remote",
                "url": url,
                "headers": {"Authorization": f"Bearer {token}", "X-Flux-Run-Id": run_id},
                "enabled": True,
            }
        }
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


async def approve_native_command(
    container: Any,
    *,
    runtime_id: str,
    run_id: str,
    command: str,
    mode: str,
) -> bool:
    """CLI 原生 shell 的一条命令要不要放行。

    Normal 下高风险命令让用户点；Full 下只有受保护对象才问；
    Unattended 下全部放行（无人值守，用户明确要求不被打断）；
    低风险直接放行。拿不到任务或审批链路异常时失败关闭（命令不执行）。
    """
    if container is None or normalize_mode(mode) == PermissionMode.PLAN.value:
        return False
    run = await container.run_repo.get(run_id)
    if run is None or run.task_id is None:
        logger.warning("原生审批找不到所属任务，按拒绝处理 run=%s cmd=%s", run_id, command[:120])
        return False
    task_id = uuid.UUID(str(run.task_id))

    report = classify_command(command)
    mode_value = normalize_mode(mode)
    if mode_value == PermissionMode.UNATTENDED.value:
        # 免审核（无人值守）：原生 shell 也不再打断用户，只留审计日志
        logger.warning(
            "native_approval.unattended_allow run=%s rule=%s level=%s command=%s",
            run_id,
            report.rule,
            report.level,
            command[:200],
        )
        return True
    if report.level == LEVEL_LOW:
        return True
    if report.level == LEVEL_HIGH and mode_value == PermissionMode.FULL.value:
        # 完全访问：高风险命令不再逐步审批（受保护对象除外）
        return True

    protected = report.level == LEVEL_PROTECTED
    record = await raise_approval(
        container,
        task_id=task_id,
        run_id=run_id,
        kind=APPROVAL_KIND_PROTECTED if protected else APPROVAL_KIND_HIGH_RISK,
        title="受保护操作 · 需要你确认" if protected else "高风险命令 · 需要你确认",
        detail=report.reason,
        meta={
            "command": command,
            "rule": report.rule,
            "source": f"{runtime_id}-native-shell",
            "permission_mode": mode_value,
        },
    )
    outcome = await wait_for_approval(
        container,
        task_id=task_id,
        decision_id=str(record["id"]),
        run_id=run_id,
    )
    logger.info(
        "native_approval.resolved run=%s decision=%s outcome=%s rule=%s",
        run_id,
        record["id"],
        outcome,
        report.rule,
    )
    return outcome == "allowed"


def build_cli_runtime(
    *,
    runtime_id: str,
    adapters: Any,
    settings: Settings,
    supervisor: RunSupervisor,
    bus: EventBus | None = None,
    token_service: AgentTokenService | None = None,
    container: Any = None,
) -> CliRuntime:
    """按 runtime 标识从内建 Adapter 集合里取对应 Adapter 组装 CliRuntime。"""
    spec = _SPECS.get(runtime_id)
    adapter = None
    if spec is not None:
        adapter = next((a for a in adapters if getattr(a, "name", None) == spec.adapter_name), None)
    if adapter is None:
        raise ConfigurationError(
            f"未找到 {runtime_id} 的 CLI Adapter",
            details={"runtime": runtime_id},
        )
    return CliRuntime(
        runtime_id=runtime_id,
        adapter=adapter,
        settings=settings,
        supervisor=supervisor,
        bus=bus,
        token_service=token_service,
        container=container,
    )


#: 供测试/调用方复用：把 Run 目录里的配置路径暴露出来（排障用，不含令牌）
def run_dir_for(settings: Settings, run_id: str) -> Path:
    return Path(settings.dsh_home).expanduser() / "runs" / run_id


__all__ = ["CLI_TOKEN_SCOPES", "CliRuntime", "build_cli_runtime", "run_dir_for"]
