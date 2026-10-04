"""三层长期记忆 API（批次② §4.2 / §4.3）。

- User Memory：跨 Workspace，用户确认后经本组接口写入，可查可删；
- Environment Memory：全局平台运行规则，由平台代码维护——本组接口只读；
- Project Memory：底座是 Project Brain，读写走 `/projects/{id}/memory`，本组接口只读。

Agent 侧没有任何记忆直写通道（MCP 面只有只读的 `memory.recall`）；所有写入先过
密钥红线（密钥/令牌/凭证一律拒收），再按各层容量与 TTL 策略落库。
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Query

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.enums import MemoryLayer
from flux.schemas.api import UserMemoryWriteRequest

router = APIRouter(prefix="/memory", tags=["memory"])


@router.get("")
async def recall_memory(
    project_id: uuid.UUID | None = Query(
        default=None, description="项目 UUID；带上时在两层之上拼入该项目的 Project Memory"
    ),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """三层统一读取口：Environment → User →（可选）Project，按优先级排序并附来源。"""
    data = await container.memory.recall(project_id=project_id)
    return ok(data, metadata={"layers": len(data["layers"])})


@router.get("/entries")
async def list_memory_entries(
    layer: MemoryLayer | None = Query(
        default=None, description="按层过滤；缺省返回 Environment + User 两层"
    ),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出未过期条目。Project 层请走 `GET /projects/{project_id}/memory`。"""
    entries = await container.memory.list_entries(layer=layer)
    return ok([entry.to_dict() for entry in entries], metadata={"count": len(entries)})


@router.post("/entries")
async def write_memory_entry(
    payload: UserMemoryWriteRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """写一条 User Memory（跨 Workspace）。敏感信息（密钥/令牌/凭证）一律拒收。"""
    entry = await container.memory.write_user(
        content=payload.content, source=payload.source, meta=payload.metadata or None
    )
    return ok(entry.to_dict())


@router.delete("/entries/{entry_id}")
async def delete_memory_entry(
    entry_id: uuid.UUID, container: Container = Depends(get_container)
) -> dict[str, object]:
    """删除一条 User Memory；Environment / Project 层不可经此删除。"""
    await container.memory.delete(entry_id)
    return ok({"deleted": str(entry_id)})
