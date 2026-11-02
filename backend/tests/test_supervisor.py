"""Supervisor（助理）P0：事件幂等入库 + outbox 排队。

P0 的验收标准只有两条，测试围绕它们展开：

1. 同一外部事件重复投递 N 次，库里只有一行 trigger、outbox 只有一条；
2. 进程重启后再投同一事件，仍然不产生第二条记录、也不再排动作。
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from flux.config import Settings
from flux.container import Container
from flux.main import create_app


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
