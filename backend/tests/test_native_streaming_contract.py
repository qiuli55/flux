"""契约：每个 Agent runtime 必须显式声明是否原生逐 token 流式。

用户要求：
- 现有三个 Agent 尽可能原生逐 token，不许用「整段生成 → 后端切块回放」冒充流式；
- 不支持原生流式的 Agent 仍可正常绑定和使用，但档案必须带 `native_streaming=False`
  供客户端标注。

因此新增 runtime 必须显式登记取值（True/False），漏登记会被契约测试拦住。
"""

from __future__ import annotations

import pytest

from flux.core.agent_runtime.manager import AgentManager, AgentSpec
from flux.core.agent_runtime.runtimes.base import (
    KNOWN_RUNTIMES,
    RUNTIME_CODEX,
    RUNTIME_DSH,
    RUNTIME_NATIVE_STREAMING,
    RUNTIME_OPENCODE,
)
from flux.core.agent_runtime.runtimes.dsh_runtime import DshRuntime
from flux.enums import AgentRole


def test_every_known_runtime_declares_streaming_explicitly() -> None:
    assert set(RUNTIME_NATIVE_STREAMING) == set(KNOWN_RUNTIMES)


@pytest.mark.parametrize("runtime", [RUNTIME_DSH, RUNTIME_CODEX, RUNTIME_OPENCODE])
def test_current_runtimes_are_native_streaming(runtime: str) -> None:
    assert RUNTIME_NATIVE_STREAMING[runtime] is True


def test_builtin_dsh_runtime_advertises_native_streaming() -> None:
    # 内置 Agent 的增量由 llm_stream_tap 从模型 SSE 旁路取得
    assert DshRuntime.runtime_id == RUNTIME_DSH
    assert DshRuntime.native_streaming is True


def test_all_known_runtimes_pass_validation() -> None:
    for runtime in KNOWN_RUNTIMES:
        AgentManager._validate_runtime(runtime)


async def test_non_streaming_agent_is_allowed_and_marked(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """不能原生流式的 runtime 也能创建 Agent，只是档案要标注 native_streaming=False。"""
    monkeypatch.setitem(RUNTIME_NATIVE_STREAMING, RUNTIME_CODEX, False)
    AgentManager._validate_runtime(RUNTIME_CODEX)

    handle = await AgentManager().create(
        AgentSpec(name="legacy-agent", role=AgentRole.DEVELOPER, runtime=RUNTIME_CODEX)
    )
    payload = handle.to_dict()
    spec = payload["spec"]
    assert isinstance(spec, dict)
    assert spec["native_streaming"] is False


async def test_streaming_agent_is_marked_true() -> None:
    handle = await AgentManager().create(
        AgentSpec(name="native-agent", role=AgentRole.DEVELOPER, runtime=RUNTIME_OPENCODE)
    )
    spec = handle.to_dict()["spec"]
    assert isinstance(spec, dict)
    assert spec["native_streaming"] is True
