"""密钥库 API（API Key Vault）。

只做管理面：列表 / 创建 / 修改 / 删除。明文 API Key 只进请求体，落库即加密；
所有响应都只回元数据与末 4 位掩码，绝不出密文/明文。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.schemas.api import ApiKeyCreateRequest, ApiKeyUpdateRequest

router = APIRouter(prefix="/keys", tags=["keys"])


@router.get("")
async def list_keys(container: Container = Depends(get_container)) -> dict[str, object]:
    """列出密钥库条目（按创建时间）；响应只含掩码与元数据。"""
    items = await container.key_vault.list_public()
    return ok(items, metadata={"count": len(items)})


@router.post("")
async def create_key(
    payload: ApiKeyCreateRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """登记一条 API Key；同一名称只能有一条。"""
    row = await container.key_vault.create(
        name=payload.name,
        provider=payload.provider,
        base_url=payload.base_url,
        api_key=payload.api_key,
        models=payload.models,
        wire_api=payload.wire_api,
        enabled=payload.enabled,
    )
    return ok(row.to_dict())


@router.patch("/{key_id}")
async def update_key(
    key_id: str,
    payload: ApiKeyUpdateRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """修改密钥库条目；未出现的字段保持不变，`api_key` 留空也表示不改。"""
    data = payload.model_dump(exclude_unset=True)
    if not str(data.get("api_key") or "").strip():
        data.pop("api_key", None)
    if data.get("enabled") is None:
        data.pop("enabled", None)
    row = await container.key_vault.update(key_id, **data)
    return ok(row.to_dict())


@router.delete("/{key_id}")
async def delete_key(
    key_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """删除密钥库条目；引用它的任务会回到 Agent 默认模型（fail-safe）。"""
    await container.key_vault.delete(key_id)
    return ok({"deleted": True, "key_id": key_id})


__all__ = ["router"]
