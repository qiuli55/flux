"""选模通道、多账号、思考元数据与计费归因回归。"""

from __future__ import annotations

import asyncio
import base64
import json
from pathlib import Path

from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.core.agent_runtime.claude_account import ClaudeAccountService
from flux.core.agent_runtime.codex_account import CodexAccountService
from flux.core.agent_runtime.model_catalog import (
    CHANNEL_KEY,
    CHANNEL_SUBSCRIPTION,
    THINKING_ALWAYS,
    THINKING_LEVELS,
    THINKING_NONE,
    THINKING_TOGGLE,
    KeyEntry,
    build_agent_catalog,
    canonical_model_id,
    default_thinking_for_models,
    default_thinking_level,
    default_thinking_spec,
    model_meta,
    thinking_effort,
    thinking_spec_from_dict,
)
from flux.core.agent_runtime.runtimes.base import RUNTIME_CLAUDE_CODE, RUNTIME_CODEX
from flux.core.billing import sources as billing_sources
from flux.core.billing.plans import PLAN_CODEX, resolve_plan
from flux.core.billing.service import BillingService

TOKEN = "sk-ant-oat01-" + "a1b2c3d4" * 8


def _seg(payload: dict) -> str:
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _jwt(payload: dict) -> str:
    return f"{_seg({'alg': 'none', 'typ': 'JWT'})}.{_seg(payload)}.signature"


def _write_codex_auth(home: Path, email: str, plan: str = "plus") -> None:
    home.mkdir(parents=True, exist_ok=True)
    (home / "auth.json").write_text(
        json.dumps(
            {
                "tokens": {
                    "id_token": _jwt(
                        {
                            "email": email,
                            "https://api.openai.com/auth": {"chatgpt_plan_type": plan},
                        }
                    ),
                    "access_token": "secret-access-token",
                    "account_id": f"acc-{email}",
                },
            }
        ),
        encoding="utf-8",
    )


def _write_claude_token(home: Path, token: str = TOKEN) -> None:
    home.mkdir(parents=True, exist_ok=True)
    (home / "token").write_text(token + "\n", encoding="utf-8")


def _key(id_: str, provider: str, models: list[str], name: str = "") -> KeyEntry:
    return KeyEntry(
        id=id_,
        name=name or id_,
        provider=provider,
        base_url="",
        wire_api="",
        models=tuple(models),
        key_hint="••••abcd",
        enabled=True,
        model_thinking=default_thinking_for_models(models),
    )


def _settings(tmp_path: Path) -> Settings:
    return Settings(
        env="test",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'quota.db'}",
        billing_plans_file="",
        billing_prices_file="",
        opencode_config_file="",
        codex_account_home=str(tmp_path / "codex-account"),
        claude_account_home=str(tmp_path / "claude-account"),
    )


# --- 目录 / 通道 ---


def test_codex_catalog_only_contains_codex_channels(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    _write_codex_auth(Path(str(settings.codex_account_home)), "user@example.com")
    codex = CodexAccountService(settings)
    accounts = codex.accounts()
    assert len(accounts) == 1

    entries = [
        _key("k-openai", "openai", ["gpt-5.5"], "OpenAI key"),
        _key("k-anthropic", "anthropic", ["claude-sonnet-5.5"], "Anthropic key"),
        _key("k-deepseek", "deepseek", ["deepseek-chat"], "DeepSeek key"),
    ]
    from flux.core.agent_runtime.model_catalog import SubscriptionAccount

    channels, catalog = build_agent_catalog(
        settings,
        RUNTIME_CODEX,
        entries,
        [SubscriptionAccount("acc-1", "Codex · user@example.com", "OpenAI", "plus")],
        native_models=["gpt-5.5"],
    )
    assert [channel.kind for channel in channels].count(CHANNEL_SUBSCRIPTION) == 1
    key_channels = [channel for channel in channels if channel.kind == CHANNEL_KEY]
    assert {channel.key_id for channel in key_channels} == {"k-openai", "k-deepseek"}
    # Codex 页不能出现 Anthropic 协议的模型
    assert all("claude" not in model.id for model in catalog)
    # 「全部」= 当前 Agent 的所有通道
    assert {model.channel_id for model in catalog} == {channel.id for channel in channels}
    # 订阅分栏包含该账号可用的原生模型
    sub = next(channel for channel in channels if channel.kind == CHANNEL_SUBSCRIPTION)
    assert any(model.channel_id == sub.id and model.id == "gpt-5.5" for model in catalog)


def test_claude_catalog_separates_accounts_and_anthropic_keys(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    _write_claude_token(Path(str(settings.claude_account_home)))
    service = ClaudeAccountService(settings)
    accounts = service.accounts()
    from flux.core.agent_runtime.model_catalog import SubscriptionAccount

    entries = [
        _key("k-a", "anthropic", ["claude-sonnet-5.5"]),
        _key("k-oai", "openai", ["gpt-5.5"]),
    ]
    channels, catalog = build_agent_catalog(
        settings,
        RUNTIME_CLAUDE_CODE,
        entries,
        [SubscriptionAccount(item["id"], item["label"], "Anthropic") for item in accounts],
        native_models=["claude-sonnet-5.5"],
    )
    assert [channel.key_id for channel in channels if channel.kind == CHANNEL_KEY] == ["k-a"]
    assert all(model.provider != "openai" for model in catalog)
    # Claude 已登录但暂时不能官方取额度：选模可见（这里在目录里）
    assert any(model.channel_kind == CHANNEL_SUBSCRIPTION for model in catalog)


def test_thinking_metadata_matches_official_shapes() -> None:
    gpt = model_meta("gpt-5.5").thinking
    assert gpt.kind == THINKING_LEVELS
    assert gpt.levels == ("minimal", "low", "medium", "high")
    assert gpt.default == "medium"
    assert thinking_effort("gpt-5.5", "high") == "high"
    assert thinking_effort("gpt-5.5", "ultra") == ""
    # 大中转里的官方模型 id 对齐同一套能力
    assert model_meta("openai/gpt-5.5").thinking == gpt
    assert model_meta("openrouter/openai/gpt-5.5:free").thinking == gpt

    # DeepSeek 官方：无思考 + 低 / 高 / max，不是通用 5 档
    deepseek = model_meta("deepseek-chat").thinking
    assert deepseek.kind == THINKING_LEVELS
    assert deepseek.levels == ("none", "low", "high", "max")
    assert thinking_effort("deepseek-chat", "high") == "high"
    assert thinking_effort("deepseek-chat", "none") == ""
    assert thinking_effort("deepseek-chat", "medium") == ""

    # 对不上号的模型：无思考 + 5 档（6 项），不是 none
    generic = model_meta("some-unknown-model").thinking
    assert generic.kind == THINKING_LEVELS
    assert generic.levels == ("none", "minimal", "low", "medium", "high", "max")
    assert default_thinking_spec("some-unknown-model") == generic
    assert default_thinking_level("some-unknown-model") == generic.default

    # 已知不支持思考的模型才真的不展示控制
    assert model_meta("text-embedding-3-large").thinking.kind == THINKING_NONE
    assert default_thinking_spec("text-embedding-3-large").kind == THINKING_NONE

    # 始终思考：不能关闭
    always = model_meta("kimi-k2-thinking").thinking
    assert always.kind == THINKING_ALWAYS
    assert default_thinking_level("kimi-k2-thinking") == "always"

    # 规范 id：供应商前缀 / 后缀不影响识别
    assert canonical_model_id("anthropic/claude-sonnet-4.5") == "claude-sonnet-4.5"
    assert model_meta("anthropic/claude-sonnet-4.5").thinking.kind == THINKING_TOGGLE


def test_key_model_uses_saved_thinking_mode_over_auto_detection(
    tmp_path: Path,
) -> None:
    saved = thinking_spec_from_dict(
        {"kind": "toggle", "levels": ["on", "off"], "default": "off"},
        "deepseek-v4-pro",
    )
    entry = KeyEntry(
        id="k1",
        name="opencode",
        provider="openai_compatible",
        base_url="",
        wire_api="",
        models=("deepseek-v4-pro",),
        key_hint="••••abcd",
        enabled=True,
        model_thinking={"deepseek-v4-pro": saved},
    )
    channels, catalog = build_agent_catalog(
        _settings(tmp_path), "codex", [entry], native_models=["gpt-5.5"]
    )
    key_model = next(item for item in catalog if item.channel_id == channels[-1].id)
    assert key_model.thinking.kind == THINKING_TOGGLE
    assert key_model.thinking.default == "off"


# --- 归因修复 ---


def test_key_channel_is_not_attributed_to_subscription_plan() -> None:
    # 无前缀模型在 Codex runtime 上：原生通道归订阅，密钥库通道不归订阅
    assert resolve_plan("gpt-5.5", "codex").key == PLAN_CODEX
    assert resolve_plan("gpt-5.5", "codex", via_key=True) is None
    # 明确前缀仍是显式通道声明，照常归因
    assert resolve_plan("codex/gpt-5.5", "codex", via_key=True).key == PLAN_CODEX


async def test_key_channel_gate_is_not_blocked_by_subscription_quota(
    container: Container,
) -> None:
    settings = container.settings.model_copy(
        update={"billing_enforce": True, "billing_refresh_seconds": 300}
    )
    service = BillingService(settings, container.session_factory)
    # 没有额度数据时订阅闸门会 unknown；关键是密钥通道根本不解析到订阅套餐
    native = await service.gate_for_model("gpt-5.5", runtime_id="codex", via_key=False)
    assert native.plan_key == PLAN_CODEX
    keyed = await service.gate_for_model("gpt-5.5", runtime_id="codex", via_key=True)
    assert keyed.plan_key == ""
    # via_key 时 ensure_can_start 不会因为订阅超额被拦
    decision = await service.ensure_can_start(
        model="gpt-5.5", runtime_id="codex", via_key=True
    )
    assert decision.blocked is False


# --- 多账号 ---


def test_codex_second_login_is_added_not_overwritten(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    root = Path(str(settings.codex_account_home))
    _write_codex_auth(root, "first@example.com")
    (root / "accounts" / "acct2").mkdir(parents=True)
    _write_codex_auth(root / "accounts" / "acct2", "second@example.com")

    service = CodexAccountService(settings)
    emails = {item["email"] for item in service.accounts()}
    assert emails == {"first@example.com", "second@example.com"}
    assert service.default_account_id() == "default"

    # 退出第二个号只移除它，第一个号仍在
    result = asyncio.run(service.logout("acct2"))
    assert result["removed"] is True
    assert {item["email"] for item in service.accounts()} == {"first@example.com"}
    assert service.get_account("acct2") is None


def test_claude_second_login_is_added_not_overwritten(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    root = Path(str(settings.claude_account_home))
    _write_claude_token(root)
    (root / "accounts" / "acct2").mkdir(parents=True)
    _write_claude_token(root / "accounts" / "acct2", "sk-ant-oat01-" + "b" * 32)

    service = ClaudeAccountService(settings)
    assert len(service.accounts()) == 2
    # 令牌只在掩码里出现
    dumped = json.dumps(service.accounts())
    assert TOKEN not in dumped

    asyncio.run(service.logout("acct2"))
    assert len(service.accounts()) == 1
    assert service.token("acct2") is None


# --- 任务记住通道 ---


async def test_task_remembers_model_key_account_and_thinking(container: Container) -> None:
    task = await container.task_repo.create(
        task_id=__import__("uuid").uuid4(),
        description="测试",
        priority=100,
        project_id=None,
        agent_id=None,
    )
    updated = await container.task_repo.set_model(
        task.id,
        "gpt-5.5",
        None,
        subscription_account_id="acc-1",
        thinking_level="high",
    )
    assert updated.model == "gpt-5.5"
    assert updated.subscription_account_id == "acc-1"
    assert updated.thinking_level == "high"

    # 切 Agent 清掉模型 / key / 账号 / 思考档
    import uuid

    switched = await container.task_repo.set_agent(task.id, uuid.uuid4())
    assert switched.model is None
    assert switched.api_key_id is None
    assert switched.subscription_account_id is None
    assert switched.thinking_level is None

    keyed = await container.task_repo.set_model(
        task.id, "deepseek-chat", uuid.uuid4(), subscription_account_id="acc-1"
    )
    # 密钥库与订阅账号互斥
    assert keyed.api_key_id is not None
    assert keyed.subscription_account_id is None


def test_agent_payload_exposes_channels_without_other_agents(
    client: TestClient,
) -> None:
    container: Container = client.app.state.container
    home = Path(str(container.settings.codex_account_home))
    _write_codex_auth(home, "codex@example.com")

    codex_id = client.post(
        "/api/v1/agents",
        json={"name": "codex-page", "role": "developer", "runtime": "codex"},
    ).json()["data"]["id"]
    client.post(
        "/api/v1/agents",
        json={"name": "claude-page", "role": "developer", "runtime": "claude-code"},
    )

    asyncio.run(
        container.key_vault.create(
            name="openai-key",
            provider="openai",
            api_key="sk-openai-" + "x" * 20,
            models=["gpt-5.5"],
            model_thinking=default_thinking_for_models(["gpt-5.5"]),
        )
    )
    asyncio.run(
        container.key_vault.create(
            name="anthropic-key",
            provider="anthropic",
            api_key="sk-ant-" + "y" * 20,
            models=["claude-sonnet-5.5"],
            model_thinking=default_thinking_for_models(["claude-sonnet-5.5"]),
        )
    )

    items = client.get("/api/v1/agents").json()["data"]
    codex = next(item for item in items if item["id"] == codex_id)
    spec = codex["spec"]
    channel_kinds = {channel["kind"] for channel in spec["model_channels"]}
    assert "subscription" in channel_kinds
    assert "key" in channel_kinds
    key_models = {
        model["id"] for model in spec["model_catalog"] if model["channel_kind"] == "key"
    }
    assert "gpt-5.5" in key_models
    assert "claude-sonnet-5.5" not in key_models
    # Codex 页不混入 Claude/OpenCode 的订阅账号
    assert all(
        channel["vendor"] != "Anthropic"
        for channel in spec["model_channels"]
        if channel["kind"] == "subscription"
    )
    # 每条模型都带思考元数据
    assert all(
        "thinking" in model and "kind" in model["thinking"]
        for model in spec["model_catalog"]
    )


def test_key_model_thinking_uses_vault_saved_mode(client: TestClient) -> None:
    key = client.post(
        "/api/v1/keys",
        json={
            "name": "opencode",
            "provider": "deepseek",
            "base_url": "https://api.deepseek.com",
            "api_key": "sk-vault-thinking",
            "models": ["deepseek-v4-pro"],
            "model_thinking": {
                "deepseek-v4-pro": {
                    "kind": "toggle",
                    "levels": ["on", "off"],
                    "default": "off",
                }
            },
        },
    ).json()["data"]

    # 未显式给档时落到密钥库保存的默认档（不是订阅模型的自动识别结果）
    created = client.post(
        "/api/v1/tasks",
        json={
            "description": "按密钥库的思考模式跑",
            "model": "deepseek-v4-pro",
            "key_id": key["id"],
        },
    )
    assert created.status_code == 200, created.text
    assert created.json()["data"]["thinking_level"] == "off"

    # 密钥库只保存了开 / 关时，通用档位不被接受
    rejected = client.post(
        "/api/v1/tasks",
        json={
            "description": "非法档位",
            "model": "deepseek-v4-pro",
            "key_id": key["id"],
            "thinking": "medium",
        },
    )
    assert rejected.status_code == 400, rejected.text


def test_task_api_can_pin_subscription_account(client: TestClient) -> None:
    container: Container = client.app.state.container
    _write_codex_auth(Path(str(container.settings.codex_account_home)), "pin@example.com")
    agent_id = client.post(
        "/api/v1/agents",
        json={"name": "codex-pin", "role": "developer", "runtime": "codex"},
    ).json()["data"]["id"]

    response = client.post(
        "/api/v1/tasks",
        json={
            "description": "用订阅账号跑",
            "agent_id": agent_id,
            "model": "gpt-5.5",
            "account_id": "default",
            "thinking": "high",
        },
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["subscription_account_id"] == "default"
    assert data["api_key_id"] is None
    assert data["thinking_level"] == "high"


# --- OpenCode Go URL ---


class _Resp:
    def __init__(self, status: int, payload: dict | None = None, text: str = "") -> None:
        self.status_code = status
        self._payload = payload or {}
        self.text = text or json.dumps(self._payload)

    def json(self):
        return self._payload


def test_opencode_go_prefers_new_url(monkeypatch, tmp_path: Path) -> None:
    calls: list[str] = []
    config = tmp_path / "opencode.json"
    config.write_text(json.dumps({"provider": {"opencode-go": {"options": {"apiKey": "go-key"}}}}))
    settings = _settings(tmp_path).model_copy(update={"opencode_config_file": str(config)})

    class _Client:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url, headers=None):
            calls.append(url)
            return _Resp(200, {"usage": {"rolling": {"percent": 12, "status": "ok"}}})

    monkeypatch.setattr(billing_sources.httpx, "AsyncClient", _Client)
    result = asyncio.run(billing_sources.OpenCodeGoUsageSource(settings).fetch())
    assert calls == [billing_sources.OPENCODE_GO_USAGE_URL]
    assert result.error is None
    assert result.rows[0].percent == 12.0


def test_opencode_go_falls_back_to_legacy_url_only_on_failure(monkeypatch, tmp_path: Path) -> None:
    calls: list[str] = []
    config = tmp_path / "opencode.json"
    config.write_text(json.dumps({"provider": {"opencode-go": {"options": {"apiKey": "go-key"}}}}))
    settings = _settings(tmp_path).model_copy(update={"opencode_config_file": str(config)})

    class _Client:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url, headers=None):
            calls.append(url)
            if url == billing_sources.OPENCODE_GO_USAGE_URL:
                return _Resp(404, {}, "not found")
            return _Resp(200, {"usage": {"rolling": {"percent": 8}}})

    monkeypatch.setattr(billing_sources.httpx, "AsyncClient", _Client)
    result = asyncio.run(billing_sources.OpenCodeGoUsageSource(settings).fetch())
    assert calls == [
        billing_sources.OPENCODE_GO_USAGE_URL,
        billing_sources.OPENCODE_GO_LEGACY_USAGE_URL,
    ]
    assert result.error is None
    assert result.rows[0].percent == 8.0


def test_opencode_go_reports_both_failures_without_inventing_windows(
    monkeypatch, tmp_path: Path
) -> None:
    config = tmp_path / "opencode.json"
    config.write_text(json.dumps({"provider": {"opencode-go": {"options": {"apiKey": "go-key"}}}}))
    settings = _settings(tmp_path).model_copy(update={"opencode_config_file": str(config)})

    class _Client:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def get(self, url, headers=None):
            return _Resp(503, {}, "upstream down")

    monkeypatch.setattr(billing_sources.httpx, "AsyncClient", _Client)
    result = asyncio.run(billing_sources.OpenCodeGoUsageSource(settings).fetch())
    assert result.rows == []
    assert "新路径" in (result.error or "")
    assert "旧路径" in (result.error or "")
    # 不编造档位名
    assert "5 小时" not in (result.error or "")


# --- 兼容名单 / 额度页可见性 ---


def test_plan_catalog_marks_non_refreshable_plans_honestly(client: TestClient) -> None:
    data = client.get("/api/v1/billing/plan-catalog").json()["data"]
    plans = {item["plan_key"]: item for item in data["plans"]}
    assert plans["claude"]["can_auto_refresh"] is False
    assert "额度页先不放卡" in plans["claude"]["note"]
    assert plans["opencode-go"]["can_auto_refresh"] is True
    assert "inference/go/v1/usage" in plans["opencode-go"]["note"]
    assert "私有后端" in plans["codex"]["risk"]
    assert "不是 OpenAI API 组织账单" in plans["codex"]["risk"]


def test_billing_overview_exposes_availability_for_cards(client: TestClient) -> None:
    data = client.get("/api/v1/billing/plans").json()["data"]
    plans = {item["key"]: item for item in data["plans"]}
    # Claude 无官方额度：可用性为 false，但选模仍能用（目录里不依赖这里）
    assert plans["claude"]["can_auto_refresh"] is False
    assert plans["claude"]["available"] is False
    assert "额度页不展示" in plans["claude"]["availability_note"]
    assert plans["codex"]["can_auto_refresh"] is True
