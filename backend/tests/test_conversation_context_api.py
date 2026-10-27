"""上下文轮换 API：占用度量、交接摘要、新对话带摘要。

覆盖用户要求的三段行为：
1. 新建对话是全新 Task（不带旧消息）；
2. 上下文接近上限时可被度量出来（normal → warning → full）；
3. 新对话只带旧对话的摘要，摘要同时进入助手历史与 Agent 执行指令。
"""

from __future__ import annotations

import asyncio
import uuid

from flux.core.agent_runtime.runtimes.service import _execution_instruction
from flux.core.task_engine.context_usage import context_message_limit
from flux.core.task_engine.repository import MessageDraft
from flux.enums import TaskStatus
from flux.models.task import Task

PREFIX = "/api/v1"


def _create_task(client, **extra) -> dict:
    payload = {"description": "继续把登录页的报错处理补完", **extra}
    response = client.post(f"{PREFIX}/tasks", json=payload)
    assert response.status_code == 200, response.text
    return response.json()["data"]


def _seed(
    container,
    task_id: str,
    count: int,
    *,
    chars: int = 10,
    role: str = "user",
    kind: str = "text",
) -> None:
    drafts = [
        MessageDraft(role=role, kind=kind, content="上" * chars) for _ in range(count)
    ]
    asyncio.run(container.task_repo.add_messages(uuid.UUID(task_id), drafts))


def test_context_usage_recomputes_from_compressed_trace(client, container):
    """历史再多也按压缩后的轨迹计：不会因为条数到顶就永远显示 100%。

    平台每轮投喂的是「执行指令（含压缩轨迹）」，原始消息不会整段进模型。
    """
    task = _create_task(client)
    limit = context_message_limit()

    empty = client.get(f"{PREFIX}/tasks/{task['id']}/context").json()["data"]
    assert empty["level"] == "normal"
    assert empty["message_count"] == 0
    assert empty["limit"] == limit
    assert empty["used_chars"] == empty["instruction_chars"]

    _seed(container, task["id"], limit * 3, chars=2000)
    compressed = client.get(f"{PREFIX}/tasks/{task['id']}/context").json()["data"]
    assert compressed["message_count"] == limit * 3
    # 条数只作解释信息：即使已到上限，也不参与比例与等级
    assert compressed["used_messages"] == limit
    assert compressed["used_chars"] == compressed["instruction_chars"]
    assert compressed["used_chars"] < compressed["budget_chars"]
    assert compressed["level"] == "normal"
    assert compressed["percent"] < 80

    # 继续加同样体量的消息：压缩轨迹封顶，占用不应继续上涨，更不该卡在 100%
    before = compressed["used_chars"]
    _seed(container, task["id"], limit * 2, chars=2000)
    more = client.get(f"{PREFIX}/tasks/{task['id']}/context").json()["data"]
    assert more["message_count"] == limit * 5
    assert more["used_chars"] == before
    assert more["level"] == "normal"


def test_context_usage_skips_messages_outside_context(client, container):
    task = _create_task(client)
    _seed(container, task["id"], 1, chars=5000, role="assistant", kind="run_started")
    data = client.get(f"{PREFIX}/tasks/{task['id']}/context").json()["data"]
    assert data["message_count"] == 0
    # 占用按真实投喂算：即使没有历史消息，下一轮执行指令本身也占字符
    assert data["instruction_chars"] > 0
    assert data["used_chars"] == data["instruction_chars"]
    assert data["level"] == "normal"


def test_context_usage_unknown_and_invalid_task(client):
    assert client.get(f"{PREFIX}/tasks/{uuid.uuid4()}/context").status_code == 404
    # 路径里的标识非法与「任务不存在」同语义，沿用 _task_id 的 404 口径
    assert client.get(f"{PREFIX}/tasks/not-a-uuid/context").status_code == 404


def test_handoff_returns_summary_for_existing_conversation(client, container):
    task = _create_task(client)
    _seed(container, task["id"], 3)

    response = client.post(f"{PREFIX}/tasks/{task['id']}/handoff")
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["from_task_id"] == task["id"]
    assert data["summary"].strip()
    assert data["source_messages"] == 3
    assert data["provider"]


def test_handoff_rejects_running_conversation(client, container):
    task = _create_task(client)
    _seed(container, task["id"], 2)
    asyncio.run(
        container.task_repo.set_status(uuid.UUID(task["id"]), TaskStatus.RUNNING)
    )

    response = client.post(f"{PREFIX}/tasks/{task['id']}/handoff")
    assert response.status_code == 409, response.text
    assert "正在执行" in response.json()["message"]


def test_handoff_without_messages_is_rejected(client):
    task = _create_task(client)
    response = client.post(f"{PREFIX}/tasks/{task['id']}/handoff")
    assert response.status_code == 422, response.text


def test_create_task_with_summary_seeds_summary_message(client):
    source = _create_task(client)
    response = client.post(
        f"{PREFIX}/tasks",
        json={
            "description": "接着修登录页",
            "summary": "目标：修复登录报错\n已完成：定位到空指针\n待办：补测试",
            "summary_from": source["id"],
        },
    )
    assert response.status_code == 200, response.text
    created = response.json()["data"]
    assert created["id"] != source["id"]

    messages = client.get(f"{PREFIX}/tasks/{created['id']}/messages").json()["data"]
    assert len(messages) == 1
    summary = messages[0]
    assert summary["kind"] == "summary"
    assert summary["role"] == "assistant"
    assert summary["payload"]["from_task_id"] == source["id"]
    assert summary["content"].startswith("目标：修复登录报错")

    # 新对话不拷贝旧消息：只有摘要这一条
    old_messages = client.get(f"{PREFIX}/tasks/{source['id']}/messages").json()["data"]
    assert old_messages == []


def test_create_task_with_invalid_summary_from_is_bad_request(client):
    response = client.post(
        f"{PREFIX}/tasks",
        json={"description": "接着做", "summary": "前情", "summary_from": "not-a-uuid"},
    )
    assert response.status_code == 400, response.text


def test_latest_summary_returns_most_recent(container, client):
    task = _create_task(client)
    _seed(container, task["id"], 1, role="assistant", kind="summary", chars=3)
    _seed(container, task["id"], 1, role="assistant", kind="summary", chars=5)

    message = asyncio.run(
        container.task_repo.latest_message(uuid.UUID(task["id"]), kind="summary")
    )
    assert message is not None
    assert len(message.content) == 5


def test_execution_instruction_includes_summary_and_followup():
    task = Task(
        id=uuid.uuid4(),
        description="接着修登录页",
        project_id=None,
        agent_id=None,
    )
    instruction = _execution_instruction(
        task,
        None,
        [],
        summary="目标：修复登录报错\n待办：补测试",
        followup="先补一个失败用例",
    )
    assert "## 前情摘要（来自上一段对话，供你接着做）" in instruction
    assert "待办：补测试" in instruction
    assert "## 本轮任务（同一对话的后续消息，只执行这一条）" in instruction
    assert "先补一个失败用例" in instruction


def test_followup_is_primary_and_original_task_is_background():
    """续跑轮的新消息必须排在原任务之前，原任务标成背景且明确不重做。

    回归：Agent 每次 Run 都是新会话，若把最初的需求当成当前任务注入，
    它会把最开始的设计任务在每一轮里重新执行一遍。
    """
    task = Task(
        id=uuid.uuid4(),
        description="帮我设计一个记账工具的页面",
        project_id=None,
        agent_id=None,
    )
    instruction = _execution_instruction(task, None, [], followup="把主色改成蓝色")

    assert "## 本轮任务" in instruction
    assert instruction.index("把主色改成蓝色") < instruction.index("帮我设计一个记账工具的页面")
    assert "## 原任务背景" in instruction
    assert "不要重新执行" in instruction
