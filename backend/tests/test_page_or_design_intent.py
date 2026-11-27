"""「页面 / 设计稿 / 文档」分流纯函数测试。

重点不是覆盖率数字，而是中文正反例要扎实：
- 可交互 HTML / 可点击网页 / demo / 完整页面原型 → page；
- 视觉稿 / 画板 / 画布 / 改布局样式组件 → design；
- Markdown / 说明文档 → doc；
- 用户明确指定 → 按指定；
- 说不清 / 两类信号打起来 → clarify，不猜；
- 事后纠正 → 改做正确类型，并给出「另做、不静默转换」的说明。
"""

from __future__ import annotations

import pytest

from flux.core.agent_runtime.page_or_design import (
    CLARIFY_QUESTION,
    PageOrDesignIntent,
    classify_page_or_design,
    correction_guidance,
)

# ---------------------------------------------------------------------------
# 可交互页面：必须走 artifact.publish type=page，不能被做成设计画布
# ---------------------------------------------------------------------------

PAGE_CASES = [
    "做一个可交互的 HTML demo",
    "我要一个能点击的网页",
    "可以点的页面就行",
    "做个完整页面原型，能点按切换",
    "帮我做一个单页应用，点了有反应",
    "做一个网页小游戏",
    "我要 React 写的可交互原型",
    "用 HTML 写个登录 demo",
    "输出成一个能跑的 HTML 文件",
    "做一个 landing page",
    "我要一个 web app，能操作表单",
    "make an interactive HTML demo",
    "不要设计稿，我要可交互网页",
    "只要网页，不要画布",
    "别做设计稿，做个能点的 demo",
]


@pytest.mark.parametrize("text", PAGE_CASES)
def test_interactive_page_intent(text: str) -> None:
    decision = classify_page_or_design(text)
    assert decision.intent is PageOrDesignIntent.PAGE, decision
    assert decision.needs_clarification is False
    assert decision.artifact_type == "page"
    assert decision.tool_hint.startswith("artifact.publish")
    assert decision.delivery_label == "可交互页面"


# ---------------------------------------------------------------------------
# 设计稿：走 design.*，完成后 design.share
# ---------------------------------------------------------------------------

DESIGN_CASES = [
    "做一版视觉稿",
    "出一张高保真设计稿",
    "在画布上改一下布局",
    "在画板上调整位置",
    "我要设计稿，不要网页",
    "调整这个页面的样式和配色",
    "把这几个组件删掉",
    "改一下登录页的布局",
    "换一套配色",
    "把按钮的样式调大一点",
    "做一个高保真 mockup",
    "画布上的这个卡片再往左一点",
    "帮我设计一下这个页面的视觉",
    "改成设计稿",
    # 「网页」只是载体，交付物仍是设计稿。
    "做一个网页设计稿",
    "做一个网页的视觉稿",
    "网页改一下布局",
]


@pytest.mark.parametrize("text", DESIGN_CASES)
def test_design_intent(text: str) -> None:
    decision = classify_page_or_design(text)
    assert decision.intent is PageOrDesignIntent.DESIGN, decision
    assert decision.needs_clarification is False
    assert decision.artifact_type is None
    assert "design." in decision.tool_hint
    assert decision.delivery_label == "设计稿"


# ---------------------------------------------------------------------------
# Markdown 文档：仍走 artifact.publish type=doc
# ---------------------------------------------------------------------------

DOC_CASES = [
    "写一份 Markdown 说明文档",
    "整理成 markdown",
    "帮我写个 README",
    "写一篇需求文档",
    "输出技术文档",
    "做一份周报文档",
    "不要网页，写成文档",
    "用 Markdown 写使用说明",
]


@pytest.mark.parametrize("text", DOC_CASES)
def test_doc_intent(text: str) -> None:
    decision = classify_page_or_design(text)
    assert decision.intent is PageOrDesignIntent.DOC, decision
    assert decision.needs_clarification is False
    assert decision.artifact_type == "doc"
    assert decision.delivery_label == "Markdown 文档"


# ---------------------------------------------------------------------------
# 明确指定：用户点名工具 / 类型时按指定
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("type=page", PageOrDesignIntent.PAGE),
        ("按 type=page 来", PageOrDesignIntent.PAGE),
        ("发成页面", PageOrDesignIntent.PAGE),
        ("在设计工作室新建一份设计稿", PageOrDesignIntent.DESIGN),
        ("用设计工作室做", PageOrDesignIntent.DESIGN),
        ("design.create 建个画板", PageOrDesignIntent.DESIGN),
        ("design.share 发我", PageOrDesignIntent.DESIGN),
        ("type=doc", PageOrDesignIntent.DOC),
        ("发布成文档", PageOrDesignIntent.DOC),
        ("整理成 markdown", PageOrDesignIntent.DOC),
    ],
)
def test_explicit_choice_wins(text: str, intent: PageOrDesignIntent) -> None:
    decision = classify_page_or_design(text)
    assert decision.intent is intent, decision
    assert decision.specified is True


def test_explicit_design_wins_even_when_message_mentions_interaction() -> None:
    """用户点名 design.create 时按指定，不在关键词层面擅自改成页面。"""
    decision = classify_page_or_design("用 design.create 做一个能点的 demo")
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert decision.specified is True


def test_bare_artifact_publish_is_not_assumed_to_be_page() -> None:
    """只说工具、不说类型时不能替用户猜 page / doc。"""
    decision = classify_page_or_design("用 artifact.publish 发一下")
    assert decision.intent is PageOrDesignIntent.CLARIFY


# ---------------------------------------------------------------------------
# 说不清：先问一句，而不是随便选一个
# ---------------------------------------------------------------------------

CLARIFY_CASES = [
    "",
    "   ",
    "做个登录页",
    "帮我设计一下",
    "做个页面",
    "弄个原型",
    "做个好看的界面",
    "随便做个东西",
    "可交互的设计稿",  # 交互信号 + 设计稿，真冲突
    "html 和设计稿都要",
    "能点的网页和设计稿都要",  # 交互信号 + 设计稿，真冲突
]


@pytest.mark.parametrize("text", CLARIFY_CASES)
def test_unclear_intent_needs_clarification(text: str) -> None:
    decision = classify_page_or_design(text)
    assert decision.intent is PageOrDesignIntent.CLARIFY, decision
    assert decision.needs_clarification is True
    assert decision.artifact_type is None
    assert decision.question == CLARIFY_QUESTION
    assert "可交互" in CLARIFY_QUESTION and "视觉稿" in CLARIFY_QUESTION


# ---------------------------------------------------------------------------
# 否定词：不能把「不要设计稿」也算成设计信号
# ---------------------------------------------------------------------------

def test_negated_design_signal_does_not_trigger_design() -> None:
    decision = classify_page_or_design("不要设计稿，我要能点击的网页")
    assert decision.intent is PageOrDesignIntent.PAGE
    assert "设计稿" not in decision.matched


def test_negated_page_signal_does_not_trigger_page() -> None:
    decision = classify_page_or_design("不是网页，我要设计稿")
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert "网页" not in decision.matched


def test_only_negation_without_target_still_clarifies() -> None:
    decision = classify_page_or_design("不是设计稿", previous=PageOrDesignIntent.DESIGN)
    assert decision.intent is PageOrDesignIntent.CLARIFY
    assert decision.correction is False


# ---------------------------------------------------------------------------
# 事后纠正：另做正确类型，不静默转换
# ---------------------------------------------------------------------------

def test_correct_design_to_interactive_page() -> None:
    decision = classify_page_or_design(
        "我要的是网页 / demo，不是设计稿",
        previous=PageOrDesignIntent.DESIGN,
    )
    assert decision.intent is PageOrDesignIntent.PAGE
    assert decision.correction is True
    assert decision.corrected_from is PageOrDesignIntent.DESIGN
    guidance = correction_guidance(decision)
    assert guidance is not None
    assert "之前是设计稿" in guidance
    assert "另做" in guidance
    assert "artifact.publish" in guidance
    assert "静默转换" in guidance


def test_correct_page_to_design() -> None:
    decision = classify_page_or_design(
        "不是网页，我要的是设计稿",
        previous=PageOrDesignIntent.PAGE,
    )
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert decision.correction is True
    assert decision.corrected_from is PageOrDesignIntent.PAGE
    guidance = correction_guidance(decision)
    assert guidance is not None
    assert "之前是可交互页面" in guidance
    assert "这次另做的是设计稿" in guidance
    assert "design.share" in guidance


def test_correct_via_explicit_tool_choice() -> None:
    decision = classify_page_or_design("做成设计稿", previous=PageOrDesignIntent.PAGE)
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert decision.specified is True
    assert decision.correction is True
    assert decision.corrected_from is PageOrDesignIntent.PAGE


def test_correct_with_redo_wording() -> None:
    decision = classify_page_or_design(
        "重做一份可交互 HTML",
        previous=PageOrDesignIntent.DESIGN,
    )
    assert decision.intent is PageOrDesignIntent.PAGE
    assert decision.correction is True


def test_reaffirming_same_type_is_not_a_correction() -> None:
    decision = classify_page_or_design("对，就要设计稿", previous=PageOrDesignIntent.DESIGN)
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert decision.correction is False
    assert correction_guidance(decision) is None


def test_first_message_is_not_a_correction() -> None:
    decision = classify_page_or_design("我要的是网页")
    assert decision.intent is PageOrDesignIntent.PAGE
    assert decision.correction is False
    assert decision.corrected_from is None


def test_same_type_after_previous_is_not_a_correction() -> None:
    decision = classify_page_or_design("我要的是网页", previous=PageOrDesignIntent.PAGE)
    assert decision.intent is PageOrDesignIntent.PAGE
    assert decision.correction is False


def test_previous_clarify_never_counts_as_correction() -> None:
    decision = classify_page_or_design(
        "改成设计稿",
        previous=PageOrDesignIntent.CLARIFY,
    )
    assert decision.intent is PageOrDesignIntent.DESIGN
    assert decision.correction is False
    assert decision.corrected_from is None


def test_correction_guidance_is_none_for_normal_decisions() -> None:
    assert correction_guidance(classify_page_or_design("做个可交互网页")) is None


# ---------------------------------------------------------------------------
# 常驻说明的语义锁：两条互斥路径必须同时存在，且不再一刀切
# ---------------------------------------------------------------------------

def test_resident_instructions_route_both_ways() -> None:
    from flux.core.agent_runtime.protocol import RUNTIME_BOOTSTRAP
    from flux.core.mcp.server import SERVER_INSTRUCTIONS

    # 两条路径都写清楚，而不是只说设计工作室。
    for text in (RUNTIME_BOOTSTRAP, SERVER_INSTRUCTIONS):
        assert "artifact.publish" in text
        assert "design.share" in text
    assert "type=page" in RUNTIME_BOOTSTRAP
    assert "type=doc" in RUNTIME_BOOTSTRAP
    assert "type=page" in SERVER_INSTRUCTIONS
    assert "type=doc" in SERVER_INSTRUCTIONS
    # 旧的「所有页面 / UI 都必须进设计工作室、禁止 HTML」不再出现。
    assert "For any page, UI or visual design task" not in RUNTIME_BOOTSTRAP
    assert "必须用 design.* 工具在设计工作室产出" not in SERVER_INSTRUCTIONS
    # 说不清时要问一句。
    assert "ask once" in RUNTIME_BOOTSTRAP
    assert "先问一句" in SERVER_INSTRUCTIONS


def test_mode_constraints_route_both_ways() -> None:
    from flux.core.agent_runtime.runtimes.service import _mode_constraints

    for mode in ("normal", "full", "unattended"):
        joined = "\n".join(_mode_constraints(mode))
        assert "artifact.publish" in joined, mode
        assert "design.share" in joined, mode
        assert "type=page" in joined, mode
        assert "type=doc" in joined, mode
        assert "不要猜" in joined, mode
        # 不再强制所有设计 / 页面都走 design.*。
        assert "设计任务仍必须用 design.*" not in joined, mode
