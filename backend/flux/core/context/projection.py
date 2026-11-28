"""HTTP / 任务流投影的统一打码入口。

任务对象、任务消息与任务流事件都是"给人看"的投影：`to_dict()` 里的正文、嵌套的
确认卡 / 决策记录 / 计划步骤仍可能是用户原文。密钥与配置字面量不该随投影离开进程，
但数据库里的原文必须保持不动（内部查询、重试与审计仍以库为准）。

API 层（`flux.api.v1.tasks`）与 core 层（审批、计划卡、Agent 切换、DSH 收尾）都走这里，
避免各处各写一份深拷贝逻辑，也避免 core 反向 import API 层造成循环依赖。

`source` 可以是 `Container`（取其 `settings`）、`Settings` 本身，或直接是字面量序列
（例如 `TaskRunBridge` 只持有 `redaction_literal_list` 的元组）。

本模块还承载"未完成密钥前缀"的识别（`incomplete_secret_start` / `safe_cut` 等）：
流式 flush、assistant 消息落库与消息投影必须用同一套口径，否则模型恰好停在某个
字面量 / 令牌前半段时，落库与 GET 消息仍会留下 `CUSTOM_LITE` 这类残片。
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from typing import Any

from flux.core.context.redact import redact, redact_bearer_tokens
from flux.enums import TaskMessageRole

#: 跨帧暂缓：仍在拼内置密钥规则的尾串字符（base64 / url-safe / JWT / 凭证 / 熵检测）。
#: 这些字符组成的尾部一旦被拆到下一帧，单帧正则就认不出完整密钥。
_STREAM_PENDING_TAIL_RE = re.compile(r"[A-Za-z0-9_\-+/=.@:%]+$")
#: `Bearer` 关键字（大小写不敏感）：模型可能输出小写 `bearer`，只认大写会漏判。
_BEARER_WORD_RE = re.compile(r"(?i)\bbearer\b")
#: `Bearer` 之后的令牌字符类（与内置 `_TOKEN_RE` 同口径）。
_BEARER_TOKEN_CHARS = r"[A-Za-z0-9._\-+/=]"
#: 令牌之后允许的句子收尾标点：`Bearer <token>。` 仍是"停在令牌上"的形态。
_BEARER_TRAIL_CHARS = ".,;:!?。，；：！？、…()[]{}<>\"'`（）【】「」『』《》〈〉〔〕」』»｜|"
#: `Bearer` 到本轮累计原文末尾：空白 + 令牌串 + 可选句子标点 + 空白。
#: 用显式标点集合而不是"非令牌字符"——否则 `请用Bearer令牌` 这类正文会被误判成令牌。
_BEARER_TAIL_RE = re.compile(
    rf"\s*(?P<token>{_BEARER_TOKEN_CHARS}*)[{re.escape(_BEARER_TRAIL_CHARS)}]*\s*$"
)
#: PEM 起止标记：`-----BEGIN ` 之后没等到 `-----END` 之前都不能当已闭合。
_PEM_BEGIN = "-----BEGIN "
_PEM_END = "-----END "


def projection_literals(source: Any) -> tuple[str, ...]:
    """从 container / Settings / 字面量序列中取出精确字面量清单。"""
    if source is None:
        return ()
    settings = getattr(source, "settings", source)
    literals = getattr(settings, "redaction_literal_list", None)
    if literals is None and isinstance(settings, (list, tuple, set, frozenset)):
        literals = settings
    return tuple(literals or ())


def redact_text(source: Any, text: str) -> str:
    """按来源对应的字面量清单给一段准备下发的文本打码。"""
    return redact(text, literals=projection_literals(source)).text


def redact_projection(source: Any, value: Any) -> Any:
    """对投影做深拷贝式打码，不改动 ORM / 已落库对象。"""
    if isinstance(value, str):
        return redact_text(source, value)
    if isinstance(value, list):
        return [redact_projection(source, item) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_projection(source, item) for item in value)
    if isinstance(value, dict):
        return {key: redact_projection(source, item) for key, item in value.items()}
    return value


# --- 未完成密钥前缀：流式 flush、落库与消息投影共用同一套判定 ---


def _bearer_suffix(raw: str) -> tuple[int, str] | None:
    """``raw`` 末尾若是 `Bearer <令牌串><句子标点>`，返回 ``(Bearer 起点, 令牌串)``。

    大小写不敏感，并允许令牌后跟句子收尾标点（`。` `,` `!` 等）。旧实现用大小写敏感的
    ``rfind("Bearer")`` + ``fullmatch``，小写 `bearer` 或尾随标点都会让判定整体失败，
    于是 `bearer abcdef…` 被当成正文放出。
    """
    match: re.Match[str] | None = None
    for candidate in _BEARER_WORD_RE.finditer(raw):
        match = candidate
    if match is None:
        return None
    tail = _BEARER_TAIL_RE.fullmatch(raw[match.end() :])
    if tail is None:
        return None
    return match.start(), tail.group("token")


def pending_suffix_length(raw: str, literals: Sequence[str]) -> int:
    """返回 ``raw`` 末尾必须暂缓、不能放出的字符数（0 = 整段都可安全放出）。

    暂缓两类内容：

    1. 可能仍在拼某个配置字面量的后缀——字面量可含空格等任意字符，只能按前缀精确比对；
    2. 可能仍在拼内置密钥规则的尾串——`sk-` / JWT / `Bearer` / 熵检测 / `user:pass@host` /
       未闭合 PEM。它们的字符都属于同一段"令牌串"，暂缓整段尾串即可。

    只要这两类未完成的后缀留在手里，已放出的可见文本就始终不会包含某个密钥/字面量的前半截。
    """
    hold = 0
    tail = _STREAM_PENDING_TAIL_RE.search(raw)
    if tail is not None:
        hold = len(raw) - tail.start()

    # `Bearer` 令牌的字符类不含空格，靠上面的尾串规则捕不到它前面的 `Bearer `。
    bearer = _bearer_suffix(raw)
    if bearer is not None:
        hold = max(hold, len(raw) - bearer[0])

    # 未闭合的 PEM 私钥/证书块：从 `-----BEGIN ` 一直暂缓到出现 `-----END`（或本轮结束）。
    begin = raw.rfind(_PEM_BEGIN)
    if begin != -1 and raw.find(_PEM_END, begin) == -1:
        hold = max(hold, len(raw) - begin)

    # 配置字面量只认"未完成前缀"：完整字面量交给 redact 整体替换，不需要暂缓。
    for literal in literals:
        limit = min(len(literal) - 1, len(raw))
        for size in range(limit, hold, -1):
            if raw.endswith(literal[:size]):
                hold = size
                break
    return hold


def safe_cut(raw: str, literals: Sequence[str]) -> int:
    """在 ``raw`` 上找一个可安全放出的切点：切点之前不含任何未完成的密钥片段。

    先按尾部暂缓量退一次；退完的位置可能正好落在另一个字面量/令牌串中间（例如切点
    落在一个配置字面量内部），于是对缩小的前缀再算一次，直到前缀本身也以安全边界结尾。
    """
    cut = len(raw)
    while cut > 0:
        hold = pending_suffix_length(raw[:cut], literals)
        if hold <= 0:
            break
        cut -= hold
    return max(0, cut)


def incomplete_literal_start(raw: str, literals: Sequence[str]) -> int | None:
    """``raw`` 末尾若停在某个配置字面量的**未完成前缀**上，返回最早的前缀起点。"""
    starts: list[int] = []
    for literal in literals:
        if not literal:
            continue
        limit = min(len(literal) - 1, len(raw))
        for size in range(limit, 0, -1):
            if raw.endswith(literal[:size]):
                starts.append(len(raw) - size)
                break
    return min(starts) if starts else None


def looks_like_incomplete_token(token: str) -> bool:
    """``token`` 是否是 `sk-` 密钥 / JWT 头这类"再长一点就是密钥"的未完成前缀。"""
    if not token:
        return False
    # `sk-` / `sk-proj-…`：前缀本身已经说明它是密钥；`sk` 可能是 `sk-` 的前半段。
    if token.startswith("sk-") or token == "sk":
        return True
    # JWT 头是 base64url 的 `{"`，解出来固定以 `eyJ` 开头。
    return bool(token.startswith("eyJ"))


def incomplete_bearer_token(token: str) -> bool:
    """`Bearer ` 之后这段是否像"还没写完"的令牌，而不是普通英文词（如 ``tokens``）。

    全小写长片段（``len >= 16``）同样按令牌处理：短业务词（`token` / `tokens`）照常放出，
    但长到不像自然语言时 fail-closed，宁可少发一段也不泄露令牌。
    """
    if not token:
        return True
    if looks_like_incomplete_token(token):
        return True
    if len(token) >= 16:
        return True
    return any(char.isdigit() or char.isupper() for char in token) or any(
        char in "._-+/=" for char in token
    )


def incomplete_secret_start(raw: str, literals: Sequence[str]) -> int | None:
    """正常结束（flush）时仍不能当正文放出的未完成密钥起点；全部安全时返回 ``None``。

    与流式暂缓不同：普通词尾（``world``）虽然也可能像令牌尾，但它是完整正文，正常结束
    就该发出。这里只挑出**确实不完整**的密钥形态：配置字面量前缀、`sk-` / JWT /
    `Bearer` / 未闭合 PEM。命中时从该起点起丢弃，之前的正文照常放出。
    """
    candidates: list[int] = []
    literal_start = incomplete_literal_start(raw, literals)
    if literal_start is not None:
        candidates.append(literal_start)

    tail = _STREAM_PENDING_TAIL_RE.search(raw)
    if tail is not None and looks_like_incomplete_token(tail.group(0)):
        candidates.append(tail.start())

    bearer = _bearer_suffix(raw)
    if bearer is not None and incomplete_bearer_token(bearer[1]):
        candidates.append(bearer[0])

    begin = raw.rfind(_PEM_BEGIN)
    if begin != -1 and raw.find(_PEM_END, begin) == -1:
        candidates.append(begin)

    return min(candidates) if candidates else None


def strip_incomplete_secret_prefix(source: Any, text: str) -> str:
    """去掉 ``text`` 末尾未完成的密钥 / 字面量前缀；其余正文原样保留。

    与流式正常结束 flush 同口径（``_final_cut``）：先定位可疑起点，再退到该起点之前
    真正安全的切点。用于 assistant 消息落库与消息投影，避免模型恰好停在
    ``CUSTOM_LITE`` 这类前缀时把残片写进库或下发给客户端。
    """
    if not text:
        return text
    literals = projection_literals(source)
    suspicious = incomplete_secret_start(text, literals)
    if suspicious is None:
        return text
    return text[: safe_cut(text[:suspicious], literals)]


def redact_assistant_text(source: Any, text: str) -> str:
    """assistant 文本落库 / 下发前的共享消毒：去未完成前缀 + 打码 Bearer 令牌。

    不替换配置字面量：完整字面量仍按既有约定留在库里，由投影层统一打码。
    """
    return redact_bearer_tokens(strip_incomplete_secret_prefix(source, text))


def sanitize_assistant_value(source: Any, value: Any) -> Any:
    """深拷贝式消毒 payload / 决策 JSON 中的字符串（只处理 assistant 来源）。

    嵌套的 decision record、确认项等同样是 assistant 正文的副本；只去掉每条字符串
    末尾未完成的密钥前缀、打码 Bearer 令牌，完整字面量保持原文。
    """
    if isinstance(value, str):
        return redact_assistant_text(source, value)
    if isinstance(value, list):
        return [sanitize_assistant_value(source, item) for item in value]
    if isinstance(value, tuple):
        return tuple(sanitize_assistant_value(source, item) for item in value)
    if isinstance(value, dict):
        return {key: sanitize_assistant_value(source, item) for key, item in value.items()}
    return value


def sanitize_assistant_message_fields(
    source: Any, content: str, payload: dict[str, Any] | None
) -> tuple[str, dict[str, Any] | None]:
    """对 assistant 消息正文与 payload 做未完成前缀 / Bearer 令牌消毒。

    payload 里的嵌套字段（决策卡、确认项、工具输出）与正文同源，必须一并处理，
    否则直接读库仍会看到 ``CUSTOM_LITE`` 这类残片。
    """
    sanitized = redact_assistant_text(source, content)
    if payload is None:
        return sanitized, payload
    return sanitized, sanitize_assistant_value(source, payload)


def redacted_message(source: Any, message: Any) -> dict[str, Any]:
    """消息投影（副本）：推流 / HTTP 响应统一走这里，落库对象保持原文。

    assistant 消息额外过一道"未完成密钥前缀"消毒，与流式 flush 同口径；user 消息
    不动（用户自己打的前缀不能因为投影被吞掉），只做完整字面量 / 内置规则打码。
    """
    data = message.to_dict()
    if data.get("role") == TaskMessageRole.ASSISTANT.value:
        content = data.get("content")
        if isinstance(content, str):
            content, payload = sanitize_assistant_message_fields(
                source, content, data.get("payload")
            )
            data = {**data, "content": content, "payload": payload}
    return redact_projection(source, data)


def task_projection(source: Any, task: Any) -> dict[str, Any]:
    """任务 HTTP / 事件投影（副本）：正文、确认卡、决策记录等一并打码。"""
    return redact_projection(source, task.to_dict())
