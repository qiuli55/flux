"""连接器设置页：自定义 MCP 探测、持久化、运行时注入，以及与旧 action API 隔离。"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.runtimes.base import (
    RUNTIME_CLAUDE_CODE,
    RUNTIME_CODEX,
)
from flux.core.agent_runtime.runtimes.cli import CliRuntime, _opencode_config
from flux.core.mcp_connectors import (
    McpConnectorService,
    validate_connector_name,
    validate_connector_url,
)
from flux.core.mcp_probe import McpProbeResult
from flux.errors import ConflictError, ValidationError


class _FakeProber:
    def __init__(self, *, ok: bool = True, error: str = "connection refused") -> None:
        self.ok = ok
        self.error = error
        self.calls: list[str] = []

    async def probe(self, url: str) -> McpProbeResult:
        self.calls.append(url)
        if self.ok:
            return McpProbeResult(
                ok=True,
                tools=("search", "fetch"),
                protocol_version="2025-06-18",
                server_name="fake",
            )
        return McpProbeResult(ok=False, error=self.error)


def _settings(tmp_path: Path) -> Settings:
    return Settings(
        env="test",
        log_level="WARNING",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'mcp.db'}",
        mcp_connectors_file=str(tmp_path / "mcp-connectors.json"),
        claude_account_home=str(tmp_path / "claude-account"),
        codex_account_home=str(tmp_path / "codex-account"),
        dsh_mcp_url="http://127.0.0.1:8000/mcp",
    )


# --- 校验 ---


def test_name_and_url_validation() -> None:
    assert validate_connector_name("  我的 MCP  ") == "我的 MCP"
    assert validate_connector_url("https://example.com/mcp") == "https://example.com/mcp"
    with pytest.raises(ValidationError):
        validate_connector_name("   ")
    with pytest.raises(ValidationError):
        validate_connector_name("flux")
    with pytest.raises(ValidationError):
        validate_connector_url("ftp://example.com/mcp")
    with pytest.raises(ValidationError):
        validate_connector_url("https:///mcp")


# --- 服务：成功才保存 ---


def test_add_saves_only_after_successful_probe(tmp_path: Path) -> None:
    prober = _FakeProber(ok=True)
    service = McpConnectorService(_settings(tmp_path), prober=prober)  # type: ignore[arg-type]

    item = asyncio.run(service.add("  我的 MCP  ", "https://example.com/mcp"))

    assert item["status"] == "connected"
    assert item["name"] == "我的 MCP"
    assert item["tools"] == ["search", "fetch"]
    assert service.path.is_file()
    assert prober.calls == ["https://example.com/mcp"]


def test_add_failure_does_not_persist_or_mark_connected(tmp_path: Path) -> None:
    prober = _FakeProber(ok=False, error="连接超时（8 秒）")
    service = McpConnectorService(_settings(tmp_path), prober=prober)  # type: ignore[arg-type]

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(service.add("坏掉的 MCP", "https://example.com/mcp"))

    assert "连接超时" in str(excinfo.value)
    assert not service.path.exists()
    assert service.list_custom() == []


def test_duplicate_name_is_rejected(tmp_path: Path) -> None:
    service = McpConnectorService(
        _settings(tmp_path), prober=_FakeProber(ok=True)  # type: ignore[arg-type]
    )
    asyncio.run(service.add("demo", "https://example.com/mcp"))
    with pytest.raises(ConflictError):
        asyncio.run(service.add("DEMO", "https://example.com/other"))


def test_probe_failure_marks_error_and_removes_from_injection(tmp_path: Path) -> None:
    prober = _FakeProber(ok=True)
    service = McpConnectorService(_settings(tmp_path), prober=prober)  # type: ignore[arg-type]
    item = asyncio.run(service.add("demo", "https://example.com/mcp"))
    assert service.servers() == {"demo": {"url": "https://example.com/mcp"}}

    prober.ok = False
    prober.error = "服务器要求认证（HTTP 401）"
    updated = asyncio.run(service.probe(item["id"]))
    assert updated["status"] == "error"
    assert "认证" in updated["error"]
    assert service.servers() == {}

    prober.ok = True
    recovered = asyncio.run(service.probe(item["id"]))
    assert recovered["status"] == "connected"
    assert service.servers() == {"demo": {"url": "https://example.com/mcp"}}


def test_remove_custom_connector(tmp_path: Path) -> None:
    service = McpConnectorService(
        _settings(tmp_path), prober=_FakeProber(ok=True)  # type: ignore[arg-type]
    )
    item = asyncio.run(service.add("demo", "https://example.com/mcp"))
    assert service.remove(item["id"])["removed"] is True
    assert service.list_custom() == []


def test_list_all_includes_github_and_coming_soon(tmp_path: Path) -> None:
    service = McpConnectorService(_settings(tmp_path), prober=_FakeProber())  # type: ignore[arg-type]
    items = service.list_all(
        {"logged_in": True, "login": "qiuli55", "scopes": "repo, read:org"}
    )["items"]
    github = next(item for item in items if item["id"] == "github")
    assert github["status"] == "connected"
    assert github["account"] == "qiuli55"
    figma = next(item for item in items if item["id"] == "figma")
    assert figma["status"] == "coming_soon"
    assert figma["available"] is False


def test_github_missing_repo_scope_needs_reconnect(tmp_path: Path) -> None:
    service = McpConnectorService(_settings(tmp_path), prober=_FakeProber())  # type: ignore[arg-type]
    items = service.list_all(
        {"logged_in": True, "login": "qiuli55", "scopes": "read:org"}
    )["items"]
    github = next(item for item in items if item["id"] == "github")
    assert github["status"] == "needs_reauth"
    assert "repo" in github["error"]


# --- API ---


def test_api_add_probe_and_delete(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    container.mcp_connectors._prober = _FakeProber(ok=True)  # type: ignore[assignment]

    listing = client.get("/api/v1/mcp-connectors")
    assert listing.status_code == 200
    names = [item["id"] for item in listing.json()["data"]["items"]]
    assert "github" in names
    assert "figma" in names

    created = client.post(
        "/api/v1/mcp-connectors",
        json={"name": "我的 MCP", "url": "https://example.com/mcp"},
    )
    assert created.status_code == 200
    item = created.json()["data"]
    assert item["status"] == "connected"
    assert item["tools"] == ["search", "fetch"]

    listing = client.get("/api/v1/mcp-connectors").json()["data"]["items"]
    assert any(entry["id"] == item["id"] for entry in listing)

    deleted = client.delete(f"/api/v1/mcp-connectors/{item['id']}")
    assert deleted.status_code == 200
    assert deleted.json()["data"]["removed"] is True


def test_api_probe_failure_returns_reason_and_does_not_save(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    container.mcp_connectors._prober = _FakeProber(ok=False, error="拒绝连接")  # type: ignore[assignment]

    response = client.post(
        "/api/v1/mcp-connectors",
        json={"name": "坏连接", "url": "https://example.com/mcp"},
    )
    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert "拒绝连接" in body["message"]
    assert not Path(container.settings.mcp_connectors_path).exists()


def test_api_reprobe_returns_error_status_without_500(client: TestClient) -> None:
    container: Container = client.app.state.container  # type: ignore[attr-defined]
    prober = _FakeProber(ok=True)
    container.mcp_connectors._prober = prober  # type: ignore[assignment]
    item = client.post(
        "/api/v1/mcp-connectors",
        json={"name": "demo", "url": "https://example.com/mcp"},
    ).json()["data"]

    prober.ok = False
    prober.error = "tools/list 不支持"
    response = client.post(f"/api/v1/mcp-connectors/{item['id']}/probe")
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["status"] == "error"
    assert "tools/list" in data["error"]


def test_old_connector_action_api_is_untouched(client: TestClient) -> None:
    schema = client.get("/openapi.json").json()
    assert "/api/v1/connectors/execute" in schema["paths"]
    # 设置页 API 是独立的路径，不复用旧 action 接口
    assert "/api/v1/mcp-connectors" in schema["paths"]


# --- 运行时注入 ---


class _FakeMcpService:
    def servers(self) -> dict[str, dict[str, str]]:
        return {"my-mcp": {"url": "https://example.com/mcp"}, "flux": {"url": "bad"}}


def _runtime(settings: Settings, runtime_id: str) -> CliRuntime:
    return CliRuntime(
        runtime_id=runtime_id,
        adapter=SimpleNamespace(),
        settings=settings,
        supervisor=SimpleNamespace(),
        container=SimpleNamespace(mcp_connectors=_FakeMcpService()),
    )


def test_codex_isolated_config_gets_custom_mcp(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    user_home = tmp_path / "codex-user"
    user_home.mkdir()
    (user_home / "config.toml").write_text(
        '[mcp_servers.user-owned]\nurl = "https://user.example/mcp"\n', encoding="utf-8"
    )
    monkeypatch.setenv("FLUX_CODEX_USER_HOME", str(user_home))
    settings = _settings(tmp_path)
    run_dir = tmp_path / "run"

    _runtime(settings, RUNTIME_CODEX)._write_runtime_config(run_dir, "flux-token", "run-1")

    config = (run_dir / "codex-home" / "config.toml").read_text(encoding="utf-8")
    assert "[mcp_servers.user-owned]" in config
    assert "[mcp_servers.my-mcp]" in config
    assert 'url = "https://example.com/mcp"' in config
    # 保留名 flux 永远由 Flux 自己生成，自定义同名条目不能覆盖
    assert "[mcp_servers.flux]" in config
    assert "[mcp_servers.\"flux\"]" not in config


def test_opencode_config_gets_custom_mcp_and_keeps_flux(tmp_path: Path) -> None:
    text = _opencode_config(
        "http://127.0.0.1:8000/mcp",
        "flux-token",
        "run-1",
        extra_mcp={"my-mcp": {"url": "https://example.com/mcp"}, "flux": {"url": "bad"}},
    )
    payload = json.loads(text)
    assert payload["mcp"]["flux"]["url"] == "http://127.0.0.1:8000/mcp"
    assert payload["mcp"]["my-mcp"]["url"] == "https://example.com/mcp"
    assert payload["mcp"]["my-mcp"]["type"] == "remote"


def test_claude_config_gets_custom_mcp_without_flux_token(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    _runtime(settings, RUNTIME_CLAUDE_CODE)._write_runtime_config(run_dir, None, "run-1")
    payload = json.loads((run_dir / "claude-mcp.json").read_text(encoding="utf-8"))
    assert "flux" not in payload["mcpServers"]
    assert payload["mcpServers"]["my-mcp"] == {
        "type": "http",
        "url": "https://example.com/mcp",
    }
