"""Virtual Workspace 服务（主规格 §7；实施计划 ④⑥⑦）。

权威存储是 virtual_changes 表，服务层负责：
- 把 Developer Agent 的 `CodeChangeSet` 落成一条条 Proposal（含 original_hash 与 unified diff）；
- 人工审查动作（accept / reject）与状态跃迁；
- Apply：把落盘交给 Apply Engine（⑦），自己只负责状态与审计字段。

**落盘的唯一入口是 ApplyEngine**，API / Agent / UI 都不允许自己写用户文件。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Mapping
from pathlib import Path

from flux.core.agent_runtime.developer import CodeChangeSet
from flux.core.event.bus import EventBus, Events
from flux.core.virtual_workspace.apply_engine import ApplyEngine
from flux.core.virtual_workspace.diff_engine import compute_file_diff, content_hash
from flux.core.virtual_workspace.repository import ProposalRepository
from flux.enums import VirtualChangeStatus
from flux.errors import ConflictError, InvalidTransitionError, ValidationError
from flux.logging import get_logger
from flux.models.workspace import VirtualChange

logger = get_logger(__name__)

# apply_error 里保留的测试输出上限。测试失败时最该被看到的是「哪个用例挂了、挂在哪行」，
# 这些都在 pytest 输出的尾部；全文可能上千行，截太长反而读不出重点。
APPLY_ERROR_OUTPUT_CHARS = 4000

# 允许的审查状态跃迁（§7.2 文件状态机 + 实施计划 §5 的 FAILED）
ALLOWED: dict[VirtualChangeStatus, frozenset[VirtualChangeStatus]] = {
    VirtualChangeStatus.PENDING: frozenset(
        {VirtualChangeStatus.ACCEPTED, VirtualChangeStatus.REJECTED}
    ),
    VirtualChangeStatus.ACCEPTED: frozenset(
        {
            VirtualChangeStatus.APPLIED,
            VirtualChangeStatus.REJECTED,
            VirtualChangeStatus.FAILED,
        }
    ),
    VirtualChangeStatus.REJECTED: frozenset(),
    VirtualChangeStatus.APPLIED: frozenset(),
    VirtualChangeStatus.FAILED: frozenset(),
}


class VirtualWorkspaceService:
    def __init__(
        self,
        repository: ProposalRepository,
        bus: EventBus | None = None,
        apply_engine: ApplyEngine | None = None,
    ) -> None:
        self._repo = repository
        self._bus = bus
        # 未注入引擎时用默认配置（workspace_root 为空即"未配置"，Apply 会明确拒绝）
        self._engine = apply_engine or ApplyEngine()

    # --- 提案 ---

    async def propose(
        self,
        *,
        file_path: str,
        original_content: str,
        proposed_content: str,
        project_id: str | uuid.UUID | None = None,
        task_id: str | uuid.UUID | None = None,
        agent_source: str | None = None,
        reason: str | None = None,
        summary: str | None = None,
    ) -> VirtualChange:
        """落一条提案。内容与原文一致时拒绝——没有可审阅的改动就不该占用审核队列。"""
        diff = compute_file_diff(file_path, original_content, proposed_content)
        if not diff.changed:
            raise ValidationError(
                f"文件 {file_path} 的提案内容与原文一致，没有可审阅的改动",
                details={"file_path": file_path},
            )
        change = await self._repo.create(
            file_path=file_path,
            original_content=original_content,
            proposed_content=proposed_content,
            original_hash=content_hash(original_content),
            diff=diff.unified,
            added_lines=diff.added_lines,
            removed_lines=diff.removed_lines,
            hunks=diff.hunks,
            project_id=_as_uuid(project_id),
            task_id=_as_uuid(task_id),
            agent_source=agent_source,
            reason=reason,
            summary=summary,
        )
        await self._publish(change, previous=None)
        return change

    async def propose_changes(
        self,
        change_set: CodeChangeSet,
        *,
        project_id: str | uuid.UUID | None = None,
        task_id: str | uuid.UUID | None = None,
        agent_source: str | None = None,
        original_files: Mapping[str, str] | None = None,
    ) -> list[VirtualChange]:
        """把一个 CodeChangeSet 落成多条 Proposal（每个文件一条）。

        `original_files` 是各文件当前内容；不在其中或内容为空的路径视为新建文件，
        original_content 记为空串（Apply 时据此判断"文件本不该存在"）。
        """
        originals = dict(original_files or {})
        created: list[VirtualChange] = []
        for change in change_set.changes:
            original = originals.get(change.path, "")
            if not compute_file_diff(change.path, original, change.content).changed:
                logger.info("跳过无实际改动的文件：%s", change.path)
                continue
            created.append(
                await self.propose(
                    file_path=change.path,
                    original_content=original,
                    proposed_content=change.content,
                    project_id=project_id,
                    task_id=task_id,
                    agent_source=agent_source,
                    reason=change.reason or None,
                    summary=change_set.summary or None,
                )
            )
        if not created:
            raise ValidationError("提案没有任何有效改动", details={"files": list(change_set.paths)})
        return created

    # --- 查询 ---

    async def get(self, change_id: str | uuid.UUID) -> VirtualChange:
        return await self._repo.get(change_id)

    async def list(
        self,
        *,
        project_id: str | uuid.UUID | None = None,
        task_id: str | uuid.UUID | None = None,
        status: str | VirtualChangeStatus | None = None,
    ) -> list[VirtualChange]:
        return await self._repo.list(project_id=project_id, task_id=task_id, status=status)

    # --- 人工审查 ---

    async def accept(self, change_id: str | uuid.UUID) -> VirtualChange:
        return await self._transition(change_id, VirtualChangeStatus.ACCEPTED)

    async def reject(self, change_id: str | uuid.UUID, reason: str | None = None) -> VirtualChange:
        """拒绝一条提案。reason 由人工填写，进事件供审计，不改提案内容。"""
        return await self._transition(
            change_id, VirtualChangeStatus.REJECTED, extra={"reason": reason} if reason else None
        )

    async def apply(
        self,
        change_id: str | uuid.UUID,
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> VirtualChange:
        """应用改动：pending 自动先转 accepted，再交给 Apply Engine 真正落盘（§7.6）。

        调用本接口即视为人工批准（§7.5）。落盘是同步 IO + 子进程，放进线程执行，
        避免阻塞事件循环。失败时状态落 failed 并保留原因，异常继续上抛——
        不静默兜底（fail-closed）。
        """
        change = await self._repo.get(change_id)
        if change.status != VirtualChangeStatus.ACCEPTED.value:
            # pending → accepted；已是终态则在这里按非法跃迁拒绝
            change = await self._transition(change.id, VirtualChangeStatus.ACCEPTED)

        try:
            outcome = await asyncio.to_thread(
                self._engine.apply,
                change,
                workspace_root=workspace_root,
                run_tests=run_tests,
            )
        except (ConflictError, ValidationError):
            # 预检未通过（文件被用户改过 / 路径非法 / 未配置工作区根目录）：
            # 改动本身没错，是当前环境或提案已过期，状态留在 accepted 等人工重新决策
            raise
        except Exception as exc:
            failed = await self._repo.set_apply_result(
                change.id,
                status=VirtualChangeStatus.FAILED,
                apply_error=_failure_reason(exc),
            )
            await self._publish(
                failed,
                previous=VirtualChangeStatus.ACCEPTED,
                extra={"error": str(exc)},
            )
            raise

        applied = await self._repo.set_apply_result(
            change.id,
            status=VirtualChangeStatus.APPLIED,
            backup_path=outcome.backup_path,
        )
        await self._publish(applied, previous=VirtualChangeStatus.ACCEPTED)
        return applied

    async def mark_failed(self, change_id: str | uuid.UUID) -> VirtualChange:
        """把一条已批准的提案手动置为 failed（保留给人工/运维的显式出口）。

        正常路径下 failed 由 apply 在落盘或测试失败时写入（§7.6），这个方法让"已批准但
        决定放弃"的提案也能走到终态，而不是永久停留在 accepted。
        """
        return await self._transition(change_id, VirtualChangeStatus.FAILED)

    async def _transition(
        self,
        change_id: str | uuid.UUID,
        target: VirtualChangeStatus,
        *,
        extra: Mapping[str, object] | None = None,
    ) -> VirtualChange:
        change = await self._repo.get(change_id)
        current = VirtualChangeStatus(change.status)
        if target not in ALLOWED[current]:
            raise InvalidTransitionError(
                f"非法状态跃迁：{current} → {target}",
                details={"from": str(current), "to": str(target), "change_id": str(change_id)},
            )
        updated = await self._repo.set_status(change.id, target)
        await self._publish(updated, previous=current, extra=extra)
        return updated

    async def _publish(
        self,
        change: VirtualChange,
        *,
        previous: VirtualChangeStatus | None,
        extra: Mapping[str, object] | None = None,
    ) -> None:
        if self._bus is None:
            return
        payload: dict[str, object] = {
            "change_id": str(change.id),
            "file_path": change.file_path,
            "from": str(previous) if previous is not None else None,
            "to": change.status,
        }
        if extra:
            payload.update(extra)
        await self._bus.publish(Events.WORKSPACE_CHANGED, payload)


def _failure_reason(exc: Exception) -> str:
    """拼出落到 apply_error 的失败原因（§7.6 要求"失败必留痕"）。

    只写异常消息的话，用户看到的是"Apply 后测试未通过（exit=1）"，既不知道哪个用例挂了、
    也不知道下一轮该改什么——测试输出才是修复的依据，所以从 details 里取出来一并记下。
    """
    message = str(exc)
    details = getattr(exc, "details", None)
    test = details.get("test") if isinstance(details, Mapping) else None
    output = test.get("output") if isinstance(test, Mapping) else None
    if not isinstance(output, str) or not output.strip():
        return message
    return f"{message}\n\n{output.strip()[-APPLY_ERROR_OUTPUT_CHARS:]}"


def _as_uuid(value: str | uuid.UUID | None) -> uuid.UUID | None:
    if value is None or isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise ValidationError(
            f"project_id / task_id 必须是合法 UUID：{value}", details={"value": str(value)}
        ) from exc
