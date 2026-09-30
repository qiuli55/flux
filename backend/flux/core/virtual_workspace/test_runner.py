"""Test Runner（主规格 §7.6 的"运行测试"一步；实施计划 ⑦）。

跑的是**项目自己配置的测试命令**（如 `pytest -q`），不是 Agent 临时编出来的命令——
命令来源只有配置，Agent 无权指定，避免把提权入口开在 AI 侧。
"""

from __future__ import annotations

import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.logging import get_logger

logger = get_logger(__name__)

MAX_OUTPUT_CHARS = 20000


@dataclass(frozen=True)
class TestOutcome:
    command: str
    exit_code: int
    output: str
    timed_out: bool
    duration_ms: int

    @property
    def passed(self) -> bool:
        return not self.timed_out and self.exit_code == 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "command": self.command,
            "exit_code": self.exit_code,
            "output": self.output,
            "timed_out": self.timed_out,
            "duration_ms": self.duration_ms,
            "passed": self.passed,
        }


class TestRunner:
    # 名字以 Test 开头会被 pytest 当成测试类收集，这里显式声明它不是测试
    __test__ = False

    def __init__(self, *, timeout_seconds: float = 300.0) -> None:
        self._timeout = timeout_seconds

    def run(self, command: str, *, cwd: Path) -> TestOutcome:
        started = time.monotonic()
        try:
            completed = subprocess.run(  # noqa: S602 - 命令来自项目配置，不是模型输出
                command,
                shell=True,
                cwd=cwd,
                capture_output=True,
                text=True,
                timeout=self._timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            return TestOutcome(
                command=command,
                exit_code=-1,
                output=self._truncate(f"测试命令超时（{self._timeout}s）：{exc}"),
                timed_out=True,
                duration_ms=int((time.monotonic() - started) * 1000),
            )
        output = self._truncate((completed.stdout or "") + (completed.stderr or ""))
        outcome = TestOutcome(
            command=command,
            exit_code=completed.returncode,
            output=output,
            timed_out=False,
            duration_ms=int((time.monotonic() - started) * 1000),
        )
        logger.info(
            "apply.test command=%s exit=%d passed=%s",
            command,
            outcome.exit_code,
            outcome.passed,
        )
        return outcome

    @staticmethod
    def _truncate(text: str) -> str:
        if len(text) <= MAX_OUTPUT_CHARS:
            return text
        return text[:MAX_OUTPUT_CHARS] + f"\n…（输出已截断，共 {len(text)} 字符）"
