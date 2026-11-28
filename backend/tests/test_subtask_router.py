"""主助理分流纯函数：大量中文正反例。

产品口径：主助理只快聊；执行需求派子任务；换用/更换走替换；再开一个走并行。
说不清时宁可派子任务。分流在服务端做，客户端不做权威判定。
"""

from __future__ import annotations

import pytest

from flux.core.task_engine.subtask_router import RouteAction, route_message


@pytest.mark.parametrize(
    "text",
    [
        "你好",
        "在吗",
        "谢谢你",
        "好的，收到",
        "你是谁？",
        "你能做什么",
        "我想想",
        "嗯",
        "ok",
        "不用了，谢谢",
    ],
)
def test_small_talk_stays_on_fast_path(text: str) -> None:
    assert route_message(text).action is RouteAction.CHAT


@pytest.mark.parametrize(
    "text",
    [
        "现在进度怎么样了？",
        "任务到哪了",
        "那个子任务完成了吗",
        "做到哪一步了",
        "汇报一下当前状态",
        "还要多久",
        "卡在哪了",
        "总结一下现在的结果",
        "有什么进展吗",
    ],
)
def test_progress_questions_stay_on_fast_path(text: str) -> None:
    assert route_message(text).action is RouteAction.CHAT


@pytest.mark.parametrize(
    "text",
    [
        "帮我修一下登录页的报错",
        "实现一个邮箱修改接口",
        "把这段代码改成异步",
        "跑一下测试",
        "在终端里执行 npm install",
        "部署到测试环境",
        "帮我写一个爬虫脚本",
        "做一个项目进度页面",
        "查一下 React 19 有什么新特性并整理成文档",
        "登录接口一直 500，排查一下",
        "把首页背景改成浅灰色",
        "优化一下这个查询",
        "帮我弄一下这个报错",
        "搞定它",
        "处理一下刚才那个问题",
        "给我生成一份接口说明",
        "接着做上次没做完的部分",
    ],
)
def test_execution_requests_dispatch(text: str) -> None:
    assert route_message(text).action is RouteAction.DISPATCH


@pytest.mark.parametrize(
    "text",
    [
        "换用 Codex 来做",
        "更换成 reviewer",
        "改用另一个 agent 继续",
        "换成 claude 做",
        "把这个停掉换新的",
        "替换成 builder 跑",
        "重新开一个换人做",
    ],
)
def test_replace_requests(text: str) -> None:
    decision = route_message(text, has_active_subtask=True)
    assert decision.action is RouteAction.REPLACE


@pytest.mark.parametrize(
    "text",
    [
        "再开一个",
        "另外开一个并行做",
        "再派一个任务",
        "同时开两个",
        "再来一个",
        "多开一个吧",
    ],
)
def test_add_requests(text: str) -> None:
    assert route_message(text).action is RouteAction.ADD


def test_replace_extracts_agent_hint() -> None:
    decision = route_message("换用 Codex 来做", has_active_subtask=True)
    assert decision.action is RouteAction.REPLACE
    assert decision.agent_hint == "Codex"


def test_replace_extracts_model_hint() -> None:
    decision = route_message("换成 gpt-5 继续做", has_active_subtask=True)
    assert decision.action is RouteAction.REPLACE
    assert decision.model_hint == "gpt-5"


def test_uncertain_long_text_prefers_subtask() -> None:
    decision = route_message("这块我也不太确定，你先看看怎么弄比较合适，弄完告诉我")
    assert decision.action is RouteAction.DISPATCH


def test_question_without_execution_stays_chat() -> None:
    assert route_message("为什么要先做登录？").action is RouteAction.CHAT


def test_project_assistant_cannot_dispatch() -> None:
    decision = route_message("帮我改一下代码", allow_dispatch=False)
    assert decision.action is RouteAction.CHAT
    assert "项目助理" in decision.reason


def test_project_assistant_replace_falls_back_to_chat() -> None:
    decision = route_message("换用 Codex 来做", allow_dispatch=False)
    assert decision.action is RouteAction.CHAT


def test_empty_message_is_chat() -> None:
    assert route_message("   ").action is RouteAction.CHAT
