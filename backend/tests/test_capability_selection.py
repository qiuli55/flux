"""技能启用状态 / 移除 / 本轮指定 的回归。

关注三件事：
- 已导入能力默认启用，停用后不再自动进入执行指令；
- 移除只删注册表，不删用户磁盘上的源目录；
- 「本次指定」出现在本轮能力段落，空指定时仍保留已启用技能的自动使用。
"""

from __future__ import annotations

import asyncio
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from flux.container import Container
from flux.core.agent_runtime.runtimes.service import _capability_sections, _execution_instruction
from flux.core.capability_import.repository import ImportedCapabilityRepository
from flux.core.capability_import.selection import (
    ConnectorBrief,
    SkillBrief,
    capability_instruction_sections,
)
from flux.core.capability_import.service import CapabilityImportService
from flux.enums import CapabilityKind
from flux.models.task import Task

SKILL_SPEC = {
    "name": "review-helper",
    "source": "/tmp/review-helper",
    "entry": "SKILL.md",
    "description": "按清单做代码审查",
    "requires_bins": [],
    "requires_skills": [],
    "permissions": [],
    "network": False,
    "files": [],
    "version": "1.2.0",
}


def _insert_skill(container: Container, name: str = "review-helper", source: str = "/tmp/x"):
    spec = dict(SKILL_SPEC, name=name, source=source)
    return asyncio.run(
        ImportedCapabilityRepository(container.session_factory).insert(
            kind=CapabilityKind.SKILL,
            name=name,
            spec=spec,
            fingerprint="f" * 64,
            source=source,
            version="1.2.0",
        )
    )


def test_enabled_defaults_true_and_disabled_skill_is_not_automatic(container: Container) -> None:
    record = _insert_skill(container)
    assert record.to_dict()["enabled"] is True

    service = CapabilityImportService(ImportedCapabilityRepository(container.session_factory))
    assert [item.name for item in asyncio.run(service.enabled_skills())] == ["review-helper"]

    asyncio.run(service.set_enabled(CapabilityKind.SKILL, "review-helper", enabled=False))
    assert asyncio.run(service.enabled_skills()) == []

    sections = asyncio.run(_capability_sections(container, [], []))
    text = "\n".join(sections)
    assert "可用技能" not in text
    assert "review-helper" not in text

    asyncio.run(service.set_enabled(CapabilityKind.SKILL, "review-helper", enabled=True))
    text = "\n".join(asyncio.run(_capability_sections(container, [], [])))
    assert "可用技能（已启用，按需使用）" in text
    assert "review-helper：按清单做代码审查" in text


def test_specified_skill_is_used_even_when_disabled(container: Container) -> None:
    record = _insert_skill(container)
    service = CapabilityImportService(ImportedCapabilityRepository(container.session_factory))
    asyncio.run(service.set_enabled(CapabilityKind.SKILL, "review-helper", enabled=False))

    text = "\n".join(asyncio.run(_capability_sections(container, [record.to_dict()["id"]], [])))

    assert "## 本轮指定使用" in text
    assert "技能：review-helper" in text
    # 停用项不会因为被指定就重新出现在「自动可用」段落
    assert "可用技能（已启用，按需使用）" not in text


def test_empty_specification_keeps_automatic_skills_available(container: Container) -> None:
    _insert_skill(container)
    text = "\n".join(asyncio.run(_capability_sections(container, [], [])))

    assert "可用技能（已启用，按需使用）" in text
    assert "本轮指定使用" not in text


def test_connector_specification_marks_unavailable_honestly() -> None:
    text = "\n".join(
        capability_instruction_sections(
            auto_skills=[],
            specified_skills=[SkillBrief(name="review-helper", description="审查")],
            specified_connectors=[
                ConnectorBrief(name="GitHub", status="connected"),
                ConnectorBrief(name="离线服务", status="error", available=True),
            ],
        )
    )

    assert "技能：review-helper：审查" in text
    assert "连接器：GitHub（已连接）" in text
    assert "连接器：离线服务（当前不可用：连接不可用；不要假装已调用）" in text


def test_instruction_places_capabilities_before_attachments() -> None:
    task = Task(description="看下这个仓库", id=uuid.uuid4())
    sections = capability_instruction_sections(
        auto_skills=[SkillBrief(name="review-helper", description="审查")],
        specified_connectors=[ConnectorBrief(name="GitHub", status="connected")],
    )
    instruction = _execution_instruction(
        task, None, [], capability_sections=sections, attachments=[{"path": "/tmp/a.txt"}]
    )

    assert instruction.index("## 可用技能") < instruction.index("## 本轮指定使用")
    assert instruction.index("## 本轮指定使用") < instruction.index("## 附件")


def test_remove_only_deletes_registry_record(container: Container, tmp_path: Path) -> None:
    skill_dir = tmp_path / "review-helper"
    skill_dir.mkdir()
    (skill_dir / "SKILL.md").write_text("---\nname: review-helper\n---\n", encoding="utf-8")
    _insert_skill(container, source=str(skill_dir))

    service = CapabilityImportService(ImportedCapabilityRepository(container.session_factory))
    asyncio.run(service.remove(CapabilityKind.SKILL, "review-helper"))

    assert asyncio.run(service.list(CapabilityKind.SKILL)) == []
    assert skill_dir.is_dir()
    assert (skill_dir / "SKILL.md").is_file()


def test_api_can_toggle_and_remove_imported_skill(client: TestClient, container: Container) -> None:
    _insert_skill(container, name="api-skill")

    listed = client.get("/api/v1/capabilities", params={"kind": "skill"}).json()["data"]
    assert listed[0]["name"] == "api-skill"
    assert listed[0]["enabled"] is True

    toggled = client.patch("/api/v1/capabilities/skill/api-skill", json={"enabled": False})
    assert toggled.status_code == 200, toggled.text
    assert toggled.json()["data"]["enabled"] is False

    removed = client.delete("/api/v1/capabilities/skill/api-skill")
    assert removed.status_code == 200, removed.text
    assert removed.json()["data"]["removed"] is True
    assert client.get("/api/v1/capabilities", params={"kind": "skill"}).json()["data"] == []

    missing = client.delete("/api/v1/capabilities/skill/api-skill")
    assert missing.status_code == 404


def test_missing_capability_toggle_returns_404(client: TestClient) -> None:
    response = client.patch("/api/v1/capabilities/skill/nope", json={"enabled": False})
    assert response.status_code == 404


@pytest.mark.parametrize("enabled", [True, False])
def test_list_includes_enabled_flag_for_every_record(
    client: TestClient, container: Container, enabled: bool
) -> None:
    _insert_skill(container, name=f"flag-{enabled}")
    client.patch(f"/api/v1/capabilities/skill/flag-{enabled}", json={"enabled": enabled})

    item = client.get("/api/v1/capabilities", params={"kind": "skill"}).json()["data"][0]
    assert item["enabled"] is enabled


def test_create_task_stores_this_send_specification_only(
    client: TestClient, container: Container
) -> None:
    """建任务时带上指定：先暂存，供首次 /start 消费；缺省时不留下任何锁定。"""
    created = client.post(
        "/api/v1/tasks",
        json={
            "description": "帮我看看这个仓库",
            "start": False,
            "specified_skill_ids": ["skill-1"],
            "specified_connector_ids": ["github"],
        },
    )
    assert created.status_code == 200, created.text
    task_id = uuid.UUID(created.json()["data"]["id"])

    stored = asyncio.run(container.task_repo.get(task_id))
    assert stored.pending_specified_capabilities == {
        "skills": ["skill-1"],
        "connectors": ["github"],
    }

    plain = client.post("/api/v1/tasks", json={"description": "普通对话", "start": False})
    assert plain.status_code == 200, plain.text
    plain_task = asyncio.run(container.task_repo.get(uuid.UUID(plain.json()["data"]["id"])))
    assert plain_task.pending_specified_capabilities is None

    # 清空后不残留：指定只属于这一次发送。
    asyncio.run(container.task_repo.set_pending_specification(task_id, None))
    assert asyncio.run(container.task_repo.get(task_id)).pending_specified_capabilities is None
