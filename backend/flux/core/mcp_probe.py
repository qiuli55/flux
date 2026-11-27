"""自定义 MCP 连接器的服务端探测（MCP Streamable HTTP）。

设置页点「添加连接器」时，Flux 必须先用真实协议确认服务器可用，再决定是否保存：
1. `initialize`（JSON-RPC）；
2. `notifications/initialized`；
3. `tools/list`。

只有三步都成功才返回 ok。失败时给出可读原因（HTTP 状态、JSON-RPC error、超时等），
前端原样展示，绝不把失败伪装成「已连接」。本模块只依赖 httpx，不启动 Agent。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

import httpx

from flux.logging import get_logger

logger = get_logger(__name__)

#: 探测超时（秒）：正常 MCP 握手是毫秒级，8 秒足够覆盖冷启动与代理
DEFAULT_TIMEOUT_SECONDS = 8.0
#: 当前 MCP 规范版本；服务器通常接受并从 initialize 结果回显自己的版本
PROTOCOL_VERSION = "2025-06-18"
_CLIENT_INFO = {"name": "flux", "version": "1.0"}


@dataclass(frozen=True)
class McpProbeResult:
    """一次 MCP 探测的结果。`error` 为空表示握手与 tools/list 都成功。"""

    ok: bool
    tools: tuple[str, ...] = ()
    protocol_version: str = ""
    server_name: str = ""
    error: str = ""

    def public(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "tools": list(self.tools),
            "tool_count": len(self.tools),
            "protocol_version": self.protocol_version,
            "server_name": self.server_name,
            "error": self.error,
        }


def _headers(session_id: str = "", protocol_version: str = "") -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    if protocol_version:
        headers["MCP-Protocol-Version"] = protocol_version
    return headers


def _message_of(text: str) -> dict[str, Any] | None:
    """解析一段 JSON-RPC 响应；兼容 SSE 的 `data:` 拼接结果与批量响应。"""
    raw = (text or "").strip()
    if not raw:
        return None
    try:
        data = json.loads(raw)
    except ValueError:
        return None
    if isinstance(data, dict):
        # 只把真正的 JSON-RPC 响应（result / error）当作握手结果；服务端通知跳过。
        return data if ("result" in data or "error" in data) else None
    if isinstance(data, list):
        for item in data:
            if isinstance(item, dict) and ("result" in item or "error" in item):
                return item
    return None


async def _read_rpc(resp: httpx.Response) -> dict[str, Any] | None:
    """从 JSON 或 SSE 响应里读出第一条 JSON-RPC result / error。"""
    content_type = (resp.headers.get("content-type") or "").lower()
    if "text/event-stream" in content_type:
        lines: list[str] = []
        async for line in resp.aiter_lines():
            if line.startswith(":"):
                continue
            if line.startswith("data:"):
                lines.append(line[5:].lstrip())
                continue
            if not line.strip() and lines:
                message = _message_of("\n".join(lines))
                lines = []
                if message is not None:
                    return message
        if lines:
            return _message_of("\n".join(lines))
        return None
    body = (await resp.aread()).decode("utf-8", errors="replace")
    return _message_of(body)


def _http_reason(status: int) -> str:
    if status in (401, 403):
        return (
            f"服务器要求认证（HTTP {status}）。当前自定义连接器只支持无需额外鉴权的 "
            "MCP 端点；请先在服务器侧放开访问，或改用在 URL 中自带凭据的服务。"
        )
    if status == 404:
        return "服务器没有这个 MCP 端点（HTTP 404），请检查 URL 路径是否正确。"
    if status == 405:
        return "服务器不接受 MCP Streamable HTTP 请求（HTTP 405）。"
    if status in (400, 406, 415):
        return f"MCP 握手被服务器拒绝（HTTP {status}），它可能不是 Streamable HTTP MCP 服务。"
    return f"MCP 服务器返回 HTTP {status}。"


def _rpc_error(message: dict[str, Any]) -> str:
    error = message.get("error")
    if isinstance(error, dict):
        text = str(error.get("message") or "").strip()
        code = error.get("code")
        if text:
            return f"MCP 返回错误：{text}" + (f"（code {code}）" if code is not None else "")
    return "MCP 服务器返回了无法识别的错误。"


class McpProbeClient:
    """用真实 MCP 协议探测一个 URL 是否可用。测试可注入 `transport`。"""

    def __init__(
        self,
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._timeout = timeout
        self._transport = transport

    async def probe(self, url: str) -> McpProbeResult:
        """执行 initialize → initialized → tools/list。"""
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=True,
            ) as client:
                return await self._probe(client, url)
        except httpx.TimeoutException:
            return McpProbeResult(
                ok=False, error=f"连接超时（{self._timeout:g} 秒），服务器没有响应 MCP 握手。"
            )
        except httpx.HTTPError as exc:
            detail = str(exc).strip() or exc.__class__.__name__
            return McpProbeResult(ok=False, error=f"无法连接服务器：{detail}")
        except OSError as exc:
            return McpProbeResult(ok=False, error=f"无法连接服务器：{exc}")

    async def _probe(self, client: httpx.AsyncClient, url: str) -> McpProbeResult:
        initialize = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": _CLIENT_INFO,
            },
        }
        try:
            async with client.stream(
                "POST", url, json=initialize, headers=_headers()
            ) as resp:
                status = resp.status_code
                if status >= 400:
                    return McpProbeResult(ok=False, error=_http_reason(status))
                session_id = resp.headers.get("mcp-session-id") or ""
                message = await _read_rpc(resp)
        except httpx.TimeoutException:
            raise
        except httpx.HTTPError as exc:
            detail = str(exc).strip() or exc.__class__.__name__
            return McpProbeResult(ok=False, error=f"无法连接服务器：{detail}")

        if message is None:
            return McpProbeResult(
                ok=False,
                error="服务器没有返回有效的 MCP 初始化响应，它可能不是 Streamable HTTP MCP 服务。",
            )
        if "error" in message:
            return McpProbeResult(ok=False, error=_rpc_error(message))
        result = message.get("result")
        if not isinstance(result, dict):
            return McpProbeResult(ok=False, error="MCP 初始化响应缺少 result。")
        protocol_version = str(result.get("protocolVersion") or PROTOCOL_VERSION)
        server_info = result.get("serverInfo")
        server_info = server_info if isinstance(server_info, dict) else {}
        server_name = str(server_info.get("title") or server_info.get("name") or "").strip()

        # notifications/initialized：没有 id，服务器通常回 202/204；失败不影响 tools/list 判断。
        try:
            async with client.stream(
                "POST",
                url,
                json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                headers=_headers(session_id, protocol_version),
            ):
                # 通知没有响应体；拿到状态即关闭，避免 SSE 长连接把探测拖到超时。
                pass
        except (httpx.TimeoutException, httpx.HTTPError):
            logger.debug("MCP initialized 通知未成功，继续 tools/list", exc_info=True)

        tools_payload = {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/list",
            "params": {},
        }
        try:
            async with client.stream(
                "POST",
                url,
                json=tools_payload,
                headers=_headers(session_id, protocol_version),
            ) as resp:
                status = resp.status_code
                if status >= 400:
                    return McpProbeResult(ok=False, error=_http_reason(status))
                tools_message = await _read_rpc(resp)
        except httpx.TimeoutException:
            raise
        except httpx.HTTPError as exc:
            detail = str(exc).strip() or exc.__class__.__name__
            return McpProbeResult(ok=False, error=f"tools/list 请求失败：{detail}")

        if tools_message is None:
            return McpProbeResult(
                ok=False,
                error="MCP 握手成功，但 tools/list 没有返回有效响应，Agent 无法使用这个服务器。",
            )
        if "error" in tools_message:
            return McpProbeResult(ok=False, error=_rpc_error(tools_message))
        tools_result = tools_message.get("result")
        if not isinstance(tools_result, dict):
            return McpProbeResult(
                ok=False,
                error="tools/list 响应缺少 result，Agent 无法确认可用工具。",
            )
        tools = tools_result.get("tools")
        names: list[str] = []
        if isinstance(tools, list):
            for tool in tools:
                if isinstance(tool, dict):
                    name = str(tool.get("name") or "").strip()
                    if name:
                        names.append(name)
        return McpProbeResult(
            ok=True,
            tools=tuple(names),
            protocol_version=protocol_version,
            server_name=server_name,
        )


__all__ = [
    "DEFAULT_TIMEOUT_SECONDS",
    "McpProbeClient",
    "McpProbeResult",
    "PROTOCOL_VERSION",
]
