"""需求 → 提案链路测试（实施计划 ⑫ / 主规格 §12.5 的 `POST /workspace/generate`）。

不 mock 数据库、不 mock 文件系统：用桩 Provider 只替换"模型返回的文本"，
验证的是真实链路：读真实文件现状 → Developer Agent → 契约解析 → 逐文件落成 pending 提案。
"""

from __future__ import annotations

import asyncio
import json
import uuid
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import builtin_manifests
from flux.core.model_gateway.base import ChatMessage, ChatResult, ModelProviderBase, TokenUsage
from flux.core.model_gateway.router import ModelRouter
from flux.core.virtual_workspace.flow import DeveloperProposalFlow
from flux.enums import ModelProvider
from flux.errors import ValidationError

PREFIX = "/api/v1"
ORIGINAL = "def login(user):\n    return False\n"
PROPOSED = "def login(user):\n    return check_password(user)\n"


class StubProvider(ModelProviderBase):
    """固定返回一段文本的供应商桩，用来模拟模型的原始输出。"""

    provider = ModelProvider.LOCAL

    def __init__(self, content: str) -> None:
        super().__init__(model_name="stub-json")
        self.content = content
        self.prompts: list[str] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        self.prompts.append(messages[-1].content if messages else "")
        return ChatResult(
            content=self.content,
            provider=self.provider,
            model="stub-json",
            usage=TokenUsage(input_tokens=120, output_tokens=220),
            latency_ms=3,
        )


def _model_reply(*changes: dict[str, str], summary: str = "实现登录校验") -> str:
    return json.dumps({"summary": summary, "changes": list(changes)}, ensure_ascii=False)


def _inject_stub(container: Container, raw: str) -> StubProvider:
    """把容器里的模型供应商换成桩，并按 Manifest 重建 Developer Agent。"""
    stub = StubProvider(raw)
    container.agents = AgentManager(ModelRouter({ModelProvider.LOCAL: stub}), container.bus)
    for name in ("developer", "dev_flow"):
        container.__dict__.pop(name, None)  # cached_property：换了 AgentManager 必须重建
    return stub


def _flow(
    container: Container, raw: str, *, root: Any
) -> tuple[DeveloperProposalFlow, StubProvider]:
    stub = _inject_stub(container, raw)
    return (
        DeveloperProposalFlow(container.developer, container.workspace, workspace_root=root),
        stub,
    )


# --- 链路本体 ---


def test_produce_lands_one_pending_proposal_per_file(
    apply_container: Container, workspace_root
) -> None:
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")
    flow, _ = _flow(
        apply_container,
        _model_reply(
            {"path": "auth/login.py", "content": PROPOSED, "reason": "改成真正的校验"},
            {"path": "tests/test_login.py", "content": "def test_login():\n    assert True\n"},
        ),
        root=workspace_root,
    )

    outcome = asyncio.run(flow.produce("给登录加真正的密码校验", paths=["auth/login.py"]))

    assert outcome.summary == "实现登录校验"
    assert outcome.files == ("auth/login.py", "tests/test_login.py")
    assert [p.status for p in outcome.proposals] == ["pending", "pending"]
    assert [p.agent_source for p in outcome.proposals] == ["developer", "developer"]
    edited = outcome.proposals[0].to_dict()
    assert edited["original_content"] == ORIGINAL
    assert edited["proposed_content"] == PROPOSED
    assert edited["diff"].startswith("---")
    assert (edited["added_lines"], edited["removed_lines"]) == (1, 1)
    assert edited["reason"] == "改成真正的校验"
    # 未提供原文的路径视为新建文件：原文为空串（Apply 时据此判断"文件本不该存在"）
    created = outcome.proposals[1].to_dict()
    assert created["original_content"] == ""
    assert created["original_hash"]


def test_produce_reads_current_file_content_into_prompt(
    apply_container: Container, workspace_root
) -> None:
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")
    flow, stub = _flow(
        apply_container,
        _model_reply({"path": "auth/login.py", "content": PROPOSED}),
        root=workspace_root,
    )

    asyncio.run(flow.produce("加校验", paths=["auth/login.py"]))

    prompt = stub.prompts[-1]
    assert "## 相关文件现状（改动前）" in prompt
    assert "### auth/login.py" in prompt
    assert ORIGINAL.strip() in prompt
    assert "加校验" in prompt


def test_produce_without_paths_carries_no_context(
    apply_container: Container, workspace_root
) -> None:
    flow, stub = _flow(
        apply_container,
        _model_reply({"path": "app/health.py", "content": "ok\n"}),
        root=workspace_root,
    )

    asyncio.run(flow.produce("新增健康检查"))

    assert "## 相关文件现状（改动前）" not in stub.prompts[-1]


def test_produce_links_task_and_project(apply_container: Container, workspace_root) -> None:
    flow, _ = _flow(
        apply_container,
        _model_reply({"path": "app/health.py", "content": "ok\n"}),
        root=workspace_root,
    )

    async def _run() -> tuple[dict[str, Any], str, str]:
        task = await apply_container.task_repo.create(
            task_id=uuid.uuid4(),
            description="加健康检查",
            priority=100,
            project_id=None,
            agent_id=None,
        )
        project = await apply_container.brain_repo.create_project(name="demo")
        outcome = await flow.produce("加健康检查", task_id=str(task.id), project_id=str(project.id))
        return outcome.proposals[0].to_dict(), str(task.id), str(project.id)

    change, task_id, project_id = asyncio.run(_run())

    assert change["task_id"] == task_id
    assert change["project_id"] == project_id


# --- 边界：fail-closed，不静默兜底 ---


def test_produce_requires_workspace_root_when_paths_given(container: Container) -> None:
    flow, _ = _flow(container, _model_reply({"path": "a.py", "content": "x\n"}), root=None)

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(flow.produce("改点东西", paths=["a.py"]))

    assert "未配置工作区根目录" in excinfo.value.message


def test_produce_rejects_paths_outside_root(apply_container: Container, workspace_root) -> None:
    flow, _ = _flow(
        apply_container, _model_reply({"path": "a.py", "content": "x\n"}), root=workspace_root
    )

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(flow.produce("偷看一下", paths=["../secret.py"]))

    assert "不得越出工作区根" in excinfo.value.message


def test_produce_rejects_too_many_context_files(apply_container: Container, workspace_root) -> None:
    flow, _ = _flow(
        apply_container, _model_reply({"path": "a.py", "content": "x\n"}), root=workspace_root
    )

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(flow.produce("改", paths=[f"f{index}.py" for index in range(6)]))

    assert "最多携带 5 个文件" in excinfo.value.message


def test_produce_rejects_model_output_without_json(
    apply_container: Container, workspace_root
) -> None:
    flow, _ = _flow(apply_container, "抱歉，这个需求我做不到。", root=workspace_root)

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(flow.produce("改点东西"))

    assert "未返回合法的 JSON 提案" in excinfo.value.message


def test_produce_rejects_change_set_without_real_change(
    apply_container: Container, workspace_root
) -> None:
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")
    flow, _ = _flow(
        apply_container,
        _model_reply({"path": "auth/login.py", "content": ORIGINAL}),
        root=workspace_root,
    )

    with pytest.raises(ValidationError) as excinfo:
        asyncio.run(flow.produce("把登录改好", paths=["auth/login.py"]))

    assert "没有任何有效改动" in excinfo.value.message


def test_produce_with_unconfigured_provider_fails_loudly(apply_container: Container) -> None:
    """一个供应商都没配置时不允许悄悄降级成"空提案"，必须明确报错。"""
    container = apply_container
    container.__dict__["router"] = ModelRouter({}, default_provider=ModelProvider.LOCAL)

    with pytest.raises(Exception) as excinfo:
        asyncio.run(container.developer.propose("改点东西"))

    assert "未配置" in str(excinfo.value)


# --- HTTP 层：/workspace/generate 走完整闭环 ---


def test_generate_endpoint_then_apply_changes_real_file(
    apply_client: TestClient, workspace_root
) -> None:
    """把 IDE 的"让 AI 改"按钮走到真实落盘：generate → accept → apply。"""
    (workspace_root / "auth").mkdir()
    (workspace_root / "auth" / "login.py").write_text(ORIGINAL, encoding="utf-8")
    container: Container = apply_client.app.state.container
    _inject_stub(container, _model_reply({"path": "auth/login.py", "content": PROPOSED}))

    generated = apply_client.post(
        f"{PREFIX}/workspace/generate",
        json={"instruction": "给登录加真正的密码校验", "paths": ["auth/login.py"]},
    )

    assert generated.status_code == 200
    body = generated.json()
    assert body["success"] is True
    assert body["data"]["summary"] == "实现登录校验"
    change_id = body["data"]["proposals"][0]["id"]
    assert body["data"]["proposals"][0]["status"] == "pending"
    # 生成提案不碰用户文件
    assert (workspace_root / "auth" / "login.py").read_text(encoding="utf-8") == ORIGINAL

    listed = apply_client.get(f"{PREFIX}/workspace/changes?status=pending").json()
    assert listed["metadata"]["count"] == 1

    accepted = apply_client.post(f"{PREFIX}/workspace/accept", json={"change_ids": [change_id]})
    assert accepted.json()["data"][0]["status"] == "accepted"

    applied = apply_client.post(f"{PREFIX}/workspace/apply", json={"change_ids": [change_id]})
    assert applied.json()["data"][0]["status"] == "applied"
    assert (workspace_root / "auth" / "login.py").read_text(encoding="utf-8") == PROPOSED


def test_generate_endpoint_without_workspace_root_is_validation_error(client: TestClient) -> None:
    container: Container = client.app.state.container
    _inject_stub(container, _model_reply({"path": "a.py", "content": "x\n"}))

    response = client.post(
        f"{PREFIX}/workspace/generate", json={"instruction": "改点东西", "paths": ["a.py"]}
    )

    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"


def test_generate_endpoint_requires_instruction(client: TestClient) -> None:
    response = client.post(f"{PREFIX}/workspace/generate", json={"instruction": ""})
    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"


def test_generate_endpoint_reports_bad_model_output_as_validation_error(
    apply_client: TestClient,
) -> None:
    container: Container = apply_client.app.state.container
    _inject_stub(container, "这次我什么都没想出来。")

    response = apply_client.post(f"{PREFIX}/workspace/generate", json={"instruction": "改点东西"})

    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"
    assert "未返回合法的 JSON 提案" in response.json()["message"]


def test_container_developer_falls_back_to_configured_provider(apply_container: Container) -> None:
    """Manifest 声明的 deepseek 没有密钥时，退回默认供应商（local），而不是直接报错。

    退回落同时必须换模型：Manifest 里写的是 deepseek 家的 `deepseek-flash`，
    把这个名字发给其他供应商会被上游直接拒掉。
    """
    developer = apply_container.developer

    assert developer.manifest.provider is ModelProvider.LOCAL
    assert developer.manifest.model == apply_container.settings.local_model_name
    # Manifest 文件本身没有被改写：退回落只在容器装配时生效
    assert builtin_manifests()["developer"].provider is ModelProvider.DEEPSEEK


def test_container_developer_fallback_uses_provider_model(
    settings: Settings, workspace_root: Path
) -> None:
    """回退到非 local 供应商时，模型 id 换成该供应商配置里的默认模型，而不是 Manifest 的。"""
    # 场景：deepseek 没配，但 anthropic 配了，默认供应商 = anthropic
    settings.default_provider = ModelProvider.ANTHROPIC.value
    settings.anthropic_api_key = "sk-test-anthropic"
    settings.anthropic_model = "claude-sonnet-5-5"
    container = Container(settings)

    manifest = container._developer_manifest()

    assert manifest.provider is ModelProvider.ANTHROPIC
    assert manifest.model == settings.anthropic_model
