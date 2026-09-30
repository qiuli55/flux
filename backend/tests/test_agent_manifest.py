"""Agent Manifest 测试（主规格 §6.2；实施计划 §3.1）。"""

from __future__ import annotations

from pathlib import Path

import pytest

from flux.container import Container
from flux.core.agent_runtime.manifest import (
    MANIFEST_DIR,
    AgentManifest,
    builtin_manifests,
    builtin_specs,
    load_manifests,
)
from flux.enums import AgentRole, AgentState, Capability, ModelProvider
from flux.errors import NotFoundError, ValidationError

_BASE_YAML = """
name: developer
role: developer
model:
  provider: deepseek
  model: deepseek-flash
skills:
  - backend
tools:
  - filesystem
permissions:
  - file.read
  - file.write
"""


def test_builtin_manifests_cover_first_four_agents() -> None:
    manifests = builtin_manifests()
    assert sorted(manifests) == ["developer", "reviewer", "tech-lead", "tester"]
    assert [str(m.role) for m in manifests.values()] == [
        "developer",
        "reviewer",
        "tech_lead",
        "tester",
    ]
    # Architect / DevOps 本轮不做（§19.7）
    assert AgentRole.ARCHITECT not in {m.role for m in manifests.values()}


def test_manifests_directory_has_no_extra_files() -> None:
    """内置目录只存放 4 份 Manifest，防止出现没人维护的孤儿文件。"""
    assert sorted(p.name for p in MANIFEST_DIR.glob("*.yaml")) == [
        "developer.yaml",
        "reviewer.yaml",
        "tech_lead.yaml",
        "tester.yaml",
    ]


def test_builtin_specs_map_to_agent_spec() -> None:
    specs = {spec.name: spec for spec in builtin_specs()}
    developer = specs["developer"]
    assert developer.role is AgentRole.DEVELOPER
    assert developer.model_provider is ModelProvider.DEEPSEEK
    assert developer.model_name == "deepseek-flash"
    assert developer.skills == ("backend",)
    assert Capability.FILE_WRITE in developer.permissions
    assert Capability.TERMINAL_EXECUTE in developer.permissions


def test_read_only_agents_do_not_hold_write_permission() -> None:
    """最小权限：Tech Lead / Reviewer 不写文件。"""
    manifests = builtin_manifests()
    for name in ("tech-lead", "reviewer"):
        permissions = manifests[name].permissions
        assert permissions == frozenset({Capability.FILE_READ})


def test_manifest_to_dict_round_trip() -> None:
    manifest = AgentManifest.from_yaml(_BASE_YAML)
    assert manifest.to_dict() == {
        "name": "developer",
        "role": "developer",
        "model": {"provider": "deepseek", "model": "deepseek-flash"},
        "description": "",
        "skills": ["backend"],
        "tools": ["filesystem"],
        "permissions": ["file.read", "file.write"],
    }
    # to_dict 的输出不含任何密钥字段，可安全回传给前端
    assert "key" not in str(manifest.to_dict()).lower()


def test_role_and_provider_are_case_insensitive() -> None:
    """Manifest 里写 Developer / DEEPSEEK 也应被接受（人类手写 YAML 的常见写法）。"""
    manifest = AgentManifest.from_yaml(
        "name: d\nrole: Developer\nmodel:\n  provider: DEEPSEEK\n  model: deepseek-flash\n"
    )
    assert manifest.role is AgentRole.DEVELOPER
    assert manifest.provider is ModelProvider.DEEPSEEK
    assert manifest.permissions == frozenset()


def test_manifest_rejects_api_key() -> None:
    """Manifest 不得携带 API Key（§14.3），顶层的 key 与嵌套在 model 下的都要拦。"""
    with pytest.raises(ValidationError) as top:
        AgentManifest.from_yaml(_BASE_YAML + "\napi_key: sk-abcdef\n")
    assert "密钥字段" in top.value.message
    assert top.value.code == "validation_error"

    nested = """
name: d
role: developer
model:
  provider: deepseek
  model: deepseek-flash
  api_token: sk-abcdef
"""
    with pytest.raises(ValidationError):
        AgentManifest.from_yaml(nested)


def test_manifest_rejects_unknown_field() -> None:
    with pytest.raises(ValidationError) as excinfo:
        AgentManifest.from_yaml(_BASE_YAML + "\nasdf: 1\n")
    assert "unknown" not in excinfo.value.message.lower()
    assert "asdf" in excinfo.value.message


@pytest.mark.parametrize(
    ("yaml_text", "expected"),
    [
        ("name: d\nrole: wizard\nmodel:\n  provider: deepseek\n  model: m\n", "未知角色"),
        ("name: d\nrole: developer\nmodel:\n  provider: gemini\n  model: m\n", "未知模型供应商"),
        (
            "name: d\nrole: developer\nmodel:\n  provider: deepseek\n  model: m\n"
            "permissions:\n  - file.destroy\n",
            "未知权限项",
        ),
        ("role: developer\nmodel:\n  provider: deepseek\n  model: m\n", "name"),
        ("name: d\nmodel:\n  provider: deepseek\n  model: m\n", "role"),
        ("name: d\nrole: developer\n", "model"),
        ("name: d\nrole: developer\nmodel:\n  provider: deepseek\n", "model.model"),
        ("name: ''\nrole: developer\nmodel:\n  provider: deepseek\n  model: m\n", "name"),
        ("name: d\nrole: developer\nmodel: deepseek-flash\n", "model"),
        (
            "name: d\nrole: developer\nmodel:\n  provider: deepseek\n  model: m\nskills: backend\n",
            "skills",
        ),
        ("[]", "顶层必须是映射"),
        ("", "内容为空"),
    ],
)
def test_manifest_validation_failures(yaml_text: str, expected: str) -> None:
    with pytest.raises(ValidationError) as excinfo:
        AgentManifest.from_yaml(yaml_text)
    assert expected in excinfo.value.message


def test_manifest_reports_yaml_syntax_error() -> None:
    with pytest.raises(ValidationError) as excinfo:
        AgentManifest.from_yaml("name: d\nrole: [developer\n")
    assert "YAML 解析失败" in excinfo.value.message


def test_load_missing_file_raises_not_found(tmp_path: Path) -> None:
    with pytest.raises(NotFoundError):
        AgentManifest.load(tmp_path / "nope.yaml")
    assert load_manifests(tmp_path) == {}


def test_load_manifests_rejects_duplicate_names(tmp_path: Path) -> None:
    for filename in ("a.yaml", "b.yaml"):
        (tmp_path / filename).write_text(_BASE_YAML, encoding="utf-8")
    with pytest.raises(ValidationError) as excinfo:
        load_manifests(tmp_path)
    assert "名称重复" in excinfo.value.message


def test_manager_creates_builtin_agents(container: Container) -> None:
    handles = container.agents.create_builtin_agents()
    assert sorted(handles) == ["developer", "reviewer", "tech-lead", "tester"]
    assert all(handle.state is AgentState.READY for handle in handles.values())
    developer = handles["developer"]
    assert developer.spec.model_name == "deepseek-flash"
    assert container.agents.get(developer.id_str) is developer
