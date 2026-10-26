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
from dataclasses import replace
from typing import Any

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse

from flux.api.deps import get_container
from flux.api.response import fail
from flux.container import Container
from flux.core.context.redact import redact
from flux.core.event.bus import Events
from flux.core.mcp.auth import AgentIdentity
from flux.core.mcp.tools import ALL_TOOLS, TOOLS, ToolContext, ToolSpec
from flux.core.permissions.modes import (
    allowed_tools,
    denied_message,
    normalize_mode,
    tool_allowed,
)
from flux.enums import StepKind, StepStatus
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
    "跨会话的长期记忆（Environment 平台规则 / User 偏好 / Project 沉淀）用 memory.recall "
    "读取（只读）；改动一律经 proposal.create 提交进人审队列，真实落盘由人审通过后经 "
    "Apply Engine 执行。页面 / UI / 视觉设计任务必须用 design.* 工具在设计工作室产出"
    "（禁止用手写 HTML、截图或静态图替代），完成后调 design.share 把卡片发进对话；"
    "改已有设计先 design.get 再 design.update。历史条目是快照：断言不等于事实，"
    "采信前自行验证。MCP 不可用时，"
    "可用 Flux Server CLI 兜底继续工作（例如 `flux tools call <tool>`）。"
    "【交卷前必须自检】proposal.create 之前，先用 proposal.preview 把同一份 payload 在"
    "临时副本里跑一遍测试命令（它会把改动落进副本再跑；直接 terminal.execute 跑的是磁盘上的"
    "旧代码，绿了也不算数）。测试没过不许提交提案：读失败输出、改自己的改动、再 preview，"
    "直到全绿；单轮最多重试 3 次，仍不绿就把失败输出写进 proposal 的 summary 里交人工判断，"
    "不要假装通过。预演本身不产生提案：只有 proposal.create 才会让改动进入人审队列，"
    "预演通过后必须再提交一次同一份 payload。"
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
            whitelist = allowed_tools(identity.permission_mode)
            tools = [
                spec.to_mcp()
                for spec in ALL_TOOLS
                if whitelist is None or spec.name in whitelist
            ]
            return _result(request_id, {"tools": tools})
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

        # 第一道：模式白名单（Plan 只读）。DSH 的长期令牌是能力位超集，
        # 只靠 scopes 区分不了模式，所以白名单必须排在能力位检查之前。
        if not tool_allowed(identity.permission_mode, name):
            message = denied_message(identity.permission_mode, name)
            await self._publish(Events.MCP_TOOL_DENIED, identity, spec, error="permission_mode")
            logger.warning(
                "MCP 模式越权调用被拒 agent=%s mode=%s tool=%s",
                identity.agent_id,
                normalize_mode(identity.permission_mode),
                name,
            )
            return _result(request_id, _tool_error(message))

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
            # 失败也要摄入（§3.3）：错误是下一轮最需要的上下文，成功路径写、失败路径不写
            # 会让 Agent 在同一个坑里反复踩——它看不到自己刚才失败在哪。
            await self._ingest_context(
                identity,
                spec,
                {"error": {"code": exc.code, "message": exc.message}},
                status=StepStatus.FAIL,
            )
            return _result(request_id, _tool_error(f"{exc.code}: {exc.message}"))
        except Exception as exc:  # noqa: BLE001 - 工具边界：任何异常都要转成显式失败
            await self._publish(Events.MCP_TOOL_CALLED, identity, spec, error="internal_error")
            logger.exception("MCP 工具执行异常 tool=%s agent=%s", name, identity.agent_id)
            await self._ingest_context(
                identity,
                spec,
                {"error": {"code": "internal_error", "message": str(exc)}},
                status=StepStatus.FAIL,
            )
            return _result(request_id, _tool_error(f"工具执行失败：{exc}"))

        await self._publish(Events.MCP_TOOL_CALLED, identity, spec)
        # 审计轨迹：谁（agent/token）调了哪个工具、成没成，落一行日志（入参不落，可能含整份文件）
        logger.info("MCP 工具调用成功 agent=%s tool=%s", identity.agent_id, name)
        # 上下文摄入（机制 §3.3 / §10.1）：能归属到 Run 才写库，且 best-effort。
        await self._ingest_context(identity, spec, data)
        return _result(request_id, _tool_text(data))

    async def _ingest_context(
        self,
        identity: AgentIdentity,
        spec: ToolSpec,
        data: dict[str, Any],
        *,
        status: StepStatus = StepStatus.OK,
    ) -> None:
        """把一次工具结果摄入上下文：redact → CAS 原文 → step 落库 → 脱敏映射（§10.1 契约）。

        成功与失败共用同一条路径：失败只是 `status=FAIL`、正文换成错误对象——step 日志
        因此能回答"这轮做砸了什么"，而不是只留下成功的部分历史。

        归属键 `run_id` 来自 `X-Flux-Run-Id`（可为 None ⇒ 无法归属到 Run，整段跳过，不写库）。
        当前上下文机制没有 turn 粒度，`turn_id` 暂以 `run_id` 近似（占位符/删除走同一把钥匙）。
        摄入是 **best-effort**：任何一步失败只记 warning，绝不改变工具对外结果。
        """
        run_id = identity.run_id
        if run_id is None:
            return
        try:
            text = json.dumps(data, ensure_ascii=False, indent=2)
            result = redact(text)
            body = result.text.encode("utf-8")
            blob = await self._container.content_store.put(body)
            await self._container.context_steps.append(
                run_id=run_id,
                turn_id=run_id,
                kind=StepKind.TOOL_CALL,
                status=status,
                tool=spec.name,
                result_sha256=blob.sha256,
                result_bytes=len(body),
                result_head=result.text[:512],
            )
            await self._container.context_redactions.record(
                run_id=run_id, turn_id=run_id, matches=result.matches
            )
        except Exception:  # noqa: BLE001 - 工具边界不允许因摄入失败而失败
            logger.warning("MCP 上下文摄入失败 tool=%s run=%s", spec.name, run_id, exc_info=True)

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

    identity = await _attach_run_id(request, container, identity)

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


async def _attach_run_id(
    request: Request, container: Container, identity: AgentIdentity
) -> AgentIdentity:
    """把 `X-Flux-Run-Id`（若确属本 Agent 的 Run）附加到身份上，供终端会话绑定 Run。

    该头由 Flux 起 Run 时注入到 DSH 的 MCP patch；用于把 Agent 经 `terminal.execute`
    派生的终端会话绑到具体 Run，Run 终态时据此回收（避免命令进程泄漏）。它**不是权威身份**：
    查不到该 Run、或归属与令牌身份不匹配，一律静默忽略（不 401）——Run 维度的小错误不该
    升级成鉴权失败。校验依据是"该 Run 的 agent_id 与令牌 agent_id 相等"，杜绝跨 Agent 冒充。
    """
    run_id = (request.headers.get("x-flux-run-id") or "").strip()
    if not run_id:
        return identity
    row = await container.run_repo.get(run_id)
    if row is None or not row.agent_id or row.agent_id != identity.agent_id:
        logger.warning("mcp 忽略非法 X-Flux-Run-Id run=%s agent=%s", run_id, identity.agent_id)
        return identity
    # 模式按 Run 快照判定：切换模式只影响下一轮，正在跑的这一轮始终用启动时的模式。
    return replace(
        identity,
        run_id=run_id,
        permission_mode=normalize_mode(getattr(row, "permission_mode", None)),
    )


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
