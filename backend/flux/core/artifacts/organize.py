# ruff: noqa: E501
"""把手机分享 / 相册里的附件整理成 Markdown 文档或可交互页面。

这是 **flux-server 的服务端能力**：不启动电脑上的 Agent，因此电脑关机、
但 flux-server 仍可达时也能完成。

模型能力说明：当前的 ``ChatMessage.content`` 是纯字符串，供应商适配器没有
把本地图片交给视觉模型的能力。因此：

- 纯文本附件：优先交给已有的 ModelRouter 整理；
- 含图片：不假装看过图，生成一份带图片嵌入 / 文件清单 / 用户说明 / 占位说明的
  文档或页面，并诚实地告诉用户「未使用视觉模型，已先保存为文档 / 页面，可再编辑」；
- 模型不可用或失败：同样退回确定性模板，不让整个功能失败。
"""

from __future__ import annotations

import base64
import html
import mimetypes
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.core.artifacts.repository import ArtifactRepository
from flux.core.attachment_store import UploadedFile, size_label
from flux.core.model_gateway.base import ChatMessage
from flux.core.model_gateway.router import ModelRouter
from flux.logging import get_logger
from flux.models.artifact import Artifact

logger = get_logger(__name__)

__all__ = [
    "OrganizeResult",
    "artifact_card_payload",
    "organize_uploads_as_artifact",
]

#: 内嵌进正文的图片原始字节上限。再大就只列文件名，避免正文超过 16 MiB 上限。
MAX_EMBED_IMAGE_BYTES = 6 * 1024 * 1024
#: 交给模型整理的单个文本附件最多读多少字符。
MAX_TEXT_CHARS = 24_000

_TEXT_EXTENSIONS = {
    ".txt",
    ".md",
    ".markdown",
    ".csv",
    ".json",
    ".yaml",
    ".yml",
    ".xml",
    ".html",
    ".htm",
    ".log",
}

_DOC_SYSTEM = """你是 Flux 的文档整理助手。用户把文件分享进来，希望你整理成一份
Markdown 文档。只输出 Markdown 正文，不要代码围栏，不要解释你的过程。
要求：标题清晰，按原文结构归纳，保留关键数字与结论；不要编造原文没有的内容。
"""

_PAGE_SYSTEM = """你是 Flux 的页面整理助手。用户把文件分享进来，希望你整理成一个
单文件 HTML 页面（可交互、可点按，但不是复杂的 Web 应用）。只输出完整 HTML，
不要代码围栏，不要解释。使用内联 CSS，不要外链脚本；标题清晰，信息可读。
"""


@dataclass(frozen=True)
class OrganizeResult:
    """一次整理的结果与诚实说明。"""

    artifact: Artifact
    #: 是否真的用了视觉模型看图片（当前恒为 False）。
    used_vision: bool = False
    #: 是否调用了文本模型。
    used_model: bool = False
    #: 给用户看的一句说明。
    note: str = ""

    def to_dict(self) -> dict[str, Any]:
        data = self.artifact.to_dict(include_content=False)
        data.update(
            {
                "used_vision": self.used_vision,
                "used_model": self.used_model,
                "note": self.note,
            }
        )
        return data


def artifact_card_payload(artifact: Artifact) -> dict[str, Any]:
    """对话里的 artifact_card 消息载荷（与 artifact.publish 同口径）。"""
    data = artifact.to_dict(include_content=False)
    return {
        "artifact_id": data["id"],
        "type": data["type"],
        "title": data["title"],
        "summary": data["summary"],
        "description": data.get("description") or "",
        "icon": data.get("icon") or "",
        "label": data.get("label") or "",
        "pinned": bool(data.get("pinned")),
        "version": data["version"],
        "metadata": data.get("metadata") or {},
    }


def _read_bytes(file: UploadedFile) -> bytes | None:
    try:
        return Path(file.path).read_bytes()
    except OSError:
        logger.warning("整理附件时读不到文件 upload=%s path=%s", file.id, file.path)
        return None


def _is_image(file: UploadedFile) -> bool:
    if file.mime.lower().startswith("image/"):
        return True
    suffix = Path(file.name).suffix.lower()
    return suffix in {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif"}


def _text_of(file: UploadedFile, data: bytes | None) -> str | None:
    if data is None:
        return None
    mime = file.mime.lower()
    suffix = Path(file.name).suffix.lower()
    if not (mime.startswith("text/") or suffix in _TEXT_EXTENSIONS):
        return None
    try:
        return data.decode("utf-8", errors="replace")[:MAX_TEXT_CHARS]
    except Exception:  # noqa: BLE001 - 解码失败就当二进制处理，不影响整理
        return None


def _image_data_uri(file: UploadedFile, data: bytes | None) -> str | None:
    if data is None or len(data) > MAX_EMBED_IMAGE_BYTES:
        return None
    mime = file.mime or mimetypes.guess_type(file.name)[0] or "image/jpeg"
    if not mime.lower().startswith("image/"):
        mime = "image/jpeg"
    encoded = base64.b64encode(data).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def _default_title(files: Sequence[UploadedFile], instruction: str) -> str:
    first_line = next((line.strip() for line in instruction.splitlines() if line.strip()), "")
    if first_line:
        return first_line[:60]
    if files:
        stem = Path(files[0].name).stem.strip()
        if stem:
            return stem[:60]
    return "整理文档"


def _strip_fence(raw: str) -> str:
    text = (raw or "").strip()
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _fallback_note(has_images: bool, used_model: bool) -> str:
    if has_images:
        return "未使用视觉模型，已先保存为文档 / 页面，图片已保留，可再编辑。"
    if used_model:
        return "已用服务端模型整理文字内容，可再编辑。"
    return "服务端模型暂不可用，已先保存为文档 / 页面，可再编辑。"


def _render_doc(
    *,
    title: str,
    files: Sequence[UploadedFile],
    file_rows: Sequence[tuple[UploadedFile, bytes | None]],
    instruction: str,
    body: str | None,
) -> str:
    lines = [f"# {title}", ""]
    if instruction.strip():
        lines += [f"> {instruction.strip()}", ""]
    if body:
        lines += [body.strip(), "", "## 来源", ""]
        for file in files:
            lines.append(f"- {file.name}（{size_label(file.size)}）")
        lines.append("")
    else:
        lines += [
            "## 来源",
            "",
        ]
        for file in files:
            lines.append(f"- {file.name}（{size_label(file.size)}）")
        lines.append("")
        if instruction.strip():
            lines += ["## 用户说明", "", instruction.strip(), ""]
        for file, data in file_rows:
            if not _is_image(file):
                continue
            uri = _image_data_uri(file, data)
            lines.append(f"## {file.name}")
            lines.append("")
            if uri:
                lines.append(f"![{file.name}]({uri})")
            else:
                lines.append(f"图片「{file.name}」体积较大（{size_label(file.size)}），未内嵌。")
            lines.append("")
        lines += [
            "## 说明",
            "",
            "这份文档由 Flux 服务端先保存，未做视觉识别；图片内容尚未整理成文字，可再编辑。",
            "",
        ]
    return "\n".join(lines).strip() + "\n"


def _render_page(
    *,
    title: str,
    files: Sequence[UploadedFile],
    file_rows: Sequence[tuple[UploadedFile, bytes | None]],
    instruction: str,
    body: str | None,
) -> str:
    safe_title = html.escape(title)
    instruction_html = (
        f'<p class="note">{html.escape(instruction.strip())}</p>' if instruction.strip() else ""
    )
    if body:
        stripped = body.strip()
        head = stripped[:400].lower()
        if head.startswith("<!doctype") or "<html" in head:
            # 模型已经给了完整单文件 HTML：原样保存，不再套一层外壳。
            return stripped + "\n"
        content = stripped
    else:
        parts = [
            "<h1>" + safe_title + "</h1>",
            instruction_html,
            "<h2>来源</h2>",
            "<ul>",
        ]
        for file in files:
            parts.append(
                f"<li>{html.escape(file.name)}（{html.escape(size_label(file.size))}）</li>"
            )
        parts.append("</ul>")
        for file, data in file_rows:
            if not _is_image(file):
                continue
            uri = _image_data_uri(file, data)
            parts.append(f"<h2>{html.escape(file.name)}</h2>")
            if uri:
                parts.append(
                    f'<figure><img src="{uri}" alt="{html.escape(file.name)}">'
                    f"<figcaption>{html.escape(file.name)}</figcaption></figure>"
                )
            else:
                parts.append(
                    f"<p>图片「{html.escape(file.name)}」体积较大（"
                    f"{html.escape(size_label(file.size))}），未内嵌。</p>"
                )
        parts.append(
            '<h2>说明</h2><p class="note">这份页面由 Flux 服务端先保存，'
            "未做视觉识别；图片内容尚未整理成文字，可再编辑。</p>"
        )
        content = "\n".join(parts)
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{safe_title}</title>
<style>
:root {{ color-scheme: light dark; }}
body {{ margin: 0; background: #fff; color: #111; font: 16px/1.7 -apple-system, "Segoe UI", "PingFang SC", sans-serif; }}
main {{ max-width: 760px; margin: 0 auto; padding: 32px 20px 64px; }}
h1 {{ font-size: 28px; line-height: 1.3; margin: 0 0 12px; }}
h2 {{ font-size: 20px; margin: 28px 0 10px; }}
p.note {{ color: #666; }}
ul {{ padding-left: 20px; }}
figure {{ margin: 20px 0; }}
img {{ max-width: 100%; height: auto; border: 1px solid #e5e5e5; border-radius: 8px; }}
figcaption {{ color: #666; font-size: 14px; margin-top: 6px; }}
@media (prefers-color-scheme: dark) {{
  body {{ background: #111; color: #f5f5f5; }}
  p.note, figcaption {{ color: #aaa; }}
  img {{ border-color: #333; }}
}}
</style>
</head>
<body>
<main>
{content}
</main>
</body>
</html>
"""


async def organize_uploads_as_artifact(
    *,
    artifacts: ArtifactRepository,
    router: ModelRouter | None,
    files: Sequence[UploadedFile],
    target: str,
    instruction: str = "",
) -> OrganizeResult:
    """把附件整理成 ``doc`` 或 ``page`` 产物并落库。

    Args:
        artifacts: 产物仓储（调用方从容器取，便于测试注入）。
        router: 模型路由；None / 未配置时退回确定性模板。
        files: 已上传的附件（按用户顺序）。
        target: ``doc`` 或 ``page``。
        instruction: 用户附带的整理说明。

    Raises:
        ValidationError: target 非法或没有可用附件。
    """
    from flux.errors import ValidationError

    if target not in {"doc", "page"}:
        raise ValidationError("整理目标只能是文档或页面", details={"target": target})
    if not files:
        raise ValidationError("没有可整理的附件", details={"reason": "no_attachment"})

    file_rows = [(file, _read_bytes(file)) for file in files]
    has_images = any(_is_image(file) for file in files)
    text_parts: list[str] = []
    for file, data in file_rows:
        text = _text_of(file, data)
        if text:
            text_parts.append(f"## {file.name}\n\n{text}")

    # 当前没有视觉能力：含图片时不猜测内容，走确定性模板。
    body: str | None = None
    used_model = False
    if router is not None and not has_images and text_parts:
        system = _DOC_SYSTEM if target == "doc" else _PAGE_SYSTEM
        joined = "\n\n".join(text_parts)
        prompt = (
            f"用户说明：{instruction.strip() or '（没有额外说明，请按内容整理）'}\n\n"
            f"附件内容：\n\n{joined}"
        )
        try:
            result = await router.chat(
                [
                    ChatMessage(role="system", content=system),
                    ChatMessage(role="user", content=prompt),
                ]
            )
            candidate = _strip_fence(result.content)
            if candidate:
                body = candidate
                used_model = True
        except Exception as exc:  # noqa: BLE001 - 模型失败不能拖垮整理
            logger.warning("整理附件时模型不可用，改用模板：%s", exc)

    title = _default_title(files, instruction)
    # 模型正文自带标题时，不再重复加一级标题。
    if body and target == "doc" and not body.lstrip().startswith("#"):
        body = f"# {title}\n\n{body}"
    content = (
        _render_doc(
            title=title,
            files=files,
            file_rows=file_rows,
            instruction=instruction,
            body=body,
        )
        if target == "doc"
        else _render_page(
            title=title,
            files=files,
            file_rows=file_rows,
            instruction=instruction,
            body=body,
        )
    )
    note = _fallback_note(has_images, used_model)
    artifact = await artifacts.create(
        type=target,
        title=title,
        content=content,
        description=instruction.strip()[:200] or "由手机分享 / 相册整理",
        label="手机整理",
        meta={
            "source": "mobile-organize",
            "upload_ids": [file.id for file in files],
            "used_vision": False,
            "used_model": used_model,
            "note": note,
        },
    )
    return OrganizeResult(
        artifact=artifact,
        used_vision=False,
        used_model=used_model,
        note=note,
    )
