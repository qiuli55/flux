"""Claude Code stream-json 会话：`claude -p` 双向流 + 原生工具审批。

Claude Code 非交互模式（`-p`）在 `--input-format stream-json --output-format
stream-json --include-partial-messages` 下会吐真增量（`content_block_delta`），并在需要
工具授权时以 `control_request` 反向询问宿主；`--permission-prompt-tool stdio` 把裁决权
交给 Flux，而不是让 CLI 自己按本地规则放行。

这里只做一件事：把一轮 stdout 事件翻译成 Flux 的 text / thinking / tool / usage 事件，
并把 `can_use_tool` 请求交给 Flux 现有的审批链路。进程生命周期仍由 CliRuntime +
RunSupervisor 管（pid/pgid/心跳/取消升级都不变）——与 `codex_server.py` 同一套结构。

实测快照：Claude Code 2.1.323（2026-11-16）。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import threading
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from flux.core.agent_runtime.adapters.claude_code import claude_config_dir
from flux.core.permissions.modes import PermissionMode, normalize_mode
from flux.logging import get_logger

logger = get_logger(__name__)

#: 工具参数/输出进时间线的展示上限（与 cli.py 同一口径）
_TOOL_DETAIL_CHARS = 600
_TOOL_OUTPUT_CHARS = 2000

#: 收到 result 后等 CLI 自然退出的优雅期（秒）。Claude Code 在 stdin EOF 后还要把会话
#: jsonl 落盘再退出；立刻 SIGTERM 可能让下一轮 `--resume` 找不到会话文件。
CLAUDE_EXIT_GRACE_SECONDS = 10.0

#: 会被 Flux 按路径白名单裁决的文件类工具
_EDIT_TOOLS: frozenset[str] = frozenset({"Edit", "Write", "MultiEdit", "NotebookEdit"})
#: 需要走命令审批的 shell 类工具
_SHELL_TOOLS: frozenset[str] = frozenset({"Bash", "PowerShell"})
#: Plan 模式下直接禁用的原生工具（只读不能只靠宿主审批——CLI 自己也可能不问）
_PLAN_DISALLOWED_TOOLS: tuple[str, ...] = ("Bash", "Edit", "Write", "MultiEdit", "NotebookEdit")

TextHandler = Callable[[str], Awaitable[None]]
ThinkingHandler = Callable[[str, str], Awaitable[None]]
ToolHandler = Callable[[dict[str, Any], str], Awaitable[None]]
StepHandler = Callable[[dict[str, Any]], Awaitable[None]]
StatusHandler = Callable[[dict[str, Any]], Awaitable[None]]
#: 原生命令审批：给一条命令，返回是否放行
ApprovalHandler = Callable[[str], Awaitable[bool]]


def claude_argv(
    executable: str,
    *,
    mode: str,
    model: str = "",
    resume: str = "",
    mcp_config: str = "",
    uploads_dir: str = "",
) -> tuple[str, ...]:
    """Claude Code 非交互 + 双向流式的启动 argv。

    权限映射与 Flux 三种模式对齐：
    - plan → `--permission-mode plan`，并直接禁用会写盘/执行的原生工具；
    - normal → `default`（原生工具逐条走 `can_use_tool`，由 Flux 裁决）；
    - full / unattended → `acceptEdits`（文件编辑不再逐条打断，受保护对象仍走审批）。

    所有模式都带 `--permission-prompt-tool stdio`：授权请求回到宿主，而不是 CLI 本地规则。
    `--mcp-config` 注入 Flux MCP（一次性令牌）；不加 `--strict-mcp-config`，保留用户自己的
    MCP；不限制 `--setting-sources`，用户的模型端点仍从 `~/.claude/settings.json` 读取。
    """
    value = normalize_mode(mode)
    permission = "default"
    disallowed: tuple[str, ...] = ()
    if value == PermissionMode.PLAN.value:
        permission = "plan"
        disallowed = _PLAN_DISALLOWED_TOOLS
    elif value in (PermissionMode.FULL.value, PermissionMode.UNATTENDED.value):
        permission = "acceptEdits"
    argv: list[str] = [
        executable,
        "-p",
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json",
        "--verbose",
        "--include-partial-messages",
        "--permission-prompt-tool",
        "stdio",
        "--permission-mode",
        permission,
    ]
    if disallowed:
        argv.extend(("--disallowed-tools", *disallowed))
    if model.strip():
        argv.extend(("--model", model.strip()))
    if resume.strip():
        argv.extend(("--resume", resume.strip()))
    if mcp_config.strip():
        argv.extend(("--mcp-config", mcp_config))
    # 放行整个 Flux MCP server；具体能力仍由令牌 scopes 与 MCP 权限策略闸门约束。
    argv.extend(("--allowed-tools", "mcp__flux"))
    if uploads_dir.strip():
        # 聊天附件落在工作区之外：不开这条目录，模型读不到用户刚传的文件。
        argv.extend(("--add-dir", uploads_dir))
    return tuple(argv)


def claude_mcp_config(url: str, token: str | None, run_id: str) -> str:
    """`--mcp-config` 文件内容：Flux MCP 端点 + 一次性 Bearer 令牌。

    令牌为 None（MCP 注入关闭）时返回空 server 表，不写任何凭据。
    """
    payload: dict[str, Any] = {"mcpServers": {}}
    if token is not None:
        headers = {"Authorization": f"Bearer {token}"}
        if run_id:
            headers["X-Flux-Run-Id"] = run_id
        payload["mcpServers"]["flux"] = {
            "type": "http",
            "url": url,
            "headers": headers,
        }
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def claude_session_file(session_id: str) -> Path | None:
    """找到某个 Claude Code 会话的 jsonl 文件；不存在返回 None。

    会话落在 `${CLAUDE_CONFIG_DIR:-~/.claude}/projects/<编码后的 cwd>/<session_id>.jsonl`。
    `--resume` 一个不存在的 id 会让 CLI 直接失败（`No conversation found with session
    ID`），所以只有文件真存在时才带上 `--resume`。
    """
    value = (session_id or "").strip()
    if not value:
        return None
    projects = claude_config_dir() / "projects"
    try:
        for candidate in projects.glob(f"*/{value}.jsonl"):
            if candidate.is_file():
                return candidate
    except OSError:
        return None
    return None


@dataclass(slots=True)
class ClaudeTurnResult:
    text: str = ""
    session_id: str = ""
    error: str | None = None
    usage: dict[str, Any] = field(default_factory=dict)
    #: 本轮收到过几步用量（message_delta 的条数；result.usage 兜底时算一步）
    steps: int = 0


class ClaudeCodeSession:
    """一条 `claude -p` 进程上的一轮对话。

    每轮一个进程（`-p` 语义），stdout 由线程逐行读取后投递到事件循环；`run()` 消费到
    `result` 为止。跨轮续聊靠 `--resume <session_id>`，不在进程内复用。
    """

    def __init__(self, process: Any) -> None:
        self._popen = process.popen
        self._queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._reader_started = False
        self._closed = False
        #: 回调（bind 逐轮绑定）
        self._on_text: TextHandler | None = None
        self._on_thinking: ThinkingHandler | None = None
        self._on_tool: ToolHandler | None = None
        self._on_step: StepHandler | None = None
        self._on_status: StatusHandler | None = None
        self._approval_handler: ApprovalHandler | None = None
        #: tool_use id → 工具名（tool_result 只有 id，靠它补出可读名称）
        self._tool_names: dict[str, str] = {}
        #: 当前 message id 与「这条 message 是否收过 text_delta」
        self._current_message_id = ""
        self._delta_message_ids: set[str] = set()
        self._streamed_text_for_message = False
        #: 正在等待审批回包的 control_request 任务；run 收口时要取消，避免挂到下一轮
        self._approval_tasks: set[asyncio.Task[None]] = set()

    # --- 对外 ---
    def bind(
        self,
        *,
        on_text: TextHandler,
        on_thinking: ThinkingHandler,
        on_tool: ToolHandler,
        on_step: StepHandler | None = None,
        on_status: StatusHandler | None = None,
        approval_handler: ApprovalHandler | None = None,
    ) -> None:
        """绑定本轮的回调：正文 / 思考 / 工具 / 每步用量 / 状态 / 命令审批。"""
        self._on_text = on_text
        self._on_thinking = on_thinking
        self._on_tool = on_tool
        self._on_step = on_step
        self._on_status = on_status
        self._approval_handler = approval_handler

    async def run(
        self,
        instruction: str,
        *,
        permission_mode: str = "normal",
        workspace: str = "",
        uploads_dir: str = "",
    ) -> ClaudeTurnResult:
        """发一轮指令并消费事件，直到 `result` 或进程退出。"""
        if self._on_text is None or self._on_thinking is None or self._on_tool is None:
            raise RuntimeError("claude 会话未绑定本轮回调")
        loop = asyncio.get_running_loop()
        if self._loop is not None and self._loop is not loop:
            raise RuntimeError("claude 会话已绑定其他事件循环，不能跨循环复用")
        self._loop = loop
        if not self._reader_started:
            self._reader_started = True
            threading.Thread(
                target=self._read_stdout,
                args=(loop,),
                name="claude-stream-stdout",
                daemon=True,
            ).start()
        # 先握手（control_request/initialize），再发用户消息；两行按顺序进 stdin。
        await self._write(
            {
                "type": "control_request",
                "request_id": "init-1",
                "request": {"subtype": "initialize", "hooks": None},
            }
        )
        await self._write(
            {
                "type": "user",
                "message": {"role": "user", "content": instruction},
                "parent_tool_use_id": None,
                "session_id": "",
            }
        )
        return await self._consume(
            permission_mode=normalize_mode(permission_mode),
            workspace=workspace,
            uploads_dir=uploads_dir,
        )

    # --- 读 stdout（线程）---
    def _read_stdout(self, loop: asyncio.AbstractEventLoop) -> None:
        stream = self._popen.stdout
        if stream is None:
            self._post(loop, None)
            return
        try:
            for line in stream:
                text = line.strip()
                if not text:
                    continue
                try:
                    message = json.loads(text)
                except ValueError:
                    logger.debug("claude stream 非 JSON 输出：%s", text[:200])
                    continue
                if isinstance(message, dict):
                    self._post(loop, message)
        except Exception:  # noqa: BLE001 - 进程被杀时读流中断属正常
            logger.debug("claude stream stdout 读取中断", exc_info=True)
        finally:
            # stdout 关闭时进程可能还没被回收：等一下再上报，失败原因里才带得到退出码
            with contextlib.suppress(Exception):
                self._popen.wait(timeout=5)
            self._post(loop, None)

    def _post(self, loop: asyncio.AbstractEventLoop, message: dict[str, Any] | None) -> None:
        """线程 → 事件循环投递；服务退出时循环可能已关闭，重投失败不影响进程回收。"""
        with contextlib.suppress(RuntimeError):
            loop.call_soon_threadsafe(self._dispatch, message)

    def _dispatch(self, message: dict[str, Any] | None) -> None:
        if message is None:
            self._closed = True
        self._queue.put_nowait(message)

    # --- 写 stdin ---
    async def _write(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False) + "\n"
        await asyncio.to_thread(self._write_sync, data)

    def _write_sync(self, data: str) -> None:
        stdin = self._popen.stdin
        if stdin is None:
            raise RuntimeError("claude 没有 stdin 管道")
        try:
            stdin.write(data)
            stdin.flush()
        except (BrokenPipeError, OSError, ValueError) as exc:
            raise RuntimeError(self._exit_reason()) from exc

    async def _close_stdin(self) -> None:
        """收到 result 后关闭 stdin：CLI 看到 EOF 自行退出（退出码 0）。"""

        def _close() -> None:
            stdin = self._popen.stdin
            if stdin is not None:
                with contextlib.suppress(OSError, ValueError):
                    stdin.close()

        with contextlib.suppress(Exception):
            await asyncio.to_thread(_close)

    def _exit_reason(self) -> str:
        reason = "claude 进程提前退出"
        code: int | None = None
        with contextlib.suppress(Exception):
            code = self._popen.poll()
            if code is None:
                # 写入 stdin 失败时子进程可能刚退出、还没被回收
                code = self._popen.wait(timeout=0.2)
        if code is not None:
            reason = f"{reason}（退出码 {code}）"
        return reason

    # --- 一轮事件 ---
    async def _consume(
        self,
        *,
        permission_mode: str,
        workspace: str,
        uploads_dir: str,
    ) -> ClaudeTurnResult:
        texts: list[str] = []
        session_id = ""
        usage: dict[str, Any] = {}
        steps = 0
        try:
            while True:
                message = await self._queue.get()
                if message is None:
                    if self._closed:
                        return ClaudeTurnResult(
                            text="".join(texts),
                            session_id=session_id,
                            error=self._exit_reason(),
                            usage=usage,
                            steps=steps,
                        )
                    continue
                kind = str(message.get("type") or "")
                if kind == "stream_event":
                    steps, usage = await self._handle_stream_event(message, texts, steps, usage)
                elif kind == "assistant":
                    await self._handle_assistant(message, texts)
                elif kind == "user":
                    await self._handle_tool_results(message)
                elif kind == "system":
                    subtype = str(message.get("subtype") or "")
                    sid = str(message.get("session_id") or "")
                    if sid and not session_id:
                        session_id = sid
                    await self._status({"phase": subtype or "system", "event": message})
                elif kind == "control_request":
                    task = asyncio.create_task(
                        self._answer_control_request(
                            message,
                            permission_mode=permission_mode,
                            workspace=workspace,
                            uploads_dir=uploads_dir,
                        )
                    )
                    self._approval_tasks.add(task)
                    task.add_done_callback(self._finish_approval_task)
                elif kind == "control_response":
                    logger.debug("claude control_response: %s", message.get("response"))
                elif kind == "result":
                    sid = str(message.get("session_id") or "")
                    if sid:
                        session_id = sid
                    subtype = str(message.get("subtype") or "success")
                    failed = bool(message.get("is_error")) or subtype != "success"
                    result_usage = normalize_claude_usage(message.get("usage"))
                    if _has_usage(result_usage):
                        usage = result_usage
                    if steps == 0 and _has_usage(result_usage) and self._on_step is not None:
                        steps = 1
                        await self._on_step(result_usage)
                    await self._close_stdin()
                    if failed:
                        error = _result_error(message, subtype)
                        return ClaudeTurnResult(
                            text="".join(texts),
                            session_id=session_id,
                            error=error,
                            usage=usage,
                            steps=steps,
                        )
                    final_text = str(message.get("result") or "")
                    return ClaudeTurnResult(
                        text=final_text or "".join(texts),
                        session_id=session_id,
                        usage=usage,
                        steps=steps,
                    )
                else:
                    # system/status、thinking_tokens 等噪声只记状态，绝不冒充答复
                    await self._status({"phase": kind or "unknown", "event": message})
        finally:
            # 本轮已收口（result / 进程退出 / 取消）：还在等审批回包的任务没人消费了，
            # 必须取消，否则会挂着审批卡直到超时，甚至跨到下一轮。
            await self._cancel_approval_tasks()

    def _finish_approval_task(self, task: asyncio.Task[None]) -> None:
        """审批任务完成回调：移出集合，并取走异常避免 "never retrieved" 警告。"""
        self._approval_tasks.discard(task)
        if task.cancelled():
            return
        error = task.exception()
        if error is not None:
            logger.warning("claude 审批回包任务失败：%s", error, exc_info=error)

    async def _cancel_approval_tasks(self) -> None:
        """取消所有未完成的审批回包任务，并等它们真正收口。"""
        pending = [task for task in self._approval_tasks if not task.done()]
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        self._approval_tasks.clear()

    async def _handle_stream_event(
        self,
        message: dict[str, Any],
        texts: list[str],
        steps: int,
        usage: dict[str, Any],
    ) -> tuple[int, dict[str, Any]]:
        event = message.get("event")
        event = event if isinstance(event, dict) else {}
        etype = str(event.get("type") or "")
        parent = str(message.get("parent_tool_use_id") or "")
        if etype == "message_start":
            payload = event.get("message")
            payload = payload if isinstance(payload, dict) else {}
            self._current_message_id = str(payload.get("id") or "")
            self._streamed_text_for_message = False
            # 每一轮模型调用的起点：没有推理文本时也留一个「思考中」刻度
            await self._thinking("", "step_start")
        elif etype == "content_block_delta":
            delta = event.get("delta")
            delta = delta if isinstance(delta, dict) else {}
            delta_type = str(delta.get("type") or "")
            if delta_type == "text_delta":
                text = str(delta.get("text") or "")
                if text:
                    self._streamed_text_for_message = True
                    if self._current_message_id:
                        self._delta_message_ids.add(self._current_message_id)
                    if parent:
                        # 子 agent 的正文不进主答复，只留状态
                        await self._status({"phase": "subagent_text", "event": message})
                    else:
                        texts.append(text)
                        if self._on_text is not None:
                            await self._on_text(text)
            elif delta_type == "thinking_delta":
                thinking = str(delta.get("thinking") or "")
                if thinking:
                    await self._thinking(thinking, "")
        elif etype == "message_delta":
            step = normalize_claude_usage(event.get("usage"))
            usage = step
            if self._on_step is not None:
                await self._on_step(step)
            return steps + 1, usage
        return steps, usage

    async def _handle_assistant(self, message: dict[str, Any], texts: list[str]) -> None:
        payload = message.get("message")
        payload = payload if isinstance(payload, dict) else {}
        message_id = str(payload.get("id") or self._current_message_id)
        parent = str(message.get("parent_tool_use_id") or "")
        content = payload.get("content")
        blocks = content if isinstance(content, list) else []
        streamed = message_id in self._delta_message_ids or (
            self._streamed_text_for_message and not self._current_message_id
        )
        for block in blocks:
            if not isinstance(block, dict):
                continue
            block_type = str(block.get("type") or "")
            if block_type == "tool_use":
                call_id = str(block.get("id") or "")
                if call_id:
                    self._tool_names[call_id] = str(block.get("name") or "tool")
                if self._on_tool is not None:
                    await self._on_tool(claude_tool_fields(block), "running")
            elif block_type == "text" and not parent and not streamed:
                # 没开 partial / 没收到 delta 时的兜底；已经流过的 text 不再重复进答复
                text = str(block.get("text") or "")
                if text:
                    texts.append(text)
                    if self._on_text is not None:
                        await self._on_text(text)
        self._streamed_text_for_message = False
        self._current_message_id = ""

    async def _handle_tool_results(self, message: dict[str, Any]) -> None:
        payload = message.get("message")
        payload = payload if isinstance(payload, dict) else {}
        content = payload.get("content")
        blocks = content if isinstance(content, list) else []
        for block in blocks:
            if not isinstance(block, dict) or str(block.get("type") or "") != "tool_result":
                continue
            state = "error" if block.get("is_error") else "completed"
            if self._on_tool is not None:
                await self._on_tool(
                    claude_tool_result_fields(block, self._tool_names),
                    state,
                )

    async def _thinking(self, text: str, phase: str) -> None:
        if self._on_thinking is not None:
            await self._on_thinking(text, phase)

    async def _status(self, fields: dict[str, Any]) -> None:
        if self._on_status is not None:
            await self._on_status(fields)

    # --- 原生工具审批 ---
    async def _answer_control_request(
        self,
        message: dict[str, Any],
        *,
        permission_mode: str,
        workspace: str,
        uploads_dir: str,
    ) -> None:
        request_id = str(message.get("request_id") or "")
        request = message.get("request")
        request = request if isinstance(request, dict) else {}
        decision = await decide_control_request(
            request,
            permission_mode=permission_mode,
            workspace=workspace,
            uploads_dir=uploads_dir,
            approval_handler=self._approval_handler,
        )
        if decision is None:
            payload = {
                "type": "control_response",
                "response": {
                    "subtype": "error",
                    "request_id": request_id,
                    "error": "unsupported",
                },
            }
        else:
            payload = {
                "type": "control_response",
                "response": {
                    "subtype": "success",
                    "request_id": request_id,
                    "response": decision,
                },
            }
        try:
            await self._write(payload)
        except Exception:  # noqa: BLE001 - 进程已退出时回包失败不影响主链路
            logger.debug("claude control_response 回包失败 id=%s", request_id, exc_info=True)


async def decide_control_request(
    request: dict[str, Any],
    *,
    permission_mode: str,
    workspace: str,
    uploads_dir: str,
    approval_handler: ApprovalHandler | None,
) -> dict[str, Any] | None:
    """裁决一条 `can_use_tool` 请求；返回要回给 CLI 的 response 内容。

    - Plan：一律拒绝（只读）；
    - Bash / PowerShell：走 Flux 的 `approve_native_command`（同一套风险分类与审批卡）；
    - Edit / Write / MultiEdit / NotebookEdit：路径必须落在工作区或附件目录内；
    - MCP 工具（`mcp__*`）与其它工具：放行。

    `approval_handler` 抛异常时失败关闭（deny）。未知 subtype 返回 None（回 unsupported）。
    """
    if str(request.get("subtype") or "") != "can_use_tool":
        return None
    tool_name = str(request.get("tool_name") or "")
    input_payload = request.get("input")
    input_payload = input_payload if isinstance(input_payload, dict) else {}
    if normalize_mode(permission_mode) == PermissionMode.PLAN.value:
        return _deny("Plan 模式只读：不允许执行原生工具")
    if tool_name in _SHELL_TOOLS:
        command = str(input_payload.get("command") or "").strip()
        # 没有审批入口 = 拿不到 Flux 的裁决能力：失败关闭，绝不默认放行。
        if approval_handler is None:
            return _deny("审批链路不可用，已拒绝")
        try:
            allowed = await approval_handler(command)
        except Exception:  # noqa: BLE001 - 审批链路异常时失败关闭
            logger.warning(
                "claude 原生命令审批失败，按拒绝处理 tool=%s cmd=%s",
                tool_name,
                command[:120],
                exc_info=True,
            )
            allowed = False
        if allowed:
            return _allow(input_payload)
        return _deny("用户拒绝了这条命令")
    if tool_name in _EDIT_TOOLS:
        raw_path = input_payload.get("file_path") or input_payload.get("notebook_path")
        if not isinstance(raw_path, str) or not raw_path.strip():
            return _deny("缺少文件路径，已拒绝")
        if _within_roots(raw_path, workspace, uploads_dir):
            return _allow(input_payload)
        return _deny("路径不在工作区或附件目录内")
    # MCP 工具与其它只读/未知工具：放行（Flux MCP 侧仍有令牌 scopes 闸门）
    return _allow(input_payload)


def _allow(input_payload: dict[str, Any]) -> dict[str, Any]:
    return {"behavior": "allow", "updatedInput": input_payload}


def _deny(reason: str) -> dict[str, Any]:
    return {"behavior": "deny", "message": reason}


def _within_roots(raw_path: str, workspace: str, uploads_dir: str) -> bool:
    try:
        target = Path(raw_path).expanduser()
        if not target.is_absolute() and str(workspace or "").strip():
            # CLI 偶尔给相对路径：按它的 cwd（Flux 工作区）解析，而不是 Flux 进程的 cwd
            target = Path(workspace).expanduser() / target
        target = target.resolve()
    except OSError:
        return False
    roots: list[Path] = []
    for value in (workspace, uploads_dir):
        if not str(value or "").strip():
            continue
        try:
            roots.append(Path(value).expanduser().resolve())
        except OSError:
            continue
    return any(target == root or root in target.parents for root in roots)


def normalize_claude_usage(raw: Any) -> dict[str, Any]:
    """Claude 的用量字段 → Flux 统一键（`cache_creation` 记作 cache_write）。"""
    usage = raw if isinstance(raw, dict) else {}
    return {
        "input": usage.get("input_tokens", usage.get("input")),
        "output": usage.get("output_tokens", usage.get("output")),
        "cache_read": usage.get("cache_read_input_tokens", usage.get("cache_read")),
        "cache_write": usage.get("cache_creation_input_tokens", usage.get("cache_write")),
    }


def _has_usage(usage: dict[str, Any]) -> bool:
    """归一化后的用量里至少有一个非空字段（否则不值得记一步）。"""
    return any(
        usage.get(key) is not None for key in ("input", "output", "cache_read", "cache_write")
    )


def claude_tool_fields(block: dict[str, Any]) -> dict[str, Any]:
    """`assistant.message.content[].tool_use` → 时间线字段。"""
    name = str(block.get("name") or "tool")
    payload = block.get("input")
    payload = payload if isinstance(payload, dict) else {}
    if name in _SHELL_TOOLS:
        detail: Any = payload.get("command")
    else:
        detail = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
    fields: dict[str, Any] = {
        "name": name,
        "detail": _clip(detail, _TOOL_DETAIL_CHARS),
        "status": "running",
    }
    call_id = block.get("id")
    if isinstance(call_id, str) and call_id:
        fields["call_id"] = call_id
    return fields


def claude_tool_result_fields(block: dict[str, Any], names: dict[str, str]) -> dict[str, Any]:
    """`user.message.content[].tool_result` → 时间线字段（带调用 id 与工具名）。"""
    call_id = str(block.get("tool_use_id") or "")
    fields: dict[str, Any] = {
        "name": names.get(call_id, "tool"),
        "detail": "",
        "output": _clip(_tool_result_text(block.get("content")), _TOOL_OUTPUT_CHARS),
        "status": "error" if block.get("is_error") else "completed",
    }
    if call_id:
        fields["call_id"] = call_id
    return fields


def _tool_result_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "".join(parts)
    if content is None:
        return ""
    return json.dumps(content, ensure_ascii=False, separators=(",", ":"), default=str)


def _clip(value: Any, limit: int) -> str:
    """把任意值压成限长文本；None / 空值一律给空串。"""
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


def _result_error(message: dict[str, Any], subtype: str) -> str:
    """失败 result → 给用户看的失败原因。"""
    parts: list[str] = []
    errors = message.get("errors")
    if isinstance(errors, list):
        parts.extend(str(item).strip() for item in errors if str(item).strip())
    result = str(message.get("result") or "").strip()
    if result:
        parts.append(result)
    return "；".join(parts) or f"claude 本轮以 {subtype} 结束"


__all__ = [
    "CLAUDE_EXIT_GRACE_SECONDS",
    "ClaudeCodeSession",
    "ClaudeTurnResult",
    "claude_argv",
    "claude_mcp_config",
    "claude_session_file",
    "claude_tool_fields",
    "claude_tool_result_fields",
    "decide_control_request",
    "normalize_claude_usage",
]
