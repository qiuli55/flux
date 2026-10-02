"""Virtual Workspace 服务（主规格 §7；实施计划 ④⑥⑦）。

权威存储是 virtual_changes 表，服务层负责：
- 把 agent 经 MCP `proposal.create` 提交的 `CodeChangeSet` 落成一条条 Proposal
  （含 original_hash 与 unified diff）；
- 人工审查动作（accept / reject）与状态跃迁；
- Apply：把落盘交给 Apply Engine（⑦），自己只负责状态与审计字段。

**落盘的唯一入口是 ApplyEngine**，API / Agent / UI 都不允许自己写用户文件。
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta, timezone
from pathlib import Path

from flux.core.event.bus import EventBus, Events
from flux.core.virtual_workspace.apply_engine import ApplyEngine
from flux.core.virtual_workspace.diff_engine import compute_file_diff, content_hash
from flux.core.virtual_workspace.proposal_parser import CodeChangeSet
from flux.core.virtual_workspace.repository import ProposalRepository
from flux.enums import VirtualChangeStatus
from flux.errors import (
    ConflictError,
    InvalidTransitionError,
    NotFoundError,
    ProposalExpiredError,
    ValidationError,
)
from flux.logging import get_logger
from flux.models.workspace import VirtualChange

logger = get_logger(__name__)

# apply_error 里保留的测试输出上限。测试失败时最该被看到的是「哪个用例挂了、挂在哪行」，
# 这些都在 pytest 输出的尾部；全文可能上千行，截太长反而读不出重点。
APPLY_ERROR_OUTPUT_CHARS = 4000

#: 允许的审查状态跃迁（§7.2 文件状态机 + 实施计划 §5 的 FAILED + P0-02 的 EXPIRED）。
#: EXPIRED 是终态：过期/被取代的提案不能再被批准或落盘，只能重新生成提案。
ALLOWED: dict[VirtualChangeStatus, frozenset[VirtualChangeStatus]] = {
    VirtualChangeStatus.PENDING: frozenset(
        {
            VirtualChangeStatus.ACCEPTED,
            VirtualChangeStatus.REJECTED,
            VirtualChangeStatus.EXPIRED,
        }
    ),
    VirtualChangeStatus.ACCEPTED: frozenset(
        {
            VirtualChangeStatus.APPLIED,
            VirtualChangeStatus.REJECTED,
            VirtualChangeStatus.FAILED,
            VirtualChangeStatus.EXPIRED,
        }
    ),
    VirtualChangeStatus.REJECTED: frozenset(),
    VirtualChangeStatus.APPLIED: frozenset(),
    VirtualChangeStatus.FAILED: frozenset(),
    VirtualChangeStatus.EXPIRED: frozenset(),
}

#: 提案失效原因（写入 expired_reason，供 UI 解释"为什么这条不能再审"）
EXPIRE_REASON_TIMEOUT = "超过审核有效期，提案已失效"
EXPIRE_REASON_SUPERSEDED = "同一文件已有更新的提案，本条被取代"


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _as_utc(value: datetime) -> datetime:
    """把库里读出的时间归一到带时区的 UTC。

    SQLite 不保存时区，写进去的 aware datetime 读出来会丢掉 tzinfo；PostgreSQL 则保留。
    只做一次归一，过期判断在任何数据库上都一致。
    """
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


class VirtualWorkspaceService:
    def __init__(
        self,
        repository: ProposalRepository,
        bus: EventBus | None = None,
        apply_engine: ApplyEngine | None = None,
        proposal_ttl_seconds: int = 0,
    ) -> None:
        self._repo = repository
        self._bus = bus
        # 未注入引擎时用默认配置（workspace_root 为空即"未配置"，Apply 会明确拒绝）
        self._engine = apply_engine or ApplyEngine()
        # 审核有效期（秒）；<= 0 表示不启用超时失效
        self._ttl_seconds = proposal_ttl_seconds

    # --- 提案 ---

    async def propose(
        self,
        *,
        file_path: str,
        original_content: str,
        proposed_content: str,
        project_id: str | uuid.UUID | None = None,
        task_id: str | uuid.UUID | None = None,
        group_id: str | uuid.UUID | None = None,
        agent_source: str | None = None,
        reason: str | None = None,
        summary: str | None = None,
        ttl_seconds: int | None = None,
    ) -> VirtualChange:
        """落一条提案。内容与原文一致时拒绝——没有可审阅的改动就不该占用审核队列。

        新提案落地后，同一文件在相同项目/任务范围内的旧 pending 提案会被置为 expired：
        审核队列里不该同时挂着两条改同一文件、谁也不该被落盘的提案（P0-02 状态一致性）。
        """
        diff = compute_file_diff(file_path, original_content, proposed_content)
        if not diff.changed:
            raise ValidationError(
                f"文件 {file_path} 的提案内容与原文一致，没有可审阅的改动",
                details={"file_path": file_path},
            )
        project_key = _as_uuid(project_id)
        task_key = _as_uuid(task_id)
        change = await self._repo.create(
            file_path=file_path,
            original_content=original_content,
            proposed_content=proposed_content,
            original_hash=content_hash(original_content),
            diff=diff.unified,
            added_lines=diff.added_lines,
            removed_lines=diff.removed_lines,
            hunks=diff.hunks,
            project_id=project_key,
            task_id=task_key,
            group_id=_as_uuid(group_id),
            agent_source=agent_source,
            reason=reason,
            summary=summary,
            expires_at=self._deadline(ttl_seconds),
        )
        await self._publish(change, previous=None)
        await self._supersede_pending_for_file(change)
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

        同一次提交的所有提案共享一个 group_id，可整组批准 / 拒绝 / 落盘（P0-02）。
        """
        originals = dict(original_files or {})
        # 先算出真正有改动的文件，避免"全部文件都没改动"时留下一个空 group_id
        effective = [
            change
            for change in change_set.changes
            if compute_file_diff(
                change.path, originals.get(change.path, ""), change.content
            ).changed
        ]
        if not effective:
            raise ValidationError("提案没有任何有效改动", details={"files": list(change_set.paths)})
        group_id = uuid.uuid4()
        created: list[VirtualChange] = []
        for change in effective:
            original = originals.get(change.path, "")
            created.append(
                await self.propose(
                    file_path=change.path,
                    original_content=original,
                    proposed_content=change.content,
                    project_id=project_id,
                    task_id=task_id,
                    group_id=group_id,
                    agent_source=agent_source,
                    reason=change.reason or None,
                    summary=change_set.summary or None,
                )
            )
        return created

    # --- 查询 ---

    async def get(self, change_id: str | uuid.UUID) -> VirtualChange:
        return await self._repo.get(change_id)

    async def list(
        self,
        *,
        project_id: str | uuid.UUID | None = None,
        task_id: str | uuid.UUID | None = None,
        group_id: str | uuid.UUID | None = None,
        file_path: str | None = None,
        status: str | VirtualChangeStatus | None = None,
    ) -> list[VirtualChange]:
        return await self._repo.list(
            project_id=project_id,
            task_id=task_id,
            group_id=group_id,
            file_path=file_path,
            status=status,
        )

    async def list_group(self, group_id: str | uuid.UUID) -> list[VirtualChange]:
        """一次提交（一个 group_id）下的全部提案。"""
        return await self._repo.list(group_id=group_id)

    # --- 人工审查 ---

    async def accept(self, change_id: str | uuid.UUID) -> VirtualChange:
        return await self._transition(change_id, VirtualChangeStatus.ACCEPTED)

    async def reject(self, change_id: str | uuid.UUID, reason: str | None = None) -> VirtualChange:
        """拒绝一条提案。reason 由人工填写，进事件供审计，不改提案内容。"""
        return await self._transition(
            change_id, VirtualChangeStatus.REJECTED, extra={"reason": reason} if reason else None
        )

    # --- 批量人工审查（P0-03：先全体校验、再统一执行，不留半状态）---

    async def accept_many(self, change_ids: Sequence[str | uuid.UUID]) -> list[VirtualChange]:
        """整批批准：全部 ID 存在、全部跃迁合法才动手，否则一个都不动。"""
        return await self._transition_many(change_ids, VirtualChangeStatus.ACCEPTED)

    async def reject_many(
        self, change_ids: Sequence[str | uuid.UUID], reason: str | None = None
    ) -> list[VirtualChange]:
        return await self._transition_many(
            change_ids,
            VirtualChangeStatus.REJECTED,
            extra={"reason": reason} if reason else None,
        )

    async def apply(
        self,
        change_id: str | uuid.UUID,
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> VirtualChange:
        """应用单条提案（等价于只有一条的原子批次）。"""
        return (
            await self.apply_many((change_id,), workspace_root=workspace_root, run_tests=run_tests)
        )[0]

    async def apply_many(
        self,
        change_ids: Sequence[str | uuid.UUID],
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> list[VirtualChange]:
        """原子批量应用（P0-03）：要么全部落盘成功、要么全部恢复并标记失败。

        pending 自动先转 accepted（调用即人工批准，§7.5）；状态预检在动任何一条之前
        对整批做完。落盘是同步 IO + 子进程，放进线程执行，避免阻塞事件循环。
        失败时状态落 failed 并保留原因，异常继续上抛——不静默兜底（fail-closed）。
        """
        changes = await self._load_many(change_ids, action="apply")
        # 过期预检：已过期的整批先落 expired 终态再报错，不允许落盘旧提案
        await self._guard_expired(changes)
        # 状态预检：整批都在可批准状态才继续（避免"前两条已批准、第三条才发现非法"）
        for change in changes:
            if VirtualChangeStatus(change.status) is VirtualChangeStatus.ACCEPTED:
                continue
            self._assert_transition(change, VirtualChangeStatus.ACCEPTED)
        accepted: list[VirtualChange] = []
        for change in changes:
            if VirtualChangeStatus(change.status) is not VirtualChangeStatus.ACCEPTED:
                change = await self._transition(change.id, VirtualChangeStatus.ACCEPTED)
            accepted.append(change)

        try:
            outcomes = await asyncio.to_thread(
                self._engine.apply_many,
                accepted,
                workspace_root=workspace_root,
                run_tests=run_tests,
            )
        except (ConflictError, ValidationError):
            # 预检未通过（文件被用户改过 / 路径非法 / 未配置工作区根目录）：
            # 改动本身没错，是当前环境或提案已过期，状态留在 accepted 等人工重新决策
            raise
        except Exception as exc:
            reason = _failure_reason(exc)
            for change in accepted:
                failed = await self._repo.set_apply_result(
                    change.id,
                    status=VirtualChangeStatus.FAILED,
                    apply_error=reason,
                )
                await self._publish(
                    failed,
                    previous=VirtualChangeStatus.ACCEPTED,
                    extra={"error": str(exc)},
                )
            raise

        applied: list[VirtualChange] = []
        for change, outcome in zip(accepted, outcomes, strict=True):
            updated = await self._repo.set_apply_result(
                change.id,
                status=VirtualChangeStatus.APPLIED,
                backup_path=outcome.backup_path,
            )
            await self._publish(updated, previous=VirtualChangeStatus.ACCEPTED)
            applied.append(updated)
        return applied

    # --- 整组审核（P0-02：一次提交的多文件一起批准/拒绝/落盘）---

    async def accept_group(self, group_id: str | uuid.UUID) -> list[VirtualChange]:
        return await self.accept_many(await self._ids_of_group(group_id, action="accept"))

    async def reject_group(
        self, group_id: str | uuid.UUID, reason: str | None = None
    ) -> list[VirtualChange]:
        return await self.reject_many(
            await self._ids_of_group(group_id, action="reject"), reason=reason
        )

    async def apply_group(
        self,
        group_id: str | uuid.UUID,
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> list[VirtualChange]:
        """整组原子落盘：与 apply_many 同语义，只是入口按 group_id 取全体提案。"""
        return await self.apply_many(
            await self._ids_of_group(group_id, action="apply"),
            workspace_root=workspace_root,
            run_tests=run_tests,
        )

    # --- 失效（P0-02：提案过期 / 被取代）---

    async def expire(self, change_id: str | uuid.UUID, reason: str | None = None) -> VirtualChange:
        """显式让一条提案失效（人工/运维出口，与 mark_failed 同一性质）。"""
        return await self._transition(
            change_id,
            VirtualChangeStatus.EXPIRED,
            extra={"reason": reason or EXPIRE_REASON_TIMEOUT},
        )

    async def expire_many(
        self, change_ids: Sequence[str | uuid.UUID], reason: str | None = None
    ) -> list[VirtualChange]:
        return await self._transition_many(
            change_ids,
            VirtualChangeStatus.EXPIRED,
            extra={"reason": reason or EXPIRE_REASON_TIMEOUT},
        )

    async def expire_stale(self) -> list[VirtualChange]:
        """把超过审核有效期的提案统一置为 expired（幂等，可被定时任务/API 调用）。

        时间比较在 Python 侧完成：SQLite 不保存时区，只有把库里读出的时间归一后
        才能得到与 Postgres 一致的结果（见 _as_utc）。
        """
        candidates = await self._repo.list_expirable()
        stale = [c for c in candidates if self._is_stale(c)]
        if not stale:
            return []
        updated = await self._repo.mark_expired([c.id for c in stale], reason=EXPIRE_REASON_TIMEOUT)
        for change, previous in zip(updated, stale, strict=True):
            await self._publish(
                change,
                previous=VirtualChangeStatus(previous.status),
                extra={"reason": EXPIRE_REASON_TIMEOUT},
            )
        return updated

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
        # 目标就是 expired 时不做过期拦截（否则"把已过期的提案标记为过期"会互相打架）
        if target is not VirtualChangeStatus.EXPIRED:
            await self._guard_expired((change,))
        current = VirtualChangeStatus(change.status)
        self._assert_transition(change, target)
        updated = await self._repo.set_status(change.id, target)
        await self._publish(updated, previous=current, extra=extra)
        return updated

    async def _transition_many(
        self,
        change_ids: Sequence[str | uuid.UUID],
        target: VirtualChangeStatus,
        *,
        extra: Mapping[str, object] | None = None,
    ) -> list[VirtualChange]:
        """整批状态跃迁：先确认每一条都合法，再统一执行（P0-03 的"不半途而废"）。"""
        changes = await self._load_many(change_ids, action=str(target))
        if target is not VirtualChangeStatus.EXPIRED:
            await self._guard_expired(changes)
        for change in changes:
            self._assert_transition(change, target)
        results: list[VirtualChange] = []
        for change in changes:
            current = VirtualChangeStatus(change.status)
            updated = await self._repo.set_status(change.id, target)
            await self._publish(updated, previous=current, extra=extra)
            results.append(updated)
        return results

    async def _load_many(
        self, change_ids: Sequence[str | uuid.UUID], *, action: str
    ) -> list[VirtualChange]:
        """整批读取提案：重复 ID 直接拒绝（否则第二条会在执行阶段才炸，留下半状态）。"""
        keys = [str(change_id) for change_id in change_ids]
        if not keys:
            raise ValidationError(f"批量 {action} 至少需要一条提案", details={"action": action})
        if len(set(keys)) != len(keys):
            raise ValidationError(
                f"批量 {action} 的提案列表里存在重复 ID",
                details={"action": action, "change_ids": keys},
            )
        return [await self._repo.get(key) for key in keys]

    async def _ids_of_group(self, group_id: str | uuid.UUID, *, action: str) -> list[str]:
        changes = await self._repo.list(group_id=group_id)
        if not changes:
            raise NotFoundError(
                f"提案组 {group_id} 不存在或没有任何提案",
                details={"group_id": str(group_id), "action": action},
            )
        return [str(c.id) for c in changes]

    def _deadline(self, ttl_seconds: int | None) -> datetime | None:
        """按 TTL 算出审核截止时间；<= 0 表示不启用超时失效。"""
        ttl = self._ttl_seconds if ttl_seconds is None else ttl_seconds
        if ttl <= 0:
            return None
        return _utcnow() + timedelta(seconds=ttl)

    @staticmethod
    def _is_stale(change: VirtualChange) -> bool:
        if change.expires_at is None:
            return False
        if VirtualChangeStatus(change.status) not in (
            VirtualChangeStatus.PENDING,
            VirtualChangeStatus.ACCEPTED,
        ):
            return False
        return _as_utc(change.expires_at) <= _utcnow()

    async def _guard_expired(self, changes: Sequence[VirtualChange]) -> None:
        """惰性过期：读到超时的提案就顺手落 expired 终态，并拒绝本次审核/落盘。

        先落状态再报错——否则调用方拿到的错误说"过期了"，库里却还是 pending，
        下一个人还会再撞一次同样的墙（fail-closed：状态必须反映事实）。
        """
        stale = [change for change in changes if self._is_stale(change)]
        if not stale:
            return
        updated = await self._repo.mark_expired(
            [change.id for change in stale], reason=EXPIRE_REASON_TIMEOUT
        )
        for change, previous in zip(updated, stale, strict=True):
            await self._publish(
                change,
                previous=VirtualChangeStatus(previous.status),
                extra={"reason": EXPIRE_REASON_TIMEOUT},
            )
        ids = [str(change.id) for change in stale]
        raise ProposalExpiredError(
            f"提案已过期，不能再审核或落盘：{', '.join(ids)}",
            details={"change_ids": ids, "reason": EXPIRE_REASON_TIMEOUT},
        )

    async def _supersede_pending_for_file(self, new_change: VirtualChange) -> list[VirtualChange]:
        """同一文件 + 同一项目/任务范围内的旧 pending 提案因被取代而失效。

        只失效 pending：已被人工批准（accepted）的提案不因新提案出现而静默消失，
        它是否还能落盘由 Apply 前的 original_hash 复验来裁决。
        """
        older = await self._repo.list(
            file_path=new_change.file_path, status=VirtualChangeStatus.PENDING
        )
        superseded = [
            change
            for change in older
            if change.id != new_change.id
            and change.project_id == new_change.project_id
            and change.task_id == new_change.task_id
        ]
        if not superseded:
            return []
        updated = await self._repo.mark_expired(
            [change.id for change in superseded], reason=EXPIRE_REASON_SUPERSEDED
        )
        for change, previous in zip(updated, superseded, strict=True):
            await self._publish(
                change,
                previous=VirtualChangeStatus(previous.status),
                extra={"reason": EXPIRE_REASON_SUPERSEDED, "superseded_by": str(new_change.id)},
            )
        return updated

    @staticmethod
    def _assert_transition(change: VirtualChange, target: VirtualChangeStatus) -> None:
        current = VirtualChangeStatus(change.status)
        if target not in ALLOWED[current]:
            raise InvalidTransitionError(
                f"非法状态跃迁：{current} → {target}",
                details={"from": str(current), "to": str(target), "change_id": str(change.id)},
            )

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
