"""本机绑定的账号云主人（cloud-owner.json）。

为什么需要单独一份绑定：

账号云签发的 access token 只证明"某账号登录过"，不证明"这个账号是这台机器的主人"。
历史上 `is_cloud_access_token` 只要签名对就放行，等于任何账号（甚至攻击者自己注册的）
都能拿到本机 `/api/v1`（终端可执行任意命令）。修复后要么配置
`FLUX_CLOUD_ALLOWED_USERS`，要么在本机回环 / 带 `FLUX_AUTH_TOKEN` 完成一次登录，
把 user id 绑成主人。

写入入口必须在 `flux.api.v1.cloud` 里由"本机主人鉴权"把关；本模块只负责
0600 落盘与读取，不做授权判断。
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.logging import get_logger

logger = get_logger(__name__)


@dataclass(frozen=True)
class CloudOwner:
    """本机绑定主人：只需要 id 与展示名，令牌仍只在 cloud.json / 客户端 cookie 里。"""

    user_id: str
    username: str = ""
    bound_at: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "user_id": self.user_id,
            "username": self.username,
            "bound_at": self.bound_at,
        }

    @classmethod
    def from_dict(cls, data: Any) -> CloudOwner | None:
        if not isinstance(data, dict):
            return None
        user_id = str(data.get("user_id") or "").strip()
        if not user_id:
            return None
        bound_at = data.get("bound_at")
        return cls(
            user_id=user_id,
            username=str(data.get("username") or ""),
            bound_at=float(bound_at) if isinstance(bound_at, (int, float)) else 0.0,
        )


def load_owner(path: Path) -> CloudOwner | None:
    """读取绑定；不存在或损坏都返回 None（按"未绑定"处理，fail-closed）。"""
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    except OSError as exc:
        logger.warning("读取本机账号云主人失败 %s: %s", path, exc)
        return None
    try:
        data = json.loads(raw)
    except ValueError:
        logger.warning("本机账号云主人文件不是合法 JSON：%s", path)
        return None
    return CloudOwner.from_dict(data)


def save_owner(path: Path, owner: CloudOwner) -> None:
    """写入绑定，权限 0600（临时文件 + replace，避免半截文件）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    payload = owner.to_dict()
    if not payload["bound_at"]:
        payload["bound_at"] = time.time()
    tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def clear_owner(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return
    except OSError as exc:  # pragma: no cover - 权限异常等，记账即可
        logger.warning("删除本机账号云主人失败 %s: %s", path, exc)


__all__ = ["CloudOwner", "clear_owner", "load_owner", "save_owner"]
