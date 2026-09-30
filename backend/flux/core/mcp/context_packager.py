"""上下文打包器（目标架构 §6.2 / §6.3，Phase 2 范围：全量 + T1）。

`context.get` 的实现：把 Flux 侧已有的工程事实（任务、项目 Brain、近期提案）
按预算拼成一段可直接投喂 agent 的文本。

Phase 2 只做**规则裁剪**（T1）：按条目整条保留、放不下就退化成引用清单。
不调模型做摘要——T2 常驻压缩器是后续 Phase 的事，先交付确定性、可复现、
且不产生任何幻觉的打包结果。
"""

from __future__ import annotations

import uuid
from typing import TYPE_CHECKING, Any

from flux.errors import ValidationError

if TYPE_CHECKING:  # pragma: no cover - 仅为类型标注，避免容器与本模块互相导入
    from flux.container import Container

#: 默认可投喂预算（字符数）。按 §6.3 的公式，真实预算应由目标模型窗口推导；
#: Phase 2 先给一个保守常量，后续接入模型档案后改成按模型算。
DEFAULT_BUDGET_CHARS = 24_000
#: 打包时最多带多少条近期提案（只带元信息，正文由 workspace.read 取）
MAX_CHANGES = 20
#: 引用清单自身的上限，避免"被裁掉的条目"反过来把预算吃光
MAX_DROPPED_REFS = 50

#: 固定写进每个包尾的采信规则（§6.4）
PROVENANCE_NOTE = (
    "本包内容全部是 Flux 侧存档的工程事实（L0–L2），历史条目为快照；"
    "断言不等于事实，动手前请用 workspace.read / workspace.diff 核对磁盘当前状态。"
)


async def package_context(
    container: Container,
    *,
    task_id: str | None = None,
    project_id: str | None = None,
    budget: int = DEFAULT_BUDGET_CHARS,
) -> dict[str, Any]:
    """打一个上下文包。task_id 为空表示只按 project_id（或全工作区）打包。"""
    if budget <= 0:
        raise ValidationError("budget 必须是正整数", details={"budget": budget})
    task_uuid = _parse_uuid(task_id, field="task_id")
    project_uuid = _parse_uuid(project_id, field="project_id")

    entries: list[dict[str, Any]] = []
    if task_uuid is not None:
        task = await container.task_repo.get(task_uuid)
        # 任务自带项目归属时自动带上该项目的 Brain，省掉调用方一次显式传参
        if project_uuid is None and task.project_id is not None:
            project_uuid = task.project_id
        entries.append(
            _entry(
                kind="task",
                title=f"任务 {task.id}",
                text="\n".join(
                    [
                        "# 当前任务（L1）",
                        f"- id：{task.id}",
                        f"- 状态：{task.status}",
                        f"- 优先级：{task.priority}",
                        f"- 项目：{task.project_id or '未关联'}",
                        "",
                        task.description,
                    ]
                ),
                ref=f"task:{task.id}",
            )
        )

    if project_uuid is not None:
        brain_text = await container.brain.context(project_uuid)
        entries.append(
            _entry(
                kind="brain",
                title=f"项目 Brain {project_uuid}",
                text=brain_text,
                ref=f"brain:{project_uuid}",
            )
        )

    changes = await container.workspace.list(task_id=task_uuid, project_id=project_uuid)
    recent = changes[-MAX_CHANGES:]
    if recent:
        lines = ["# 近期提案（L2，人审队列）"]
        for change in recent:
            lines.append(
                f"- [{change.status}] {change.file_path}"
                f"（+{change.added_lines}/-{change.removed_lines}，{change.hunks} 个改动块）"
                f" id={change.id}" + (f" 说明：{change.summary}" if change.summary else "")
            )
        entries.append(
            _entry(
                kind="proposals",
                title=f"近期提案（{len(recent)}/{len(changes)} 条）",
                text="\n".join(lines),
                ref=f"proposals:{task_uuid or project_uuid or 'workspace'}",
            )
        )

    return _apply_budget(entries, budget=budget, project_id=project_uuid, task_id=task_uuid)


def _entry(*, kind: str, title: str, text: str, ref: str) -> dict[str, Any]:
    return {"kind": kind, "title": title, "text": text, "ref": ref}


def _apply_budget(
    entries: list[dict[str, Any]],
    *,
    budget: int,
    project_id: uuid.UUID | None,
    task_id: uuid.UUID | None,
) -> dict[str, Any]:
    """整条保留；第一条就超预算时就地截断（保证调用方至少拿到东西）；其余退化成引用。"""
    kept: list[dict[str, Any]] = []
    dropped: list[str] = []
    used = 0
    for entry in entries:
        size = len(entry["text"])
        remaining = budget - used
        if size <= remaining:
            kept.append({**entry, "truncated": False})
            used += size
        elif not kept and remaining > 0:
            kept.append({**entry, "text": entry["text"][:remaining], "truncated": True})
            used += remaining
        else:
            dropped.append(entry["ref"])

    content = "\n\n".join(f"## {item['title']}\n{item['text']}" for item in kept)
    return {
        "task_id": str(task_id) if task_id else None,
        "project_id": str(project_id) if project_id else None,
        "budget": budget,
        "used": used,
        "entries": [
            {
                "kind": item["kind"],
                "title": item["title"],
                "ref": item["ref"],
                "chars": len(item["text"]),
                "truncated": item["truncated"],
            }
            for item in kept
        ],
        "dropped_refs": dropped[:MAX_DROPPED_REFS],
        "dropped_count": len(dropped),
        "content": content,
        "note": PROVENANCE_NOTE,
    }


def _parse_uuid(value: str | None, *, field: str) -> uuid.UUID | None:
    if value is None or not str(value).strip():
        return None
    try:
        return uuid.UUID(str(value).strip())
    except ValueError as exc:
        raise ValidationError(
            f"{field} 必须是合法 UUID：{value}", details={field: str(value)}
        ) from exc
