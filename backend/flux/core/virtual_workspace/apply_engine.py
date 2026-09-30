"""Apply Engine（主规格 §7.6；实施计划 ⑦）。

固定流程，顺序不可调换：

```
校验提案 → 复验 original_hash → 备份原文件 → 打补丁 → 校验文件 → 跑配置的测试
```

任何一步失败都尽可能把原文件还原回去，并把完整错误交给上层记录。
**这里是 Flux 里唯一会写用户真实文件的地方**——API、Agent、UI 都不允许自己落盘。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from flux.core.virtual_workspace.backup import BackupService
from flux.core.virtual_workspace.diff_engine import content_hash
from flux.core.virtual_workspace.test_runner import TestOutcome, TestRunner
from flux.errors import ApplyFailedError, ConflictError, ValidationError
from flux.logging import get_logger
from flux.models.workspace import VirtualChange

logger = get_logger(__name__)


@dataclass(frozen=True)
class ApplyOutcome:
    """一次成功落盘的完整凭据：改了哪个文件、备份在哪、测试结果如何。"""

    change_id: str
    file_path: str
    backup_path: str | None
    test: TestOutcome | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "change_id": self.change_id,
            "file_path": self.file_path,
            "backup_path": self.backup_path,
            "test": self.test.to_dict() if self.test is not None else None,
        }


def safe_relative_path(file_path: str) -> Path:
    """把提案里的路径规整成"一定落在工作区根之内"的相对路径。"""
    candidate = (file_path or "").strip().replace("\\", "/")
    pure = PurePosixPath(candidate)
    if not candidate or not pure.parts:
        raise ValidationError(
            f"提案的文件路径非法：{file_path!r}", details={"file_path": file_path}
        )
    if candidate.startswith("/") or pure.is_absolute():
        raise ValidationError(
            f"提案的文件路径不得是绝对路径：{file_path}", details={"file_path": file_path}
        )
    if ".." in pure.parts:
        raise ValidationError(
            f"提案的文件路径不得越出工作区根：{file_path}", details={"file_path": file_path}
        )
    return Path(*pure.parts)


def resolve_workspace_root(candidate: str | Path | None) -> Path:
    """解析"被改项目根目录"。未配置或不是目录一律拒绝，绝不猜一个默认目录。

    Apply Engine 与 Tester Agent 都必须在同一个根下工作，所以这段判断放在一处。
    """
    if candidate is None:
        raise ValidationError(
            "未配置工作区根目录（FLUX_WORKSPACE_ROOT）",
            details={"hint": "设置 FLUX_WORKSPACE_ROOT 指向被改项目的根目录"},
        )
    root = Path(candidate).expanduser().resolve()
    if not root.is_dir():
        raise ValidationError(
            f"工作区根目录不存在或不是目录：{root}", details={"workspace_root": str(root)}
        )
    return root


class ApplyEngine:
    def __init__(
        self,
        *,
        workspace_root: str | Path | None = None,
        test_command: str | None = None,
        test_timeout_seconds: float = 300.0,
    ) -> None:
        self._configured_root = Path(workspace_root).expanduser() if workspace_root else None
        self._test_command = test_command
        self._test_timeout = test_timeout_seconds

    # --- 主流程 ---

    def apply(
        self,
        change: VirtualChange,
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> ApplyOutcome:
        root = self._resolve_root(workspace_root)
        relative = safe_relative_path(change.file_path)
        target = root / relative
        backups = BackupService(workspace_root=root)

        # 1) 复验 original_hash：用户在 Agent 生成提案后手动改过文件就禁止落盘
        self._assert_unchanged(change, target)

        # 2) 备份原文件（新建文件没有可备份的内容）
        backup_path = backups.backup(
            change_id=str(change.id), file_path=change.file_path, relative_target=relative
        )
        wrote = False
        try:
            # 3) 打补丁（写完整内容，不是应用 diff 文本）
            self._write(target, change.proposed_content)
            wrote = True
            # 4) 校验落盘结果
            self._verify(target, change)
            # 5) 跑项目自己配置的测试
            test = self._run_tests(root) if run_tests else None
            if test is not None and not test.passed:
                raise ApplyFailedError(
                    f"Apply 后测试未通过（exit={test.exit_code}，{test.command}）",
                    details={"file_path": change.file_path, "test": test.to_dict()},
                )
        except Exception as exc:
            self._rollback(root, backups, backup_path=backup_path, relative=relative, wrote=wrote)
            if isinstance(exc, (ApplyFailedError, ConflictError, ValidationError)):
                raise
            raise ApplyFailedError(
                f"Apply 失败：{exc}", details={"file_path": change.file_path}
            ) from exc

        logger.info(
            "apply.done change=%s file=%s backup=%s test=%s",
            change.id,
            change.file_path,
            backup_path,
            test.passed if test is not None else "skipped",
        )
        return ApplyOutcome(
            change_id=str(change.id),
            file_path=change.file_path,
            backup_path=str(backup_path) if backup_path is not None else None,
            test=test,
        )

    # --- 各步骤 ---

    def _resolve_root(self, override: str | Path | None) -> Path:
        return resolve_workspace_root(override if override is not None else self._configured_root)

    @staticmethod
    def _assert_unchanged(change: VirtualChange, target: Path) -> None:
        if not target.exists():
            # 磁盘上没有这个文件：只有"新建文件"的提案才允许继续
            if change.original_content != "":
                raise ConflictError(
                    f"文件 {change.file_path} 已不存在，提案基于的原始内容无法复验",
                    details={"file_path": change.file_path},
                )
            return
        if not target.is_file():
            raise ConflictError(
                f"路径 {change.file_path} 不是普通文件，拒绝 Apply",
                details={"file_path": change.file_path},
            )
        current_hash = content_hash(target.read_text(encoding="utf-8"))
        if current_hash != change.original_hash:
            raise ConflictError(
                f"文件 {change.file_path} 已被改动，提案已过期，禁止直接 Apply",
                details={
                    "file_path": change.file_path,
                    "expected_hash": change.original_hash,
                    "actual_hash": current_hash,
                },
            )

    @staticmethod
    def _write(target: Path, content: str) -> None:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    @staticmethod
    def _verify(target: Path, change: VirtualChange) -> None:
        if not target.is_file():
            raise ApplyFailedError(
                f"写入后文件不存在：{change.file_path}", details={"file_path": change.file_path}
            )
        actual = content_hash(target.read_text(encoding="utf-8"))
        expected = content_hash(change.proposed_content)
        if actual != expected:
            raise ApplyFailedError(
                f"写入后内容与提案不一致：{change.file_path}",
                details={
                    "file_path": change.file_path,
                    "expected_hash": expected,
                    "actual_hash": actual,
                },
            )

    def _run_tests(self, root: Path) -> TestOutcome | None:
        if not self._test_command:
            return None
        return TestRunner(timeout_seconds=self._test_timeout).run(self._test_command, cwd=root)

    @staticmethod
    def _rollback(
        root: Path,
        backups: BackupService,
        *,
        backup_path: Path | None,
        relative: Path,
        wrote: bool,
    ) -> None:
        if backup_path is not None and backup_path.is_file():
            backups.restore(backup_path=backup_path, relative_target=relative)
            return
        if wrote:
            # 新建文件：没有备份可还原，直接删掉刚写下的文件
            target = root / relative
            if target.is_file():
                target.unlink()
                logger.warning("apply.rollback 删除新建文件 %s", target)
