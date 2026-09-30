"""Virtual Workspace 服务（主规格 §7；实施计划 ④）。

权威存储是 virtual_changes 表，服务层负责：
- 把 Developer Agent 的 `CodeChangeSet` 落成一条条 Proposal（含 original_hash 与 unified diff）；
- 人工审查动作（accept / reject）与 apply 的状态跃迁。

**本步不落盘**：真正写用户文件（hash 复验 → 备份 → 打补丁 → 校验 → 跑测试 → Git 提交）
由 ⑦ Apply Engine 负责，届时在 `apply()` 里插入那段流程。
"""

from __future__ import annotations

import uuid
from collections.abc import Mapping

from flux.core.agent_runtime.developer import CodeChangeSet
from flux.core.event.bus import EventBus, Events
from flux.core.virtual_workspace.diff_engine import compute_file_diff, content_hash
from flux.core.virtual_workspace.repository import ProposalRepository
from flux.enums import VirtualChangeStatus
from flux.errors import InvalidTransitionError, ValidationError
from flux.logging import get_logger
from flux.models.workspace import VirtualChange

logger = get_logger(__name__)

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
    def __init__(self, repository: ProposalRepository, bus: EventBus | None = None) -> None:
        self._repo = repository
        self._bus = bus

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

    async def reject(self, change_id: str | uuid.UUID) -> VirtualChange:
        return await self._transition(change_id, VirtualChangeStatus.REJECTED)

    async def apply(self, change_id: str | uuid.UUID) -> VirtualChange:
        """应用改动：pending 自动先转 accepted。

        ④⑤ 只做状态跃迁（调用本接口即视为人工批准，§7.5）；⑦ 会在 applied 之前插入
        「校验补丁 → 备份原文件 → 写入 → 校验文件 → 跑配置的测试 → Git 提交」（§7.6）。
        """
        change = await self._repo.get(change_id)
        if change.status == VirtualChangeStatus.PENDING.value:
            change = await self._transition(change.id, VirtualChangeStatus.ACCEPTED)
        return await self._transition(change.id, VirtualChangeStatus.APPLIED)

    async def mark_failed(self, change_id: str | uuid.UUID) -> VirtualChange:
        """留一条失败终态（供 ⑦ Apply Engine 在落盘失败时调用），失败原因由日志与事件承载。"""
        return await self._transition(change_id, VirtualChangeStatus.FAILED)

    async def _transition(
        self, change_id: str | uuid.UUID, target: VirtualChangeStatus
    ) -> VirtualChange:
        change = await self._repo.get(change_id)
        current = VirtualChangeStatus(change.status)
        if target not in ALLOWED[current]:
            raise InvalidTransitionError(
                f"非法状态跃迁：{current} → {target}",
                details={"from": str(current), "to": str(target), "change_id": str(change_id)},
            )
        updated = await self._repo.set_status(change.id, target)
        await self._publish(updated, previous=current)
        return updated

    async def _publish(
        self, change: VirtualChange, *, previous: VirtualChangeStatus | None
    ) -> None:
        if self._bus is None:
            return
        await self._bus.publish(
            Events.WORKSPACE_CHANGED,
            {
                "change_id": str(change.id),
                "file_path": change.file_path,
                "from": str(previous) if previous is not None else None,
                "to": change.status,
            },
        )


def _as_uuid(value: str | uuid.UUID | None) -> uuid.UUID | None:
    if value is None or isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise ValidationError(
            f"project_id / task_id 必须是合法 UUID：{value}", details={"value": str(value)}
        ) from exc
