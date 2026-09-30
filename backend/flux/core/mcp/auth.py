"""MCP 鉴权：Agent 接入令牌的签发与校验（目标架构 §3.2）。

三条硬规则，任何一条被放松都等于开了后门：

1. **明文只出现一次**：`fxt_` + 32 字节随机 hex，签名时返回给调用方，库里只存 sha256；
2. **身份不可自报**：`agent_id` 与 `scopes` 一律来自令牌记录，请求体里同名字段无效
   （写入类工具的 provenance 由服务端盖章）；
3. **现查现判**：每次调用都查库，`revoked_at` 非空立即失效，不做进程内缓存——
   撤销要"下一个请求就生效"，而不是重启后才生效。
"""

from __future__ import annotations

import hashlib
import secrets
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.enums import Capability
from flux.errors import AuthenticationError, NotFoundError, ValidationError
from flux.logging import get_logger
from flux.models.agent_token import AgentToken

logger = get_logger(__name__)

TOKEN_PREFIX = "fxt_"
#: 令牌随机部分的字节数（hex 编码后为 64 个字符）
TOKEN_SECRET_BYTES = 32
#: 永不可授予的能力（目标架构 §3.5）：密钥访问不进任何令牌的 scopes
FORBIDDEN_SCOPES: frozenset[Capability] = frozenset({Capability.SECRET_ACCESS})


def hash_token(raw: str) -> str:
    """令牌的存储形态：sha256 十六进制。"""
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def parse_scopes(values: Iterable[Capability | str]) -> tuple[Capability, ...]:
    """把入参规整成能力集合；未知能力与硬禁用能力一律报错（不静默丢弃）。"""
    parsed: list[Capability] = []
    for value in values:
        try:
            capability = value if isinstance(value, Capability) else Capability(str(value))
        except ValueError as exc:
            raise ValidationError(
                f"未知的能力标识：{value}",
                details={"scope": str(value), "known": sorted(str(c) for c in Capability)},
            ) from exc
        if capability in FORBIDDEN_SCOPES:
            raise ValidationError(
                f"能力 {capability} 不允许授予任何 Agent 令牌（目标架构 §3.5 硬禁令）",
                details={"scope": str(capability)},
            )
        if capability not in parsed:
            parsed.append(capability)
    return tuple(parsed)


@dataclass(frozen=True)
class AgentIdentity:
    """一次 MCP 调用的调用方身份——每个字段都来自令牌记录，不来自请求体。"""

    agent_id: str
    token_id: str
    scopes: frozenset[Capability]

    def has(self, capability: Capability) -> bool:
        return capability in self.scopes


class AgentTokenService:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def issue(
        self,
        *,
        agent_id: str,
        scopes: Iterable[Capability | str],
        label: str = "",
    ) -> tuple[AgentToken, str]:
        """签发一枚令牌，返回 (记录, 明文)。明文只在这里出现一次，调用方必须立即交出去。"""
        name = (agent_id or "").strip()
        if not name:
            raise ValidationError("agent_id 不能为空", details={"agent_id": agent_id})
        granted = parse_scopes(scopes)
        raw = f"{TOKEN_PREFIX}{secrets.token_hex(TOKEN_SECRET_BYTES)}"
        token = AgentToken(
            agent_id=name,
            label=label.strip(),
            token_hash=hash_token(raw),
            scopes=[str(scope) for scope in granted],
        )
        async with self._session_factory() as session:
            session.add(token)
            await session.commit()
        # 日志只记身份与能力，不记明文也不记哈希
        logger.info("agent_token.issue agent=%s scopes=%s", name, [str(s) for s in granted])
        return token, raw

    async def list(self, *, agent_id: str | None = None) -> list[AgentToken]:
        statement = select(AgentToken).order_by(AgentToken.created_at, AgentToken.id)
        if agent_id:
            statement = statement.where(AgentToken.agent_id == agent_id)
        async with self._session_factory() as session:
            return list(await session.scalars(statement))

    async def revoke(self, token_id: str | uuid.UUID) -> AgentToken:
        key = self._as_uuid(token_id)
        async with self._session_factory() as session:
            token = await session.get(AgentToken, key)
            if token is None:
                raise NotFoundError(f"令牌 {token_id} 不存在", details={"token_id": str(token_id)})
            if token.revoked_at is None:
                token.revoked_at = datetime.now(timezone.utc)
            await session.commit()
        logger.info("agent_token.revoke id=%s agent=%s", token_id, token.agent_id)
        return token

    async def authenticate(self, raw: str | None) -> AgentIdentity:
        """校验明文令牌；任何不通过都抛 AuthenticationError（401，fail-closed）。"""
        if not raw or not raw.strip():
            raise AuthenticationError("缺少 Agent 接入令牌")
        candidate = raw.strip()
        if not candidate.startswith(TOKEN_PREFIX):
            raise AuthenticationError("Agent 接入令牌格式非法")
        digest = hash_token(candidate)
        async with self._session_factory() as session:
            token = await session.scalar(select(AgentToken).where(AgentToken.token_hash == digest))
        if token is None or token.revoked_at is not None:
            raise AuthenticationError("Agent 接入令牌无效或已撤销")
        return AgentIdentity(
            agent_id=token.agent_id,
            token_id=str(token.id),
            scopes=frozenset(_known_scopes(token.scopes)),
        )

    async def identity_for(self, agent_id: str) -> AgentIdentity | None:
        """按 agent_id 取一枚未撤销令牌的身份，供进程内组件（如内置 agent 启动器）复用。

        找不到时返回 None，由调用方决定报错方式——这里不兜底造一份身份。
        """
        async with self._session_factory() as session:
            token = await session.scalar(
                select(AgentToken)
                .where(AgentToken.agent_id == agent_id, AgentToken.revoked_at.is_(None))
                .order_by(AgentToken.created_at)
            )
        if token is None:
            return None
        return AgentIdentity(
            agent_id=token.agent_id,
            token_id=str(token.id),
            scopes=frozenset(_known_scopes(token.scopes)),
        )

    @staticmethod
    def _as_uuid(value: str | uuid.UUID) -> uuid.UUID:
        if isinstance(value, uuid.UUID):
            return value
        try:
            return uuid.UUID(str(value))
        except ValueError as exc:
            raise NotFoundError(f"令牌标识非法：{value}", details={"token_id": str(value)}) from exc


def _known_scopes(values: Iterable[object]) -> list[Capability]:
    """把库里存的字符串还原成能力集合。

    遇到已废弃/不认识的能力标识只跳过——它代表的权限本就不存在，
    跳过等于不给权限，符合 fail-closed；报错反而会让整枚令牌不可用。
    """
    known: list[Capability] = []
    for value in values or []:
        try:
            capability = value if isinstance(value, Capability) else Capability(str(value))
        except ValueError:
            logger.warning("令牌里存在未知能力标识，已忽略：%s", value)
            continue
        if capability in FORBIDDEN_SCOPES:
            logger.error("令牌里出现硬禁用能力，已忽略：%s", capability)
            continue
        known.append(capability)
    return known
