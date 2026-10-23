"""Agent 可用模型目录（移动端对话页切换模型用）。

模型的权威来源是各 runtime 自己的配置，Flux 不臆造模型 id：

- `dsh`：内置 Runtime 只有一个配置项（`FLUX_DSH_MODEL`），列表即它自己；
- `codex`：若配置了 `FLUX_CODEX_MODEL_CATALOG`（codex 的 model_catalog_json），
  从该文件读 `models[].slug`；否则退回 `FLUX_CODEX_MODEL`；
- 其它 runtime：空列表（前端只显示当前模型，不提供切换）。

`POST /tasks/{id}/model` 用同一份目录做校验，避免"前端能选、后端跑不了"。
"""

from __future__ import annotations

import json
from pathlib import Path

from flux.config import Settings
from flux.core.agent_runtime.runtimes.base import (
    DEFAULT_RUNTIME,
    RUNTIME_CODEX,
    RUNTIME_DSH,
)


def _catalog_slugs(raw_path: str) -> list[str]:
    path_text = (raw_path or "").strip()
    if not path_text:
        return []
    path = Path(path_text).expanduser()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    entries = data.get("models") if isinstance(data, dict) else None
    if not isinstance(entries, list):
        return []
    slugs: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        slug = entry.get("slug")
        if isinstance(slug, str) and slug.strip() and slug.strip() not in slugs:
            slugs.append(slug.strip())
    return slugs


def models_for_runtime(settings: Settings, runtime: str | None) -> list[str]:
    """该 runtime 支持的模型 id 列表（顺序即展示顺序，第一项是默认值）。"""
    name = (runtime or DEFAULT_RUNTIME).strip().lower()
    if name == RUNTIME_DSH:
        model = (settings.dsh_model or "").strip()
        return [model] if model else []
    if name == RUNTIME_CODEX:
        configured = (settings.codex_model or "").strip()
        catalog = _catalog_slugs(settings.codex_model_catalog)
        if configured:
            return [configured, *(m for m in catalog if m != configured)]
        return catalog
    return []


def default_model_for_runtime(settings: Settings, runtime: str | None) -> str:
    """未设置任务级覆盖时该 runtime 实际会用的模型（空串 = runtime 自己的默认）。"""
    name = (runtime or DEFAULT_RUNTIME).strip().lower()
    if name == RUNTIME_DSH:
        return (settings.dsh_model or "").strip()
    if name == RUNTIME_CODEX:
        configured = (settings.codex_model or "").strip()
        if configured:
            return configured
        catalog = _catalog_slugs(settings.codex_model_catalog)
        return catalog[0] if catalog else ""
    return ""


__all__ = ["default_model_for_runtime", "models_for_runtime"]
