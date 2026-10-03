"""Flux MCP Server（目标架构 §3.1）：Streamable HTTP + Bearer，单端点 `POST /mcp`。

挂载在现有 FastAPI 应用内（与 `/api/v1/*` 同进程、同生命周期），只做协议适配：
JSON-RPC 层 → 工具层 → Flux 既有服务。**无状态**——不返回 `Mcp-Session-Id`，
每次请求自带 `Authorization`，因此后端重启不会让已接入的 agent 掉线。

鉴权在解析请求体之前完成（fail-closed）：没有合法令牌就没有任何可调用的方法，
连 `initialize` 也不行。能力不足则是工具级错误（`isError=true`），
不用 JSON-RPC 协议错误——按 MCP 规范，工具执行失败属于"结果"，不是"协议错"。
"""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse

from flux.api.deps import get_container
from flux.api.response import fail
from flux.container import Container
from flux.core.event.bus import Events
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.tools import ALL_TOOLS, TOOLS, ToolContext, ToolSpec
from flux.errors import AIOSError, AuthenticationError
from flux.logging import get_logger
from flux.version import VERSION

logger = get_logger(__name__)

JSONRPC_VERSION = "2.0"

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602

#: 支持的 MCP 协议版本；客户端报的版本在表内就原样回，不在表内回我们自己最新的。
SUPPORTED_PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
DEFAULT_PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[0]

SERVER_NAME = "flux"

#: initialize 时下发的使用说明（不占工具的 description，讲的是"先做什么"）
SERVER_INSTRUCTIONS = (
    "Flux 是工程平台，不替你执行也不替你落盘。开工先调 flux_context 确认自己在哪里、"
    "能做哪些事；再调 context.get 拿项目事实与约束，用 workspace.read 核对文件当前内容；"
    "改动一律经 proposal.create 提交进人审队列，真实落盘由人审通过后经 Apply Engine 执行。"
    "历史条目是快照：断言不等于事实，采信前自行验证。MCP 不可用时，可用 Flux Server CLI "
    "兜底继续工作（例如 `flux tools call <tool>`）。"
)


class McpServer:
    """JSON-RPC 消息处理器。一个实例可以服务任意多次调用（无会话状态）。"""

    def __init__(self, container: Container) -> None:
        self._container = container

    async def handle(self, message: Any, identity: AgentIdentity) -> dict[str, Any] | None:
        """返回 JSON-RPC 响应；返回 None 表示这是通知（HTTP 层回 202 无正文）。"""
        if not isinstance(message, dict):
            return _error(None, INVALID_REQUEST, "请求必须是 JSON-RPC 对象")
        if message.get("jsonrpc") != JSONRPC_VERSION:
            return _error(
                _safe_id(message), INVALID_REQUEST, f"jsonrpc 字段必须是 {JSONRPC_VERSION}"
            )

        method = message.get("method")
        request_id = _safe_id(message)
        params = message.get("params") or {}
        if not isinstance(method, str):
            return _error(request_id, INVALID_REQUEST, "缺少 method 字段")

        if request_id is None:
            # 通知：不产生响应体（initialized / cancelled 都是这一类）
            logger.debug("MCP 通知 method=%s agent=%s", method, identity.agent_id)
            return None
        if method == "initialize":
            return _result(request_id, self._initialize(params))
        if method == "ping":
            return _result(request_id, {})
        if method == "tools/list":
            return _result(request_id, {"tools": [spec.to_mcp() for spec in ALL_TOOLS]})
        if method == "tools/call":
            return await self._call_tool(request_id, params, identity)
        return _error(request_id, METHOD_NOT_FOUND, f"不支持的方法：{method}")

    # --- 内部 ---

    @staticmethod
    def _initialize(params: Any) -> dict[str, Any]:
        requested = params.get("protocolVersion") if isinstance(params, dict) else None
        version = (
            requested
            if isinstance(requested, str) and requested in SUPPORTED_PROTOCOL_VERSIONS
            else DEFAULT_PROTOCOL_VERSION
        )
        return {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": VERSION},
            "instructions": SERVER_INSTRUCTIONS,
        }

    async def _call_tool(
        self, request_id: Any, params: Any, identity: AgentIdentity
    ) -> dict[str, Any]:
        if not isinstance(params, dict):
            return _error(request_id, INVALID_PARAMS, "tools/call 需要 params 对象")
        name = params.get("name")
        if not isinstance(name, str) or not name.strip():
            return _error(request_id, INVALID_PARAMS, "tools/call 缺少工具名 name")
        name = name.strip()
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return _error(request_id, INVALID_PARAMS, "tools/call 的 arguments 必须是对象")

        spec = TOOLS.get(name)
        if spec is None:
            return _error(request_id, INVALID_PARAMS, f"未知工具：{name}")

        if not identity.has(spec.capability):
            message = (
                f"权限不足：令牌所属 agent「{identity.agent_id}」未获授权能力"
                f" {spec.capability}，已拒绝执行 {name}"
            )
            await self._publish(Events.MCP_TOOL_DENIED, identity, spec, error=str(spec.capability))
            logger.warning("MCP 越权调用被拒 agent=%s tool=%s", identity.agent_id, name)
            return _result(request_id, _tool_error(message))

        ctx = ToolContext(container=self._container, identity=identity, tool_name=name)
        try:
            data = await spec.handler(ctx, arguments)
        except AIOSError as exc:
            await self._publish(Events.MCP_TOOL_CALLED, identity, spec, error=exc.code)
            logger.info(
                "MCP 工具调用失败 agent=%s tool=%s error=%s", identity.agent_id, name, exc.code
            )
            return _result(request_id, _tool_error(f"{exc.code}: {exc.message}"))
        except Exception as exc:  # noqa: BLE001 - 工具边界：任何异常都要转成显式失败
            await self._publish(Events.MCP_TOOL_CALLED, identity, spec, error="internal_error")
            logger.exception("MCP 工具执行异常 tool=%s agent=%s", name, identity.agent_id)
            return _result(request_id, _tool_error(f"工具执行失败：{exc}"))

        await self._publish(Events.MCP_TOOL_CALLED, identity, spec)
        # 审计轨迹：谁（agent/token）调了哪个工具、成没成，落一行日志（入参不落，可能含整份文件）
        logger.info("MCP 工具调用成功 agent=%s tool=%s", identity.agent_id, name)
        return _result(request_id, _tool_text(data))

    async def _publish(
        self,
        event: str,
        identity: AgentIdentity,
        spec: ToolSpec,
        *,
        error: str | None = None,
    ) -> None:
        """记一条工具调用事件。**不含入参**——提案入参里可能有整份文件内容。"""
        await self._container.bus.publish(
            event,
            {
                "agent_id": identity.agent_id,
                "token_id": identity.token_id,
                "tool": spec.name,
                "capability": str(spec.capability),
                "error": error,
            },
        )


router = APIRouter(tags=["mcp"])


@router.post(
    "/mcp",
    summary="MCP Streamable HTTP 端点（Agent 能力面）",
    response_model=None,
    responses={
        200: {"description": "JSON-RPC 响应（工具结果或协议错误）"},
        202: {"description": "通知类消息，无正文"},
        401: {"description": "缺少或非法/已撤销的 Bearer 令牌"},
    },
)
async def mcp_endpoint(request: Request, container: Container = Depends(get_container)) -> Response:
    """MCP 唯一入口。鉴权先于解析：没有合法令牌，任何方法都不可见。"""
    try:
        identity = await container.agent_tokens.authenticate(
            _bearer_token(request.headers.get("authorization"))
        )
    except AuthenticationError as exc:
        # MCP 规范要求 401 带 WWW-Authenticate；这里不让全局处理器代劳，因为那个不带该头
        return JSONResponse(
            status_code=401,
            content=fail(exc.code, exc.message),
            headers={"WWW-Authenticate": "Bearer"},
        )

    raw = await request.body()
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return _json(_error(None, PARSE_ERROR, "请求体不是合法 JSON"))

    if isinstance(payload, list):
        # MCP 2025-06-18 起不再支持批量请求
        return _json(_error(None, INVALID_REQUEST, "不支持 JSON-RPC 批量请求"))

    outcome = await McpServer(container).handle(payload, identity)
    if outcome is None:
        return Response(status_code=202)
    return _json(outcome)


def _bearer_token(header: str | None) -> str | None:
    if not header:
        return None
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer":
        return None
    return value.strip() or None


def _safe_id(message: Any) -> Any:
    """只接受 JSON-RPC 允许的 id 类型，其它一律当作无 id（避免回显奇怪对象）。"""
    if not isinstance(message, dict):
        return None
    value = message.get("id")
    return value if isinstance(value, (str, int)) and not isinstance(value, bool) else None


def _result(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": JSONRPC_VERSION, "id": request_id, "result": result}


def _error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": JSONRPC_VERSION,
        "id": request_id,
        "error": {"code": code, "message": message},
    }


def _tool_text(data: dict[str, Any]) -> dict[str, Any]:
    """把工具的结构化结果渲染成一份 JSON 文本（所有 MCP 客户端都能消费）。"""
    return {
        "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False, indent=2)}],
        "isError": False,
    }


def _tool_error(message: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": message}], "isError": True}


def _json(body: dict[str, Any]) -> JSONResponse:
    return JSONResponse(status_code=200, content=body)
