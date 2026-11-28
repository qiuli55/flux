"""一轮对话该由谁接：服务端助手 / 整理成文档或页面 / 电脑 Agent。

判定必须是**服务端权威**：手机只展示结果，不能自己先用一套规则判完再传
``start=true/false``。这里是纯函数，不依赖运行时、数据库或模型调用，方便用大量
中文正反例做单测。

保守原则：

- 能明确看出「可以直接回答」的问题才走服务端助手；
- 明显的实现 / 改环境 / 长任务 / 指向工作区，交给电脑上的 Agent；
- 说不清时宁可交给 Agent，也不要把该跑的活留在聊天里；
- 但带附件且用户明确说「整理成文档 / 页面」时，走服务端整理能力，不启动 Agent。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

__all__ = [
    "ORGANIZE_CLARIFY_QUESTION",
    "TurnDecision",
    "TurnReason",
    "TurnRoute",
    "decide_turn",
]


class TurnRoute(str, Enum):
    """本轮由谁处理。"""

    #: flux-server 的 TaskAssistant 直接回答（服务端 ModelRouter，不启动电脑 Agent）。
    SERVER_ASSISTANT = "server_assistant"
    #: 把附件整理成 Markdown 文档（服务端能力，不启动电脑 Agent）。
    ORGANIZE_DOCUMENT = "organize_document"
    #: 把附件整理成可交互页面（服务端能力，不启动电脑 Agent）。
    ORGANIZE_PAGE = "organize_page"
    #: 交给电脑上的 Agent 执行。
    COMPUTER_AGENT = "computer_agent"
    #: 信息不够：先问一句，不要猜。
    CLARIFY = "clarify"


class TurnReason(str, Enum):
    """判定原因，只用于日志与测试断言，不直接展示给用户。"""

    EMPTY = "empty"
    EXPLICIT_START = "explicit_start"
    LONG_REQUEST = "long_request"
    EXECUTION_INTENT = "execution_intent"
    WORKSPACE_REFERENCE = "workspace_reference"
    ATTACHMENT = "attachment"
    ORGANIZE_DOCUMENT = "organize_document"
    ORGANIZE_PAGE = "organize_page"
    ORGANIZE_NEEDS_TARGET = "organize_needs_target"
    ORGANIZE_WITHOUT_ATTACHMENT = "organize_without_attachment"
    DIRECT_QUESTION = "direct_question"
    GREETING = "greeting"
    CONSERVATIVE = "conservative"


@dataclass(frozen=True)
class TurnDecision:
    """一次分流的纯数据结果。"""

    route: TurnRoute
    reason: TurnReason
    #: 命中的关键词或短语（已剔除被否定的命中）。
    matched: tuple[str, ...] = ()
    #: 这条路线是否必须有一台在线的电脑 Agent。
    needs_computer: bool = False
    #: 用户是否明确要求「开始执行 / 交给 Agent」。
    explicit_start: bool = False

    @property
    def organize_target(self) -> str | None:
        """整理路线的目标类型：doc / page；非整理路线为 None。"""
        if self.route is TurnRoute.ORGANIZE_DOCUMENT:
            return "doc"
        if self.route is TurnRoute.ORGANIZE_PAGE:
            return "page"
        return None

    @property
    def uses_server_only(self) -> bool:
        """是否完全不启动电脑 Agent。"""
        return not self.needs_computer


ORGANIZE_CLARIFY_QUESTION = "要整理成 Markdown 文档，还是可交互页面？我按你选的做，不猜。"

# ---------------------------------------------------------------------------
# 关键词表
# ---------------------------------------------------------------------------

#: 用户明确说「整理」（而不是让 Agent 分析文件）。
_ORGANIZE_CUES: tuple[str, ...] = (
    "整理成",
    "整理为",
    "整理一下",
    "帮我整理",
    "帮忙整理",
    "整理这份",
    "整理这个",
    "整理这张",
    "整理这些",
    "归纳成",
    "汇总成",
    "总结成",
    "转成",
    "转成文档",
    "转成页面",
    "保存为",
    "存成",
    "做成",
    "生成一份",
    "生成一个",
    "写成",
    "变成",
    "沉淀成",
)

#: 明确的目标类型。只有带这些词才认定用户选了 doc / page。
_DOC_TARGETS: tuple[str, ...] = (
    "文档",
    "markdown",
    "md 文档",
    "md文档",
    ".md",
    "说明文",
    "笔记",
    "readme",
    "文字稿",
)

_PAGE_TARGETS: tuple[str, ...] = (
    "页面",
    "网页",
    "html",
    "可交互",
    "能点的",
    "单页",
    "demo",
    "小站",
    "landing page",
)

#: 明确的执行 / 改造意图（动词 + 对象）。
_EXECUTION_RE = re.compile(
    r"(帮我|给我|请你|帮忙|麻烦你?)\s*"
    r"(写|做|改|加|删|修|实现|开发|创建|新建|部署|发布|跑|执行|装|配置|安装)"
    r"|"
    r"(写|实现|开发|创建|新建|添加|新增|删除|移除|修改|改成|改为|重构|修复|修好|"
    r"优化|调整|更新|升级|安装|卸载|配置|部署|发布|上线|运行|执行|编译|打包|构建|"
    r"提交|推送|合并|回滚|迁移|搭建|接入|集成|联调|测试|验证|生成)\s*"
    r"(一下|下|一遍|个|一个|这段|这个|一份|代码|脚本|文件|页面|接口|服务|功能|项目|"
    r"配置|命令|测试|文档|依赖|版本|仓库|应用|网站|网页|程序|函数|类|模块|数据库|表|"
    r"逻辑|样式|组件|参数|字段|按钮)"
    r"|"
    r"(做|写|搭|建)\s*(一个|个|一下|一份|段|点)\s*"
    r"(页面|网页|应用|app|网站|服务|接口|脚本|工具|功能|项目|demo|原型|组件|插件|文档)"
    r"|"
    r"(跑|执行|运行)\s*(一下|下|一遍)\s*(命令|测试|脚本|构建|编译|服务|项目)?"
    r"|"
    r"(改|动)\s*(一下)?\s*(代码|仓库|项目|文件|配置)"
    r"|"
    r"(加|增加|补充)\s*(一个|个|一下)?\s*(功能|接口|参数|字段|按钮|页面|模块|测试)",
    re.IGNORECASE,
)

_EXECUTION_EN_RE = re.compile(
    r"\b(write|implement|create|build|add|remove|delete|modify|change|refactor|fix|"
    r"deploy|release|run|execute|install|uninstall|configure|commit|push|merge|revert|"
    r"migrate|setup|set up|optimi[sz]e|generate|test)\b",
    re.IGNORECASE,
)

_EXPLICIT_START_RE = re.compile(
    r"(开始执行|开始动手|动手吧|执行吧|跑起来|开跑|开干)"
    r"|(继续|接着)(上次|上一轮|刚才|之前)"
    r"|(交给|让|叫)\s*(电脑上?)?\s*(的)?\s*(Agent|agent|智能体)"
    r"|(照着|按).{0,12}(做|执行|改)"
    r"|\b(hand (it|this) (over|off) to (the )?agent|start executing|"
    r"go ahead and (do|run|build|implement))\b",
    re.IGNORECASE,
)

_WORKSPACE_REF_RE = re.compile(
    r"(这个|该|那个|本地|当前)\s*(项目|仓库|代码库|工作区|文件|目录|代码)"
    r"|(项目|仓库|代码库|工作区)\s*(里|中|内)"
    r"|(这段|那段|上面这段|下面这段)\s*(代码|逻辑|配置)"
    r"|\b(this|that|the current|our)\s+"
    r"(project|repo|repository|workspace|codebase|file|folder|directory)\b",
    re.IGNORECASE,
)

_LOOK_AT_RE = re.compile(
    r"(帮我)?(看看|看一下|瞧瞧|翻一下|打开|搜索|查找|读取|读一下|查看|定位)\s*"
    r"(一下|下)?\s*(这个|那个|该|本地)\s*(文件|目录|仓库|项目|代码|日志|配置)"
    r"|(打开|搜索|查找|读取|查看|定位)\s*(一下|下)?\s*(项目|仓库|代码库|工作区)"
    r"|\b(open|read|look at|search|find|inspect|check)\b.{0,24}"
    r"\b(file|folder|directory|repo|repository|project|workspace|log|config)\b",
    re.IGNORECASE,
)

_QUESTION_ZH_RE = re.compile(
    r"(什么|啥|为什么|为何|怎么|怎样|如何|哪|谁|何时|多少|是否|能否|可不可以|可以吗|"
    r"是不是|有没有|要不要|还是|是什么意思|什么意思|区别|原理|用法|怎么用|含义|定义|"
    r"概念|了解|学习|入门|推荐|建议|最佳实践)"
    r"|(解释|说明|介绍|讲讲|聊聊|说说|说一下|展开说说|详细说说|再说一遍|重复一下|"
    r"没听懂|不太懂)"
)

_QUESTION_EN_RE = re.compile(
    r"^(what|why|how|who|when|where|which|is|are|can|could|should|does|do|did|"
    r"explain|define|tell me)\b",
    re.IGNORECASE,
)

_CLARIFICATION_RE = re.compile(
    r"(你刚才|你刚刚|刚才|刚刚|上面|前面|上一条|之前)\s*(说|讲|提|回答)"
    r"|(再说|重复|解释|展开|详细)\s*(一遍|一下|说说|讲讲)"
)


_GREETING_RE = re.compile(
    r"^(你好|您好|嗨|哈喽|在吗|在不在|谢谢|多谢|感谢|好的|好|嗯|收到|早上好|晚上好)"
    r"[!！。.~～ ]*$"
    r"|^(hi|hello|hey|thanks|thank you|ok|okay|good morning|good evening)[!.~ ]*$",
    re.IGNORECASE,
)

_LIST_LINE_RE = re.compile(r"^(\d+[.、)）]|[-*•·])\s*")

#: 长需求的字符上限：超过就不当简单问题。
MAX_SIMPLE_CHARS = 300


def _normalize(text: str) -> str:
    return " ".join(str(text or "").split())


def _has_organize_cue(text: str) -> tuple[str, ...]:
    return tuple(cue for cue in _ORGANIZE_CUES if cue in text)


def _target_hits(text: str, targets: tuple[str, ...]) -> tuple[str, ...]:
    lowered = text.lower()
    return tuple(item for item in targets if item.lower() in lowered)


def _is_long(text: str) -> bool:
    if len(text) > MAX_SIMPLE_CHARS:
        return True
    list_lines = sum(1 for line in text.splitlines() if _LIST_LINE_RE.match(line.strip()))
    return list_lines >= 3


def _looks_like_direct_question(text: str) -> bool:
    if _GREETING_RE.search(text):
        return True
    if _CLARIFICATION_RE.search(text):
        return True
    return bool(
        _QUESTION_ZH_RE.search(text)
        or "?" in text
        or "？" in text
        or _QUESTION_EN_RE.search(text)
    )


def decide_turn(
    text: str,
    *,
    attachment_count: int = 0,
    explicit_start: bool = False,
    task_description: str = "",
) -> TurnDecision:
    """决定本轮走哪条路。

    Args:
        text: 用户这一轮的原话（可以为空——只发附件时）。
        attachment_count: 本轮携带的附件数量。
        explicit_start: 用户是否点了「开始执行 / 交给 Agent」这类明确动作。
        task_description: 暂无消息时的任务描述兜底（新建任务首轮传 description）。

    Returns:
        :class:`TurnDecision`；``route=CLARIFY`` 时调用方应让服务端助手问一句。
    """
    original = str(text or "").strip()
    raw = _normalize(text)
    fallback = _normalize(task_description)
    body = raw or fallback
    # 长度与清单行数必须看原始换行，归一化后的 body 会把多行压成一行。
    length_source = original or str(task_description or "").strip()
    attachments = max(0, int(attachment_count or 0))

    # 0) 用户明确点「开始执行」：这是最强的执行信号，不拦。
    if explicit_start or (body and _EXPLICIT_START_RE.search(body)):
        return TurnDecision(
            TurnRoute.COMPUTER_AGENT,
            TurnReason.EXPLICIT_START,
            matched=("开始执行",) if explicit_start else (),
            needs_computer=True,
            explicit_start=True,
        )

    # 1) 用户说要「整理」。
    if body and _has_organize_cue(body):
        doc_hits = _target_hits(body, _DOC_TARGETS)
        page_hits = _target_hits(body, _PAGE_TARGETS)
        if doc_hits and page_hits:
            return TurnDecision(
                TurnRoute.CLARIFY,
                TurnReason.ORGANIZE_NEEDS_TARGET,
                matched=doc_hits + page_hits,
            )
        if not doc_hits and not page_hits:
            return TurnDecision(
                TurnRoute.CLARIFY,
                TurnReason.ORGANIZE_NEEDS_TARGET,
                matched=_has_organize_cue(body),
            )
        if attachments == 0:
            return TurnDecision(
                TurnRoute.CLARIFY,
                TurnReason.ORGANIZE_WITHOUT_ATTACHMENT,
                matched=doc_hits + page_hits,
            )
        if page_hits:
            return TurnDecision(
                TurnRoute.ORGANIZE_PAGE,
                TurnReason.ORGANIZE_PAGE,
                matched=page_hits,
            )
        return TurnDecision(
            TurnRoute.ORGANIZE_DOCUMENT,
            TurnReason.ORGANIZE_DOCUMENT,
            matched=doc_hits,
        )

    # 2) 空输入（只发附件且没写任何话）：带附件交给 Agent 看，没附件就请用户说清楚。
    if not body:
        if attachments:
            return TurnDecision(
                TurnRoute.COMPUTER_AGENT,
                TurnReason.ATTACHMENT,
                needs_computer=True,
            )
        return TurnDecision(TurnRoute.CLARIFY, TurnReason.EMPTY)

    # 3) 长需求 / 指向工作区：电脑 Agent。
    if _is_long(length_source):
        return TurnDecision(
            TurnRoute.COMPUTER_AGENT, TurnReason.LONG_REQUEST, needs_computer=True
        )
    workspace = _WORKSPACE_REF_RE.search(body) or _LOOK_AT_RE.search(body)
    if workspace is not None:
        return TurnDecision(
            TurnRoute.COMPUTER_AGENT,
            TurnReason.WORKSPACE_REFERENCE,
            matched=(workspace.group(0),),
            needs_computer=True,
        )

    # 4) 带附件但没说要整理：要看文件，交给电脑 Agent。
    if attachments:
        return TurnDecision(
            TurnRoute.COMPUTER_AGENT,
            TurnReason.ATTACHMENT,
            needs_computer=True,
        )

    # 5) 能直接回答的问题 / 寒暄 / 短澄清：服务端助手。
    #    放在执行意图之前：英文「how do I install…」和中文「怎么读文件」是问用法，
    #    不是「替我装」。明确的工作区引用已在上面优先交给 Agent。
    if _looks_like_direct_question(body):
        return TurnDecision(
            TurnRoute.SERVER_ASSISTANT,
            TurnReason.GREETING if _GREETING_RE.search(body) else TurnReason.DIRECT_QUESTION,
        )

    # 6) 明确执行意图：电脑 Agent。
    execution = _EXECUTION_RE.search(body) or _EXECUTION_EN_RE.search(body)
    if execution is not None:
        return TurnDecision(
            TurnRoute.COMPUTER_AGENT,
            TurnReason.EXECUTION_INTENT,
            matched=(execution.group(0),),
            needs_computer=True,
        )

    # 6) 拿不准：宁可交给电脑 Agent，也不要把该跑的活留在聊天里。
    return TurnDecision(
        TurnRoute.COMPUTER_AGENT, TurnReason.CONSERVATIVE, needs_computer=True
    )
