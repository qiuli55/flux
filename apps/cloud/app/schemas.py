"""请求 / 响应模型。

刻意不引 email-validator：邮箱只做形状校验，真实可用性由 OAuth / 用户自己保证。
"""
from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator

_EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[A-Za-z]{2,24}$")


def _iso_z(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.replace(microsecond=0).isoformat() + "Z"


class OrmModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------------------------------------------------------------- 账号

class RegisterIn(BaseModel):
    email: str = Field(min_length=5, max_length=255)
    password: str = Field(min_length=8, max_length=128)
    username: str | None = Field(default=None, max_length=64)
    display_name: str | None = Field(default=None, max_length=128)

    @field_validator("email")
    @classmethod
    def _check_email(cls, v: str) -> str:
        v = v.strip().lower()
        if not _EMAIL_RE.match(v):
            raise ValueError("邮箱格式不正确")
        return v

    @field_validator("username")
    @classmethod
    def _check_username(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        if not v:
            return None
        if not re.fullmatch(r"[A-Za-z0-9._-]{3,64}", v):
            raise ValueError("用户名只能包含字母、数字、点、下划线、连字符，长度 3-64")
        return v


class LoginIn(BaseModel):
    identifier: str = Field(min_length=3, max_length=255)  # 邮箱或用户名
    password: str = Field(min_length=1, max_length=128)


class PasswordChangeIn(BaseModel):
    current_password: str | None = Field(default=None, max_length=128)
    new_password: str = Field(min_length=8, max_length=128)


class DeleteAccountIn(BaseModel):
    # 二次确认：必须原样输入用户名，避免误触
    confirm_username: str = Field(min_length=1, max_length=64)
    # 已自行设过密码的用户必须验旧密码；第三方首登未改密的可留空
    password: str | None = Field(default=None, max_length=128)


class IdentityOut(OrmModel):
    provider: str
    provider_email: str | None = None
    provider_login: str | None = None
    created_at: datetime

    @field_serializer("created_at")
    def _s(self, v: datetime) -> str:
        return _iso_z(v) or ""


class UserOut(OrmModel):
    id: int
    email: str | None
    username: str
    display_name: str | None
    avatar_url: str | None
    must_change_password: bool
    created_at: datetime
    last_login_at: datetime | None = None

    @field_serializer("created_at", "last_login_at")
    def _s(self, v: datetime | None) -> str | None:
        return _iso_z(v)


class MeOut(BaseModel):
    user: UserOut
    identities: list[IdentityOut]
    providers: dict[str, bool]


class OkOut(BaseModel):
    ok: bool = True
    message: str | None = None


# ---------------------------------------------------------------- 跨站登录回跳

class HandoffOut(BaseModel):
    # 站内目标原样返回；站外目标会带上一次性 code
    redirect: str


class ExchangeIn(BaseModel):
    code: str = Field(min_length=8, max_length=256)


class RefreshIn(BaseModel):
    refresh_token: str = Field(min_length=16, max_length=256)


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int
    user: UserOut


# ---------------------------------------------------------------- 日志

class LogOut(OrmModel):
    id: int
    original_name: str
    size_bytes: int
    sha256: str
    run_started_at: datetime | None = None
    prev_run_started_at: datetime | None = None
    client_version: str | None = None
    os_info: str | None = None
    note: str | None = None
    created_at: datetime

    @field_serializer("created_at", "run_started_at", "prev_run_started_at")
    def _s(self, v: datetime | None) -> str | None:
        return _iso_z(v)


class LogListOut(BaseModel):
    items: list[LogOut]
    total: int


class HealthOut(BaseModel):
    status: str = "ok"
    app: str
    env: str
    time: str
    providers: dict[str, bool]


def as_dict(model: BaseModel) -> dict[str, Any]:
    return model.model_dump(mode="json")
