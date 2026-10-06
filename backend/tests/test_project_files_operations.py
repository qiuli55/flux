from pathlib import Path

import pytest

from flux.core.project_files.explorer import WorkspaceFileExplorer
from flux.errors import ConflictError, ValidationError


def test_user_file_operations_round_trip(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    explorer = WorkspaceFileExplorer(workspace_root=tmp_path)

    assert explorer.create_file(path="src/app.ts", content="export const x = 1;") == "src/app.ts"
    assert (tmp_path / "src/app.ts").read_text(encoding="utf-8") == "export const x = 1;"

    assert explorer.create_directory(path="src/components") == "src/components"
    assert explorer.rename(path="src/app.ts", new_path="src/main.ts") == "src/main.ts"
    assert (tmp_path / "src/main.ts").exists()
    assert not (tmp_path / "src/app.ts").exists()

    assert explorer.delete(path="src/components") == "src/components"
    assert explorer.delete(path="src/main.ts") == "src/main.ts"
    assert not (tmp_path / "src/main.ts").exists()


def test_user_file_operations_reject_internal_paths_and_workspace_root(tmp_path: Path) -> None:
    explorer = WorkspaceFileExplorer(workspace_root=tmp_path)

    with pytest.raises(ValidationError):
        explorer.create_file(path="../escape.txt")

    with pytest.raises(ValidationError):
        explorer.create_file(path=".flux/escape.txt")

    with pytest.raises(ValidationError):
        explorer.create_directory(path=".git/hijack")

    with pytest.raises(ValidationError):
        explorer.rename(path=".", new_path="renamed")

    with pytest.raises(ValidationError):
        explorer.delete(path=".")


def test_user_file_operations_reject_collisions(tmp_path: Path) -> None:
    (tmp_path / "a.txt").write_text("a", encoding="utf-8")
    explorer = WorkspaceFileExplorer(workspace_root=tmp_path)

    with pytest.raises(ConflictError):
        explorer.create_file(path="a.txt")

    with pytest.raises(ConflictError):
        explorer.create_directory(path="a.txt")

    with pytest.raises(ConflictError):
        explorer.rename(path="a.txt", new_path="a.txt")


def test_user_directory_rename_cannot_move_inside_itself(tmp_path: Path) -> None:
    (tmp_path / "parent").mkdir()
    (tmp_path / "parent" / "child").mkdir()
    explorer = WorkspaceFileExplorer(workspace_root=tmp_path)

    with pytest.raises(ValidationError):
        explorer.rename(path="parent", new_path="parent/child/new-parent")
