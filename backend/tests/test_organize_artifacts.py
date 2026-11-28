"""服务端「整理成文档 / 页面」：不启动电脑 Agent 也能走通。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pytest

from flux.core.artifacts.organize import organize_uploads_as_artifact
from flux.core.attachment_store import UploadedFile
from flux.errors import ValidationError


@dataclass
class _FakeArtifact:
    type: str
    title: str
    content: str
    description: str
    label: str
    meta: dict[str, Any]

    def to_dict(self, *, include_content: bool = True) -> dict[str, Any]:
        data = {
            "id": "artifact-1",
            "type": self.type,
            "title": self.title,
            "description": self.description,
            "icon": "",
            "label": self.label,
            "contract": None,
            "capabilities": {},
            "pinned": False,
            "version": 1,
            "metadata": self.meta,
            "summary": self.content.splitlines()[0][:80],
            "created_at": None,
            "updated_at": None,
        }
        if include_content:
            data["content"] = self.content
        return data


class _FakeArtifacts:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> _FakeArtifact:
        self.created.append(kwargs)
        return _FakeArtifact(
            type=kwargs["type"],
            title=kwargs["title"],
            content=kwargs["content"],
            description=kwargs.get("description") or "",
            label=kwargs.get("label") or "",
            meta=kwargs.get("meta") or {},
        )


class _FakeResult:
    def __init__(self, content: str) -> None:
        self.content = content


class _FakeRouter:
    def __init__(self, content: str = "# 整理结果\n\n正文", *, fail: bool = False) -> None:
        self.content = content
        self.fail = fail
        self.calls: list[list[Any]] = []

    async def chat(self, messages: list[Any], **kwargs: Any) -> _FakeResult:
        self.calls.append(messages)
        if self.fail:
            raise RuntimeError("model down")
        return _FakeResult(self.content)


def _image(tmp_path, name: str = "photo.png") -> UploadedFile:
    path = tmp_path / name
    path.write_bytes(b"\x89PNG\r\n\x1a\nfake-image-bytes")
    return UploadedFile(
        id="up-image",
        name=name,
        size=path.stat().st_size,
        mime="image/png",
        path=str(path),
    )


def _text(
    tmp_path, name: str = "notes.txt", text: str = "今天开会：先做登录，再做支付。"
) -> UploadedFile:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return UploadedFile(
        id="up-text",
        name=name,
        size=path.stat().st_size,
        mime="text/plain",
        path=str(path),
    )


@pytest.mark.asyncio
async def test_image_without_vision_becomes_doc_with_embedded_image(tmp_path) -> None:
    artifacts = _FakeArtifacts()
    result = await organize_uploads_as_artifact(
        artifacts=artifacts,  # type: ignore[arg-type]
        router=_FakeRouter(),
        files=[_image(tmp_path)],
        target="doc",
        instruction="整理成会议纪要",
    )
    assert result.artifact.type == "doc"
    assert result.used_vision is False
    assert result.used_model is False
    assert "未使用视觉模型" in result.note
    assert "data:image/png;base64," in result.artifact.content
    assert "photo.png" in result.artifact.content
    assert "整理成会议纪要" in result.artifact.content
    assert artifacts.created[0]["meta"]["used_vision"] is False


@pytest.mark.asyncio
async def test_image_becomes_page_with_interactive_html(tmp_path) -> None:
    artifacts = _FakeArtifacts()
    result = await organize_uploads_as_artifact(
        artifacts=artifacts,  # type: ignore[arg-type]
        router=None,
        files=[_image(tmp_path)],
        target="page",
    )
    assert result.artifact.type == "page"
    content = result.artifact.content
    assert content.lstrip().startswith("<!doctype html>")
    assert "<img" in content
    assert "data:image/png;base64," in content


@pytest.mark.asyncio
async def test_text_attachment_uses_model(tmp_path) -> None:
    artifacts = _FakeArtifacts()
    router = _FakeRouter("# 会议纪要\n\n- 先做登录")
    result = await organize_uploads_as_artifact(
        artifacts=artifacts,  # type: ignore[arg-type]
        router=router,  # type: ignore[arg-type]
        files=[_text(tmp_path)],
        target="doc",
    )
    assert result.used_model is True
    assert router.calls, "文本附件应交给模型整理"
    assert "# 会议纪要" in result.artifact.content
    assert "已用服务端模型整理文字内容" in result.note


@pytest.mark.asyncio
async def test_model_failure_falls_back_to_template(tmp_path) -> None:
    artifacts = _FakeArtifacts()
    result = await organize_uploads_as_artifact(
        artifacts=artifacts,  # type: ignore[arg-type]
        router=_FakeRouter(fail=True),  # type: ignore[arg-type]
        files=[_text(tmp_path)],
        target="doc",
    )
    assert result.used_model is False
    assert "服务端模型暂不可用" in result.note
    assert result.artifact.content.strip()


@pytest.mark.asyncio
async def test_empty_attachment_list_is_rejected() -> None:
    with pytest.raises(ValidationError):
        await organize_uploads_as_artifact(
            artifacts=_FakeArtifacts(),  # type: ignore[arg-type]
            router=None,
            files=[],
            target="doc",
        )


@pytest.mark.asyncio
async def test_unknown_target_is_rejected(tmp_path) -> None:
    with pytest.raises(ValidationError):
        await organize_uploads_as_artifact(
            artifacts=_FakeArtifacts(),  # type: ignore[arg-type]
            router=None,
            files=[_image(tmp_path)],
            target="design",
        )
