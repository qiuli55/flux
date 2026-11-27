"""设置页「连接器」：内置连接器目录 + 用户自定义 MCP 服务器。

和 `backend/flux/api/v1/connectors.py` 不是同一套东西：那个接口是 Agent 执行
connector action（主规格 §12.7），这里只管理「哪些外部 MCP 服务器接入了 Flux」。
设置页使用独立的 `/api/v1/mcp-connectors`，不会改变旧 action API 的行为。

自定义连接器必须先用真实 MCP 协议探测通过（initialize + tools/list）才会落盘；
探测失败的服务器不会以「已连接」状态保存。会话状态只保留展示字段，不涉及令牌。
"""

from __future__ import annotations

import json
import os
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from flux.config import Settings
from flux.core.agent_runtime.platforms import secure_file
from flux.core.mcp_probe import McpProbeClient, McpProbeResult
from flux.errors import ConflictError, NotFoundError, ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

#: 连接器名字长上限；也会作为 MCP 配置里的 server key
_MAX_NAME_CHARS = 64
_MAX_URL_CHARS = 2048
_MAX_FILE_BYTES = 1 << 20
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_RESERVED_NAMES = {"flux"}

_STATUS_CONNECTED = "connected"
_STATUS_ERROR = "error"
_STATUS_DISCONNECTED = "disconnected"
_STATUS_COMING_SOON = "coming_soon"
_STATUS_NEEDS_REAUTH = "needs_reauth"

#: 内置连接器目录。GitHub 已完整接入；其余先占位，点击不发起任何连接。
_BUILTIN_CONNECTORS: tuple[dict[str, Any], ...] = (
    {
        "id": "github",
        "name": "GitHub",
        "kind": "builtin",
        "description": "读写仓库、提交代码与创建 Pull Request",
        "available": True,
        "status": _STATUS_DISCONNECTED,
        "featured": False,
        "action": "github",
    },
    {
        "id": "figma",
        "name": "Figma",
        "kind": "builtin",
        "description": "读取设计稿与组件信息",
        "available": False,
        "status": _STATUS_COMING_SOON,
        "featured": True,
        "action": "",
    },
    {
        "id": "gmail",
        "name": "Gmail",
        "kind": "builtin",
        "description": "搜索与读取邮件",
        "available": False,
        "status": _STATUS_COMING_SOON,
        "featured": True,
        "action": "",
    },
    {
        "id": "google-calendar",
        "name": "Google Calendar",
        "kind": "builtin",
        "description": "查看日程与安排会议",
        "available": False,
        "status": _STATUS_COMING_SOON,
        "featured": True,
        "action": "",
    },
    {
        "id": "google-drive",
        "name": "Google Drive",
        "kind": "builtin",
        "description": "读取云端文件与文档",
        "available": False,
        "status": _STATUS_COMING_SOON,
        "featured": True,
        "action": "",
    },
)


def _now_iso() -> str:
    return datetime.now(tz=timezone.utc).isoformat()


def _github_item(status: dict[str, Any] | None) -> dict[str, Any]:
    """把 GitHub 账号态映射成连接器状态；缺 repo scope 时提示重新连接。"""
    item = dict(_BUILTIN_CONNECTORS[0])
    if not status or not status.get("logged_in"):
        item.update({"status": _STATUS_DISCONNECTED, "account": None, "error": None})
        return item
    scopes = str(status.get("scopes") or "").strip()
    scope_set = {part.strip().lower() for part in scopes.split(",") if part.strip()}
    if scope_set and "repo" not in scope_set:
        item.update(
            {
                "status": _STATUS_NEEDS_REAUTH,
                "account": status.get("login"),
                "error": "授权缺少 repo 权限，Agent 无法读写仓库；请重新连接 GitHub。",
            }
        )
        return item
    item.update(
        {
            "status": _STATUS_CONNECTED,
            "account": status.get("login"),
            "error": None,
        }
    )
    return item


def validate_connector_name(name: str) -> str:
    value = (name or "").strip()
    if not value:
        raise ValidationError("请填写连接器名称")
    if len(value) > _MAX_NAME_CHARS:
        raise ValidationError(f"连接器名称不能超过 {_MAX_NAME_CHARS} 个字符")
    if _CONTROL_RE.search(value):
        raise ValidationError("连接器名称不能包含换行或控制字符")
    if value.lower() in _RESERVED_NAMES:
        raise ValidationError("名称 flux 保留给 Flux 内置工具，请换一个名字")
    return value


def validate_connector_url(url: str) -> str:
    value = (url or "").strip()
    if not value:
        raise ValidationError("请填写 MCP 服务器 URL")
    if len(value) > _MAX_URL_CHARS:
        raise ValidationError(f"服务器 URL 不能超过 {_MAX_URL_CHARS} 个字符")
    if _CONTROL_RE.search(value) or any(ch.isspace() for ch in value):
        raise ValidationError("服务器 URL 不能包含空格或控制字符")
    parts = urlsplit(value)
    if parts.scheme.lower() not in ("http", "https"):
        raise ValidationError("服务器 URL 需要以 https:// 或 http:// 开头")
    if not parts.netloc:
        raise ValidationError("服务器 URL 缺少主机名")
    return value


@dataclass
class _StoredConnector:
    id: str
    name: str
    url: str
    status: str = _STATUS_CONNECTED
    tools: list[str] | None = None
    error: str = ""
    created_at: str = ""
    last_checked_at: str = ""

    def public(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "kind": "custom",
            "description": "自定义 MCP 服务器",
            "available": True,
            "status": self.status,
            "url": self.url,
            "tools": list(self.tools or []),
            "account": None,
            "error": self.error or None,
            "featured": False,
            "action": "",
            "created_at": self.created_at,
            "last_checked_at": self.last_checked_at,
        }

    def to_record(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "url": self.url,
            "status": self.status,
            "tools": list(self.tools or []),
            "error": self.error,
            "created_at": self.created_at,
            "last_checked_at": self.last_checked_at,
        }


def _record_of(raw: Any) -> _StoredConnector | None:
    if not isinstance(raw, dict):
        return None
    connector_id = str(raw.get("id") or "").strip()
    name = str(raw.get("name") or "").strip()
    url = str(raw.get("url") or "").strip()
    if not connector_id or not name or not url:
        return None
    tools = raw.get("tools")
    return _StoredConnector(
        id=connector_id,
        name=name,
        url=url,
        status=str(raw.get("status") or _STATUS_CONNECTED),
        tools=[str(item) for item in tools] if isinstance(tools, list) else [],
        error=str(raw.get("error") or ""),
        created_at=str(raw.get("created_at") or ""),
        last_checked_at=str(raw.get("last_checked_at") or ""),
    )


class McpConnectorService:
    """自定义 MCP 连接器的探测、持久化与运行时注入视图。"""

    def __init__(
        self,
        settings: Settings,
        *,
        prober: McpProbeClient | None = None,
    ) -> None:
        self._settings = settings
        self._prober = prober or McpProbeClient()

    @property
    def path(self) -> Path:
        return self._settings.mcp_connectors_path

    # --- 存储 ---

    def _load(self) -> list[_StoredConnector]:
        path = self.path
        try:
            if not path.is_file() or path.stat().st_size > _MAX_FILE_BYTES:
                return []
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            logger.warning("自定义 MCP 连接器文件不可读 path=%s", path)
            return []
        raw_items = data.get("connectors") if isinstance(data, dict) else data
        if not isinstance(raw_items, list):
            return []
        records: list[_StoredConnector] = []
        for raw in raw_items:
            record = _record_of(raw)
            if record is not None:
                records.append(record)
        return records

    def _save(self, records: list[_StoredConnector]) -> None:
        path = self.path
        try:
            # 只给新建目录 0700；已有目录（例如 /tmp）不改权限，避免误伤共享目录。
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            tmp = path.parent / f".{path.name}.tmp"
            payload = {"version": 1, "connectors": [item.to_record() for item in records]}
            tmp.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            secure_file(tmp)
            os.replace(tmp, path)
            secure_file(path)
        except OSError as exc:
            raise ValidationError(f"保存自定义连接器失败：{exc}") from exc

    # --- 列表 ---

    def list_all(self, github_status: dict[str, Any] | None = None) -> dict[str, Any]:
        """返回内置目录 + 自定义连接器；GitHub 状态来自现有 GitHub 服务。"""
        items: list[dict[str, Any]] = []
        for builtin in _BUILTIN_CONNECTORS:
            if builtin["id"] == "github":
                items.append(_github_item(github_status))
            else:
                items.append(dict(builtin))
        items.extend(item.public() for item in self._load())
        return {"items": items}

    def list_custom(self) -> list[dict[str, Any]]:
        return [item.public() for item in self._load()]

    # --- 新增 / 探测 / 删除 ---

    async def add(self, name: str, url: str) -> dict[str, Any]:
        """先探测，通过才保存；失败抛 ValidationError（带可读原因）。"""
        clean_name = validate_connector_name(name)
        clean_url = validate_connector_url(url)
        records = self._load()
        if any(item.name.lower() == clean_name.lower() for item in records):
            raise ConflictError(f"已经有一个同名连接器：{clean_name}")
        result = await self._prober.probe(clean_url)
        if not result.ok:
            # 不落盘：失败不能表现为「已连接」。
            raise ValidationError(
                f"连接器测试失败：{result.error}",
                details={"url": clean_url, "reason": result.error, "probe": result.public()},
            )
        now = _now_iso()
        record = _StoredConnector(
            id=uuid.uuid4().hex,
            name=clean_name,
            url=clean_url,
            status=_STATUS_CONNECTED,
            tools=list(result.tools),
            error="",
            created_at=now,
            last_checked_at=now,
        )
        records.append(record)
        self._save(records)
        logger.info(
            "自定义 MCP 连接器已添加 name=%s url=%s tools=%d",
            record.name,
            record.url,
            len(record.tools or []),
        )
        return record.public()

    async def probe(self, connector_id: str) -> dict[str, Any]:
        """重新探测已保存的连接器，并更新状态与工具清单。"""
        records = self._load()
        record = next((item for item in records if item.id == connector_id), None)
        if record is None:
            raise NotFoundError(
                f"连接器不存在：{connector_id}", details={"connector_id": connector_id}
            )
        result: McpProbeResult = await self._prober.probe(record.url)
        record.last_checked_at = _now_iso()
        if result.ok:
            record.status = _STATUS_CONNECTED
            record.tools = list(result.tools)
            record.error = ""
        else:
            record.status = _STATUS_ERROR
            record.error = result.error or "连接失败"
        self._save(records)
        logger.info(
            "自定义 MCP 连接器探测 connector=%s ok=%s tools=%d",
            record.name,
            result.ok,
            len(result.tools),
        )
        return record.public()

    def remove(self, connector_id: str) -> dict[str, Any]:
        records = self._load()
        kept = [item for item in records if item.id != connector_id]
        if len(kept) == len(records):
            raise NotFoundError(
                f"连接器不存在：{connector_id}", details={"connector_id": connector_id}
            )
        self._save(kept)
        logger.info("自定义 MCP 连接器已移除 connector=%s", connector_id)
        return {"removed": True, "id": connector_id}

    # --- 运行时注入 ---

    def servers(self) -> dict[str, dict[str, Any]]:
        """Run 注入视图：只返回已连接的自定义服务器（`{name: {url}}`）。

        失败（error）的连接器不注入，避免拖慢 Agent 启动；用户重新测试通过后自动恢复。
        """
        result: dict[str, dict[str, Any]] = {}
        for record in self._load():
            if record.status != _STATUS_CONNECTED:
                continue
            name = record.name.strip()
            if not name or name.lower() in _RESERVED_NAMES or name in result:
                continue
            result[name] = {"url": record.url}
        return result


__all__ = [
    "McpConnectorService",
    "validate_connector_name",
    "validate_connector_url",
]
