"""Virtual Workspace 测试（主规格 §7）。"""

from __future__ import annotations

import asyncio

import pytest

from flux.core.virtual_workspace.service import VirtualWorkspaceService, build_unified_diff
from flux.enums import VirtualChangeStatus
from flux.errors import InvalidTransitionError, NotFoundError

ORIGINAL = "def login(user):\n    return False\n"
PROPOSED = "def login(user):\n    return check_password(user)\n"


def _service() -> VirtualWorkspaceService:
    return VirtualWorkspaceService()


def _propose(service: VirtualWorkspaceService):
    return service.propose(
        project_id="proj-1",
        file_path="auth/login.py",
        original_content=ORIGINAL,
        proposed_content=PROPOSED,
        agent_source="developer-agent",
    )


def test_unified_diff_marks_changed_line() -> None:
    diff = build_unified_diff("auth/login.py", ORIGINAL, PROPOSED)
    assert "--- a/auth/login.py" in diff
    assert "+++ b/auth/login.py" in diff
    assert "-    return False" in diff
    assert "+    return check_password(user)" in diff


def test_proposal_starts_pending_with_diff() -> None:
    proposal = _propose(_service())
    assert proposal.status is VirtualChangeStatus.PENDING
    assert proposal.diff
    assert proposal.agent_source == "developer-agent"


def test_apply_pending_goes_through_accepted() -> None:
    """pending → accepted → applied（§7.2 / §7.5 调用方即人工批准）。"""
    service = _service()
    proposal = _propose(service)
    applied = asyncio.run(service.apply(proposal.id))
    assert applied.status is VirtualChangeStatus.APPLIED


def test_rejected_proposal_cannot_be_applied() -> None:
    service = _service()
    proposal = _propose(service)
    asyncio.run(service.reject(proposal.id))
    with pytest.raises(InvalidTransitionError):
        asyncio.run(service.apply(proposal.id))


def test_applied_proposal_is_terminal() -> None:
    service = _service()
    proposal = _propose(service)
    asyncio.run(service.apply(proposal.id))
    with pytest.raises(InvalidTransitionError):
        asyncio.run(service.reject(proposal.id))


def test_list_filters_by_project_and_status() -> None:
    service = _service()
    first = _propose(service)
    service.propose(
        project_id="proj-2",
        file_path="b.py",
        original_content="a\n",
        proposed_content="b\n",
    )
    assert len(service.list()) == 2
    assert len(service.list(project_id="proj-1")) == 1
    asyncio.run(service.reject(first.id))
    assert len(service.list(status="rejected")) == 1


def test_unknown_change_raises_not_found() -> None:
    with pytest.raises(NotFoundError):
        _service().get("不存在")
