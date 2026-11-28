"""入站脱敏与加密映射表测试（FLUX_CONTEXT_MECHANISM §10.1 / §13-5 / §13-7）。

打码必须**零模型依赖**（§13-7），故只有正则 + 熵检测两条腿。这里逐条核对：
四类带前缀标记、熵检测兜底（含"别误伤自家 sha256"）、占位符编号与复用、映射 1:1、
映射值单独加密（密文里搜不到明文）、无密钥时 fail-closed。
"""

from __future__ import annotations

import base64
import hashlib

import pytest
from sqlalchemy import select

from flux.config import Settings
from flux.core.context import RedactionStore, SecretBox, redact
from flux.core.context.redact import redact_for_settings
from flux.db.session import create_engine, create_session_factory
from flux.enums import RedactionKind
from flux.models.context import ContextRedaction

# --- 四类带前缀标记（§10.1 的对照表）---


def test_redacts_prefixed_key() -> None:
    result = redact("OPENAI_KEY=sk-abcdefghijklmnop")
    assert result.text == "OPENAI_KEY=[REDACTED:key]"
    assert result.changed is True
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == "sk-abcdefghijklmnop"


def test_redacts_pem_block() -> None:
    pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
    result = redact(f"cat {pem}\ndone")
    assert result.text == "cat [REDACTED:pem]\ndone"
    assert result.matches[0].kind == RedactionKind.PEM
    assert result.matches[0].original == pem


def test_redacts_bearer_token() -> None:
    token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    result = redact(f"Authorization: Bearer {token}")
    assert result.text == "Authorization: [REDACTED:token]"
    assert result.matches[0].kind == RedactionKind.TOKEN
    # 前缀 `Bearer ` 属令牌的一部分，一起换掉
    assert result.matches[0].original == f"Bearer {token}"


#: 明显不像英语单词的虚构全小写片段（高辅音、无常见词缀），不是任何真实凭据。
_LOWERCASE_BEARER_TOKEN = "qxzjvkwpqzbcdfghjklm"


def test_redacts_inline_lowercase_bearer_token_with_trailing_prose() -> None:
    """小写 `bearer` + 全小写令牌在句中出现（后面还有正文）也必须打码。"""
    for token in ("qxzjvkwpqz", _LOWERCASE_BEARER_TOKEN, _LOWERCASE_BEARER_TOKEN[:8]):
        text = f"凭证是 bearer {token}，后面还有正文。"
        result = redact(text)
        assert token not in result.text
        assert result.text == "凭证是 [REDACTED:token]，后面还有正文。"
        assert result.matches[0].kind == RedactionKind.TOKEN
        assert result.matches[0].original == f"bearer {token}"


def test_uppercase_bearer_still_redacts_inline_lowercase_token() -> None:
    """既有口径不削弱：大写 `Bearer` + 全小写片段无论是否在句中都照旧打码。"""
    token = "tokenization"  # 12 个字符，满足旧正则的 {8,}
    result = redact(f"Authorization: Bearer {token}，然后继续。")
    assert token not in result.text
    assert result.text == "Authorization: [REDACTED:token]，然后继续。"
    assert result.matches[0].original == f"Bearer {token}"


def test_lowercase_bearer_prose_is_not_redacted() -> None:
    """对照：普通英语短语不能被扩大后的规则误伤（尤其 `bearer tokenization`）。"""
    for prose in (
        "bearer tokenization",
        "熟悉 bearer token 与 bearer tokens 的区别",
        "bearer authentication 的说明",
    ):
        result = redact(prose)
        assert result.text == prose
        assert result.matches == ()


def test_redacts_credential_keeps_host() -> None:
    result = redact("postgres://user:s3cr3t@db.internal:5432/app")
    assert result.text == "postgres://[REDACTED:cred]@db.internal:5432/app"
    assert result.matches[0].kind == RedactionKind.CRED
    # 只换认证段，`@host` 保留（主机名不是秘密，留着才好排障）
    assert result.matches[0].original == "user:s3cr3t"


# --- 无 Bearer 前缀的 JWT（issue #4）---

#: 虚构 JWT：header 是 `{"alg":"HS256","typ":"JWT"}` 的 base64url，payload/signature 均为假值
_JWT = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0"
    ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
)


def test_redacts_jwt_without_bearer_prefix() -> None:
    result = redact(f"cookie={_JWT}")
    assert result.text == "cookie=[REDACTED:token]"
    assert result.matches[0].kind == RedactionKind.TOKEN
    assert result.matches[0].original == _JWT


def test_bearer_jwt_still_redacts_with_prefix_convention() -> None:
    """既有约定不破：带 `Bearer ` 时前缀与令牌一起换掉。"""
    result = redact(f"Authorization: Bearer {_JWT}")
    assert result.text == "Authorization: [REDACTED:token]"
    assert result.matches[0].original == f"Bearer {_JWT}"


def test_jwt_rule_does_not_touch_dotted_filenames_or_versions() -> None:
    """三段点分隔的长串不一定都是 JWT：header 解不出 JSON 就不换。"""
    text = "read every_module_name.another_module_name.third_module_name and v1.2.3"
    result = redact(text)
    assert result.text == text
    assert result.matches == ()


# --- 可控字面量清单（issue #4）---


def test_short_literal_is_not_redacted_by_default() -> None:
    """没有前缀、熵也不够高的短密钥，未配置清单时保持不误伤。"""
    text = "secret=TEST_SECRET_12345"
    result = redact(text)
    assert result.text == text
    assert result.matches == ()


def test_configured_literal_is_redacted_exactly() -> None:
    result = redact("secret=TEST_SECRET_12345", literals=["TEST_SECRET_12345"])
    assert result.text == "secret=[REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == "TEST_SECRET_12345"


def test_literal_list_ignores_empty_and_prefers_longest() -> None:
    """空字面量不参与；长字面量先替换，短字面量不会把长串拆坏。"""
    result = redact(
        "values TEST_SECRET_12345 and TEST_SECRET_12345_LONG",
        literals=["", "TEST_SECRET_12345", "TEST_SECRET_12345_LONG", "TEST_SECRET_12345"],
    )
    assert result.text == "values [REDACTED:key:2] and [REDACTED:key]"


def test_settings_literal_list_parsing() -> None:
    settings = Settings(
        redaction_literals="TEST_SECRET_12345, another.secret\nthird-secret;ignored"
    )
    # 只按逗号 / 空白切分；分号不是分隔符，保留在字面量里
    assert settings.redaction_literal_list == (
        "TEST_SECRET_12345",
        "another.secret",
        "third-secret;ignored",
    )


def test_redact_for_settings_uses_configured_literals() -> None:
    settings = Settings(redaction_literals="TEST_SECRET_12345")
    result = redact_for_settings("secret=TEST_SECRET_12345", settings)
    assert result.text == "secret=[REDACTED:key]"


# --- 熵检测兜底（无前缀的密钥）---


def test_entropy_catches_random_token() -> None:
    token = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae"
    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    # 无前缀的密钥归到 KEY（"key" 是兜底的密钥标记）
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == token


def test_entropy_spares_sha256_hex() -> None:
    """纯 hex 熵上限才 4.0，低于阈值 —— 自家 sha256 绝不能被误伤。"""
    digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    result = redact(f"sha256 {digest}")
    assert result.text == f"sha256 {digest}"
    assert result.matches == ()


def test_entropy_spares_plain_digits() -> None:
    number = "1" * 40
    result = redact(f"id {number}")
    assert result.text == f"id {number}"
    assert result.matches == ()


def test_entropy_spares_uploads_absolute_path() -> None:
    """附件绝对路径不能被熵检测误判成 KEY（uploads 下的 hex 目录 + 文件名）。"""
    path = "/tmp/flux/uploads/0123456789abcdef0123456789abcdef/a.txt"
    result = redact(f"附件在 {path}")
    assert result.text == f"附件在 {path}"
    assert result.matches == ()


def test_entropy_still_catches_base64_with_single_slash() -> None:
    """只含一个 `/` 的候选不是路径，仍按高熵 base64 密钥打码。"""
    token = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae/abcdefGHIJKL0123456789"
    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == token


def _fictional_standard_base64(seed: int) -> str:
    """由虚构种子派生标准 base64 串（只用于锁定形态，不是任何真实凭据）。"""
    digest = hashlib.sha256(f"fictional-test-seed:{seed}".encode()).digest()
    return base64.b64encode(digest).decode()


def test_entropy_catches_multislash_base64_with_short_segment() -> None:
    """不以 `/` 开头、含多个 `/` 的标准 base64 不能因"切开有短段"就被当成路径。"""
    token = _fictional_standard_base64(2)
    assert not token.startswith("/")
    assert token.count("/") >= 2
    assert any(len(segment) < 20 for segment in token.split("/"))

    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    # 记录的是整段令牌，而不是某个恰好像密钥的短段
    assert result.matches[0].original == token


def test_entropy_catches_multislash_base64_with_all_short_segments() -> None:
    """多段都短于 20 的标准 base64：旧口径整串原样漏出，必须整段打码。"""
    token = _fictional_standard_base64(15)
    assert not token.startswith("/")
    assert token.count("/") >= 2
    assert all(len(segment) < 20 for segment in token.split("/"))

    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == token


def test_entropy_catches_base64_starting_with_slash() -> None:
    """恰以 `/` 开头的标准 base64 仍要整串打码，不能只按 POSIX 绝对路径留短前缀。

    验收遗留 A（虚构种子 386）：整串是 44 位标准 base64，只是首字符碰巧是 `/`；
    按段打码会留下 13 位前缀段。应回退为整段 `[REDACTED:key]`，`original` 为整串。
    """
    token = _fictional_standard_base64(386)
    assert token.startswith("/")
    assert token.count("/") >= 2
    assert any(len(segment) < 20 for segment in token.split("/"))

    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == token


def test_entropy_catches_multislash_base64_with_word_fragments() -> None:
    """含词形态碎片的多斜杠标准 base64 不能部分打码。

    验收遗留 B（虚构种子 1377）：`bnak` / `uzs` 这类短小写片段碰巧过了元音比例，
    被当成目录名，导致前缀碎片留下。整串仍是标准 base64 时应整段打码。
    """
    token = _fictional_standard_base64(1377)
    assert not token.startswith("/")
    assert token.count("/") >= 2

    result = redact(f"value {token}")
    assert result.text == "value [REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == token


def test_entropy_redacts_narrow_relative_path_secret_segment() -> None:
    """窄口径相对路径 `foo/x/<24 位密钥>` 要打码密钥段，保留 `foo/x/`。

    验收遗留 D：只有一个真正目录名 + 1 字符段时走不进按段打码；整串又不到全局
    32 位下限。应把 24 位密钥形段按段打码，路径前缀原样保留。
    """
    secret = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1"
    result = redact(f"见 foo/x/{secret}")
    assert result.text == "见 foo/x/[REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    assert result.matches[0].original == secret


def test_entropy_redacts_secret_segment_inside_absolute_path() -> None:
    """绝对路径中间的无前缀高熵密钥段要打码，路径前缀保留。"""
    secret = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae"
    result = redact(f"curl /api/keys/{secret}")
    assert result.text == "curl /api/keys/[REDACTED:key]"
    assert result.matches[0].kind == RedactionKind.KEY
    # 只记录密钥段本身，不把整条路径当作原文
    assert result.matches[0].original == secret


def test_entropy_keeps_path_identifiers_inside_absolute_path() -> None:
    """路径段里的纯 hex（32/40/64）与 UUID 是标识符，不能当密钥打掉。"""
    hex32 = "0123456789abcdef0123456789abcdef"
    hex40 = "0123456789abcdef0123456789abcdef01234567"
    hex64 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    uuid_like = "123e4567-e89b-12d3-a456-426614174000"
    path = f"/var/lib/flux/objects/{hex32}/{hex40}/{hex64}/{uuid_like}/payload"
    result = redact(f"object at {path}")
    assert result.text == f"object at {path}"
    assert result.matches == ()


def test_entropy_still_redacts_secret_alongside_path_identifiers() -> None:
    """同一路径里标识符保留、密钥段打码，两类判定互不干扰。"""
    hex32 = "0123456789abcdef0123456789abcdef"
    secret = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae"
    path = f"/data/{hex32}/{secret}/blob"
    result = redact(f"path {path}")
    assert result.text == f"path /data/{hex32}/[REDACTED:key]/blob"
    assert [m.original for m in result.matches] == [secret]


def test_entropy_redacts_short_base64_segment_inside_absolute_path() -> None:
    """切段后的较短 base64 段（20/24 位）仍按密钥打码，前缀路径保留。"""
    secret20 = "Zx9Qm2Lp7Rw4Tn8Vc3Bk"
    secret24 = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1"
    with_plus = "Zx9Qm2Lp+7Rw4Tn8Vc3B"
    urlsafe = "Zx9Qm2Lp-7Rw4T_8Vc3B"

    result = redact(f"curl /api/keys/{secret20}")
    assert result.text == "curl /api/keys/[REDACTED:key]"
    assert [m.original for m in result.matches] == [secret20]

    result = redact(f"curl /api/keys/{secret24}")
    assert result.text == "curl /api/keys/[REDACTED:key]"
    assert [m.original for m in result.matches] == [secret24]

    for secret in (with_plus, urlsafe):
        result = redact(f"curl /api/keys/{secret}")
        assert result.text == "curl /api/keys/[REDACTED:key]"
        assert [m.original for m in result.matches] == [secret]


def test_entropy_redacts_both_short_segments_split_by_slash() -> None:
    """被斜杠切成两段、每段约 20 位的密钥：两段都打码，前缀保留。"""
    first = "Zx9Qm2Lp7Rw4Tn8Vc3Bk"
    second = "Ab7Yd3Kp9Nm2Qr5Ts8Wx"
    result = redact(f"/api/keys/{first}/{second}")
    assert result.text == "/api/keys/[REDACTED:key]/[REDACTED:key:2]"
    assert [m.original for m in result.matches] == [first, second]


def test_entropy_redacts_secret_segment_in_relative_path() -> None:
    """相对路径与绝对路径同口径：只打密钥段，其余段保留，不整串打码。"""
    secret32 = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae"
    result = redact(f"见 foo/{secret32}/bar")
    assert result.text == "见 foo/[REDACTED:key]/bar"
    assert [m.original for m in result.matches] == [secret32]

    first = "Zx9Qm2Lp7Rw4Tn8Vc3Bk"
    second = "Ab7Yd3Kp9Nm2Qr5Ts8Wx"
    result = redact(f"见 foo/{first}/{second}/bar")
    assert result.text == "见 foo/[REDACTED:key]/[REDACTED:key:2]/bar"
    assert [m.original for m in result.matches] == [first, second]


def test_entropy_redacts_secret_segment_in_windows_path() -> None:
    """Windows 反斜杠路径同样按段打码，并原样保留 `\\` 分隔符。"""
    secret = "Zx9Qm2Lp7Rw4Tn8Vc3Bk6Hj1Ds5Fg0Ae"
    result = redact(f"打开 C:\\Users\\{secret}\\a.txt")
    assert result.text == "打开 C:\\Users\\[REDACTED:key]\\a.txt"
    assert [m.original for m in result.matches] == [secret]


def test_entropy_spares_natural_long_directory_names() -> None:
    """普通目录名（词形态、低熵）不因长度被打码；随机 32 位混合串仍按密钥处理。"""
    assert redact("path my-super-long-project-name/notes.txt").text == (
        "path my-super-long-project-name/notes.txt"
    )
    assert redact("path project-2024-backup/file.txt").text == "path project-2024-backup/file.txt"
    assert redact("path data-2024-11-28-backup-x/file.txt").text == (
        "path data-2024-11-28-backup-x/file.txt"
    )


# --- 占位符编号与复用（映射 1:1）---


def test_placeholder_numbering_and_reuse() -> None:
    result = redact("sk-aaaaaaaa sk-aaaaaaaa sk-bbbbbbbb")
    # 同一原文复用同一占位符；同类不同原文按类递增编号
    assert result.text == "[REDACTED:key] [REDACTED:key] [REDACTED:key:2]"
    assert [m.placeholder for m in result.matches] == ["[REDACTED:key]", "[REDACTED:key:2]"]
    assert [m.original for m in result.matches] == ["sk-aaaaaaaa", "sk-bbbbbbbb"]


def test_unchanged_text_reports_no_matches() -> None:
    result = redact("just a plain sentence")
    assert result.text == "just a plain sentence"
    assert result.changed is False
    assert result.matches == ()


# --- 信封加解密（映射表的值单独加密）---


def test_secretbox_round_trip() -> None:
    box = SecretBox("correct horse")
    sealed = box.seal(b"attack at dawn")
    assert sealed != b"attack at dawn"
    assert box.open(sealed) == b"attack at dawn"


def test_secretbox_detects_tampering() -> None:
    box = SecretBox("correct horse")
    sealed = box.seal(b"attack at dawn")
    tampered = bytearray(sealed)
    tampered[-1] ^= 0x01
    with pytest.raises(ValueError):
        box.open(bytes(tampered))


def test_secretbox_rejects_wrong_key() -> None:
    sealed = SecretBox("k1").seal(b"secret")
    with pytest.raises(ValueError):
        SecretBox("k2").open(sealed)


def test_secretbox_rejects_empty_key() -> None:
    with pytest.raises(ValueError):
        SecretBox("")


def test_secretbox_random_nonce_makes_ciphertext_differ() -> None:
    box = SecretBox("k")
    assert box.seal(b"same") != box.seal(b"same")


# --- 映射表读写 ---


async def test_store_round_trip_and_mapping(settings: Settings, db_schema: None) -> None:
    engine = create_engine(settings.database_url)
    try:
        store = RedactionStore(create_session_factory(engine), key="test-key")
        assert store.enabled is True
        result = redact("token sk-abcdefghijklmnop")
        written = await store.record(run_id="r1", turn_id="t1", matches=result.matches)
        assert written == 1
        assert await store.resolve("t1", "[REDACTED:key]") == "sk-abcdefghijklmnop"
        assert await store.mapping_of("t1") == {"[REDACTED:key]": "sk-abcdefghijklmnop"}
        # 占位符只在 turn 内唯一：别的 turn 查同占位符查不到
        assert await store.resolve("t2", "[REDACTED:key]") is None
    finally:
        await engine.dispose()


async def test_stored_ciphertext_hides_plaintext(settings: Settings, db_schema: None) -> None:
    engine = create_engine(settings.database_url)
    secret = "sk-abcdefghijklmnop"
    try:
        store = RedactionStore(create_session_factory(engine), key="test-key")
        result = redact(secret)
        await store.record(run_id="r1", turn_id="t1", matches=result.matches)
        async with create_session_factory(engine)() as db:
            row = (await db.scalars(select(ContextRedaction))).one()
        assert secret.encode() not in row.ciphertext
        # to_dict 刻意不回显密文与明文，只给元数据
        assert "ciphertext" not in row.to_dict()
        assert row.to_dict()["ciphertext_bytes"] == len(row.ciphertext)
    finally:
        await engine.dispose()


async def test_store_is_fail_closed_without_key(settings: Settings, db_schema: None) -> None:
    """无密钥时正文照常打码，但映射**不落库**：最坏是查不到原文，不是明文躺库里。"""
    engine = create_engine(settings.database_url)
    try:
        store = RedactionStore(create_session_factory(engine))
        assert store.enabled is False
        result = redact("sk-abcdefghijklmnop")
        assert result.changed is True
        written = await store.record(run_id="r1", turn_id="t1", matches=result.matches)
        assert written == 0
        assert await store.resolve("t1", "[REDACTED:key]") is None
        assert await store.mapping_of("t1") == {}
    finally:
        await engine.dispose()
