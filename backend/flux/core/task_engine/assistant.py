"""Solo 任务对话助手（平台侧助手）。

定位（目标架构 §1）：它不是 agent——平台不跑 agent loop。这里只提供平台自身的
对话能力：把用户的需求整理成「澄清结论 + 执行计划」，供任务执行中心呈现。
模型调用走 ModelRouter（平台内部模型通道），与 agent 自己的模型通道互不相干。

真实性约束：回复内容一律来自真实模型调用结果。模型没有按结构化协议输出时，
只落普通文本回复——不臆造澄清条目与计划步骤。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from flux.core.model_gateway.base import ChatMessage
from flux.core.model_gateway.router import ModelRouter
from flux.errors import ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

#: 需求确认的六个固定维度（文档 §4 P0-06）。顺序即展示顺序，模型给不出结论的维度落"待确认"，
#: 既不隐藏也不臆造——用户的下一步动作（补信息 / 直接执行）取决于看到真实缺口。
CONFIRMATION_DIMENSIONS: tuple[str, ...] = (
    "目标",
    "功能范围",
    "技术方案",
    "修改范围",
    "风险",
    "需人工审核的环节",
)

#: 某维度尚未有结论时的占位值（不是编造的内容，是"这里还没定"）。
CONFIRMATION_UNKNOWN = "待确认"

#: 结构化输出的协议说明。字段名与前端渲染一一对应：
#: questions → 需要用户补充的问题，confirmation → 需求确认六维度，steps → 执行计划步骤。
SYSTEM_PROMPT = """你是 Flux，一个 AI 软件工程平台的执行助手，正在与用户确认一项开发任务。

任务描述：{description}
{context}
把用户的需求整理清楚，并给出下一步执行计划。

只输出一个 JSON 对象（不要用 markdown 代码块包裹），字段如下：
- "reply"：字符串，给用户看的中文回复，1~3 句，说明你理解了什么、接下来做什么；
- "questions"：数组，仍需要用户补充的问题（每项是一句中文问句）；信息已经足够时给空数组。
  需求越不完整，越要先问清楚（登录方式、存储、权限、是否要注册等都算缺口），不要直接开始写代码；
- "confirmation"：数组，需求确认，每项 {{"label": "维度", "value": "结论"}}，
  label 必须且只能取这六个之一：{dimensions}。没有把握的维度把 value 写成
  "待确认"，不要编一个看起来合理的答案；
- "steps"：数组，执行计划，每项是简短的中文步骤标题（3~6 项）；信息不足以给出计划时给空数组。

不要编造事实：需求里没提到的技术细节一律进 "questions" 或把对应维度写成"待确认"。"""


@dataclass(frozen=True)
class AssistantTurn:
    """一轮助手回复：文本 + 结构化追问/需求确认/计划 + 真实模型调用元信息。"""

    reply: str
    questions: list[str] = field(default_factory=list)
    confirmation: list[dict[str, str]] = field(default_factory=list)
    steps: list[str] = field(default_factory=list)
    provider: str = ""
    model: str = ""
    usage: dict[str, int] = field(default_factory=dict)
    latency_ms: int = 0

    def payload(self) -> dict[str, Any]:
        """落库用的结构化负载：模型没有给出对应内容时键不落库（不落空壳）。"""
        data: dict[str, Any] = {
            "model": {
                "provider": self.provider,
                "model": self.model,
                "usage": self.usage,
                "latency_ms": self.latency_ms,
            }
        }
        if self.questions:
            data["questions"] = self.questions
        if self.confirmation:
            data["confirmation"] = self.confirmation
        if self.steps:
            data["steps"] = self.steps
        return data


class TaskAssistant:
    """把用户消息变成真实回复（含澄清结论与执行计划）。"""

    def __init__(self, router: ModelRouter, *, max_history: int = 20) -> None:
        self._router = router
        self._max_history = max_history

    async def respond(
        self,
        *,
        description: str,
        message: str,
        history: list[ChatMessage] | None = None,
        context: str | None = None,
    ) -> AssistantTurn:
        """调用模型生成一轮回复。history 为早前的对话（只含纯文本消息，升序）。"""
        system = SYSTEM_PROMPT.format(
            description=description,
            context=f"项目上下文（来自 Project Brain）：\n{context}\n" if context else "",
            dimensions="、".join(CONFIRMATION_DIMENSIONS),
        )
        messages = [ChatMessage(role="system", content=system)]
        messages.extend((history or [])[-self._max_history :])
        messages.append(ChatMessage(role="user", content=message))

        result = await self._router.chat(messages)
        reply, questions, confirmation, steps = parse_turn(result.content)
        logger.info(
            "Solo 助手回复 provider=%s model=%s questions=%d confirmation=%d steps=%d",
            result.provider,
            result.model,
            len(questions),
            len(confirmation),
            len(steps),
        )
        return AssistantTurn(
            reply=reply,
            questions=questions,
            confirmation=confirmation,
            steps=steps,
            provider=str(result.provider),
            model=result.model,
            usage={
                "input_tokens": result.usage.input_tokens,
                "output_tokens": result.usage.output_tokens,
                "total_tokens": result.usage.total_tokens,
            },
            latency_ms=result.latency_ms,
        )


def parse_turn(raw: str) -> tuple[str, list[str], list[dict[str, str]], list[str]]:
    """解析模型输出，返回 (reply, questions, confirmation, steps)。

    模型按协议给出 JSON 时取结构化字段；给不出（纯文本、残缺 JSON、字段类型不符）时
    退化为整段文本回复——宁可少两块结构化内容，也不把解析失败编成假数据。
    """
    text = (raw or "").strip()
    payload = _load_json_object(text)
    if payload is None:
        return text, [], [], []

    reply = payload.get("reply")
    if not isinstance(reply, str) or not reply.strip():
        # reply 是协议里的必填项；缺失说明这次输出整体不可信，整段原样返回
        return text, [], [], []

    questions: list[str] = []
    for item in payload.get("questions") or []:
        if isinstance(item, str) and item.strip():
            questions.append(item.strip())

    confirmation = normalize_confirmation(payload.get("confirmation"))
    steps: list[str] = []
    for item in payload.get("steps") or []:
        if isinstance(item, str) and item.strip():
            steps.append(item.strip())

    return reply.strip(), questions, confirmation, steps


def normalize_confirmation(raw: Any) -> list[dict[str, str]]:
    """把模型给的确认项规整成固定六维度、固定顺序。

    未知 label 一律丢弃（模型自由发挥的维度不进确认卡）；缺失维度补 `待确认`——
    "这里还没定"是事实，用户据此决定要不要补充信息。整块解析不出内容时返回空表，
    由调用方决定要不要展示确认卡。
    """
    if not isinstance(raw, list):
        return []
    found: dict[str, str] = {}
    for item in raw:
        if not isinstance(item, dict):
            continue
        label, value = item.get("label"), item.get("value")
        if not isinstance(label, str) or not isinstance(value, str):
            continue
        key = label.strip()
        text = value.strip()
        if key in CONFIRMATION_DIMENSIONS and text and key not in found:
            found[key] = text
    if not found:
        return []
    return [
        {"label": dimension, "value": found.get(dimension, CONFIRMATION_UNKNOWN)}
        for dimension in CONFIRMATION_DIMENSIONS
    ]


def validate_confirmation_items(raw: Any) -> list[dict[str, str]]:
    """校验用户改后的确认内容：必须恰好覆盖六个维度，label 不许自由发挥。

    与 `normalize_confirmation` 的宽松不同——那是容忍模型输出，这是校验用户输入：
    缺维度/多维度/重复维度一律拒绝，避免执行时用上一份"半张确认卡"。
    """
    if not isinstance(raw, list):
        raise ValidationError("confirmation.items 必须是数组", details={"field": "items"})
    items: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in raw:
        if not isinstance(item, dict):
            raise ValidationError("confirmation.items 每项必须是对象", details={"item": item})
        label, value = item.get("label"), item.get("value")
        if not isinstance(label, str) or label.strip() not in CONFIRMATION_DIMENSIONS:
            raise ValidationError(
                f"确认维度非法：{label!r}",
                details={"allowed": list(CONFIRMATION_DIMENSIONS)},
            )
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"确认维度的结论不能为空：{label}", details={"label": label})
        key = label.strip()
        if key in seen:
            raise ValidationError(f"确认维度重复：{key}", details={"label": key})
        seen.add(key)
        items.append({"label": key, "value": value.strip()})
    missing = [d for d in CONFIRMATION_DIMENSIONS if d not in seen]
    if missing:
        raise ValidationError(
            f"需求确认缺少维度：{'、'.join(missing)}", details={"missing": missing}
        )
    order = {dimension: index for index, dimension in enumerate(CONFIRMATION_DIMENSIONS)}
    return sorted(items, key=lambda item: order[item["label"]])


def _load_json_object(text: str) -> dict[str, Any] | None:
    """从模型输出里取出 JSON 对象：容忍 ``` 围栏与前后缀说明文字。"""
    candidate = text
    if candidate.startswith("```"):
        candidate = candidate.strip("`")
        # 去掉围栏后可能残留语言标注（json / JSON）
        first_line, _, rest = candidate.partition("\n")
        candidate = rest if first_line.strip().lower() in {"json", "json5"} else candidate
    start, end = candidate.find("{"), candidate.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        loaded = json.loads(candidate[start : end + 1])
    except json.JSONDecodeError:
        return None
    return loaded if isinstance(loaded, dict) else None
