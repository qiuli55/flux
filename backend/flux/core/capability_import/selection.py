"""本轮能力说明：已启用技能 + 用户指定项（纯格式化，便于单测）。

规则（与手机 / 桌面 / Web 的输入区一致）：

- 已启用的技能默认进入本轮指令，停用的不出现；
- 用户指定只影响这一轮：明确指出本轮优先使用哪些技能 / 连接器，
  但不改变「未指定时仍可自动使用」的默认行为；
- 技能说明文件（SKILL.md）在本机存在时把路径交给运行时，
  源目录不可用时如实标注，不假装已经读到。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from flux.models.imported_capability import ImportedCapability

#: 连接器状态 → 中文标签（与 /mcp-connectors 的 status 字段一致）
CONNECTOR_STATUS_LABELS: dict[str, str] = {
    "connected": "已连接",
    "disconnected": "未连接",
    "needs_reauth": "需要重新授权",
    "error": "连接不可用",
    "coming_soon": "暂不支持",
}


@dataclass(frozen=True)
class SkillBrief:
    """一条技能的运行时说明。"""

    name: str
    description: str = ""
    source: str = ""
    version: str | None = None
    enabled: bool = True

    @classmethod
    def from_record(cls, record: ImportedCapability) -> SkillBrief:
        spec = record.spec if isinstance(record.spec, dict) else {}
        description = spec.get("description")
        return cls(
            name=record.name,
            description=description.strip() if isinstance(description, str) else "",
            source=record.source or "",
            version=record.version,
            enabled=bool(record.enabled),
        )

    def instruction_line(self) -> str:
        text = f"- {self.name}"
        if self.description:
            text += f"：{self.description}"
        entry = Path(self.source) / "SKILL.md" if self.source else None
        if entry is not None and entry.is_file():
            text += f"（说明文件：{entry}）"
        elif self.source:
            text += "（源目录当前不可用，按已导入的说明执行）"
        return text


@dataclass(frozen=True)
class ConnectorBrief:
    """一条连接器的运行时说明。"""

    name: str
    status: str = "connected"
    available: bool = True

    @property
    def usable(self) -> bool:
        return self.available and self.status == "connected"

    def instruction_line(self) -> str:
        label = CONNECTOR_STATUS_LABELS.get(self.status, self.status or "未知状态")
        if self.usable:
            return f"- {self.name}（{label}）"
        return f"- {self.name}（当前不可用：{label}；不要假装已调用）"


def connector_brief(item: dict[str, Any]) -> ConnectorBrief:
    """把 /mcp-connectors 的列表项转成运行时说明。"""
    return ConnectorBrief(
        name=str(item.get("name") or item.get("id") or "连接器"),
        status=str(item.get("status") or "unknown"),
        available=bool(item.get("available", True)),
    )


def capability_instruction_sections(
    *,
    auto_skills: list[SkillBrief] | None = None,
    specified_skills: list[SkillBrief] | None = None,
    specified_connectors: list[ConnectorBrief] | None = None,
) -> list[str]:
    """拼出能力相关段落；没有任何能力时返回空列表，不占执行指令篇幅。"""
    lines: list[str] = []
    auto = [skill for skill in (auto_skills or []) if skill.enabled]
    if auto:
        lines += [
            "",
            "## 可用技能（已启用，按需使用）",
            "下面这些技能已经启用；只在任务确实需要时使用，不必逐个执行。",
            *[skill.instruction_line() for skill in auto],
        ]
    specified_skills = list(specified_skills or [])
    specified_connectors = list(specified_connectors or [])
    if specified_skills or specified_connectors:
        lines += [
            "",
            "## 本轮指定使用",
            "用户指定本轮优先使用下列能力；未列出的已启用技能与可用连接器仍可按需使用。",
        ]
        lines += [f"- 技能：{skill.instruction_line().removeprefix('- ')}"
                  for skill in specified_skills]
        lines += [f"- 连接器：{connector.instruction_line().removeprefix('- ')}"
                  for connector in specified_connectors]
    return lines


__all__ = [
    "CONNECTOR_STATUS_LABELS",
    "ConnectorBrief",
    "SkillBrief",
    "capability_instruction_sections",
    "connector_brief",
]
