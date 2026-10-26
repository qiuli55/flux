"""计费与额度：套餐归因、账本聚合、闸门判定、REST 接口、自定义套餐。"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

import pytest
from fastapi.testclient import TestClient

from flux.container import Container
from flux.core.billing.plans import (
    PLANS,
    PLAN_AGENT_PLAN,
    PLAN_CLAUDE,
    PLAN_CODEX,
    PLAN_OPENCODE_GO,
    PLAN_OPENCODE_ZEN,
    load_plan_registry,
    resolve_plan,
)
from flux.core.billing.pricing import load_price_book
from flux.core.billing.service import (
    LEVEL_EXCEEDED,
    LEVEL_OK,
    LEVEL_WARNING,
    BillingService,
)
from flux.core.billing.sources import HttpUsageSource, SnapshotRow, SourceResult, build_sources
from flux.errors import ConflictError


class _FakeSource:
    """替身采集器：不联网、不定时，返回注入的窗口快照。"""

    name = "fake"

    def __init__(self, rows: list[SnapshotRow]) -> None:
        self._rows = rows

    async def fetch(self) -> SourceResult:
        return SourceResult(source=self.name, rows=list(self._rows))


def _rows(plan_key: str, percent: float, windows: tuple[str, ...]) -> list[SnapshotRow]:
    return [SnapshotRow(plan_key=plan_key, window_key=w, percent=percent) for w in windows]


def _service(container: Container, *, percent: float = 10.0, enforce: bool = False) -> BillingService:
    settings = container.settings.model_copy(
        update={"billing_enforce": enforce, "billing_refresh_seconds": 300}
    )
    service = BillingService(settings, container.session_factory)
    service._sources = {  # noqa: SLF001 - 测试注入替身，避免真实网络/子进程
        PLAN_OPENCODE_GO: _FakeSource(
            _rows(PLAN_OPENCODE_GO, percent, ("rolling_5h", "weekly", "monthly"))
        ),
        PLAN_AGENT_PLAN: _FakeSource(
            _rows(PLAN_AGENT_PLAN, percent, ("rolling_5h", "daily", "weekly", "monthly"))
        ),
    }
    return service


def test_resolve_plan_by_model_prefix() -> None:
    assert resolve_plan("opencode-go/gemini-4-argon") is not None
    assert resolve_plan("opencode-go/gemini-4-argon").key == PLAN_OPENCODE_GO
    assert resolve_plan("agent-plan/doubao-seed").key == PLAN_AGENT_PLAN
    # Zen 免费模型只记账，但仍归到 opencode 套餐视图
    assert resolve_plan("opencode/deepseek-v4.1-flash-free").key == PLAN_OPENCODE_ZEN
    assert resolve_plan("codex/gpt-5.5").key == PLAN_CODEX
    assert resolve_plan("claude/sonnet-4.6").key == PLAN_CLAUDE
    assert resolve_plan("deepseek/deepseek-chat") is None
    assert resolve_plan("") is None


def test_resolve_plan_by_runtime_when_model_has_no_prefix() -> None:
    """codex 的模型 id 不带前缀（gpt-5.5），靠 runtime 归因到 Codex 套餐。"""
    assert resolve_plan("gpt-5.5", "codex").key == PLAN_CODEX
    assert resolve_plan("gpt-5.5", "opencode") is None
    assert resolve_plan("", "claude-code").key == PLAN_CLAUDE
    # 前缀优先于 runtime
    assert resolve_plan("agent-plan/doubao-seed", "codex").key == PLAN_AGENT_PLAN


def test_custom_plans_extend_registry_without_code(tmp_path) -> None:
    path = tmp_path / "billing-plans.json"
    path.write_text(
        json.dumps(
            {
                "plans": [
                    {
                        "key": "claude-max",
                        "label": "Claude Max",
                        "vendor": "Anthropic",
                        "model_prefixes": ["claude-max"],
                        "runtime_ids": ["claude-max"],
                        "windows": ["5h", "weekly", "monthly"],
                        "source": {"kind": "http", "url": "https://example.com/usage"},
                    },
                    # 同 key 覆盖内置定义（只记账）
                    {"key": PLAN_CODEX, "model_prefixes": ["codex"], "windows": ["weekly"]},
                    # kind=none 显式关掉内置套餐的官方采集
                    {"key": PLAN_AGENT_PLAN, "model_prefixes": ["agent-plan"], "source": {"kind": "none"}},
                    # 缺 key / 缺前缀的条目跳过，不让一条写错拖垮整套
                    {"label": "broken"},
                    {"key": "broken-2"},
                ]
            }
        ),
        encoding="utf-8",
    )
    registry = load_plan_registry(str(path))
    assert registry["claude-max"].label == "Claude Max"
    assert registry["claude-max"].official_source == "http"
    assert resolve_plan("claude-max/opus", plans=registry).key == "claude-max"
    assert resolve_plan("gpt-5.5", "claude-max", plans=registry).key == "claude-max"
    # 覆盖内置 codex：runtime 归因被去掉，windows 只剩周
    assert registry[PLAN_CODEX].runtime_ids == ()
    assert [w.key for w in registry[PLAN_CODEX].windows] == ["weekly"]
    assert PLANS[PLAN_CODEX].runtime_ids == ("codex",)  # 内置定义本身没被改
    assert "broken" not in registry and "broken-2" not in registry

    sources = build_sources(container_settings(tmp_path), registry)
    assert isinstance(sources["claude-max"], HttpUsageSource)
    assert PLAN_OPENCODE_GO in sources
    # kind=none 覆盖后不再装配内置采集器
    assert PLAN_AGENT_PLAN not in sources


def container_settings(tmp_path):
    """构造一份最小 Settings（自定义套餐测试不碰容器/数据库）。"""
    from flux.config import Settings

    return Settings(
        env="test",
        database_url=f"sqlite+aiosqlite:///{tmp_path / 'billing.db'}",
        billing_plans_file="",
    )


def test_custom_plan_shows_up_in_overview(client: TestClient, tmp_path) -> None:
    """自定义文件加一条 → 概览/前端立即多一张套餐卡（不必改代码）。"""
    container: Container = client.app.state.container
    path = tmp_path / "billing-plans.json"
    path.write_text(
        json.dumps(
            {
                "plans": [
                    {
                        "key": "claude-max",
                        "label": "Claude Max",
                        "vendor": "Anthropic",
                        "model_prefixes": ["claude-max"],
                        "windows": ["weekly"],
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    container.billing._plans = load_plan_registry(str(path))  # noqa: SLF001
    container.billing._sources = {}  # noqa: SLF001 - 无官方来源，纯账本

    data = client.get("/api/v1/billing/plans").json()["data"]
    plan = next(item for item in data["plans"] if item["key"] == "claude-max")
    assert plan["label"] == "Claude Max"
    assert plan["official_source"] == ""
    assert plan["windows"][0]["official"] is None
    assert plan["gate"]["level"] == "unknown"


def test_price_book_estimates_from_official_prices() -> None:
    """价目表按官方单价估算；DeepSeek 分峰谷，火山人民币换算成美元。"""
    book = load_price_book(None)
    entry = book.find("opencode-go/gemini-4-argon")
    assert entry is not None and entry.vendor == "Google"
    assert (
        book.estimate_cost(
            "opencode-go/gemini-4-argon",
            input_tokens=1_000_000,
            output_tokens=1_000_000,
            cache_read_tokens=1_000_000,
        )
        == 12.1
    )

    # 2026-10-26 是周一：02:00 UTC 峰时、12:00 UTC 错峰
    peak = datetime(2026, 10, 26, 2, 0, tzinfo=timezone.utc)
    off_peak = datetime(2026, 10, 26, 12, 0, tzinfo=timezone.utc)
    assert book.estimate_cost("deepseek-flash", input_tokens=1_000_000, occurred_at=peak) == 0.3
    assert book.estimate_cost("deepseek-flash", input_tokens=1_000_000, occurred_at=off_peak) == 0.15

    # 火山价目是人民币：6 元 / 7.2
    assert book.estimate_cost("agent-plan/doubao-seed", input_tokens=1_000_000) == round(6 / 7.2, 6)
    assert book.find("deepseek/deepseek-v4-flash").model == "deepseek-flash"
    assert book.find("gpt-5.5-2026-10-01").model == "gpt-5.5"
    assert book.find("some-unknown/model") is None


def test_price_book_custom_file_overrides_builtin(tmp_path) -> None:
    """价格变了只改 JSON；同 model 覆盖内置，其它条目保持。"""
    path = tmp_path / "billing-prices.json"
    path.write_text(
        json.dumps(
            {
                "as_of": "2026-11-01",
                "prices": [
                    {"model": "gpt-5.5", "vendor": "OpenAI", "input": 6, "output": 36},
                ],
            }
        ),
        encoding="utf-8",
    )
    book = load_price_book(str(path))
    assert book.as_of == "2026-11-01"
    assert book.find("gpt-5.5").input == 6
    assert book.estimate_cost("gpt-5.5", input_tokens=1_000_000) == 6
    # 未覆盖的条目仍在
    assert book.find("claude-sonnet-5.5") is not None


async def test_record_step_fills_missing_cost_from_price_book(container: Container) -> None:
    """runtime 没报 cost 时按价目表估算；已报的 cost 不被覆盖。"""
    service = _service(container)
    await service.record_step(run_id="run-price", model="claude-sonnet-5.5", input_tokens=1_000_000)
    run = await service.run_usage("run-price")
    assert run["totals"]["cost"] == 2.0

    await service.record_step(
        run_id="run-price", model="claude-sonnet-5.5", input_tokens=1_000_000, cost=9.99
    )
    run = await service.run_usage("run-price")
    assert run["totals"]["cost"] == 11.99


async def test_record_step_aggregates_into_plan_windows(container: Container) -> None:
    service = _service(container)
    await service.record_step(
        run_id="run-1", model="opencode-go/gemini-4-argon", input_tokens=100, output_tokens=50, cost=1.5
    )
    await service.record_step(
        run_id="run-1", model="opencode-go/gemini-4-argon", input_tokens=20, output_tokens=10, cost=0.5
    )
    # 未登记套餐的模型只记账，不进任何套餐合计
    await service.record_step(run_id="run-2", model="deepseek/deepseek-chat", input_tokens=999)

    view = await service.plan_view(PLAN_OPENCODE_GO)
    monthly = next(w for w in view["windows"] if w["key"] == "monthly")
    assert monthly["local"]["records"] == 2
    assert monthly["local"]["cost"] == 2.0
    assert monthly["local"]["total_tokens"] == 180
    # 官方快照 10% → 正常
    assert view["gate"]["level"] == LEVEL_OK

    run = await service.run_usage("run-1")
    assert run["totals"]["records"] == 2
    assert run["totals"]["total_tokens"] == 180


async def test_gate_warns_then_blocks_only_when_enforced(container: Container) -> None:
    warning = _service(container, percent=85.0)
    assert (await warning.gate_for_model("opencode-go/gemini-4-argon")).level == LEVEL_WARNING

    exceeded = _service(container, percent=96.0)
    decision = await exceeded.gate_for_model("agent-plan/doubao-seed")
    assert decision.level == LEVEL_EXCEEDED
    assert decision.blocked is False  # 默认只告警
    await exceeded.ensure_can_start(model="agent-plan/doubao-seed", runtime_id="opencode")

    enforced = _service(container, percent=96.0, enforce=True)
    with pytest.raises(ConflictError) as excinfo:
        await enforced.ensure_can_start(model="agent-plan/doubao-seed", runtime_id="opencode")
    assert excinfo.value.code == "billing_quota_exceeded"


def test_billing_api_endpoints(client: TestClient) -> None:
    container: Container = client.app.state.container
    container.billing._sources = _service(container)._sources  # noqa: SLF001 - 免联网

    overview = client.get("/api/v1/billing/plans")
    assert overview.status_code == 200, overview.text
    plans = {item["key"]: item for item in overview.json()["data"]["plans"]}
    assert set(plans) == {
        PLAN_OPENCODE_GO,
        PLAN_AGENT_PLAN,
        PLAN_OPENCODE_ZEN,
        PLAN_CODEX,
        PLAN_CLAUDE,
    }

    usage = client.get("/api/v1/billing/usage", params={"plan": PLAN_OPENCODE_GO})
    assert usage.status_code == 200, usage.text
    assert usage.json()["data"]["key"] == PLAN_OPENCODE_GO

    prices = client.get("/api/v1/billing/prices")
    assert prices.status_code == 200, prices.text
    price_data = prices.json()["data"]
    assert price_data["as_of"]
    assert {item["model"] for item in price_data["prices"]} >= {
        "claude-sonnet-5.5",
        "gemini-4-argon",
        "deepseek-flash",
    }

    # 不传 plan 等价于概览
    assert client.get("/api/v1/billing/usage").json()["data"]["plans"]

    missing = client.get("/api/v1/billing/usage", params={"plan": "nope"})
    assert missing.status_code == 409
    assert missing.json()["code"] == "unknown_plan"

    refreshed = client.post("/api/v1/billing/refresh")
    assert refreshed.status_code == 200, refreshed.text
    results: list[dict[str, Any]] = refreshed.json()["data"]["results"]
    assert {item["plan_key"] for item in results} >= {PLAN_OPENCODE_GO, PLAN_AGENT_PLAN}

    run = client.get("/api/v1/billing/runs/run-404")
    assert run.status_code == 200, run.text
    assert run.json()["data"]["totals"]["records"] == 0
