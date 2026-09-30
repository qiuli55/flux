"""Developer Agent：把用户需求转成可审阅的代码改动提案（实施计划 §3.1 ③）。

Agent **只产出提案，绝不碰用户真实文件**（主规格 §19.7 M2 产品原则）；
真正的落盘由后续的 Apply Engine 完成，中间必须经过人工审阅。
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

from flux.core.agent_runtime.manager import AgentManager
from flux.core.agent_runtime.manifest import AgentManifest, builtin_manifests
from flux.errors import ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

DEV_AGENT_NAME = "developer"

_FENCED_BLOCK = re.compile(r"```[a-zA-Z0-9_+-]*[ \t]*\r?\n(.*?)```", re.DOTALL)
_PREVIEW_CHARS = 300


@dataclass(frozen=True)
class FileChange:
    """一个文件的改动提案：path 为相对项目根的 POSIX 路径，content 为改动后的完整内容。"""

    path: str
    content: str
    reason: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {"path": self.path, "content": self.content, "reason": self.reason}


@dataclass(frozen=True)
class CodeChangeSet:
    """一次需求对应的一组文件改动（提案草稿，尚未落库为 Proposal）。"""

    summary: str
    changes: tuple[FileChange, ...]

    @property
    def paths(self) -> tuple[str, ...]:
        return tuple(change.path for change in self.changes)

    def to_dict(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "changes": [change.to_dict() for change in self.changes],
        }


def build_developer_prompt(instruction: str, files: Mapping[str, str]) -> str:
    """拼出 Developer Agent 的输入：需求 + 相关文件现状 + 机器可解析的输出契约。"""
    sections = ["## 需求", instruction.strip()]
    if files:
        sections += ["", "## 相关文件现状（改动前）"]
        for path, content in files.items():
            sections += [f"### {path}", "```", content.rstrip("\n"), "```"]
    sections += ["", "## 输出契约", _OUTPUT_CONTRACT]
    return "\n".join(sections)


_OUTPUT_CONTRACT = (
    "只输出一个 JSON 对象，不要输出任何解释文字：\n"
    "{\n"
    '  "summary": "用一两句话说明这次改动做了什么",\n'
    '  "changes": [\n'
    '    {"path": "相对项目根的 POSIX 路径", "content": "改动后该文件的完整内容",'
    ' "reason": "为什么这样改"}\n'
    "  ]\n"
    "}\n"
    "要求：content 必须是完整文件内容而不是 diff；新增文件同样要列出；"
    '路径禁止绝对路径与 ".."。'
)


def parse_code_change_set(raw: str, *, source: str = "developer") -> CodeChangeSet:
    """把模型返回的文本解析成 CodeChangeSet，任何不合契约的输出都视为失败。"""
    payload = _decode_payload(raw, source=source)
    if not isinstance(payload, Mapping):
        raise _invalid(source, "提案顶层必须是 JSON 对象", raw)

    summary = payload.get("summary", "")
    if not isinstance(summary, str):
        raise _invalid(source, "summary 必须是字符串", raw)

    raw_changes = payload.get("changes")
    if not isinstance(raw_changes, list) or not raw_changes:
        raise _invalid(source, "changes 必须是非空数组", raw)

    changes: list[FileChange] = []
    seen: set[str] = set()
    for index, item in enumerate(raw_changes):
        if not isinstance(item, Mapping):
            raise _invalid(source, f"changes[{index}] 必须是对象", raw)
        path = _require_path(item.get("path"), index, source, raw)
        if path in seen:
            raise _invalid(source, f"changes 中出现重复路径：{path}", raw)
        seen.add(path)
        content = item.get("content")
        if not isinstance(content, str):
            raise _invalid(source, f"changes[{index}].content 必须是字符串", raw)
        reason = item.get("reason", "")
        if not isinstance(reason, str):
            raise _invalid(source, f"changes[{index}].reason 必须是字符串", raw)
        changes.append(FileChange(path=path, content=content, reason=reason.strip()))

    return CodeChangeSet(summary=summary.strip(), changes=tuple(changes))


class DeveloperAgent:
    """Developer Agent 的入口：声明来自 Manifest，执行走 AgentManager 的真实运行时。"""

    def __init__(self, manager: AgentManager, manifest: AgentManifest | None = None) -> None:
        self._manager = manager
        self._manifest = manifest or builtin_manifests()[DEV_AGENT_NAME]
        self._handle = manager.create_from_manifest(self._manifest)

    @property
    def agent_id(self) -> str:
        return self._handle.id_str

    @property
    def manifest(self) -> AgentManifest:
        return self._manifest

    async def propose(
        self,
        instruction: str,
        *,
        files: Mapping[str, str] | None = None,
        task_id: str | None = None,
    ) -> CodeChangeSet:
        """根据需求与文件现状产出一份代码改动提案（不写任何文件）。"""
        prompt = build_developer_prompt(instruction, files or {})
        result = await self._manager.execute(self._handle.id_str, prompt, task_id=task_id)
        change_set = parse_code_change_set(result.content)
        logger.info(
            "developer.propose agent=%s task=%s files=%d",
            self.agent_id,
            task_id,
            len(change_set.changes),
        )
        return change_set


# --- 内部：容错解析 ---


def _decode_payload(raw: str, *, source: str) -> Any:
    """模型输出可能是纯 JSON、```json 围栏、或夹带说明文字，这里逐种尝试。"""
    text = raw.strip()
    candidates: list[str] = []
    if text.startswith(("{", "[")):
        candidates.append(text)
    candidates.extend(block.strip() for block in _FENCED_BLOCK.findall(raw))
    starts = [index for index in (text.find("{"), text.find("[")) if index != -1]
    end = max(text.rfind("}"), text.rfind("]"))
    if starts and end > min(starts):
        candidates.append(text[min(starts) : end + 1])

    tried: set[str] = set()
    for candidate in candidates:
        if not candidate or candidate in tried:
            continue
        tried.add(candidate)
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            continue
    raise ValidationError(
        "Developer Agent 未返回合法的 JSON 提案",
        details={"source": source, "raw_preview": _preview(raw)},
    )


def _require_path(raw: Any, index: int, source: str, full: str) -> str:
    if not isinstance(raw, str) or not raw.strip():
        raise _invalid(source, f"changes[{index}].path 必须是非空字符串", full)
    candidate = raw.strip().replace("\\", "/")
    pure = PurePosixPath(candidate)
    if candidate.startswith("/") or pure.is_absolute():
        raise _invalid(source, f"changes[{index}].path 不得是绝对路径：{raw}", full)
    if ".." in pure.parts:
        raise _invalid(source, f"changes[{index}].path 不得越出项目根：{raw}", full)
    if not pure.parts:
        raise _invalid(source, f"changes[{index}].path 非法：{raw}", full)
    # 归一化 a/./b、a//b 之类的写法，保证同一次提案里路径唯一可比
    return str(pure)


def _invalid(source: str, message: str, full: str) -> ValidationError:
    return ValidationError(
        f"Developer Agent 提案非法（{source}）：{message}",
        details={"raw_preview": _preview(full)},
    )


def _preview(text: str) -> str:
    text = text.strip()
    return text if len(text) <= _PREVIEW_CHARS else text[:_PREVIEW_CHARS] + "…"
