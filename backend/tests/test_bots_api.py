"""Bot 模式 API 测试（2026-11-05）。

口径：
- Bot 与对话共用执行链路：每次开工落一条 `tasks` 记录（`tasks.bot_id` + `workspace` 快照）；
- Bot 任务默认不进首页对话列表，`/tasks?bot_id=` 才能单独取；
- 同一 Bot 同一时刻只允许一轮（上一轮未终态时手动触发 409）；
- 定时靠落库的 `next_run_at`：`tick()` 取到期 Bot 开工，并推进下一次触发时间。

真实 Run 一律替换成空实现：本文件只验证配置面与调度契约，不启动 runtime、不调模型。
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from flux.container import Container
from flux.core.agent_runtime.runtimes.service import TaskRunService

PREFIX = "/api/v1"

_BOT_KEYS = {
    "id",
    "name",
    "description",
    "avatar_shape",
    "avatar_color",
    "agent_id",
    "model",
    "permission_mode",
    "decision_mode",
    "workspace",
    "workspace_path",
    "status",
    "schedule",
    "next_run_at",
    "last_run_at",
    "last_task_id",
    "created_at",
    "updated_at",
}


@pytest.fixture(autouse=True)
def quiet_runs(monkeypatch: pytest.MonkeyPatch) -> None:
    """起 Run 换成空实现：任务留在 pending，正好用来验证「忙」与「不重复开工」。"""

    async def _noop(self: TaskRunService, task: object, *args: object, **kwargs: object) -> None:
        return None

    monkeypatch.setattr(TaskRunService, "start", _noop)


def _create(client: TestClient, **overrides: object) -> dict:
    payload: dict[str, object] = {"name": "巡检", "description": "检查服务健康"}
    payload.update(overrides)
    response = client.post(f"{PREFIX}/bots", json=payload)
    assert response.status_code == 200, response.text
    return response.json()["data"]


def test_create_bot_defaults(client: TestClient) -> None:
    """默认：active、无人值守、无定时、工作目录按 <dsh_home>/bots/<id>/workspace 派生。"""
    bot = _create(client)
    assert set(bot) == _BOT_KEYS
    assert bot["status"] == "active"
    assert bot["permission_mode"] == "unattended"
    assert bot["decision_mode"] == "auto"
    assert bot["schedule"] is None
    assert bot["next_run_at"] is None
    assert bot["workspace_path"].endswith(f"/bots/{bot['id']}/workspace")


def test_create_bot_with_schedule_sets_next_run(client: TestClient) -> None:
    bot = _create(client, schedule="*/15 * * * *")
    assert bot["schedule"] == "*/15 * * * *"
    assert bot["next_run_at"] is not None


def test_create_bot_rejects_bad_schedule(client: TestClient) -> None:
    response = client.post(f"{PREFIX}/bots", json={"name": "坏定时", "schedule": "not a cron"})
    assert response.status_code == 400


def test_create_bot_rejects_relative_workspace(client: TestClient) -> None:
    response = client.post(
        f"{PREFIX}/bots", json={"name": "相对目录", "workspace": "relative/path"}
    )
    assert response.status_code == 400


def test_create_bot_unknown_agent_is_404(client: TestClient) -> None:
    response = client.post(f"{PREFIX}/bots", json={"name": "野 Agent", "agent_id": "no-such-agent"})
    assert response.status_code == 404


def test_patch_pause_clears_next_run(client: TestClient) -> None:
    bot = _create(client, schedule="0 * * * *")
    paused = client.patch(f"{PREFIX}/bots/{bot['id']}", json={"status": "paused"})
    assert paused.status_code == 200, paused.text
    data = paused.json()["data"]
    assert data["status"] == "paused"
    assert data["next_run_at"] is None
    # 恢复后按 cron 重新算出下一次触发
    resumed = client.patch(f"{PREFIX}/bots/{bot['id']}", json={"status": "active"})
    assert resumed.json()["data"]["next_run_at"] is not None


def test_run_now_creates_bot_task_hidden_from_chat_list(client: TestClient) -> None:
    bot = _create(client, workspace="/tmp/flux-bot-test")
    started = client.post(f"{PREFIX}/bots/{bot['id']}/run")
    assert started.status_code == 200, started.text
    payload = started.json()["data"]
    task = payload["task"]
    assert task["bot_id"] == bot["id"]
    assert task["bot_name"] == bot["name"]
    assert payload["bot"]["last_task_id"] == task["id"]

    # 首页对话列表默认不含 Bot 任务
    chat_tasks = client.get(f"{PREFIX}/tasks").json()["data"]
    assert all(item["bot_id"] is None for item in chat_tasks)

    # 按 bot_id 过滤能取到，且带上 workspace 快照
    bot_tasks = client.get(f"{PREFIX}/tasks", params={"bot_id": bot["id"]}).json()["data"]
    assert [item["id"] for item in bot_tasks] == [task["id"]]
    listed = client.get(f"{PREFIX}/bots/{bot['id']}/tasks").json()["data"]
    assert [item["id"] for item in listed] == [task["id"]]


def test_run_now_conflicts_while_previous_round_running(client: TestClient) -> None:
    bot = _create(client)
    assert client.post(f"{PREFIX}/bots/{bot['id']}/run").status_code == 200
    conflict = client.post(f"{PREFIX}/bots/{bot['id']}/run")
    assert conflict.status_code == 409


def test_run_now_requires_instruction_or_description(client: TestClient) -> None:
    bot = _create(client, description="")
    response = client.post(f"{PREFIX}/bots/{bot['id']}/run")
    assert response.status_code == 400


def test_list_filter_and_delete(client: TestClient) -> None:
    kept = _create(client, name="保留")
    removed = _create(client, name="删除")
    client.patch(f"{PREFIX}/bots/{removed['id']}", json={"status": "paused"})

    paused = client.get(f"{PREFIX}/bots", params={"status": "paused"}).json()["data"]
    assert [bot["id"] for bot in paused] == [removed["id"]]
    assert client.get(f"{PREFIX}/bots", params={"status": "bogus"}).status_code == 400

    assert client.delete(f"{PREFIX}/bots/{removed['id']}").status_code == 200
    assert client.get(f"{PREFIX}/bots/{removed['id']}").status_code == 404
    assert client.get(f"{PREFIX}/bots/{kept['id']}").status_code == 200


def test_tick_launches_due_bot_and_advances_schedule(container: Container) -> None:
    """到期的 Bot 由 tick 开工，且 next_run_at 立刻推进到下一个触发点。"""

    async def _scenario() -> None:
        bot = await container.bots.create(
            name="定时巡检", description="检查磁盘", schedule="*/10 * * * *"
        )
        first_due = bot.next_run_at
        # 往前推到一天后：一定已经到期
        later = datetime.now(timezone.utc) + timedelta(days=1)
        started = await container.bots.tick(later)
        refreshed = await container.bots.get(bot.id)
        assert started == [str(refreshed.last_task_id)]
        assert refreshed.last_task_id is not None
        assert refreshed.next_run_at is not None
        assert refreshed.next_run_at > first_due

        # 上一轮还是 pending（替身不起 Run）：再扫一次必须跳过，不能重复开工
        again = await container.bots.tick(later + timedelta(minutes=5))
        assert again == []

    asyncio.run(_scenario())
