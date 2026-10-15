"""口令哈希、JWT、随机令牌与可读初始密码。"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import string
import time
from typing import Any

import bcrypt
import jwt

from .config import settings

ACCESS_COOKIE = "fx_at"
REFRESH_COOKIE = "fx_rt"
STATE_COOKIE = "fx_state"
REVEAL_COOKIE = "fx_reveal"

_ALG = "HS256"


def _secret() -> str:
    if not settings.secret_key:
        raise RuntimeError("FLUX_CLOUD_SECRET_KEY 未配置，服务不应在无密钥下运行")
    return settings.secret_key


# ---------------------------------------------------------------- 口令

def _password_bytes(password: str) -> bytes:
    """bcrypt 只取前 72 字节，超出部分先折叠，避免静默截断带来歧义。"""
    raw = password.encode("utf-8")
    if len(raw) <= 72:
        return raw
    return hashlib.sha256(raw).hexdigest().encode("ascii")


def hash_password(password: str) -> str:
    return bcrypt.hashpw(_password_bytes(password), bcrypt.gensalt()).decode("ascii")


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    try:
        return bcrypt.checkpw(_password_bytes(password), password_hash.encode("ascii"))
    except (ValueError, TypeError):
        return False


def generate_readable_password(length: int = 14) -> str:
    """给第三方首登自动建号用的初始密码：去掉易混淆字符，保证四类字符齐全。"""
    lowers = "abcdefghijkmnpqrstuvwxyz"
    uppers = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    digits = "23456789"
    symbols = "!@#$%^&*"
    pools = (lowers, uppers, digits, symbols)
    chars = [secrets.choice(p) for p in pools]
    alphabet = lowers + uppers + digits + symbols
    chars += [secrets.choice(alphabet) for _ in range(max(0, length - len(chars)))]
    # 打散，避免前四位固定是「小写大写数字符号」的顺序
    for i in range(len(chars) - 1, 0, -1):
        j = secrets.randbelow(i + 1)
        chars[i], chars[j] = chars[j], chars[i]
    return "".join(chars)


# ---------------------------------------------------------------- JWT

def create_access_token(user_id: int, ttl_min: int | None = None) -> str:
    now = int(time.time())
    ttl = (ttl_min if ttl_min is not None else settings.access_ttl_min) * 60
    payload = {"sub": str(user_id), "typ": "access", "iat": now, "exp": now + ttl}
    return jwt.encode(payload, _secret(), algorithm=_ALG)


def decode_token(token: str) -> dict[str, Any] | None:
    try:
        return jwt.decode(token, _secret(), algorithms=[_ALG])
    except jwt.PyJWTError:
        return None


def create_state_token(provider: str, nonce: str, next_url: str = "", ttl_min: int = 10) -> str:
    now = int(time.time())
    payload = {
        "typ": "state",
        "provider": provider,
        "nonce": nonce,
        "next": next_url,
        "iat": now,
        "exp": now + ttl_min * 60,
    }
    return jwt.encode(payload, _secret(), algorithm=_ALG)


def create_reveal_token(user_id: int, password: str, ttl_min: int = 15) -> str:
    """一次性初始密码：不落库，用短期签名 cookie 带回前端展示。"""
    now = int(time.time())
    payload = {
        "typ": "reveal",
        "sub": str(user_id),
        "pwd": password,
        "iat": now,
        "exp": now + ttl_min * 60,
    }
    return jwt.encode(payload, _secret(), algorithm=_ALG)


# ---------------------------------------------------------------- 随机令牌

def new_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def new_nonce() -> str:
    return secrets.token_urlsafe(16)


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


_USERNAME_ALPHABET = string.ascii_lowercase + string.digits


def username_from_email(email: str | None, fallback: str = "flux") -> str:
    base = (email or "").split("@")[0].lower()
    base = "".join(ch if ch in _USERNAME_ALPHABET + "._-" else "" for ch in base)
    base = base.strip("._-") or fallback
    return base[:24]
