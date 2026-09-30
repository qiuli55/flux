"""一句需求 → 可审阅提案（实施计划 ⑫ 最小 IDE 的写入口）。

链路：需求 + 相关文件现状 → Developer Agent → `CodeChangeSet` → 逐文件落成 Proposal(pending)。

三条边界：
1. **只读用户文件**：只按显式给出的相对路径读取现状，读多大、读几个都有上限；
2. **只写提案表**：产出的是提案，不是落盘改动——真正写用户文件只能走 Apply Engine（§7.6）；
3. **失败即失败**：模型输出不合契约、路径越界、文件数超限一律明确报错，不静默兜底。
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.core.agent_runtime.developer import DeveloperAgent
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root, safe_relative_path
from flux.core.virtual_workspace.service import VirtualWorkspaceService
from flux.errors import ValidationError
from flux.logging import get_logger
from flux.models import VirtualChange

logger = get_logger(__name__)

# 上下文有界：一次最多带几个文件、单个文件最多读多少字节
MAX_CONTEXT_FILES = 5
MAX_CONTEXT_FILE_BYTES = 60_000


@dataclass(frozen=True)
class ProposalOutcome:
    """一次"让 AI 改"的结果：模型给出的改动摘要与落库的提案。"""

    summary: str
    files: tuple[str, ...]
    proposals: tuple[VirtualChange, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "files": list(self.files),
            "proposals": [change.to_dict() for change in self.proposals],
        }


class DeveloperProposalFlow:
    """把 Developer Agent 接到 Virtual Workspace 上：一次调用产出一批待审阅提案。"""

    def __init__(
        self,
        developer: DeveloperAgent,
        workspace: VirtualWorkspaceService,
        *,
        workspace_root: str | Path | None = None,
    ) -> None:
        self._developer = developer
        self._workspace = workspace
        self._workspace_root = workspace_root

    async def produce(
        self,
        instruction: str,
        *,
        paths: Sequence[str] = (),
        task_id: str | uuid.UUID | None = None,
        project_id: str | uuid.UUID | None = None,
    ) -> ProposalOutcome:
        originals = self._read_files(paths)
        change_set = await self._developer.propose(instruction, files=originals, task_id=task_id)
        proposals = await self._workspace.propose_changes(
            change_set,
            project_id=project_id,
            task_id=task_id,
            agent_source=self._developer.manifest.name,
            original_files=originals,
        )
        logger.info(
            "flow.proposals agent=%s files=%d proposals=%d",
            self._developer.manifest.name,
            len(originals),
            len(proposals),
        )
        return ProposalOutcome(
            summary=change_set.summary,
            files=change_set.paths,
            proposals=tuple(proposals),
        )

    def _read_files(self, paths: Sequence[str]) -> dict[str, str]:
        """读需求相关的文件现状。路径必须在工作区根之内；不存在视为新建文件（内容为空）。"""
        requested = [path for path in paths if path and path.strip()]
        if not requested:
            return {}
        if len(requested) > MAX_CONTEXT_FILES:
            raise ValidationError(
                f"一次最多携带 {MAX_CONTEXT_FILES} 个文件作为上下文",
                details={"requested": len(requested), "limit": MAX_CONTEXT_FILES},
            )
        root = resolve_workspace_root(self._workspace_root)
        originals: dict[str, str] = {}
        for raw in requested:
            relative = safe_relative_path(raw)
            target = root / relative
            if not target.is_file():
                # 计划新建的文件：原文为空串，Apply 时据此判断"文件本不该存在"
                originals[relative.as_posix()] = ""
                continue
            if target.stat().st_size > MAX_CONTEXT_FILE_BYTES:
                raise ValidationError(
                    f"文件过大，无法作为上下文：{relative.as_posix()}",
                    details={"file_path": relative.as_posix(), "limit": MAX_CONTEXT_FILE_BYTES},
                )
            originals[relative.as_posix()] = target.read_text(encoding="utf-8", errors="replace")
        return originals


__all__ = [
    "MAX_CONTEXT_FILES",
    "MAX_CONTEXT_FILE_BYTES",
    "DeveloperProposalFlow",
    "ProposalOutcome",
]
