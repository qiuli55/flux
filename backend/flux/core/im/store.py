"""飞书 / 个人微信连接器的凭据与游标落盘。

一个文件同时保存两条连接的状态：

- 飞书：绑定到的单聊 chat_id / 发送者标识（不含 App Secret，后者只在环境变量里）；
- 微信：iLink 返回的 bot_token、ilink_bot_id、baseurl、ilink_user_id，以及
  `get_updates_buf` 游标和最近一次入站的 context_token。

安全约束（本模块唯一负责）：

- 文件权限收紧到 0600（Windows 走 icacls），不写进 git；
- 原子写（临时文件 + os.replace），避免进程被杀时留下半个 JSON；
- 对外只暴露 `public_*` 投影，任何方法都不会把 token / context_token 返回给 API 层；
- 文件损坏时按「未配置」处理并记 warning，不让连接器因为一个坏文件启动失败。
"""

from __future__ import annotations

import json
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.core.agent_runtime.platforms import secure_file
from flux.logging import get_logger

logger = get_logger(__name__)

__all__ = ["ImConnectorStore", "MAX_PENDING_NOTICES"]

#: 文件大小上限：只是状态文件，正常远小于此。
_MAX_FILE_BYTES = 256 * 1024
#: 微信在互动窗口外推不出去时暂存的提醒上限；超出的旧提醒丢弃。
MAX_PENDING_NOTICES = 20


def _empty() -> dict[str, Any]:
    return {"version": 1, "feishu": {}, "wechat": {}}


@dataclass(frozen=True)
class WechatCredentials:
    bot_token: str
    ilink_bot_id: str
    baseurl: str
    ilink_user_id: str

    @property
    def complete(self) -> bool:
        return bool(self.bot_token and self.ilink_bot_id and self.ilink_user_id)


class ImConnectorStore:
    """连接器状态的唯一落盘点（同步方法 + 进程内锁）。"""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.RLock()

    @property
    def path(self) -> Path:
        return self._path

    # --- 原始读写 ---

    def load(self) -> dict[str, Any]:
        with self._lock:
            path = self._path
            try:
                if not path.is_file() or path.stat().st_size > _MAX_FILE_BYTES:
                    return _empty()
                raw = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                logger.warning("IM 连接器状态文件不可读 path=%s", path)
                return _empty()
            if not isinstance(raw, dict):
                return _empty()
            data = _empty()
            for key in ("feishu", "wechat"):
                section = raw.get(key)
                if isinstance(section, dict):
                    data[key] = section
            return data

    def save(self, data: dict[str, Any]) -> None:
        with self._lock:
            path = self._path
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            tmp = path.parent / f".{path.name}.tmp"
            payload = {
                "version": 1,
                "feishu": data.get("feishu") or {},
                "wechat": data.get("wechat") or {},
            }
            tmp.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            secure_file(tmp)
            os.replace(tmp, path)
            secure_file(path)

    # --- 飞书 ---

    def feishu(self) -> dict[str, Any]:
        return dict(self.load().get("feishu") or {})

    def update_feishu(self, **fields: Any) -> dict[str, Any]:
        with self._lock:
            data = self.load()
            section = dict(data.get("feishu") or {})
            section.update({key: value for key, value in fields.items() if value is not None})
            data["feishu"] = section
            self.save(data)
            return dict(section)

    def clear_feishu(self) -> None:
        with self._lock:
            data = self.load()
            data["feishu"] = {}
            self.save(data)

    # --- 微信 ---

    def wechat(self) -> dict[str, Any]:
        return dict(self.load().get("wechat") or {})

    def wechat_credentials(self) -> WechatCredentials | None:
        section = self.wechat()
        credentials = WechatCredentials(
            bot_token=str(section.get("bot_token") or ""),
            ilink_bot_id=str(section.get("ilink_bot_id") or ""),
            baseurl=str(section.get("baseurl") or ""),
            ilink_user_id=str(section.get("ilink_user_id") or ""),
        )
        return credentials if credentials.complete else None

    def update_wechat(self, **fields: Any) -> dict[str, Any]:
        with self._lock:
            data = self.load()
            section = dict(data.get("wechat") or {})
            section.update({key: value for key, value in fields.items() if value is not None})
            data["wechat"] = section
            self.save(data)
            return dict(section)

    def clear_wechat(self) -> None:
        with self._lock:
            data = self.load()
            data["wechat"] = {}
            self.save(data)

    def add_pending_notice(self, text: str) -> None:
        value = (text or "").strip()
        if not value:
            return
        with self._lock:
            section = self.wechat()
            pending = section.get("pending_notices")
            items = [str(item) for item in pending] if isinstance(pending, list) else []
            items.append(value)
            self.update_wechat(pending_notices=items[-MAX_PENDING_NOTICES:])

    def take_pending_notices(self) -> list[str]:
        with self._lock:
            section = self.wechat()
            pending = section.get("pending_notices")
            items = [str(item) for item in pending] if isinstance(pending, list) else []
            if items:
                self.update_wechat(pending_notices=[])
            return items
