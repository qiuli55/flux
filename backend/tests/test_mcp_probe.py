"""自定义 MCP 连接器探测：真实协议握手，但用 MockTransport 拦住网络。"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable

import httpx

from flux.core.mcp_probe import McpProbeClient


def _client(handler: Callable[[httpx.Request], httpx.Response]) -> McpProbeClient:
    return McpProbeClient(transport=httpx.MockTransport(handler))


def _rpc(request: httpx.Request) -> dict:
    return json.loads(request.content.decode("utf-8"))


def test_probe_succeeds_after_initialize_and_tools_list() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = _rpc(request)
        method = payload.get("method")
        calls.append(method)
        if method == "initialize":
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "result": {
                        "protocolVersion": "2025-06-18",
                        "serverInfo": {"name": "demo-mcp"},
                    },
                },
                headers={"mcp-session-id": "session-1"},
            )
        if method == "notifications/initialized":
            assert request.headers["mcp-session-id"] == "session-1"
            return httpx.Response(202)
        if method == "tools/list":
            assert request.headers["mcp-session-id"] == "session-1"
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": 2,
                    "result": {"tools": [{"name": "search"}, {"name": "fetch"}]},
                },
            )
        raise AssertionError(f"unexpected method {method}")

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))

    assert result.ok is True
    assert result.tools == ("search", "fetch")
    assert result.server_name == "demo-mcp"
    assert result.protocol_version == "2025-06-18"
    assert calls == ["initialize", "notifications/initialized", "tools/list"]


def test_probe_parses_sse_responses() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = _rpc(request)
        if payload.get("method") == "initialize":
            body = (
                "event: message\n"
                'data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}\n\n'
            )
            return httpx.Response(
                200, text=body, headers={"content-type": "text/event-stream"}
            )
        if payload.get("method") == "notifications/initialized":
            return httpx.Response(204)
        body = (
            "event: message\n"
            'data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping"}]}}\n\n'
        )
        return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is True
    assert result.tools == ("ping",)


def test_probe_reports_auth_required_without_pretending_connected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, text="unauthorized")

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is False
    assert "认证" in result.error
    assert result.tools == ()


def test_probe_reports_non_mcp_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>not mcp</html>")

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is False
    assert "初始化响应" in result.error


def test_probe_reports_jsonrpc_error_from_tools_list() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = _rpc(request)
        if payload.get("method") == "initialize":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-06-18"}},
            )
        if payload.get("method") == "notifications/initialized":
            return httpx.Response(202)
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": 2,
                "error": {"code": -32601, "message": "tools/list unsupported"},
            },
        )

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is False
    assert "tools/list unsupported" in result.error


def test_probe_reports_network_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("name resolution failed", request=request)

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is False
    assert "无法连接服务器" in result.error

def test_probe_skips_notifications_before_the_jsonrpc_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = _rpc(request)
        if payload.get("method") == "initialize":
            body = (
                'data: {"jsonrpc":"2.0","method":"notifications/message",'
                '"params":{"level":"info"}}\n\n'
                'data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}\n\n'
            )
            return httpx.Response(
                200, text=body, headers={"content-type": "text/event-stream"}
            )
        if payload.get("method") == "notifications/initialized":
            return httpx.Response(202)
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": "ping"}]}},
        )

    result = asyncio.run(_client(handler).probe("https://example.com/mcp"))
    assert result.ok is True
    assert result.tools == ("ping",)

