"""Virtual Workspace 测试（主规格 §7；实施计划 ④）。

提案的权威存储是 virtual_changes 表，因此这里全部走真实数据库，
并且专门验证"重启后审核队列不丢"这条 M1 起就确立的原则。
"""

from __future__ import annotations

import asyncio
import shlex
import sys

import pytest

from flux.container import Container
from flux.core.agent_runtime.developer import CodeChangeSet, FileChange
from flux.core.event.bus import Events
from flux.core.virtual_workspace.diff_engine import content_hash
from flux.enums import VirtualChangeStatus
from flux.errors import ApplyFailedError, InvalidTransitionError, NotFoundError, ValidationError

ORIGINAL = "def login(user):\n    return False\n"
PROPOSED = "def login(user):\n    return check_password(user)\n"


def _propose(container: Container, *, file_path: str = "auth/login.py", **kwargs: object):
    return asyncio.run(
        container.workspace.propose(
            file_path=file_path,
            original_content=ORIGINAL,
            proposed_content=PROPOSED,
            agent_source="developer-agent",
            **kwargs,  # type: ignore[arg-type]
        )
    )


def test_proposal_starts_pending_with_hash_and_stats(container: Container) -> None:
    proposal = _propose(container, reason="改成真正的校验", summary="实现登录校验")
    assert proposal.status == VirtualChangeStatus.PENDING.value
    assert proposal.original_hash == content_hash(ORIGINAL)
    assert proposal.diff and proposal.diff.startswith("---")
    assert (proposal.added_lines, proposal.removed_lines, proposal.hunks) == (1, 1, 1)
    assert proposal.agent_source == "developer-agent"
    assert proposal.reason == "改成真正的校验"
    assert proposal.summary == "实现登录校验"


def test_proposal_is_visible_through_api_shape(container: Container) -> None:
    payload = _propose(container).to_dict()
    assert payload["status"] == "pending"
    assert payload["file_path"] == "auth/login.py"
    assert payload["task_id"] is None and payload["project_id"] is None
    assert payload["added_lines"] == 1


def test_unchanged_content_is_rejected(container: Container) -> None:
    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(
            container.workspace.propose(
                file_path="a.py", original_content=ORIGINAL, proposed_content=ORIGINAL
            )
        )
    assert "没有可审阅的改动" in excinfo.value.message


def test_propose_changes_maps_change_set_to_one_proposal_per_file(container: Container) -> None:
    change_set = CodeChangeSet(
        summary="新增健康检查",
        changes=(
            FileChange(path="app/main.py", content=PROPOSED, reason="注册路由"),
            FileChange(path="app/health.py", content="def health():\n    return 'ok'\n"),
        ),
    )
    created = asyncio.run(
        container.workspace.propose_changes(
            change_set,
            agent_source="developer-agent",
            original_files={"app/main.py": ORIGINAL},
        )
    )
    assert [c.file_path for c in created] == ["app/main.py", "app/health.py"]
    # 新文件：原文为空串，hash 为空内容的 hash（Apply 时据此判断"文件本不该存在"）
    new_file = created[1]
    assert new_file.original_content == ""
    assert new_file.original_hash == content_hash("")
    assert created[0].reason == "注册路由"
    assert created[0].summary == "新增健康检查"


def test_propose_changes_skips_files_without_real_change(container: Container) -> None:
    change_set = CodeChangeSet(
        summary="无意义改动",
        changes=(
            FileChange(path="app/main.py", content=PROPOSED),
            FileChange(path="app/other.py", content="same\n"),
        ),
    )
    created = asyncio.run(
        container.workspace.propose_changes(
            change_set,
            original_files={"app/main.py": ORIGINAL, "app/other.py": "same\n"},
        )
    )
    assert [c.file_path for c in created] == ["app/main.py"]
    assert len(asyncio.run(container.workspace.list())) == 1


def test_propose_changes_rejects_empty_effective_change_set(container: Container) -> None:
    change_set = CodeChangeSet(
        summary="空提案", changes=(FileChange(path="app/main.py", content=ORIGINAL),)
    )
    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(
            container.workspace.propose_changes(
                change_set, original_files={"app/main.py": ORIGINAL}
            )
        )
    assert "没有任何有效改动" in excinfo.value.message


def test_accept_then_apply(apply_container: Container, workspace_root) -> None:
    """⑦ 起 apply 会真正落盘：先把被改文件按原文写到工作区里。"""
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")

    proposal = _propose(apply_container)
    accepted = asyncio.run(apply_container.workspace.accept(str(proposal.id)))
    assert accepted.status == VirtualChangeStatus.ACCEPTED.value
    applied = asyncio.run(apply_container.workspace.apply(str(proposal.id)))
    assert applied.status == VirtualChangeStatus.APPLIED.value


def test_apply_pending_goes_through_accepted(apply_container: Container, workspace_root) -> None:
    """pending → accepted → applied（§7.2 / §7.5 调用方即人工批准）。"""
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")

    proposal = _propose(apply_container)
    applied = asyncio.run(apply_container.workspace.apply(str(proposal.id)))
    assert applied.status == VirtualChangeStatus.APPLIED.value


def test_apply_failure_records_test_output(
    apply_settings, db_schema: None, workspace_root
) -> None:
    """落盘后测试不过 → failed，且 apply_error 必须带测试输出（§7.6 失败必留痕）。

    只留一句"测试未通过"的话，人看不出是哪个用例挂了、下一轮该改哪里。
    """
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")
    failing = workspace_root / "failing_check.py"
    failing.write_text(
        "import sys\nprint('E   assert 1 == 2')\nsys.exit(1)\n", encoding="utf-8"
    )
    settings = apply_settings.model_copy(
        update={"test_command": f"{shlex.quote(sys.executable)} {shlex.quote(str(failing))}"}
    )
    container = Container(settings)
    proposal = _propose(container)

    with pytest.raises(ApplyFailedError):
        asyncio.run(container.workspace.apply(str(proposal.id)))

    stored = asyncio.run(container.workspace.get(str(proposal.id)))
    assert stored.status == VirtualChangeStatus.FAILED.value
    assert stored.apply_error is not None
    assert "测试未通过" in stored.apply_error
    assert "assert 1 == 2" in stored.apply_error


def test_apply_without_workspace_root_is_rejected(container: Container) -> None:
    """未配置 FLUX_WORKSPACE_ROOT 时拒绝落盘：绝不默认写进某个"看起来还行"的目录。"""
    proposal = _propose(container)
    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(container.workspace.apply(str(proposal.id)))
    assert "未配置工作区根目录" in excinfo.value.message
    # 状态留在 accepted（已批准但未落盘），不是 failed——这不是改动本身的问题
    assert asyncio.run(container.workspace.get(str(proposal.id))).status == "accepted"


def test_rejected_proposal_cannot_be_applied(container: Container) -> None:
    proposal = _propose(container)
    asyncio.run(container.workspace.reject(str(proposal.id)))
    with pytest.raises(InvalidTransitionError):
        asyncio.run(container.workspace.apply(str(proposal.id)))


def test_applied_proposal_is_terminal(apply_container: Container, workspace_root) -> None:
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")

    proposal = _propose(apply_container)
    asyncio.run(apply_container.workspace.apply(str(proposal.id)))
    with pytest.raises(InvalidTransitionError):
        asyncio.run(apply_container.workspace.reject(str(proposal.id)))


def test_failed_is_terminal_and_reachable_from_accepted(container: Container) -> None:
    proposal = _propose(container)
    asyncio.run(container.workspace.accept(str(proposal.id)))
    failed = asyncio.run(container.workspace.mark_failed(str(proposal.id)))
    assert failed.status == VirtualChangeStatus.FAILED.value
    with pytest.raises(InvalidTransitionError):
        asyncio.run(container.workspace.accept(str(proposal.id)))


def test_transition_publishes_workspace_event(container: Container) -> None:
    proposal = _propose(container)
    container.bus.clear()
    asyncio.run(container.workspace.accept(str(proposal.id)))

    events = [
        payload for event, payload in container.bus.history if event == Events.WORKSPACE_CHANGED
    ]
    assert events[-1]["to"] == "accepted"
    assert events[-1]["from"] == "pending"
    assert events[-1]["change_id"] == str(proposal.id)


def test_list_filters_by_status(container: Container) -> None:
    first = _propose(container, file_path="a.py")
    _propose(container, file_path="b.py")
    asyncio.run(container.workspace.reject(str(first.id)))

    assert len(asyncio.run(container.workspace.list())) == 2
    assert len(asyncio.run(container.workspace.list(status="rejected"))) == 1
    assert len(asyncio.run(container.workspace.list(status=VirtualChangeStatus.PENDING))) == 1


def test_proposals_survive_process_restart(settings, db_schema) -> None:
    """重启后审核队列不丢：换一个全新的 Container（新引擎）仍能读到同一批提案。"""
    first_container = Container(settings)
    proposal = _propose(first_container, file_path="auth/login.py")
    proposal_id = str(proposal.id)
    asyncio.run(first_container.dispose())

    restarted = Container(settings)
    reloaded = asyncio.run(restarted.workspace.list())
    assert [str(c.id) for c in reloaded] == [proposal_id]
    assert asyncio.run(restarted.workspace.get(proposal_id)).file_path == "auth/login.py"
    asyncio.run(restarted.dispose())


def test_unknown_change_raises_not_found(container: Container) -> None:
    with pytest.raises(NotFoundError):
        asyncio.run(container.workspace.get("0f5b6f4c-0000-0000-0000-000000000000"))
    with pytest.raises(NotFoundError):
        asyncio.run(container.workspace.get("不是-uuid"))
    with pytest.raises(NotFoundError):
        asyncio.run(container.workspace.get("不存在"))


def test_propose_with_unknown_project_raises_not_found(container: Container) -> None:
    with pytest.raises(NotFoundError):
        _propose(container, project_id="0f5b6f4c-0000-0000-0000-000000000000")


def test_propose_with_malformed_owner_id_is_validation_error(container: Container) -> None:
    with pytest.raises(ValidationError):
        _propose(container, task_id="not-a-uuid")
