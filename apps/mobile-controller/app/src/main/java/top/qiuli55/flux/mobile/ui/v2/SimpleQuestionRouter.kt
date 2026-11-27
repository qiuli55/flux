package top.qiuli55.flux.mobile.ui.v2

/** 一条消息走哪条路：服务端助手直接回，还是交给电脑上的 Agent。 */
enum class QuickReplyRoute {
    /** 由 flux-server 的 TaskAssistant 直接回答（服务端 ModelRouter，不启动 Agent）。 */
    SERVER_ASSISTANT,

    /** 交给电脑上的 Agent 执行（今天的 startTask / continueTask 路径）。 */
    COMPUTER_AGENT,
}

/** 判定原因：只用于日志与测试断言，不直接展示给用户。 */
enum class QuickReplyReason {
    EMPTY,
    ATTACHMENT,
    LONG_REQUEST,
    EXPLICIT_START,
    EXECUTION_INTENT,
    WORKSPACE_REFERENCE,
    DIRECT_QUESTION,
    CONSERVATIVE,
}

data class QuickReplyDecision(
    val route: QuickReplyRoute,
    val reason: QuickReplyReason,
) {
    val simple: Boolean get() = route == QuickReplyRoute.SERVER_ASSISTANT
}

/**
 * 「简单问题」判定：纯函数，不依赖 Compose / 网络 / 用户设置。
 *
 * 判定原则是**保守**：只有能明确看出「可以直接回答」的消息才走服务端助手；
 * 拿不准的时候宁可启动电脑上的 Agent，避免该跑的活没跑。
 *
 * 不算简单（一律交给电脑 Agent）：
 * - 带附件（要看文件 / 工作区）；
 * - 明确要求执行 / 改造环境（写代码、改仓库、跑命令、部署、打开或搜索项目文件、
 *   继续上次执行、「交给 Agent」「开始执行」等）；
 * - 长需求清单（多行编号 / 项目符号，或大段正文）；
 * - 指向当前工作区（这个项目 / 这个文件 / 项目里 / 工作区等）；
 * - 没有明显「提问 / 澄清 / 寒暄」意图的短句。
 *
 * 算简单：
 * - 事实、概念、解释、怎么用、短澄清、对已有对话内容的追问；
 * - 寒暄 / 致谢。
 *
 * 注意：这里只做分流，不代表手机端运行了任何模型。走 [QuickReplyRoute.SERVER_ASSISTANT]
 * 时，回复来自 flux-server 的 TaskAssistant（服务端 ModelRouter）。
 */
internal object SimpleQuestionRouter {

    fun decide(text: String, attachmentCount: Int = 0): QuickReplyDecision {
        val raw = text.trim()
        if (raw.isEmpty()) {
            return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.EMPTY)
        }
        // 有附件就要看文件内容，手机端没法替 Agent 读工作区。
        if (attachmentCount > 0) {
            return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.ATTACHMENT)
        }
        if (isLongRequirement(raw)) {
            return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.LONG_REQUEST)
        }
        if (hasExplicitStart(raw)) {
            return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.EXPLICIT_START)
        }
        if (hasExecutionIntent(raw)) {
            return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.EXECUTION_INTENT)
        }
        if (refersToWorkspace(raw)) {
            return QuickReplyDecision(
                QuickReplyRoute.COMPUTER_AGENT,
                QuickReplyReason.WORKSPACE_REFERENCE,
            )
        }
        if (isDirectAnswerable(raw)) {
            return QuickReplyDecision(QuickReplyRoute.SERVER_ASSISTANT, QuickReplyReason.DIRECT_QUESTION)
        }
        return QuickReplyDecision(QuickReplyRoute.COMPUTER_AGENT, QuickReplyReason.CONSERVATIVE)
    }

    fun isSimple(text: String, attachmentCount: Int = 0): Boolean =
        decide(text, attachmentCount).simple

    // ------------------------------------------------------------ 规则

    /** 长需求：大段正文，或出现多行编号 / 项目符号清单。 */
    private fun isLongRequirement(raw: String): Boolean {
        if (raw.length > MAX_SIMPLE_CHARS) return true
        val listLines = raw.lineSequence().count { line ->
            LIST_LINE.containsMatchIn(line.trim())
        }
        return listLines >= 3
    }

    /** 用户已经明确要把活交给 Agent，而不是问一句话。 */
    private fun hasExplicitStart(raw: String): Boolean =
        EXPLICIT_START.containsMatchIn(raw) ||
            EXPLICIT_START_EN.containsMatchIn(raw.lowercase())

    /** 明确的执行 / 改造意图：写代码、改仓库、跑命令、部署、看项目文件…… */
    private fun hasExecutionIntent(raw: String): Boolean {
        val lower = raw.lowercase()
        return ZH_EXECUTION.containsMatchIn(raw) ||
            EN_EXECUTION.containsMatchIn(lower) ||
            ZH_LOOK_AT.containsMatchIn(raw) ||
            EN_LOOK_AT.containsMatchIn(lower)
    }

    /** 指向当前工作区 / 本地文件：没有附件时助手看不到，必须让 Agent 去看。 */
    private fun refersToWorkspace(raw: String): Boolean {
        val lower = raw.lowercase()
        return WORKSPACE_REF.containsMatchIn(raw) || WORKSPACE_REF_EN.containsMatchIn(lower)
    }

    /** 能直接回答的意图：提问、解释、澄清、寒暄。 */
    private fun isDirectAnswerable(raw: String): Boolean {
        val lower = raw.lowercase()
        if (GREETING.matches(raw) || GREETING_EN.matches(lower)) return true
        if (CLARIFICATION.containsMatchIn(raw) || CLARIFICATION_EN.containsMatchIn(lower)) return true
        return QUESTION_ZH.containsMatchIn(raw) ||
            QUESTION_MARK.containsMatchIn(raw) ||
            QUESTION_EN.containsMatchIn(lower)
    }

    // ------------------------------------------------------------ 规则表

    private const val MAX_SIMPLE_CHARS = 300

    private val LIST_LINE = Regex("""^(\d+[.、)）]|[-*•·])\s*""")

    private val EXPLICIT_START = Regex(
        """(开始执行|开始动手|动手吧|执行吧|跑起来|开跑|开干)|
           (继续|接着)(上次|上一轮|刚才|之前)|
           (交给|让|叫)\s*(电脑上?)?\s*(的)?\s*(Agent|agent|智能体)|
           (照着|按).{0,12}(做|执行|改)""".trimIndent().replace(Regex("""\s*\n\s*"""), ""),
    )

    private val EXPLICIT_START_EN = Regex(
        """\b(hand (it|this) (over|off) to (the )?agent|start executing|go ahead and (do|run|build|implement))\b""",
    )

    /**
     * 中文执行意图。动词必须带上对象 / 量词，避免把「你能做什么？」这类问句误判成任务。
     */
    private val ZH_EXECUTION = Regex(
        """(帮我|给我|请你|帮忙|麻烦你?)\s*(写|做|改|加|删|修|实现|开发|创建|新建|部署|发布|跑|执行|装|配置|安装)|
           (写|实现|开发|创建|新建|添加|新增|删除|移除|修改|改成|改为|重构|修复|修好|优化|调整|更新|升级|安装|卸载|配置|部署|发布|上线|运行|执行|编译|打包|构建|提交|推送|合并|回滚|迁移|搭建|接入|集成|联调|测试|验证|生成)\s*(一下|下|一遍|个|一个|这段|这个|一份|代码|脚本|文件|页面|接口|服务|功能|项目|配置|命令|测试|文档|依赖|版本|仓库|应用|网站|网页|程序|函数|类|模块|数据库|表|逻辑|样式|组件|参数|字段|按钮)|
           (做|写|搭|建)\s*(一个|个|一下|一份|段|点)\s*(页面|网页|应用|App|app|网站|服务|接口|脚本|工具|功能|项目|demo|Demo|原型|组件|插件|文档)|
           (跑|执行|运行)\s*(一下|下|一遍)\s*(命令|测试|脚本|构建|编译|服务|项目)?|
           (改|动)\s*(一下)?\s*(代码|仓库|项目|文件|配置)|
           (加|增加|补充)\s*(一个|个|一下)?\s*(功能|接口|参数|字段|按钮|页面|模块|测试)"""
            .trimIndent()
            .replace(Regex("""\s*\n\s*"""), ""),
    )

    private val EN_EXECUTION = Regex(
        """\b(write|implement|create|build|add|remove|delete|modify|change|refactor|fix|deploy|release|run|execute|install|uninstall|configure|commit|push|merge|revert|migrate|setup|set up|optimi[sz]e|generate|test)\b""",
    )

    /**
     * 「帮我看看这个文件」「打开那个目录」：必须由 Agent 接触工作区。
     *
     * 文件类名词要求带指示词（这个 / 那个 / 本地），否则「Python 怎么读取文件？」
     * 这种通用问题会被误判成执行任务；项目 / 仓库类名词本身已指向具体工作区。
     */
    private val ZH_LOOK_AT = Regex(
        """(帮我)?(看看|看一下|瞧瞧|翻一下|打开|搜索|查找|读取|读一下|查看|定位)\s*(一下|下)?\s*(这个|那个|该|本地)\s*(文件|目录|仓库|项目|代码|日志|配置)|
           (打开|搜索|查找|读取|查看|定位)\s*(一下|下)?\s*(项目|仓库|代码库|工作区)"""
            .trimIndent()
            .replace(Regex("""\s*\n\s*"""), ""),
    )

    private val EN_LOOK_AT = Regex(
        """\b(open|read|look at|search|find|inspect|check)\b.{0,24}\b(file|folder|directory|repo|repository|project|workspace|log|config)\b""",
    )

    private val WORKSPACE_REF = Regex(
        """(这个|该|那个|本地|当前)\s*(项目|仓库|代码库|工作区|文件|目录|代码)|
           (项目|仓库|代码库|工作区)\s*(里|中|内)|
           (这段|那段|上面这段|下面这段)\s*(代码|逻辑|配置)""".trimIndent().replace(Regex("""\s*\n\s*"""), ""),
    )

    private val WORKSPACE_REF_EN = Regex(
        """\b(this|that|the current|our)\s+(project|repo|repository|workspace|codebase|file|folder|directory)\b""",
    )

    private val QUESTION_ZH = Regex(
        """(什么|啥|为什么|为何|怎么|怎样|如何|哪|谁|何时|多少|是否|能否|可不可以|可以吗|是不是|有没有|要不要|还是|是什么意思|什么意思|区别|原理|用法|怎么用|含义|定义|概念|了解|学习|入门|推荐|建议|最佳实践)|
           (解释|说明|介绍|讲讲|聊聊|说说|说一下|展开说说|详细说说|再说一遍|重复一下|没听懂|不太懂)""".trimIndent().replace(Regex("""\s*\n\s*"""), ""),
    )

    private val QUESTION_MARK = Regex("""[?？]""")

    // 英文问句要求问词在句首或句子里有问号（QUESTION_MARK 单独判）：
    // 避免「This is a bug report…」这种含 is 的陈述句被当成可以直接回答。
    private val QUESTION_EN = Regex(
        """^(what|why|how|who|when|where|which|is|are|can|could|should|does|do|did|explain|define|tell me)\b""",
    )

    private val CLARIFICATION = Regex(
        """(你刚才|你刚刚|刚才|刚刚|上面|前面|上一条|之前)\s*(说|讲|提|回答)|
           (再说|重复|解释|展开|详细)\s*(一遍|一下|说说|讲讲)""".trimIndent().replace(Regex("""\s*\n\s*"""), ""),
    )

    private val CLARIFICATION_EN = Regex(
        """\b(what did you (just )?(say|mean)|say that again|explain (that|this) again|more detail|elaborate)\b""",
    )

    private val GREETING = Regex(
        """^(你好|您好|嗨|哈喽|在吗|在不在|谢谢|多谢|感谢|好的|好|嗯|收到|早上好|晚上好)[!！。.~～ ]*$""",
    )

    private val GREETING_EN = Regex(
        """^(hi|hello|hey|thanks|thank you|ok|okay|good morning|good evening)[!.~ ]*$""",
    )
}
