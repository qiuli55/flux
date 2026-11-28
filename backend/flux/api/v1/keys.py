"""密钥库 API（API Key Vault）。

管理面：列表 / 创建 / 修改 / 删除 / 连通性探测。明文 API Key 只进请求体，落库即加密；
所有响应都只回元数据与末 4 位掩码，绝不出密文/明文。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.core.agent_runtime.model_catalog import default_thinking_spec
from flux.core.key_vault.probe import probe_vault_key
from flux.schemas.api import (
    ApiKeyCreateRequest,
    ApiKeyUpdateRequest,
    ThinkingDefaultsRequest,
)

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
        model_thinking={
            model: spec.model_dump()
            for model, spec in payload.model_thinking.items()
        },
        wire_api=payload.wire_api,
        enabled=payload.enabled,
    )
    return ok(row.to_dict())


@router.post("/thinking-defaults")
async def thinking_defaults(payload: ThinkingDefaultsRequest) -> dict[str, object]:
    """按官方 / 公共目录预填思考模式；对不上时用「无思考 + 5 档」。

    只读纯函数，不向任何模型端点发请求。
    """
    items = {
        model: default_thinking_spec(model).to_dict()
        for model in payload.models
        if str(model).strip()
    }
    return ok(items, metadata={"count": len(items)})


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
    if "model_thinking" in data and data["model_thinking"] is not None:
        data["model_thinking"] = {
            model: dict(spec or {})
            for model, spec in data["model_thinking"].items()
        }
    elif data.get("model_thinking") is None:
        data.pop("model_thinking", None)
    row = await container.key_vault.update(key_id, **data)
    return ok(row.to_dict())


@router.post("/{key_id}/probe")
async def probe_key(
    key_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """用该 key 登记的模型与端点真实发起一次最小请求，校验是否可用。

    保存动作本身不触网；由调用方在保存后显式触发，结果只回状态与耗时，不回密钥。
    """
    result = await probe_vault_key(container.key_vault, key_id)
    return ok(result)


@router.delete("/{key_id}")
async def delete_key(
    key_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """删除密钥库条目；引用它的任务会回到 Agent 默认模型（fail-safe）。"""
    await container.key_vault.delete(key_id)
    return ok({"deleted": True, "key_id": key_id})


__all__ = ["router"]
