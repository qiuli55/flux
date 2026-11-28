"""密钥库（api_keys 表）。

存的是一条可用于 Agent 模型通道的供应商凭据：

- `provider`：deepseek / openai / openai_compatible / anthropic；
- `base_url`：空 = 用该供应商在 Flux 配置里的默认地址；
- `key_ciphertext`：`SecretBox` 信封密文（主密钥来自 FLUX_KEY_VAULT_SECRET 或
  `~/.flux/key-vault.key`）；**明文不落库、不出接口**；
- `key_hint`：末 4 位，仅供界面辨识；
- `models`：这条 key 可用的模型 id 列表，至少一项；
- `model_thinking`：每个登记模型在密钥库里定好的思考模式（类型 + 档位 + 默认档）。
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import JSON, Boolean, LargeBinary, String
from sqlalchemy.orm import Mapped, mapped_column

from flux.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class ApiKey(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "api_keys"

    name: Mapped[str] = mapped_column(String(128), unique=True, index=True)
    provider: Mapped[str] = mapped_column(String(32), index=True)
    base_url: Mapped[str] = mapped_column(String(256), default="", server_default="")
    #: codex 的 wire_api；空 = 按 provider 推导
    wire_api: Mapped[str] = mapped_column(String(16), default="", server_default="")
    models: Mapped[list[Any]] = mapped_column(JSON, default=list)
    #: 模型 id → {kind, levels, default}；创建/保存模型时必填（老数据读时补预填值）。
    model_thinking: Mapped[dict[str, Any]] = mapped_column(
        JSON, default=dict, server_default="{}"
    )
    key_ciphertext: Mapped[bytes] = mapped_column(LargeBinary)
    key_hint: Mapped[str] = mapped_column(String(16), default="", server_default="")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, server_default="1", nullable=False)

    def to_dict(self) -> dict[str, Any]:
        """对外表示**不含**密文：密钥库列表只回元数据与掩码。"""
        # 老数据没有思考模式：对外按预填规则补齐，编辑页打开时就是「已选择」状态。
        from flux.core.agent_runtime.model_catalog import thinking_spec_from_dict

        stored = self.model_thinking if isinstance(self.model_thinking, dict) else {}
        models = [str(item) for item in (self.models or []) if str(item).strip()]
        return {
            "id": str(self.id),
            "name": self.name,
            "provider": self.provider,
            "base_url": self.base_url,
            "wire_api": self.wire_api,
            "models": models,
            "model_thinking": {
                model: thinking_spec_from_dict(stored.get(model), model).to_dict()
                for model in models
            },
            "key_hint": self.key_hint,
            "enabled": self.enabled,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }


__all__ = ["ApiKey"]
