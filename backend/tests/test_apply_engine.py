"""Apply Engine 测试（主规格 §7.6；实施计划 ⑦）。

这是 Flux 里唯一会写用户真实文件的组件，因此每条用例都在**真实临时目录**上跑，
并且都断言"失败之后用户原文件必须回到原样"——回滚不是可选项。
"""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
import uuid
from pathlib import Path

import pytest

from flux.core.virtual_workspace.apply_engine import ApplyEngine, safe_relative_path
from flux.core.virtual_workspace.backup import BackupService
from flux.core.virtual_workspace.diff_engine import content_hash
from flux.core.virtual_workspace.test_runner import TestRunner
from flux.errors import ApplyFailedError, ConflictError, ValidationError
from flux.models.workspace import VirtualChange

ORIGINAL = "def login(user):\n    return False\n"
PROPOSED = "def login(user):\n    return check_password(user)\n"


def _change(
    file_path: str,
    *,
    original: str = ORIGINAL,
    proposed: str = PROPOSED,
) -> VirtualChange:
    """构造一条"已批准"的提案。id 显式给值：脱离会话的实例拿不到 column default。"""
    return VirtualChange(
        id=uuid.uuid4(),
        file_path=file_path,
        original_hash=content_hash(original),
        original_content=original,
        proposed_content=proposed,
        status="accepted",
    )


def _write(root: Path, relative: str, content: str) -> Path:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


# --- 正常落盘 ---


def test_apply_writes_proposed_content_and_backs_up_original(workspace_root: Path) -> None:
    target = _write(workspace_root, "auth/login.py", ORIGINAL)
    change = _change("auth/login.py")

    outcome = ApplyEngine(workspace_root=workspace_root).apply(change, run_tests=False)

    assert target.read_text(encoding="utf-8") == PROPOSED
    assert outcome.change_id == str(change.id)
    assert outcome.file_path == "auth/login.py"
    assert outcome.test is None
    backup = Path(outcome.backup_path)
    assert backup.is_file()
    # 备份落在工作区根下的 .flux/backups/<change_id>/<相对路径>，内容为改动前的原文
    assert backup == workspace_root / ".flux" / "backups" / str(change.id) / "auth" / "login.py"
    assert backup.read_text(encoding="utf-8") == ORIGINAL


def test_apply_creates_new_file_without_backup(workspace_root: Path) -> None:
    """新建文件：原文为空串 + 磁盘上不存在，属于合法提案，且没有可备份的内容。"""
    change = _change("app/health.py", original="", proposed="def health():\n    return 'ok'\n")

    outcome = ApplyEngine(workspace_root=workspace_root).apply(change, run_tests=False)

    created = workspace_root / "app" / "health.py"
    assert created.read_text(encoding="utf-8") == "def health():\n    return 'ok'\n"
    assert outcome.backup_path is None


def test_apply_normalizes_dotted_and_backslashed_paths(workspace_root: Path) -> None:
    _write(workspace_root, "app/main.py", ORIGINAL)
    change = _change("./app\\main.py")

    ApplyEngine(workspace_root=workspace_root).apply(change, run_tests=False)

    assert (workspace_root / "app" / "main.py").read_text(encoding="utf-8") == PROPOSED
    # 不能因为路径写法不同就在工作区里留下 ./app 这样的怪目录
    assert sorted(p.name for p in workspace_root.iterdir()) == [".flux", "app"]


def test_apply_uses_override_root(workspace_root: Path, tmp_path: Path) -> None:
    """显式传入的 workspace_root 优先于构造时的配置。"""
    other = tmp_path / "other-project"
    other.mkdir()
    _write(other, "a.py", ORIGINAL)

    engine = ApplyEngine(workspace_root=workspace_root)
    engine.apply(_change("a.py"), workspace_root=other, run_tests=False)

    assert (other / "a.py").read_text(encoding="utf-8") == PROPOSED
    assert not (workspace_root / "a.py").exists()


# --- 预检失败（不落盘，不动原文件）---


def test_apply_conflicts_when_user_changed_the_file(workspace_root: Path) -> None:
    """Agent 出提案后用户又改了同一文件 → 拒绝落盘，用户的改动必须原样保留。"""
    user_version = "def login(user):\n    return user.is_active\n"
    target = _write(workspace_root, "auth/login.py", user_version)

    with pytest.raises(ConflictError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(_change("auth/login.py"), run_tests=False)

    assert "已被改动" in excinfo.value.message
    assert excinfo.value.details["expected_hash"] == content_hash(ORIGINAL)
    assert excinfo.value.details["actual_hash"] == content_hash(user_version)
    assert target.read_text(encoding="utf-8") == user_version
    assert not (workspace_root / ".flux").exists()


def test_apply_conflicts_when_file_was_deleted(workspace_root: Path) -> None:
    """提案基于"有原文"的文件，但文件已不在 → 冲突，而不是当成新建文件覆盖。"""
    with pytest.raises(ConflictError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(_change("auth/login.py"), run_tests=False)

    assert "已不存在" in excinfo.value.message
    assert not (workspace_root / "auth" / "login.py").exists()


def test_apply_rejects_directory_as_target(workspace_root: Path) -> None:
    (workspace_root / "pkg").mkdir()

    with pytest.raises(ConflictError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(_change("pkg"), run_tests=False)

    assert "不是普通文件" in excinfo.value.message


def test_apply_without_workspace_root_is_rejected() -> None:
    with pytest.raises(ValidationError) as excinfo:
        ApplyEngine().apply(_change("a.py"), run_tests=False)
    assert "未配置工作区根目录" in excinfo.value.message


def test_apply_rejects_missing_workspace_root(tmp_path: Path) -> None:
    with pytest.raises(ValidationError) as excinfo:
        ApplyEngine(workspace_root=tmp_path / "不存在").apply(_change("a.py"), run_tests=False)
    assert "不存在或不是目录" in excinfo.value.message


def test_safe_relative_path_rejects_escaping_paths() -> None:
    assert safe_relative_path("./app/main.py") == Path("app") / "main.py"
    for bad in ("", "   ", "/etc/passwd", "../../secret.env", "app/../../secret.env"):
        with pytest.raises(ValidationError):
            safe_relative_path(bad)


# --- 失败回滚 ---


def test_apply_rolls_back_when_tests_fail(workspace_root: Path) -> None:
    """测试不通过 → 改回原文件、抛 ApplyFailedError，且带上测试输出。"""
    target = _write(workspace_root, "auth/login.py", ORIGINAL)
    engine = ApplyEngine(workspace_root=workspace_root, test_command="exit 3")

    with pytest.raises(ApplyFailedError) as excinfo:
        engine.apply(_change("auth/login.py"))

    assert "测试未通过" in excinfo.value.message
    assert excinfo.value.details["test"]["exit_code"] == 3
    assert excinfo.value.details["test"]["passed"] is False
    assert target.read_text(encoding="utf-8") == ORIGINAL


def test_apply_rolls_back_by_deleting_new_file_when_tests_fail(workspace_root: Path) -> None:
    """新建文件 + 测试不通过 → 没有备份可还原，必须把刚写下的文件删掉。"""
    change = _change("app/health.py", original="", proposed="def health():\n    return 'ok'\n")
    engine = ApplyEngine(workspace_root=workspace_root, test_command="exit 5")

    with pytest.raises(ApplyFailedError):
        engine.apply(change)

    assert not (workspace_root / "app" / "health.py").exists()


def test_apply_rollback_keeps_original_content_even_with_unicode(workspace_root: Path) -> None:
    """回滚必须按字节还原中文内容，不能因为编码差异改名或损坏文件。"""
    original = "# 中文注释：登录校验\n\ndef login(user):\n    return False\n"
    target = _write(workspace_root, "auth/login.py", original)

    with pytest.raises(ApplyFailedError):
        ApplyEngine(workspace_root=workspace_root, test_command="exit 1").apply(
            _change("auth/login.py", original=original)
        )

    assert target.read_text(encoding="utf-8") == original


def test_apply_skips_tests_when_run_tests_is_false(workspace_root: Path) -> None:
    """run_tests=False 时不跑测试：命令必定失败也必须成功落盘。"""
    _write(workspace_root, "auth/login.py", ORIGINAL)
    engine = ApplyEngine(workspace_root=workspace_root, test_command="exit 1")

    outcome = engine.apply(_change("auth/login.py"), run_tests=False)

    assert outcome.test is None


# --- 测试执行 ---


def test_apply_runs_configured_test_command(workspace_root: Path) -> None:
    _write(workspace_root, "auth/login.py", ORIGINAL)
    engine = ApplyEngine(workspace_root=workspace_root, test_command="echo 测试通过 && exit 0")

    outcome = engine.apply(_change("auth/login.py"))

    assert outcome.test is not None
    assert outcome.test.passed is True
    assert outcome.test.exit_code == 0
    assert "测试通过" in outcome.test.output
    assert outcome.test.command == "echo 测试通过 && exit 0"


def test_apply_rolls_back_when_test_times_out(workspace_root: Path) -> None:
    target = _write(workspace_root, "auth/login.py", ORIGINAL)
    engine = ApplyEngine(
        workspace_root=workspace_root,
        test_command="sleep 5",
        test_timeout_seconds=0.2,
    )

    with pytest.raises(ApplyFailedError) as excinfo:
        engine.apply(_change("auth/login.py"))

    assert excinfo.value.details["test"]["timed_out"] is True
    assert target.read_text(encoding="utf-8") == ORIGINAL


def test_test_runner_reports_failure_and_truncates_output(tmp_path: Path) -> None:
    runner = TestRunner(timeout_seconds=10)
    assert runner.run("exit 0", cwd=tmp_path).passed is True

    failed = runner.run("echo 炸了 && exit 7", cwd=tmp_path)
    assert failed.passed is False
    assert failed.exit_code == 7
    assert "炸了" in failed.output

    long_output = runner.run(
        f"{shlex.quote(sys.executable)} -c 'print(\"x\" * 30000)'", cwd=tmp_path
    )
    assert len(long_output.output) < 30000
    assert "输出已截断" in long_output.output


def test_test_runner_returns_outcome_as_dict(tmp_path: Path) -> None:
    payload = TestRunner().run("exit 0", cwd=tmp_path).to_dict()
    assert set(payload) == {"command", "exit_code", "output", "timed_out", "duration_ms", "passed"}


# --- 备份服务 ---


def test_backup_service_returns_none_for_missing_file(workspace_root: Path) -> None:
    backups = BackupService(workspace_root=workspace_root)
    assert backups.backup(change_id="c1", file_path="a.py", relative_target=Path("a.py")) is None
    assert backups.backup_root == workspace_root / ".flux" / "backups"


def test_backup_service_restore_overwrites_target(workspace_root: Path) -> None:
    target = _write(workspace_root, "a.py", ORIGINAL)
    backups = BackupService(workspace_root=workspace_root)
    backup_path = backups.backup(change_id="c1", file_path="a.py", relative_target=Path("a.py"))
    assert backup_path is not None

    target.write_text("被写坏的内容\n", encoding="utf-8")
    backups.restore(backup_path=backup_path, relative_target=Path("a.py"))

    assert target.read_text(encoding="utf-8") == ORIGINAL


def test_backup_directory_is_excluded_from_git_status(workspace_root: Path) -> None:
    """Apply 的备份不该出现在用户的 git status 里，也不该改用户受版本控制的 .gitignore。"""
    env = {
        **os.environ,
        "GIT_AUTHOR_NAME": "Flux Test",
        "GIT_AUTHOR_EMAIL": "flux-test@example.com",
        "GIT_COMMITTER_NAME": "Flux Test",
        "GIT_COMMITTER_EMAIL": "flux-test@example.com",
    }

    def git(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=workspace_root, check=True, capture_output=True, text=True, env=env
        ).stdout

    git("init", "-b", "main")
    _write(workspace_root, "a.py", ORIGINAL)
    git("add", "a.py")
    git("commit", "-m", "chore: 初始化")

    backup_path = BackupService(workspace_root=workspace_root).backup(
        change_id="c1", file_path="a.py", relative_target=Path("a.py")
    )

    assert backup_path is not None and backup_path.is_file()
    exclude = (workspace_root / ".git" / "info" / "exclude").read_text(encoding="utf-8")
    assert ".flux/" in exclude
    assert git("status", "--porcelain").strip() == ""
    assert not (workspace_root / ".gitignore").exists()


def test_backup_ignores_non_repository_workspace(workspace_root: Path) -> None:
    """工作区不是 Git 仓库时安静跳过，备份照样成功。"""
    _write(workspace_root, "a.py", ORIGINAL)

    backup_path = BackupService(workspace_root=workspace_root).backup(
        change_id="c1", file_path="a.py", relative_target=Path("a.py")
    )

    assert backup_path is not None and backup_path.is_file()


# --- 软链逃逸防护（分析文档 §5.1）---


def test_apply_rejects_symlinked_file_escaping_workspace(
    workspace_root: Path, tmp_path: Path
) -> None:
    """工作区里的软链文件指向根外：apply 必须拒绝，根外文件一个字节都不能动。"""
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "secret.py"
    secret.write_text(ORIGINAL, encoding="utf-8")
    (workspace_root / "config.py").symlink_to(secret)

    with pytest.raises(ValidationError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(_change("config.py"), run_tests=False)

    assert "符号链接" in excinfo.value.message
    assert secret.read_text(encoding="utf-8") == ORIGINAL
    # 拒绝发生在备份之前：工作区里连 .flux 都不该出现
    assert not (workspace_root / ".flux").exists()


def test_apply_rejects_symlinked_directory_escape_for_new_file(
    workspace_root: Path, tmp_path: Path
) -> None:
    """目录软链指向根外：新建文件的提案同样拒绝，不在根外造出任何文件。"""
    outside = tmp_path / "outside"
    outside.mkdir()
    (workspace_root / "evil").symlink_to(outside, target_is_directory=True)

    change = _change("evil/new.py", original="", proposed="print('boom')\n")
    with pytest.raises(ValidationError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(change, run_tests=False)

    assert "符号链接" in excinfo.value.message
    assert list(outside.iterdir()) == []


def test_apply_rejects_symlink_even_when_it_stays_inside_root(workspace_root: Path) -> None:
    """指向根内的软链也 fail-closed：不支持跟随软链是一条规则，不做例外判断。"""
    _write(workspace_root, "real/config.py", ORIGINAL)
    (workspace_root / "config.py").symlink_to(workspace_root / "real" / "config.py")

    with pytest.raises(ValidationError) as excinfo:
        ApplyEngine(workspace_root=workspace_root).apply(_change("config.py"), run_tests=False)

    assert "符号链接" in excinfo.value.message
    assert (workspace_root / "real" / "config.py").read_text(encoding="utf-8") == ORIGINAL


def test_backup_rejects_symlinked_backup_root(workspace_root: Path, tmp_path: Path) -> None:
    """`.flux/` 本身被软链到根外：备份先拒绝，绝不把用户文件复制到工作区之外。"""
    outside = tmp_path / "outside"
    outside.mkdir()
    _write(workspace_root, "a.py", ORIGINAL)
    (workspace_root / ".flux").symlink_to(outside, target_is_directory=True)

    with pytest.raises(ValidationError) as excinfo:
        BackupService(workspace_root=workspace_root).backup(
            change_id="c1", file_path="a.py", relative_target=Path("a.py")
        )

    assert "符号链接" in excinfo.value.message
    assert list(outside.iterdir()) == []


def test_backup_restore_rejects_symlinked_target(workspace_root: Path, tmp_path: Path) -> None:
    """回滚目标变成软链时同样拒绝：别把备份内容写到根外去。"""
    _write(workspace_root, "a.py", ORIGINAL)
    backups = BackupService(workspace_root=workspace_root)
    backup_path = backups.backup(change_id="c1", file_path="a.py", relative_target=Path("a.py"))
    assert backup_path is not None

    secret = tmp_path / "secret.py"
    secret.write_text("根外内容\n", encoding="utf-8")
    (workspace_root / "a.py").unlink()
    (workspace_root / "a.py").symlink_to(secret)

    with pytest.raises(ValidationError) as excinfo:
        backups.restore(backup_path=backup_path, relative_target=Path("a.py"))

    assert "符号链接" in excinfo.value.message
    assert secret.read_text(encoding="utf-8") == "根外内容\n"
