"""Task API（主规格 §12.4）。

任务状态落 tasks 表（本增量起），进程内的调度队列是易失结构、不承担状态权威。
对话消息落 task_messages 表（任务执行中心增量）：刷新页面、重启后端都不丢；
助手回复由平台经 ModelRouter 真实调用生成（见 core/task_engine/assistant.py）。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import uuid
from collections.abc import AsyncIterator
from contextlib import suppress
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Header
from fastapi.responses import StreamingResponse

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.agent_runtime.manager import AGENT_KIND_EXTERNAL
from flux.core.agent_runtime.run_repository import as_utc, utcnow
from flux.core.agent_runtime.runtimes.base import DEFAULT_RUNTIME
from flux.core.agent_runtime.trace import summarize_trace
from flux.core.attachment_store import UploadedFile, resolve_uploads, size_label
from flux.core.context.budget import history_message_limit
from flux.core.context.projection import (
    incomplete_secret_start,
    redact_projection,
    redact_text,
    redacted_message,
    safe_cut,
    sanitize_assistant_message_fields,
    task_projection,
)
from flux.core.event.bus import Events
from flux.core.group.avatar import normalize_avatar
from flux.core.model_gateway.base import ChatMessage
from flux.core.task_engine.assistant import validate_confirmation_items
from flux.core.task_engine.context_usage import (
    CONTEXT_MESSAGE_KINDS,
    context_message_limit,
    measure_context,
)
from flux.core.task_engine.decisions import build_decision, reject_decision, resolve_decision
from flux.core.task_engine.repository import MessageDraft
from flux.enums import (
    DecisionMode,
    DecisionStatus,
    DshRunStatus,
    TaskMessageRole,
    TaskOrigin,
    TaskStatus,
)
from flux.errors import BadRequestError, ConflictError, NotFoundError, ValidationError
from flux.logging import get_logger
from flux.models.task import Task
from flux.schemas.api import (
    ConfirmationUpdateRequest,
    DecisionChooseRequest,
    DecisionCreateRequest,
    DecisionModeRequest,
    PermissionModeRequest,
    SecureInputRequest,
    TaskAgentRequest,
    TaskCreateRequest,
    TaskMessageCreateRequest,
    TaskModelRequest,
    TaskStartRequest,
)

router = APIRouter(prefix="/tasks", tags=["tasks"])

logger = get_logger(__name__)

_FINAL_STATUSES = {TaskStatus.COMPLETED.value, TaskStatus.CANCELLED.value, TaskStatus.FAILED.value}

#: 一次对话请求带上的历史条数上限：由 §6.1 的字符预算折算而来（不再是固定值）
_HISTORY_LIMIT = history_message_limit()

#: 生成交接摘要时最多回看的历史消息条数（更早的由摘要器按字符预算丢弃并标注）
_HANDOFF_MESSAGE_LIMIT = 120


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _format_task_sse(payload: dict[str, Any]) -> str:
    data = json.dumps(payload, ensure_ascii=False)
    return f"id: {payload['seq']}\nevent: task.event\ndata: {data}\n\n"


def _confirmation_text(items: list[dict[str, str]]) -> str:
    """把确认卡拍成一条可读消息（聊天记录里能直接看懂，不依赖前端渲染）。"""
    lines = ["需求确认（最终版本）："]
    lines.extend(f"- {item['label']}：{item['value']}" for item in items)
    return "\n".join(lines)


async def _require_open_task(container: Container, task_uuid: uuid.UUID, task_id: str) -> Task:
    """取任务并确认它还能被操作（终态任务一律 409，与追加消息同一口径）。"""
    task = await container.task_repo.get(task_uuid)
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，不能再执行该操作",
            details={"task_id": task_id, "status": task.status},
        )
    return task


class _StreamingDeltaRedactor:
    """跨帧 `assistant_delta` 打码：对累计原文打码，只放出已确认安全的前缀。

    单帧调用 `redact` 挡不住被拆到相邻两帧的配置字面量：两半都不构成完整字面量，
    各自原样通过，客户端按增量拼接后又能看到完整明文。这里以整轮累计原文为输入，
    每次只放出"不是任何字面量 / 内置规则未完成后缀"的安全前缀，其余暂缓到后续块或
    本轮结束（``flush``）。

    普通正文（含中文）没有可疑后缀，会照常逐帧流出；只有确实可能拼成密钥的短尾巴
    会被短暂缓一缓，不会把整段回复攒到最后。
    """

    def __init__(self, container: Container) -> None:
        self._container = container
        self._literals = tuple(getattr(container.settings, "redaction_literal_list", ()))
        self._raw = ""
        #: 已放出的累计原文长度：下一段只对新增部分打码，避免占位符编号随累计重算抖动。
        self._released = 0

    def feed(self, chunk: str) -> str:
        """喂入一帧模型增量，返回本次可以放出的打码后文本（可能为空）。"""
        if not chunk:
            return ""
        self._raw += chunk
        return self._release(final=False)

    def flush(self, *, abort: bool = False) -> str:
        """本轮结束：正常完成放出暂缓文本；中途失败只放已确认安全的前缀。

        异常 / 取消时不能按"正常结束"处理：尚未放出的尾串可能只是某个字面量或令牌的
        前半截（如 ``CUSTOM_LITE``），精确清单命中不了，整段放出就会把前缀残片漏给
        客户端。这里退到安全切点后直接丢弃剩余尾串——宁可少发一段，也不放未完成密钥。

        正常结束同样不能整段照发：回复如果恰好停在字面量 / 令牌的前半截，精确清单
        命中不了，flush 就会把残片当正文漏出。但也不能一律按 abort 截断——被暂缓的
        普通词尾（``hello`` + `` world`` 的 ``world``）是安全正文，仍要完整发出。故
        ``_final_cut`` 只丢弃确实不完整的密钥形态，其余照常打码后放出。
        """
        if abort:
            return self._abort()
        return self._release(final=True)

    def _abort(self) -> str:
        """中途结束：放出安全前缀，丢弃未确认安全的尾串且不再续放。"""
        cut = safe_cut(self._raw, self._literals)
        text = ""
        if cut > self._released:
            segment = self._raw[self._released : cut]
            text = redact_text(self._container, segment)
        # 未完成的前缀留在 `_raw` 里也不能再放：把游标推到末尾，后续 feed 不会补发。
        self._released = len(self._raw)
        return text

    def _release(self, *, final: bool) -> str:
        cut = self._final_cut() if final else safe_cut(self._raw, self._literals)
        if cut <= self._released:
            return ""
        segment = self._raw[self._released : cut]
        self._released = cut
        return redact_text(self._container, segment)

    def _final_cut(self) -> int:
        """正常结束的切点：丢开未完成的密钥尾串，其余跟着打码一起放出。"""
        suspicious = incomplete_secret_start(self._raw, self._literals)
        if suspicious is None:
            return len(self._raw)
        # 可疑尾串之前仍可能以另一个未完成前缀结尾，继续退到真正的安全切点。
        return safe_cut(self._raw[:suspicious], self._literals)


async def _record_attachments(
    container: Container, task_uuid: uuid.UUID, files: list[UploadedFile]
) -> None:
    """把本轮附件记成一条 kind=attachments 的消息：刷新/换设备后仍能看到发过哪些文件。"""
    if not files:
        return
    message = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.USER.value,
                    kind="attachments",
                    content="、".join(item.name for item in files),
                    payload={"files": [item.to_dict() for item in files]},
                )
            ],
        )
    )[0]
    await container.task_stream.publish(
        task_uuid, "user_message", {"message": redacted_message(container, message)}
    )


def _attachments_brief(files: list[UploadedFile]) -> str:
    """只发附件没写正文时，给助手/Agent 的一句人话说明。"""
    return "请看我随消息附上的文件：" + "、".join(
        f"{item.name}（{size_label(item.size)}）" for item in files
    )


def _parse_uuid(value: str, *, field: str) -> uuid.UUID:
    """请求体里的标识串必须是合法 UUID，否则按 400 拒绝。"""
    try:
        return uuid.UUID(value)
    except ValueError as exc:
        raise BadRequestError(f"{field} 不是合法 UUID：{value}", details={field: value}) from exc


def _task_id(value: str) -> uuid.UUID:
    """路径参数里的任务标识非法时按「任务不存在」处理，与 M0 的 404 语义一致（不返回 422）。"""
    try:
        return uuid.UUID(value)
    except ValueError as exc:
        raise NotFoundError(f"任务 {value} 不存在", details={"task_id": value}) from exc


@router.post("")
async def create_task(
    payload: TaskCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    project_id = _parse_uuid(payload.project_id, field="project_id") if payload.project_id else None
    agent_id = _parse_uuid(payload.agent_id, field="agent_id") if payload.agent_id else None
    runtime = DEFAULT_RUNTIME
    if agent_id is not None:
        # Agent 注册表是 Agent 存在性的唯一权威：API 边界 fail-closed。
        agent = container.agents.get(payload.agent_id)
        if agent.spec.kind == AGENT_KIND_EXTERNAL:
            raise BadRequestError(
                "外部接入的 Agent 不可通过 Flux 执行",
                details={"agent_id": payload.agent_id, "kind": agent.spec.kind},
            )
        runtime = str(getattr(getattr(agent, "spec", None), "runtime", DEFAULT_RUNTIME))
    model, key_id = await container.key_vault.resolve_selection(
        runtime=runtime, model=payload.model or "", key_id=payload.key_id
    )
    avatar_shape, avatar_color = normalize_avatar(payload.avatar_shape, payload.avatar_color)

    # 附件先校验：不合格就别把任务建出来，免得留下一条空对话
    files = resolve_uploads(container.settings, payload.attachment_ids)
    task = await container.task_repo.create(
        task_id=uuid.uuid4(),
        description=payload.description,
        priority=payload.priority,
        project_id=project_id,
        agent_id=agent_id,
        decision_mode=payload.decision_mode,
        permission_mode=payload.permission_mode,
        model=model,
        api_key_id=key_id,
        origin=payload.origin,
        bot_name=payload.bot_name,
        avatar_shape=avatar_shape,
        avatar_color=avatar_color,
    )
    # 从旧对话接手：摘要落成一条 kind=summary 的助手消息。它同时进入助手历史
    # 与 Agent 执行指令（TaskRunService._latest_summary），新对话因此不是"从零开始"，
    # 但也不会拷贝任何旧消息。
    summary = (payload.summary or "").strip()
    if summary:
        source_id = (
            _parse_uuid(payload.summary_from, field="summary_from")
            if payload.summary_from
            else None
        )
        await container.task_repo.add_messages(
            task.id,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="summary",
                    content=summary,
                    payload={"from_task_id": str(source_id) if source_id else None},
                )
            ],
        )
        logger.info(
            "新对话带入前情摘要 task=%s from=%s 字数=%d",
            task.id,
            source_id or "未提供",
            len(summary),
        )
    await _record_attachments(container, task.id, files)
    # 建 bot 时只落对话、不启动执行：否则会拿 bot 名字当需求直接跑 Agent。
    if payload.start:
        container.scheduler.submit(
            str(task.id),
            task.description,
            priority=task.priority,
            agent_id=str(agent_id) if agent_id is not None else None,
        )
    result = task_projection(container, task)
    await container.bus.publish(Events.TASK_CREATED, result)
    return ok(result)


@router.get("")
async def list_tasks(
    project_id: str | None = None,
    status: str | None = None,
    origin: str | None = None,
    limit: int = 50,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出任务（任务执行中心的任务列表）。默认按创建时间倒序。"""
    project_uuid = _parse_uuid(project_id, field="project_id") if project_id else None
    wanted: TaskStatus | None = None
    if status:
        try:
            wanted = TaskStatus(status)
        except ValueError as exc:
            raise BadRequestError(
                f"status 不是合法的任务状态：{status}",
                details={"status": status, "allowed": [s.value for s in TaskStatus]},
            ) from exc
    origin_filter: TaskOrigin | None = None
    if origin:
        try:
            origin_filter = TaskOrigin(origin)
        except ValueError as exc:
            raise BadRequestError(
                f"origin 不是合法的对话来源：{origin}",
                details={"origin": origin, "allowed": [o.value for o in TaskOrigin]},
            ) from exc
    if limit < 1 or limit > 200:
        raise BadRequestError("limit 必须在 1~200 之间", details={"limit": limit})
    tasks = await container.task_repo.list(
        project_id=project_uuid, status=wanted, origin=origin_filter, limit=limit
    )
    return ok([task_projection(container, t) for t in tasks], metadata={"count": len(tasks)})


@router.post("/context-sync")
async def sync_context(container: Container = Depends(get_container)) -> dict[str, object]:
    """手动触发一次对话上下文上云（隐私输入以外的消息），返回本轮统计。"""
    result = await container.context_cloud.push()
    return ok(result)


@router.get("/{task_id}")
async def get_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    task = await container.task_repo.get(_task_id(task_id))
    return ok(task_projection(container, task))


@router.get("/{task_id}/messages")
async def list_task_messages(
    task_id: str,
    limit: int = 30,
    before: int | None = None,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """按 seq 升序返回一段任务消息；before 为向上加载更早消息的游标。"""
    if limit < 1 or limit > 200:
        raise BadRequestError("limit 必须在 1~200 之间", details={"limit": limit})
    messages, has_more = await container.task_repo.list_messages(
        _task_id(task_id), limit=limit, before=before
    )
    return ok(
        [redacted_message(container, m) for m in messages],
        metadata={"count": len(messages), "has_more": has_more},
    )


def _run_summary(run: Any, stats: dict[str, int]) -> dict[str, object]:
    """一轮 Run 的摘要：耗时 + 概览计数（对话里的「任务耗时」入口与详情页眉用）。"""
    duration_ms: int | None = None
    if run.started_at is not None:
        end = run.finished_at if run.finished_at is not None else utcnow()
        duration_ms = max(0, int((as_utc(end) - as_utc(run.started_at)).total_seconds() * 1000))
    return {
        "run_id": run.id,
        "task_id": str(run.task_id) if run.task_id else None,
        "agent_id": run.agent_id,
        "status": run.status,
        "started_at": run.started_at.isoformat() if run.started_at else None,
        "finished_at": run.finished_at.isoformat() if run.finished_at else None,
        "duration_ms": duration_ms,
        "commands": stats.get("commands", 0),
        "files_read": stats.get("files_read", 0),
        "files_changed": stats.get("files_changed", 0),
    }


@router.get("/{task_id}/runs")
async def list_task_runs(
    task_id: str,
    limit: int = 20,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """任务名下的 Run 摘要（按时间升序）：耗时与「改动/命令/读取」计数。"""
    if limit < 1 or limit > 100:
        raise BadRequestError("limit 必须在 1~100 之间", details={"limit": limit})
    task_uuid = _task_id(task_id)
    await container.task_repo.get(task_uuid)
    runs = await container.run_repo.list_by_task(task_uuid, limit=limit)
    rows = await container.run_trace_repo.list_for_runs([run.id for run in runs])
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(str(row["run_id"]), []).append(row)
    return ok([_run_summary(run, summarize_trace(grouped.get(run.id, []))) for run in runs])


@router.get("/{task_id}/runs/{run_id}/trace")
async def get_run_trace(
    task_id: str,
    run_id: str,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """一轮 Run 的完整执行轨迹：概览 + 思考过程 + 命令（含输出）。"""
    task_uuid = _task_id(task_id)
    run = await container.run_repo.get(run_id)
    if run is None or run.task_id != task_uuid:
        raise NotFoundError(
            f"Run {run_id} 不属于任务 {task_id}",
            details={"task_id": task_id, "run_id": run_id},
        )
    rows = await container.run_trace_repo.list_for_run(run_id)
    items = [row.to_dict() for row in rows]
    data = _run_summary(run, summarize_trace(items))
    data["items"] = items
    return ok(data)


@router.get("/{task_id}/context")
async def get_task_context(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """对话上下文占用（「上下文快满」提醒的唯一口径）。

    度量的不是模型标称窗口，而是执行 Agent 下一轮实际会收到的内容：
    与 `TaskRunService.start()` 同源装配的执行指令（约束 / 任务 / 工作记忆 /
    压缩后的对话轨迹 / 计划）。历史已压缩在指令里，这里不再把原始消息重复计入，
    否则对话一长就永远停在 100%。条数只作为解释信息返回。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    # 与真实开跑共用一套装配，避免「占用条」与「Agent 实际收到」两套口径
    instruction = await container.task_run_service.preview_instruction(task)
    limit = context_message_limit()
    _, total = await container.task_repo.context_snapshot(
        task_uuid, limit=limit, kinds=CONTEXT_MESSAGE_KINDS
    )
    usage = measure_context([], message_count=total, limit=limit, extra_chars=len(instruction))
    return ok(usage.to_dict())


@router.post("/{task_id}/handoff")
async def create_task_handoff(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """把一段对话压成前情摘要，供客户端带进新对话。

    只生成摘要、不建任务：新对话由客户端在用户发出第一条消息时创建
    （`POST /tasks` 的 summary / summary_from 字段），避免留下一段
    没有用户意图的孤儿对话。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status == TaskStatus.RUNNING.value:
        raise ConflictError(
            "这段对话正在执行，结束后再新建带摘要的对话",
            details={"task_id": task_id, "status": task.status},
        )
    messages, _ = await container.task_repo.list_messages(task_uuid, limit=_HANDOFF_MESSAGE_LIMIT)
    # 交接摘要会把消息原文交给模型并回传；进入摘要前先按配置清单打码，
    # 避免 task_messages 里的密钥以明文形式穿过 handoff（也不让截断边界漏片段）。
    pairs = [
        (message.role, redact_text(container, message.content))
        for message in messages
        if message.kind in CONTEXT_MESSAGE_KINDS and message.content.strip()
    ]
    summary = await container.conversation_summarizer.summarize(
        description=redact_text(container, task.description or ""), messages=pairs
    )
    logger.info(
        "生成对话交接摘要 task=%s 来源消息=%d 截断=%s",
        task_uuid,
        summary.source_messages,
        summary.truncated,
    )
    data = summary.to_dict()
    # 模型可能原样复述输入：返回前再对摘要做一次统一打码（模型输出也走同一口径）。
    data["summary"] = redact_text(container, str(data.get("summary") or ""))
    data["from_task_id"] = str(task_uuid)
    return ok(data)


@router.post("/{task_id}/messages")
async def create_task_message(
    task_id: str,
    payload: TaskMessageCreateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """发一条消息并取回助手的真实回复。

    用户消息先落库（模型调用失败时它也不会丢，刷新页面仍在），
    随后调用模型生成回复并落库——两个步骤都是真实数据，服务端不接受伪造的 assistant 消息。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    content = payload.content.strip()
    files = resolve_uploads(container.settings, payload.attachment_ids)
    if not content and not files:
        raise ValidationError(
            "消息内容不能为空", details={"task_id": task_id, "attachments": len(files)}
        )
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，不能再追加消息",
            details={"task_id": task_id, "status": task.status},
        )

    user_message = (
        await container.task_repo.add_messages(
            task_uuid, [MessageDraft(role=TaskMessageRole.USER.value, content=content)]
        )
    )[0]
    await container.task_stream.publish(
        task_uuid, "user_message", {"message": redacted_message(container, user_message)}
    )
    await _record_attachments(container, task_uuid, files)

    history, _ = await container.task_repo.list_messages(task_uuid, limit=_HISTORY_LIMIT)
    context = (
        await container.brain.context(task.project_id) if task.project_id is not None else None
    )
    # 新一轮开始：上一轮的增量状态已由它自己的 flush 清空，这里新建本轮的打码器。
    stream_redactor = _StreamingDeltaRedactor(container)
    await container.task_stream.publish(task_uuid, "assistant_start", {})

    async def _publish_delta(text: str) -> None:
        if text:
            await container.task_stream.publish(task_uuid, "assistant_delta", {"text": text})

    async def _on_delta(text: str) -> None:
        # 只放出累计原文里"已确认安全"的增量；被拆开或未完成的密钥片段先留在手里。
        await _publish_delta(stream_redactor.feed(text))

    async def _flush_delta(*, abort: bool = False) -> None:
        # 正常结束必须 flush：否则暂缓的安全文本永远发不出去，客户端看到的回复会缺尾巴。
        # 中途失败只放已确认安全的前缀，未完成的字面量 / 令牌残片直接丢弃。
        await _publish_delta(stream_redactor.flush(abort=abort))

    # 附件消息不进助手历史（kind=attachments），所以把附件清单拼进本轮提问，
    # 否则用户「文字 + 附件」一起发时助手只看到文字、不知道有文件。
    prompt_text = content or _attachments_brief(files)
    if files and content:
        prompt_text = content + "\n\n" + _attachments_brief(files)
    try:
        turn = await container.assistant.respond(
            description=task.description,
            message=prompt_text,
            history=[
                ChatMessage(role=m.role, content=m.content)
                for m in history
                if m.kind in CONTEXT_MESSAGE_KINDS and m.id != user_message.id
            ],
            context=context,
            on_delta=_on_delta,
        )
    except asyncio.CancelledError:
        # 取消也算中途结束：只放安全前缀，别把未完成的字面量 / 令牌残片当成正文发出去。
        with suppress(Exception):
            await _flush_delta(abort=True)
        raise
    except Exception as exc:  # noqa: BLE001 - 先通知正在看的客户端，再按原样抛给调用方
        # 本轮异常结束只放已确认安全的前缀；未完成的前缀残片不能当正文补发。
        await _flush_delta(abort=True)
        # 先对完整异常文本打码，再截断：反序会把跨边界的字面量切成片段漏出。
        await container.task_stream.publish(
            task_uuid,
            "assistant_error",
            {"error": redact_text(container, str(exc))[:500]},
        )
        raise
    # 正常结束：先补发暂缓的增量，再发最终整条消息（最终消息会整体打码）。
    await _flush_delta()
    # 落库前与流式 flush 同口径消毒：模型恰好停在字面量 / 令牌前半段时，
    # 不能把 `CUSTOM_LITE` 这类残片写进 assistant 消息（GET / messages 会原样读到）。
    stored_reply, stored_payload = sanitize_assistant_message_fields(
        container, turn.reply, turn.payload()
    )
    reply = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    # 需要用户填密钥时，气泡本身就是那张安全输入卡（正文仍是回复文案）
                    kind="secure_input" if turn.secure_input else "text",
                    content=stored_reply,
                    payload=stored_payload,
                )
            ],
        )
    )[0]
    await container.task_stream.publish(
        task_uuid, "assistant_message", {"message": redacted_message(container, reply)}
    )

    if turn.confirmation:
        # 助手每给出一次确认，就刷新任务上的确认卡（P0-06）。用户在"开始执行"前改过的那份
        # 是执行依据；这里覆盖的是"又有了新信息"的情况——新一轮结论比旧结论更接近最终需求。
        task = await container.task_repo.set_confirmation(
            task_uuid,
            {"items": turn.confirmation, "actor": "assistant", "updated_at": _now_iso()},
        )

    if task.status == TaskStatus.PENDING.value:
        # 平台已真实开始处理这条任务：状态从 pending 进入 running（有实际动作才改状态）
        task = await container.task_repo.set_status(task_uuid, TaskStatus.RUNNING)
    return ok(
        {
            "task": task_projection(container, task),
            "messages": [
                redacted_message(container, user_message),
                redacted_message(container, reply),
            ],
        },
        metadata={"provider": turn.provider, "model": turn.model},
    )


@router.post("/{task_id}/secure-input")
async def submit_secure_input(
    task_id: str,
    payload: SecureInputRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """安全输入卡片的提交入口：明文只在这里出现一次，落库即加密，接口只回掩码。

    用户填的密钥**不进 task_messages**：消息表只留一条「已安全存储」的回执，
    正文与 payload 都不含明文，因此后续上传云端上下文时也不会带出密钥。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，不能再提交密钥",
            details={"task_id": task_id, "status": task.status},
        )

    card_uuid = _parse_uuid(payload.message_id, field="message_id")
    card = await container.task_repo.get_message(task_uuid, card_uuid)
    if card is None or card.kind != "secure_input":
        raise NotFoundError(
            "安全输入卡片不存在或不属于该任务",
            details={"task_id": task_id, "message_id": payload.message_id},
        )
    spec = (card.payload or {}).get("secure_input")
    if not isinstance(spec, dict) or not spec:
        raise ValidationError(
            "卡片缺少密钥描述，无法保存", details={"message_id": payload.message_id}
        )

    receipts = await container.task_repo.messages_of_kind(task_uuid, "secure_input_result")
    if any((item.payload or {}).get("message_id") == str(card_uuid) for item in receipts):
        raise ConflictError(
            "这张卡片的密钥已保存过，不会再写入一次",
            details={"message_id": payload.message_id},
        )

    secret = payload.value.strip()
    if not secret:
        raise ValidationError("密钥不能为空", details={"field": "value"})

    name = str(spec.get("name") or spec.get("title") or "API key")[:128]
    fields = {
        "provider": str(spec.get("provider") or ""),
        "base_url": str(spec.get("base_url") or ""),
        "models": [str(item) for item in spec.get("models") or []],
        "wire_api": str(spec.get("wire_api") or ""),
    }
    # 同名密钥视为「重新填一次」：覆盖凭据与用途，而不是报 409 让用户去密钥库手动合并。
    existing = next(
        (row for row in await container.key_vault.list_entries() if row.name == name), None
    )
    if existing is None:
        key = await container.key_vault.create(name=name, api_key=secret, **fields)
    else:
        key = await container.key_vault.update(existing.id, api_key=secret, **fields)

    stored = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.USER.value,
                    kind="secure_input_result",
                    content=f"已通过安全输入框保存密钥「{key.name}」，密钥不会出现在对话里。",
                    payload={
                        "message_id": str(card_uuid),
                        "key_id": str(key.id),
                        "name": key.name,
                        "provider": key.provider,
                        "key_hint": key.key_hint,
                        "status": "stored",
                    },
                )
            ],
        )
    )[0]
    await container.task_stream.publish(
        task_uuid, "user_message", {"message": redacted_message(container, stored)}
    )
    logger.info(
        "安全输入已保存 task=%s message=%s key=%s provider=%s",
        task_uuid,
        card_uuid,
        key.id,
        key.provider,
    )
    return ok(
        {
            "task": task_projection(container, task),
            "key": key.to_dict(),
            "message": redacted_message(container, stored),
        }
    )


@router.get("/{task_id}/stream")
async def stream_task(
    task_id: str,
    after_seq: int = 0,
    last_event_id: str | None = Header(default=None, alias="Last-Event-ID"),
    container: Container = Depends(get_container),
) -> StreamingResponse:
    """对话增量流（SSE）：助手逐字回复 + Agent 运行事件。

    连接建立后先补 `after_seq` 之后的缓冲事件，再持续推送，15 秒静默发一次心跳。
    最终消息仍以 GET /tasks/{id}/messages 为准：断线期间漏掉的增量由客户端重拉补齐。
    """
    task_uuid = _task_id(task_id)
    await container.task_repo.get(task_uuid)
    resume_from = after_seq
    if last_event_id:
        with suppress(ValueError):
            resume_from = max(resume_from, int(last_event_id))

    async def generate() -> AsyncIterator[str]:
        async for event in container.task_stream.stream(task_uuid, after_seq=resume_from):
            yield ": keep-alive\n\n" if event is None else _format_task_sse(event.to_dict())

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/{task_id}/events")
async def poll_task_events(
    task_id: str,
    after_seq: int = 0,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """对话增量的轮询版（账号云中转只做请求-响应，不能承载 SSE 长连接）。

    与 `/stream` 共用同一份缓冲：返回 `after_seq` 之后的事件，客户端按回调逐条消费即可。
    """
    if after_seq < 0:
        raise BadRequestError("after_seq 不能为负数", details={"after_seq": after_seq})
    task_uuid = _task_id(task_id)
    await container.task_repo.get(task_uuid)
    events = container.task_stream.poll(task_uuid, after_seq=after_seq)
    return ok(
        [event.to_dict() for event in events],
        metadata={"count": len(events), "after_seq": after_seq},
    )


@router.post("/{task_id}/continue")
async def continue_task(
    task_id: str,
    payload: TaskMessageCreateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """同一对话续跑：终态对话收到新消息时，重开任务并起一轮新 Run。

    与 `/messages` 的区别：那个只让助手回话；续跑把新消息当作本轮 Agent 指令，
    让同一个对话接着执行（历史消息、项目关联与标题都保留）。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    content = payload.content.strip()
    files = resolve_uploads(container.settings, payload.attachment_ids)
    if not content and not files:
        raise ValidationError(
            "消息内容不能为空", details={"task_id": task_id, "attachments": len(files)}
        )
    if task.status not in _FINAL_STATUSES:
        raise ConflictError(
            f"对话 {task_id} 仍在进行中，直接追加消息即可，不需要续跑",
            details={"task_id": task_id, "status": task.status},
        )
    previous_status = task.status
    task = await container.task_repo.reopen(task_uuid)
    user_message = (
        await container.task_repo.add_messages(
            task_uuid,
            [MessageDraft(role=TaskMessageRole.USER.value, content=content)],
        )
    )[0]
    await container.task_stream.publish(
        task_uuid, "user_message", {"message": redacted_message(container, user_message)}
    )
    await _record_attachments(container, task_uuid, files)
    followup = content or _attachments_brief(files)
    try:
        result = await container.task_run_service.start(task, followup=followup)
    except Exception:
        # 起不来时退回原终态：用户消息保留，修好 runtime 后可以直接重试，
        # 不留下一个没有 Run 支撑的 running 僵尸。
        current = await container.task_repo.get(task_uuid)
        if current.run_id is None and current.status in {
            TaskStatus.PENDING.value,
            TaskStatus.RUNNING.value,
        }:
            await container.task_repo.set_status(task_uuid, TaskStatus(previous_status))
        raise
    return ok(
        {
            "task": task_projection(container, result.task),
            "messages": [redacted_message(container, user_message)],
        }
    )


@router.put("/{task_id}/confirmation")
async def update_confirmation(
    task_id: str,
    payload: ConfirmationUpdateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """用户修改需求确认（P0-06）：六个维度必须齐全，落库后就是执行依据。

    改完会追加一条 kind=confirmation 的用户消息——用户改了什么必须留在对话轨迹里，
    否则"执行用的是哪一版需求"事后无从解释（TC-103）。
    """
    task_uuid = _task_id(task_id)
    await _require_open_task(container, task_uuid, task_id)
    items = validate_confirmation_items([item.model_dump() for item in payload.items])
    confirmation = {"items": items, "actor": "user", "updated_at": _now_iso()}
    task = await container.task_repo.set_confirmation(task_uuid, confirmation)
    message = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.USER.value,
                    kind="confirmation",
                    content=_confirmation_text(items),
                    payload={"confirmation": confirmation},
                )
            ],
        )
    )[0]
    return ok(
        {"task": task_projection(container, task), "message": redacted_message(container, message)}
    )


@router.post("/{task_id}/decision-mode")
async def set_decision_mode(
    task_id: str,
    payload: DecisionModeRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """切换任务级决策策略（文档 §5）：auto = AI 默认方案，manual = 由我决定。"""
    task_uuid = _task_id(task_id)
    await _require_open_task(container, task_uuid, task_id)
    task = await container.task_repo.set_decision_mode(task_uuid, payload.mode)
    return ok(task_projection(container, task))


@router.post("/{task_id}/permission-mode")
async def set_permission_mode(
    task_id: str,
    payload: PermissionModeRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """切换对话级权限模式（Plan / Normal / Full）。

    任何状态都允许切换；正在执行的 Run 按启动时的模式跑完，新模式从下一轮生效
    （响应里的 `effective_from` 明确告诉客户端这一点）。切换本身不执行任何命令、
    也不改动历史消息与已完成的审批记录。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    previous = task.permission_mode or "normal"
    task = await container.task_repo.set_permission_mode(task_uuid, payload.mode)
    effective_from = "next_run" if task.status == TaskStatus.RUNNING.value else "next_message"
    result = {
        **task_projection(container, task),
        "previous_mode": previous,
        "effective_from": effective_from,
    }
    # 多端一致性：其他设备打开同一对话时靠这个事件同步胶囊状态
    await container.task_stream.publish(
        task_uuid,
        "permission_mode_changed",
        {"permission_mode": str(payload.mode), "previous_mode": previous},
    )
    logger.info(
        "task.permission_mode_changed task=%s from=%s to=%s effective_from=%s",
        task_uuid,
        previous,
        payload.mode,
        effective_from,
    )
    return ok(result)


@router.post("/{task_id}/agent")
async def switch_task_agent(
    task_id: str,
    payload: TaskAgentRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """切换执行该任务的 Agent（移动端对话页的 Agent 下拉）。

    同一任务/对话保留历史消息，后续消息改由新 Agent 承接。
    - 执行中切换：只排队（`pending_agent_id`），本轮 Run 收尾后自动生效，
      当前这一轮仍由原 Agent 跑完；响应 `queued=true`、`effective_from=next_run`。
    - 空闲/终态切换：立即生效，生成一张交接卡（`kind=handoff`）供新 Agent 接手。
    - 预检失败（Agent 不存在 / runtime 未接入）：保留原 Agent，按真实原因报错。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    _parse_uuid(payload.agent_id, field="agent_id")
    target = container.agents.get(payload.agent_id)
    if target.spec.kind == AGENT_KIND_EXTERNAL:
        raise BadRequestError(
            "外部接入的 Agent 不可通过 Flux 执行",
            details={"agent_id": payload.agent_id, "kind": target.spec.kind},
        )
    result = await container.agent_switch.switch(task, payload.agent_id)
    body = {
        **task_projection(container, result.task),
        "queued": result.queued,
        "target_agent_id": str(result.target.agent_id),
        "target_agent": result.target.name,
        "effective_from": "next_run" if result.queued else "now",
    }
    logger.info(
        "task.agent_switch task=%s target=%s queued=%s",
        task_uuid,
        result.target.agent_id,
        result.queued,
    )
    return ok(body)


@router.post("/{task_id}/model")
async def switch_task_model(
    task_id: str,
    payload: TaskModelRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """切换任务的执行模型（移动端对话页 Agent 名称旁的下拉）。

    只接受该 Agent runtime 的模型目录里的 id；正在执行时不允许切换。
    切换 Agent 时会清掉本覆盖，回到新 Agent 的默认模型。
    终态对话同样允许切换，理由与切换 Agent 一致：只影响下一次续聊的模型。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status == TaskStatus.RUNNING.value:
        raise ConflictError(
            f"任务 {task_id} 正在执行，先停止 Agent 再切换模型",
            details={"task_id": task_id, "status": task.status},
        )
    runtime = DEFAULT_RUNTIME
    if task.agent_id is not None:
        agent = container.agents.get(str(task.agent_id))
        if agent.spec.kind == AGENT_KIND_EXTERNAL:
            raise BadRequestError(
                "外部接入的 Agent 不可通过 Flux 执行",
                details={"agent_id": str(task.agent_id), "kind": agent.spec.kind},
            )
        runtime = getattr(getattr(agent, "spec", None), "runtime", DEFAULT_RUNTIME)
    model, key_id = await container.key_vault.resolve_selection(
        runtime=runtime, model=payload.model, key_id=payload.key_id
    )
    task = await container.task_repo.set_model(task_uuid, model, key_id)
    return ok(task_projection(container, task))


@router.post("/{task_id}/start")
async def start_task(
    task_id: str,
    payload: TaskStartRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """开始执行（P0-05）：把确认过的需求交给该 Agent 的 runtime 起一次 Run，任务进入 running。

    确认卡可以随请求一起传（前端"改完直接执行"），也可以留空用任务上已保存的那份。
    编排收敛在 `TaskRunService`（P2-1 §6.2）：按 `agent.config["runtime"]` 分发 DSH / CLI，
    对外响应与消息落库行为保持不变。runtime 不可用（DSH 未启用 / CLI 未接入）时不返回
    "假装开始执行"的 200。
    """
    task_uuid = _task_id(task_id)
    task = await _require_open_task(container, task_uuid, task_id)
    if task.run_id:
        raise ConflictError(
            f"任务 {task_id} 已开始执行，不能重复触发",
            details={"task_id": task_id, "run_id": task.run_id},
        )
    if task.status == TaskStatus.WAITING_FOR_USER_DECISION.value:
        raise ConflictError(
            f"任务 {task_id} 正在等待你做出决策，请先完成选择",
            details={"task_id": task_id},
        )

    result = await container.task_run_service.start(task, payload.confirmation)
    return ok(
        {
            "task": task_projection(container, result.task),
            "run": result.run,
            "message": (
                redacted_message(container, result.message) if result.message is not None else None
            ),
        }
    )


@router.post("/{task_id}/decisions")
async def raise_decision(
    task_id: str,
    payload: DecisionCreateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """登记一个决策点（文档 §5）：Agent 执行中撞上可选方案时调用。

    按任务策略分流——auto 模式当场按推荐方案拍板并留痕，manual 模式把任务挂到
    `waiting_for_user_decision`，等用户选完再继续。
    """
    task_uuid = _task_id(task_id)
    task = await _require_open_task(container, task_uuid, task_id)
    if not task.run_id:
        raise ConflictError(
            f"任务 {task_id} 尚未开始执行，此时没有执行中的决策点",
            details={"task_id": task_id},
        )
    mode = DecisionMode(task.decision_mode)
    if mode is DecisionMode.MANUAL and task.pending_decision() is not None:
        pending = task.pending_decision() or {}
        raise ConflictError(
            "已有待决策的问题尚未回答，请先完成当前决策",
            details={"decision_id": pending.get("id")},
        )

    record = build_decision(
        question=payload.question,
        options=[option.model_dump() for option in payload.options],
        context=payload.context,
        recommendation=payload.recommendation,
        mode=mode,
    )
    task = await container.task_repo.raise_decision(task_uuid, record)

    if record["status"] == DecisionStatus.PENDING.value:
        task = await container.task_repo.set_status(task_uuid, TaskStatus.WAITING_FOR_USER_DECISION)
        content = f"执行遇到需要你决定的点：{record['question']}"
        kind = "decision"
    else:
        content = f"AI 已按默认方案自行决定：{record['chosen']}（问题：{record['question']}）"
        kind = "decision_auto"
    message = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind=kind,
                    content=content,
                    payload={"decision": record},
                )
            ],
        )
    )[0]
    return ok(
        {
            "task": task_projection(container, task),
            "decision": redact_projection(container, record),
            "message": redacted_message(container, message),
        }
    )


@router.post("/{task_id}/decisions/choose")
async def choose_decision(
    task_id: str,
    payload: DecisionChooseRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """用户对挂起的决策点做选择（文档 §5 模式 B）。

    - `choose`：必须给出候选方案里的 label，任务回到 running，Agent 从决策点继续；
    - `reject`：全部候选都不接受，任务同样回到 running 并把"请重新给方案"写进对话，
      Agent 可以再问一次（TC-202/203）。
    """
    task_uuid = _task_id(task_id)
    task = await _require_open_task(container, task_uuid, task_id)
    pending = task.pending_decision()
    if pending is None:
        raise ConflictError("当前没有等待你决策的问题", details={"task_id": task_id})
    if pending.get("id") != payload.decision_id:
        raise NotFoundError(
            f"决策点 {payload.decision_id} 不是当前待决策项",
            details={"decision_id": payload.decision_id, "pending": pending.get("id")},
        )

    if payload.action == "reject":
        record = reject_decision(pending, note=payload.note)
        task, _ = await container.task_repo.resolve_decision(
            task_uuid,
            payload.decision_id,
            status=DecisionStatus.REJECTED,
            chosen=None,
            note=payload.note,
            resolved_at=str(record["resolved_at"]),
        )
        content = f"你拒绝了全部候选方案：{payload.note or '请 Agent 重新给出方案'}"
    else:
        if not payload.option:
            raise BadRequestError("action=choose 时必须给出 option", details={"field": "option"})
        record = resolve_decision(pending, option=payload.option, note=payload.note)
        task, _ = await container.task_repo.resolve_decision(
            task_uuid,
            payload.decision_id,
            status=DecisionStatus.RESOLVED,
            chosen=payload.option,
            note=payload.note,
            resolved_at=str(record["resolved_at"]),
        )
        content = f"你的选择：{payload.option}" + (f"（{payload.note}）" if payload.note else "")

    task = await container.task_repo.set_status(task_uuid, TaskStatus.RUNNING)
    message = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.USER.value,
                    kind="decision_result",
                    content=content,
                    payload={"decision": record},
                )
            ],
        )
    )[0]
    return ok(
        {
            "task": task_projection(container, task),
            "decision": redact_projection(container, record),
            "message": redacted_message(container, message),
        }
    )


@router.post("/{task_id}/cancel")
async def cancel_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，无法取消",
            details={"task_id": task_id, "status": task.status},
        )
    # 队列是进程内的易失结构，数据库才是任务状态的权威：
    # 任务在库中但不在队列里（进程重启过、或已被 next() 取出）时取消仍必须成功。
    with contextlib.suppress(NotFoundError):
        container.scheduler.cancel(str(task.id))
    if task.run_id:
        # 取消任务要连它正在跑的 DSH Run 一起停——留一个还在改文件的 Agent 与"已取消"是矛盾的。
        # P2-15：以 Run 的终态为准映射任务状态——进程树确认清理干净才是 cancelled，
        # 清理失败（FAILED）或超时（TIMEOUT）都按 failed 记录，不粉饰成"取消成功"。
        run_status = await _cancel_run(container, task.run_id)
        if run_status is DshRunStatus.COMPLETED:
            # 取消来晚了：Run 已成功结束（竞态窗口：Run 落终态、任务尚未被桥接收尾）。
            # 任务按 Run 的真实结局收尾，绝不把一次成功的执行改判成失败。
            task = await container.task_repo.set_status(task_uuid, TaskStatus.COMPLETED)
            return ok(task_projection(container, task))
        if run_status is not None and run_status is not DshRunStatus.CANCELLED:
            task = await container.task_repo.set_status(task_uuid, TaskStatus.FAILED)
            return ok(task_projection(container, task))
    task = await container.task_repo.set_status(task_uuid, TaskStatus.CANCELLED)
    return ok(task_projection(container, task))


@router.delete("/{task_id}")
async def delete_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """彻底删除一段对话（任务）：消息与运行记录一并清除，不做软删除。

    正在执行（running / waiting_for_user_decision）的对话必须先停止：
    删掉一个 Agent 仍在改文件的任务是矛盾的。
    """
    task_uuid = _task_id(task_id)
    task = await container.task_repo.get(task_uuid)
    if task.status in {TaskStatus.RUNNING.value, TaskStatus.WAITING_FOR_USER_DECISION.value}:
        raise ConflictError(
            f"对话 {task_id} 正在执行，先停止后再删除",
            details={"task_id": task_id, "status": task.status},
        )
    # 队列是进程内的易失结构：pending 任务可能还排在队列里，删之前先摘掉，
    # 否则调度器之后取到一条已经不存在的任务。
    with contextlib.suppress(NotFoundError):
        container.scheduler.cancel(str(task.id))
    messages, runs = await container.task_repo.delete(task_uuid)
    # 工作记忆卡与会话绑定同样随任务清除：任务没了，旧会话也不能被继承。
    cards = await container.task_memory.delete_for_task(task_uuid)
    sessions = await container.runtime_sessions.delete_for_task(task_uuid)
    logger.info(
        "删除对话 %s 完成：消息=%d 运行=%d 记忆卡=%d 会话绑定=%d",
        task_id,
        messages,
        runs,
        cards,
        sessions,
    )
    return ok({"id": task_id, "deleted": True, "messages": messages, "runs": runs})


async def _cancel_run(container: Container, run_id: str) -> DshRunStatus | None:
    """取消任务所属的 DSH Run；Run 不存在时返回 None（历史任务没有 Run，不算失败）。"""
    try:
        return (await container.dsh.cancel(run_id)).status
    except NotFoundError:
        return None
