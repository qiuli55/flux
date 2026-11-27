package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简单问题分流：正反例以中文为主，带少量英文。
 *
 * 口径：只有明确「能直接回答」的消息走服务端助手；拿不准一律交给电脑 Agent。
 */
class SimpleQuestionRouterTest {

    private fun route(text: String, attachments: Int = 0) =
        SimpleQuestionRouter.decide(text, attachments)

    // ------------------------------------------------------------ 算简单

    @Test
    fun `factual and conceptual questions go to the server assistant`() {
        val simple = listOf(
            "什么是 Docker？",
            "Docker 和虚拟机的区别是什么",
            "为什么 HTTP 是无状态的？",
            "Python 的 GIL 是什么",
            "怎么用 Git 变基？",
            "解释一下什么是幂等",
            "介绍一下你自己",
            "你能做什么？",
            "Rust 的所有权怎么理解？",
            "我想学习 Rust",
            "前端最佳实践有哪些？",
            "Python 怎么读取文件？",
            "我该选 PostgreSQL 还是 MySQL？",
            "REST 和 GraphQL 哪个更适合移动端？",
            "How do I explain closures to a beginner?",
            "What is the CAP theorem?",
            "Can you explain monads?",
        )
        simple.forEach { text ->
            val decision = route(text)
            assertTrue("应走服务端助手：$text（实际 ${decision.reason}）", decision.simple)
            assertEquals(QuickReplyReason.DIRECT_QUESTION, decision.reason)
        }
    }

    @Test
    fun `short clarifications about earlier replies stay on the server assistant`() {
        val simple = listOf(
            "刚才那个参数是什么意思？",
            "你刚才说的那个命令什么意思？",
            "再说一遍",
            "继续解释一下",
            "没听懂，展开说说",
            "what did you mean by that?",
        )
        simple.forEach { text ->
            assertTrue("应走服务端助手：$text", route(text).simple)
        }
    }

    @Test
    fun `greetings and thanks go to the server assistant`() {
        listOf("你好", "您好！", "谢谢", "多谢。", "好的", "嗯", "hi", "Hello!", "thanks")
            .forEach { text ->
                assertTrue("应走服务端助手：$text", route(text).simple)
            }
    }

    // ------------------------------------------------------------ 不算简单

    @Test
    fun `attachments always go to the computer agent`() {
        val decision = route("这是什么文件？", attachments = 1)
        assertFalse(decision.simple)
        assertEquals(QuickReplyReason.ATTACHMENT, decision.reason)
    }

    @Test
    fun `implementation requests go to the computer agent`() {
        val agent = listOf(
            "帮我写一个登录页",
            "给项目加一个导出 CSV 的接口",
            "把这个函数改成异步",
            "跑一下测试",
            "部署到服务器",
            "改一下配置",
            "实现一个缓存层",
            "打开 src/main.py 看看",
            "搜索一下仓库里的 TODO",
            "看一下这个文件",
            "帮我看看这个文件",
            "Fix the failing build",
            "Add a retry to the upload API",
            "Write unit tests for the parser",
            "How do I run the tests?",
        )
        agent.forEach { text ->
            assertFalse("应交给电脑 Agent：$text", route(text).simple)
        }
    }

    @Test
    fun `explicit handover or continue instructions go to the computer agent`() {
        val agent = listOf(
            "开始执行",
            "交给 Agent 吧",
            "让电脑上的 Agent 来做",
            "继续上次的改动",
            "接着上次的活干",
            "go ahead and implement it",
        )
        agent.forEach { text ->
            val decision = route(text)
            assertFalse("应交给电脑 Agent：$text", decision.simple)
        }
        assertEquals(QuickReplyReason.EXPLICIT_START, route("开始执行").reason)
        assertEquals(QuickReplyReason.EXPLICIT_START, route("交给 Agent 吧").reason)
    }

    @Test
    fun `references to the current workspace go to the computer agent`() {
        val agent = listOf(
            "这个项目怎么启动？",
            "项目里的配置在哪？",
            "这个文件的内容是什么？",
            "这段代码有什么问题？",
            "what does this file do?",
        )
        agent.forEach { text ->
            val decision = route(text)
            assertFalse("应交给电脑 Agent：$text", decision.simple)
            assertEquals(QuickReplyReason.WORKSPACE_REFERENCE, decision.reason)
        }
    }

    @Test
    fun `long requirement lists go to the computer agent`() {
        val longList = """
            我要做一个后台管理系统：
            1. 登录和权限
            2. 用户列表
            3. 导出 CSV
        """.trimIndent()
        val decision = route(longList)
        assertFalse(decision.simple)
        assertEquals(QuickReplyReason.LONG_REQUEST, decision.reason)

        val longText = "我想做一个功能，".repeat(40)
        assertEquals(QuickReplyReason.LONG_REQUEST, route(longText).reason)
    }

    @Test
    fun `ambiguous messages stay conservative and go to the computer agent`() {
        val agent = listOf(
            "继续",
            "这个",
            "嗯嗯",
            "我有个需求",
            "This is a bug report: the login page crashes on submit",
            "",
        )
        agent.forEach { text ->
            assertFalse("拿不准应交给电脑 Agent：$text", route(text).simple)
        }
        assertEquals(QuickReplyReason.EMPTY, route("").reason)
        assertEquals(QuickReplyReason.CONSERVATIVE, route("继续").reason)
    }

    @Test
    fun `isSimple mirrors decide`() {
        assertTrue(SimpleQuestionRouter.isSimple("什么是事件循环？"))
        assertFalse(SimpleQuestionRouter.isSimple("帮我改一下事件循环"))
        assertFalse(SimpleQuestionRouter.isSimple("什么是这只文件？", attachmentCount = 2))
    }
}
