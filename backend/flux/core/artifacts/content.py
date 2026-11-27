"""Artifacts 正文与资产校验（与官方 Artifacts 协议对齐的最小集合）。

这里只做**纯函数**校验，不碰数据库、不碰磁盘：正文编码 / 体积 / 标题提取与
资产类型 / 大小限制都集中在一处，REST API 与 MCP 工具共用同一套判据。

对齐的是 Claude Code 2.1.323 的发布语义：
- 发布源只有 HTML（page）与 Markdown（doc）；icon 是浏览器标签图标词，不是新类型；
- 正文按 UTF-8 编码，体积上限 16 MiB；含替换字符 U+FFFD 视为解码失败；
- HTML 标题优先取正文前 8KB 的 <title>，参数 title 只在缺标签时补上，且不覆盖标签；
- label 是本次发布的短名（≤60 字符）；capabilities 是运行时能力声明，空对象表示清空。
"""

from __future__ import annotations

import html
import json
import mimetypes
import re
from typing import Any

from flux.errors import ValidationError

#: 单份正文（HTML / Markdown）的渲染体积上限，与官方一致。
MAX_CONTENT_BYTES = 16 * 1024 * 1024
#: 只在正文前 8KB 里找 <title>，避免把正文中段的同名标签当成页面标题。
HTML_TITLE_SCAN_CHARS = 8 * 1024

MAX_TITLE_CHARS = 160
MAX_DESCRIPTION_CHARS = 500
MAX_LABEL_CHARS = 60
MAX_ICON_CHARS = 32
MAX_CONTRACT_CHARS = 64
MAX_CAPABILITIES_BYTES = 64 * 1024

#: 单个资产文件上限（图片 / 视频 / PDF / 字体 / CSS / JS / 文本）。
ASSET_MAX_FILE_BYTES = 16 * 1024 * 1024
#: 一个产物最多挂多少个资产，以及资产库总字节上限。
ASSET_MAX_FILES = 50
ASSET_MAX_TOTAL_BYTES = 64 * 1024 * 1024

#: 对外说明的类型清单（REST /artifacts/types 与 MCP artifact.list_types 共用）。
TYPE_DESCRIPTIONS: tuple[dict[str, Any], ...] = (
    {
        "type": "page",
        "label": "页面",
        "description": "单文件 HTML 页面；标题优先取正文前 8KB 的 <title>。",
        "stored": True,
        "create_via": "POST /api/v1/artifacts",
    },
    {
        "type": "doc",
        "label": "文档",
        "description": "Markdown 文档；缺标题时用首个 Markdown 标题回退。",
        "stored": True,
        "create_via": "POST /api/v1/artifacts",
    },
    {
        "type": "design",
        "label": "设计稿",
        "description": "Luma 设计稿的只读聚合；不改名、不删除、不复制到 artifacts 表。",
        "stored": False,
        "create_via": "设计工作室（/api/v1/designs）",
    },
)

#: 允许挂进资产库的 MIME。官方资产库面向媒体、PDF、字体、样式、脚本与文本；
#: HTML 属于产物正文，不作为资产上传。
_ALLOWED_ASSET_PREFIXES = ("image/", "video/", "font/")
_ALLOWED_ASSET_TYPES = {
    "application/pdf",
    "application/javascript",
    "text/javascript",
    "text/css",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/csv",
    "application/json",
    "application/font-woff",
    "application/font-woff2",
    "application/vnd.ms-fontobject",
    "application/x-font-ttf",
    "application/x-font-otf",
}

_TITLE_RE = re.compile(r"<title\b[^>]*>(.*?)</title\s*>", re.IGNORECASE | re.DOTALL)
_MARKDOWN_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
_UNSAFE_ASSET_SUFFIX_RE = re.compile(r"[^a-z0-9.]+")


def _line_column(text: str, index: int) -> tuple[int, int]:
    line = text.count("\n", 0, index) + 1
    last_newline = text.rfind("\n", 0, index)
    column = index - last_newline
    return line, column


def validate_content(kind: str, content: str) -> None:
    """校验正文：UTF-8 可解码、无 U+FFFD、体积 ≤16 MiB，page 必须是非空文本。

    JSON 请求里的 content 已是 Python str，无法再区分「原始字节非法」与「真的写了
    替换字符」，因此统一按后者处理——两者对发布者而言都是编码坏了，拒绝后让他重发。
    """
    if not isinstance(content, str):
        raise ValidationError("正文必须是字符串", details={"param": "content"})
    if "\x00" in content:
        raise ValidationError(
            "正文包含 NUL 字节，不能作为 HTML / Markdown 发布",
            details={"param": "content", "reason": "binary_content"},
        )
    replacement = content.find(chr(0xFFFD))
    if replacement >= 0:
        line, column = _line_column(content, replacement)
        raise ValidationError(
            f"正文第 {line} 行第 {column} 列含替换字符 U+FFFD，请用 UTF-8 重新编码后再发布",
            details={
                "param": "content",
                "reason": "invalid_encoding",
                "position": {"line": line, "column": column},
            },
        )
    size = len(content.encode("utf-8"))
    if size > MAX_CONTENT_BYTES:
        raise ValidationError(
            f"正文体积 {size} 字节，超过 {MAX_CONTENT_BYTES // (1024 * 1024)} MiB 上限",
            details={"param": "content", "reason": "content_too_large", "bytes": size},
        )
    if kind == "page" and not content.strip():
        raise ValidationError(
            "page 需要非空的 HTML 正文", details={"param": "content", "reason": "empty_page"}
        )


def html_title(content: str) -> str | None:
    """取正文前 8KB 的 <title> 文本；没有就返回 None。"""
    match = _TITLE_RE.search(content[:HTML_TITLE_SCAN_CHARS])
    if match is None:
        return None
    title = html.unescape(match.group(1))
    title = " ".join(title.split())
    return title[:MAX_TITLE_CHARS] or None


def markdown_title(content: str) -> str | None:
    """Markdown 的首个一级/任意级标题，作为缺 title 时的合理回退。"""
    for line in content.splitlines()[:40]:
        match = _MARKDOWN_HEADING_RE.match(line)
        if match is not None:
            title = " ".join(match.group(1).split())
            if title:
                return title[:MAX_TITLE_CHARS]
    return None


def create_title(kind: str, content: str, title: str | None) -> str:
    """新建时的标题规则：HTML 的 <title> 优先，参数只补缺；doc 可用首个标题。"""
    if kind == "page":
        tagged = html_title(content)
        if tagged is not None:
            return tagged
    provided = (title or "").strip()
    if provided:
        return provided[:MAX_TITLE_CHARS]
    if kind == "doc":
        heading = markdown_title(content)
        if heading is not None:
            return heading
        return "未命名文档"
    return "未命名页面"


def update_title(
    kind: str,
    *,
    content: str,
    incoming_title: str | None,
    existing_title: str,
    content_changed: bool,
) -> str:
    """更新时的标题规则：HTML 已有 <title> 时，参数不得覆盖标签文本。"""
    if kind == "page":
        tagged = html_title(content)
        if tagged is not None:
            return tagged
    if incoming_title is not None:
        value = incoming_title.strip()
        if value:
            return value[:MAX_TITLE_CHARS]
    if content_changed and kind == "doc":
        # 更新正文但没给标题时保留原标题；新建才从首个 Markdown 标题回退。
        return existing_title
    return existing_title


def clean_description(value: Any) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValidationError("description 必须是字符串", details={"param": "description"})
    return value.strip()[:MAX_DESCRIPTION_CHARS]


def clean_icon(value: Any) -> str:
    """icon 是一个短通用词（chart / calendar / code 等），不是品牌名或 URL。"""
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValidationError("icon 必须是字符串", details={"param": "icon"})
    icon = value.strip().lower()
    if len(icon) > MAX_ICON_CHARS:
        raise ValidationError(f"icon 最多 {MAX_ICON_CHARS} 个字符", details={"param": "icon"})
    return icon


def clean_label(value: Any) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValidationError("label 必须是字符串", details={"param": "label"})
    label = value.strip()
    if len(label) > MAX_LABEL_CHARS:
        raise ValidationError(f"label 最多 {MAX_LABEL_CHARS} 个字符", details={"param": "label"})
    return label


def clean_contract(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValidationError("contract 必须是字符串", details={"param": "contract"})
    contract = value.strip()
    if not contract:
        return None
    if len(contract) > MAX_CONTRACT_CHARS:
        raise ValidationError(
            f"contract 最多 {MAX_CONTRACT_CHARS} 个字符", details={"param": "contract"}
        )
    return contract


def clean_capabilities(value: Any) -> dict[str, Any]:
    """能力声明必须是 JSON 对象；空对象表示清空，省略由调用方用 None 表示沿用。"""
    if value is None:
        raise ValidationError("capabilities 必须是 JSON 对象", details={"param": "capabilities"})
    if not isinstance(value, dict):
        raise ValidationError("capabilities 必须是 JSON 对象", details={"param": "capabilities"})
    try:
        rendered = json.dumps(value, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise ValidationError(
            "capabilities 必须是可序列化的 JSON 对象", details={"param": "capabilities"}
        ) from exc
    size = len(rendered.encode("utf-8"))
    if size > MAX_CAPABILITIES_BYTES:
        raise ValidationError(
            f"capabilities 最多 {MAX_CAPABILITIES_BYTES // 1024} KB",
            details={"param": "capabilities", "bytes": size},
        )
    return value


def asset_suffix(file_name: str) -> str:
    """从原始文件名里取一个安全的后缀（用于落盘），没有则返回空串。"""
    suffix = ""
    if "." in file_name:
        suffix = "." + file_name.rsplit(".", 1)[1].lower()
    suffix = _UNSAFE_ASSET_SUFFIX_RE.sub("", suffix)[:16]
    return suffix if suffix != "." else ""


def normalize_asset_type(file_name: str, declared: str | None) -> str:
    """确定资产的 MIME：优先客户端声明，缺失时按文件名猜；不认识就拒绝。"""
    value = (declared or "").split(";", 1)[0].strip().lower()
    if not value or value == "application/octet-stream":
        guessed, _ = mimetypes.guess_type(file_name)
        value = (guessed or "").lower()
    if not value:
        raise ValidationError(
            "无法判断资产类型，请提供 content_type",
            details={"param": "content_type", "reason": "missing_content_type"},
        )
    allowed = value in _ALLOWED_ASSET_TYPES or value.startswith(_ALLOWED_ASSET_PREFIXES)
    if not allowed:
        raise ValidationError(
            f"不支持把 {value} 挂进产物资产库；支持图片、视频、PDF、字体、CSS、JS 与文本",
            details={
                "param": "content_type",
                "reason": "unsupported_content_type",
                "content_type": value,
            },
        )
    return value


def validate_asset(file_name: str, content_type: str, data: bytes) -> str:
    """校验单个资产并返回规范化后的 MIME。"""
    if not file_name.strip():
        raise ValidationError("file_name 不能为空", details={"param": "file_name"})
    if not data:
        raise ValidationError("资产内容为空", details={"param": "content", "reason": "empty_file"})
    if len(data) > ASSET_MAX_FILE_BYTES:
        raise ValidationError(
            f"单个资产不能超过 {ASSET_MAX_FILE_BYTES // (1024 * 1024)} MiB",
            details={
                "param": "content",
                "reason": "file_too_large",
                "bytes": len(data),
                "max_bytes": ASSET_MAX_FILE_BYTES,
            },
        )
    return normalize_asset_type(file_name, content_type)


__all__ = [
    "ASSET_MAX_FILES",
    "ASSET_MAX_FILE_BYTES",
    "ASSET_MAX_TOTAL_BYTES",
    "HTML_TITLE_SCAN_CHARS",
    "MAX_CAPABILITIES_BYTES",
    "MAX_CONTENT_BYTES",
    "MAX_DESCRIPTION_CHARS",
    "MAX_ICON_CHARS",
    "MAX_LABEL_CHARS",
    "MAX_TITLE_CHARS",
    "TYPE_DESCRIPTIONS",
    "asset_suffix",
    "clean_capabilities",
    "clean_contract",
    "clean_description",
    "clean_icon",
    "clean_label",
    "create_title",
    "html_title",
    "markdown_title",
    "normalize_asset_type",
    "update_title",
    "validate_asset",
    "validate_content",
]
