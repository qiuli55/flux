"""Task API（主规格 §12.4）。

任务状态落 tasks 表（本增量起），进程内的调度队列是易失结构、不承担状态权威。
对话消息落 task_messages 表（任务执行中心增量）：刷新页面、重启后端都不丢；
助手回复由平台经 ModelRouter 真实调用生成（见 core/task_engine/assistant.py）。
"""

from __future__ import annotations

import contextlib
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.event.bus import Events
from flux.core.model_gateway.base import ChatMessage
from flux.core.task_engine.assistant import validate_confirmation_items
from flux.core.task_engine.decisions import build_decision, reject_decision, resolve_decision
from flux.core.task_engine.repository import MessageDraft
from flux.enums import DecisionMode, DecisionStatus, DshRunStatus, TaskMessageRole, TaskStatus
from flux.errors import AIOSError, BadRequestError, ConflictError, NotFoundError
from flux.models.task import Task
from flux.schemas.api import (
    ConfirmationUpdateRequest,
    DecisionChooseRequest,
    DecisionCreateRequest,
    DecisionModeRequest,
    TaskCreateRequest,
    TaskMessageCreateRequest,
    TaskStartRequest,
)

router = APIRouter(prefix="/tasks", tags=["tasks"])

_FINAL_STATUSES = {TaskStatus.COMPLETED.value, TaskStatus.CANCELLED.value, TaskStatus.FAILED.value}

#: 一次对话请求带上的历史条数上限（上下文成本有界；够模型理解前文即可）
_HISTORY_LIMIT = 50


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


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


async def _latest_plan(container: Container, task_uuid: uuid.UUID) -> list[str]:
    """取最近一轮助手给出的执行计划（开始执行时一并交给 Agent）。"""
    messages, _ = await container.task_repo.list_messages(task_uuid, limit=_HISTORY_LIMIT)
    for message in reversed(messages):
        steps = (message.payload or {}).get("steps")
        if message.role == TaskMessageRole.ASSISTANT.value and isinstance(steps, list) and steps:
            return [str(step) for step in steps]
    return []


def _execution_instruction(
    task: Task, confirmation: dict[str, object] | None, steps: list[str]
) -> str:
    """拼出交给 DSH 的执行指令：任务描述 + 用户确认过的需求 + 计划 + 能力边界。"""
    lines = [task.description.strip()]
    items = (confirmation or {}).get("items")
    if isinstance(items, list) and items:
        lines += ["", "## 需求确认（用户已确认，以此为准）"]
        lines += [f"- {item['label']}：{item['value']}" for item in items]
    if steps:
        lines += ["", "## 执行计划"]
        lines += [f"{index + 1}. {step}" for index, step in enumerate(steps)]
    lines += [
        "",
        "## 平台身份（调用 Flux MCP 时带上）",
        f"- task_id：{task.id}",
        f"- project_id：{task.project_id or '未关联'}",
        "- 开工先调 `context.get` 并带上 task_id：它会返回项目 Brain（项目结构、技术栈、"
        "入口文件）与近期提案；不确定文件路径时以它为准，再用 workspace.read 核对内容。",
    ]
    lines += [
        "",
        "## 执行约束",
        "- 只能通过 Flux MCP 读取项目上下文并把改动提交为提案；"
        "不要尝试直接写文件、执行 shell 或读取密钥。",
        "- 改动一律进入人工审核队列，落盘由人审通过后经 Apply Engine 执行，并在落盘时跑项目测试。",
    ]
    return "\n".join(lines)


def _task_dict(task: Task) -> dict[str, object]:
    """任务响应体：M0 冻结的 7 个键 + created_at（任务执行中心展示真实创建时间）。"""
    return task.to_dict()


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
    if agent_id is not None:
        # Agent 注册表是 Agent 存在性的唯一权威：API 边界 fail-closed。
        container.agents.get(payload.agent_id)

    task = await container.task_repo.create(
        task_id=uuid.uuid4(),
        description=payload.description,
        priority=payload.priority,
        project_id=project_id,
        agent_id=agent_id,
        decision_mode=payload.decision_mode,
    )
    container.scheduler.submit(
        str(task.id),
        task.description,
        priority=task.priority,
        agent_id=str(agent_id) if agent_id is not None else None,
    )
    result = _task_dict(task)
    await container.bus.publish(Events.TASK_CREATED, result)
    return ok(result)


@router.get("")
async def list_tasks(
    project_id: str | None = None,
    status: str | None = None,
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
    if limit < 1 or limit > 200:
        raise BadRequestError("limit 必须在 1~200 之间", details={"limit": limit})
    tasks = await container.task_repo.list(project_id=project_uuid, status=wanted, limit=limit)
    return ok([_task_dict(t) for t in tasks], metadata={"count": len(tasks)})


@router.get("/{task_id}")
async def get_task(
    task_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    task = await container.task_repo.get(_task_id(task_id))
    return ok(_task_dict(task))


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
        [m.to_dict() for m in messages],
        metadata={"count": len(messages), "has_more": has_more},
    )


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
    if task.status in _FINAL_STATUSES:
        raise ConflictError(
            f"任务 {task_id} 已处于终态 {task.status}，不能再追加消息",
            details={"task_id": task_id, "status": task.status},
        )

    user_message = (
        await container.task_repo.add_messages(
            task_uuid, [MessageDraft(role=TaskMessageRole.USER.value, content=payload.content)]
        )
    )[0]

    history, _ = await container.task_repo.list_messages(task_uuid, limit=_HISTORY_LIMIT)
    context = (
        await container.brain.context(task.project_id) if task.project_id is not None else None
    )
    turn = await container.assistant.respond(
        description=task.description,
        message=payload.content,
        history=[
            ChatMessage(role=m.role, content=m.content)
            for m in history
            if m.kind == "text" and m.id != user_message.id
        ],
        context=context,
    )
    reply = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    content=turn.reply,
                    payload=turn.payload(),
                )
            ],
        )
    )[0]

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
        {"task": _task_dict(task), "messages": [user_message.to_dict(), reply.to_dict()]},
        metadata={"provider": turn.provider, "model": turn.model},
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
    return ok({"task": _task_dict(task), "message": message.to_dict()})


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
    return ok(_task_dict(task))


@router.post("/{task_id}/start")
async def start_task(
    task_id: str,
    payload: TaskStartRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """开始执行（P0-05）：把确认过的需求交给 DSH 起一次 Run，任务进入 running。

    确认卡可以随请求一起传（前端"改完直接执行"），也可以留空用任务上已保存的那份。
    DSH 未启用时起 Run 会抛 503 configuration_error——不在"假装开始执行"的假状态下返回 200。
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

    confirmation: dict[str, object] | None = task.confirmation
    if payload.confirmation is not None:
        items = validate_confirmation_items([item.model_dump() for item in payload.confirmation])
        confirmation = {"items": items, "actor": "user", "updated_at": _now_iso()}
        task = await container.task_repo.set_confirmation(task_uuid, confirmation)

    steps = await _latest_plan(container, task_uuid)
    instruction = _execution_instruction(task, confirmation, steps)
    # 先登记 run_id 与 running，再真正起 Run：倒过来的话，Run 结束事件可能先于登记到达，
    # 任务就永远收不到自己的终态（P0-05）。
    run_id = uuid.uuid4().hex
    previous_status = task.status
    task = await container.task_repo.set_run_id(task_uuid, run_id)
    task = await container.task_repo.set_status(task_uuid, TaskStatus.RUNNING)
    try:
        run = await container.dsh.start_run(
            instruction, session_id=f"flux-task-{task.id}", run_id=run_id, task_id=task.id
        )
    except AIOSError:
        # 起 Run 失败（未启用 / 令牌签发失败 / patch 写不出）时撤回预登记：
        # 否则任务会卡在"看起来在跑"，用户既看不到 Agent 也再也点不动「开始执行」。
        await container.task_repo.set_run_id(task_uuid, None)
        task = await container.task_repo.set_status(task_uuid, TaskStatus(previous_status))
        raise
    message = (
        await container.task_repo.add_messages(
            task_uuid,
            [
                MessageDraft(
                    role=TaskMessageRole.ASSISTANT.value,
                    kind="run_started",
                    content=(
                        f"已开始执行（DSH Run {run.run_id}），"
                        "Agent 的改动会以提案形式进入人工审核队列。"
                    ),
                    payload={"run_id": run.run_id, "instruction": instruction},
                )
            ],
        )
    )[0]
    return ok({"task": _task_dict(task), "run": run.to_dict(), "message": message.to_dict()})


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
    return ok({"task": _task_dict(task), "decision": record, "message": message.to_dict()})


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
    return ok({"task": _task_dict(task), "decision": record, "message": message.to_dict()})


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
        if run_status is not None and run_status is not DshRunStatus.CANCELLED:
            task = await container.task_repo.set_status(task_uuid, TaskStatus.FAILED)
            return ok(_task_dict(task))
    task = await container.task_repo.set_status(task_uuid, TaskStatus.CANCELLED)
    return ok(_task_dict(task))


async def _cancel_run(container: Container, run_id: str) -> DshRunStatus | None:
    """取消任务所属的 DSH Run；Run 不存在时返回 None（历史任务没有 Run，不算失败）。"""
    try:
        return (await container.dsh.cancel(run_id)).status
    except NotFoundError:
        return None
