"""入站脱敏与加密映射表（FLUX_CONTEXT_MECHANISM §10.1）。

原文全存 Flux 一处，好处是单一真相源，代价是**爆炸半径 = 全部**；而工具输出里夹带
token / key / 连接串 / 内网地址是常态（`env`、`curl -v`、日志、`.npmrc`）。故存档前
先过一层打码：正则 + 熵检测，**零模型依赖**（§13-7：打码不得依赖模型）。

三条契约：

1. **打码是纯函数**（`redact`）：给定文本，返回打码后的文本与一份"占位符 → 原文"清单；
2. **留痕单独加密**（`RedactionStore` 走 `SecretBox`）：换下来的原文进映射表，且单独加密
   ——否则它跟着打码后的正文一起裸奔，打码就白做了；
3. **随会话删除**（§8.2）：映射表本身敏感，删会话时必须一并清掉，故带 `run_id` / `turn_id`。

要点：

- 打码是**单向**的：宁少还原，不多泄露。故熵阈值刻意定在 4.5（纯 hex 上限才 4.0），
  自家 sha256 / 事实脊柱凭证一律不被误伤，而随机 base64（约 5–6）稳稳越线。
- 无密钥（`FLUX_REDACTION_KEY` 留空）时 **fail-closed**：正文照常打码，只是映射**不落库**
  ——最坏后果是"查不到原文"，而不是"明文躺在库里"。
- 占位符只在 **turn 内**唯一，故 `resolve` 必须带 `turn_id`。

入站契约（本文档即调用方要照做的顺序）：``result = redact(text)`` →
``content_store.put(result.text.encode())`` → ``redaction_store.record(...)`` → step 落库。
"""

from __future__ import annotations

import base64
import binascii
import json
import math
import re
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from flux.core.context.secretbox import SecretBox
from flux.enums import RedactionKind
from flux.models.context import ContextRedaction

#: 首个占位符（同类首见）：`[REDACTED:key]`
_PLACEHOLDER = "[REDACTED:{kind}]"
#: 后续占位符（同类第 N 个）：`[REDACTED:key:2]`
_PLACEHOLDER_N = "[REDACTED:{kind}:{index}]"
#: 短于此长度的候选一律不当密钥（降低误伤）
_MIN_SECRET_CHARS = 32
#: 香农熵阈值（bit/char）。纯 hex 上限 4.0 → 不误伤自家 sha256；随机 base64 约 5–6 → 命中
_MIN_ENTROPY = 4.5
#: 十六进制字符集：整串都是它 → 是哈希/摘要，不是密钥
_HEX_CHARS = frozenset("0123456789abcdefABCDEF")

#: `sk-…` 一类的带前缀密钥
_KEY_RE = re.compile(r"\bsk-[A-Za-z0-9_\-]{8,}\b")
#: PEM 私钥 / 证书块（跨行，DOTALL 让 `.` 也吃换行）
_PEM_RE = re.compile(r"-----BEGIN [A-Z0-9 ]+-----.*?-----END [A-Z0-9 ]+-----", re.DOTALL)
#: `Bearer …` 一类令牌（含前缀整体替换）。大小写不敏感：模型也会输出小写 `bearer`，
#: 只认大写会让句中的小写令牌漏出；是否算令牌由 `_is_bearer_token_match` 再判定。
_TOKEN_RE = re.compile(
    r"\b(?P<scheme>bearer)\s+(?P<token>[A-Za-z0-9._\-+/=]{8,})",
    re.IGNORECASE,
)
#: 判断"纯小写片段像英语词还是像令牌"时使用的元音集合（不含 y：`tokenization` 里的
#: y 不改变结论，而随机串往往连元音都不成比例）。
_ENGLISH_VOWELS = frozenset("aeiou")
#: 无 `Bearer ` 前缀的 JWT：`header.payload.signature`（base64url 三段，点分隔）。
#: 三段都要求 ≥ 8 字符，先挡掉 `a.b.c` / 语义版本号 / 普通带点文件名；命中后再校验
#: header 是否真是 JSON（见 `_looks_like_jwt`），不做"看起来像就换"的猜测。
_JWT_RE = re.compile(r"\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")
#: `user:pass@host`：只打码认证段，保留 `@host`
_CRED_RE = re.compile(r"(?P<cred>[A-Za-z0-9._%+\-]+:[^\s:@/]+)(?P<host>@[A-Za-z0-9.\-]+(?::\d+)?)")
#: 熵检测候选：无前缀的长 token（hex / base64 / urlsafe）
_CANDIDATE_RE = re.compile(r"[A-Za-z0-9+/=_\-]{32,}")


@dataclass(frozen=True)
class RedactionMatch:
    """一次替换：占位符、类别、被换下来的**原文**（进加密映射表的那份）。"""

    placeholder: str
    kind: RedactionKind
    original: str


@dataclass(frozen=True)
class RedactionResult:
    """打码结果：替换后的文本 + 全部替换清单（顺序即发现顺序）。"""

    text: str
    matches: tuple[RedactionMatch, ...] = field(default=())

    @property
    def changed(self) -> bool:
        return bool(self.matches)


def redact(text: str, *, literals: Sequence[str] | None = None) -> RedactionResult:
    """把文本里的密钥 / 令牌 / 凭证打码成占位符，返回结果与替换清单（§10.1）。

    替换顺序即优先级：KEY → PEM → TOKEN →（无 Bearer 的）JWT → CRED → 熵检测兜底 →
    配置字面量。同一类别里的**同一原文**复用同一占位符（映射因此始终 1:1）；不同原文
    按类递增编号。

    `literals` 是**可控字面量清单**（如 `FLUX_REDACTION_LITERALS`）：没有任何前缀、
    熵又不够高的短密钥只能靠它精确命中（issue #4 的 `TEST_SECRET_12345`）。为空时不启用
    ——纯函数不隐式读全局配置，调用方要么显式传，要么走 `redact_for_settings`。
    """
    matches: list[RedactionMatch] = []
    seen: dict[RedactionKind, dict[str, str]] = {}

    def placeholder_for(kind: RedactionKind, original: str) -> str:
        bucket = seen.setdefault(kind, {})
        existing = bucket.get(original)
        if existing is not None:
            return existing
        index = len(bucket) + 1
        placeholder = (
            _PLACEHOLDER.format(kind=kind.value)
            if index == 1
            else _PLACEHOLDER_N.format(kind=kind.value, index=index)
        )
        bucket[original] = placeholder
        matches.append(RedactionMatch(placeholder=placeholder, kind=kind, original=original))
        return placeholder

    def whole(kind: RedactionKind) -> Callable[[re.Match[str]], str]:
        def _sub(match: re.Match[str]) -> str:
            return placeholder_for(kind, match.group(0))

        return _sub

    def _cred_sub(match: re.Match[str]) -> str:
        placeholder = placeholder_for(RedactionKind.CRED, match.group("cred"))
        return f"{placeholder}{match.group('host')}"

    def _entropy_sub(match: re.Match[str]) -> str:
        token = match.group(0)
        if not _looks_like_secret(token):
            return token
        # 无前缀的密钥归到 KEY（"key"是兜底的密钥标记）
        return placeholder_for(RedactionKind.KEY, token)

    def _jwt_sub(match: re.Match[str]) -> str:
        token = match.group(0)
        if not _looks_like_jwt(token):
            return token
        return placeholder_for(RedactionKind.TOKEN, token)

    def _token_sub(match: re.Match[str]) -> str:
        if not _is_bearer_token_match(match):
            # 普通英语短语（`bearer tokenization`）：原样保留，位置与长度都不变，
            # 后续 JWT / 熵检测规则也不会再把它当成令牌。
            return match.group(0)
        return placeholder_for(RedactionKind.TOKEN, match.group(0))

    text = _KEY_RE.sub(whole(RedactionKind.KEY), text)
    text = _PEM_RE.sub(whole(RedactionKind.PEM), text)
    # Bearer 先于裸 JWT：既保持"连 `Bearer ` 一起换掉"的既有占位符约定，
    # 也避免先换 JWT 后留下一个没有前缀归属的 `Bearer [REDACTED:token]`。
    text = _TOKEN_RE.sub(_token_sub, text)
    text = _JWT_RE.sub(_jwt_sub, text)
    text = _CRED_RE.sub(_cred_sub, text)
    text = _CANDIDATE_RE.sub(_entropy_sub, text)
    # 可控字面量最后应用：前缀密钥 / 连接串已先按更精确的规则处理；
    # 剩下的无前缀短密钥才由清单兜底。长字面量优先，避免 "abc" 先把 "abcd" 拆坏。
    for literal in _ordered_literals(literals):
        if literal in text:
            text = text.replace(literal, placeholder_for(RedactionKind.KEY, literal))
    return RedactionResult(text=text, matches=tuple(matches))


def redact_for_settings(text: str, settings: object) -> RedactionResult:
    """按进程配置打码（`Settings.redaction_literal_list`）；便于调用方不重复传参。

    纯函数 `redact` 本身不读取全局配置；这一层薄封装把"配置 → 字面量清单"的绑定
    留在装配边界，单测既可显式注入 `literals`，也可构造 `Settings` 验证配置入口。
    """
    literals = getattr(settings, "redaction_literal_list", ())
    return redact(text, literals=literals)


def redact_then_clip(text: str, limit: int, *, literals: Sequence[str] | None = None) -> str:
    """先对**完整原文**打码，再取尾部 ``limit`` 个字符。

    直接 ``redact(text[-limit:], literals=...)`` 会让跨截断边界的字面量只剩片段：
    精确清单匹配不到，片段就会原样漏出（验收探针的 ``ITERAL_SMOKE_XYZ``）。
    这里先打码，占位符整体落进窗口，再按调用方原有语义取尾。
    """
    if limit <= 0:
        return ""
    return redact(text, literals=literals).text[-limit:]


def redact_bearer_tokens(text: str) -> str:
    """只替换 `Bearer` / `bearer` 令牌，供"不做全量打码"的落库路径复用。

    与 `redact` 的 TOKEN 规则同一判定：大写 `Bearer` 一律替换（既有行为不削弱）；
    小写 `bearer` 只在后续片段像令牌时替换，`bearer tokenization` 这类正文保留。
    不跑熵检测、不替换配置字面量——库里的完整字面量 / 其它密钥仍按既有约定保留。
    """
    return _TOKEN_RE.sub(
        lambda match: (
            match.group(0)
            if not _is_bearer_token_match(match)
            else _PLACEHOLDER.format(kind=RedactionKind.TOKEN.value)
        ),
        text,
    )


def _is_bearer_token_match(match: re.Match[str]) -> bool:
    """`Bearer` 命中是否该当令牌换掉。

    大写 `Bearer` 维持原口径：其后缀一律打码。其余大小写（典型是小写 `bearer`）
    才启用"像令牌 / 像普通英语词"的判别，避免把 `bearer tokenization` 误伤。
    """
    if match.group("scheme") == "Bearer":
        return True
    return not _looks_like_plain_english(match.group("token"))


def _looks_like_plain_english(token: str) -> bool:
    """纯小写字母片段是否更像普通英语词（`tokenization`）而不是随机令牌。

    零词典依赖：英语词的元音比例通常不低于 1/5，且不会出现长辅音簇；随机令牌
    （以及高辅音虚构串）往往相反。含数字 / 大写 / 符号的片段不在此列——它们已经
    是明确的令牌形态，调用方会直接打码。
    """
    if not token.isalpha() or not token.islower():
        return False
    vowels = sum(1 for char in token if char in _ENGLISH_VOWELS)
    if vowels == 0 or vowels / len(token) < 0.2:
        return False
    longest_run = run = 0
    for char in token:
        if char in _ENGLISH_VOWELS:
            run = 0
            continue
        run += 1
        longest_run = max(longest_run, run)
    return longest_run < 5


def _ordered_literals(literals: Sequence[str] | None) -> list[str]:
    """去空、去重、按长度倒序——长字面量先替换，短字面量不会把它拆成碎片。"""
    unique = {literal for literal in literals or () if literal}
    return sorted(unique, key=len, reverse=True)


def _looks_like_jwt(token: str) -> bool:
    """三段 base64url，且 header 能解出带 `alg` 的 JSON 才算 JWT。

    只靠正则会把"恰好三个长段、点分隔"的字符串（某些文件名 / 构建号）误伤；
    解一次 header 是最小代价的语义确认，仍然零模型依赖。
    """
    segments = token.split(".")
    if len(segments) != 3:
        return False
    header = segments[0]
    try:
        raw = base64.urlsafe_b64decode(header + "=" * (-len(header) % 4))
        parsed = json.loads(raw.decode("utf-8"))
    except (ValueError, binascii.Error, UnicodeDecodeError):
        return False
    return isinstance(parsed, dict) and "alg" in parsed


def _looks_like_secret(token: str) -> bool:
    """熵检测兜底：够长、非纯数字、非纯 hex，且香农熵越线，才当密钥。"""
    if len(token) < _MIN_SECRET_CHARS:
        return False
    if token.isdigit():
        return False
    # 整串都是十六进制 → 是哈希/摘要（自家 sha256、事实脊柱凭证正在此列），不误伤
    if set(token) <= _HEX_CHARS:
        return False
    # 文件系统路径不是密钥。候选字符类包含 `/`（标准 base64 需要），否则
    # `/tmp/.../uploads/<hex>/a` 这类绝对路径会被整体当成一个高熵候选。
    if _looks_like_filesystem_path(token):
        return False
    return _entropy(token) >= _MIN_ENTROPY


def _looks_like_filesystem_path(token: str) -> bool:
    """候选是否更像文件系统路径，而不是密钥。

    ``_CANDIDATE_RE`` 的字符类必须含 `/`（标准 base64 用的就是它），于是绝对路径
    （尤其附件所在的 ``/tmp/.../uploads/<hex>/a.txt``）会被整体匹配成一个候选，
    长度与熵都越线，被打成 ``[REDACTED:key]``。路径本身不是机密，且调用方（附件
    必读路径）依赖它可读，故这里按"以 ``/`` 开头且再含至少一个 ``/``"排除 POSIX
    绝对路径。

    只含单个 `/` 的候选**不**在此列：那仍可能是夹了一个 `/` 的标准 base64 密钥，
    照常走熵检测。Windows 盘符路径里的 `\\` / `:` 不在候选字符类中，正则会在那里
    断开；`C:/Users/...` 的 ``/Users/...`` 部分同样按绝对路径排除。
    """
    return token.startswith("/") and token.count("/") >= 2


def _entropy(token: str) -> float:
    """香农熵（bit/char）：`-Σ (n/N) log2(n/N)`。"""
    counts = Counter(token)
    total = len(token)
    return -sum((n / total) * math.log2(n / total) for n in counts.values())


class RedactionStore:
    """脱敏映射表的加密读写（§10.1）——占位符 → 原文，值单独加密。

    无密钥时不落库（fail-closed）：`enabled` 为假，`record` 返回 0，`resolve` 返回 None。
    删除走 `ContextLifecycle` 的级联（按 `turn_id` / `run_id`），本类不管删除。
    """

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        *,
        key: str | None = None,
    ) -> None:
        self._session_factory = session_factory
        self._box = SecretBox(key) if key else None

    @property
    def enabled(self) -> bool:
        """是否配了密钥——没配则映射不落库（正文仍照常打码）。"""
        return self._box is not None

    async def record(self, *, run_id: str, turn_id: str, matches: Sequence[RedactionMatch]) -> int:
        """把这次打码换下来的原文加密落库，返回写入行数；无密钥或空清单返回 0。"""
        box = self._box
        if box is None or not matches:
            return 0
        async with self._session_factory() as db:
            for match in matches:
                db.add(
                    ContextRedaction(
                        run_id=run_id,
                        turn_id=turn_id,
                        kind=match.kind.value,
                        placeholder=match.placeholder,
                        ciphertext=box.seal(match.original.encode("utf-8")),
                    )
                )
            await db.commit()
            return len(matches)

    async def resolve(self, turn_id: str, placeholder: str) -> str | None:
        """回填单个占位符的原文；查不到或未启用返回 None（占位符只在 turn 内唯一）。"""
        box = self._box
        if box is None:
            return None
        async with self._session_factory() as db:
            row = await db.scalar(
                select(ContextRedaction).where(
                    ContextRedaction.turn_id == turn_id,
                    ContextRedaction.placeholder == placeholder,
                )
            )
            if row is None:
                return None
            return box.open(row.ciphertext).decode("utf-8")

    async def mapping_of(self, turn_id: str) -> dict[str, str]:
        """该 turn 的全部「占位符 → 原文」；未启用返回空表。"""
        box = self._box
        if box is None:
            return {}
        async with self._session_factory() as db:
            rows = (
                await db.scalars(
                    select(ContextRedaction).where(ContextRedaction.turn_id == turn_id)
                )
            ).all()
            return {row.placeholder: box.open(row.ciphertext).decode("utf-8") for row in rows}
