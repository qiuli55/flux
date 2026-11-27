package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.ClaudeLoginSession
import top.qiuli55.flux.mobile.data.CodexLoginSession

/** 统一订阅入口：两家订阅共用入口，Claude 的「粘授权码」会话状态可测。 */
class V2SubscriptionLogicTest {

    @Test
    fun `unified entry lists chatgpt and claude code`() {
        assertEquals(listOf("ChatGPT", "Claude Code"), SUBSCRIPTION_PROVIDERS)
    }

    @Test
    fun `claude login session stays pending until success or failure`() {
        assertTrue(ClaudeLoginSession(status = "starting").isPending)
        assertTrue(ClaudeLoginSession(status = "pending").isPending)
        assertFalse(ClaudeLoginSession(status = "success").isPending)
        assertFalse(ClaudeLoginSession(status = "failed").isPending)
        assertFalse(ClaudeLoginSession(status = "cancelled").isPending)
    }

    @Test
    fun `claude login requires a pasted code and exposes its url`() {
        val session = ClaudeLoginSession(
            loginId = "l1",
            status = "pending",
            url = "https://claude.com/cai/oauth/authorize?code=true",
            needsCode = true,
        )
        assertTrue(session.needsCode)
        assertTrue(session.url.startsWith("https://claude.com/"))
    }

    @Test
    fun `codex device login still uses the same pending contract`() {
        assertTrue(CodexLoginSession(status = "pending").isPending)
        assertFalse(CodexLoginSession(status = "failed").isPending)
    }
}
