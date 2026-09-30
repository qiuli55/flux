"""DSH Run API 请求模型（集成方案 §18 Phase 1）。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class DshRunCreateRequest(BaseModel):
    """起一次 DSH Run：instruction 必填，session_id 留空则由服务端生成。"""

    instruction: str = Field(min_length=1)
    session_id: str | None = None


__all__ = ["DshRunCreateRequest"]
