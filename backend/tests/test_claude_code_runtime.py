"""Claude Code 一等运行时（claude-code）的确定性测试。

不启动真实 `claude`：Adapter 探测用注入的 `which` / `probe_runner`；会话事件翻译直接
往队列塞 stream-json 事件；端到端用例用假 `claude` 脚本走完整 CliRuntime → RunSupervisor
链路（与 test_cli_runtime.py 的假 CLI 同一套写法）。
"""

from __future__ import annotations

import asyncio
import io
import json
import os
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from flux.config import Settings
from flux.core.agent_runtime import platforms
from flux.core.agent_runtime.adapters import ClaudeCodeAdapter, build_default_adapters
from flux.core.agent_runtime.runtimes.base import RUNTIME_CLAUDE_CODE
from flux.core.agent_runtime.runtimes.claude_code import (
    ClaudeCodeSession,
    claude_argv,
    claude_mcp_config,
    claude_tool_fields,
    claude_tool_result_fields,
    decide_control_request,
    normalize_claude_usage,
)
from flux.core.agent_runtime.runtimes.cli import CliRuntime
from flux.errors import ConfigurationError
from tests.test_cli_runtime import (
    PREFIX,
    _cli_app,
    _create_cli_agent,
    _mark_ready,
    _start_task,
    _wait_last_message,
    _wait_status,
    _write_script,
)

# --- Adapter：探测 / 登录态 ---


def _probe_table(auth_output: str):
    def _run(argv, *, timeout: float = 10.0):  # noqa: ANN001, ARG001
        key = tuple(argv[1:])
        if key == ("--version",):
            return 0, "2.1.323 (Claude Code)"
        if key == ("auth", "status", "--json"):
            return 0, auth_output
        raise AssertionError(f"未预设的探测命令：{argv}")

    return _run


def _adapter(auth_output: str) -> ClaudeCodeAdapter:
    return ClaudeCodeAdapter(
        which=lambda _: "/usr/local/bin/claude",
        probe_runner=_probe_table(auth_output),
    )


def test_adapter_logged_in_is_ok() -> None:
    probe = _adapter('{"loggedIn": true, "authMethod": "api_key"}').discover()
    assert probe.version == "2.1.323"
    assert probe.auth_status == "ok"
    assert probe.capabilities == ("mcp", "stream")


def test_adapter_logged_out_without_credentials_is_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path))
    probe = _adapter('{"loggedIn": false}').discover()
    assert probe.auth_status == "missing"


def test_adapter_logged_out_but_settings_has_api_key(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """CLI 自己没登录，但 settings.json 的 env 里配了 key：启动时仍可用。"""
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path))
    (tmp_path / "settings.json").write_text(
        json.dumps({"env": {"ANTHROPIC_API_KEY": "sk-ant-local"}}), encoding="utf-8"
    )
    probe = _adapter('{"loggedIn": false}').discover()
    assert probe.auth_status == "ok"


def test_adapter_logged_out_but_api_key_helper(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(tmp_path))
    (tmp_path / "settings.json").write_text(
        json.dumps({"apiKeyHelper": "/usr/local/bin/get-key"}), encoding="utf-8"
    )
    probe = _adapter('{"loggedIn": false}').discover()
    assert probe.auth_status == "ok"


def test_adapter_broken_json_is_unknown() -> None:
    assert _adapter("not json at all").check_auth() == "unknown"


def test_default_registry_includes_claude_code() -> None:
    names = {adapter.name for adapter in build_default_adapters()}
    assert names == {"opencode", "codex", "claude-code", "cursor", "antigravity"}


# --- argv / MCP 配置 ---


@pytest.mark.parametrize(
    ("mode", "expected"),
    [
        ("plan", "plan"),
        ("normal", "default"),
        ("full", "acceptEdits"),
        ("unattended", "acceptEdits"),
    ],
)
def test_argv_permission_mode_mapping(mode: str, expected: str) -> None:
    argv = claude_argv("claude", mode=mode)
    index = argv.index("--permission-mode")
    assert argv[index + 1] == expected
    # 所有模式都把授权请求交回宿主（Flux）裁决
    assert "--permission-prompt-tool" in argv
    assert argv[argv.index("--permission-prompt-tool") + 1] == "stdio"
    assert "--allowed-tools" in argv
    assert argv[argv.index("--allowed-tools") + 1] == "mcp__flux"
    # 非交互 + 双向流式
    assert "-p" in argv
    assert argv[argv.index("--input-format") + 1] == "stream-json"
    assert argv[argv.index("--output-format") + 1] == "stream-json"
    assert "--include-partial-messages" in argv


def test_argv_plan_disables_write_tools() -> None:
    argv = claude_argv("claude", mode="plan")
    index = argv.index("--disallowed-tools")
    assert argv[index + 1 : index + 6] == (
        "Bash",
        "Edit",
        "Write",
        "MultiEdit",
        "NotebookEdit",
    )
    assert claude_argv("claude", mode="normal").count("--disallowed-tools") == 0


def test_argv_optional_flags() -> None:
    argv = claude_argv(
        "claude",
        mode="normal",
        model="claude-opus-5-5",
        resume="sess-1",
        mcp_config="/tmp/claude-mcp.json",
        uploads_dir="/tmp/uploads",
    )
    assert argv[argv.index("--model") + 1] == "claude-opus-5-5"
    assert argv[argv.index("--resume") + 1] == "sess-1"
    assert argv[argv.index("--mcp-config") + 1] == "/tmp/claude-mcp.json"
    assert argv[argv.index("--add-dir") + 1] == "/tmp/uploads"

    bare = claude_argv("claude", mode="normal")
    for flag in ("--model", "--resume", "--mcp-config", "--add-dir"):
        assert flag not in bare


def test_mcp_config_carries_bearer_and_run_id() -> None:
    payload = json.loads(claude_mcp_config("http://127.0.0.1:8000/mcp", "tok", "run-1"))
    server = payload["mcpServers"]["flux"]
    assert server["type"] == "http"
    assert server["url"] == "http://127.0.0.1:8000/mcp"
    assert server["headers"]["Authorization"] == "Bearer tok"
    assert server["headers"]["X-Flux-Run-Id"] == "run-1"
    # 令牌为 None 时只写空 server 表，不落任何凭据
    assert json.loads(claude_mcp_config("http://x/mcp", None, "run-1")) == {"mcpServers": {}}


def _runtime(settings: Settings) -> CliRuntime:
    return CliRuntime(
        runtime_id=RUNTIME_CLAUDE_CODE,
        adapter=SimpleNamespace(),
        settings=settings,
        supervisor=SimpleNamespace(),
    )


def test_runtime_writes_mcp_config_with_owner_only_permissions(
    settings: Settings, tmp_path: Path
) -> None:
    env = _runtime(settings)._write_runtime_config(tmp_path, "tok", "run-1", model="m")
    config = tmp_path / "claude-mcp.json"
    assert config.is_file()
    data = json.loads(config.read_text(encoding="utf-8"))
    headers = data["mcpServers"]["flux"]["headers"]
    assert headers["Authorization"] == "Bearer tok"
    assert headers["X-Flux-Run-Id"] == "run-1"
    if not platforms.IS_WINDOWS:
        assert oct(config.stat().st_mode & 0o777) == "0o600"
    assert env == {}


def test_runtime_channel_keeps_secret_out_of_file(settings: Settings, tmp_path: Path) -> None:
    channel = SimpleNamespace(
        provider="anthropic",
        api_key="sk-ant-secret",
        base_url="https://api.anthropic.com",
        key_id="key-1",
    )
    env = _runtime(settings)._write_runtime_config(
        tmp_path, "tok", "run-1", model="claude-opus-5-5", channel=channel
    )
    assert env["ANTHROPIC_API_KEY"] == "sk-ant-secret"
    assert env["ANTHROPIC_BASE_URL"] == "https://api.anthropic.com"
    assert env["ANTHROPIC_AUTH_TOKEN"] == ""
    assert "sk-ant-secret" not in (tmp_path / "claude-mcp.json").read_text(encoding="utf-8")


def test_runtime_channel_rejects_non_anthropic_provider(settings: Settings, tmp_path: Path) -> None:
    channel = SimpleNamespace(provider="openai", api_key="k", base_url="", key_id="key-1")
    with pytest.raises(ConfigurationError):
        _runtime(settings)._write_runtime_config(
            tmp_path, "tok", "run-1", model="gpt-5", channel=channel
        )


def test_runtime_channel_requires_task_model(settings: Settings, tmp_path: Path) -> None:
    channel = SimpleNamespace(provider="anthropic", api_key="k", base_url="", key_id="key-1")
    with pytest.raises(ConfigurationError):
        _runtime(settings)._write_runtime_config(tmp_path, "tok", "run-1", channel=channel)


# --- 事件翻译 ---


def _event_session() -> tuple[ClaudeCodeSession, dict[str, list[Any]]]:
    captured: dict[str, list[Any]] = {
        "text": [],
        "thinking": [],
        "tool": [],
        "steps": [],
        "status": [],
    }

    async def on_text(text: str) -> None:
        captured["text"].append(text)

    async def on_thinking(text: str, phase: str = "") -> None:
        captured["thinking"].append((text, phase))

    async def on_tool(fields: dict[str, Any], state: str) -> None:
        captured["tool"].append((fields, state))

    async def on_step(step: dict[str, Any]) -> None:
        captured["steps"].append(step)

    async def on_status(fields: dict[str, Any]) -> None:
        captured["status"].append(fields)

    popen = SimpleNamespace(
        stdin=io.StringIO(),
        stdout=None,
        poll=lambda: 0,
        wait=lambda timeout=None: 0,  # noqa: ARG005
    )
    session = ClaudeCodeSession(SimpleNamespace(popen=popen))
    session.bind(
        on_text=on_text,
        on_thinking=on_thinking,
        on_tool=on_tool,
        on_step=on_step,
        on_status=on_status,
    )
    return session, captured


def _push(session: ClaudeCodeSession, *messages: dict[str, Any]) -> None:
    for message in messages:
        session._queue.put_nowait(message)


async def test_translation_deltas_and_full_message_do_not_duplicate() -> None:
    session, captured = _event_session()
    _push(
        session,
        {"type": "system", "subtype": "init", "session_id": "s1", "model": "m"},
        {"type": "stream_event", "event": {"type": "message_start", "message": {"id": "m1"}}},
        {
            "type": "stream_event",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "text_delta", "text": "你好"},
            },
        },
        {
            "type": "stream_event",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "thinking_delta", "thinking": "先看看"},
            },
        },
        {
            "type": "assistant",
            "message": {
                "id": "m1",
                "content": [
                    {"type": "text", "text": "你好"},
                    {
                        "type": "tool_use",
                        "id": "call_1",
                        "name": "Bash",
                        "input": {"command": "ls"},
                    },
                ],
            },
        },
        {
            "type": "user",
            "message": {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "call_1",
                        "content": [{"type": "text", "text": "a.txt"}],
                        "is_error": False,
                    }
                ],
            },
        },
        {
            "type": "stream_event",
            "event": {
                "type": "message_delta",
                "delta": {"stop_reason": "end_turn"},
                "usage": {
                    "input_tokens": 11,
                    "output_tokens": 7,
                    "cache_read_input_tokens": 2,
                    "cache_creation_input_tokens": 3,
                },
            },
        },
        {
            "type": "result",
            "subtype": "success",
            "is_error": False,
            "result": "你好",
            "session_id": "s1",
        },
    )
    result = await asyncio.wait_for(
        session._consume(permission_mode="normal", workspace="/ws", uploads_dir="/up"),
        timeout=5,
    )

    # delta 已进答复；assistant 整条消息的 text 不再重复
    assert captured["text"] == ["你好"]
    assert result.text == "你好"
    assert result.session_id == "s1"
    assert ("先看看", "") in captured["thinking"]
    assert ("", "step_start") in captured["thinking"]

    running, completed = captured["tool"]
    assert running[0]["name"] == "Bash"
    assert running[0]["detail"] == "ls"
    assert running[0]["call_id"] == "call_1"
    assert running[1] == "running"
    assert completed[0]["call_id"] == "call_1"
    assert completed[0]["name"] == "Bash"
    assert completed[0]["output"] == "a.txt"
    assert completed[1] == "completed"

    assert captured["steps"] == [{"input": 11, "output": 7, "cache_read": 2, "cache_write": 3}]
    assert result.steps == 1


async def test_translation_subagent_text_does_not_enter_reply() -> None:
    session, captured = _event_session()
    _push(
        session,
        {
            "type": "stream_event",
            "parent_tool_use_id": "parent-1",
            "event": {
                "type": "content_block_delta",
                "delta": {"type": "text_delta", "text": "子代理的中间过程"},
            },
        },
        {"type": "result", "subtype": "success", "result": "主答复", "session_id": "s1"},
    )
    result = await asyncio.wait_for(
        session._consume(permission_mode="normal", workspace="/ws", uploads_dir="/up"),
        timeout=5,
    )
    assert captured["text"] == []
    assert result.text == "主答复"
    assert any(item["phase"] == "subagent_text" for item in captured["status"])


async def test_translation_result_usage_is_fallback_step() -> None:
    session, captured = _event_session()
    _push(
        session,
        {
            "type": "result",
            "subtype": "success",
            "result": "完成",
            "session_id": "s1",
            "usage": {"input_tokens": 20, "output_tokens": 5},
        },
    )
    result = await asyncio.wait_for(
        session._consume(permission_mode="normal", workspace="/ws", uploads_dir="/up"),
        timeout=5,
    )
    assert captured["steps"] == [
        {"input": 20, "output": 5, "cache_read": None, "cache_write": None}
    ]
    assert result.steps == 1


async def test_translation_failed_result_returns_error() -> None:
    session, captured = _event_session()
    _push(
        session,
        {
            "type": "result",
            "subtype": "error_during_execution",
            "is_error": True,
            "errors": ["No conversation found with session ID: nope"],
            "result": "启动失败",
            "session_id": "s1",
        },
    )
    result = await asyncio.wait_for(
        session._consume(permission_mode="normal", workspace="/ws", uploads_dir="/up"),
        timeout=5,
    )
    assert result.error is not None
    assert "No conversation found" in result.error
    assert "启动失败" in result.error
    # 没有 usage 的失败 result 不能凭空记一步
    assert captured["steps"] == []


def test_tool_helpers_clip_and_carry_call_id() -> None:
    fields = claude_tool_fields(
        {"id": "c1", "name": "Write", "input": {"file_path": "/ws/a.py", "content": "x" * 900}}
    )
    assert fields["call_id"] == "c1"
    assert len(fields["detail"]) <= 600
    result = claude_tool_result_fields(
        {"tool_use_id": "c1", "content": "y" * 3000, "is_error": True}, {"c1": "Write"}
    )
    assert result["name"] == "Write"
    assert result["status"] == "error"
    assert len(result["output"]) <= 2000


def test_usage_normalisation_maps_cache_creation_to_write() -> None:
    assert normalize_claude_usage(
        {
            "input_tokens": 1,
            "output_tokens": 2,
            "cache_read_input_tokens": 3,
            "cache_creation_input_tokens": 4,
        }
    ) == {"input": 1, "output": 2, "cache_read": 3, "cache_write": 4}


# --- can_use_tool 裁决 ---


async def _decide(
    tool_name: str,
    payload: dict[str, Any],
    *,
    mode: str = "normal",
    workspace: str = "/ws",
    uploads_dir: str = "/up",
    approval_handler: Any = None,
) -> dict[str, Any] | None:
    return await decide_control_request(
        {"subtype": "can_use_tool", "tool_name": tool_name, "input": payload},
        permission_mode=mode,
        workspace=workspace,
        uploads_dir=uploads_dir,
        approval_handler=approval_handler,
    )


async def test_plan_denies_every_tool() -> None:
    async def never(command: str) -> bool:  # pragma: no cover - 被调用即失败
        raise AssertionError(f"Plan 模式不应调用审批回调：{command}")

    for name in ("Bash", "Edit", "mcp__flux__context_get"):
        decision = await _decide(name, {"command": "ls"}, mode="plan", approval_handler=never)
        assert decision == {"behavior": "deny", "message": "Plan 模式只读：不允许执行原生工具"}


async def test_bash_goes_through_approval_handler() -> None:
    seen: list[str] = []

    async def allow(command: str) -> bool:
        seen.append(command)
        return True

    decision = await _decide("Bash", {"command": "ls"}, approval_handler=allow)
    assert seen == ["ls"]
    assert decision == {"behavior": "allow", "updatedInput": {"command": "ls"}}

    async def deny(command: str) -> bool:
        seen.append(command)
        return False

    decision = await _decide("Bash", {"command": "rm -rf /"}, approval_handler=deny)
    assert decision["behavior"] == "deny"

    async def boom(command: str) -> bool:
        raise RuntimeError("approval backend down")

    # 审批链路异常 → 失败关闭
    decision = await _decide("Bash", {"command": "ls"}, approval_handler=boom)
    assert decision["behavior"] == "deny"


async def test_shell_without_approval_handler_is_denied() -> None:
    """没有审批入口（拿不到 Flux 裁决能力）时必须拒绝，不能默认放行。"""
    for tool in ("Bash", "PowerShell"):
        decision = await _decide(tool, {"command": "ls"}, approval_handler=None)
        assert decision["behavior"] == "deny"
        assert decision["message"] == "审批链路不可用，已拒绝"


async def test_edit_outside_workspace_is_denied(tmp_path: Path) -> None:
    workspace = tmp_path / "ws"
    uploads = tmp_path / "uploads"
    workspace.mkdir()
    uploads.mkdir()
    inside = workspace / "a.py"
    outside = tmp_path / "other" / "a.py"

    decision = await _decide(
        "Edit", {"file_path": str(outside)}, workspace=str(workspace), uploads_dir=str(uploads)
    )
    assert decision["behavior"] == "deny"
    decision = await _decide(
        "Write", {"file_path": str(inside)}, workspace=str(workspace), uploads_dir=str(uploads)
    )
    assert decision["behavior"] == "allow"
    attached = uploads / "report.txt"
    decision = await _decide(
        "Edit", {"file_path": str(attached)}, workspace=str(workspace), uploads_dir=str(uploads)
    )
    assert decision["behavior"] == "allow"
    # NotebookEdit 用 notebook_path
    decision = await _decide(
        "NotebookEdit",
        {"notebook_path": str(outside)},
        workspace=str(workspace),
        uploads_dir=str(uploads),
    )
    assert decision["behavior"] == "deny"
    # 相对路径按 CLI 的 cwd（工作区）解析，不能借 Flux 进程 cwd 逃逸
    assert (
        await _decide(
            "Edit", {"file_path": "a.py"}, workspace=str(workspace), uploads_dir=str(uploads)
        )
    )["behavior"] == "allow"
    assert (
        await _decide(
            "Edit",
            {"file_path": "../other/a.py"},
            workspace=str(workspace),
            uploads_dir=str(uploads),
        )
    )["behavior"] == "deny"


async def test_mcp_and_unknown_tools_are_allowed() -> None:
    decision = await _decide("mcp__flux__workspace_read", {"path": "a.py"})
    assert decision == {"behavior": "allow", "updatedInput": {"path": "a.py"}}
    decision = await _decide("WebSearch", {"query": "x"})
    assert decision["behavior"] == "allow"


async def test_pending_approval_is_cancelled_when_turn_ends() -> None:
    """result / 进程退出时，仍在等审批回包的任务必须取消，不能挂到 Run 之后。"""
    session, _ = _event_session()
    started = asyncio.Event()

    async def slow_approval(command: str) -> bool:  # noqa: ARG001
        started.set()
        await asyncio.sleep(60)
        return True

    session._approval_handler = slow_approval
    _push(
        session,
        {
            "type": "control_request",
            "request_id": "req-1",
            "request": {
                "subtype": "can_use_tool",
                "tool_name": "Bash",
                "input": {"command": "ls"},
            },
        },
        {"type": "result", "subtype": "success", "result": "完成", "session_id": "s1"},
    )
    result = await asyncio.wait_for(
        session._consume(permission_mode="normal", workspace="/ws", uploads_dir="/up"),
        timeout=5,
    )
    assert result.text == "完成"
    assert started.is_set()
    assert session._approval_tasks == set()


async def test_unknown_control_subtype_is_unsupported() -> None:
    assert (
        await decide_control_request(
            {"subtype": "hook_callback"},
            permission_mode="normal",
            workspace="/ws",
            uploads_dir="/up",
            approval_handler=None,
        )
        is None
    )


# --- 端到端：假 claude 脚本 ---


_COMPLETED_SCRIPT = """#!/usr/bin/env python3
import json
import os
import sys

ARGV_LOG = os.environ.get("FAKE_CLAUDE_ARGV_LOG")
if ARGV_LOG:
    with open(ARGV_LOG, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(sys.argv, ensure_ascii=False) + "\\n")


def send(payload):
    print(json.dumps(payload, ensure_ascii=False), flush=True)


for line in sys.stdin:
    message = json.loads(line)
    kind = message.get("type")
    if kind == "control_request":
        request = message.get("request") or {}
        if request.get("subtype") == "initialize":
            send({"type": "control_response", "response": {
                "subtype": "success", "request_id": message.get("request_id")}})
    elif kind == "user":
        send({"type": "system", "subtype": "init", "session_id": "sess-e2e",
              "model": "fake-model", "tools": [], "cwd": os.getcwd()})
        send({"type": "stream_event", "parent_tool_use_id": None,
              "event": {"type": "message_start", "message": {"id": "msg_1"}}})
        for chunk in ["收到，", "开始执行。"]:
            send({"type": "stream_event", "parent_tool_use_id": None,
                  "event": {"type": "content_block_delta",
                            "delta": {"type": "text_delta", "text": chunk}}})
        send({"type": "assistant", "parent_tool_use_id": None, "message": {
            "id": "msg_1", "content": [
                {"type": "text", "text": "收到，开始执行。"},
                {"type": "tool_use", "id": "call_1", "name": "Bash",
                 "input": {"command": "ls", "description": "list files"}},
            ]}})
        send({"type": "control_request", "request_id": "req-1", "request": {
            "subtype": "can_use_tool", "tool_name": "Bash",
            "input": {"command": "ls", "description": "list files"}}})
        while True:
            response_line = sys.stdin.readline()
            if not response_line:
                sys.exit(1)
            response = json.loads(response_line)
            if response.get("type") == "control_response":
                break
        send({"type": "user", "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "call_1",
             "content": "a.txt", "is_error": False}]}})
        send({"type": "stream_event", "parent_tool_use_id": None, "event": {
            "type": "message_delta", "delta": {"stop_reason": "end_turn"},
            "usage": {"input_tokens": 11, "output_tokens": 7,
                      "cache_read_input_tokens": 2,
                      "cache_creation_input_tokens": 3}}})
        send({"type": "result", "subtype": "success", "is_error": False,
              "result": "收到，开始执行。", "session_id": "sess-e2e",
              "usage": {"input_tokens": 11, "output_tokens": 7}})
        sys.exit(0)
sys.exit(1)
"""

_EOF_MARKER_SCRIPT = """#!/usr/bin/env python3
import json
import os
import sys
import time


def send(payload):
    print(json.dumps(payload), flush=True)


for line in sys.stdin:
    message = json.loads(line)
    kind = message.get("type")
    if kind == "control_request":
        request = message.get("request") or {}
        if request.get("subtype") == "initialize":
            send({"type": "control_response", "response": {
                "subtype": "success", "request_id": message.get("request_id")}})
    elif kind == "user":
        send({"type": "result", "subtype": "success", "is_error": False,
              "result": "完成", "session_id": "sess-eof"})
        break

# result 已发出；session 处理完 result 后会关闭 stdin。这里继续读到 EOF，
# 再模拟 Claude Code 落盘会话文件，最后退出。
for _line in sys.stdin:
    pass
time.sleep(0.5)
marker = os.environ.get("FAKE_CLAUDE_EXIT_MARKER")
if marker:
    with open(marker, "w", encoding="utf-8") as fh:
        fh.write("exited")
"""

_FAILED_SCRIPT = """#!/usr/bin/env python3
import sys
sys.exit(2)
"""

_CANCEL_SCRIPT = """#!/usr/bin/env python3
import signal
import sys
import time

signal.signal(signal.SIGTERM, signal.SIG_IGN)
for _line in sys.stdin:
    pass
while True:
    time.sleep(0.1)
"""


def _argv_log(tmp_path: Path) -> tuple[Path, str | None]:
    log = tmp_path / "claude-argv.jsonl"
    previous = os.environ.get("FAKE_CLAUDE_ARGV_LOG")
    os.environ["FAKE_CLAUDE_ARGV_LOG"] = str(log)
    return log, previous


def _restore_argv_log(previous: str | None) -> None:
    if previous is None:
        os.environ.pop("FAKE_CLAUDE_ARGV_LOG", None)
    else:
        os.environ["FAKE_CLAUDE_ARGV_LOG"] = previous


def test_claude_run_completes_and_emits_unified_events(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    argv_log, previous = _argv_log(tmp_path)
    try:
        binary = _write_script(tmp_path / "fake-claude-ok", _COMPLETED_SCRIPT)
        with _cli_app(settings, tmp_path, binary, env_name="FLUX_CLAUDE_CODE_CLI_BINARY") as client:
            container = client.app.state.container
            _mark_ready(container, RUNTIME_CLAUDE_CODE)
            agent_id = _create_cli_agent(client, RUNTIME_CLAUDE_CODE)
            started = _start_task(client, agent_id)
            task_id = started["task"]["id"]
            run_id = started["task"]["run_id"]
            assert started["run"]["runtime"] == RUNTIME_CLAUDE_CODE

            settled = _wait_status(client, task_id, "completed")
            assert settled["run_id"] == run_id
            message = _wait_last_message(client, task_id, "run_finished")
            # 答复来自 delta / result；assistant 整条消息不重复
            assert message["content"] == "收到，开始执行。"

            events = [
                payload
                for event, payload in container.bus.history
                if event == "dsh.event" and payload.get("run_id") == run_id
            ]
            types = [item["event_type"] for item in events]
            assert "message" in types
            assert "tool_call" in types and "tool_result" in types
            tool_result = next(item for item in events if item["event_type"] == "tool_result")
            assert tool_result["call_id"] == "call_1"
            assert tool_result["output"] == "a.txt"

            # MCP 配置落在 run 目录、仅属主可读
            config = Path(container.settings.dsh_home) / "runs" / run_id / "claude-mcp.json"
            assert config.is_file()
            assert "Bearer " in config.read_text(encoding="utf-8")
            if not platforms.IS_WINDOWS:
                assert oct(config.stat().st_mode & 0o777) == "0o600"

            # 假 claude 收到了 stream-json + 宿主裁决参数
            argv = json.loads(argv_log.read_text(encoding="utf-8").splitlines()[0])
            assert "--permission-prompt-tool" in argv
            assert argv[argv.index("--permission-prompt-tool") + 1] == "stdio"
            assert "--mcp-config" in argv
            assert argv[argv.index("--mcp-config") + 1].endswith("claude-mcp.json")

            # 原生用量：一步、Bash 一次、token 来自 message_delta
            deadline = time.monotonic() + 2.0
            run = None
            while time.monotonic() < deadline:
                run = asyncio.run(container.run_repo.get(run_id))
                if run is not None and (run.last_event or {}).get("event_type") == "run_metrics":
                    break
                time.sleep(0.02)
            metrics = (run.last_event or {}) if run is not None else {}
            assert metrics["runtime"] == RUNTIME_CLAUDE_CODE
            assert metrics["steps"] == 1
            assert metrics["tool_calls"] == 1
            assert metrics["input_tokens"] == 11
            assert metrics["output_tokens"] == 7
            assert metrics["cache_read_tokens"] == 2
            assert metrics["cache_write_tokens"] == 3
    finally:
        _restore_argv_log(previous)


def test_claude_waits_for_natural_exit_before_stopping(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    """result 之后 CLI 还要落盘会话；进程必须自然退出，不能被立刻 SIGTERM 杀掉。"""
    marker = tmp_path / "claude-exit-marker"
    previous = os.environ.get("FAKE_CLAUDE_EXIT_MARKER")
    os.environ["FAKE_CLAUDE_EXIT_MARKER"] = str(marker)
    try:
        binary = _write_script(tmp_path / "fake-claude-eof", _EOF_MARKER_SCRIPT)
        with _cli_app(settings, tmp_path, binary, env_name="FLUX_CLAUDE_CODE_CLI_BINARY") as client:
            container = client.app.state.container
            _mark_ready(container, RUNTIME_CLAUDE_CODE)
            agent_id = _create_cli_agent(client, RUNTIME_CLAUDE_CODE)
            started = _start_task(client, agent_id)
            task_id = started["task"]["id"]
            _wait_status(client, task_id, "completed")
            # 任务 completed 时进程已经退出（_run_claude_code 等过 wait），标记文件应在
            assert marker.is_file()
            assert marker.read_text(encoding="utf-8") == "exited"
    finally:
        if previous is None:
            os.environ.pop("FAKE_CLAUDE_EXIT_MARKER", None)
        else:
            os.environ["FAKE_CLAUDE_EXIT_MARKER"] = previous


def test_claude_nonzero_exit_is_failed(settings: Settings, tmp_path: Path, db_schema: None) -> None:
    binary = _write_script(tmp_path / "fake-claude-fail", _FAILED_SCRIPT)
    with _cli_app(settings, tmp_path, binary, env_name="FLUX_CLAUDE_CODE_CLI_BINARY") as client:
        _mark_ready(client.app.state.container, RUNTIME_CLAUDE_CODE)
        agent_id = _create_cli_agent(client, RUNTIME_CLAUDE_CODE)
        started = _start_task(client, agent_id)
        task_id = started["task"]["id"]

        settled = _wait_status(client, task_id, "failed")
        assert settled["status"] == "failed"
        message = _wait_last_message(client, task_id, "run_finished")
        assert "退出码 2" in message["content"]


@pytest.mark.skipif(
    platforms.IS_WINDOWS,
    reason="依赖 POSIX 进程组信号语义（SIGTERM 可被忽略）；Windows 取消链路另由 platforms 用例覆盖",
)
def test_claude_cancel_kills_process_group(
    settings: Settings, tmp_path: Path, db_schema: None
) -> None:
    binary = _write_script(tmp_path / "fake-claude-hold", _CANCEL_SCRIPT)
    with _cli_app(settings, tmp_path, binary, env_name="FLUX_CLAUDE_CODE_CLI_BINARY") as client:
        container = client.app.state.container
        _mark_ready(container, RUNTIME_CLAUDE_CODE)
        agent_id = _create_cli_agent(client, RUNTIME_CLAUDE_CODE)
        started = _start_task(client, agent_id)
        task_id = started["task"]["id"]
        run_id = started["task"]["run_id"]

        snapshot = None
        for _ in range(300):
            snapshot = container.dsh.supervisor.monitor_snapshot(run_id)
            if snapshot and snapshot["pgid"]:
                break
            time.sleep(0.02)
        assert snapshot and snapshot["pgid"]

        cancelled = client.post(f"{PREFIX}/tasks/{task_id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["data"]["status"] == "cancelled"
        settled = _wait_status(client, task_id, "cancelled")
        assert settled["status"] == "cancelled"


def test_claude_resumes_the_previous_session(
    settings: Settings,
    tmp_path: Path,
    db_schema: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """第二轮 argv 带上一轮记下的 session_id（且会话文件必须真实存在）。"""
    claude_home = tmp_path / "claude-home"
    session_dir = claude_home / "projects" / "-tmp-project"
    session_dir.mkdir(parents=True)
    (session_dir / "sess-e2e.jsonl").write_text("{}\n", encoding="utf-8")
    monkeypatch.setenv("CLAUDE_CONFIG_DIR", str(claude_home))

    argv_log, previous = _argv_log(tmp_path)
    try:
        binary = _write_script(tmp_path / "fake-claude-resume", _COMPLETED_SCRIPT)
        with _cli_app(settings, tmp_path, binary, env_name="FLUX_CLAUDE_CODE_CLI_BINARY") as client:
            container = client.app.state.container
            _mark_ready(container, RUNTIME_CLAUDE_CODE)
            agent_id = _create_cli_agent(client, RUNTIME_CLAUDE_CODE)
            started = _start_task(client, agent_id)
            task_id = started["task"]["id"]
            _wait_status(client, task_id, "completed")

            continued = client.post(
                f"{PREFIX}/tasks/{task_id}/continue", json={"content": "再补充一句"}
            )
            assert continued.status_code == 200, continued.text
            _wait_status(client, task_id, "completed", timeout=10.0)

            invocations = [
                json.loads(line)
                for line in argv_log.read_text(encoding="utf-8").splitlines()
                if line.strip()
            ]
            assert len(invocations) == 2
            assert "--resume" not in invocations[0]
            second = invocations[1]
            assert second[second.index("--resume") + 1] == "sess-e2e"
    finally:
        _restore_argv_log(previous)
