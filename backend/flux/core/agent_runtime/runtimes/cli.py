"""CliRuntime：Flux 主动拉起外部 CLI Agent（P2-1 §6 + P2-2 §7）。

复用既有 `CliAgentAdapter`（`build_run_argv` / `parse_event`）与平台原语
（`platforms.popen_kwargs` 进程组隔离、`platforms.signal_group_graceful` 优雅中断），
并把进程登记进**现有的** `RunSupervisor`——pid/pgid/心跳/超时/对账/取消升级链路全部复用，
不新建第二套生命周期机制。

每次 Run 有自己的私有目录 `<FLUX_DSH_HOME>/runs/<run_id>/`（0700）：里面是该 CLI 的隔离
配置（0600），注入 Flux MCP 端点与一枚一次性 Bearer 令牌（scopes 仅 file.read / file.write）。
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

#: CLI Agent 的 MCP 令牌能力：只读文件 + 提交提案（proposal.create 需要 file.write）。
#: 与 DSH 一致，没有 apply / git / secret 能力——外部 CLI 无法直接落盘。
CLI_TOKEN_SCOPES: tuple[Capability, ...] = (Capability.FILE_READ, Capability.FILE_WRITE)

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
        workspace = self._settings.workspace_root or self._settings.dsh_workspace
        run_dir = Path(self._settings.dsh_home).expanduser() / "runs" / run_id
        token_id: str | None = None
        try:
            run_dir.mkdir(parents=True, exist_ok=True)
            with contextlib.suppress(OSError):
                os.chmod(run_dir, 0o700)
            token_id, token_raw = await self._issue_token(agent_id, run_id)
            runtime_env = self._write_runtime_config(run_dir, token_raw)
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
            run_dir=run_dir,
            argv=argv,
            token_id=token_id,
        )

    async def start(self, launch: RuntimeLaunch) -> RuntimeProcess:
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
                self._adapter.start, launch.argv, env=launch.env, cwd=launch.cwd
            )
        except Exception:
            await self._revoke(launch.token_id)
            raise

        if self._supervisor.is_settled(launch.run_id):
            # 取消/超时抢先落定：不要再跑，直接清理
            await asyncio.to_thread(self._adapter.stop, process)
            await self._revoke(launch.token_id)
            return self._process(launch, process)

        await self._supervisor.mark_running(launch.run_id, pid=process.pid, pgid=process.pgid)
        self._supervisor.register_interrupt(
            launch.run_id, lambda: signal_group_graceful(process.pgid)
        )
        queue: asyncio.Queue[Any] = asyncio.Queue()
        self._queues[launch.run_id] = queue
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

    def _write_runtime_config(self, run_dir: Path, token_raw: str | None) -> dict[str, str]:
        url = self._settings.dsh_mcp_url.strip()
        if token_raw is not None and not url.startswith(("http://", "https://")):
            raise ConfigurationError(f"MCP 端点必须是 http(s) URL：{url!r}", details={"url": url})
        if self.runtime_id == RUNTIME_CODEX:
            home = run_dir / "codex-home"
            home.mkdir(parents=True, exist_ok=True)
            config = home / "config.toml"
            config.write_text(_codex_config(url, token_raw), encoding="utf-8")
            self._secure(config)
            return {"CODEX_HOME": str(home)}
        config = run_dir / "opencode.json"
        config.write_text(_opencode_config(url, token_raw), encoding="utf-8")
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
        stream = process.popen.stdout
        if stream is not None:
            for line in stream:
                parsed = self._adapter.parse_event(line)
                if parsed is None:
                    continue
                event = _normalize_event(parsed, launch.run_id, self.runtime_id)
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
        if final is not status:
            # 看护器改写了终态（取消/超时先落定）：本线程算出的失败原因不串进结果
            error = None
            finish_reason = None
        self._supervisor.release(launch.run_id)
        self._tasks.pop(launch.run_id, None)
        queue.put_nowait(
            RuntimeEvent(
                EVENT_FINAL,
                launch.run_id,
                {"status": str(final), "exit_code": exit_code, "final_response": response},
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


def _normalize_event(parsed: dict[str, Any], run_id: str, runtime_id: str) -> RuntimeEvent:
    raw_type = str(parsed.get("type") or "").strip().lower()
    event_type = _EVENT_TYPE_MAP.get(raw_type)
    if event_type is None:
        # 未知类型不吞：有 text 归 message，否则按 status 记录原始事件
        event_type = EVENT_MESSAGE if _text_of(parsed) else EVENT_STATUS
    return RuntimeEvent(event_type, run_id, {**parsed, "runtime_id": runtime_id})


def _text_of(data: dict[str, Any]) -> str:
    for key in ("text", "content", "message"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _codex_config(url: str, token: str | None) -> str:
    """Codex 隔离配置（本机 codex-cli 0.157.1 实测字段：mcp_servers.<name>.url + http_headers）。"""
    lines = ["# Flux 自动生成：为本次 Run 注入 Flux MCP（含一次性令牌，权限 0600）。"]
    if token is not None:
        lines += [
            "[mcp_servers.flux]",
            f'url = "{url}"',
            f'http_headers = {{ Authorization = "Bearer {token}" }}',
            "",
        ]
    return "\n".join(lines)


def _opencode_config(url: str, token: str | None) -> str:
    """OpenCode 合并式配置（本机 opencode 1.18.29 实测：仅 mcp 块，全局 provider 保留）。"""
    payload: dict[str, Any] = {"$schema": "https://opencode.ai/config.json"}
    if token is not None:
        payload["mcp"] = {
            "flux": {
                "type": "remote",
                "url": url,
                "headers": {"Authorization": f"Bearer {token}"},
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
