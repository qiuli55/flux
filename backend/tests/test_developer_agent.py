"""Developer Agent 测试（实施计划 §3.1 ③）。

用桩 Provider 注入「模型返回文本」，验证的是真实链路：Manifest → AgentHandle →
AgentManager 状态机 → 执行器 → 提案解析。
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from flux.core.agent_runtime.developer import (
    DeveloperAgent,
    build_developer_prompt,
    parse_code_change_set,
)
from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import builtin_manifests
from flux.core.event.bus import EventBus, Events
from flux.core.model_gateway.base import ChatMessage, ChatResult, ModelProviderBase, TokenUsage
from flux.core.model_gateway.router import ModelRouter
from flux.enums import AgentState, ModelProvider
from flux.errors import ValidationError

_VALID_PAYLOAD = {
    "summary": "新增 /health 健康检查接口",
    "changes": [
        {
            "path": "app/main.py",
            "content": (
                "from fastapi import FastAPI\n\napp = FastAPI()\n\n\n"
                "@app.get('/health')\ndef health():\n    return {'status': 'ok'}\n"
            ),
            "reason": "在 API 入口注册健康检查路由",
        },
        {
            "path": "tests/test_health.py",
            "content": "def test_health():\n    assert True\n",
            "reason": "补一个覆盖新接口的测试",
        },
    ],
}


class StubProvider(ModelProviderBase):
    """固定返回一段文本的供应商桩，用来模拟模型的原始输出。"""

    provider = ModelProvider.LOCAL

    def __init__(self, content: str) -> None:
        super().__init__(model_name="stub-json")
        self.content = content
        self.calls: list[list[ChatMessage]] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        self.calls.append(list(messages))
        return ChatResult(
            content=self.content,
            provider=self.provider,
            model="stub-json",
            usage=TokenUsage(input_tokens=120, output_tokens=220),
            latency_ms=3,
        )


def _developer(raw: str) -> tuple[DeveloperAgent, StubProvider, AgentManager]:
    provider = StubProvider(raw)
    manager = AgentManager(ModelRouter({ModelProvider.LOCAL: provider}))
    manifest = replace(
        builtin_manifests()["developer"], provider=ModelProvider.LOCAL, model="stub-json"
    )
    return DeveloperAgent(manager, manifest), provider, manager


async def test_propose_returns_code_change_set() -> None:
    agent, _, manager = _developer(json.dumps(_VALID_PAYLOAD))

    change_set = await agent.propose("给这个 FastAPI 项目增加一个健康检查接口")

    assert change_set.summary == "新增 /health 健康检查接口"
    assert change_set.paths == ("app/main.py", "tests/test_health.py")
    assert change_set.changes[0].reason == "在 API 入口注册健康检查路由"
    assert "/health" in change_set.changes[0].content
    # 提案跑在真实运行时上：Agent 注册在 manager 中且执行完成
    assert manager.count() == 1
    assert manager.get(agent.agent_id).state is AgentState.COMPLETED


async def test_propose_publishes_agent_events() -> None:
    provider = StubProvider(json.dumps(_VALID_PAYLOAD))
    bus = EventBus()
    manager = AgentManager(ModelRouter({ModelProvider.LOCAL: provider}), bus)
    manifest = replace(
        builtin_manifests()["developer"], provider=ModelProvider.LOCAL, model="stub-json"
    )
    agent = DeveloperAgent(manager, manifest)

    await agent.propose("补健康检查", task_id="t-dev-1")

    published = [event for event, _ in bus.history]
    assert Events.AGENT_STARTED in published
    assert Events.AGENT_COMPLETED in published
    payload = next(p for e, p in bus.history if e == Events.AGENT_STARTED)
    assert payload["task_id"] == "t-dev-1"
    assert payload["role"] == "developer"


async def test_prompt_carries_role_prompt_requirement_and_file_state() -> None:
    agent, provider, _ = _developer(json.dumps(_VALID_PAYLOAD))

    await agent.propose(
        "给这个 FastAPI 项目增加一个健康检查接口",
        files={"app/main.py": "app = FastAPI()\n"},
    )

    messages = provider.calls[0]
    assert messages[0].role == "system"
    assert "Developer Agent" in messages[0].content
    user = messages[-1]
    assert user.role == "user"
    assert "给这个 FastAPI 项目增加一个健康检查接口" in user.content
    assert "### app/main.py" in user.content
    assert "app = FastAPI()" in user.content
    assert '"changes"' in user.content  # 输出契约必须随请求下发


async def test_propose_never_touches_real_files(tmp_path: Path) -> None:
    """核心产品原则：Agent 只产出提案，磁盘上真实文件必须一个字节都不变。"""
    target = tmp_path / "app" / "main.py"
    target.parent.mkdir(parents=True)
    original = "app = FastAPI()\n"
    target.write_text(original, encoding="utf-8")

    agent, _, _ = _developer(json.dumps(_VALID_PAYLOAD))
    await agent.propose("加健康检查", files={"app/main.py": original})

    assert target.read_text(encoding="utf-8") == original
    assert not (tmp_path / "tests" / "test_health.py").exists()


async def test_propose_retries_nothing_on_invalid_output() -> None:
    """解析失败必须显式报错，不能偷偷返回空提案。"""
    agent, _, _ = _developer("抱歉，我需要更多信息才能完成这个需求。")

    with pytest.raises(ValidationError) as excinfo:
        await agent.propose("加健康检查")

    assert "未返回合法的 JSON 提案" in excinfo.value.message
    assert excinfo.value.code == "validation_error"


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("```json\n" + json.dumps(_VALID_PAYLOAD) + "\n```", "fenced"),
        (
            "好的，改动如下：\n\n```json\n"
            + json.dumps(_VALID_PAYLOAD)
            + "\n```\n\n以上就是全部改动。",
            "prose",
        ),
        ("{\n" + json.dumps(_VALID_PAYLOAD)[1:-1] + "\n}\n（改动已完成）", "trailing"),
        (json.dumps(_VALID_PAYLOAD), "plain"),
    ],
)
def test_parse_tolerates_common_model_output_shapes(raw: str, expected: str) -> None:
    change_set = parse_code_change_set(raw)
    assert change_set.paths == ("app/main.py", "tests/test_health.py"), expected


def test_parse_rejects_absolute_and_escaping_paths() -> None:
    for path in ("/etc/passwd", "../outside.py", "app/../../outside.py"):
        payload = {"summary": "x", "changes": [{"path": path, "content": "x"}]}
        with pytest.raises(ValidationError) as excinfo:
            parse_code_change_set(json.dumps(payload))
        assert "path" in excinfo.value.message


def test_parse_normalises_paths() -> None:
    payload = {"summary": "x", "changes": [{"path": "./app//main.py", "content": "x"}]}
    assert parse_code_change_set(json.dumps(payload)).paths == ("app/main.py",)


def test_parse_rejects_duplicate_paths() -> None:
    payload = {
        "summary": "x",
        "changes": [{"path": "a.py", "content": "1"}, {"path": "./a.py", "content": "2"}],
    }
    with pytest.raises(ValidationError) as excinfo:
        parse_code_change_set(json.dumps(payload))
    assert "重复路径" in excinfo.value.message


@pytest.mark.parametrize(
    ("payload", "expected"),
    [
        ([], "顶层必须是 JSON 对象"),
        ({}, "changes 必须是非空数组"),
        ({"changes": []}, "changes 必须是非空数组"),
        ({"changes": "app/main.py"}, "changes 必须是非空数组"),
        ({"changes": [{"content": "x"}]}, "path 必须是非空字符串"),
        ({"changes": [{"path": "a.py"}]}, "content 必须是字符串"),
        ({"changes": [{"path": "a.py", "content": 1}]}, "content 必须是字符串"),
        ({"changes": [{"path": "a.py", "content": "x", "reason": 1}]}, "reason 必须是字符串"),
        ({"summary": 1, "changes": [{"path": "a.py", "content": "x"}]}, "summary 必须是字符串"),
        ({"changes": ["a.py"]}, "changes[0] 必须是对象"),
    ],
)
def test_parse_rejects_malformed_payload(payload: Any, expected: str) -> None:
    with pytest.raises(ValidationError) as excinfo:
        parse_code_change_set(json.dumps(payload))
    assert expected in excinfo.value.message


def test_parse_preview_is_truncated() -> None:
    with pytest.raises(ValidationError) as excinfo:
        parse_code_change_set("{" + "x" * 5000)
    preview = excinfo.value.details["raw_preview"]
    assert len(preview) == 301 and preview.endswith("…")


def test_build_prompt_without_files() -> None:
    prompt = build_developer_prompt("修复登录报错", {})
    assert "修复登录报错" in prompt
    assert "相关文件现状" not in prompt
    assert "输出契约" in prompt
