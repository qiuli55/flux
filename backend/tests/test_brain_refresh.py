"""Apply 成功后项目 Brain 自动刷新测试（上下文闭环）。

验证三条边界：只看 applied、同项目节流、失败不阻塞且不把失败记成"刚扫过"。
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

from flux.core.event.bus import EventBus, Events
from flux.core.project_brain.refresh import BrainRefresher


class _Clock:
    """可控单调时钟。"""

    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


class _Brain:
    """记录扫描调用；`fail` 打开后模拟扫描异常。"""

    def __init__(self) -> None:
        self.scans: list[str] = []
        self.fail = False

    async def scan(self, project_id, *, record: bool = True):
        if self.fail:
            raise RuntimeError("scan boom")
        self.scans.append(str(project_id))
        return SimpleNamespace(profile=SimpleNamespace(files_scanned=1))


class _Workspace:
    def __init__(self, *, project_id, task_id=None) -> None:
        self._change = SimpleNamespace(project_id=project_id, task_id=task_id)

    async def get(self, change_id: str):
        return self._change


class _Tasks:
    def __init__(self, project_id) -> None:
        self._project_id = project_id

    async def get(self, task_id):
        return SimpleNamespace(project_id=self._project_id)


def _build(*, brain: _Brain, project_id, workspace: _Workspace, clock: _Clock):
    bus = EventBus()
    refresher = BrainRefresher(
        brain=brain,
        tasks=_Tasks(project_id),
        workspace=workspace,
        bus=bus,
        interval_seconds=300,
        clock=clock,
    )
    refresher.attach()
    return bus, refresher


async def test_applied_change_triggers_one_scan_per_project_window() -> None:
    """applied 触发扫描；同项目 300 秒内再 applied 只算一次。"""
    clock, brain, project = _Clock(), _Brain(), uuid.uuid4()
    bus, _ = _build(
        brain=brain, project_id=project, workspace=_Workspace(project_id=project), clock=clock
    )

    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c1", "to": "applied"})
    assert brain.scans == [str(project)]

    clock.now += 10
    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c2", "to": "applied"})
    assert len(brain.scans) == 1

    clock.now += 400
    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c3", "to": "applied"})
    assert len(brain.scans) == 2


async def test_non_applied_change_is_ignored() -> None:
    """只有落盘的提案才刷新；accepted / pending 等状态不触发扫描。"""
    clock, brain, project = _Clock(), _Brain(), uuid.uuid4()
    bus, _ = _build(
        brain=brain, project_id=project, workspace=_Workspace(project_id=project), clock=clock
    )

    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c1", "to": "accepted"})
    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c2", "to": "pending"})
    assert brain.scans == []


async def test_change_without_project_is_skipped() -> None:
    """提案与任务都没有项目归属时跳过：Brain 按项目存，猜一个项目比不刷新更糟。"""
    clock, brain = _Clock(), _Brain()
    bus, refresher = _build(
        brain=brain,
        project_id=None,
        workspace=_Workspace(project_id=None, task_id=uuid.uuid4()),
        clock=clock,
    )

    await refresher.on_change(Events.WORKSPACE_CHANGED, {"change_id": "c1", "to": "applied"})
    assert brain.scans == []
    assert bus.history == []


async def test_scan_failure_is_swallowed_and_retried_on_the_next_change() -> None:
    """扫描失败只记日志：不抛给 Apply，也不占用节流窗口（下一次仍会重试）。"""
    clock, brain, project = _Clock(), _Brain(), uuid.uuid4()
    bus, _ = _build(
        brain=brain, project_id=project, workspace=_Workspace(project_id=project), clock=clock
    )

    brain.fail = True
    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c1", "to": "applied"})
    assert brain.scans == []

    brain.fail = False
    clock.now += 1
    await bus.publish(Events.WORKSPACE_CHANGED, {"change_id": "c2", "to": "applied"})
    assert brain.scans == [str(project)]
