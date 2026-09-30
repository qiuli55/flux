"""Tester Agent：执行项目已配置的测试命令并回报结果（实施计划 ⑧）。

与 Developer Agent 的分工：Developer 产出提案，Tester 在改动落盘后负责验证。

三条硬约束：
1. **命令只能来自配置**（`FLUX_TEST_COMMAND` 或显式参数），模型无权指定命令——
   否则等于把任意命令执行权交给 AI（主规格 §6.1 的 Tester 只有 terminal.execute，
   不是"AI 想跑什么就跑什么"）。
2. 测试跑在**工作区根目录**下，与 Apply Engine 同一个根（§7.6）。
3. 测试失败时由模型**分析失败原因**，但分析结果只作为文本回报，不改任何文件。
"""

from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import AgentManifest, builtin_manifests
from flux.core.virtual_workspace.apply_engine import resolve_workspace_root
from flux.core.virtual_workspace.test_runner import TestOutcome, TestRunner
from flux.errors import ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

TESTER_AGENT_NAME = "tester"

# pytest 汇总行里的计数，如 "12 passed, 2 failed, 1 skipped in 0.53s"
_COUNT_PATTERN = re.compile(r"(\d+)\s+(passed|failed|error|errors|skipped)", re.IGNORECASE)
# 分析失败时送给模型的输出上限，避免把整份测试日志塞进上下文
_ANALYSIS_OUTPUT_CHARS = 8000


@dataclass(frozen=True)
class TestCounts:
    """从测试输出里解析出的用例统计。解析不到时为全 0（不改判定结论，只影响展示）。"""

    # 名字以 Test 开头，pytest 会试图把它当测试类收集，显式排除
    __test__ = False

    passed: int = 0
    failed: int = 0
    errors: int = 0
    skipped: int = 0

    @property
    def total(self) -> int:
        return self.passed + self.failed + self.errors + self.skipped

    @property
    def parsed(self) -> bool:
        return self.total > 0

    def to_dict(self) -> dict[str, int]:
        return {
            "passed": self.passed,
            "failed": self.failed,
            "errors": self.errors,
            "skipped": self.skipped,
        }


@dataclass(frozen=True)
class TestReport:
    """一次测试执行的完整结果（可审计：命令、退出码、耗时、输出都在）。"""

    __test__ = False

    command: str
    exit_code: int
    passed: bool
    timed_out: bool
    duration_ms: int
    output: str
    counts: TestCounts

    @property
    def summary(self) -> str:
        """给人和 UI 看的一行结论，例如 `14 passed`、`12 passed, 2 failed`。"""
        if self.timed_out:
            return f"测试超时（{self.command}）"
        if not self.counts.parsed:
            verdict = "通过" if self.passed else "失败"
            return f"测试{verdict}（exit={self.exit_code}，未能解析用例统计）"
        parts = [f"{self.counts.passed} passed"]
        if self.counts.failed:
            parts.append(f"{self.counts.failed} failed")
        if self.counts.errors:
            parts.append(f"{self.counts.errors} error")
        if self.counts.skipped:
            parts.append(f"{self.counts.skipped} skipped")
        return ", ".join(parts)

    def to_dict(self) -> dict[str, Any]:
        return {
            "command": self.command,
            "exit_code": self.exit_code,
            "passed": self.passed,
            "timed_out": self.timed_out,
            "duration_ms": self.duration_ms,
            "counts": self.counts.to_dict(),
            "summary": self.summary,
            "output": self.output,
        }


@dataclass(frozen=True)
class VerifyResult:
    """Tester Agent 的回报：测试结果 +（失败时）模型给出的原因分析。"""

    report: TestReport
    analysis: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"report": self.report.to_dict(), "analysis": self.analysis}


def parse_test_counts(output: str) -> TestCounts:
    """解析测试输出里的用例统计。

    汇总行在输出末尾，因此同一关键词取最后一次出现的值；解析不到返回全 0，
    判定"通过与否"永远只认退出码，不认解析结果。
    """
    found: dict[str, int] = {}
    for value, keyword in _COUNT_PATTERN.findall(output):
        key = "errors" if keyword.lower().startswith("error") else keyword.lower()
        found[key] = int(value)
    return TestCounts(
        passed=found.get("passed", 0),
        failed=found.get("failed", 0),
        errors=found.get("errors", 0),
        skipped=found.get("skipped", 0),
    )


def build_analysis_prompt(report: TestReport, *, instruction: str | None = None) -> str:
    """拼出"测试失败原因分析"的输入：命令 + 退出码 + 输出片段。"""
    sections = ["## 任务背景", (instruction or "（未提供任务描述）").strip(), "", "## 测试结果"]
    sections.append(f"命令：{report.command}")
    sections.append(f"退出码：{report.exit_code}（超时：{report.timed_out}）")
    sections.append(f"统计：{report.summary}")
    output = report.output.strip()
    if len(output) > _ANALYSIS_OUTPUT_CHARS:
        output = output[:_ANALYSIS_OUTPUT_CHARS] + "\n…（输出已截断）"
    sections += ["", "## 测试输出", "```", output or "（无输出）", "```"]
    sections += [
        "",
        "## 输出要求",
        "用中文回答，分三部分：1) 失败的直接原因（引用输出里的关键行）；"
        "2) 最可能的根因（是代码缺陷、测试本身有问题，还是环境问题）；"
        "3) 建议的下一步（要改哪个文件、要补哪个用例）。"
        "只做分析，不要改任何文件，也不要输出补丁。",
    ]
    return "\n".join(sections)


class TesterAgent:
    """Tester Agent 的入口：声明来自 Manifest，分析走 AgentManager 的真实运行时。"""

    __test__ = False

    def __init__(
        self,
        manager: AgentManager,
        manifest: AgentManifest | None = None,
        *,
        test_command: str | None = None,
        workspace_root: str | Path | None = None,
        timeout_seconds: float = 300.0,
    ) -> None:
        self._manager = manager
        self._manifest = manifest or builtin_manifests()[TESTER_AGENT_NAME]
        self._handle = manager.create_from_manifest(self._manifest)
        self._test_command = test_command
        self._workspace_root = workspace_root
        self._timeout = timeout_seconds

    @property
    def agent_id(self) -> str:
        return self._handle.id_str

    @property
    def manifest(self) -> AgentManifest:
        return self._manifest

    # --- 执行测试 ---

    async def run_tests(
        self,
        command: str | None = None,
        *,
        workspace_root: str | Path | None = None,
    ) -> TestReport:
        """在项目根下执行测试命令并回报结构化结果。

        `command` 只能是调用方（人 / 服务层）显式给出的配置值，绝不来自模型输出。
        """
        resolved_command = command or self._test_command
        if not resolved_command:
            raise ValidationError(
                "未配置测试命令（FLUX_TEST_COMMAND），Tester 无法执行测试",
                details={"hint": "设置 FLUX_TEST_COMMAND，例如 pytest -q"},
            )
        root = resolve_workspace_root(
            workspace_root if workspace_root is not None else self._workspace_root
        )
        outcome = await asyncio.to_thread(
            TestRunner(timeout_seconds=self._timeout).run, resolved_command, cwd=root
        )
        report = _to_report(outcome)
        logger.info(
            "tester.run agent=%s command=%s exit=%d summary=%s",
            self.agent_id,
            resolved_command,
            report.exit_code,
            report.summary,
        )
        return report

    # --- 回报（失败时做原因分析）---

    async def verify(
        self,
        command: str | None = None,
        *,
        workspace_root: str | Path | None = None,
        instruction: str | None = None,
        task_id: str | None = None,
    ) -> VerifyResult:
        """跑测试；通过则直接回报，失败（或超时）时再让模型分析原因。"""
        report = await self.run_tests(command, workspace_root=workspace_root)
        if report.passed:
            return VerifyResult(report=report)

        result = await self._manager.execute(
            self._handle.id_str,
            build_analysis_prompt(report, instruction=instruction),
            task_id=task_id,
        )
        return VerifyResult(report=report, analysis=result.content.strip())


def _to_report(outcome: TestOutcome) -> TestReport:
    return TestReport(
        command=outcome.command,
        exit_code=outcome.exit_code,
        passed=outcome.passed,
        timed_out=outcome.timed_out,
        duration_ms=outcome.duration_ms,
        output=outcome.output,
        counts=parse_test_counts(outcome.output),
    )
