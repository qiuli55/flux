"""codex app-server 会话的确定性测试。

不启动真实 codex：直接往会话的通知队列里塞 JSON-RPC 通知，验证
「连续 willRetry + 整轮零输出」会快速失败（而不是干等 10 分钟 idle 超时），
以及正常增量仍按 text / thinking 逐段外发。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

from flux.core.agent_runtime.runtimes.codex_server import (
    CodexAppServerSession,
    _TextPump,
)


def _session(
    retry_fail_seconds: float = 0.15,
    permission_mode: str = "normal",
    approval_handler: Any = None,
) -> tuple[CodexAppServerSession, dict]:
    captured: dict[str, list[str]] = {"text": [], "thinking": []}

    async def on_text(text: str) -> None:
        captured["text"].append(text)

    async def on_thinking(text: str) -> None:
        captured["thinking"].append(text)

    async def on_tool(fields: dict, state: str) -> None:
        captured.setdefault("tool", []).append(state)

    session = CodexAppServerSession(
        SimpleNamespace(popen=SimpleNamespace(poll=lambda: None)),
        cwd="/tmp",
        on_text=on_text,
        on_thinking=on_thinking,
        on_tool=on_tool,
        retry_fail_seconds=retry_fail_seconds,
        permission_mode=permission_mode,
        approval_handler=approval_handler,
    )
    session._pump = _TextPump(on_text, on_thinking)
    return session, captured


def _push(session: CodexAppServerSession, *notifications: dict) -> None:
    for notification in notifications:
        session._notifications.put_nowait(notification)


def _retry(message: str = "workspace routing discovery failed") -> dict:
    return {
        "method": "error",
        "params": {"error": {"message": message}, "willRetry": True},
    }


async def test_retry_errors_without_output_fail_fast() -> None:
    session, captured = _session(retry_fail_seconds=0.1)
    _push(session, _retry(), _retry())
    result = await asyncio.wait_for(session._consume_turn(), timeout=5)

    assert result.status == "failed"
    assert "连续重试 2 次" in (result.error or "")
    assert "workspace routing discovery failed" in (result.error or "")
    assert captured["text"] == []


async def test_retry_errors_after_streaming_do_not_fail() -> None:
    """已经有正文增量时，偶发重试不算失败——codex 会自己续上。"""
    session, captured = _session(retry_fail_seconds=0.05)
    _push(
        session,
        {
            "method": "item/agentMessage/delta",
            "params": {"itemId": "a1", "delta": "杭州是"},
        },
        _retry(),
        {
            "method": "item/agentMessage/delta",
            "params": {"itemId": "a1", "delta": "浙江省会。"},
        },
        {
            "method": "turn/completed",
            "params": {"turn": {"id": "u1", "status": "completed"}},
        },
    )
    result = await asyncio.wait_for(session._consume_turn(), timeout=5)

    assert result.status == "completed"
    assert result.error is None
    assert "".join(captured["text"]) == "杭州是浙江省会。"


async def test_error_without_retry_returns_failure() -> None:
    session, _ = _session()
    _push(
        session,
        {"method": "error", "params": {"error": {"message": "model not found"}}},
    )
    result = await asyncio.wait_for(session._consume_turn(), timeout=5)

    assert result.status == "failed"
    assert result.error == "model not found"


def test_plan_mode_thread_is_read_only() -> None:
    """Plan 模式必须把 codex 的 sandbox 收到 read-only：原生 shell 不走 Flux 审批闸门。"""
    plan, _ = _session(permission_mode="plan")
    params = plan._thread_start_params()
    assert params["sandbox"] == "read-only"
    assert params["approvalPolicy"] == "never"

    normal, _ = _session(permission_mode="normal")
    assert normal._thread_start_params()["sandbox"] == "workspace-write"
    # 正常模式要 codex 把"它自己也不确定"的命令交上来，由 Flux 决定问不问
    assert normal._thread_start_params()["approvalPolicy"] == "untrusted"

    full, _ = _session(permission_mode="full")
    assert full._thread_start_params()["sandbox"] == "workspace-write"
    assert full._thread_start_params()["approvalPolicy"] == "untrusted"


async def test_native_command_approval_decline() -> None:
    """审批回调返回 False 时回 decline，命令不执行。"""
    seen: list[str] = []

    async def handler(command: str) -> bool:
        seen.append(command)
        return False

    session, _ = _session(approval_handler=handler)
    writes: list[dict] = []
    session._write = _capture_write(writes)
    await session._answer_server_request(
        {
            "jsonrpc": "2.0",
            "id": 7,
            "method": "item/commandExecution/requestApproval",
            "params": {"command": "rm -rf /tmp/x"},
        }
    )

    assert seen == ["rm -rf /tmp/x"]
    assert writes[0]["result"] == {"decision": "decline"}


async def test_native_command_approval_allow_legacy_array() -> None:
    """旧版 execCommandApproval 的命令是数组，回 approved。"""
    seen: list[str] = []

    async def handler(command: str) -> bool:
        seen.append(command)
        return True

    session, _ = _session(approval_handler=handler)
    writes: list[dict] = []
    session._write = _capture_write(writes)
    await session._answer_server_request(
        {
            "jsonrpc": "2.0",
            "id": 8,
            "method": "execCommandApproval",
            "params": {"command": ["git", "push", "origin", "main"]},
        }
    )

    assert seen == ["git push origin main"]
    assert writes[0]["result"] == {"decision": "approved"}


async def test_native_command_approval_plan_denies() -> None:
    """Plan 模式即使收到审批请求也一律拒绝（只读）。"""
    session, _ = _session(permission_mode="plan", approval_handler=_never_called)
    writes: list[dict] = []
    session._write = _capture_write(writes)
    await session._answer_server_request(
        {
            "jsonrpc": "2.0",
            "id": 9,
            "method": "item/commandExecution/requestApproval",
            "params": {"command": "touch probe.txt"},
        }
    )

    assert writes[0]["result"] == {"decision": "decline"}


async def _never_called(command: str) -> bool:  # pragma: no cover - 被调用即失败
    raise AssertionError(f"Plan 模式不应调用审批回调：{command}")


def _capture_write(sink: list[dict]):
    async def write(payload: dict) -> None:
        sink.append(payload)

    return write
