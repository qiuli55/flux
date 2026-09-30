"""Project / Project Brain API（主规格 §12.12；实施计划 ⑩⑪）。

Project Scanner（⑩）只读不写：遍历工作区目录产出画像。Project Brain（⑪）是
结构化记忆：六种分区，现状型分区覆盖写入、累积型分区只追加。

`POST /projects/{id}/scan` 会把扫描结果自动落进 Brain（Overview + Tech Stack），
`record=false` 时只返回画像、不改任何记忆。整组接口都是纯本地服务端行为，不调模型。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import MemoryWriteRequest, ProjectCreateRequest, ScanRequest

router = APIRouter(prefix="/projects", tags=["projects"])


@router.get("")
async def list_projects(container: Container = Depends(get_container)) -> dict[str, object]:
    projects = await container.brain.list_projects()
    return ok([project.to_dict() for project in projects], metadata={"count": len(projects)})


@router.post("")
async def create_project(
    payload: ProjectCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    project = await container.brain.create_project(
        name=payload.name, repository=payload.repository, meta=payload.metadata or None
    )
    return ok(project.to_dict())


@router.get("/{project_id}")
async def get_project(
    project_id: uuid.UUID, container: Container = Depends(get_container)
) -> dict[str, object]:
    project = await container.brain.get_project(project_id)
    return ok(project.to_dict())


@router.get("/{project_id}/memory")
async def get_memory(
    project_id: uuid.UUID, container: Container = Depends(get_container)
) -> dict[str, object]:
    """六个分区全部列出（缺给空列表），便于前端直接渲染。"""
    project = await container.brain.get_project(project_id)
    sections = await container.brain.sections(project.id)
    total = sum(len(entries) for entries in sections.values())
    return ok(sections, metadata={"count": total})


@router.post("/{project_id}/memory")
async def write_memory(
    project_id: uuid.UUID,
    payload: MemoryWriteRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """写记忆：现状型分区覆盖当前内容，累积型分区新增一条。"""
    project = await container.brain.get_project(project_id)
    entry = await container.brain.write(
        project.id, section=payload.section, content=payload.content, meta=payload.metadata or None
    )
    return ok(entry.to_dict())


@router.get("/{project_id}/memory/context")
async def get_context(
    project_id: uuid.UUID, container: Container = Depends(get_container)
) -> dict[str, object]:
    """拼出 Agent 执行前注入的项目上下文（Project Brain 的消费入口）。"""
    project = await container.brain.get_project(project_id)
    text = await container.brain.context(project.id)
    return ok({"text": text}, metadata={"chars": len(text)})


@router.post("/{project_id}/scan")
async def scan_project(
    project_id: uuid.UUID,
    payload: ScanRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """扫描工作区（⑩）并默认写入 Brain（⑪）；record=false 只拿画像、不落记忆。"""
    project = await container.brain.get_project(project_id)
    outcome = await container.brain.scan(
        project.id,
        workspace_root=payload.workspace_root,
        record=payload.record,
    )
    return ok(outcome.to_dict(), metadata={"truncated": outcome.profile.truncated})
