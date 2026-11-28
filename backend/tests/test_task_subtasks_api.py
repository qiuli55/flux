"""主助理调度员 + 临时子任务 + 项目助理 API 测试（2026-11-28）。

覆盖：
- 快聊不在父任务起 Agent/Run，也不把父任务表现成 running；
- 执行需求立即派子任务并快照父对话选择，父请求不等待子任务完成；
- 子任务只读：用户直接发消息被拒绝；
- 更换 / 再开一个；只改父选择器不影响进行中的子任务；
- runtime 不可用时父/子都有中文说明，父对话仍可快聊；
- 列表默认不含子任务；删除父对话级联删除子任务；
- 项目助理：创建须确认、取消不创建、不能派子任务、只读来源对话。
"""

from __future__ import annotations

import asyncio
import time
import uuid

from fastapi.testclient import TestClient

from flux.container import Container

PREFIX = "/api/v1"


def _create_agent(client: TestClient, name: str, runtime: str = "dsh") -> str:
    response = client.post(
        f"{PREFIX}/agents", json={"name": name, "role": "developer", "runtime": runtime}
    )
    assert response.status_code == 200, response.text
    return str(response.json()["data"]["id"])


def _assistant_task(
    client: TestClient, *, agent_id: str | None = None, model: str | None = None
) -> dict:
    body: dict = {"description": "主助理", "origin": "assistant", "start": False}
    if agent_id:
        body["agent_id"] = agent_id
    if model:
        body["model"] = model
    response = client.post(f"{PREFIX}/tasks", json=body)
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _send(client: TestClient, task_id: str, content: str) -> dict:
    response = client.post(f"{PREFIX}/tasks/{task_id}/messages", json={"content": content})
    assert response.status_code == 200, response.text
    return response.json()


def _messages(client: TestClient, task_id: str) -> list[dict]:
    response = client.get(f"{PREFIX}/tasks/{task_id}/messages", params={"limit": 100})
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _subtasks(client: TestClient, task_id: str) -> list[dict]:
    response = client.get(f"{PREFIX}/tasks/{task_id}/subtasks")
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _patch_slow_start(client: TestClient, seconds: float = 2.0) -> None:
    container: Container = client.app.state.container

    async def _slow(task, *args, **kwargs):  # noqa: ANN001, ANN002, ANN003
        await asyncio.sleep(seconds)

    container.task_run_service.start = _slow  # type: ignore[method-assign]


def _patch_failing_start(client: TestClient) -> None:
    container: Container = client.app.state.container

    async def _fail(task, *args, **kwargs):  # noqa: ANN001, ANN002, ANN003
        raise RuntimeError("runtime unavailable")

    container.task_run_service.start = _fail  # type: ignore[method-assign]


# --- 快聊 ---


def test_chat_stays_fast_and_does_not_mark_parent_running(client: TestClient) -> None:
    task = _assistant_task(client)
    body = _send(client, task["id"], "你好，你是谁？")

    assert body["metadata"]["route"] == "chat"
    assert body["data"]["task"]["status"] == "pending"
    assert body["data"]["task"]["run_id"] is None
    messages = _messages(client, task["id"])
    assert [m["role"] for m in messages] == ["user", "assistant"]
    assert _subtasks(client, task["id"]) == []


def test_progress_question_stays_fast(client: TestClient) -> None:
    task = _assistant_task(client)
    body = _send(client, task["id"], "现在进度怎么样了？")
    assert body["metadata"]["route"] == "chat"
    assert _subtasks(client, task["id"]) == []


# --- 派活 ---


def test_execution_dispatches_subtask_and_snapshots_parent_selection(client: TestClient) -> None:
    agent_id = _create_agent(client, "builder")
    task = _assistant_task(client, agent_id=agent_id, model="deepseek-v4-flash")

    body = _send(client, task["id"], "帮我修一下登录页的报错")

    assert body["metadata"]["route"] == "dispatch"
    child_id = body["data"]["subtask"]["subtask_id"]
    children = _subtasks(client, task["id"])
    assert [item["subtask_id"] for item in children] == [child_id]
    assert children[0]["agent_id"] == agent_id
    assert children[0]["model_name"] == "deepseek-v4-flash"
    assert children[0]["parent_task_id"] == task["id"]

    # 父对话立即出现「已派出」卡片，父任务不进入 running
    messages = _messages(client, task["id"])
    assert messages[0]["role"] == "user"
    card = messages[1]
    assert card["kind"] == "subtask"
    assert card["payload"]["subtask_id"] == child_id
    parent = client.get(f"{PREFIX}/tasks/{task['id']}").json()["data"]
    assert parent["status"] == "pending"
    assert parent["run_id"] is None


def test_dispatch_returns_before_slow_subtask_finishes(client: TestClient) -> None:
    _patch_slow_start(client, seconds=2.0)
    task = _assistant_task(client)

    started = time.perf_counter()
    body = _send(client, task["id"], "帮我写一个脚本")
    elapsed = time.perf_counter() - started

    assert body["metadata"]["route"] == "dispatch"
    assert elapsed < 1.5, f"父请求等了子任务：{elapsed:.2f}s"
    children = _subtasks(client, task["id"])
    assert len(children) == 1
    time.sleep(2.2)  # 让后台启动协程自己收尾，避免测试结束留下悬挂任务


def test_subtask_messages_are_read_only(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.1)
    task = _assistant_task(client)
    child_id = _send(client, task["id"], "帮我跑一下测试")["data"]["subtask"]["subtask_id"]

    response = client.post(f"{PREFIX}/tasks/{child_id}/messages", json={"content": "改一下方向"})
    assert response.status_code == 409, response.text
    assert "回到助理对话" in response.json()["message"]
    assert (
        client.post(f"{PREFIX}/tasks/{child_id}/continue", json={"content": "继续"}).status_code
        == 409
    )


def test_replace_stops_previous_and_dispatches_new_agent(client: TestClient) -> None:
    _patch_slow_start(client, seconds=2.0)
    first_agent = _create_agent(client, "builder")
    second_agent = _create_agent(client, "reviewer")
    task = _assistant_task(client, agent_id=first_agent)
    _send(client, task["id"], "帮我修复登录接口")

    body = _send(client, task["id"], "换用 reviewer 来做")
    assert body["metadata"]["route"] == "replace"
    children = _subtasks(client, task["id"])
    assert len(children) == 2
    statuses = {item["subtask_id"]: item["status"] for item in children}
    first = children[0]
    new = body["data"]["subtask"]
    assert statuses[first["subtask_id"]] == "cancelled"
    assert new["agent_id"] == second_agent
    assert new["status"] != "cancelled"
    # 更换后接着做同一需求，而不是把「换用某某做」当成新需求
    replaced_task = client.get(f"{PREFIX}/tasks/{new['subtask_id']}").json()["data"]
    assert replaced_task["description"] == "帮我修复登录接口"
    # 主对话说明了换了什么
    kinds = [m["kind"] for m in _messages(client, task["id"])]
    assert "subtask_result" in kinds
    time.sleep(2.2)


def test_add_keeps_existing_subtask_running(client: TestClient) -> None:
    _patch_slow_start(client, seconds=2.0)
    task = _assistant_task(client)
    first = _send(client, task["id"], "帮我做页面 A")["data"]["subtask"]["subtask_id"]

    body = _send(client, task["id"], "再开一个，顺便把页面 B 也做了")
    assert body["metadata"]["route"] == "add"
    children = _subtasks(client, task["id"])
    assert len(children) == 2
    by_id = {item["subtask_id"]: item["status"] for item in children}
    assert by_id[first] != "cancelled"
    time.sleep(2.2)


def test_replace_only_stops_most_recent_active_subtask(client: TestClient) -> None:
    _patch_slow_start(client, seconds=2.0)
    task = _assistant_task(client)
    first = _send(client, task["id"], "帮我做页面 A")["data"]["subtask"]["subtask_id"]
    second = _send(client, task["id"], "再开一个，把页面 B 也做了")["data"]["subtask"]["subtask_id"]

    replaced = _send(client, task["id"], "换用另一个做法")["data"]["subtask"]["subtask_id"]
    by_id = {item["subtask_id"]: item["status"] for item in _subtasks(client, task["id"])}
    assert by_id[first] != "cancelled"  # 并行任务继续跑
    assert by_id[second] == "cancelled"  # 只更换最近一个
    assert by_id[replaced] not in {"cancelled", "failed"}
    time.sleep(2.2)


def test_parent_selection_change_does_not_touch_running_subtask(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.1)
    first_agent = _create_agent(client, "builder")
    second_agent = _create_agent(client, "reviewer")
    task = _assistant_task(client, agent_id=first_agent)
    child = _send(client, task["id"], "帮我实现接口")["data"]["subtask"]

    response = client.post(f"{PREFIX}/tasks/{task['id']}/agent", json={"agent_id": second_agent})
    assert response.status_code == 200, response.text

    children = _subtasks(client, task["id"])
    assert children[0]["subtask_id"] == child["subtask_id"]
    assert children[0]["agent_id"] == first_agent
    # 下一次新开才用新选择
    new_child = _send(client, task["id"], "再开一个处理另一个接口")["data"]["subtask"]
    assert new_child["agent_id"] == second_agent


def test_runtime_unavailable_is_explained_on_both_sides_and_chat_still_works(
    client: TestClient,
) -> None:
    _patch_failing_start(client)
    task = _assistant_task(client)
    body = _send(client, task["id"], "帮我部署到测试环境")
    child_id = body["data"]["subtask"]["subtask_id"]

    deadline = time.time() + 5
    parent_messages: list[dict] = []
    while time.time() < deadline:
        parent_messages = _messages(client, task["id"])
        if any(m["kind"] == "subtask_result" for m in parent_messages):
            break
        time.sleep(0.05)

    result = next(m for m in parent_messages if m["kind"] == "subtask_result")
    assert "电脑" in result["content"] and "暂时办不了" in result["content"]
    child = client.get(f"{PREFIX}/tasks/{child_id}").json()["data"]
    assert child["status"] == "failed"
    child_messages = _messages(client, child_id)
    assert any("电脑" in m["content"] for m in child_messages)

    # 主对话不被卡住：仍可快聊
    follow = _send(client, task["id"], "那先聊点别的")
    assert follow["metadata"]["route"] == "chat"


def test_start_on_parent_dispatches_subtask_instead_of_run(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.05)
    task = _assistant_task(client)
    response = client.post(f"{PREFIX}/tasks/{task['id']}/start", json={})
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["task"]["status"] == "pending"
    assert data["subtask"]["parent_task_id"] == task["id"]
    assert _subtasks(client, task["id"])[0]["subtask_id"] == data["subtask"]["subtask_id"]


# --- 列表 / 删除 ---


def test_list_tasks_excludes_subtasks_but_subtasks_endpoint_lists_them(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.1)
    task = _assistant_task(client)
    child_id = _send(client, task["id"], "帮我写代码")["data"]["subtask"]["subtask_id"]

    listed = client.get(f"{PREFIX}/tasks", params={"limit": 200}).json()["data"]
    assert task["id"] in [item["id"] for item in listed]
    assert child_id not in [item["id"] for item in listed]

    only_subtasks = client.get(f"{PREFIX}/tasks", params={"origin": "subtask"}).json()["data"]
    assert [item["id"] for item in only_subtasks] == [child_id]


def test_delete_parent_removes_subtasks(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.1)
    container: Container = client.app.state.container
    task = _assistant_task(client)
    child_id = _send(client, task["id"], "帮我写代码")["data"]["subtask"]["subtask_id"]

    assert client.delete(f"{PREFIX}/tasks/{task['id']}").status_code == 200
    assert client.get(f"{PREFIX}/tasks/{child_id}").status_code == 404
    asyncio.run(_assert_no_task(container, uuid.UUID(child_id)))


async def _assert_no_task(container: Container, task_id: uuid.UUID) -> None:
    from flux.errors import NotFoundError

    try:
        await container.task_repo.get(task_id)
    except NotFoundError:
        return
    raise AssertionError("子任务未被级联删除")


# --- 项目助理 ---


def _create_project_assistant(
    client: TestClient,
    *,
    name: str = "项目 A",
    sources: list[str] | None = None,
    confirmed: bool = True,
) -> dict:
    response = client.post(
        f"{PREFIX}/assistants",
        json={"name": name, "source_task_ids": sources or [], "confirmed": confirmed},
    )
    return response


def test_project_assistant_requires_confirmation_and_cancel_creates_nothing(
    client: TestClient,
) -> None:
    response = _create_project_assistant(client, confirmed=False)
    assert response.status_code == 409
    assert client.get(f"{PREFIX}/assistants").json()["data"]["projects"] == []


def test_assistant_switcher_has_master_and_created_projects_only(client: TestClient) -> None:
    _patch_slow_start(client, seconds=0.1)
    master = _assistant_task(client)
    _send(client, master["id"], "帮我写代码")  # 产生一个临时子任务
    created = _create_project_assistant(client, name="项目 A")
    assert created.status_code == 200, created.text
    project_id = created.json()["data"]["id"]

    data = client.get(f"{PREFIX}/assistants").json()["data"]
    assert data["master"]["name"] == "主助理"
    assert data["master"]["conversation_task_id"] == master["id"]
    assert [item["id"] for item in data["projects"]] == [project_id]
    # 临时子任务不混进切换器
    assert all("subtask" not in item["kind"] for item in data["projects"])


def test_project_assistant_cannot_dispatch_subtasks(client: TestClient) -> None:
    created = _create_project_assistant(client, name="项目 A")
    conversation_id = created.json()["data"]["conversation_task_id"]

    body = _send(client, conversation_id, "帮我改一下代码")
    assert body["metadata"]["route"] == "chat"
    assert _subtasks(client, conversation_id) == []
    # 直接在项目助理对话上 /start 也被拒绝
    assert client.post(f"{PREFIX}/tasks/{conversation_id}/start", json={}).status_code == 409


def test_project_assistant_reads_sources_without_writing_them(client: TestClient) -> None:
    from flux.core.task_engine.assistant import AssistantTurn

    source = client.post(
        f"{PREFIX}/tasks", json={"description": "登录改造", "start": False}
    ).json()["data"]
    client.post(f"{PREFIX}/tasks/{source['id']}/messages", json={"content": "登录页已经改完"})
    before = _messages(client, source["id"])

    container: Container = client.app.state.container
    captured: dict = {}

    async def _fake_respond(**kwargs):  # noqa: ANN003
        captured.update(kwargs)
        return AssistantTurn(reply="登录项目已完成登录页改造。", provider="fake", model="fake")

    container.assistant.respond = _fake_respond  # type: ignore[method-assign]

    created = _create_project_assistant(client, name="登录项目", sources=[source["id"]])
    assert created.status_code == 200, created.text
    conversation_id = created.json()["data"]["conversation_task_id"]

    body = _send(client, conversation_id, "这个项目现在进度怎么样？")
    assert body["metadata"]["route"] == "chat"
    assert "登录" in body["data"]["messages"][1]["content"]
    assert captured["persona"] == "project_assistant"
    assert "登录页已经改完" in captured["context"]
    # 只读：来源对话的消息一条都没多
    assert len(_messages(client, source["id"])) == len(before)


def test_suggestions_do_not_create_anything(client: TestClient) -> None:
    client.post(f"{PREFIX}/tasks", json={"description": "已有对话一", "start": False})
    client.post(f"{PREFIX}/tasks", json={"description": "已有对话二", "start": False})

    response = client.get(f"{PREFIX}/assistants/suggestions")
    assert response.status_code == 200, response.text
    ideas = response.json()["data"]
    assert ideas
    assert client.get(f"{PREFIX}/assistants").json()["data"]["projects"] == []
