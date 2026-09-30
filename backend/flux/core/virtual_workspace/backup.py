"""原文件备份与回滚（主规格 §7.6 的"备份原文件"一步；实施计划 ⑦）。

备份落在 `<工作区根>/.flux/backups/<change_id>/<file_path>`：与项目同处一棵树下，
内网/离线环境都能用，不引入额外存储依赖。`.flux/` 需要被项目自身的 .gitignore 忽略。
"""

from __future__ import annotations

import shutil
from pathlib import Path

from flux.logging import get_logger

logger = get_logger(__name__)

BACKUP_DIRNAME = ".flux"


class BackupService:
    def __init__(self, *, workspace_root: Path, dirname: str = BACKUP_DIRNAME) -> None:
        self._root = workspace_root
        self._dirname = dirname

    @property
    def backup_root(self) -> Path:
        return self._root / self._dirname / "backups"

    def backup(self, *, change_id: str, file_path: str, relative_target: Path) -> Path | None:
        """把待改文件复制到备份目录。文件不存在（新建文件）时返回 None。"""
        source = self._root / relative_target
        if not source.is_file():
            return None
        destination = self.backup_root / change_id / relative_target
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        logger.info("apply.backup change=%s file=%s → %s", change_id, file_path, destination)
        return destination

    def restore(self, *, backup_path: Path, relative_target: Path) -> None:
        """用备份覆盖回原文件（回滚）。"""
        target = self._root / relative_target
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(backup_path, target)
        logger.warning("apply.rollback file=%s ← %s", target, backup_path)
