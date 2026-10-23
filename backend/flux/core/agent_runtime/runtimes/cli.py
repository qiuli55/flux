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
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.platforms import secure_file, signal_group_graceful
from flux.core.agent_runtime.protocol import build_runtime_env
from flux.core.agent_runtime.runtimes.base import (
    EVENT_ERROR,
    EVENT_FINAL,
    EVENT_MESSAGE,
    EVENT_STATUS,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    RUNTIME_CODEX,
    RUNTIME_OPENCODE,
    RuntimeEvent,
    RuntimeLaunch,
    RuntimeProcess,
)
from flux.core.agent_runtime.supervisor import RunSupervisor
from flux.core.event.bus import EventBus, Events
from flux.core.mcp.auth import AgentTokenService
from flux.enums import Capability, DshRunStatus
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


def _accumulate_usage(usage: _RunUsage, event: RuntimeEvent, parsed: dict[str, Any]) -> None:
    """把一条已归一化事件折算进 Run 用量。

    OpenCode 每步结束发 step-finish（带 tokens/cost）；Codex 只在 turn.completed 带 usage。
    两者都算一步，工具状态为 error/failed 时另计一次失败。
    """
    if event.type == EVENT_TOOL_CALL:
        usage.add_tool(str(event.data.get("state") or ""))
    raw_type = str(parsed.get("type") or "").strip().lower()
    if event.data.get("step_finished"):
        payload = event.data.get("usage")
        usage.add_step(payload if isinstance(payload, dict) else {}, event.data.get("cost"))
        return
    if raw_type in ("turn.completed", "thread.completed"):
        payload = parsed.get("usage")
        usage.add_step(_normalized_usage(payload) if isinstance(payload, dict) else {})


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
        # 结构化 JSON 事件流（默认格式化输出无法可靠识别 tool_call）
        extra_args=("--format", "json"),
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
    ) -> None:
        if runtime_id not in _SPECS:
            raise ConfigurationError(
                f"未知的 CLI runtime：{runtime_id}",
                details={"runtime": runtime_id, "known": list(_SPECS)},
            )
        self.runtime_id = runtime_id
        self._spec = _SPECS[runtime_id]
        self._adapter = adapter
        self._settings = settings
        self._supervisor = supervisor
        self._bus = bus
        self._token_service = token_service
        self._queues: dict[str, asyncio.Queue[Any]] = {}
        self._tokens: dict[str, str] = {}
        self._tasks: dict[str, asyncio.Task[None]] = {}
        #: run_id → stderr 文件句柄（进程退出、Run 落终态后关闭）
        self._stderr_files: dict[str, Any] = {}
        #: run_id → 原生用量累计（终态时写进 run 记录，供预算报表读取）
        self._usage: dict[str, _RunUsage] = {}

    # --- AgentRuntime ---

    async def prepare(
        self,
        *,
        task: Any,
        agent: Any,
        run_id: str,
        instruction: str,
    ) -> RuntimeLaunch:
        agent_id = getattr(agent, "id_str", None)
        if not agent_id:
            raise ConfigurationError(
                f"{self.runtime_id} runtime 需要一个 Agent 档案作为令牌身份",
                details={"runtime": self.runtime_id},
            )
        # 任务级模型覆盖（移动端切换模型）；留空 = 用 runtime 配置里的默认模型
        model_override = str(getattr(task, "model", "") or "").strip()
        workspace = self._settings.workspace_root or self._settings.dsh_workspace
        run_dir = Path(self._settings.dsh_home).expanduser() / "runs" / run_id
        token_id: str | None = None
        try:
            run_dir.mkdir(parents=True, exist_ok=True)
            with contextlib.suppress(OSError):
                os.chmod(run_dir, 0o700)
            token_id, token_raw = await self._issue_token(agent_id, run_id)
            runtime_env = self._write_runtime_config(run_dir, token_raw, run_id, model=model_override)
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
            )
        )
        env.update(runtime_env)
        # 子进程 cwd 变了但 PWD 仍是 Flux 服务进程的旧值；OpenCode 用它判定项目目录
        # （会话会落到错误 workspace，原生文件工具也会看错仓库），必须同步覆盖。
        env["PWD"] = str(workspace)
        argv = self._build_argv(instruction)
        return RuntimeLaunch(
            runtime_id=self.runtime_id,
            run_id=run_id,
            instruction=instruction,
            env=env,
            cwd=workspace,
            session_id=f"flux-task-{getattr(task, 'id', run_id)}",
            task_id=str(getattr(task, "id", "")) or None,
            agent_id=agent_id,
            model=model_override,
            run_dir=run_dir,
            argv=argv,
            token_id=token_id,
        )

    async def start(self, launch: RuntimeLaunch) -> RuntimeProcess:
        stderr_handle = self._open_stderr(launch)
        if stderr_handle is not None:
            self._stderr_files[launch.run_id] = stderr_handle
        try:
            await self._supervisor.create_run(
                run_id=launch.run_id,
                session_id=launch.session_id,
                instruction=launch.instruction,
                agent_id=launch.agent_id,
                task_id=launch.task_id,
            )
            await self._supervisor.mark_starting(launch.run_id)
            process = await asyncio.to_thread(
                self._adapter.start,
                launch.argv,
                env=launch.env,
                cwd=launch.cwd,
                stderr=stderr_handle,
            )
        except Exception:
            self._close_stderr(launch.run_id)
            await self._revoke(launch.token_id)
            raise

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
            self._supervise(process, launch, queue), name=f"cli-run-{launch.run_id}"
        )
        return self._process(launch, process)

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

    def _build_argv(self, instruction: str) -> tuple[str, ...]:
        builder = getattr(self._adapter, "build_run_argv", None)
        if builder is not None:
            base = tuple(builder(instruction))
        else:  # pragma: no cover - 内置 Adapter 都有 build_run_argv
            base = (getattr(self._adapter, "_path", None) or self._spec.adapter_name, instruction)
        override = os.environ.get(self._spec.binary_env)
        if override:
            base = (override, *base[1:])
        executable, *rest = base
        prompt = rest[-1] if rest else instruction
        run_args = rest[:-1]
        return (executable, *run_args, *self._spec.extra_args, prompt)

    async def _issue_token(self, agent_id: str, run_id: str) -> tuple[str | None, str | None]:
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
            scopes=CLI_TOKEN_SCOPES,
            label=f"{self.runtime_id} run {run_id}（Flux 一次性令牌）",
        )
        return str(token.id), raw

    def _write_runtime_config(
        self, run_dir: Path, token_raw: str | None, run_id: str, *, model: str = ""
    ) -> dict[str, str]:
        url = self._settings.dsh_mcp_url.strip()
        if token_raw is not None and not url.startswith(("http://", "https://")):
            raise ConfigurationError(f"MCP 端点必须是 http(s) URL：{url!r}", details={"url": url})
        if self.runtime_id == RUNTIME_CODEX:
            home = run_dir / "codex-home"
            home.mkdir(parents=True, exist_ok=True)
            config = home / "config.toml"
            config.write_text(
                _codex_config(url, token_raw, settings=self._settings, run_id=run_id, model=model),
                encoding="utf-8",
            )
            self._secure(config)
            return {"CODEX_HOME": str(home)}
        config = run_dir / "opencode.json"
        config.write_text(_opencode_config(url, token_raw, run_id), encoding="utf-8")
        self._secure(config)
        return {"OPENCODE_CONFIG": str(config)}

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
                _accumulate_usage(usage, event, parsed)
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

    def _normalize(self, parsed: dict[str, Any], run_id: str) -> RuntimeEvent:
        """按 runtime 归一化事件：codex 的 `--json` 是 item / turn 两级结构，与统一词表不同源。"""
        if self.runtime_id == RUNTIME_CODEX:
            return _normalize_codex_event(parsed, run_id, self.runtime_id)
        if self.runtime_id == RUNTIME_OPENCODE:
            return _normalize_opencode_event(parsed, run_id, self.runtime_id)
        return _normalize_event(parsed, run_id, self.runtime_id)

    def _open_stderr(self, launch: RuntimeLaunch) -> Any:
        """stderr 单独收进 run 目录：CLI 的 INFO 日志/提示不许混进事件流与 Agent 答复。"""
        try:
            return (Path(launch.run_dir) / "stderr.log").open("ab")
        except OSError:
            logger.warning(
                "cli.run 打不开 stderr 日志，本次 Run 的 CLI 噪声将被丢弃 run=%s", launch.run_id
            )
            return None

    def _close_stderr(self, run_id: str) -> None:
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
        error: str | None = None
        finish_reason: str | None = None
        if status is DshRunStatus.FAILED:
            error = f"{self.runtime_id} CLI 进程以退出码 {exit_code} 结束"
            finish_reason = str(exit_code)
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
    if part_type == "tool" or raw_type in ("tool_use", "tool_call", "tool-call"):
        state = part.get("state") if isinstance(part.get("state"), dict) else {}
        name = str(part.get("tool") or part.get("name") or "tool")
        return RuntimeEvent(
            EVENT_TOOL_CALL,
            run_id,
            {**data, "name": name, "state": str(state.get("status") or "")},
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
        if "tool_call" in item_type or item_type == "command_execution":
            name = item.get("tool") or item.get("name") or item.get("command") or item_type
            return RuntimeEvent(EVENT_TOOL_CALL, run_id, {**data, "name": str(name)})
        if "tool" in item_type:
            return RuntimeEvent(EVENT_TOOL_RESULT, run_id, data)
        # reasoning / 其它 item：只记状态，不进答复
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
    url: str, token: str | None, *, settings: Settings, run_id: str = "", model: str = ""
) -> str:
    """Codex 隔离配置（本机 codex-cli 0.157.1 实测字段：mcp_servers.<name>.url + http_headers）。

    隔离 CODEX_HOME 里没有用户的 auth.json，codex 会判定「未登录」；所以模型通道必须自带
    凭据来源：第三方供应商写 [model_providers.<name>]（只写 env_key 环境变量名，密钥不落盘）。
    """
    lines = ["# Flux 自动生成：为本次 Run 注入 Flux MCP（含一次性令牌，权限 0600）。"]
    lines += _codex_provider_lines(settings, model=model)
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


def _codex_provider_lines(settings: Settings, *, model: str = "") -> list[str]:
    """codex 的模型通道：openai（内置供应商）不写，第三方供应商写一块 model_providers。

    `model` 是任务级覆盖；留空用 `FLUX_CODEX_MODEL`，再留空用 codex 自带默认。
    """
    provider = (settings.codex_provider or "").strip().lower()
    configured_model = (model or settings.codex_model or "").strip()
    catalog = (settings.codex_model_catalog or "").strip()
    if provider in ("", "openai"):
        # 空 model = 用 codex 自带默认模型
        return [f'model = "{configured_model}"', ""] if configured_model else []
    if provider != "deepseek":
        raise ConfigurationError(
            f"codex 不支持该模型供应商：{provider}（已知：openai / deepseek）",
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


def _opencode_config(url: str, token: str | None, run_id: str = "") -> str:
    """OpenCode 合并式配置（本机 opencode 1.18.29 实测：仅 mcp 块，全局 provider 保留）。

    `X-Flux-Run-Id` 把这条 MCP 连接绑到本次 Run：context 摄入、失败步记录、
    `context.steps` 的默认归属都依赖它。少了这个头，CLI 通道下的 step 日志会被整段跳过
    （2026-10-23 基准复跑时发现：Agent 只能自己从环境变量里翻 run_id 再显式传参）。
    """
    payload: dict[str, Any] = {"$schema": "https://opencode.ai/config.json"}
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


def build_cli_runtime(
    *,
    runtime_id: str,
    adapters: Any,
    settings: Settings,
    supervisor: RunSupervisor,
    bus: EventBus | None = None,
    token_service: AgentTokenService | None = None,
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
    )


#: 供测试/调用方复用：把 Run 目录里的配置路径暴露出来（排障用，不含令牌）
def run_dir_for(settings: Settings, run_id: str) -> Path:
    return Path(settings.dsh_home).expanduser() / "runs" / run_id


__all__ = ["CLI_TOKEN_SCOPES", "CliRuntime", "build_cli_runtime", "run_dir_for"]
