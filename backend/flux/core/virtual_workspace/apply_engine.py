"""Apply Engine（主规格 §7.6；实施计划 ⑦）。

固定流程，顺序不可调换：

```
校验提案 → 复验 original_hash → 备份原文件 → 打补丁 → 校验文件 → 跑配置的测试
```

任何一步失败都尽可能把原文件还原回去，并把完整错误交给上层记录。
**这里是 Flux 里唯一会写用户真实文件的地方**——API、Agent、UI 都不允许自己落盘。

路径在碰盘前先经 `resolve_within_root` 校验：沿途任何一段是软链一律 fail-closed，
落盘永远发生在工作区根之内（否则仓库里一个提交的软链就能把改动写到根外）。

`apply_many` 是原子批量入口（P0-03）：**全量预检 → 全量备份 → 全量写入 → 全量校验 →
只跑一次测试 → 任一失败全部回滚**。绝不接受"A ✓ B ✓ C ✗ 但 A/B 留在盘上"的半应用；
批次内同一文件出现多条、同一提案重复出现都在预检阶段整批拒绝。
"""

from __future__ import annotations

import threading
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from flux.core.virtual_workspace.backup import BackupService
from flux.core.virtual_workspace.diff_engine import content_hash
from flux.core.virtual_workspace.path_guard import resolve_within_root
from flux.core.virtual_workspace.test_runner import TestOutcome, TestRunner
from flux.errors import ApplyFailedError, ConflictError, ValidationError
from flux.logging import get_logger
from flux.models.workspace import VirtualChange

logger = get_logger(__name__)

#: 进程内串行化所有落盘（P0-03 的"并发 Apply"）。
#:
#: 两个并发批次同时改同一文件时，各自在对方写入与回滚之间都拿的是过期快照：
#: B 在 A 回滚（用备份覆盖）之后写入，A 的回滚就会把 B 的成果抹掉。锁的范围覆盖
#: "预检 → 备份 → 写入 → 校验 → 测试"整段，第二个批次在第一个结束前连预检都进不来。
#: 多进程部署时还需文件锁（当前是单进程装配，M0 范围）。
_APPLY_LOCK = threading.Lock()

#: 一条提案在批量回滚里的失败记录（回滚失败必须被报出来，不能静默）。
RollbackError = dict[str, str]


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

    Apply Engine、测试执行、Git 集成、文件浏览与扫描都必须在同一个根下工作，
    所以这段判断放在一处。
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
        """应用单条提案（等价于只有一条的原子批次，走同一条代码路径）。"""
        return self.apply_many((change,), workspace_root=workspace_root, run_tests=run_tests)[0]

    def apply_many(
        self,
        changes: Sequence[VirtualChange],
        *,
        workspace_root: str | Path | None = None,
        run_tests: bool = True,
    ) -> list[ApplyOutcome]:
        """原子批量落盘：要么全部成功，要么全部恢复原样（P0-03）。

        顺序固定：全量预检（路径 + 重复 + original_hash）→ 全量备份 → 全量写入 →
        全量校验 → 只跑一次测试。写入阶段开始后任何一步失败，已经碰过的文件全部回滚，
        并把回滚失败逐条报在错误的 `details["rollback_errors"]` 里。
        """
        items = list(changes)
        if not items:
            raise ValidationError("批量 Apply 至少需要一条提案")
        # 整段持锁：并发批次拿到的盘上快照必须是一致的
        with _APPLY_LOCK:
            return self._apply_batch(items, workspace_root=workspace_root, run_tests=run_tests)

    # --- 批量主流程（调用方已持锁）---

    def _apply_batch(
        self,
        changes: list[VirtualChange],
        *,
        workspace_root: str | Path | None,
        run_tests: bool,
    ) -> list[ApplyOutcome]:
        root = self._resolve_root(workspace_root)
        backups = BackupService(workspace_root=root)

        # 1) 全量预检：任何一条不通过都在"还没碰盘"时整批拒绝
        prepared = self._prepare_all(changes, root)

        # 2) 全量备份（新建文件没有可备份的内容，返回 None）
        try:
            backup_paths = {
                str(change.id): backups.backup(
                    change_id=str(change.id),
                    file_path=change.file_path,
                    relative_target=relative,
                )
                for change, relative, _ in prepared
            }
        except Exception as exc:
            raise ApplyFailedError(
                f"Apply 失败：备份原文件时出错（{exc}）",
                details={"files": [c.file_path for c, _, _ in prepared]},
            ) from exc

        # 3) 全量写入（记录"写到第几条"，失败时把已经碰过的都回滚，含写了一半的那条）
        attempted = 0
        try:
            for change, _, target in prepared:
                attempted += 1
                self._write(target, change.proposed_content)
            # 4) 全量校验
            for change, _, target in prepared:
                self._verify(target, change)
            # 5) 跑项目自己配置的测试（整批只跑一次）
            test = self._run_tests(root) if run_tests else None
            if test is not None and not test.passed:
                raise ApplyFailedError(
                    f"Apply 后测试未通过（exit={test.exit_code}，{test.command}）",
                    details={
                        "files": [c.file_path for c, _, _ in prepared],
                        "test": test.to_dict(),
                    },
                )
        except Exception as exc:
            rollback_errors = self._rollback_all(
                backups,
                prepared[:attempted],
                backup_paths=backup_paths,
            )
            error = (
                exc
                if isinstance(exc, (ApplyFailedError, ConflictError, ValidationError))
                else ApplyFailedError(f"Apply 失败：{exc}", details={})
            )
            if rollback_errors:
                # 回滚失败是最严重的情形（用户文件可能停在中间态），必须显式报出来
                raise ApplyFailedError(
                    f"{error.message}；且回滚未完全成功："
                    + "；".join(
                        f"{item['file_path']}（{item['error']}）" for item in rollback_errors
                    ),
                    details={**(error.details or {}), "rollback_errors": rollback_errors},
                ) from exc
            if error is exc:
                raise
            raise error from exc

        logger.info(
            "apply.done changes=%s files=%s test=%s",
            len(prepared),
            [c.file_path for c, _, _ in prepared],
            test.passed if test is not None else "skipped",
        )
        return [
            ApplyOutcome(
                change_id=str(change.id),
                file_path=change.file_path,
                backup_path=(
                    str(backup_paths[str(change.id)])
                    if backup_paths[str(change.id)] is not None
                    else None
                ),
                test=test,
            )
            for change, _, _ in prepared
        ]

    # --- 各步骤 ---

    def _resolve_root(self, override: str | Path | None) -> Path:
        return resolve_workspace_root(override if override is not None else self._configured_root)

    def _prepare_all(
        self, changes: Sequence[VirtualChange], root: Path
    ) -> list[tuple[VirtualChange, Path, Path]]:
        """全量预检：路径合法、批次内不重复、逐条复验 original_hash。

        返回 (提案, 归一化相对路径, 落盘目标) 三元组列表，供后续步骤直接用——
        预检通过的路径不再二次解析，避免两步之间路径含义变化。
        """
        prepared: list[tuple[VirtualChange, Path, Path]] = []
        seen_ids: set[str] = set()
        seen_paths: dict[str, str] = {}
        for change in changes:
            change_id = str(change.id)
            if change_id in seen_ids:
                raise ValidationError(
                    f"同一提案在批次里出现了多次：{change_id}", details={"change_id": change_id}
                )
            seen_ids.add(change_id)

            relative = safe_relative_path(change.file_path)
            key = relative.as_posix()
            if key in seen_paths:
                # 同一文件两条提案：后写的会覆盖前写的，但两条的状态都会被标 applied，
                # 留下对不上账的审计记录——整批拒绝，让人工先把提案合并成一条
                raise ValidationError(
                    f"同一文件在同一批次里出现了多条提案：{change.file_path}",
                    details={
                        "file_path": change.file_path,
                        "change_ids": [seen_paths[key], change_id],
                    },
                )
            seen_paths[key] = change_id

            target = resolve_within_root(root, relative)
            self._assert_unchanged(change, target)
            prepared.append((change, relative, target))
        return prepared

    @staticmethod
    def _rollback_all(
        backups: BackupService,
        prepared: Sequence[tuple[VirtualChange, Path, Path]],
        *,
        backup_paths: dict[str, Path | None],
    ) -> list[RollbackError]:
        """按写入的逆序尽力回滚每一条；单条失败不打断其它条，逐条收集错误返回。

        只回滚 `prepared` 里传进来的条目（调用方已裁剪为"真正碰过盘"的集合）。
        """
        errors: list[RollbackError] = []
        for change, relative, target in reversed(list(prepared)):
            change_id = str(change.id)
            try:
                ApplyEngine._rollback(
                    backups,
                    backup_path=backup_paths.get(change_id),
                    relative=relative,
                    target=target,
                    wrote=True,
                )
            except Exception as exc:  # noqa: BLE001 - 回滚失败要留痕，不能吞
                logger.exception(
                    "apply.rollback 失败 change=%s file=%s", change_id, change.file_path
                )
                errors.append(
                    {"change_id": change_id, "file_path": change.file_path, "error": str(exc)}
                )
        return errors

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
                f"文件 {change.file_path} 已被改动，提案已过期，禁止直接 Apply；"
                "请让 Agent 基于最新文件重新提交提案，或手动修改该文件",
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
        backups: BackupService,
        *,
        backup_path: Path | None,
        relative: Path,
        target: Path,
        wrote: bool,
    ) -> None:
        """还原原文件；只碰 apply() 一开始就校验过的 `target`，回滚阶段不再重新解析路径。"""
        if backup_path is not None and backup_path.is_file():
            backups.restore(backup_path=backup_path, relative_target=relative)
            return
        # 新建文件：没有备份可还原，直接删掉刚写下的文件
        if wrote and target.is_file():
            target.unlink()
            logger.warning("apply.rollback 删除新建文件 %s", target)
