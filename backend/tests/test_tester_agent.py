"""Tester Agent 测试（实施计划 ⑧）。

用桩 Provider 注入「模型返回文本」，验证真实链路：Manifest → AgentHandle →
测试命令执行（真实子进程 + 真实临时目录）→ 结构化回报 → 失败时的模型分析。
"""

from __future__ import annotations

import asyncio
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import builtin_manifests
from flux.core.agent_runtime.tester import (
    TestCounts,
    TesterAgent,
    TestReport,
    build_analysis_prompt,
    parse_test_counts,
)
from flux.core.model_gateway.base import ChatMessage, ChatResult, ModelProviderBase, TokenUsage
from flux.core.model_gateway.router import ModelRouter
from flux.core.virtual_workspace.test_runner import MAX_OUTPUT_CHARS, TestRunner
from flux.enums import AgentState, Capability, ModelProvider
from flux.errors import ValidationError

ANALYSIS = "1) 直接原因：add 返回 a*b；2) 根因：实现写错；3) 下一步：修 math_utils.py 的 add。"


def _run(coro: Any) -> Any:
    return asyncio.run(coro)


class StubProvider(ModelProviderBase):
    """固定返回一段文本的供应商桩。"""

    provider = ModelProvider.LOCAL

    def __init__(self, content: str = ANALYSIS) -> None:
        super().__init__(model_name="stub-analysis")
        self.content = content
        self.calls: list[list[ChatMessage]] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        self.calls.append(list(messages))
        return ChatResult(
            content=self.content,
            provider=self.provider,
            model="stub-analysis",
            usage=TokenUsage(input_tokens=90, output_tokens=60),
            latency_ms=2,
        )


def _tester(
    *,
    test_command: str | None = None,
    workspace_root: Path | None = None,
    timeout_seconds: float = 300.0,
) -> tuple[TesterAgent, StubProvider, AgentManager]:
    provider = StubProvider()
    manager = AgentManager(ModelRouter({ModelProvider.LOCAL: provider}))
    manifest = replace(
        builtin_manifests()["tester"], provider=ModelProvider.LOCAL, model="stub-analysis"
    )
    agent = TesterAgent(
        manager,
        manifest,
        test_command=test_command,
        workspace_root=workspace_root,
        timeout_seconds=timeout_seconds,
    )
    return agent, provider, manager


# --- Manifest 与身份 ---


def test_tester_agent_uses_builtin_manifest() -> None:
    agent, _, _ = _tester(test_command="exit 0")
    assert agent.manifest.name == "tester"
    expected = frozenset({Capability.FILE_READ, Capability.TERMINAL_EXECUTE})
    assert agent.manifest.permissions == expected
    # 最小权限：Tester 不能写文件，只能读 + 跑命令
    assert Capability.FILE_WRITE not in agent.manifest.permissions
    assert agent.manifest.system_prompt
    assert agent.agent_id


def test_agent_runs_through_manager_lifecycle(workspace_root: Path) -> None:
    agent, _, manager = _tester(test_command="exit 0", workspace_root=workspace_root)
    report = _run(agent.run_tests())
    assert report.passed is True
    assert manager.get(agent.agent_id).state is AgentState.READY


# --- 执行测试 ---


def test_run_tests_parses_pytest_summary(workspace_root: Path) -> None:
    agent, _, _ = _tester(
        test_command="echo '14 passed in 0.12s' && exit 0", workspace_root=workspace_root
    )
    report = _run(agent.run_tests())

    assert report.passed is True
    assert report.exit_code == 0
    assert report.counts.passed == 14
    assert report.counts.total == 14
    assert report.summary == "14 passed"
    assert "14 passed" in report.output


def test_run_tests_reports_failures(workspace_root: Path) -> None:
    agent, _, _ = _tester(
        test_command="echo '2 passed, 1 failed in 0.20s' && exit 1", workspace_root=workspace_root
    )
    report = _run(agent.run_tests())

    assert report.passed is False
    assert report.exit_code == 1
    assert report.summary == "2 passed, 1 failed"
    assert report.to_dict()["counts"] == {"passed": 2, "failed": 1, "errors": 0, "skipped": 0}


def test_run_tests_runs_inside_workspace_root(workspace_root: Path) -> None:
    (workspace_root / "marker.txt").write_text("ok\n", encoding="utf-8")
    agent, _, _ = _tester(test_command="ls", workspace_root=workspace_root)

    report = _run(agent.run_tests())

    assert "marker.txt" in report.output


def test_run_tests_accepts_explicit_command_and_root(workspace_root: Path, tmp_path: Path) -> None:
    other = tmp_path / "other"
    other.mkdir()
    (other / "other.txt").write_text("ok\n", encoding="utf-8")
    agent, _, _ = _tester(test_command="ls", workspace_root=workspace_root)

    report = _run(agent.run_tests("ls", workspace_root=other))

    assert "other.txt" in report.output
    assert "marker.txt" not in report.output


def test_run_tests_without_command_is_rejected(workspace_root: Path) -> None:
    agent, _, _ = _tester(workspace_root=workspace_root)
    with pytest.raises(ValidationError) as excinfo:
        _run(agent.run_tests())
    assert "未配置测试命令" in excinfo.value.message


def test_run_tests_without_workspace_root_is_rejected() -> None:
    agent, _, _ = _tester(test_command="exit 0")
    with pytest.raises(ValidationError) as excinfo:
        _run(agent.run_tests())
    assert "未配置工作区根目录" in excinfo.value.message


def test_run_tests_reports_timeout(workspace_root: Path) -> None:
    agent, _, _ = _tester(
        test_command="sleep 5", workspace_root=workspace_root, timeout_seconds=0.2
    )

    report = _run(agent.run_tests())

    assert report.timed_out is True
    assert report.passed is False
    assert "测试超时" in report.summary


# --- 回报：失败时分析 ---


def test_verify_passing_does_not_call_model(workspace_root: Path) -> None:
    """测试通过就不该花模型的钱：verify 只在失败时调用模型。"""
    agent, provider, _ = _tester(test_command="exit 0", workspace_root=workspace_root)

    result = _run(agent.verify())

    assert result.report.passed is True
    assert result.analysis is None
    assert provider.calls == []
    assert result.to_dict()["report"]["passed"] is True


def test_verify_failing_asks_model_for_analysis(workspace_root: Path) -> None:
    agent, provider, manager = _tester(
        test_command="echo '1 failed in 0.05s' && exit 1", workspace_root=workspace_root
    )

    result = _run(agent.verify(instruction="修复 add 的加法实现", task_id="task-7"))

    assert result.report.passed is False
    assert result.analysis == ANALYSIS
    assert len(provider.calls) == 1
    prompt = provider.calls[0][-1].content
    assert "修复 add 的加法实现" in prompt
    assert "1 failed in 0.05s" in prompt
    assert "退出码：1" in prompt
    assert manager.get(agent.agent_id).state is AgentState.COMPLETED


def test_verify_analyzes_timeout_too(workspace_root: Path) -> None:
    agent, provider, _ = _tester(
        test_command="sleep 5", workspace_root=workspace_root, timeout_seconds=0.2
    )

    result = _run(agent.verify())

    assert result.report.timed_out is True
    assert result.analysis == ANALYSIS
    assert "超时：True" in provider.calls[0][-1].content


def test_build_analysis_prompt_truncates_long_output() -> None:
    report = TestReport(
        command="pytest -q",
        exit_code=1,
        passed=False,
        timed_out=False,
        duration_ms=120,
        output="x" * 9000,
        counts=TestCounts(passed=1, failed=1),
    )
    prompt = build_analysis_prompt(report)
    assert "输出已截断" in prompt
    assert len(prompt) < 9000
    assert "（未提供任务描述）" in prompt


def test_truncated_output_keeps_the_summary_line() -> None:
    """输出超长时必须保留末尾的汇总行，否则回报不出 `Tests: N passed`（⑧ 的验收要求）。"""
    text = ("F" * 30000) + "\n===== 12 passed, 2 failed in 1.02s =====\n"

    truncated = TestRunner._truncate(text)

    assert len(truncated) <= MAX_OUTPUT_CHARS
    assert "输出已截断" in truncated
    counts = parse_test_counts(truncated)
    assert (counts.passed, counts.failed) == (12, 2)


# --- 统计解析 ---


@pytest.mark.parametrize(
    ("output", "expected"),
    [
        ("14 passed in 0.12s", {"passed": 14, "failed": 0, "errors": 0, "skipped": 0}),
        ("2 passed, 1 failed in 0.20s", {"passed": 2, "failed": 1, "errors": 0, "skipped": 0}),
        (
            "1 failed, 3 passed, 2 skipped in 0.3s",
            {"passed": 3, "failed": 1, "errors": 0, "skipped": 2},
        ),
        ("1 error in 0.02s", {"passed": 0, "failed": 0, "errors": 1, "skipped": 0}),
        ("no tests ran in 0.01s", {"passed": 0, "failed": 0, "errors": 0, "skipped": 0}),
    ],
)
def test_parse_test_counts(output: str, expected: dict[str, int]) -> None:
    assert parse_test_counts(output).to_dict() == expected


def test_parse_test_counts_keeps_last_occurrence() -> None:
    """跑了两轮时以最后一轮汇总为准（pytest 的汇总行在末尾）。"""
    output = "10 passed in 1.0s\n…\n3 passed, 1 failed in 0.4s\n"
    counts = parse_test_counts(output)
    assert (counts.passed, counts.failed) == (3, 1)


def test_unparsable_output_still_reports_verdict() -> None:
    """解析不到统计不影响判定：通过与否只认退出码。"""
    report = TestReport(
        command="mvn test",
        exit_code=0,
        passed=True,
        timed_out=False,
        duration_ms=800,
        output="BUILD SUCCESS",
        counts=TestCounts(),
    )
    assert report.counts.parsed is False
    assert "测试通过" in report.summary
