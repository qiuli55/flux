"""主助理消息分流（服务端权威，纯函数）。

产品决定（2026-11-28）：主助理是低延迟调度员——只负责聊天、澄清、汇报与调度，
真正干活交给临时子任务。分流必须在服务端完成，手机端不做权威判定。

这个模块只做「这句话该走哪条路」的纯函数判断，不碰数据库、不调模型，
因此可以覆盖大量中文正反例做单测。说不清时宁可派子任务，也不要让主助理自己干活。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from flux.enums import StrEnum


class RouteAction(StrEnum):
    """主助理收到一句话后的动作。"""

    #: 快聊：闲聊、澄清、问进度、解释结果——走现有 TaskAssistant 低延迟路径。
    CHAT = "chat"
    #: 派一个新子任务（用户提出了需要执行的需求）。
    DISPATCH = "dispatch"
    #: 更换：停止/替换正在进行的子任务，用新的 Agent / 模型接着做同一需求。
    REPLACE = "replace"
    #: 再开一个：并行新开子任务，不替换正在跑的。
    ADD = "add"


@dataclass(frozen=True)
class RouteDecision:
    action: RouteAction
    #: 给日志/解释用的简短原因（不进用户可见文案）。
    reason: str
    #: 用户话里提到的 Agent 名称（「换用 Codex 做」里的 Codex），仅作提示。
    agent_hint: str | None = None
    #: 用户话里提到的模型名（「换成 gpt-5 做」里的 gpt-5），仅作提示。
    model_hint: str | None = None


#: 「再开一个」：并行新增，不替换正在跑的。
_ADD_PATTERNS = (
    "再开一个",
    "再开个",
    "再开一条",
    "另开一个",
    "另开个",
    "另外开一个",
    "另外开个",
    "再派一个",
    "再派个",
    "再起一个",
    "再来一个",
    "多开一个",
    "并行开",
    "同时开",
    "同时再做",
    "另外再做",
)

#: 「更换」：结束/替换原任务，用新选择接着做。
_REPLACE_PATTERNS = (
    "更换",
    "换掉",
    "换一个",
    "换个",
    "换用",
    "改用",
    "换成",
    "改换",
    "替换",
    "重开一个",
    "重新开一个",
    "停下换",
    "停止换",
    "停掉换",
    "这个不用了",
)

#: 问进度 / 汇报：明确走快聊，不要让助理误以为要再派活。
_PROGRESS_PATTERNS = (
    "进度",
    "怎么样了",
    "怎么样了？",
    "到哪了",
    "做到哪",
    "完成了吗",
    "做完了吗",
    "好了吗",
    "在做什么",
    "在干什么",
    "干什么呢",
    "进行到",
    "状态如何",
    "还要多久",
    "卡在哪",
    "有什么进展",
    "汇报一下",
    "汇总一下",
    "总结一下",
    "结果如何",
    "结果怎么样",
)

#: 社交 / 澄清 / 元问题：短句直接快聊。
_SMALL_TALK_PATTERNS = (
    "你好",
    "您好",
    "hi",
    "hello",
    "在吗",
    "在不在",
    "谢谢",
    "多谢",
    "感谢",
    "好的",
    "好呀",
    "行",
    "可以",
    "收到",
    "明白",
    "懂了",
    "嗯",
    "ok",
    "okay",
    "你是谁",
    "你叫什么",
    "你能做什么",
    "你会什么",
    "你是谁？",
    "稍等",
    "等一下",
    "没事",
    "不用了",
    "算了",
)

#: 执行类关键词：出现即派子任务（写代码 / 改环境 / 长任务 / 终端 / 创作落地 / 查资料产出）。
_EXECUTION_KEYWORDS = (
    "写代码",
    "写个代码",
    "改代码",
    "修改代码",
    "看代码",
    "读代码",
    "代码",
    "改成",
    "改一下",
    "实现",
    "开发",
    "编写",
    "写一个",
    "写个",
    "帮我写",
    "修复",
    "修一下",
    "bug",
    "报错",
    "部署",
    "上线",
    "发布",
    "运行",
    "执行",
    "跑一下",
    "跑个",
    "跑一下测试",
    "终端",
    "命令行",
    "命令",
    "shell",
    "创建文件",
    "新建文件",
    "删除文件",
    "重命名",
    "安装",
    "配置",
    "搭建",
    "初始化",
    "写脚本",
    "脚本",
    "重构",
    "测试",
    "提交",
    "commit",
    "push",
    "git",
    "数据库",
    "迁移",
    "接口",
    "页面",
    "组件",
    "样式",
    "设计稿",
    "画一个",
    "画个",
    "生成",
    "做个",
    "做一个",
    "搞个",
    "搞一个",
    "整理成",
    "导出",
    "调研",
    "搜集",
    "搜索一下",
    "查一下",
    "查查",
    "帮我查",
    "修复一下",
    "优化",
    "排查",
    "诊断",
    "处理一下",
    "搞定",
    "搞一下",
    "弄一下",
    "弄个",
    "安排一下",
)

#: 明确「动手」的动词：与请求语气一起出现时派子任务。
_ACTION_HINTS = (
    "帮我",
    "给我",
    "替我",
    "麻烦你",
    "你去",
    "把它",
    "把这个",
    "把那个",
    "接着做",
    "开始做",
    "继续做",
    "怎么弄",
    "弄完",
    "弄一下",
    "弄个",
    "处理",
    "搞定",
)

#: 纯提问语气：没有执行关键词时按快聊处理。
_QUESTION_MARKERS = ("?", "？", "吗", "呢", "为什么", "是什么", "怎么理解", "能不能", "可不可以")

#: 「换用 X 做」里 X 的提取：取动词后到标点/空白前的片段。
_AGENT_HINT_RE = re.compile(
    r"(?:换用|改用|换成|更换成|更换为|换到|替换成|替换为)\s*([A-Za-z0-9_.\-一-鿿]{1,32})"
)
_MODEL_HINT_RE = re.compile(r"(?:换成|改用|换用|模型改(?:成|为)|用)\s*([A-Za-z0-9_.:+\-]{2,64})")
#: 进度类说法里如果同时出现这些「具体产出物」，说明用户是在要求做东西，不是问进度。
_PROGRESS_ARTIFACT_KEYWORDS = (
    "代码",
    "文件",
    "页面",
    "组件",
    "脚本",
    "命令",
    "终端",
    "测试",
    "部署",
    "接口",
    "数据库",
    "文档",
    "设计",
)


def route_message(
    text: str,
    *,
    has_active_subtask: bool = False,
    allow_dispatch: bool = True,
) -> RouteDecision:
    """判断一句话该走快聊、派活、更换还是再开一个。

    `has_active_subtask` 只用于解释（例如没有进行中的子任务时说「更换」仍按派活处理），
    不影响纯文本判定本身：分流规则不依赖数据库状态，方便大量单测。
    `allow_dispatch=False`（项目助理）时任何执行需求都退回快聊，由调用方说明边界。
    """
    raw = (text or "").strip()
    if not raw:
        return RouteDecision(RouteAction.CHAT, "空消息")
    lowered = raw.lower()

    add = _first_match(lowered, _ADD_PATTERNS)
    if add:
        decision = RouteDecision(
            RouteAction.ADD,
            f"命中再开一个：{add}",
            agent_hint=_match_hint(_AGENT_HINT_RE, raw),
            model_hint=_match_hint(_MODEL_HINT_RE, raw),
        )
        return _respect_dispatch(decision, allow_dispatch)

    replace = _first_match(lowered, _REPLACE_PATTERNS)
    if replace:
        decision = RouteDecision(
            RouteAction.REPLACE,
            f"命中更换：{replace}" + ("" if has_active_subtask else "（当前无进行中子任务）"),
            agent_hint=_match_hint(_AGENT_HINT_RE, raw),
            model_hint=_match_hint(_MODEL_HINT_RE, raw),
        )
        return _respect_dispatch(decision, allow_dispatch)

    progress = _first_match(lowered, _PROGRESS_PATTERNS)
    execution = _first_match(lowered, _EXECUTION_KEYWORDS)
    # 问进度优先，但「项目进度页面怎么做」这类带具体产出物的句子仍是执行需求
    if progress and not (execution and _first_match(lowered, _PROGRESS_ARTIFACT_KEYWORDS)):
        return RouteDecision(RouteAction.CHAT, f"问进度/汇报：{progress}")
    if execution:
        return _respect_dispatch(
            RouteDecision(RouteAction.DISPATCH, f"命中执行关键词：{execution}"),
            allow_dispatch,
        )

    # 短句 / 社交 / 澄清：快聊。先判短句，避免「在吗」这类被后面的长句规则误派。
    if len(raw) <= 12 and _first_match(lowered, _SMALL_TALK_PATTERNS):
        return RouteDecision(RouteAction.CHAT, "社交/澄清短句")
    if len(raw) <= 8 and not any(marker in raw for marker in _QUESTION_MARKERS):
        # 极短且没有提问语气：更像语气词/确认，按快聊。
        if _first_match(lowered, _SMALL_TALK_PATTERNS):
            return RouteDecision(RouteAction.CHAT, "极短语气词")
        return RouteDecision(RouteAction.CHAT, "短句快聊")

    # 明确请求动手，但没有命中上面的具体执行词：宁可派子任务，也别让主助理自己干。
    action = _first_match(lowered, _ACTION_HINTS)
    if action:
        return _respect_dispatch(
            RouteDecision(RouteAction.DISPATCH, f"命中动作请求：{action}"),
            allow_dispatch,
        )

    # 带问号且没有执行词：澄清 / 解释，走快聊。
    if any(marker in raw for marker in _QUESTION_MARKERS):
        return RouteDecision(RouteAction.CHAT, "提问/澄清")

    # 长句且没有明确提问：不确定时按派活处理（产品决定：宁可派子任务）。
    if len(raw) >= 30:
        return _respect_dispatch(
            RouteDecision(RouteAction.DISPATCH, "长句且无明确提问，按执行需求处理"),
            allow_dispatch,
        )
    return RouteDecision(RouteAction.CHAT, "默认快聊")


def _respect_dispatch(decision: RouteDecision, allow_dispatch: bool) -> RouteDecision:
    """项目助理不允许派子任务：把派活类动作统一退回快聊。"""
    if allow_dispatch:
        return decision
    return RouteDecision(RouteAction.CHAT, f"项目助理不可派子任务（原判定：{decision.reason}）")


def _first_match(text: str, patterns: tuple[str, ...]) -> str | None:
    for pattern in patterns:
        if pattern in text:
            return pattern
    return None


def _match_hint(pattern: re.Pattern[str], text: str) -> str | None:
    match = pattern.search(text)
    if not match:
        return None
    value = match.group(1).strip()
    return value or None


__all__ = ["RouteAction", "RouteDecision", "route_message"]
