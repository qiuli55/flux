"""「页面 / 设计稿 / 文档」分流判定（纯函数，可单测）。

目标只有一个：用户要**可交互 HTML 页面 / demo** 时，不要被做成设计工作室里
不能点的画布稿；用户要**画布上的视觉稿**时，也不要用一份手写 HTML 去冒充。

判定不依赖任何运行时、数据库或模型调用：输入一段用户原话，输出一个明确的
:class:`PageOrDesignIntent`。它用于两件事：

1. 锁住常驻说明（``SERVER_INSTRUCTIONS`` / ``RUNTIME_RULES`` / 模式约束）的语义——
   说明里必须同时给出两条互斥路径，并且说不清时允许问一句；
2. 需要时可在服务端 / MCP 侧做辅助校验或解释（例如把判定结果写进日志、在工具
   参数不明确时给出提示）。它**不是**端侧模型，也不假装理解整段对话。

规则（优先级从高到低）：

- 用户明确点名工具 / 类型（``artifact.publish``、``type=page``、``design.share`` 等）；
- 用户事后纠正（「我要的是网页 / demo」「我要的是设计稿」）——纠正只在已知上一版类型
  时标记 ``correction``，并给出另做正确类型的说明，绝不静默转换已有产物；
- 强信号：可交互 / 可点击 / HTML / demo / 网页 → page；设计稿 / 画板 / 画布 /
  改布局 / 改样式 / 组件 → design；Markdown / 说明文档 → doc；
- 两类强信号同时出现且没有被否定 → 需要澄清（不猜）；
- 只有「页面 / 原型 / 设计一下」这类弱信号 → 需要澄清；
- 空输入 → 需要澄清。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

__all__ = [
    "CLARIFY_QUESTION",
    "PageOrDesignDecision",
    "PageOrDesignIntent",
    "classify_page_or_design",
    "correction_guidance",
]


class PageOrDesignIntent(str, Enum):
    """用户想要的交付物类型。"""

    #: 可交互页面：``artifact.publish`` 的 ``type=page``（单文件 HTML）。
    PAGE = "page"
    #: 设计稿：设计工作室画布，走 ``design.*``，完成后 ``design.share``。
    DESIGN = "design"
    #: Markdown 文档：``artifact.publish`` 的 ``type=doc``。
    DOC = "doc"
    #: 说不清：先问一句，不要猜。
    CLARIFY = "clarify"

    @property
    def artifact_type(self) -> str | None:
        """对应 ``artifact.publish`` 的 type；设计稿不走 artifacts。"""
        if self is PageOrDesignIntent.PAGE:
            return "page"
        if self is PageOrDesignIntent.DOC:
            return "doc"
        return None

    @property
    def delivery_label(self) -> str:
        """交付时给用户看的诚实短句，避免含糊的「产物 / 作品」。"""
        return {
            PageOrDesignIntent.PAGE: "可交互页面",
            PageOrDesignIntent.DESIGN: "设计稿",
            PageOrDesignIntent.DOC: "Markdown 文档",
            PageOrDesignIntent.CLARIFY: "待确认",
        }[self]

    @property
    def tool_hint(self) -> str:
        """该类型应该走的工具路径。"""
        return {
            PageOrDesignIntent.PAGE: "artifact.publish（type=page）",
            PageOrDesignIntent.DESIGN: "design.*（完成后 design.share）",
            PageOrDesignIntent.DOC: "artifact.publish（type=doc）",
            PageOrDesignIntent.CLARIFY: "先问清再选工具",
        }[self]


@dataclass(frozen=True)
class PageOrDesignDecision:
    """一次分流判定的结果；全部字段都是可序列化的纯数据。"""

    intent: PageOrDesignIntent
    #: 给日志 / 调试看的一句判定理由。
    reason: str
    #: 命中的关键词或短语（已剔除被否定的命中）。
    matched: tuple[str, ...] = ()
    #: 用户是否明确点名了工具 / 类型。
    specified: bool = False
    #: 是否是事后纠正（上一版类型已知且这次改口）。
    correction: bool = False
    #: 纠正前是什么类型；非纠正时为 None。
    corrected_from: PageOrDesignIntent | None = None

    @property
    def needs_clarification(self) -> bool:
        return self.intent is PageOrDesignIntent.CLARIFY

    @property
    def artifact_type(self) -> str | None:
        return self.intent.artifact_type

    @property
    def delivery_label(self) -> str:
        return self.intent.delivery_label

    @property
    def tool_hint(self) -> str:
        return self.intent.tool_hint

    @property
    def question(self) -> str | None:
        """需要澄清时给用户的一句话；其余时候为 None。"""
        return CLARIFY_QUESTION if self.needs_clarification else None


CLARIFY_QUESTION = (
    "你要的是可交互、能点按的网页 / demo，还是设计画布上的视觉稿？我按你选的做，不猜。"
)

# ---------------------------------------------------------------------------
# 关键词表
# ---------------------------------------------------------------------------

#: 强 page 信号：出现其一就说明用户想要「能跑、能点」的东西。
_PAGE_STRONG: tuple[str, ...] = (
    "可交互",
    "可点击",
    "可以点击",
    "能点击",
    "可以点",
    "能点",
    "点得动",
    "能操作",
    "可操作",
    "交互式",
    "互动",
    "html",
    "h5",
    "单页应用",
    "single page",
    "single-page",
    "spa",
    "react",
    "vue",
    "demo",
    "interactive",
    "clickable",
    "web page",
    "webpage",
    "演示页面",
    "小游戏",
    "网页游戏",
    "网页",
    "网站",
    "web app",
    "webapp",
    "landing page",
    "页面原型",
    "完整页面",
    "能跑的页面",
    "可运行",
    "点了有反应",
)

#: page 组里真正说明「要能点 / 要跑起来」的信号。
#: 「网页 / 网站」只是载体名词：用户说「网页设计稿」时，设计稿才是交付物。
_PAGE_INTERACTION: tuple[str, ...] = (
    "可交互",
    "可点击",
    "可以点击",
    "能点击",
    "可以点",
    "能点",
    "点得动",
    "能操作",
    "可操作",
    "交互式",
    "互动",
    "html",
    "h5",
    "单页应用",
    "single page",
    "single-page",
    "spa",
    "react",
    "vue",
    "demo",
    "interactive",
    "clickable",
    "小游戏",
    "网页游戏",
    "点了有反应",
    "可运行",
    "能跑的页面",
)

#: 强 design 信号：视觉稿 / 画布 / 在画布上改布局样式组件。
_DESIGN_STRONG: tuple[str, ...] = (
    "设计稿",
    "设计图",
    "视觉稿",
    "视觉设计",
    "视觉",
    "ui稿",
    "ui 稿",
    "界面稿",
    "高保真",
    "画板",
    "画布",
    "设计工作室",
    "design studio",
    "luma",
    "mockup",
    "wireframe",
    "原型图",
    "设计规范",
    "样式稿",
    "配色稿",
    "改配色",
    "换颜色",
    "改颜色",
    "改布局",
    "调整布局",
    "排版",
    "布局和样式",
    "改样式",
    "调整样式",
    "加个组件",
    "加组件",
    "删组件",
    "改组件",
    "图层",
    "在画布上",
    "在画板上",
    "画布上改",
    "画板上改",
    "可编辑的设计",
    "设计文件",
)

#: 强 doc 信号：Markdown / 说明文档。
_DOC_STRONG: tuple[str, ...] = (
    "markdown",
    "md 文档",
    "md文档",
    ".md",
    "说明文档",
    "技术文档",
    "需求文档",
    "设计文档",
    "使用说明",
    "readme",
    "文档",
    "手册",
    "指南",
    "wiki",
    "笔记",
)

#: 弱信号：单独出现时不足以判定，必须问一句。
_WEAK_PAGE: tuple[str, ...] = (
    "页面",
    "原型",
    "界面",
    "ui设计",
    "登录页",
    "首页",
    "详情页",
    "表单",
    "小程序",
)

_WEAK_DESIGN: tuple[str, ...] = (
    "设计一下",
    "设计一个",
    "帮我设计",
    "做个设计",
    "设计风格",
    "美化",
    "好看一点",
    "样式",
    "布局",
    "组件",
    "配色",
)

#: 明确点名的工具 / 类型。命中即按用户指定执行，不再靠关键词猜。
_EXPLICIT_PAGE: tuple[str, ...] = (
    "type=page",
    "type 是 page",
    "type 为 page",
    "发布成页面",
    "发布为页面",
    "发成页面",
    "存成页面",
    "做成可交互",
    "用页面形式",
    "走页面",
)

_EXPLICIT_DESIGN: tuple[str, ...] = (
    "design.create",
    "design.share",
    "design.patch",
    "design.update",
    "设计工作室",
    "用 design",
    "走 design",
    "用设计工具",
    "设计稿形式",
    "做成设计稿",
    "画布形式",
)

_EXPLICIT_DOC: tuple[str, ...] = (
    "type=doc",
    "type 是 doc",
    "type 为 doc",
    "发布成文档",
    "发布为文档",
    "写成 markdown",
    "整理成 markdown",
    "做成文档",
    "用文档形式",
)

#: 否定词：出现在关键词前 6 个字符内（不跨标点）就把该次命中视为否定。
_NEGATORS: tuple[str, ...] = (
    "不要",
    "不用",
    "不需要",
    "不想",
    "别",
    "不是",
    "不做",
    "无需",
    "不必",
    "勿",
)

#: 改口信号。只有已知上一版类型时才把「改口 + 新类型」当成 correction。
_CORRECTION_CUES: tuple[str, ...] = (
    "不是",
    "不要",
    "别给",
    "别用",
    "改成",
    "换成",
    "做成",
    "另做",
    "重做",
    "我说的是",
    "我要的是",
    "其实",
    "搞错了",
    "弄错了",
)

#: 判定否定范围时只在这些标点处截断；空格不截断，否则「不是 设计稿」会丢掉否定。
_PUNCTUATION = "，。,.!?！？；;：:\n"

#: 「改一下这个页面的布局」这类动作 + 对象也算强 design 信号（中间允许若干字）。
_DESIGN_ACTION_RE = re.compile(
    r"(改|调整|调|换|重做|加|删|增|移)[^，。,.!?！？；;：:\n]{0,6}"
    r"(布局|样式|组件|配色|颜色|间距|字号)"
    r"|(布局|样式|组件|配色|颜色|间距|字号)[^，。,.!?！？；;：:\n]{0,4}"
    r"(改|调整|调|换|删|加|增|移)"
)


def _normalize(text: str) -> str:
    return " ".join(str(text or "").split()).lower()


def _negated_at(text: str, start: int) -> bool:
    """关键词的一次出现是否被前面的否定词否定（不跨标点）。"""
    prefix = text[max(0, start - 6) : start]
    # 只看到最近的标点为止，避免把上一句的「不」带进来。
    cut = max(prefix.rfind(ch) for ch in _PUNCTUATION)
    if cut >= 0:
        prefix = prefix[cut + 1 :]
    return any(negator in prefix for negator in _NEGATORS)


def _positive_hits(text: str, phrases: tuple[str, ...]) -> tuple[str, ...]:
    """返回文本里未被否定的短语命中（去重，保持短语表顺序）。"""
    hits: list[str] = []
    for phrase in phrases:
        start = text.find(phrase)
        while start != -1:
            if not _negated_at(text, start):
                hits.append(phrase)
                break
            start = text.find(phrase, start + 1)
    return tuple(hits)


def _explicit_intent(text: str) -> tuple[PageOrDesignIntent, tuple[str, ...]] | None:
    """用户明确点名工具 / 类型时直接返回指定类型。"""
    for intent, phrases in (
        (PageOrDesignIntent.DOC, _EXPLICIT_DOC),
        (PageOrDesignIntent.DESIGN, _EXPLICIT_DESIGN),
        (PageOrDesignIntent.PAGE, _EXPLICIT_PAGE),
    ):
        hits = _positive_hits(text, phrases)
        if hits:
            return intent, hits
    return None


def _has_correction_cue(text: str) -> bool:
    return any(cue in text for cue in _CORRECTION_CUES)


def _coerce_previous(previous: PageOrDesignIntent | str | None) -> PageOrDesignIntent | None:
    if previous is None:
        return None
    if isinstance(previous, PageOrDesignIntent):
        return previous
    try:
        return PageOrDesignIntent(str(previous).strip().lower())
    except ValueError:
        return None


def classify_page_or_design(
    message: str,
    *,
    previous: PageOrDesignIntent | str | None = None,
) -> PageOrDesignDecision:
    """把一段用户原话分流为 page / design / doc / clarify。

    Args:
        message: 用户原话（中文为主，兼容少量英文关键词）。
        previous: 已知的上一版交付类型。用户这次改口时用来标记 ``correction``，
            并说明「另做正确类型」，不会把已有产物静默转换。

    Returns:
        :class:`PageOrDesignDecision`；``intent=CLARIFY`` 时带一句可直接问用户的话。
    """
    text = _normalize(message)
    prev = _coerce_previous(previous)

    if not text:
        return PageOrDesignDecision(
            PageOrDesignIntent.CLARIFY,
            "用户没有说明要什么形态",
        )

    # 1) 明确点名工具 / 类型：按指定执行。
    explicit = _explicit_intent(text)
    if explicit is not None:
        intent, hits = explicit
        correction = (
            prev is not None
            and prev is not intent
            and prev is not PageOrDesignIntent.CLARIFY
            and _has_correction_cue(text)
        )
        if correction:
            return PageOrDesignDecision(
                intent,
                (
                    f"用户明确指定 {intent.tool_hint}，同时从{prev.delivery_label}改口；"
                    f"应另做正确类型，不要转换已有产物"
                ),
                matched=hits,
                specified=True,
                correction=True,
                corrected_from=prev,
            )
        return PageOrDesignDecision(
            intent,
            f"用户明确指定 {intent.tool_hint}",
            matched=hits,
            specified=True,
        )

    # 2) 收集三组未被否定的强信号。
    page_hits = _positive_hits(text, _PAGE_STRONG)
    design_hits = _positive_hits(text, _DESIGN_STRONG)
    doc_hits = _positive_hits(text, _DOC_STRONG)
    design_action = _DESIGN_ACTION_RE.search(text)
    if design_action is not None and not _negated_at(text, design_action.start()):
        design_hits = design_hits + (design_action.group(0),)

    nonzero = [
        (PageOrDesignIntent.PAGE, page_hits),
        (PageOrDesignIntent.DESIGN, design_hits),
        (PageOrDesignIntent.DOC, doc_hits),
    ]
    present = [(intent, hits) for intent, hits in nonzero if hits]

    # 3) 只有一组强信号：直接判定。
    if len(present) == 1:
        intent, hits = present[0]
        return _finalize(intent, hits, text, prev, reason_prefix="命中")

    # 4) 多组强信号：
    #    -「网页设计稿 / 网页的视觉稿 / 网页改布局」是「给网页做设计」，设计稿才是交付物；
    #    - 但一旦出现「可交互 / 能点 / HTML / demo」等交互信号，就是真冲突，先问不猜。
    if len(present) > 1:
        kinds = {intent for intent, _ in present}
        if kinds == {PageOrDesignIntent.PAGE, PageOrDesignIntent.DESIGN}:
            interaction = set(page_hits) & set(_PAGE_INTERACTION)
            if not interaction:
                return _finalize(
                    PageOrDesignIntent.DESIGN,
                    design_hits + page_hits,
                    text,
                    prev,
                    reason_prefix="用户说的是网页这种载体，但交付物明确是设计稿：命中",
                )
        names = " / ".join(intent.delivery_label for intent, _ in present)
        return PageOrDesignDecision(
            PageOrDesignIntent.CLARIFY,
            f"同时出现 {names} 的信号，无法确定交付物",
            matched=tuple(hit for _, hits in present for hit in hits),
        )

    # 5) 只有弱信号：仍然先问。
    weak_page = _positive_hits(text, _WEAK_PAGE)
    weak_design = _positive_hits(text, _WEAK_DESIGN)
    if weak_page or weak_design:
        hits = weak_page + weak_design
        return PageOrDesignDecision(
            PageOrDesignIntent.CLARIFY,
            "只提到页面 / 设计，但没说要不要可交互，无法确定交付物",
            matched=hits,
        )

    # 6) 什么都没命中。
    return PageOrDesignDecision(
        PageOrDesignIntent.CLARIFY,
        "没有识别到页面 / 设计稿 / 文档的类型信号",
    )


def _finalize(
    intent: PageOrDesignIntent,
    hits: tuple[str, ...],
    text: str,
    previous: PageOrDesignIntent | None,
    *,
    reason_prefix: str,
) -> PageOrDesignDecision:
    """给单一强信号补上纠正信息。"""
    correction = (
        previous is not None
        and previous is not intent
        and previous is not PageOrDesignIntent.CLARIFY
        and _has_correction_cue(text)
    )
    if correction:
        return PageOrDesignDecision(
            intent,
            (
                f"用户从{previous.delivery_label}纠正为{intent.delivery_label}；"
                f"应另做正确类型，不要转换已有产物"
            ),
            matched=hits,
            correction=True,
            corrected_from=previous,
        )
    return PageOrDesignDecision(
        intent,
        f"{reason_prefix} {intent.delivery_label} 信号",
        matched=hits,
    )


def correction_guidance(decision: PageOrDesignDecision) -> str | None:
    """纠正后交给 Agent 的诚实说明模板；非纠正返回 None。

    重点：**另做**正确类型，而不是把已有设计画布硬转成 HTML（或反过来），
    以免丢失原意图；回复里要明确说出前后两种交付物。
    """
    if not decision.correction or decision.corrected_from is None:
        return None
    was = decision.corrected_from.delivery_label
    now = decision.intent.delivery_label
    tool = decision.intent.tool_hint
    return (
        f"用户已纠正：之前交付的是{was}，这次要的是{now}。"
        f"请另做一份{now}（{tool}），不要试图把已有的{was}静默转换成{now}；"
        f"在回复里如实说明「之前是{was}，这次另做的是{now}」。"
    )
