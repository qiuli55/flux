"""项目助理的读历史 / 接着干 / 开副本 / 进度汇总 API 测试（2026-11-28）。

产品边界：
- 读历史只 GET，不写来源对话；
- 接着干必须二次确认，拒绝时原对话一条消息都不变；
- 开副本必须二次确认，副本是普通常驻对话（不是临时子任务、不进切换器）；
- 进度汇总至少覆盖进行中 / 可能已停住 / 失败 / 已完成。
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import timedelta
from types import SimpleNamespace

from fastapi.testclient import TestClient

from flux.container import Container
from flux.core.task_engine.repository import MessageDraft
from flux.enums import TaskMessageRole, TaskStatus
from flux.models.task import Task

PREFIX = "/api/v1"


def _source(client: TestClient, description: str = "登录改造") -> dict:
    response = client.post(f"{PREFIX}/tasks", json={"description": description, "start": False})
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _assistant(
    client: TestClient,
    *,
    name: str = "登录项目",
    sources: list[str] | None = None,
    confirmed: bool = True,
) -> dict:
    response = client.post(
        f"{PREFIX}/assistants",
        json={"name": name, "source_task_ids": sources or [], "confirmed": confirmed},
    )
    return response


def _messages(client: TestClient, task_id: str) -> list[dict]:
    response = client.get(f"{PREFIX}/tasks/{task_id}/messages", params={"limit": 200})
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _add_message(client: TestClient, task_id: str, role: str, content: str) -> None:
    container: Container = client.app.state.container

    async def _run() -> None:
        await container.task_repo.add_messages(
            uuid.UUID(task_id),
            [MessageDraft(role=role, content=content)],
        )

    asyncio.run(_run())


def _set_status(client: TestClient, task_id: str, status: TaskStatus) -> None:
    container: Container = client.app.state.container
    asyncio.run(container.task_repo.set_status(uuid.UUID(task_id), status))


def _age_task(client: TestClient, task_id: str, hours: int) -> None:
    """把没有消息的任务的 updated_at 调老，用来构造「可能已停住」。"""
    container: Container = client.app.state.container

    async def _run() -> None:
        from flux.core.agent_runtime.run_repository import utcnow

        async with container.task_repo._session_factory() as session:
            task = await session.get(Task, uuid.UUID(task_id))
            assert task is not None
            task.updated_at = utcnow() - timedelta(hours=hours)
            await session.commit()

    asyncio.run(_run())


# --- 进度汇总 ---


def test_progress_summarises_active_stalled_failed_and_completed(client: TestClient) -> None:
    running = _source(client, "正在跑的对话")
    stalled = _source(client, "停了很久的对话")
    failed = _source(client, "失败的对话")
    completed = _source(client, "完成的对话")

    _set_status(client, running["id"], TaskStatus.RUNNING)
    _set_status(client, stalled["id"], TaskStatus.RUNNING)
    _set_status(client, failed["id"], TaskStatus.FAILED)
    _set_status(client, completed["id"], TaskStatus.COMPLETED)
    _add_message(client, running["id"], TaskMessageRole.USER.value, "刚发了一句")
    _age_task(client, stalled["id"], hours=10)

    created = _assistant(
        client,
        sources=[running["id"], stalled["id"], failed["id"], completed["id"]],
    )
    assert created.status_code == 200, created.text
    assistant_id = created.json()["data"]["id"]

    response = client.get(f"{PREFIX}/assistants/{assistant_id}/progress")
    assert response.status_code == 200, response.text
    data = response.json()["data"]

    assert data["stalled_after_hours"] == 6
    assert data["counts"]["running"] == 1
    assert data["counts"]["possibly_stalled"] == 1
    assert data["counts"]["failed"] == 1
    assert data["counts"]["completed"] == 1
    assert "进度汇总" in response.json()["metadata"]["text"]
    buckets = {item["task_id"]: item["bucket"] for item in data["conversations"]}
    assert buckets[running["id"]] == "running"
    assert buckets[stalled["id"]] == "possibly_stalled"
    assert buckets[failed["id"]] == "failed"
    assert buckets[completed["id"]] == "completed"


def test_project_assistant_reply_leads_with_progress_summary(client: TestClient) -> None:
    from flux.core.task_engine.assistant import AssistantTurn

    source = _source(client)
    _set_status(client, source["id"], TaskStatus.COMPLETED)
    created = _assistant(client, sources=[source["id"]])
    conversation_id = created.json()["data"]["conversation_task_id"]

    container: Container = client.app.state.container

    async def _fake_respond(**kwargs):  # noqa: ANN003
        return AssistantTurn(reply="这段对话已经完成。", provider="fake", model="fake")

    container.assistant.respond = _fake_respond  # type: ignore[method-assign]

    response = client.post(
        f"{PREFIX}/tasks/{conversation_id}/messages",
        json={"content": "现在进度怎么样？"},
    )
    assert response.status_code == 200, response.text
    reply = response.json()["data"]["messages"][1]
    assert reply["content"].startswith("进度汇总：")
    assert "已完成：1" in reply["content"]
    assert reply["payload"]["progress_summary"] is True


# --- 读历史 ---


def test_read_history_is_read_only_and_limited_to_related_conversations(
    client: TestClient,
) -> None:
    source = _source(client)
    _add_message(client, source["id"], TaskMessageRole.USER.value, "登录页已经改完")
    before = _messages(client, source["id"])

    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]

    listing = client.get(f"{PREFIX}/assistants/{assistant_id}/history")
    assert listing.status_code == 200, listing.text
    data = listing.json()["data"]
    assert data["read_only"] is True
    assert [item["task_id"] for item in data["conversations"]] == [source["id"]]
    assert data["conversations"][0]["read_only"] is True

    detail = client.get(f"{PREFIX}/assistants/{assistant_id}/history/{source['id']}")
    assert detail.status_code == 200, detail.text
    detail_data = detail.json()["data"]
    assert detail_data["read_only"] is True
    assert any(message["content"] == "登录页已经改完" for message in detail_data["messages"])
    # 读历史只提供 GET：项目助理不能借这条路径向来源对话发送
    write_attempt = client.post(
        f"{PREFIX}/assistants/{assistant_id}/history/{source['id']}",
        json={"content": "悄悄往来源对话发一条"},
    )
    assert write_attempt.status_code in {404, 405}
    # 读历史不写来源对话
    assert _messages(client, source["id"]) == before

    unrelated = _source(client, "不相关的对话")
    denied = client.get(f"{PREFIX}/assistants/{assistant_id}/history/{unrelated['id']}")
    assert denied.status_code == 404


# --- 接着干 ---


def test_continue_requires_confirmation_and_leaves_source_untouched(client: TestClient) -> None:
    source = _source(client)
    before = _messages(client, source["id"])
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/continue",
        json={
            "source_task_id": source["id"],
            "content": "继续把登录页的错误分支补上",
            "confirmed": False,
        },
    )
    assert response.status_code == 409
    assert _messages(client, source["id"]) == before
    assert client.get(f"{PREFIX}/tasks/{source['id']}").json()["data"]["status"] == "pending"


def test_continue_after_confirmation_sends_task_into_existing_conversation(
    client: TestClient,
) -> None:
    source = _source(client)
    _set_status(client, source["id"], TaskStatus.COMPLETED)
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]
    container: Container = client.app.state.container

    async def _fake_start(task, *args, **kwargs):  # noqa: ANN001, ANN002, ANN003
        updated = await container.task_repo.set_status(task.id, TaskStatus.RUNNING)
        return SimpleNamespace(task=updated, run={"run_id": "fake"})

    container.task_run_service.start = _fake_start  # type: ignore[method-assign]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/continue",
        json={
            "source_task_id": source["id"],
            "content": "继续把登录页的错误分支补上",
            "confirmed": True,
        },
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["task"]["id"] == source["id"]
    assert data["task"]["parent_task_id"] is None
    assert data["task"]["origin"] == "chat"
    assert data["messages"][-1]["content"] == "继续把登录页的错误分支补上"
    assert _messages(client, source["id"])[-1]["content"] == "继续把登录页的错误分支补上"


def test_continue_rejects_running_target(client: TestClient) -> None:
    """真正进行中（这一轮还在跑、更新尚未过期）仍然不允许接着干。"""
    source = _source(client)
    _set_status(client, source["id"], TaskStatus.RUNNING)
    _add_message(client, source["id"], TaskMessageRole.USER.value, "刚发了一句")
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/continue",
        json={"source_task_id": source["id"], "content": "继续", "confirmed": True},
    )
    assert response.status_code == 409
    assert "还在进行中" in response.json()["message"]


def test_continue_rejects_possibly_stalled_without_confirmation(client: TestClient) -> None:
    """可能已停住也必须二次确认：未确认时 409，原对话一条消息都不变。"""
    source = _source(client)
    _set_status(client, source["id"], TaskStatus.RUNNING)
    _age_task(client, source["id"], hours=10)
    before = _messages(client, source["id"])
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/continue",
        json={"source_task_id": source["id"], "content": "继续", "confirmed": False},
    )
    assert response.status_code == 409
    assert _messages(client, source["id"]) == before
    assert client.get(f"{PREFIX}/tasks/{source['id']}").json()["data"]["status"] == "running"


def test_continue_allows_possibly_stalled_after_confirmation(client: TestClient) -> None:
    """可能已停住：用户再次确认后，先结束旧的一轮，再追加消息并续跑。"""
    source = _source(client)
    _set_status(client, source["id"], TaskStatus.RUNNING)
    _age_task(client, source["id"], hours=10)
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]
    container: Container = client.app.state.container
    started: list[str] = []

    async def _fake_start(task, *args, **kwargs):  # noqa: ANN001, ANN002, ANN003
        started.append(str(task.id))
        updated = await container.task_repo.set_status(task.id, TaskStatus.RUNNING)
        return SimpleNamespace(task=updated, run={"run_id": "fake"})

    container.task_run_service.start = _fake_start  # type: ignore[method-assign]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/continue",
        json={
            "source_task_id": source["id"],
            "content": "登录页的错误分支还没补，接着做",
            "confirmed": True,
        },
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["task"]["id"] == source["id"]
    assert started == [source["id"]]
    assert _messages(client, source["id"])[-1]["content"] == "登录页的错误分支还没补，接着做"
    assert client.get(f"{PREFIX}/tasks/{source['id']}").json()["data"]["status"] == "running"


# --- 开副本 ---


def test_fork_requires_confirmation_and_creates_nothing(client: TestClient) -> None:
    source = _source(client)
    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]
    before = client.get(f"{PREFIX}/tasks", params={"limit": 200}).json()["data"]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/fork",
        json={"source_task_id": source["id"], "confirmed": False},
    )
    assert response.status_code == 409
    after = client.get(f"{PREFIX}/tasks", params={"limit": 200}).json()["data"]
    assert [item["id"] for item in after] == [item["id"] for item in before]


def test_fork_creates_independent_persistent_conversation(client: TestClient) -> None:
    source = _source(client)
    _add_message(client, source["id"], TaskMessageRole.USER.value, "登录页已经改完")
    _add_message(client, source["id"], TaskMessageRole.ASSISTANT.value, "接下来补错误分支")
    before = _messages(client, source["id"])

    created = _assistant(client, sources=[source["id"]])
    assistant_id = created.json()["data"]["id"]

    response = client.post(
        f"{PREFIX}/assistants/{assistant_id}/fork",
        json={"source_task_id": source["id"], "name": "登录改造副本", "confirmed": True},
    )
    assert response.status_code == 200, response.text
    fork = response.json()["data"]["task"]
    assert fork["origin"] == "chat"
    assert fork["parent_task_id"] is None
    assert fork["description"] == "登录改造副本"

    fork_messages = _messages(client, fork["id"])
    summary = [message for message in fork_messages if message["kind"] == "summary"]
    assert len(summary) == 1
    assert summary[0]["payload"]["from_task_id"] == source["id"]
    assert "登录页已经改完" in summary[0]["content"]

    # 原文一条都不改
    assert _messages(client, source["id"]) == before

    # 副本不是临时子任务：不挂在主助理对话下，也不进项目助理切换器
    master = client.post(
        f"{PREFIX}/tasks", json={"description": "主助理", "origin": "assistant", "start": False}
    ).json()["data"]
    subtasks = client.get(f"{PREFIX}/tasks/{master['id']}/subtasks").json()["data"]
    assert fork["id"] not in [item["subtask_id"] for item in subtasks]
    switcher = client.get(f"{PREFIX}/assistants").json()["data"]
    assert fork["id"] not in [item["conversation_task_id"] for item in switcher["projects"]]
    # 但它是普通常驻对话，会出现在对话列表里
    listed = client.get(f"{PREFIX}/tasks", params={"limit": 200}).json()["data"]
    assert fork["id"] in [item["id"] for item in listed]
