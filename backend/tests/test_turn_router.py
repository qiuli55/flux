"""服务端分流判定：简单问题 / 整理附件 / 电脑 Agent。"""

from __future__ import annotations

import pytest

from flux.core.task_engine.turn_router import (
    ORGANIZE_CLARIFY_QUESTION,
    TurnReason,
    TurnRoute,
    decide_turn,
)


@pytest.mark.parametrize(
    "text",
    [
        "你好",
        "谢谢！",
        "什么是闭包？",
        "Python 怎么读取文件？",
        "刚才说的那个参数是什么意思",
        "解释一下这段原理",
        "REST 和 GraphQL 的区别",
        "能再说一遍吗",
        "为什么天空是蓝色的？",
        "how do I install a package?",
        "what is a monad",
        "ok",
    ],
)
def test_direct_questions_stay_on_the_server(text: str) -> None:
    decision = decide_turn(text)
    assert decision.route is TurnRoute.SERVER_ASSISTANT
    assert decision.needs_computer is False


@pytest.mark.parametrize(
    "text",
    [
        "帮我加一个按完成状态过滤的接口",
        "把登录页改成暗色",
        "跑一下测试",
        "部署到服务器",
        "打开项目里的配置文件看看",
        "这个仓库里有没有缓存实现",
        "实现邮箱修改",
        "给待办服务加一个按完成状态过滤的接口",
        "开始执行",
        "交给 Agent 处理",
        "continue from last time",
        "write a function to parse dates",
    ],
)
def test_execution_intent_goes_to_the_computer(text: str) -> None:
    decision = decide_turn(text)
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.needs_computer is True


def test_long_requirements_go_to_the_computer() -> None:
    text = "需求如下：\n1. 支持登录\n2. 支持注册\n3. 支持找回密码"
    decision = decide_turn(text)
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.reason is TurnReason.LONG_REQUEST


def test_attachment_without_organize_intent_goes_to_the_computer() -> None:
    decision = decide_turn("看看这个", attachment_count=1)
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.reason is TurnReason.ATTACHMENT


@pytest.mark.parametrize(
    "text",
    [
        "整理成文档",
        "帮我把这张照片整理成文档",
        "把这份分享整理为 Markdown",
        "存成文档",
        "汇总成一份说明文档",
    ],
)
def test_organize_doc_with_attachment(text: str) -> None:
    decision = decide_turn(text, attachment_count=1)
    assert decision.route is TurnRoute.ORGANIZE_DOCUMENT
    assert decision.organize_target == "doc"
    assert decision.needs_computer is False


@pytest.mark.parametrize(
    "text",
    [
        "整理成页面",
        "把这张图整理成网页",
        "做成一个可交互页面",
        "整理为 HTML 页面",
    ],
)
def test_organize_page_with_attachment(text: str) -> None:
    decision = decide_turn(text, attachment_count=1)
    assert decision.route is TurnRoute.ORGANIZE_PAGE
    assert decision.organize_target == "page"
    assert decision.needs_computer is False


def test_organize_without_target_asks_once() -> None:
    decision = decide_turn("帮我整理一下这张照片", attachment_count=1)
    assert decision.route is TurnRoute.CLARIFY
    assert decision.reason is TurnReason.ORGANIZE_NEEDS_TARGET


def test_organize_without_attachment_asks_for_one() -> None:
    decision = decide_turn("整理成文档")
    assert decision.route is TurnRoute.CLARIFY
    assert decision.reason is TurnReason.ORGANIZE_WITHOUT_ATTACHMENT


def test_both_targets_ask_instead_of_guessing() -> None:
    decision = decide_turn("整理成文档或页面都行", attachment_count=1)
    assert decision.route is TurnRoute.CLARIFY
    assert decision.reason is TurnReason.ORGANIZE_NEEDS_TARGET


def test_explicit_start_wins_over_organize() -> None:
    decision = decide_turn("整理成文档并开始执行", attachment_count=1, explicit_start=True)
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.explicit_start is True


def test_empty_message_asks_or_hands_attachment_to_agent() -> None:
    assert decide_turn("").route is TurnRoute.CLARIFY
    decision = decide_turn("", attachment_count=2)
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.reason is TurnReason.ATTACHMENT


def test_unknown_short_statement_is_conservative() -> None:
    decision = decide_turn("邮箱需要支持更换通知")
    assert decision.route is TurnRoute.COMPUTER_AGENT
    assert decision.reason is TurnReason.CONSERVATIVE


def test_clarify_question_is_honest_and_specific() -> None:
    assert "Markdown" in ORGANIZE_CLARIFY_QUESTION
    assert "页面" in ORGANIZE_CLARIFY_QUESTION
