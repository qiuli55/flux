package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.IncomingShare

/**
 * 分享落地规则与登录墙暂存：
 * - 正在看某段对话 → 仍留在该对话；
 * - 新对话草稿 → 不另开一层；
 * - 其它页面 → 打开新对话；
 * - 未进入主界面 → 暂存，进入后每条只应用一次。
 */
class V2ShareRoutingTest {

    @Test
    fun `task chat keeps the share in the current task`() {
        assertEquals(
            ShareLanding.CURRENT_TASK,
            resolveShareLanding(
                route = V2Route.TaskChat("task-1"),
                bootReady = true,
                hasDraftChat = false,
            ),
        )
    }

    @Test
    fun `existing draft chat is reused`() {
        assertEquals(
            ShareLanding.CURRENT_DRAFT,
            resolveShareLanding(
                route = V2Route.Chat,
                bootReady = true,
                hasDraftChat = true,
            ),
        )
    }

    @Test
    fun `chat route without a draft state still opens a new chat`() {
        assertEquals(
            ShareLanding.NEW_CHAT,
            resolveShareLanding(
                route = V2Route.Chat,
                bootReady = true,
                hasDraftChat = false,
            ),
        )
    }

    @Test
    fun `home artifacts profile and browser open a new chat`() {
        listOf(
            V2Route.Home,
            V2Route.Artifacts,
            V2Route.Profile,
            V2Route.Browser(url = "https://example.com", origin = V2Route.Home),
        ).forEach { route ->
            assertEquals(
                "$route should open a new chat",
                ShareLanding.NEW_CHAT,
                resolveShareLanding(route, bootReady = true, hasDraftChat = true),
            )
        }
    }

    @Test
    fun `login wall holds the share until the main ui is ready`() {
        assertEquals(
            ShareLanding.HOLD,
            resolveShareLanding(V2Route.Home, bootReady = false, hasDraftChat = false),
        )
        assertEquals(
            ShareLanding.HOLD,
            resolveShareLanding(V2Route.TaskChat("t"), bootReady = false, hasDraftChat = true),
        )
    }

    @Test
    fun `queued share is consumed exactly once after boot`() {
        val queued = ShareIntakeState().receive(IncomingShare(text = "分享进来的文字"))

        val (stillHeld, beforeReady) = queued.nextIfReady(bootReady = false)
        assertNull(beforeReady)
        assertEquals(1, stillHeld.pending.size)

        val (afterFirst, first) = stillHeld.nextIfReady(bootReady = true)
        assertEquals("分享进来的文字", first?.text)
        assertTrue(afterFirst.pending.isEmpty())

        val (afterSecond, second) = afterFirst.nextIfReady(bootReady = true)
        assertNull(second)
        assertTrue(afterSecond.pending.isEmpty())
    }

    @Test
    fun `multiple queued shares are applied one at a time`() {
        val state = ShareIntakeState()
            .receive(IncomingShare(text = "第一条"))
            .receive(IncomingShare(text = "第二条"))

        val (afterFirst, first) = state.nextIfReady(bootReady = true)
        assertEquals("第一条", first?.text)
        assertEquals(1, afterFirst.pending.size)

        val (afterSecond, second) = afterFirst.nextIfReady(bootReady = true)
        assertEquals("第二条", second?.text)
        assertTrue(afterSecond.pending.isEmpty())
    }

    // ---------------------------------------------------------------- 首页未发送草稿冲突

    @Test
    fun `home with an unsent draft asks before discarding it`() {
        assertEquals(
            ShareLanding.ASK_DRAFT_CONFLICT,
            resolveShareLanding(
                route = V2Route.Home,
                bootReady = true,
                hasDraftChat = true,
                hasUnsentDraft = true,
            ),
        )
    }

    @Test
    fun `home without unsent content still opens a new chat directly`() {
        assertEquals(
            ShareLanding.NEW_CHAT,
            resolveShareLanding(
                route = V2Route.Home,
                bootReady = true,
                hasDraftChat = true,
                hasUnsentDraft = false,
            ),
        )
        assertEquals(
            ShareLanding.NEW_CHAT,
            resolveShareLanding(
                route = V2Route.Profile,
                bootReady = true,
                hasDraftChat = false,
                hasUnsentDraft = false,
            ),
        )
    }

    @Test
    fun `login wall still holds even with an unsent draft`() {
        assertEquals(
            ShareLanding.HOLD,
            resolveShareLanding(
                route = V2Route.Home,
                bootReady = false,
                hasDraftChat = true,
                hasUnsentDraft = true,
            ),
        )
    }

    @Test
    fun `chat route keeps merging into the current draft without asking`() {
        assertEquals(
            ShareLanding.CURRENT_DRAFT,
            resolveShareLanding(
                route = V2Route.Chat,
                bootReady = true,
                hasDraftChat = true,
                hasUnsentDraft = true,
            ),
        )
    }

    @Test
    fun `unsent draft detection ignores an empty or welcome-only draft`() {
        assertFalse(hasUnsentDraft(chat = null, messages = emptyList(), attachmentCount = 0))
        assertFalse(
            hasUnsentDraft(
                chat = draftChat(),
                messages = listOf(V2Message(V2MessageRole.SYSTEM, "描述你的需求", "10:00")),
                attachmentCount = 0,
            ),
        )
        assertTrue(
            hasUnsentDraft(
                chat = draftChat(),
                messages = listOf(V2Message(V2MessageRole.SYSTEM, "描述你的需求", "10:00")),
                attachmentCount = 1,
            ),
        )
        assertTrue(
            hasUnsentDraft(
                chat = draftChat(),
                messages = listOf(V2Message(V2MessageRole.USER, "还没发出去", "10:00")),
                attachmentCount = 0,
            ),
        )
    }

    private fun draftChat(): V2ChatState = V2ChatState(
        conversationId = null,
        title = "新的对话",
        device = "本机",
        agentId = "arch",
        model = "deepseek-v4.1-flash",
    )
}
