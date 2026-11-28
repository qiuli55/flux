"""Cursor / 谷歌 Antigravity：Adapter、官方 CLI 登录、runtime 事件与连接器。

不启动真实 `agent` / `agy`，也不访问 Cursor / Google 服务器：
- 探测与登录用临时目录里的假 CLI 脚本（真实子进程语义，但不是官方二进制）；
- 事件映射用 stream-json 夹具；
- API 用 FastAPI TestClient。
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime import browser_cli_account
from flux.core.agent_runtime.adapters import build_default_adapters
from flux.core.agent_runtime.adapters.antigravity import AntigravityAdapter
from flux.core.agent_runtime.adapters.cursor import CursorAdapter
from flux.core.agent_runtime.antigravity_account import AntigravityAccountService
from flux.core.agent_runtime.browser_cli_account import (
    parse_login_url,
    parse_needs_code,
)
from flux.core.agent_runtime.cursor_account import CursorAccountService
from flux.core.agent_runtime.model_catalog import default_model_for_runtime, models_for_runtime
from flux.core.agent_runtime.runtimes.base import (
    KNOWN_RUNTIMES,
    RUNTIME_ANTIGRAVITY,
    RUNTIME_CURSOR,
    RUNTIME_NATIVE_STREAMING,
)
from flux.core.agent_runtime.runtimes.cli import (
    EVENT_ERROR,
    EVENT_FINAL,
    EVENT_MESSAGE,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    CliRuntime,
    _normalize_print_json_event,
    _print_json_events,
    build_cli_runtime,
)
from flux.core.mcp_connectors import McpConnectorService
from flux.errors import ConfigurationError

# ---------------------------------------------------------------- 夹具 / 假 CLI

_FAKE_CLI = """#!/usr/bin/env python3
import os
import sys
import time
from pathlib import Path

args = sys.argv[1:]
state = Path(os.environ["FAKE_CLI_STATE"])
mode = os.environ.get("FAKE_CLI_MODE", "ok")
url = os.environ.get("FAKE_CLI_URL", "https://cursor.com/loginDeepControl?challenge=abc")

if args and args[0] == "--version":
    print(os.environ.get("FAKE_CLI_VERSION", "cursor-agent 1.2.3"))
elif args and args[0] == "status":
    if state.read_text().strip() == "yes":
        print("Logged in as user@example.com")
    else:
        print("Not logged in")
elif args and args[0] == "login":
    if mode == "slow":
        time.sleep(30)
    if mode == "fail":
        print("login failed: cancelled")
        sys.exit(1)
    print(f"Open {url} to continue")
    if mode == "code":
        print("Paste the authorization code:")
    sys.stdout.flush()
    if mode == "code":
        sys.stdin.readline()
    time.sleep(0.15)
    state.write_text("yes")
elif args and args[0] == "logout":
    state.write_text("no")
"""


def _fake_cli(tmp_path: Path) -> Path:
    path = tmp_path / "fake-agent"
    path.write_text(_FAKE_CLI, encoding="utf-8")
    path.chmod(0o755)
    (tmp_path / "state").write_text("no", encoding="utf-8")
    return path


def _settings(tmp_path: Path) -> Settings:
    return Settings(
        env="test",
        log_level="WARNING",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'flux.db'}",
        cursor_account_home=str(tmp_path / "cursor-account"),
        antigravity_account_home=str(tmp_path / "antigravity-account"),
        mcp_connectors_file=str(tmp_path / "mcp-connectors.json"),
    )


@pytest.fixture()
def fake_cli(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    path = _fake_cli(tmp_path)
    monkeypatch.setenv("FAKE_CLI_STATE", str(tmp_path / "state"))
    monkeypatch.setenv("FLUX_CURSOR_CLI_BINARY", str(path))
    monkeypatch.setenv("FLUX_ANTIGRAVITY_CLI_BINARY", str(path))
    return path


# ---------------------------------------------------------------- Adapter


def _probe_table(table: dict[tuple[str, ...], tuple[int, str]]):
    def _run(argv, *, timeout: float = 10.0):  # noqa: ANN001, ARG001
        key = tuple(argv[1:])
        if key in table:
            return table[key]
        raise AssertionError(f"未预设的探测命令：{argv}")

    return _run


def test_cursor_adapter_discovers_installed_and_logged_in() -> None:
    adapter = CursorAdapter(
        which=lambda _: "/usr/local/bin/agent",
        probe_runner=_probe_table(
            {
                ("--version",): (0, "cursor-agent 1.2.3"),
                ("status",): (0, "Logged in as user@example.com"),
            }
        ),
    )
    probe = adapter.discover()
    assert probe.status.value == "CONNECTED"
    assert probe.version == "1.2.3"
    assert probe.auth_status == "ok"
    assert probe.capabilities == ("mcp", "stream")


def test_antigravity_adapter_reports_missing_auth_without_pretending() -> None:
    adapter = AntigravityAdapter(
        which=lambda _: "/usr/local/bin/agy",
        probe_runner=_probe_table(
            {
                ("--version",): (0, "agy 0.9.0"),
                ("status",): (0, "Not logged in"),
            }
        ),
    )
    probe = adapter.discover()
    assert probe.status.value == "VERIFIED"
    assert probe.auth_status == "missing"
    assert probe.version == "0.9.0"


def test_antigravity_adapter_not_installed() -> None:
    adapter = AntigravityAdapter(which=lambda _: None)
    probe = adapter.discover()
    assert probe.status.value == "NOT_INSTALLED"
    assert probe.path is None


# ---------------------------------------------------------------- URL / 脱敏


def test_parse_login_url_only_accepts_official_domains() -> None:
    text = (
        "Open https://evil.example/login and then https://cursor.com/loginDeepControl?challenge=abc"
    )
    assert (
        parse_login_url(text, ("cursor.com", "cursor.sh"))
        == "https://cursor.com/loginDeepControl?challenge=abc"
    )
    assert parse_login_url("https://evil.example/login", ("cursor.com",)) == ""


def test_parse_needs_code_does_not_treat_url_code_as_prompt() -> None:
    assert parse_needs_code("Visit https://cursor.com/login?code=abc") is False
    assert parse_needs_code("Paste the authorization code from the browser") is True


def test_account_status_never_exposes_secrets(
    tmp_path: Path, fake_cli: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    service = CursorAccountService(_settings(tmp_path))
    payload = service.status()
    rendered = json.dumps(payload)
    for secret_key in ("access_token", "refresh_token", "cookie", "api_key", "CURSOR_API_KEY"):
        assert secret_key not in rendered
    assert payload["logged_in"] is False
    assert payload["binary_found"] is True


# ---------------------------------------------------------------- 登录流程


def _run(coro):
    return asyncio.run(coro)


@pytest.mark.parametrize(
    ("service_cls", "url", "domains"),
    [
        (
            CursorAccountService,
            "https://cursor.com/loginDeepControl?challenge=abc",
            ("cursor.com",),
        ),
        (
            AntigravityAccountService,
            "https://accounts.google.com/o/oauth2/v2/auth?x=1",
            ("accounts.google.com",),
        ),
    ],
)
def test_start_login_returns_official_url_and_polls_to_success(
    tmp_path: Path,
    fake_cli: Path,
    monkeypatch: pytest.MonkeyPatch,
    service_cls,
    url: str,
    domains: tuple[str, ...],
) -> None:
    monkeypatch.setenv("FAKE_CLI_URL", url)
    service = service_cls(_settings(tmp_path))

    async def scenario():
        session = await service.start_login()
        assert session["status"] in ("starting", "pending")
        assert session["url"].startswith("https://")
        assert any(domain in session["url"] for domain in domains)
        for _ in range(30):
            state = service.login_state(session["login_id"])
            if state["status"] not in ("starting", "pending"):
                return state
            await asyncio.sleep(0.05)
        raise AssertionError("登录没有收敛到终态")

    state = _run(scenario())
    assert state["status"] == "success"
    assert service.status()["logged_in"] is True
    assert service.marker_path.is_file()
    # 标记文件只说明「曾在 Flux 完成官方登录」，不含任何令牌。
    marker = json.loads(service.marker_path.read_text(encoding="utf-8"))
    assert set(marker) == {"runtime", "at"}


def test_login_failure_is_readable_and_not_marked_connected(
    tmp_path: Path, fake_cli: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_CLI_MODE", "fail")
    service = CursorAccountService(_settings(tmp_path))

    async def scenario():
        session = await service.start_login()
        for _ in range(30):
            state = service.login_state(session["login_id"])
            if state["status"] not in ("starting", "pending"):
                return state
            await asyncio.sleep(0.05)
        raise AssertionError("登录没有收敛到终态")

    state = _run(scenario())
    assert state["status"] == "failed"
    assert "取消" in state["message"] or "failed" in state["message"].lower()
    assert service.status()["logged_in"] is False


def test_login_without_binary_explains_install_prerequisite(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FLUX_CURSOR_CLI_BINARY", str(tmp_path / "missing-agent"))
    service = CursorAccountService(_settings(tmp_path))
    with pytest.raises(ConfigurationError) as excinfo:
        _run(service.start_login())
    assert "安装" in str(excinfo.value)
    assert "Cursor" in str(excinfo.value)


def test_login_timeout_marks_failed(
    tmp_path: Path, fake_cli: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_CLI_MODE", "slow")
    monkeypatch.setattr(browser_cli_account, "_PROMPT_TIMEOUT_SECONDS", 0.1)
    monkeypatch.setattr(browser_cli_account, "_LOGIN_MAX_SECONDS", 0.3)
    service = CursorAccountService(_settings(tmp_path))

    async def scenario():
        session = await service.start_login()
        assert session["status"] in ("starting", "pending")
        await asyncio.sleep(0.6)
        return service.login_state(session["login_id"])

    state = _run(scenario())
    assert state is not None
    assert state["status"] == "failed"
    assert "超时" in state["message"]


def test_submit_code_only_when_cli_asks(
    tmp_path: Path, fake_cli: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_CLI_MODE", "code")
    monkeypatch.setenv("FAKE_CLI_URL", "https://cursor.com/login?x=1")
    service = CursorAccountService(_settings(tmp_path))

    async def scenario():
        session = await service.start_login()
        assert session["needs_code"] is True
        updated = await service.submit_code(session["login_id"], "abcd-1234")
        assert updated["needs_code"] is True
        for _ in range(30):
            state = service.login_state(session["login_id"])
            if state["status"] not in ("starting", "pending"):
                return state
            await asyncio.sleep(0.05)
        raise AssertionError("登录没有收敛到终态")

    state = _run(scenario())
    assert state["status"] == "success"


def test_logout_calls_official_cli_and_removes_marker(
    tmp_path: Path, fake_cli: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    service = CursorAccountService(_settings(tmp_path))

    async def login_then_logout():
        session = await service.start_login()
        for _ in range(30):
            if service.login_state(session["login_id"])["status"] == "success":
                break
            await asyncio.sleep(0.05)
        result = await service.logout()
        return result

    result = _run(login_then_logout())
    assert result["removed"] is True
    assert result["status"]["logged_in"] is False
    assert not service.marker_path.exists()


def test_stale_marker_is_surfaced_as_relogin_not_logged_in(tmp_path: Path, fake_cli: Path) -> None:
    service = CursorAccountService(_settings(tmp_path))
    service.home.mkdir(parents=True, exist_ok=True)
    service.marker_path.write_text('{"runtime": "cursor"}\n', encoding="utf-8")

    def _run_probe(argv, *, timeout: float = 10.0):  # noqa: ANN001, ARG001
        return 0, "Not logged in"

    service._probe = _run_probe  # type: ignore[assignment]
    payload = service.status()
    assert payload["logged_in"] is False
    assert payload["stale_login"] is True


# ---------------------------------------------------------------- 事件映射


def test_cursor_stream_json_maps_text_tool_and_result() -> None:
    assistant = {
        "type": "assistant",
        "message": {
            "role": "assistant",
            "content": [
                {"type": "text", "text": "我先看下项目结构。"},
                {"type": "tool_use", "id": "c1", "name": "read", "input": {"path": "a.py"}},
            ],
        },
    }
    events = _print_json_events(assistant, "r1", RUNTIME_CURSOR)
    assert [event.type for event in events] == [EVENT_MESSAGE, EVENT_TOOL_CALL]
    assert events[0].data["text"].startswith("我先看下")
    assert events[1].data["name"] == "read"
    assert events[1].data["call_id"] == "c1"

    tool = {
        "type": "tool_call",
        "subtype": "completed",
        "call_id": "c1",
        "tool_call": {"readToolCall": {"args": {"path": "a.py"}, "result": "print(1)"}},
    }
    event = _normalize_print_json_event(tool, "r1", RUNTIME_CURSOR)
    assert event.type == EVENT_TOOL_RESULT
    assert event.data["status"] == "completed"
    assert "print(1)" in event.data["output"]


def test_antigravity_stream_json_maps_message_tools_and_usage() -> None:
    message = {"type": "message", "role": "assistant", "content": "改好了。"}
    event = _normalize_print_json_event(message, "r1", RUNTIME_ANTIGRAVITY)
    assert event.type == EVENT_MESSAGE
    assert event.data["text"] == "改好了。"

    tool = {
        "type": "tool_use",
        "tool_name": "shell",
        "tool_id": "t1",
        "parameters": {"command": "pytest -q"},
    }
    event = _normalize_print_json_event(tool, "r1", RUNTIME_ANTIGRAVITY)
    assert event.type == EVENT_TOOL_CALL
    assert event.data["name"] == "shell"
    assert "pytest -q" in event.data["detail"]

    result = {
        "type": "tool_result",
        "tool_id": "t1",
        "status": "success",
        "output": "10 passed",
    }
    event = _normalize_print_json_event(result, "r1", RUNTIME_ANTIGRAVITY)
    assert event.type == EVENT_TOOL_RESULT
    assert event.data["call_id"] == "t1"
    assert event.data["output"] == "10 passed"

    final = {
        "type": "result",
        "status": "success",
        "stats": {"input_tokens": 12, "output_tokens": 34},
    }
    event = _normalize_print_json_event(final, "r1", RUNTIME_ANTIGRAVITY)
    assert event.type == EVENT_FINAL
    assert event.data["usage"]["input"] == 12
    assert event.data["usage"]["output"] == 34


def test_result_text_is_used_when_cli_never_streamed() -> None:
    parsed = {"type": "result", "subtype": "success", "result": "最终答复"}
    event = _normalize_print_json_event(parsed, "r1", RUNTIME_CURSOR, streamed=False)
    assert event.type == EVENT_MESSAGE
    assert event.data["text"] == "最终答复"
    # 已经流过增量时不再重复整段结果
    event = _normalize_print_json_event(parsed, "r1", RUNTIME_CURSOR, streamed=True)
    assert event.type == EVENT_FINAL


def test_cursor_cumulative_text_is_deduped_to_deltas(tmp_path: Path) -> None:
    runtime = _cli_runtime(tmp_path, RUNTIME_CURSOR)
    runtime._last_text["r1"] = ""
    emitted: list[str] = []
    for text in ("你", "你好", "你好，世界"):
        events = runtime._dedupe_cumulative_text(
            _print_json_events(
                {"type": "assistant", "message": {"content": [{"type": "text", "text": text}]}},
                "r1",
                RUNTIME_CURSOR,
            ),
            "r1",
        )
        emitted.extend(str(event.data.get("text") or "") for event in events)
    assert "".join(emitted) == "你好，世界"


def test_cursor_error_event_is_not_swallowed() -> None:
    event = _normalize_print_json_event(
        {"type": "error", "message": "not logged in"}, "r1", RUNTIME_CURSOR
    )
    assert event.type == EVENT_ERROR
    assert "not logged in" in event.data["message"]


# ---------------------------------------------------------------- Runtime 登记 / argv


def _cli_runtime(tmp_path: Path, runtime_id: str) -> CliRuntime:
    adapter = SimpleNamespace(
        name=runtime_id,
        _path=f"/usr/local/bin/{runtime_id}",
        build_run_argv=lambda instruction, mode="normal": (
            f"/usr/local/bin/{runtime_id}",
            instruction,
        ),
    )
    return CliRuntime(
        runtime_id=runtime_id,
        adapter=adapter,
        settings=_settings(tmp_path),
        supervisor=SimpleNamespace(),
    )


@pytest.mark.parametrize("runtime_id", [RUNTIME_CURSOR, RUNTIME_ANTIGRAVITY])
def test_build_cli_runtime_resolves_new_adapters(tmp_path: Path, runtime_id: str) -> None:
    runtime = build_cli_runtime(
        runtime_id=runtime_id,
        adapters=build_default_adapters(),
        settings=_settings(tmp_path),
        supervisor=SimpleNamespace(),
    )
    assert runtime.runtime_id == runtime_id


@pytest.mark.parametrize("runtime_id", [RUNTIME_CURSOR, RUNTIME_ANTIGRAVITY])
def test_new_runtimes_are_registered_and_not_claiming_unverified_streaming(
    runtime_id: str,
) -> None:
    assert runtime_id in KNOWN_RUNTIMES
    assert runtime_id in RUNTIME_NATIVE_STREAMING
    if runtime_id == RUNTIME_ANTIGRAVITY:
        # 官方未承诺 token 级增量：如实标记，不冒充逐 token。
        assert RUNTIME_NATIVE_STREAMING[runtime_id] is False


def test_cursor_argv_has_print_stream_json_model_and_workspace(tmp_path: Path) -> None:
    runtime = _cli_runtime(tmp_path, RUNTIME_CURSOR)
    argv = runtime._build_argv("修一下", "normal", model="cursor-fast", workspace="/work/project")
    assert argv[0] == "/usr/local/bin/cursor"
    assert "--print" in argv
    assert argv[argv.index("--output-format") + 1] == "stream-json"
    assert "--stream-partial-output" in argv
    assert argv[argv.index("--model") + 1] == "cursor-fast"
    assert argv[argv.index("--workspace") + 1] == "/work/project"
    assert argv[-1] == "修一下"


def test_antigravity_argv_has_print_stream_json(tmp_path: Path) -> None:
    runtime = _cli_runtime(tmp_path, RUNTIME_ANTIGRAVITY)
    argv = runtime._build_argv("修一下", "normal", workspace="/work/project")
    assert "--print" in argv
    assert argv[argv.index("--output-format") + 1] == "stream-json"
    # 官方公开参数里没有 --workspace：只靠子进程 cwd，不臆造参数。
    assert "--workspace" not in argv
    assert argv[-1] == "修一下"


def test_new_runtimes_do_not_accept_vault_key_channel(tmp_path: Path) -> None:
    runtime = _cli_runtime(tmp_path, RUNTIME_CURSOR)
    channel = SimpleNamespace(provider="openai", api_key="sk-test", key_id="k1")
    with pytest.raises(ConfigurationError):
        runtime._write_runtime_config(tmp_path / "run", "token", "run-1", channel=channel)


def test_model_catalog_only_uses_configured_ids() -> None:
    settings = Settings(cursor_model="cursor-fast", antigravity_model="")
    assert models_for_runtime(settings, RUNTIME_CURSOR) == ["cursor-fast"]
    assert default_model_for_runtime(settings, RUNTIME_CURSOR) == "cursor-fast"
    assert models_for_runtime(settings, RUNTIME_ANTIGRAVITY) == []
    assert default_model_for_runtime(settings, RUNTIME_ANTIGRAVITY) == ""


# ---------------------------------------------------------------- 连接器


def test_connector_list_contains_cursor_and_antigravity_with_honest_status(
    tmp_path: Path,
) -> None:
    service = McpConnectorService(_settings(tmp_path))
    items = service.list_all(
        {"logged_in": False},
        cursor_status={"logged_in": True, "stale_login": False},
        antigravity_status={"logged_in": False, "stale_login": True},
    )["items"]
    by_id = {item["id"]: item for item in items}
    assert by_id["cursor"]["status"] == "connected"
    assert by_id["cursor"]["available"] is True
    assert by_id["antigravity"]["status"] == "needs_reauth"
    assert "重新连接" in by_id["antigravity"]["error"]
    assert by_id["antigravity"]["available"] is True


def test_connector_api_lists_cursor_and_antigravity(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    container.cursor_account = SimpleNamespace(
        status=lambda: {"logged_in": True, "stale_login": False}
    )
    container.antigravity_account = SimpleNamespace(
        status=lambda: {"logged_in": False, "stale_login": False}
    )
    response = client.get("/api/v1/mcp-connectors")
    assert response.status_code == 200
    items = {item["id"]: item for item in response.json()["data"]["items"]}
    assert items["cursor"]["status"] == "connected"
    assert items["cursor"]["name"] == "Cursor"
    assert items["antigravity"]["status"] == "disconnected"
    assert items["antigravity"]["name"] == "谷歌 / Antigravity"


# ---------------------------------------------------------------- 账号 API


def test_cursor_account_api_login_flow_and_no_secrets(client: TestClient, fake_cli: Path) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    # 激活回调会扫描全部本机 CLI；API 用例只验证登录契约，用记录器替换。
    activated: list[str] = []

    async def activate(runtime_id: str) -> None:
        activated.append(runtime_id)

    container.cursor_account.set_activation(activate)

    started = client.post("/api/v1/cursor-account/login")
    assert started.status_code == 200
    session = started.json()["data"]
    assert session["url"].startswith("https://cursor.com/")
    assert "access_token" not in json.dumps(session)

    import time

    state = None
    for _ in range(50):
        state = client.get(f"/api/v1/cursor-account/login/{session['login_id']}")
        assert state.status_code == 200
        if state.json()["data"]["status"] not in ("starting", "pending"):
            break
        time.sleep(0.05)
    assert state is not None
    assert state.json()["data"]["status"] == "success"

    status = client.get("/api/v1/cursor-account").json()["data"]
    assert status["logged_in"] is True
    assert status["auth_mode"] == "official-cli"
    assert "token" not in json.dumps(status).lower()

    assert client.get("/api/v1/cursor-account/login/not-exist").status_code == 404
    out = client.post("/api/v1/cursor-account/logout")
    assert out.status_code == 200
    assert out.json()["data"]["status"]["logged_in"] is False


def test_antigravity_account_api_status_shape(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    container.antigravity_account = SimpleNamespace(
        status=lambda: {
            "logged_in": False,
            "auth_mode": None,
            "binary_found": False,
            "official_status": "missing",
            "stale_login": False,
            "pending_login": None,
        }
    )
    payload = client.get("/api/v1/antigravity-account").json()["data"]
    assert payload["logged_in"] is False
    assert payload["binary_found"] is False
    assert "token" not in json.dumps(payload).lower()
