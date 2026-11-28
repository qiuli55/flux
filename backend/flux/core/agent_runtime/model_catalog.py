"""Agent 可用模型目录（对话页切换模型 / key 库模型选择用）。

模型的权威来源分两类：

1. 各 runtime 自己的原生配置，Flux 不臆造模型 id：
   - `dsh`：内置 Runtime 只有一个配置项（`FLUX_DSH_MODEL`），列表即它自己；
   - `codex`：若配置了 `FLUX_CODEX_MODEL_CATALOG`（codex 的 model_catalog_json），
     从该文件读 `models[].slug`；否则退回 `FLUX_CODEX_MODEL`；
   - `opencode`：读它自己的全局配置 `provider.<id>.models`，拼成 `<provider>/<model>`
     （用户配置的 Zen 免费模型因此能在手机/桌面端直接选）；
   - `claude-code`：优先 `FLUX_CLAUDE_CODE_MODEL`；否则读 Claude Code 自己的
     `~/.claude/settings.json` 顶层 `model` / `env.ANTHROPIC_MODEL`（只读模型 id，不读密钥）；
   - 其它 runtime：空列表（前端只显示当前模型，不提供切换）。
2. 密钥库（`api_keys`）里登记的 key + 模型；按 runtime 的协议兼容性过滤。

`POST /tasks/{id}/model`、`POST /tasks` 与 `POST /dsh/runs` 都用同一份目录做校验，
避免"前端能选、后端跑不了"。
"""

from __future__ import annotations

import json
import os
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from flux.config import Settings
from flux.core.agent_runtime.runtimes.base import (
    DEFAULT_RUNTIME,
    RUNTIME_CLAUDE_CODE,
    RUNTIME_CODEX,
    RUNTIME_DSH,
    RUNTIME_OPENCODE,
)

#: 密钥库允许的供应商类型（写库/校验用）
PROVIDER_DEEPSEEK = "deepseek"
PROVIDER_OPENAI = "openai"
PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
PROVIDER_ANTHROPIC = "anthropic"
KEY_PROVIDERS: tuple[str, ...] = (
    PROVIDER_DEEPSEEK,
    PROVIDER_OPENAI,
    PROVIDER_OPENAI_COMPATIBLE,
    PROVIDER_ANTHROPIC,
)

PROVIDER_TITLES: dict[str, str] = {
    PROVIDER_DEEPSEEK: "DeepSeek",
    PROVIDER_OPENAI: "OpenAI",
    PROVIDER_OPENAI_COMPATIBLE: "OpenAI 兼容",
    PROVIDER_ANTHROPIC: "Anthropic",
}

#: 各 runtime 能吃哪些供应商协议的 key。协议层不兼容的一律不展示、不接收。
_RUNTIME_PROVIDERS: dict[str, frozenset[str]] = {
    # dsh 的模型通道是 DeepSeekHarness（DEEPSEEK_BASE_URL / DEEPSEEK_API_KEY）
    RUNTIME_DSH: frozenset({PROVIDER_DEEPSEEK}),
    # codex 的第三方供应商块只讲 OpenAI wire api（responses / chat）
    RUNTIME_CODEX: frozenset({PROVIDER_DEEPSEEK, PROVIDER_OPENAI, PROVIDER_OPENAI_COMPATIBLE}),
    # opencode 原生支持多家供应商
    RUNTIME_OPENCODE: frozenset(KEY_PROVIDERS),
    # claude-code 只讲 Anthropic 协议（ANTHROPIC_API_KEY / ANTHROPIC_BASE_URL）
    RUNTIME_CLAUDE_CODE: frozenset({PROVIDER_ANTHROPIC}),
}

#: dsh 内部 provider 标识：只有 DeepSeek 官方协议走这个入口
_DSH_PROVIDER_BY_KEY_PROVIDER = {PROVIDER_DEEPSEEK: "deepseek-official"}


@dataclass(frozen=True, slots=True)
class KeyEntry:
    """密钥库里一条 key 的安全投影（不含明文/密文）。"""

    id: str
    name: str
    provider: str
    base_url: str
    wire_api: str
    models: tuple[str, ...]
    key_hint: str
    enabled: bool
    #: 每个登记模型在密钥库里定好的思考模式（缺省 = 老数据，按官方/通用规则预填）。
    model_thinking: Mapping[str, ThinkingSpec] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "provider": self.provider,
            "provider_title": PROVIDER_TITLES.get(self.provider, self.provider),
            "base_url": self.base_url,
            "wire_api": self.wire_api,
            "models": list(self.models),
            "key_hint": self.key_hint,
            "enabled": self.enabled,
            "model_thinking": {
                model: (
                    self.model_thinking.get(model) or default_thinking_spec(model)
                ).to_dict()
                for model in self.models
            },
        }


#: 思考控制的四种形态（前端按元数据渲染，不在客户端写死）
THINKING_NONE = "none"
THINKING_TOGGLE = "toggle"
THINKING_LEVELS = "levels"
THINKING_ALWAYS = "always"

#: 官方档位的中文展示名；前端只按后端返回的 levels 顺序渲染
THINKING_LEVEL_LABELS: dict[str, str] = {
    "none": "无思考",
    "minimal": "最低",
    "low": "低",
    "medium": "中",
    "high": "高",
    "max": "最高",
    "on": "开",
    "off": "关",
}

#: 对不上官方模型时的通用形态：无思考 + 5 档（界面共 6 项）。
GENERIC_THINKING_LEVELS: tuple[str, ...] = (
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "max",
)

#: 通道类型：订阅账号 / 密钥库 / runtime 原生配置
CHANNEL_SUBSCRIPTION = "subscription"
CHANNEL_KEY = "key"
CHANNEL_NATIVE = "native"


@dataclass(frozen=True, slots=True)
class ThinkingSpec:
    """一个模型的思考能力元数据。

    - `none`：不支持思考，前端不展示控制；
    - `toggle`：只能开/关；
    - `levels`：官方支持多档，`levels` 是官方档位顺序，`default` 是默认档；
    - `always`：始终思考、不能关闭，前端不提供 Off。
    """

    kind: str = THINKING_NONE
    levels: tuple[str, ...] = ()
    default: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "levels": list(self.levels),
            "labels": [THINKING_LEVEL_LABELS.get(item, item) for item in self.levels],
            "default": self.default,
            "can_disable": self.kind in (THINKING_TOGGLE, THINKING_LEVELS),
        }


@dataclass(frozen=True, slots=True)
class ModelMeta:
    """模型能力元数据：上下文长度、能力标签、思考控制。"""

    context_window: int | None = None
    capabilities: tuple[str, ...] = ()
    thinking: ThinkingSpec = ThinkingSpec()

    def to_dict(self) -> dict[str, Any]:
        return {
            "context_window": self.context_window,
            "capabilities": list(self.capabilities),
            "thinking": self.thinking.to_dict(),
        }


@dataclass(frozen=True, slots=True)
class SubscriptionAccount:
    """一个已登录的订阅账号（不包含任何令牌，只用于展示与选择）。"""

    account_id: str
    label: str
    vendor: str
    plan: str = ""
    detail: str = ""
    logged_in: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "account_id": self.account_id,
            "label": self.label,
            "vendor": self.vendor,
            "plan": self.plan,
            "detail": self.detail,
            "logged_in": self.logged_in,
        }


@dataclass(frozen=True, slots=True)
class ModelChannel:
    """选模页里的一个分栏：一个订阅账号 / 一把密钥 / runtime 原生配置。"""

    id: str
    kind: str
    label: str
    vendor: str = ""
    account_id: str = ""
    key_id: str = ""
    detail: str = ""
    enabled: bool = True
    #: 分栏 chip 用的短名（订阅账号名 / 密钥名）；列表来源仍用完整 [label]。
    chip_label: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "label": self.label,
            "vendor": self.vendor,
            "account_id": self.account_id,
            "key_id": self.key_id,
            "detail": self.detail,
            "enabled": self.enabled,
            "chip_label": self.chip_label or self.label,
        }


@dataclass(frozen=True, slots=True)
class CatalogModel:
    """选模列表里的一条模型：带通道归属与思考能力。"""

    id: str
    label: str
    channel_id: str
    channel_kind: str
    account_id: str = ""
    key_id: str = ""
    provider: str = ""
    context_window: int | None = None
    capabilities: tuple[str, ...] = ()
    thinking: ThinkingSpec = ThinkingSpec()
    channel_label: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "label": self.label,
            "channel_id": self.channel_id,
            "channel_kind": self.channel_kind,
            "channel_label": self.channel_label,
            "account_id": self.account_id,
            "key_id": self.key_id,
            "provider": self.provider,
            "context_window": self.context_window,
            "capabilities": list(self.capabilities),
            "thinking": self.thinking.to_dict(),
        }


@dataclass(frozen=True, slots=True)
class KeyModelOption:
    """key 库中一个可选模型（供前端/校验使用）。"""

    key_id: str
    key_name: str
    provider: str
    model: str
    channel_id: str = ""
    meta: ModelMeta = ModelMeta()

    def to_dict(self) -> dict[str, Any]:
        return {
            "key_id": self.key_id,
            "key_name": self.key_name,
            "provider": self.provider,
            "provider_title": PROVIDER_TITLES.get(self.provider, self.provider),
            "model": self.model,
            "channel_id": self.channel_id,
            "label": self.model,
            "context_window": self.meta.context_window,
            "capabilities": list(self.meta.capabilities),
            "thinking": self.meta.thinking.to_dict(),
        }


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


def _opencode_model_ids(raw_path: str) -> list[str]:
    """从 opencode 全局配置取用户已配置的模型 id（`<provider>/<model>`）。

    opencode 的模型清单由它自己的全局配置维护（provider 的 models 键，含 Zen 免费模型）；
    Flux 只读模型 id 并把顶层 `model`（opencode 的默认）置顶，供应商的 options/密钥一律不读。
    """
    path = Path((raw_path or "").strip()).expanduser()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    providers = data.get("provider") if isinstance(data, dict) else None
    if not isinstance(providers, dict):
        return []
    ids: list[str] = []
    for provider_id, provider in providers.items():
        models = provider.get("models") if isinstance(provider, dict) else None
        if not isinstance(models, dict):
            continue
        for model_id in models:
            if not isinstance(model_id, str) or not model_id.strip():
                continue
            full = f"{provider_id}/{model_id.strip()}"
            if full not in ids:
                ids.append(full)
    default = data.get("model") if isinstance(data, dict) else None
    if isinstance(default, str) and default.strip() in ids:
        ids.remove(default.strip())
        ids.insert(0, default.strip())
    return ids


def _claude_settings_model() -> str:
    """读 Claude Code 自己配置的默认模型（只读模型 id，绝不读 env 里的密钥）。"""
    override = os.environ.get("CLAUDE_CONFIG_DIR", "").strip()
    root = Path(override).expanduser() if override else Path.home() / ".claude"
    try:
        data = json.loads((root / "settings.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ""
    if not isinstance(data, dict):
        return ""
    model = data.get("model")
    if isinstance(model, str) and model.strip():
        return model.strip()
    env = data.get("env")
    if isinstance(env, dict):
        value = env.get("ANTHROPIC_MODEL")
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def models_for_runtime(settings: Settings, runtime: str | None) -> list[str]:
    """该 runtime 支持的原生模型 id 列表（顺序即展示顺序，第一项是默认值）。"""
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
    if name == RUNTIME_OPENCODE:
        # 模型清单由 opencode 全局配置持有；列表首项 = 它的默认模型。
        return _opencode_model_ids(settings.opencode_config_file)
    if name == RUNTIME_CLAUDE_CODE:
        configured = (settings.claude_code_model or "").strip()
        if configured:
            return [configured]
        model = _claude_settings_model()
        return [model] if model else []
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
    if name == RUNTIME_OPENCODE:
        models = _opencode_model_ids(settings.opencode_config_file)
        return models[0] if models else ""
    if name == RUNTIME_CLAUDE_CODE:
        return (settings.claude_code_model or "").strip() or _claude_settings_model()
    return ""


def supports_provider(runtime: str | None, provider: str) -> bool:
    """该 runtime 的协议层能否使用这个供应商的 key。"""
    name = (runtime or DEFAULT_RUNTIME).strip().lower()
    return provider in _RUNTIME_PROVIDERS.get(name, frozenset())


def dsh_provider_for(provider: str) -> str:
    """key 库供应商 → dsh 内部 provider 标识；不支持时返回空串。"""
    return _DSH_PROVIDER_BY_KEY_PROVIDER.get(provider, "")


def default_base_url(settings: Settings, provider: str) -> str:
    """key 未填 Base URL 时的默认地址（兼容端点必须自己填）。"""
    if provider == PROVIDER_DEEPSEEK:
        return (settings.deepseek_base_url or "").strip()
    if provider == PROVIDER_OPENAI:
        return (settings.openai_base_url or "").strip()
    if provider == PROVIDER_ANTHROPIC:
        return (settings.anthropic_base_url or "").strip()
    return ""


def canonical_model_id(model: str) -> str:
    """把各家/各中转的模型 id 收敛到可对齐官方模型的规范 id。

    - 去掉供应商前缀：`openai/gpt-5.5`、`anthropic/claude-sonnet-4.5` → 末段；
    - 去掉 OpenRouter 等目录的可选后缀：`:free` / `:extended`、`@2025-08-07`；
    - 小写并把下划线视作连字符。
    """
    value = (model or "").strip().lower()
    if not value:
        return ""
    value = value.rsplit("/", 1)[-1]
    value = value.split(":", 1)[0]
    value = value.split("@", 1)[0]
    return value.replace("_", "-")


# --- 官方 / 公共目录能力（唯一一张表，按规范模型 id 对齐） ---
#
# 这里只登记能对到官方模型的能力形态；对不上的一律走 GENERAL 通用形态。
# 不按中转站各建一张表，也不向模型发请求探测。OpenRouter 等大中转的 id 只要能
# 收敛到同一规范 id，就复用同一份能力。

_NO_THINKING = ThinkingSpec(kind=THINKING_NONE)
_ALWAYS_THINKING = ThinkingSpec(kind=THINKING_ALWAYS)
_TOGGLE_THINKING = ThinkingSpec(
    kind=THINKING_TOGGLE, levels=("on", "off"), default="on"
)
#: GPT-5 / GPT-6 / Codex 官方 reasoning effort 档位
_GPT_THINKING = ThinkingSpec(
    kind=THINKING_LEVELS,
    levels=("minimal", "low", "medium", "high"),
    default="medium",
)
#: o 系列官方档位（没有 minimal）
_O_SERIES_THINKING = ThinkingSpec(
    kind=THINKING_LEVELS,
    levels=("low", "medium", "high"),
    default="medium",
)
#: Claude 官方扩展思考只安全对应开 / 关；不把预算数字伪装成官方 5 档
_CLAUDE_THINKING = _TOGGLE_THINKING
_GEMINI_THINKING = ThinkingSpec(
    kind=THINKING_LEVELS, levels=("low", "high"), default="high"
)
#: DeepSeek 官方：无思考 + 低 / 高 / max（不是通用 5 档）
_DEEPSEEK_THINKING = ThinkingSpec(
    kind=THINKING_LEVELS, levels=("none", "low", "high", "max"), default="low"
)
_GENERIC_THINKING = ThinkingSpec(
    kind=THINKING_LEVELS,
    levels=GENERIC_THINKING_LEVELS,
    default="medium",
)


def _official_thinking(name: str) -> ThinkingSpec | None:
    """按规范模型 id 返回官方思考形态；对不上返回 None（由调用方落通用形态）。"""
    if not name:
        return None
    if "embed" in name:
        # 向量模型没有思考控制
        return _NO_THINKING
    if name.startswith(("gpt-5", "gpt-6", "gpt5", "gpt6", "codex")):
        return _GPT_THINKING
    if name.startswith(("o1", "o3", "o4")):
        return _O_SERIES_THINKING
    if name.startswith(("gpt-4", "gpt-3.5", "gpt3")):
        # GPT-4 代官方没有 reasoning effort
        return _NO_THINKING
    if name.startswith("claude") or name.startswith(("sonnet", "opus", "haiku")):
        if name.startswith("claude-3-") and not name.startswith("claude-3-7"):
            # Claude 3.5 及更早没有扩展思考；3.7 起才有开关
            return _NO_THINKING
        return _CLAUDE_THINKING
    if name.startswith("gemini"):
        return _GEMINI_THINKING
    if name.startswith("deepseek"):
        return _DEEPSEEK_THINKING
    if name.startswith(("kimi", "moonshot")):
        # K2 Thinking 是始终思考；其余 K 系列提供开关
        return _ALWAYS_THINKING if "thinking" in name else _TOGGLE_THINKING
    if name.startswith("grok"):
        if name.startswith("grok-4"):
            # Grok 4 官方始终推理，不提供关闭
            return _ALWAYS_THINKING
        if name.startswith("grok-3-mini"):
            return ThinkingSpec(
                kind=THINKING_LEVELS, levels=("low", "high"), default="low"
            )
        return _TOGGLE_THINKING
    if name.startswith("qwen"):
        if name.startswith("qwen2.5") or "coder" in name:
            return _NO_THINKING
        return _TOGGLE_THINKING
    if name.startswith(("llama", "mistral", "mixtral", "command", "phi")):
        return _NO_THINKING
    return None


def _thinking_of(name: str) -> ThinkingSpec:
    """规范 id 的思考形态：官方能对上就用官方，否则用通用「无思考 + 5 档」。"""
    return _official_thinking(canonical_model_id(name)) or _GENERIC_THINKING


def default_thinking_spec(model: str) -> ThinkingSpec:
    """密钥库新增模型时的预填形态（官方优先，对不上用通用 6 项）。"""
    return _official_thinking(canonical_model_id(model)) or _GENERIC_THINKING


def default_thinking_for_models(models: Iterable[str]) -> dict[str, ThinkingSpec]:
    """给一组登记模型补齐全默认思考模式（供安全输入等无思考 UI 的入口使用）。"""
    return {
        str(model).strip(): default_thinking_spec(str(model).strip())
        for model in models
        if str(model).strip()
    }


def thinking_spec_from_dict(raw: Any, model: str = "") -> ThinkingSpec:
    """把接口/库里的思考配置收敛成合法 ThinkingSpec；空值回落预填形态。"""
    if isinstance(raw, ThinkingSpec):
        return raw
    if not isinstance(raw, dict):
        return default_thinking_spec(model)
    kind = str(raw.get("kind") or "").strip().lower()
    if kind not in (THINKING_NONE, THINKING_TOGGLE, THINKING_LEVELS, THINKING_ALWAYS):
        return default_thinking_spec(model)
    levels = tuple(
        str(item).strip().lower()
        for item in raw.get("levels") or []
        if str(item).strip()
    )
    default = str(raw.get("default") or "").strip().lower()
    if kind == THINKING_LEVELS:
        if not levels:
            levels = GENERIC_THINKING_LEVELS
        if default not in levels:
            default = levels[0]
        return ThinkingSpec(kind=kind, levels=levels, default=default)
    if kind == THINKING_TOGGLE:
        if default not in ("on", "off"):
            default = "on"
        return ThinkingSpec(kind=kind, levels=("on", "off"), default=default)
    if kind == THINKING_ALWAYS:
        return ThinkingSpec(kind=kind, default="always")
    return ThinkingSpec(kind=THINKING_NONE)


def thinking_spec_to_dict(spec: ThinkingSpec) -> dict[str, Any]:
    """落库用的紧凑表示（展示名由后端 to_dict 现算，不存冗余文案）。"""
    return {
        "kind": spec.kind,
        "levels": list(spec.levels),
        "default": spec.default,
    }


def _context_window_of(name: str) -> int | None:
    if name.startswith("gemini"):
        return 1_000_000
    if name.startswith(("gpt-5", "gpt-6", "codex")):
        return 400_000
    if name.startswith(("claude", "sonnet", "opus", "haiku")):
        return 200_000
    if name.startswith("deepseek"):
        return 128_000
    if name.startswith("grok"):
        return 256_000
    if name.startswith(("kimi", "moonshot")):
        return 256_000
    if name.startswith("qwen"):
        return 128_000
    return None


def _capabilities_of(name: str, thinking: ThinkingSpec) -> tuple[str, ...]:
    labels: list[str] = []
    if thinking.kind != THINKING_NONE:
        labels.append("始终思考" if thinking.kind == THINKING_ALWAYS else "思考")
    if thinking.kind == THINKING_LEVELS and len(thinking.levels) > 1:
        labels.append("思考分档")
    if name.startswith("gemini"):
        labels.extend(["图片输入", "视频输入"])
    elif name.startswith(
        ("gpt-5", "gpt-6", "codex", "claude", "sonnet", "opus", "haiku", "qwen-vl")
    ):
        labels.append("图片输入")
    if name.startswith(
        (
            "gpt",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "gemini",
            "deepseek",
            "grok",
            "kimi",
            "moonshot",
            "qwen",
            "codex",
            "o1",
            "o3",
            "o4",
        )
    ):
        labels.append("工具调用")
    return tuple(labels)


def model_meta(model: str, thinking: ThinkingSpec | None = None) -> ModelMeta:
    """模型能力元数据（纯函数；前端只按这份数据渲染能力标签与思考控制）。

    `thinking` 非空时以它为准（密钥库里用户保存的思考模式优先于自动识别）。
    """
    name = canonical_model_id(model)
    spec = thinking if thinking is not None else _thinking_of(name)
    return ModelMeta(
        context_window=_context_window_of(name),
        capabilities=_capabilities_of(name, spec),
        thinking=spec,
    )


def thinking_effort(
    model: str, level: str, thinking: ThinkingSpec | None = None
) -> str:
    """把任务上的思考档解析成 runtime 能用的值；不适用时返回空串。

    - levels 模型：只接受官方档位里的值；`none` = 不传思考参数；
    - toggle 模型：on → `medium`，off → 空串（不传 = 模型默认）；
    - always / none：不接受用户档位。
    """
    value = (level or "").strip().lower()
    if not value:
        return ""
    spec = thinking if thinking is not None else _thinking_of(canonical_model_id(model))
    if spec.kind == THINKING_LEVELS:
        if value not in spec.levels or value in ("none", "off"):
            return ""
        return value
    if spec.kind == THINKING_TOGGLE:
        if value in ("on", "true", "enabled"):
            return "medium"
        return ""
    return ""


def default_thinking_level(model: str, thinking: ThinkingSpec | None = None) -> str:
    """模型切换后的可预期落点：该模型的默认档（无思考能力时为空串）。"""
    spec = thinking if thinking is not None else _thinking_of(canonical_model_id(model))
    if spec.kind in (THINKING_TOGGLE, THINKING_LEVELS):
        return spec.default
    if spec.kind == THINKING_ALWAYS:
        return "always"
    return ""


def _channel_id_for_key(key_id: str) -> str:
    return f"key:{key_id}"


def _channel_id_for_account(account_id: str) -> str:
    return f"subscription:{account_id}"


def _channel_id_for_native(runtime: str) -> str:
    return f"native:{runtime}"


def build_agent_catalog(
    settings: Settings,
    runtime: str | None,
    key_entries: Iterable[KeyEntry],
    accounts: Iterable[SubscriptionAccount] = (),
    *,
    native_models: Iterable[str] | None = None,
) -> tuple[list[ModelChannel], list[CatalogModel]]:
    """按**当前 Agent 的 runtime** 组装选模页的分栏与模型。

    - 订阅分栏：调用方传入的、该 runtime 已登录可用的账号（每个账号一栏）；
    - 密钥分栏：密钥库里对该 runtime 协议兼容、且已启用的每一把 key（一 key 一栏）；
    - 原生分栏：该 runtime 自己的模型配置；有订阅账号时并入账号分栏，避免重复。
    """
    name = (runtime or DEFAULT_RUNTIME).strip().lower()
    models = (
        list(native_models) if native_models is not None else models_for_runtime(settings, name)
    )
    account_list = [item for item in accounts if item.logged_in]
    channels: list[ModelChannel] = []
    catalog: list[CatalogModel] = []

    def add_models(
        channel: ModelChannel,
        model_ids: Iterable[str],
        *,
        provider: str = "",
        thinking_by_model: Mapping[str, ThinkingSpec] | None = None,
    ) -> None:
        seen: set[str] = set()
        for model in model_ids:
            model_id = (model or "").strip()
            if not model_id or model_id in seen:
                continue
            seen.add(model_id)
            meta = model_meta(model_id, (thinking_by_model or {}).get(model_id))
            catalog.append(
                CatalogModel(
                    id=model_id,
                    label=model_id,
                    channel_id=channel.id,
                    channel_kind=channel.kind,
                    account_id=channel.account_id,
                    key_id=channel.key_id,
                    provider=provider,
                    context_window=meta.context_window,
                    capabilities=meta.capabilities,
                    thinking=meta.thinking,
                    channel_label=channel.label,
                )
            )

    if account_list:
        for account in account_list:
            vendor = account.vendor.strip() or _runtime_title(name)
            channel = ModelChannel(
                id=_channel_id_for_account(account.account_id),
                kind=CHANNEL_SUBSCRIPTION,
                # 列表来源：`Kimi 订阅 · qiuli`；chip 只显示账号名。
                label=f"{vendor} 订阅 · {account.label}",
                vendor=vendor,
                account_id=account.account_id,
                detail=account.detail or account.plan,
                chip_label=account.label,
            )
            channels.append(channel)
            add_models(channel, models)
    elif models:
        channel = ModelChannel(
            id=_channel_id_for_native(name),
            kind=CHANNEL_NATIVE,
            label="本机默认",
            vendor=_runtime_title(name),
            chip_label="本机默认",
        )
        channels.append(channel)
        add_models(channel, models)

    for entry in key_entries:
        if not entry.enabled or not supports_provider(name, entry.provider):
            continue
        if not entry.models:
            continue
        channel = ModelChannel(
            id=_channel_id_for_key(entry.id),
            kind=CHANNEL_KEY,
            label=f"密钥库 · {entry.name}",
            vendor=PROVIDER_TITLES.get(entry.provider, entry.provider),
            key_id=entry.id,
            detail=entry.key_hint,
            chip_label=entry.name,
        )
        channels.append(channel)
        add_models(
            channel,
            entry.models,
            provider=entry.provider,
            thinking_by_model=entry.model_thinking,
        )

    return channels, catalog


def _runtime_title(runtime: str) -> str:
    return {
        RUNTIME_CODEX: "Codex",
        RUNTIME_CLAUDE_CODE: "Claude Code",
        RUNTIME_OPENCODE: "OpenCode",
        RUNTIME_DSH: "DSH",
    }.get(runtime, runtime)


def vault_models_for_runtime(
    entries: Iterable[KeyEntry],
    runtime: str | None,
    *,
    enabled_only: bool = True,
) -> list[KeyModelOption]:
    """把密钥库条目摊平成该 runtime 可选的模型列表（供应商协议不兼容的跳过）。"""
    options: list[KeyModelOption] = []
    for entry in entries:
        if enabled_only and not entry.enabled:
            continue
        if not supports_provider(runtime, entry.provider):
            continue
        channel_id = _channel_id_for_key(entry.id)
        for model in entry.models:
            options.append(
                KeyModelOption(
                    key_id=entry.id,
                    key_name=entry.name,
                    provider=entry.provider,
                    model=model,
                    channel_id=channel_id,
                    meta=model_meta(model, entry.model_thinking.get(model)),
                )
            )
    return options


__all__ = [
    "CHANNEL_KEY",
    "CHANNEL_NATIVE",
    "CHANNEL_SUBSCRIPTION",
    "GENERIC_THINKING_LEVELS",
    "KEY_PROVIDERS",
    "PROVIDER_ANTHROPIC",
    "PROVIDER_DEEPSEEK",
    "PROVIDER_OPENAI",
    "PROVIDER_OPENAI_COMPATIBLE",
    "PROVIDER_TITLES",
    "THINKING_ALWAYS",
    "THINKING_LEVELS",
    "THINKING_LEVEL_LABELS",
    "THINKING_NONE",
    "THINKING_TOGGLE",
    "CatalogModel",
    "KeyEntry",
    "KeyModelOption",
    "ModelChannel",
    "ModelMeta",
    "SubscriptionAccount",
    "ThinkingSpec",
    "build_agent_catalog",
    "canonical_model_id",
    "default_base_url",
    "default_model_for_runtime",
    "default_thinking_for_models",
    "default_thinking_level",
    "default_thinking_spec",
    "dsh_provider_for",
    "model_meta",
    "models_for_runtime",
    "supports_provider",
    "thinking_effort",
    "thinking_spec_from_dict",
    "thinking_spec_to_dict",
    "vault_models_for_runtime",
]
