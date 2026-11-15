"""Claude Code Adapter（外部 CLI Agent 的第四个一等运行时）。

探测规则来自 Claude Code 2.1.323 的真实行为（[2026-11-16 快照]）：
- `claude --version` → `2.1.323 (Claude Code)`（由版本正则抽出 2.1.323）
- `claude auth status --json` → `{"loggedIn": true, "authMethod": "api_key", ...}`

`loggedIn=false` 不一定真的不可用：用户可以把 `ANTHROPIC_API_KEY` /
`ANTHROPIC_AUTH_TOKEN` 写进 `~/.claude/settings.json` 的 `env`，或配置 `apiKeyHelper`，
CLI 启动时才解析这些配置。这里只读配置判断「有没有可用凭据」，绝不改动用户环境；
文件缺失 / 损坏一律按「没有」处理，不阻断扫描。
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from flux.core.agent_runtime.adapters.generic import GenericCliAdapter


def claude_config_dir() -> Path:
    """Claude Code 的配置目录（`CLAUDE_CONFIG_DIR` 优先，默认 `~/.claude`）。"""
    override = os.environ.get("CLAUDE_CONFIG_DIR", "").strip()
    return Path(override).expanduser() if override else Path.home() / ".claude"


def _settings_has_credentials() -> bool:
    """settings.json 里是否配置了可用的凭据来源（只读事实，不读取密钥值）。"""
    path = claude_config_dir() / "settings.json"
    try:
        data: Any = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    helper = data.get("apiKeyHelper")
    if isinstance(helper, str) and helper.strip():
        return True
    env = data.get("env")
    if not isinstance(env, dict):
        return False
    for key in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
        value = env.get(key)
        if isinstance(value, str) and value.strip():
            return True
    return False


class ClaudeCodeAdapter(GenericCliAdapter):
    def __init__(self, **overrides: object) -> None:
        config: dict[str, object] = {
            "name": "claude-code",
            "executable": "claude",
            "version_args": ("--version",),
            # 实际登录态以 JSON 字段判定（见 check_auth）；这里是探测命令本身
            "auth_args": ("auth", "status", "--json"),
            "capabilities": ("mcp", "stream"),
        }
        config.update(overrides)
        super().__init__(**config)  # type: ignore[arg-type]

    def check_auth(self) -> str:
        path = self._resolve_path()
        if path is None or self._auth_args is None:
            return "unknown"
        try:
            _, output = self._probe((path, *self._auth_args))
        except (OSError, TimeoutError):
            return "unknown"
        data = _parse_json_object(output)
        if data is None:
            return "unknown"
        logged_in = data.get("loggedIn")
        if logged_in is True:
            return "ok"
        if logged_in is False:
            # CLI 自己没登录，但用户在 settings.json 里配了凭据：启动时仍可用
            return "ok" if _settings_has_credentials() else "missing"
        return "unknown"


def _parse_json_object(output: str) -> dict[str, Any] | None:
    """解析探测输出里的 JSON 对象；允许命令在 JSON 前后打日志。"""
    text = (output or "").strip()
    if not text:
        return None
    try:
        data = json.loads(text)
    except ValueError:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            data = json.loads(text[start : end + 1])
        except ValueError:
            return None
    return data if isinstance(data, dict) else None


__all__ = ["ClaudeCodeAdapter", "claude_config_dir"]
