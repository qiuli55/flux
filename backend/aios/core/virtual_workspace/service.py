"""Virtual Workspace 服务（主规格 §7）。

已实现（M0）：状态机、Change Object、行级 unified diff 生成、审查操作（接受/拒绝/应用）。
未实现（M2 里程碑交付）：真实写盘、原文件备份、测试触发、Git 提交 —— 即 §7.6 Apply 流程中
除校验与状态跃迁之外的部分。本文件中的 apply() 只做状态跃迁与审计记录，绝不落盘。
"""

from __future__ import annotations

import difflib
import uuid
from dataclasses import dataclass, field

from aios.core.event.bus import EventBus, Events
from aios.enums import VirtualChangeStatus
from aios.errors import InvalidTransitionError, NotFoundError

# 允许的审查状态跃迁（§7.2 文件状态机）
ALLOWED: dict[VirtualChangeStatus, frozenset[VirtualChangeStatus]] = {
    VirtualChangeStatus.PENDING: frozenset(
        {VirtualChangeStatus.ACCEPTED, VirtualChangeStatus.REJECTED}
    ),
    VirtualChangeStatus.ACCEPTED: frozenset(
        {VirtualChangeStatus.APPLIED, VirtualChangeStatus.REJECTED}
    ),
    VirtualChangeStatus.REJECTED: frozenset(),
    VirtualChangeStatus.APPLIED: frozenset(),
}


@dataclass
class ChangeProposal:
    """§7.3 Change Object。"""

    project_id: str
    file_path: str
    original_content: str
    proposed_content: str
    agent_source: str | None = None
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    status: VirtualChangeStatus = VirtualChangeStatus.PENDING
    diff: str = ""

    def to_dict(self) -> dict[str, object]:
        return {
            "id": self.id,
            "project_id": self.project_id,
            "file_path": self.file_path,
            "original_content": self.original_content,
            "proposed_content": self.proposed_content,
            "diff": self.diff,
            "agent_source": self.agent_source,
            "status": str(self.status),
        }


def build_unified_diff(file_path: str, original: str, proposed: str) -> str:
    """行级 unified diff（§7.4 Diff Engine 的行级能力）。"""
    return "".join(
        difflib.unified_diff(
            original.splitlines(keepends=True),
            proposed.splitlines(keepends=True),
            fromfile=f"a/{file_path}",
            tofile=f"b/{file_path}",
        )
    )


class VirtualWorkspaceService:
    def __init__(self, bus: EventBus | None = None) -> None:
        self._changes: dict[str, ChangeProposal] = {}
        self._bus = bus

    def propose(
        self,
        *,
        project_id: str,
        file_path: str,
        original_content: str,
        proposed_content: str,
        agent_source: str | None = None,
    ) -> ChangeProposal:
        proposal = ChangeProposal(
            project_id=project_id,
            file_path=file_path,
            original_content=original_content,
            proposed_content=proposed_content,
            agent_source=agent_source,
        )
        proposal.diff = build_unified_diff(file_path, original_content, proposed_content)
        self._changes[proposal.id] = proposal
        return proposal

    def get(self, change_id: str) -> ChangeProposal:
        proposal = self._changes.get(change_id)
        if proposal is None:
            raise NotFoundError(f"虚拟改动 {change_id} 不存在", details={"change_id": change_id})
        return proposal

    def list(
        self, *, project_id: str | None = None, status: str | None = None
    ) -> list[ChangeProposal]:
        items = list(self._changes.values())
        if project_id is not None:
            items = [c for c in items if c.project_id == project_id]
        if status is not None:
            items = [c for c in items if str(c.status) == status]
        return items

    async def accept(self, change_id: str) -> ChangeProposal:
        return await self._transition(change_id, VirtualChangeStatus.ACCEPTED)

    async def reject(self, change_id: str) -> ChangeProposal:
        return await self._transition(change_id, VirtualChangeStatus.REJECTED)

    async def apply(self, change_id: str) -> ChangeProposal:
        """应用改动。

        M0：pending → accepted → applied 的状态跃迁 + 审计事件（调用本接口即视为人工批准，§7.5）。
        M2：在 applied 之前插入"校验补丁 → 备份原文件 → 写入 → 跑测试 → Git 提交"（§7.6）。
        """
        proposal = self.get(change_id)
        if proposal.status is VirtualChangeStatus.PENDING:
            proposal = await self._transition(change_id, VirtualChangeStatus.ACCEPTED)
        return await self._transition(proposal.id, VirtualChangeStatus.APPLIED)

    async def _transition(self, change_id: str, target: VirtualChangeStatus) -> ChangeProposal:
        proposal = self.get(change_id)
        if target not in ALLOWED[proposal.status]:
            raise InvalidTransitionError(
                f"非法状态跃迁：{proposal.status} → {target}",
                details={"from": str(proposal.status), "to": str(target)},
            )
        previous = proposal.status
        proposal.status = target
        if self._bus is not None:
            await self._bus.publish(
                Events.WORKSPACE_CHANGED,
                {"change_id": proposal.id, "from": str(previous), "to": str(target)},
            )
        return proposal
