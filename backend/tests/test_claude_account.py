"""Claude Code 订阅登录：解析、状态脱敏、Run 注入与账号 API。

不启动真实 claude、不访问 Anthropic：只验证 Flux 自己的边界。
"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.claude_account import (
    ClaudeAccountService,
    claude_account_env,
    parse_authorize_url,
    parse_subscription_token,
)
from flux.core.agent_runtime.runtimes.base import RUNTIME_CLAUDE_CODE
from flux.core.agent_runtime.runtimes.cli import CliRuntime

ACCOUNT_HOME = "claude-account"
TOKEN = "sk-ant-oat01-" + "a1b2c3d4" * 8
TOKEN_FORMAT = "sk-ant-oat01-flow-token"
AUTH_URL = (
    "https://claude.com/cai/oauth/authorize?code=true&client_id=test-client"
    "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth"
    "%2Fcode%2Fcallback&scope=user%3Ainference&state=abc123"
)


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    values: dict[str, object] = {
        "env": "test",
        "log_level": "WARNING",
        "database_url": f"sqlite+aiosqlite:///{tmp_path / 'claude-account.db'}",
        "claude_account_home": str(tmp_path / ACCOUNT_HOME),
        "dsh_mcp_url": "http://127.0.0.1:8000/mcp",
        "dsh_mcp_enabled": True,
    }
    values.update(overrides)
    return Settings(**values)  # type: ignore[arg-type]


def _write_token(tmp_path: Path, token: str = TOKEN) -> Path:
    home = tmp_path / ACCOUNT_HOME
    home.mkdir(parents=True, exist_ok=True)
    path = home / "token"
    path.write_text(token + "\n", encoding="utf-8")
    return path


# --- 解析 ---


def test_parse_authorize_url_reads_osc8_hyperlink() -> None:
    output = f"\x1b]8;id=abc;{AUTH_URL}\x07{AUTH_URL}\x1b]8;;\x07\nPaste code here >"
    assert parse_authorize_url(output) == AUTH_URL


def test_parse_authorize_url_reads_plain_text() -> None:
    assert parse_authorize_url(f"visit: {AUTH_URL}") == AUTH_URL


def test_parse_authorize_url_ignores_unrelated_links() -> None:
    assert parse_authorize_url("see https://example.com/help") == ""


def test_parse_subscription_token_only_matches_official_prefix() -> None:
    assert parse_subscription_token(f"token: {TOKEN}\n") == TOKEN
    assert parse_subscription_token("sk-ant-api03-not-a-subscription-token") == ""


# --- 状态脱敏 ---


def test_status_reports_login_but_never_returns_token(tmp_path: Path) -> None:
    _write_token(tmp_path)
    service = ClaudeAccountService(_settings(tmp_path))

    status = service.status()
    assert status["logged_in"] is True
    assert status["auth_mode"] == "subscription"
    assert status["created_at"] is not None
    dumped = json.dumps(status)
    assert TOKEN not in dumped
    assert "sk-ant" not in dumped


def test_status_without_token_is_logged_out(tmp_path: Path) -> None:
    status = ClaudeAccountService(_settings(tmp_path)).status()
    assert status["logged_in"] is False
    assert status["auth_mode"] is None
    assert status["pending_login"] is None


def test_invalid_token_file_is_ignored(tmp_path: Path) -> None:
    _write_token(tmp_path, "not-a-token")
    assert ClaudeAccountService(_settings(tmp_path)).status()["logged_in"] is False


def test_logout_removes_token_and_reports_logged_out(tmp_path: Path) -> None:
    import asyncio

    _write_token(tmp_path)
    service = ClaudeAccountService(_settings(tmp_path))

    result = asyncio.run(service.logout())
    assert result["removed"] is True
    assert result["status"]["logged_in"] is False
    assert not service.token_path.exists()
    assert TOKEN not in json.dumps(result)


def test_claude_account_env_injects_only_when_logged_in(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    assert claude_account_env(settings) == {}

    _write_token(tmp_path)
    assert claude_account_env(settings) == {"CLAUDE_CODE_OAUTH_TOKEN": TOKEN}


# --- Run 注入 ---


class _FakeMcpService:
    def servers(self) -> dict[str, dict[str, str]]:
        return {"my-mcp": {"url": "https://example.com/mcp"}}


def _runtime(settings: Settings) -> CliRuntime:
    return CliRuntime(
        runtime_id=RUNTIME_CLAUDE_CODE,
        adapter=SimpleNamespace(),
        settings=settings,
        supervisor=SimpleNamespace(),
        container=SimpleNamespace(mcp_connectors=_FakeMcpService()),
    )


def test_claude_run_injects_account_token_and_custom_mcp(tmp_path: Path) -> None:
    _write_token(tmp_path)
    settings = _settings(tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir()

    env = _runtime(settings)._write_runtime_config(run_dir, "flux-token", "run-1")

    assert env["CLAUDE_CODE_OAUTH_TOKEN"] == TOKEN
    payload = json.loads((run_dir / "claude-mcp.json").read_text(encoding="utf-8"))
    assert payload["mcpServers"]["flux"]["url"] == "http://127.0.0.1:8000/mcp"
    assert payload["mcpServers"]["my-mcp"]["type"] == "http"
    assert payload["mcpServers"]["my-mcp"]["url"] == "https://example.com/mcp"


def test_claude_run_key_channel_takes_precedence_over_subscription(tmp_path: Path) -> None:
    _write_token(tmp_path)
    settings = _settings(tmp_path)
    channel = SimpleNamespace(
        provider="anthropic",
        key_id="k1",
        api_key="sk-ant-api03-key",
        base_url="https://api.anthropic.com",
        label="我的 Anthropic",
    )

    run_dir = tmp_path / "run"
    run_dir.mkdir()
    env = _runtime(settings)._write_runtime_config(
        run_dir, "flux-token", "run-1", model="claude-sonnet-4-5", channel=channel
    )

    assert env["ANTHROPIC_API_KEY"] == "sk-ant-api03-key"
    assert "CLAUDE_CODE_OAUTH_TOKEN" not in env


# --- 账号 API ---


def test_api_status_never_returns_token(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    home = Path(container.settings.claude_account_home)
    home.mkdir(parents=True, exist_ok=True)
    (home / "token").write_text(TOKEN + "\n", encoding="utf-8")

    response = client.get("/api/v1/claude-account")
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["logged_in"] is True
    assert TOKEN not in response.text


def test_api_login_start_poll_and_code_never_return_token(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def fake_start(self: ClaudeAccountService) -> dict[str, object]:
        return {
            "login_id": "login-1",
            "status": "pending",
            "url": AUTH_URL,
            "message": "",
            "needs_code": True,
            "expires_in": 900,
        }

    def fake_state(self: ClaudeAccountService, login_id: str) -> dict[str, object] | None:
        if login_id != "login-1":
            return None
        return {
            "login_id": login_id,
            "status": "pending",
            "url": AUTH_URL,
            "message": "等待验证",
            "needs_code": True,
            "expires_in": 800,
        }

    async def fake_code(self: ClaudeAccountService, login_id: str, code: str) -> dict[str, object]:
        assert code == "abc#state"
        return {
            "login_id": login_id,
            "status": "pending",
            "url": AUTH_URL,
            "message": "已提交授权码，等待 Claude 验证…",
            "needs_code": True,
            "expires_in": 700,
        }

    monkeypatch.setattr(ClaudeAccountService, "start_login", fake_start)
    monkeypatch.setattr(ClaudeAccountService, "login_state", fake_state)
    monkeypatch.setattr(ClaudeAccountService, "submit_code", fake_code)

    start = client.post("/api/v1/claude-account/login")
    assert start.status_code == 200
    assert start.json()["data"]["url"] == AUTH_URL
    assert TOKEN not in start.text

    state = client.get("/api/v1/claude-account/login/login-1")
    assert state.status_code == 200
    assert state.json()["data"]["status"] == "pending"
    assert TOKEN not in state.text

    code = client.post("/api/v1/claude-account/login/login-1/code", json={"code": "abc#state"})
    assert code.status_code == 200
    assert "已提交" in code.json()["data"]["message"]
    assert TOKEN not in code.text


def test_api_logout_removes_token(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    home = Path(container.settings.claude_account_home)
    home.mkdir(parents=True, exist_ok=True)
    token_path = home / "token"
    token_path.write_text(TOKEN + "\n", encoding="utf-8")

    response = client.post("/api/v1/claude-account/logout")
    assert response.status_code == 200
    assert response.json()["data"]["removed"] is True
    assert response.json()["data"]["status"]["logged_in"] is False
    assert not token_path.exists()
    assert TOKEN not in response.text


def test_account_home_defaults_under_flux(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # 仅验证默认路径推导；不读写真实 ~/.flux。
    if os.name == "nt":
        pytest.skip("HOME 语义在 Windows 上不同")
    monkeypatch.setenv("HOME", str(tmp_path))
    settings = Settings(env="test", claude_account_home="")
    assert str(ClaudeAccountService(settings).home) == str(tmp_path / ".flux" / "claude-account")


# --- 完整登录流程（假 CLI 走 PTY，不访问网络）---


def test_setup_token_flow_writes_token_to_isolated_home(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    if os.name == "nt":
        pytest.skip("PTY 流程只在 POSIX 上测试")

    script = tmp_path / "fake-claude"
    script.write_text(
        "#!/bin/sh\n"
        "echo 'Open this URL: https://claude.com/cai/oauth/authorize?code=true&client_id=test'\n"
        "printf 'Paste code here > '\n"
        "read code\n"
        "echo 'sk-ant-oat01-flow-token-0123456789abcdef'\n"
        "sleep 0.2\n",
        encoding="utf-8",
    )
    script.chmod(0o755)
    monkeypatch.setenv("FLUX_CLAUDE_CODE_CLI_BINARY", str(script))

    settings = _settings(tmp_path)
    service = ClaudeAccountService(settings)

    async def flow() -> dict[str, object]:
        started = await service.start_login()
        assert started["status"] == "pending"
        assert "oauth/authorize" in str(started["url"])
        await service.submit_code(str(started["login_id"]), "abc#state")
        for _ in range(100):
            state = service.login_state(str(started["login_id"]))
            if state is not None and state["status"] in ("success", "failed"):
                return state
            await asyncio.sleep(0.05)
        raise AssertionError("登录流程没有在 5 秒内结束")

    state = asyncio.run(flow())
    assert state["status"] == "success"
    assert TOKEN_FORMAT not in json.dumps(state)
    assert service.token_path.is_file()
    assert service.token_path.read_text(encoding="utf-8").strip().startswith("sk-ant-oat01-")
    assert service.status()["logged_in"] is True

    asyncio.run(service.logout())
    assert not service.token_path.exists()
