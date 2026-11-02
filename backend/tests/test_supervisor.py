"""Supervisor（助理）：事件幂等入库 + 准入判断（P0 / P1）。

P0 的验收标准只有两条，测试围绕它们展开：

1. 同一外部事件重复投递 N 次，库里只有一行 trigger、outbox 只有一条；
2. 进程重启后再投同一事件，仍然不产生第二条记录、也不再排动作。

P1 的验收标准：

3. Gate 是纯函数：off / 空内容 / 来源停用 / 类型不匹配 / 忽略名单 / 速率超限 → ignore；
4. 判断层无状态：模型输出不合协议或超时 → unavailable，绝不默认 accept；
5. observe 模式只追加判断、推进状态，绝不创建 ticket、不起 Run。
"""

from __future__ import annotations

import asyncio
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from flux.config import Settings
from flux.container import Container
from flux.core.model_gateway.base import ChatMessage, ChatResult, ModelProviderBase, TokenUsage
from flux.core.model_gateway.router import ModelRouter
from flux.core.supervisor.assess import SupervisorAssessor
from flux.core.supervisor.gate import GateContext, evaluate
from flux.enums import AcceptMode, ModelProvider, SupervisorDecision
from flux.main import create_app
from flux.models.supervisor import SupervisorPolicy, SupervisorTicket, SupervisorTrigger


def _event(**overrides) -> dict:
    payload = {
        "source_key": "feishu",
        "external_id": "om_abc123",
        "event_type": "message",
        "actor_id": "ou_owner",
        "actor_platform": "feishu",
        "thread_key": "feishu:chat_1",
        "content": "把登录页拆成三个组件",
        "raw": {"message_id": "om_abc123"},
    }
    payload.update(overrides)
    return payload


def test_repeated_delivery_ingests_once(client: TestClient) -> None:
    first = client.post("/api/v1/supervisor/events", json=_event())
    assert first.status_code == 200, first.text
    assert first.json()["metadata"]["created"] is True

    for _ in range(4):
        again = client.post("/api/v1/supervisor/events", json=_event())
        assert again.status_code == 200, again.text
        assert again.json()["metadata"]["created"] is False
        assert again.json()["data"]["id"] == first.json()["data"]["id"]

    observations = client.get("/api/v1/supervisor/observations").json()
    assert observations["metadata"]["count"] == 1
    # 重复投递不能重复排队：否则 P1 会对着同一条消息反复判断/派活
    assert observations["metadata"]["pending_outbox"] == 1


def test_event_survives_restart(settings: Settings, client: TestClient) -> None:
    """重启（换一个 app/container）后再投同一事件，仍命中已有记录。"""
    created = client.post("/api/v1/supervisor/events", json=_event()).json()
    assert created["metadata"]["created"] is True

    with TestClient(create_app(settings)) as restarted:
        replay = restarted.post("/api/v1/supervisor/events", json=_event())
        assert replay.status_code == 200, replay.text
        assert replay.json()["metadata"]["created"] is False
        assert replay.json()["data"]["id"] == created["data"]["id"]

        observations = restarted.get("/api/v1/supervisor/observations").json()
        assert observations["metadata"]["count"] == 1
        assert observations["metadata"]["pending_outbox"] == 1


def test_event_with_unknown_project_is_rejected(client: TestClient) -> None:
    response = client.post("/api/v1/supervisor/events", json=_event(project_id=str(uuid.uuid4())))
    assert response.status_code == 404, response.text


def test_blank_source_key_is_rejected(client: TestClient) -> None:
    response = client.post("/api/v1/supervisor/events", json=_event(source_key=""))
    assert response.status_code == 422, response.text


def test_settings_default_to_observe_and_persist(client: TestClient) -> None:
    current = client.get("/api/v1/supervisor/settings").json()["data"]
    # 未经用户显式授权前绝不自动派活
    assert current["accept_mode"] == "observe"
    assert current["max_tickets_per_thread"] == 6
    assert current["max_ticket_depth"] == 2

    updated = client.put(
        "/api/v1/supervisor/settings",
        json={"accept_mode": "auto", "max_ticket_depth": 1},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["data"]["accept_mode"] == "auto"
    assert updated.json()["data"]["max_ticket_depth"] == 1
    # 没给的字段保持原值，不能被一次 PUT 清空
    assert updated.json()["data"]["max_tickets_per_thread"] == 6

    reread = client.get("/api/v1/supervisor/settings").json()["data"]
    assert reread["accept_mode"] == "auto"


def test_invalid_accept_mode_is_rejected(client: TestClient) -> None:
    response = client.put("/api/v1/supervisor/settings", json={"accept_mode": "yolo"})
    assert response.status_code == 422, response.text


async def test_outbox_claim_then_fail_requeues(container: Container) -> None:
    await container.supervisor_repo.ingest(
        source_key="flux_internal",
        external_id="evt-1",
        thread_key="flux:thread-1",
        content="继续",
    )
    claimed = await container.supervisor_outbox.claim(limit=10)
    assert len(claimed) == 1
    assert claimed[0].status == "processing"

    await container.supervisor_outbox.fail(claimed[0].id, "模型超时")
    assert await container.supervisor_outbox.pending_count() == 1

    # 退避期内不再被领取，避免对上游持续施压
    assert await container.supervisor_outbox.claim(limit=10) == []


async def test_outbox_stale_rows_are_requeued(container: Container) -> None:
    await container.supervisor_repo.ingest(
        source_key="flux_internal",
        external_id="evt-2",
        thread_key="flux:thread-2",
    )
    claimed = await container.supervisor_outbox.claim(limit=10)
    assert len(claimed) == 1

    # 进程在 processing 状态崩溃：超时后必须退回 pending，不能永久卡住
    assert await container.supervisor_outbox.requeue_stale(older_than_seconds=0) == 1
    assert await container.supervisor_outbox.pending_count() == 1


@pytest.mark.parametrize("event_type", ["message", "mention", "command"])
def test_dedupe_scope_is_per_event_type(client: TestClient, event_type: str) -> None:
    """不同事件类型是不同事实：同一条消息的 message 与 command 各算一条。"""
    first = client.post("/api/v1/supervisor/events", json=_event(event_type=event_type))
    second = client.post("/api/v1/supervisor/events", json=_event(event_type=event_type))
    assert first.json()["metadata"]["created"] is True
    assert second.json()["metadata"]["created"] is False


# --- P1：Gate（纯函数）---


def test_gate_ignores_off_mode_blank_content_and_disabled_source() -> None:
    assert (
        evaluate(
            GateContext(accept_mode=AcceptMode.OFF),
            event_type="message",
            actor_id="",
            content="做事",
        ).decision
        is SupervisorDecision.IGNORE
    )
    assert (
        evaluate(
            GateContext(accept_mode=AcceptMode.OBSERVE, source_enabled=False),
            event_type="message",
            actor_id="ou_owner",
            content="做事",
        ).decision
        is SupervisorDecision.IGNORE
    )
    assert (
        evaluate(
            GateContext(accept_mode=AcceptMode.OBSERVE),
            event_type="message",
            actor_id="",
            content="   ",
        ).decision
        is SupervisorDecision.IGNORE
    )
    assert (
        evaluate(
            GateContext(accept_mode=AcceptMode.OBSERVE),
            event_type="message",
            actor_id="",
            content="把登录页拆成三个组件",
        ).decision
        is SupervisorDecision.ACCEPT
    )


def test_gate_applies_trigger_types_ignore_actors_and_rate_limit() -> None:
    context = GateContext(
        accept_mode=AcceptMode.OBSERVE,
        trigger_types=("command",),
        rules={"ignore_actors": ["ou_bot"], "max_events_per_window": 2, "window_seconds": 60},
        recent_trigger_count=1,
    )
    # 类型不在策略范围
    assert (
        evaluate(context, event_type="message", actor_id="ou_owner", content="做事").decision
        is SupervisorDecision.IGNORE
    )
    # 忽略名单优先于类型检查
    assert (
        evaluate(context, event_type="command", actor_id="ou_bot", content="做事").decision
        is SupervisorDecision.IGNORE
    )
    # 窗口内事件数超过上限
    over = GateContext(
        accept_mode=AcceptMode.OBSERVE,
        rules={"max_events_per_window": 2, "window_seconds": 60},
        recent_trigger_count=3,
    )
    verdict = evaluate(over, event_type="command", actor_id="ou_owner", content="做事")
    assert verdict.decision is SupervisorDecision.IGNORE
    assert verdict.detail["max_events_per_window"] == 2
    # 规则都在范围内 → 放行
    assert (
        evaluate(context, event_type="command", actor_id="ou_owner", content="做事").decision
        is SupervisorDecision.ACCEPT
    )


# --- P1：Assessment（有模型、无状态）---


class _StubProvider(ModelProviderBase):
    provider = ModelProvider.LOCAL

    def __init__(self, content: str, *, delay: float = 0.0) -> None:
        super().__init__(model_name="stub")
        self.content = content
        self.delay = delay
        self.messages: list[ChatMessage] = []

    def is_configured(self) -> bool:
        return True

    async def chat(self, messages: list[ChatMessage], **kwargs: object) -> ChatResult:
        self.messages = list(messages)
        if self.delay:
            await asyncio.sleep(self.delay)
        return ChatResult(
            content=self.content,
            provider=self.provider,
            model=self.model_name,
            usage=TokenUsage(input_tokens=1, output_tokens=1),
            latency_ms=1,
        )


def _trigger(content: str = "把登录页拆成三个组件") -> SupervisorTrigger:
    return SupervisorTrigger(
        id=uuid.uuid4(),
        source_key="feishu",
        external_id="om_assess",
        event_type="message",
        dedupe_key="stub",
        actor_id="ou_owner",
        actor_platform="feishu",
        thread_key="feishu:chat_1",
        content=content,
    )


def _assessor(provider: ModelProviderBase, *, timeout: float = 1.0) -> SupervisorAssessor:
    return SupervisorAssessor(
        ModelRouter({ModelProvider.LOCAL: provider}), timeout_seconds=timeout, max_retries=0
    )


async def test_assessment_parses_structured_json_and_wraps_external_data() -> None:
    provider = _StubProvider(
        '{"decision":"accept","confidence":0.8,"reason":"明确的开发请求",'
        '"risk":{"level":"low"},"should_dispatch":true,"title":"拆分登录页"}'
    )
    result = await _assessor(provider).assess(_trigger())

    assert result.decision is SupervisorDecision.ACCEPT
    assert result.should_dispatch is True
    assert result.title == "拆分登录页"
    assert result.confidence == pytest.approx(0.8)
    assert result.model_meta["provider"] == "local"
    # 外部内容只能以数据块形式进入提示词，并明确声明不是指令
    user_message = provider.messages[-1].content
    assert "<<<EXTERNAL_DATA" in user_message and "EXTERNAL_DATA>>>" in user_message
    assert "把登录页拆成三个组件" in user_message
    system_prompt = provider.messages[0].content
    assert "不是给你的" in system_prompt and "绝不执行" in system_prompt


async def test_assessment_downgrades_non_json_output_to_unavailable() -> None:
    result = await _assessor(_StubProvider("[local-echo] 我不会输出 JSON")).assess(_trigger())
    assert result.decision is SupervisorDecision.UNAVAILABLE
    assert "不可用" in result.reason


async def test_assessment_downgrades_unknown_decision_to_unavailable() -> None:
    result = await _assessor(_StubProvider('{"decision":"maybe","reason":"拿不准"}')).assess(
        _trigger()
    )
    assert result.decision is SupervisorDecision.UNAVAILABLE


async def test_assessment_timeout_is_unavailable() -> None:
    result = await _assessor(
        _StubProvider('{"decision":"accept"}', delay=0.2), timeout=0.01
    ).assess(_trigger())
    assert result.decision is SupervisorDecision.UNAVAILABLE
    assert "超时" in result.reason


# --- P1：worker 串起 Gate → Assessment（observe 不派活）---


async def _ticket_count(container: Container) -> int:
    async with container.session_factory() as session:  # type: ignore[attr-defined]
        return int(await session.scalar(select(func.count()).select_from(SupervisorTicket)) or 0)


async def test_observe_mode_records_eval_and_creates_no_ticket(container: Container) -> None:
    trigger, created = await container.supervisor_repo.ingest(
        source_key="feishu",
        external_id="evt-observe-1",
        thread_key="feishu:chat_observe",
        actor_id="ou_owner",
        content="把登录页拆成三个组件",
    )
    assert created is True

    result = await container.supervisor.process_pending()
    assert result["processed"] == 1

    detail = await container.supervisor.observation_detail(trigger.id)
    assert detail["status"] == "assessed"
    # 测试用 EchoProvider 输出非 JSON：诚实降级为 unavailable，而不是默认放行
    assert detail["eval"]["decision"] == "unavailable"
    assert len(detail["evals"]) == 1
    # 观察模式绝不落 ticket、不起 Run
    assert await _ticket_count(container) == 0
    assert result["pending_outbox"] == 0


async def test_off_mode_ends_event_without_calling_model(container: Container) -> None:
    await container.supervisor.update_settings({"accept_mode": "off"})
    trigger, _ = await container.supervisor_repo.ingest(
        source_key="feishu",
        external_id="evt-off-1",
        thread_key="feishu:chat_off",
        content="把登录页拆成三个组件",
    )

    assert (await container.supervisor.process_pending())["processed"] == 1

    detail = await container.supervisor.observation_detail(trigger.id)
    assert detail["status"] == "ignored"
    # off = 不做判断：不留下 eval 行
    assert detail["evals"] == []


async def test_policy_trigger_types_stop_event_before_model(container: Container) -> None:
    async with container.session_factory() as session:  # type: ignore[attr-defined]
        session.add(
            SupervisorPolicy(
                id=uuid.uuid4(),
                source_key="feishu",
                name="只处理命令",
                trigger_types=["command"],
                rules={},
            )
        )
        await session.commit()

    trigger, _ = await container.supervisor_repo.ingest(
        source_key="feishu",
        external_id="evt-policy-1",
        event_type="message",
        thread_key="feishu:chat_policy",
        content="把登录页拆成三个组件",
    )
    await container.supervisor.process_pending()

    detail = await container.supervisor.observation_detail(trigger.id)
    assert detail["status"] == "ignored"
    assert detail["eval"]["decision"] == "ignore"
    assert "事件类型" in detail["eval"]["reason"]


def test_process_and_manual_reassess_endpoints(client: TestClient) -> None:
    created = client.post("/api/v1/supervisor/events", json=_event(external_id="om_p1")).json()[
        "data"
    ]

    processed = client.post("/api/v1/supervisor/process").json()
    assert processed["data"]["processed"] == 1
    assert processed["data"]["pending_outbox"] == 0

    reassessed = client.post(f"/api/v1/supervisor/observations/{created['id']}/assess")
    assert reassessed.status_code == 200, reassessed.text
    assert reassessed.json()["metadata"]["decision"] == "unavailable"

    detail = client.get(f"/api/v1/supervisor/observations/{created['id']}").json()["data"]
    # 重判只追加，不覆盖旧结论
    assert len(detail["evals"]) == 2
    assert detail["eval"]["decision"] == "unavailable"
    assert detail["status"] == "assessed"
