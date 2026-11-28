"""上下文摄入的唯一共享入口（FLUX_CONTEXT_MECHANISM §3.3 / §10.1）。

issue #3 的残留问题：过去只有带 `X-Flux-Run-Id` 的 Flux MCP `tools/call` 会写
`context_steps`。产品主循环里的用户消息、助手最终答复、runtime 原生工具/命令输出
都不经过 MCP，于是存档里只有平台工具那一小块，接管方看不到"用户到底要什么、agent
原生执行了什么"。

本模块把各个入口收敛到同一条契约（与 MCP 路径逐字一致）：

    result = redact(text)
    body = result.text.encode("utf-8")
    blob = content_store.put(body)
    context_steps.append(... result_sha256=blob.sha256, result_head=result.text[:512])
    context_redactions.record(...)

归属键（`run_id`）的取舍：

- 能合理归属到 Run 就写 Run id（stream relay / terminal / MCP 都有）；
- 用户消息可能在 Run 创建之前落库：此时用**稳定的任务级键** ``task:<task_uuid>``
  （`turn_id` 同键）。Run 启动后不追溯搬移——摄入在消息落库热路径上，回填/双写只会
  制造重复 step，宁可让"任务级"与"Run 级"两段历史在各自键下完整存在；
- 完全无法归属（空 `run_id`）时跳过并记 debug：不在上下文日志里编造与 Run 无关的噪声。

**best-effort**：任何一步失败只记 warning，绝不改变主路径结果。正文一律先打码再落库，
清单里的字面量也走同一套占位符，明文密钥不会进 step head / blob。
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any, Protocol

from flux.core.context.redact import redact
from flux.core.context.repository import ContextStepRepository
from flux.core.context.store import ContentStore
from flux.enums import StepKind, StepStatus
from flux.logging import get_logger

logger = get_logger(__name__)

#: `result_head` 的预览长度：与 MCP 路径保持一致（目录行内联锚点，完整原文在 CAS）。
RESULT_HEAD_CHARS = 512
#: 任务级归属键前缀：Run 尚未创建时用户消息的稳定归属（不随时间/重启漂移）。
TASK_RUN_PREFIX = "task:"

#: 用户消息里真正算"正文"的类型：普通发言、需求确认、决策选择。
#: 附件清单与安全输入回执是系统消息，不重复灌进上下文。
_USER_MESSAGE_KINDS = frozenset({"text", "confirmation", "decision_result"})
#: 助手侧可见正文（最终答复、前情摘要、交接、安全输入卡片文案）。
_ASSISTANT_MESSAGE_KINDS = frozenset({"text", "summary", "handoff", "secure_input"})
#: run_finished 的兜底文案：模型没产出文字时的状态说明，不是最终答复，摄入只会制造噪声。
_RUN_FINISHED_FALLBACK_PREFIX = "Agent 本轮没有生成文字回复"


class RedactionRecorder(Protocol):
    """摄入所需的脱敏映射表最小接口（便于单测注入假实现）。"""

    async def record(self, *, run_id: str, turn_id: str, matches: Sequence[Any]) -> int: ...


class ContextIngestor:
    """把一段正文打码后写进 step 日志 / 原文库 / 脱敏映射表（§10.1 契约）。"""

    def __init__(
        self,
        *,
        content_store: ContentStore,
        steps: ContextStepRepository,
        redactions: RedactionRecorder,
        literals: Sequence[str] = (),
    ) -> None:
        self._content_store = content_store
        self._steps = steps
        self._redactions = redactions
        self._literals = tuple(literals)

    async def ingest(
        self,
        *,
        run_id: str,
        kind: StepKind,
        text: str,
        turn_id: str | None = None,
        status: StepStatus = StepStatus.OK,
        tool: str | None = None,
        target_ref: str | None = None,
        exit_code: int | None = None,
        agent_fp: str = "",
        argv_digest: str | None = None,
        facts: list[dict[str, Any]] | None = None,
    ) -> Any | None:
        """摄入一条 step；返回落库后的 step，跳过或失败返回 None。

        `run_id` 为空表示无法归属到任何执行单元——直接跳过（不写库）。
        `turn_id` 省略时与 `run_id` 相同（当前机制没有更细的 turn 粒度）。
        """
        if not run_id:
            logger.debug("上下文摄入跳过：无法归属到 Run kind=%s tool=%s", kind.value, tool)
            return None
        turn = turn_id or run_id
        try:
            result = redact(text, literals=self._literals)
            body = result.text.encode("utf-8")
            blob = await self._content_store.put(body)
            step = await self._steps.append(
                run_id=run_id,
                turn_id=turn,
                kind=kind,
                status=status,
                agent_fp=agent_fp,
                tool=tool,
                argv_digest=argv_digest,
                target_ref=target_ref,
                exit_code=exit_code,
                result_sha256=blob.sha256,
                result_bytes=len(body),
                result_head=result.text[:RESULT_HEAD_CHARS],
                facts=facts,
            )
            await self._redactions.record(run_id=run_id, turn_id=turn, matches=result.matches)
            return step
        except Exception:  # noqa: BLE001 - 摄入失败不能改变主路径结果
            logger.warning(
                "上下文摄入失败 run=%s kind=%s tool=%s",
                run_id,
                kind.value,
                tool,
                exc_info=True,
            )
            return None

    async def ingest_task_messages(
        self,
        task_id: Any,
        run_id: str | None,
        messages: Sequence[Any],
    ) -> None:
        """消息落库后的摄入钩子（`TaskRepository.add_messages` 调用）。

        只收会话正文：用户正文、助手最终答复/可见正文。`run_finished` 只有
        `status=completed` 且不是"没有文字回复"兜底时才当最终答复；失败/取消的状态
        摘要、重试提示、附件清单等系统消息一律跳过，避免和 stream 路径重复。
        """
        owner = run_id or f"{TASK_RUN_PREFIX}{task_id}"
        for message in messages:
            role = str(getattr(message, "role", "") or "")
            kind = str(getattr(message, "kind", "text") or "text")
            content = str(getattr(message, "content", "") or "")
            payload = getattr(message, "payload", None)
            if not content.strip() or not self._message_is_ingestable(role, kind, content, payload):
                continue
            await self.ingest(
                run_id=owner,
                kind=StepKind.MODEL_CALL,
                text=content,
                tool="user_message" if role == "user" else "assistant_message",
                target_ref=f"message:{getattr(message, 'seq', '')}",
            )

    @staticmethod
    def _message_is_ingestable(role: str, kind: str, content: str, payload: Any) -> bool:
        if role == "user":
            return kind in _USER_MESSAGE_KINDS
        if role != "assistant":
            return False
        if kind in _ASSISTANT_MESSAGE_KINDS:
            return True
        if kind != "run_finished":
            return False
        data = payload if isinstance(payload, dict) else {}
        return str(data.get("status") or "") == "completed" and not content.startswith(
            _RUN_FINISHED_FALLBACK_PREFIX
        )


__all__ = ["ContextIngestor", "RESULT_HEAD_CHARS", "TASK_RUN_PREFIX"]
