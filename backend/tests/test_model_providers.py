"""真实模型供应商适配器的离线测试（主规格 §5.3 / §19.1 M1 Issues #013–#015）。

一律使用 `httpx.MockTransport` 注入请求处理函数，不发起任何真实网络请求。
`max_retries` 传 0 或注入 no-op `sleep`，保证用例不会真的等待。
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from flux.config import Settings
from flux.core.model_gateway.base import ChatMessage
from flux.core.model_gateway.http import map_status_to_error, post_json
from flux.core.model_gateway.providers.anthropic import DEFAULT_MAX_TOKENS, AnthropicProvider
from flux.core.model_gateway.providers.codex_cli import CodexCliProvider, render_prompt
from flux.core.model_gateway.providers.deepseek import DeepSeekProvider
from flux.core.model_gateway.providers.openai import OpenAIProvider
from flux.core.model_gateway.providers.registry import build_providers
from flux.core.model_gateway.router import ModelRouter
from flux.enums import ModelProvider
from flux.errors import AIOSError, ProviderNotConfiguredError

OPENAI_KEY = "test-openai-key"


class _Recorder:
    """记录最近一次请求，供断言 URL / 请求头 / 请求体。"""

    def __init__(self) -> None:
        self.url: httpx.URL | None = None
        self.headers: httpx.Headers | None = None
        self.body: dict[str, Any] = {}
        self.count = 0


def _client(handler: Any) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _json_body(request: httpx.Request) -> dict[str, Any]:
    data = json.loads(request.content)
    assert isinstance(data, dict)
    return data


def _openai_response() -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", "content": "你好，我是 OpenAI。"}}],
        "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18},
    }


def _deepseek_response() -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", "content": "你好，我是 DeepSeek。"}}],
        "usage": {"prompt_tokens": 9, "completion_tokens": 5, "total_tokens": 14},
    }


def _anthropic_response() -> dict[str, Any]:
    return {
        "content": [
            {"type": "text", "text": "第一段。"},
            {"type": "text", "text": "第二段。"},
        ],
        "usage": {"input_tokens": 21, "output_tokens": 13},
    }


def _no_sleep_sink(delays: list[float]):
    async def _sleep(seconds: float) -> None:
        delays.append(seconds)

    return _sleep


# --- OpenAI（Issue #013）---


async def test_openai_request_and_response_mapping() -> None:
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.url = request.url
        recorder.headers = request.headers
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_openai_response())

    provider = OpenAIProvider(
        model_name="gpt-5.5",
        api_key=OPENAI_KEY,
        base_url="https://api.openai.com/v1",
        client=_client(handler),
        max_retries=0,
    )
    result = await provider.chat(
        [ChatMessage(role="user", content="你好")], max_tokens=256, temperature=0.3
    )

    assert str(recorder.url) == "https://api.openai.com/v1/chat/completions"
    assert recorder.headers is not None
    assert recorder.headers["authorization"] == f"Bearer {OPENAI_KEY}"
    assert "max_completion_tokens" in recorder.body
    assert "max_tokens" not in recorder.body
    assert recorder.body["temperature"] == 0.3
    assert result.content == "你好，我是 OpenAI。"
    assert result.provider == ModelProvider.OPENAI
    assert result.model == "gpt-5.5"
    assert result.usage.input_tokens == 11
    assert result.usage.output_tokens == 7
    assert result.cost is None
    assert result.latency_ms >= 0
    assert result.raw["usage"]["total_tokens"] == 18


# --- DeepSeek（Issue #015）---


async def test_deepseek_request_and_default_model() -> None:
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.url = request.url
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_deepseek_response())

    provider = DeepSeekProvider(
        model_name="deepseek-flash",
        api_key="test-deepseek-key",
        base_url="https://api.deepseek.com",
        client=_client(handler),
        max_retries=0,
    )
    result = await provider.chat([ChatMessage(role="user", content="你好")], max_tokens=128)

    assert str(recorder.url) == "https://api.deepseek.com/chat/completions"
    assert "max_tokens" in recorder.body
    assert "max_completion_tokens" not in recorder.body
    assert recorder.body["model"] == "deepseek-flash"
    assert result.provider == ModelProvider.DEEPSEEK
    assert result.content == "你好，我是 DeepSeek。"
    assert result.usage.input_tokens == 9
    assert result.cost is None


@pytest.mark.parametrize(
    ("provider_cls", "base_url"),
    [
        (OpenAIProvider, "https://api.openai.com/v1"),
        (DeepSeekProvider, "https://api.deepseek.com"),
    ],
)
async def test_no_token_limit_field_when_caller_omits_it(provider_cls: Any, base_url: str) -> None:
    """调用方不设上限 → 请求体里不出现任何生成上限字段（不填 1024 兜底）。

    兜底值会把 Agent 的完整文件输出截断、让提案 JSON 解析失败，所以"不传"必须等于
    "不下发"，而不是"退回某个默认值"。
    """
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_openai_response())

    provider = provider_cls(
        model_name="test-model",
        api_key="test-key",
        base_url=base_url,
        client=_client(handler),
        max_retries=0,
    )
    await provider.chat([ChatMessage(role="user", content="你好")])

    assert "max_tokens" not in recorder.body
    assert "max_completion_tokens" not in recorder.body


def test_deepseek_default_model_name_from_settings() -> None:
    providers = build_providers(Settings())
    assert providers[ModelProvider.DEEPSEEK].model_name == "deepseek-flash"


# --- Anthropic（Issue #014）---


async def test_anthropic_request_and_response_mapping() -> None:
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.url = request.url
        recorder.headers = request.headers
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_anthropic_response())

    provider = AnthropicProvider(
        model_name="claude-sonnet-5-5",
        api_key="test-anthropic-key",
        base_url="https://api.anthropic.com",
        client=_client(handler),
        max_retries=0,
    )
    result = await provider.chat(
        [
            ChatMessage(role="system", content="你是审查者。"),
            ChatMessage(role="user", content="看这段代码"),
        ],
        max_tokens=512,
        temperature=1.5,
    )

    assert str(recorder.url) == "https://api.anthropic.com/v1/messages"
    assert recorder.headers is not None
    assert recorder.headers["x-api-key"] == "test-anthropic-key"
    assert recorder.headers["anthropic-version"] == "2023-06-01"
    assert "authorization" not in recorder.headers
    assert recorder.body["system"] == "你是审查者。"
    assert recorder.body["max_tokens"] == 512
    assert all(message["role"] != "system" for message in recorder.body["messages"])
    assert recorder.body["temperature"] == 1.0
    assert result.content == "第一段。第二段。"
    assert result.usage.input_tokens == 21
    assert result.usage.output_tokens == 13
    assert result.provider == ModelProvider.ANTHROPIC
    assert result.cost is None


async def test_anthropic_system_absent_when_no_system_message() -> None:
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_anthropic_response())

    provider = AnthropicProvider(
        model_name="claude-sonnet-5-5",
        api_key="test-anthropic-key",
        base_url="https://api.anthropic.com",
        client=_client(handler),
        max_retries=0,
    )
    await provider.chat([ChatMessage(role="user", content="你好")])

    assert "system" not in recorder.body
    assert recorder.body["max_tokens"] == DEFAULT_MAX_TOKENS


async def test_anthropic_uses_configured_fallback_when_caller_omits_max_tokens() -> None:
    """调用方不设上限时下发的是配置值，而不是写死的小值。

    Anthropic 的 max_tokens 是必填字段、做不到"不传=不限"，但兜底值一旦偏小，
    Agent 要产出的完整文件内容会被截断、提案 JSON 解析失败——所以这个兜底必须
    显式可配（FLUX_ANTHROPIC_MAX_TOKENS）且给足余量。
    """
    recorder = _Recorder()

    async def handler(request: httpx.Request) -> httpx.Response:
        recorder.body = _json_body(request)
        return httpx.Response(200, json=_anthropic_response())

    provider = AnthropicProvider(
        model_name="claude-sonnet-5-5",
        api_key="test-anthropic-key",
        base_url="https://api.anthropic.com",
        default_max_tokens=48000,
        client=_client(handler),
        max_retries=0,
    )
    await provider.chat([ChatMessage(role="user", content="你好")])

    assert recorder.body["max_tokens"] == 48000


def test_anthropic_module_default_is_not_a_truncating_value() -> None:
    """模块级默认兜底不得退回小值（曾经是 1024，会把提案输出截断）。"""
    assert DEFAULT_MAX_TOKENS >= 32000


def test_registry_passes_anthropic_max_tokens_from_settings() -> None:
    settings = Settings(anthropic_max_tokens=12345)
    providers = build_providers(settings)
    provider = providers[ModelProvider.ANTHROPIC]
    assert isinstance(provider, AnthropicProvider)
    assert provider.default_max_tokens == 12345


async def test_anthropic_missing_content_raises_provider_error() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"usage": {"input_tokens": 1, "output_tokens": 1}})

    provider = AnthropicProvider(
        model_name="claude-sonnet-5-5",
        api_key="test-anthropic-key",
        base_url="https://api.anthropic.com",
        client=_client(handler),
        max_retries=0,
    )
    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])
    assert excinfo.value.code == "provider_error"


# --- 错误映射 ---


@pytest.mark.parametrize(
    ("status", "expected_code"),
    [
        (401, "provider_auth_error"),
        (429, "provider_rate_limited"),
        (500, "provider_unavailable"),
        (400, "provider_bad_request"),
    ],
)
async def test_status_is_mapped_to_domain_error(status: int, expected_code: str) -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"error": {"message": "上游拒绝了本次调用"}})

    provider = OpenAIProvider(
        model_name="gpt-5.5",
        api_key=OPENAI_KEY,
        base_url="https://api.openai.com/v1",
        client=_client(handler),
        max_retries=0,
    )
    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == expected_code
    assert excinfo.value.details["provider"] == "openai"
    assert excinfo.value.details["status"] == status


def test_map_status_to_error_reads_anthropic_error_shape() -> None:
    error = map_status_to_error(
        401, '{"type": "error", "error": {"type": "authentication_error"}}', "anthropic"
    )
    assert error.code == "provider_auth_error"
    assert error.details["provider"] == "anthropic"
    assert "authentication_error" in error.message


def test_map_status_to_error_truncates_long_summary() -> None:
    raw_summary = "很长的上游错误" * 100
    body = '{"error": {"message": "' + raw_summary + '"}}'
    error = map_status_to_error(400, body, "openai")
    assert error.code == "provider_bad_request"
    # 上游摘要截断到 500 字符，message 只多出固定前缀
    assert raw_summary not in error.message
    assert len(error.message) <= 500 + len("供应商 openai 返回 HTTP 400：")


def test_map_status_to_error_falls_back_to_raw_text() -> None:
    error = map_status_to_error(502, "<html>Bad Gateway</html>", "deepseek")
    assert error.code == "provider_unavailable"
    assert "Bad Gateway" in error.message


async def test_timeout_maps_to_provider_timeout() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("读取超时", request=request)

    with pytest.raises(AIOSError) as excinfo:
        await post_json(
            _client(handler),
            "https://api.openai.com/v1/chat/completions",
            payload={"model": "gpt-5.5"},
            headers={"Authorization": f"Bearer {OPENAI_KEY}"},
            provider="openai",
            max_retries=0,
        )
    assert excinfo.value.code == "provider_timeout"
    assert excinfo.value.details["provider"] == "openai"
    assert excinfo.value.details["status"] is None
    assert OPENAI_KEY not in excinfo.value.message


async def test_invalid_json_success_body_raises_provider_error() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="不是 JSON")

    with pytest.raises(AIOSError) as excinfo:
        await post_json(
            _client(handler),
            "https://api.openai.com/v1/chat/completions",
            payload={"model": "gpt-5.5"},
            headers={"Authorization": f"Bearer {OPENAI_KEY}"},
            provider="openai",
            max_retries=0,
        )
    assert excinfo.value.code == "provider_error"
    assert excinfo.value.details["provider"] == "openai"


# --- 重试与退避 ---


async def test_retry_exhausted_on_persistent_rate_limit() -> None:
    calls = {"count": 0}
    delays: list[float] = []

    async def handler(_: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        return httpx.Response(429, json={"error": {"message": "限流"}})

    with pytest.raises(AIOSError) as excinfo:
        await post_json(
            _client(handler),
            "https://api.openai.com/v1/chat/completions",
            payload={"model": "gpt-5.5"},
            headers={"Authorization": f"Bearer {OPENAI_KEY}"},
            provider="openai",
            max_retries=2,
            sleep=_no_sleep_sink(delays),
        )

    assert excinfo.value.code == "provider_rate_limited"
    assert calls["count"] == 3
    assert delays == [0.5, 1.0]


async def test_retry_recovers_after_transient_server_error() -> None:
    calls = {"count": 0}
    delays: list[float] = []

    async def handler(_: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        if calls["count"] == 1:
            return httpx.Response(500, json={"error": {"message": "上游故障"}})
        return httpx.Response(200, json=_openai_response())

    data = await post_json(
        _client(handler),
        "https://api.openai.com/v1/chat/completions",
        payload={"model": "gpt-5.5"},
        headers={"Authorization": f"Bearer {OPENAI_KEY}"},
        provider="openai",
        max_retries=2,
        sleep=_no_sleep_sink(delays),
    )

    assert calls["count"] == 2
    assert delays == [0.5]
    assert data["choices"][0]["message"]["content"] == "你好，我是 OpenAI。"


async def test_non_retryable_status_does_not_retry() -> None:
    calls = {"count": 0}

    async def handler(_: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        return httpx.Response(401, json={"error": {"message": "密钥无效"}})

    with pytest.raises(AIOSError) as excinfo:
        await post_json(
            _client(handler),
            "https://api.openai.com/v1/chat/completions",
            payload={"model": "gpt-5.5"},
            headers={"Authorization": f"Bearer {OPENAI_KEY}"},
            provider="openai",
            max_retries=2,
            sleep=_no_sleep_sink([]),
        )
    assert excinfo.value.code == "provider_auth_error"
    assert calls["count"] == 1


async def test_connection_error_retries_then_reports_unavailable() -> None:
    calls = {"count": 0}
    delays: list[float] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        raise httpx.ConnectError("连接被拒绝", request=request)

    with pytest.raises(AIOSError) as excinfo:
        await post_json(
            _client(handler),
            "https://api.deepseek.com/chat/completions",
            payload={"model": "deepseek-flash"},
            headers={"Authorization": "Bearer test-deepseek-key"},
            provider="deepseek",
            max_retries=1,
            sleep=_no_sleep_sink(delays),
        )
    assert excinfo.value.code == "provider_unavailable"
    assert calls["count"] == 2
    assert delays == [0.5]


# --- 未配置 ---


async def test_unconfigured_provider_reports_not_configured() -> None:
    provider = OpenAIProvider(
        model_name="gpt-5.5",
        api_key=None,
        base_url="https://api.openai.com/v1",
    )
    assert provider.is_configured() is False
    with pytest.raises(ProviderNotConfiguredError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])
    assert excinfo.value.code == "provider_not_configured"
    assert excinfo.value.details["provider"] == "openai"


async def test_unconfigured_anthropic_reports_not_configured() -> None:
    provider = AnthropicProvider(
        model_name="claude-sonnet-5-5",
        api_key=None,
        base_url="https://api.anthropic.com",
    )
    assert provider.is_configured() is False
    with pytest.raises(ProviderNotConfiguredError):
        await provider.chat([ChatMessage(role="user", content="你好")])


# --- Codex CLI（subprocess 形态的第五个供应商）---

#: 只要求 is_configured() 能解析到可执行文件；用例里注入假 runner，不真的拉起 codex
FAKE_BINARY = "sh"
LAST_MESSAGE = '{"summary": "加了登录接口", "changes": []}'


class _FakeRunner:
    """假的子进程执行器：记录命令与 stdin，并按脚本伪造 codex 写的最后一轮回答。"""

    def __init__(
        self,
        *,
        exit_code: int = 0,
        last_message: str | None = LAST_MESSAGE,
        stderr: str = "",
        stdout: str = "codex 的运行日志",
        raises: BaseException | None = None,
    ) -> None:
        self.exit_code = exit_code
        self.last_message = last_message
        self.stderr = stderr
        self.stdout = stdout
        self.raises = raises
        self.command: list[str] = []
        self.cwd: Path | None = None
        # 工作根的状态必须在"拉起子进程的那一刻"记录：临时目录在 chat() 返回后已被清掉
        self.cwd_exists = False
        self.cwd_entries: list[str] = []
        self.stdin_text = ""
        self.timeout = 0.0

    async def __call__(
        self, command: Any, cwd: Path, stdin_text: str, timeout: float
    ) -> tuple[int, str, str]:
        self.command = list(command)
        self.cwd = cwd
        self.cwd_exists = cwd.is_dir()
        self.cwd_entries = sorted(path.name for path in cwd.iterdir()) if self.cwd_exists else []
        self.stdin_text = stdin_text
        self.timeout = timeout
        if self.raises is not None:
            raise self.raises
        if self.last_message is not None:
            out_file = Path(self.command[self.command.index("-o") + 1])
            out_file.write_text(self.last_message, encoding="utf-8")
        return self.exit_code, self.stdout, self.stderr


def _codex_provider(runner: _FakeRunner, **kwargs: Any) -> CodexCliProvider:
    return CodexCliProvider(
        model_name=kwargs.pop("model_name", "MiniMax-M3"),
        binary=kwargs.pop("binary", FAKE_BINARY),
        runner=runner,
        **kwargs,
    )


async def test_codex_cli_command_shape_and_last_message() -> None:
    """命令拼装：只读沙箱 + 一次性空工作根 + 结果写文件 + 提示词走 stdin。"""
    runner = _FakeRunner()
    provider = _codex_provider(runner, timeout=123.0)

    result = await provider.chat(
        [
            ChatMessage(role="system", content="你是 Flux 的 Developer Agent。"),
            ChatMessage(role="user", content="## 需求\n做登录"),
        ]
    )

    command = runner.command
    assert command[1] == "exec"
    assert "--ephemeral" in command
    assert "--skip-git-repo-check" in command
    assert command[command.index("-s") + 1] == "read-only"
    assert command[command.index("-m") + 1] == "MiniMax-M3"
    assert command[-1] == "-"
    # 工作根是一次性空目录：模型看不到、也写不了任何用户项目
    assert runner.cwd is not None
    assert runner.cwd == Path(command[command.index("-C") + 1])
    assert runner.cwd_exists is True
    assert runner.cwd_entries == []
    assert runner.timeout == 123.0

    # 提示词把 system 前置于多轮对话之前；内容走 stdin 而不是 argv
    assert runner.stdin_text.startswith("你是 Flux 的 Developer Agent。")
    assert "## 需求\n做登录" in runner.stdin_text

    assert result.content == LAST_MESSAGE
    assert result.provider == ModelProvider.CODEX_CLI
    assert result.model == "MiniMax-M3"
    assert result.cost is None
    assert result.usage.input_tokens > 0
    assert result.usage.output_tokens > 0
    assert result.latency_ms >= 0
    assert result.raw == {"exit_code": 0}


def test_codex_cli_prompt_labels_turns_and_omits_model_flag_when_empty() -> None:
    prompt = render_prompt(
        [
            ChatMessage(role="user", content="第一轮"),
            ChatMessage(role="assistant", content="收到"),
            ChatMessage(role="user", content="第二轮"),
        ]
    )
    assert prompt == "[user]\n第一轮\n\n[assistant]\n收到\n\n[user]\n第二轮"

    provider = CodexCliProvider(model_name="", binary=FAKE_BINARY, runner=_FakeRunner())
    command = provider._build_command(
        "/usr/local/bin/codex-minimax", Path("/tmp/ws"), Path("/tmp/out.txt")
    )
    assert "-m" not in command
    assert command[0] == "/usr/local/bin/codex-minimax"


async def test_codex_cli_nonzero_exit_reports_provider_error_with_stderr() -> None:
    runner = _FakeRunner(exit_code=2, last_message=None, stderr="codex: 上游 500")
    provider = _codex_provider(runner)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_error"
    assert excinfo.value.details["provider"] == "codex_cli"
    assert excinfo.value.details["exit_code"] == 2
    assert "codex: 上游 500" in excinfo.value.details["stderr"]


async def test_codex_cli_key_problem_maps_to_auth_error() -> None:
    """包装脚本报密钥缺失时为鉴权错误码，而不是笼统的 provider_error。"""
    runner = _FakeRunner(
        exit_code=1,
        last_message=None,
        stderr="codex-minimax: /opt/ops/.env 里的 MINIMAX_API_KEY 缺失",
    )
    provider = _codex_provider(runner)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_auth_error"


async def test_codex_cli_missing_output_file_is_an_error() -> None:
    """退出码 0 但没有结果文件 → 明确报错，不拿 stdout 里的日志兜底。"""
    runner = _FakeRunner(last_message=None)
    provider = _codex_provider(runner)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_error"
    assert "未产出最后一轮回答" in excinfo.value.message


async def test_codex_cli_empty_last_message_is_an_error() -> None:
    runner = _FakeRunner(last_message="   \n")
    provider = _codex_provider(runner)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_error"
    assert "空的最后一轮回答" in excinfo.value.message


async def test_codex_cli_timeout_maps_to_provider_timeout() -> None:
    runner = _FakeRunner(raises=asyncio.TimeoutError())
    provider = _codex_provider(runner, timeout=7.0)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_timeout"
    assert excinfo.value.details["timeout_seconds"] == 7.0


async def test_codex_cli_spawn_failure_maps_to_provider_error() -> None:
    runner = _FakeRunner(raises=FileNotFoundError("no such file"))
    provider = _codex_provider(runner)

    with pytest.raises(AIOSError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])

    assert excinfo.value.code == "provider_error"
    assert "拉起子进程失败" in excinfo.value.message


async def test_codex_cli_unconfigured_when_binary_missing() -> None:
    provider = CodexCliProvider(
        model_name="MiniMax-M3",
        binary="flux-nonexistent-codex-binary",
        runner=_FakeRunner(),
    )
    assert provider.is_configured() is False
    with pytest.raises(ProviderNotConfiguredError) as excinfo:
        await provider.chat([ChatMessage(role="user", content="你好")])
    assert excinfo.value.details["provider"] == "codex_cli"


def test_codex_cli_configured_when_binary_on_path() -> None:
    provider = CodexCliProvider(model_name="MiniMax-M3", binary=FAKE_BINARY, runner=_FakeRunner())
    assert provider.is_configured() is True


# --- registry ---


def test_registry_registers_all_five_providers() -> None:
    providers = build_providers(Settings())
    assert set(providers) == {
        ModelProvider.LOCAL,
        ModelProvider.OPENAI,
        ModelProvider.ANTHROPIC,
        ModelProvider.DEEPSEEK,
        ModelProvider.CODEX_CLI,
    }


def test_registry_codex_cli_reads_binary_and_model_from_settings() -> None:
    settings = Settings(
        codex_cli_binary="/opt/custom/codex-minimax", codex_cli_model="MiniMax-M2.7"
    )
    provider = build_providers(settings)[ModelProvider.CODEX_CLI]
    assert isinstance(provider, CodexCliProvider)
    assert provider.binary == "/opt/custom/codex-minimax"
    assert provider.model_name == "MiniMax-M2.7"


def test_registry_without_keys_does_not_expose_http_providers() -> None:
    """没有密钥时四家 HTTP 供应商都不可用；CODEX_CLI 只看可执行文件在不在（本机差异）。"""
    settings = Settings(
        openai_api_key=None,
        anthropic_api_key=None,
        deepseek_api_key=None,
        codex_cli_binary="flux-nonexistent-codex-binary",
    )
    router = ModelRouter(build_providers(settings))
    assert router.available() == [ModelProvider.LOCAL]
