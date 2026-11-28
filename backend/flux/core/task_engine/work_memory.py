"""每轮结束生成一张「工作记忆卡」，供下一轮执行 Agent 开工时先读。

Uni 范围（与产品确认过的口径一致）：
- 已改文件：执行轨迹里 write 类工具的路径（去重），外加提案统计；
- 结果与失败原因：Run 终态 + finish_reason + error；
- 已确认需求：任务上的需求确认六维度；
- 最终答复尾部：Agent 自己说的「还差什么」比平台猜的待办更可靠。

只写事实，不做推断；卡片有硬上限，避免长期对话把指令撑爆。
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from flux.core.context.redact import redact, redact_then_clip

#: 记忆卡总字符上限
MEMORY_CARD_CHARS = 1200
#: 本轮目标截断
MEMORY_GOAL_CHARS = 300
#: 最终答复尾部保留字符
MEMORY_RESPONSE_CHARS = 400
#: 错误尾部保留字符
MEMORY_ERROR_CHARS = 300
#: 最多列多少个已改文件
MEMORY_MAX_PATHS = 15


def _clip(text: str, limit: int) -> str:
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    return text[: max(1, limit - 1)] + "…"


def _changed_paths(trace_rows: list[dict[str, Any]], changes: list[Any]) -> list[str]:
    paths: list[str] = []
    for row in trace_rows:
        if str(row.get("category") or "") != "write":
            continue
        for path in row.get("paths") or []:
            value = str(path).strip()
            if value and value not in paths:
                paths.append(value)
        if not row.get("paths"):
            # 取不到路径时用调用 id 兜底：至少证明发生过一次写操作
            call = str(row.get("call_id") or row.get("run_id") or "").strip()
            fallback = f"call:{call}" if call else ""
            if fallback and fallback not in paths:
                paths.append(fallback)
    for change in changes:
        path = str(getattr(change, "file_path", "") or "").strip()
        if path and path not in paths:
            paths.append(path)
    return paths[:MEMORY_MAX_PATHS]


def _count(trace_rows: list[dict[str, Any]], category: str) -> int:
    return sum(1 for row in trace_rows if str(row.get("category") or "") == category)


def _goal_of(instruction: str, fallback: str) -> str:
    """从执行指令里取「这一轮到底要做什么」。

    指令开头是稳定约束（平台身份/权限），直接截前 300 字会把约束当目标；
    优先取「本轮任务」段，其次取初次执行的「任务」段。
    """
    text = instruction or ""
    for marker in ("## 本轮任务", "## 任务"):
        start = text.find(marker)
        if start < 0:
            continue
        body = text[start + len(marker) :]
        end = body.find("\n## ")
        body = (body[:end] if end >= 0 else body).strip(" \n:-")
        if body:
            return body
    # 初次执行的指令没有「任务」小标题：约束段之后、平台身份之前的那一段就是任务；
    # 计划/附件/确认段都以小标题或列表开头，倒序跳过即可。
    body = text.split("## 平台身份")[0]
    paragraphs = [part.strip() for part in body.split("\n\n") if part.strip()]
    for paragraph in reversed(paragraphs):
        if paragraph.startswith("#"):
            continue
        if all(line.lstrip().startswith(("-", "*")) for line in paragraph.splitlines()):
            continue
        return paragraph
    return text or fallback


def _confirmation_lines(
    confirmation: dict[str, Any] | None, literals: Sequence[str] = ()
) -> list[str]:
    """把需求确认项拼成卡片行；label / value 同样可能复述密钥，先按清单打码。

    确认项原文留在任务 JSON 上（不改已落库原文）；这里只处理**派生给下一轮读的
    卡片文本**，与 goal / error / 答复同一口径。
    """
    items = (confirmation or {}).get("items")
    if not isinstance(items, list):
        return []
    lines = []
    for item in items:
        if not isinstance(item, dict):
            continue
        label = redact(str(item.get("label") or "").strip(), literals=literals).text
        value = redact(str(item.get("value") or "").strip(), literals=literals).text
        if label and value:
            lines.append(f"{label}：{value}")
    return lines


def build_memory_card(
    *,
    task: Any,
    run: Any | None = None,
    instruction: str = "",
    final_response: str = "",
    status: str = "",
    trace_rows: list[dict[str, Any]] | None = None,
    changes: list[Any] | None = None,
    confirmation: dict[str, Any] | None = None,
    literals: Sequence[str] = (),
) -> str:
    """拼一张工作记忆卡；没有任何事实时也至少说明轮次与结果。

    `literals` 是配置的可控脱敏字面量清单（`FLUX_REDACTION_LITERALS`）：默认空清单时
    只走内置规则（`sk-` / JWT / 熵检测），与既有行为一致；装配层会传入配置清单。
    """
    rows = list(trace_rows or [])
    change_rows = list(changes or [])
    if confirmation is None:
        confirmation = getattr(task, "confirmation", None)
    run_id = str(getattr(run, "id", "") or "")
    goal = _goal_of(
        instruction or str(getattr(run, "instruction", "") or ""),
        str(getattr(task, "description", "") or ""),
    )
    state = status or str(getattr(run, "status", "") or "")

    lines = ["## 上一轮工作记忆（平台自动生成，下一轮开工先读）"]
    if run_id:
        lines.append(f"- 轮次：run={run_id}")
    # 目标来自执行指令 / 任务描述，可能夹带密钥：先打码再截断
    goal_text = redact(goal, literals=literals).text
    lines.append(f"- 本轮目标：{_clip(goal_text, MEMORY_GOAL_CHARS)}")
    lines.append(f"- 结果：{state or '未知'}")
    finish_reason = str(getattr(run, "finish_reason", "") or "").strip()
    if finish_reason:
        # 结束原因也可能夹带密钥 / 配置字面量：卡片给下一轮读，先打码
        lines.append(f"- 结束原因：{redact(finish_reason, literals=literals).text}")
    error = str(getattr(run, "error", "") or "").strip()
    if error:
        # 错误里可能夹着密钥/令牌：先对完整原文打码再取尾部，避免跨边界漏片段
        lines.append(
            f"- 失败原因：{redact_then_clip(error, MEMORY_ERROR_CHARS, literals=literals)}"
        )

    paths = _changed_paths(rows, change_rows)
    if paths:
        lines.append(f"- 已改文件（{len(paths)}）：" + "、".join(paths))
    if change_rows:
        states: dict[str, int] = {}
        for change in change_rows:
            key = str(getattr(change, "status", "") or "unknown")
            states[key] = states.get(key, 0) + 1
        detail = "、".join(f"{key} {value} 条" for key, value in states.items())
        lines.append(f"- 提案：{len(change_rows)} 条（{detail}）")
    commands = _count(rows, "command")
    reads = _count(rows, "read")
    if commands or reads:
        lines.append(f"- 过程：执行命令 {commands} 条，读取文件 {reads} 次")

    confirmed = _confirmation_lines(confirmation, literals)
    if confirmed:
        lines.append("- 已确认需求：" + "；".join(confirmed))

    response = (final_response or "").strip()
    if response:
        # 答复同样可能复述密钥：先对完整原文打码，再取尾并归一空白
        redacted_response = redact(response, literals=literals).text
        tail = _clip(redacted_response[-MEMORY_RESPONSE_CHARS:], MEMORY_RESPONSE_CHARS)
        lines.append(f"- 上一轮最终答复（尾部）：{tail}")

    lines.append("- 下一轮先核对上面的事实与最终答复，不要重做已完成的步骤。")
    card = "\n".join(lines)
    if len(card) > MEMORY_CARD_CHARS:
        card = card[: MEMORY_CARD_CHARS - 1] + "…"
    return card


__all__ = ["MEMORY_CARD_CHARS", "build_memory_card"]
