"""Codex CLI Provider：把 Codex CLI 当"会干活的模型"接入网关。

本机的 Codex CLI 由 codex-minimax 包装到 MiniMax 官方 API（CODEX_HOME=/root/.codex-minimax）。
与 OpenAI / Anthropic / DeepSeek 三个 HTTP 适配器的区别只有一个：模型调用发生在子进程里
（`codex exec`），本适配器负责拉起它、把最后一轮回答读回来。对上层完全透明——
Agent Runtime 拿到的仍然是 ChatResult，"提案 → 审阅 → 测试门禁 → 落盘 → Git 提交"
这一圈一步不变，Flux 出提示词、Flux 解析提案 JSON 的分工也不变。

"Agent 绝不直接写用户文件"这条硬约束仍由 Flux 把守，本适配器用四件事保证子进程同样越不过去：

1. **看不见用户项目**：子进程在一次性临时空目录里启动（`-C <tmpdir>`）。需求与文件现状
   由 Flux 拼进提示词（`DeveloperProposalFlow` 只按显式路径只读），不靠它自己读盘；
2. **没有写入能力**：`-s read-only` 沙箱，模型即使生成 shell 命令也写不了任何文件；
3. **不留会话**：`--ephemeral`，不在 CODEX_HOME 里持久化会话文件；
4. **不碰密钥**：MiniMax 密钥由 codex-minimax 包装脚本自己从 /opt/ops/.env 读进环境变量，
   Flux 不持有、不传递、不落盘（主规格 §14.3）。

生成上限：`codex exec` 没有 max_tokens 旋钮，本适配器忽略调用方传入的 max_tokens，
上限由 Codex / MiniMax 侧决定；用量按字符数估算（与 EchoProvider 同口径），cost 恒为 null。
"""

from __future__ import annotations

import asyncio
import shutil
import tempfile
import time
from collections.abc import Awaitable, Callable, Sequence
from pathlib import Path
from typing import Any

from flux.core.model_gateway.base import (
    ChatMessage,
    ChatResult,
    ModelPricing,
    ModelProviderBase,
    TokenUsage,
)
from flux.enums import ModelProvider
from flux.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderNotConfiguredError,
    ProviderTimeoutError,
)
from flux.logging import get_logger

logger = get_logger(__name__)

# 失败时 stderr 摘要的最大长度，与 HTTP 适配器同一口径（flux.core.model_gateway.http）
ERROR_SUMMARY_MAX_CHARS = 500

# codex-minimax 包装脚本在密钥缺失/非法时的原话，用来把这类失败映射成鉴权错误码
AUTH_FAILURE_MARKER = "MINIMAX_API_KEY"

#: 子进程结果：(退出码, stdout, stderr)
RunnerResult = tuple[int, str, str]
#: 可注入的子进程执行器。测试注入假实现以断言命令拼装与结果读取，不真的拉起 codex
Runner = Callable[[Sequence[str], Path, str, float], Awaitable[RunnerResult]]


def render_prompt(messages: Sequence[ChatMessage]) -> str:
    """把消息列表压成一段单轮提示词。

    `codex exec` 是"给一段指令、跑一轮"的非交互入口，没有独立的 system 通道，
    所以 system 内容前置于最上面，其余多轮对话按角色分段拼接。
    """
    parts = [m.content for m in messages if m.role == "system" and m.content.strip()]
    dialog = [m for m in messages if m.role != "system"]
    if len(dialog) == 1:
        parts.append(dialog[0].content)
    else:
        parts.extend(f"[{m.role}]\n{m.content}" for m in dialog)
    return "\n\n".join(parts)


async def _spawn(
    command: Sequence[str], cwd: Path, stdin_text: str, timeout: float
) -> RunnerResult:
    """真正拉起子进程并取回结果；超时则杀掉子进程，不留孤儿进程。"""
    process = await asyncio.create_subprocess_exec(
        *command,
        cwd=str(cwd),
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(
            process.communicate(stdin_text.encode("utf-8")), timeout=timeout
        )
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        raise
    return (
        process.returncode if process.returncode is not None else -1,
        stdout.decode("utf-8", errors="replace"),
        stderr.decode("utf-8", errors="replace"),
    )


def _tail(text: str, limit: int = ERROR_SUMMARY_MAX_CHARS) -> str:
    """错误摘要只保留尾部：codex 的失败原因总在 stderr 最后几行。"""
    text = (text or "").strip()
    return text if len(text) <= limit else "…" + text[-limit:]


class CodexCliProvider(ModelProviderBase):
    provider = ModelProvider.CODEX_CLI

    def __init__(
        self,
        model_name: str,
        *,
        binary: str,
        timeout: float = 900.0,
        pricing: ModelPricing | None = None,
        runner: Runner | None = None,
    ) -> None:
        super().__init__(model_name=model_name, pricing=pricing)
        self.binary = binary
        self.timeout = timeout
        self._runner = runner or _spawn

    def is_configured(self) -> bool:
        """可执行文件在 PATH 上即可用。

        密钥不在这里判断：它由包装脚本自理（运行时从 /opt/ops/.env 读进环境变量），
        Flux 既不持有也无从校验——真缺了会在调用时以鉴权错误码明确报出来。
        """
        return shutil.which(self.binary) is not None

    async def chat(self, messages: list[ChatMessage], **kwargs: Any) -> ChatResult:
        # kwargs 里的 max_tokens / temperature 不下发：codex exec 没有对应旋钮
        executable = shutil.which(self.binary)
        if executable is None:
            raise ProviderNotConfiguredError(
                f"供应商 {self.provider} 未配置：找不到可执行文件 {self.binary}",
                details={"provider": str(self.provider), "binary": self.binary},
            )

        prompt = render_prompt(messages)
        started = time.perf_counter()

        # 一次性临时目录：ws/ 是子进程的工作根（空目录，看不到任何用户项目），
        # 结果文件放在 ws/ 之外，避免沙箱策略影响 codex 自己写最后一轮回答。
        with tempfile.TemporaryDirectory(prefix="flux-codex-cli-") as tmp:
            root = Path(tmp)
            workdir = root / "ws"
            workdir.mkdir()
            out_file = root / "last-message.txt"
            command = self._build_command(executable, workdir, out_file)

            try:
                exit_code, stdout, stderr = await self._runner(
                    command, workdir, prompt, self.timeout
                )
            except asyncio.TimeoutError:
                raise ProviderTimeoutError(
                    f"供应商 {self.provider} 调用超时（{self.timeout:.0f}s）",
                    details={
                        "provider": str(self.provider),
                        "status": None,
                        "timeout_seconds": self.timeout,
                    },
                ) from None
            except OSError as exc:
                raise ProviderError(
                    f"供应商 {self.provider} 拉起子进程失败：{exc}",
                    details={"provider": str(self.provider), "status": None},
                ) from exc

            logger.debug(
                "codex-cli.exec exit=%d stdout_chars=%d stderr_chars=%d",
                exit_code,
                len(stdout),
                len(stderr),
            )
            if exit_code != 0:
                raise self._failure(exit_code, stderr)

            content = self._read_last_message(out_file, stderr)

        latency_ms = int((time.perf_counter() - started) * 1000)
        usage = TokenUsage(
            input_tokens=self.count_tokens(messages),
            output_tokens=self.count_tokens([ChatMessage(role="assistant", content=content)]),
        )
        return ChatResult(
            content=content,
            provider=self.provider,
            model=self.model_name,
            usage=usage,
            latency_ms=latency_ms,
            cost=self.calculate_cost(usage),
            raw={"exit_code": exit_code},
        )

    # --- 内部 ---

    def _build_command(self, executable: str, workdir: Path, out_file: Path) -> list[str]:
        command = [
            executable,
            "exec",
            # 不落会话、不要求 Git 仓库：这是"调一次模型"，不是"开一个会话"
            "--ephemeral",
            "--skip-git-repo-check",
            # 只读沙箱 + 空工作根：模型即便生成 shell 命令，也碰不到用户项目
            "-s",
            "read-only",
            "-C",
            str(workdir),
            # 最后一轮回答写文件：stdout 里混着 codex 的运行日志，只有这个文件是干净的回答
            "-o",
            str(out_file),
        ]
        if self.model_name:
            command += ["-m", self.model_name]
        # "-" 表示提示词从 stdin 读：提示词里带着整份文件现状，走 argv 会顶到参数长度上限
        command.append("-")
        return command

    def _read_last_message(self, out_file: Path, stderr: str) -> str:
        if not out_file.is_file():
            raise ProviderError(
                f"供应商 {self.provider} 未产出最后一轮回答（退出码 0，但没有结果文件）",
                details={"provider": str(self.provider), "status": None, "stderr": _tail(stderr)},
            )
        content = out_file.read_text(encoding="utf-8", errors="replace").strip()
        if not content:
            raise ProviderError(
                f"供应商 {self.provider} 返回了空的最后一轮回答",
                details={"provider": str(self.provider), "status": None, "stderr": _tail(stderr)},
            )
        return content

    def _failure(self, exit_code: int, stderr: str) -> ProviderError:
        summary = _tail(stderr)
        message = f"供应商 {self.provider} 调用失败（exit={exit_code}）：{summary}"
        details = {
            "provider": str(self.provider),
            "status": None,
            "exit_code": exit_code,
            "stderr": summary,
        }
        if AUTH_FAILURE_MARKER in stderr:
            # 包装脚本的原话：/opt/ops/.env 里的 MINIMAX_API_KEY 缺失或以非法字符开头
            return ProviderAuthError(message, details=details)
        return ProviderError(message, details=details)
