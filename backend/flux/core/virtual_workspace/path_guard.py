"""工作区路径守卫：所有碰工作区文件的地方共用这一份"不许逃逸"的实现（§7.6 / §12.5）。

`safe_relative_path` 只做字符串级校验（拒绝对路径与 `..`），但"工作区内"的路径
只要有一环是软链，`Path` 接口的读写就会跟着软链跑到根外——仓库里被提交的
`.flux -> /home/user/.ssh`、`config.py -> /etc/passwd` 都是真实可行的逃逸路径。

因此真正碰盘之前必须再过这一关：逐段 `is_symlink()` fail-closed（指向根内的软链
同样拒绝，不做例外判断）+ realpath 包含性兜底。Apply Engine（写）、Backup Service
（备份 / 回滚）与 Project File Explorer（读）都调用这里，保护逻辑只有这一份真源。
"""

from __future__ import annotations

import os
from pathlib import Path

from flux.errors import ValidationError


def within_root(root: Path, candidate: Path) -> bool:
    """`candidate` 的真实路径（realpath，解析全部软链）是否落在 `root` 之内。"""
    root_real = Path(os.path.realpath(root))
    candidate_real = Path(os.path.realpath(candidate))
    return candidate_real == root_real or root_real in candidate_real.parents


def resolve_within_root(root: Path, relative: Path) -> Path:
    """在工作区根内解析相对路径；沿途任何一段是软链都 fail-closed 拒绝。

    返回值与 `root / relative` 相同——校验通过就代表"沿这条路径碰盘不会跑出根"。
    """
    if not relative.parts:
        return root
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            resolved = Path(os.path.realpath(current))
            if not within_root(root, resolved):
                raise ValidationError(
                    f"路径是符号链接且指向工作区根之外，已拒绝：{relative.as_posix()}",
                    details={"path": relative.as_posix(), "resolved": str(resolved)},
                )
            raise ValidationError(
                f"路径是符号链接，本接口不跟随符号链接：{relative.as_posix()}",
                details={"path": relative.as_posix()},
            )
    if not within_root(root, current):
        raise ValidationError(
            f"路径越出工作区根，已拒绝：{relative.as_posix()}",
            details={"path": relative.as_posix()},
        )
    return current


__all__ = ["resolve_within_root", "within_root"]
