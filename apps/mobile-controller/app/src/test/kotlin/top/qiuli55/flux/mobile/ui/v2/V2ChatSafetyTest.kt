package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 对话页的误触保护（Robolectric / JVM）。
 *
 * 覆盖：
 *  - 返回：没有草稿直接退出；有草稿先确认，取消后草稿还在，确认才退出；
 *  - 发送失败：草稿保留，成功后才清空；
 *  - 停止运行：点一次只弹确认，确认后才真正取消；
 *  - 建议 chip：点一下发出一次，且触摸目标不小于 48dp。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ChatSafetyTest {

    @get:Rule
    val rule = createComposeRule()

    private var backCount = 0
    private var stopCount = 0
    private val sent = mutableListOf<String>()
    private var sending by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)

    private fun render(
        conversationId: String? = "conversation-1",
        taskRunning: Boolean = false,
    ) {
        backCount = 0
        stopCount = 0
        sent.clear()
        sending = false
        notice = null
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = conversationId,
                    title = "交互回归",
                    device = "本机",
                    agentId = "arch",
                    model = "deepseek-v4.1-flash",
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                onBack = { backCount++ },
                onOpenLogs = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = { sent += it },
                suggestions = listOf(SUGGESTION),
                sending = sending,
                notice = notice,
                taskRunning = taskRunning,
                onStopTask = { stopCount++ },
            )
        }
        rule.waitForIdle()
    }

    private fun typeDraft(text: String) {
        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun `back without a draft leaves the conversation immediately`() {
        render()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        assertEquals(1, backCount)
        rule.onNodeWithText("丢弃草稿？").assertDoesNotExist()
    }

    @Test
    fun `whitespace only draft is not treated as unsent content`() {
        render()
        typeDraft("   ")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        assertEquals(1, backCount)
        rule.onNodeWithText("丢弃草稿？").assertDoesNotExist()
    }

    @Test
    fun `back with a draft asks first and cancel keeps the text`() {
        render()
        typeDraft("还没发完的草稿")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("丢弃草稿？").assertIsDisplayed()
        assertEquals("must not leave before confirming", 0, backCount)

        rule.onNodeWithTag(FluxConfirmTags.DISMISS).performClick()
        rule.waitForIdle()
        assertEquals(0, backCount)
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("还没发完的草稿")
    }

    @Test
    fun `confirming discard closes only after the user accepts`() {
        render()
        typeDraft("要丢掉的草稿")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(FluxConfirmTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(1, backCount)
    }

    @Test
    fun `failed send keeps the draft for a retry`() {
        render()
        typeDraft("网络不好时的内容")
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("网络不好时的内容"), sent)

        sending = true
        rule.waitForIdle()
        notice = "网络错误，请稍后重试"
        sending = false
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("网络不好时的内容")
    }

    @Test
    fun `successful send clears the draft after the round finishes`() {
        render()
        typeDraft("发送成功后清空")
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("发送成功后清空"), sent)

        sending = true
        rule.waitForIdle()
        sending = false
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextEquals("")
    }

    @Test
    fun `stop tap asks first and cancel keeps the run going`() {
        render(taskRunning = true)
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("停止本次运行？").assertIsDisplayed()
        assertEquals(0, stopCount)

        rule.onNodeWithTag(FluxConfirmTags.DISMISS).performClick()
        rule.waitForIdle()
        assertEquals(0, stopCount)
    }

    @Test
    fun `confirming stop cancels the run once`() {
        render(taskRunning = true)
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(FluxConfirmTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(1, stopCount)
    }

    @Test
    fun `suggestion chip sends once and has a 48dp touch target`() {
        render(conversationId = null)
        val chip = rule.onNodeWithText(SUGGESTION)
        val bounds = chip.getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(
            "suggestion height $height should be >= 48dp",
            height >= 48.dp,
        )
        chip.performClick()
        rule.waitForIdle()
        assertEquals(listOf(SUGGESTION), sent)
    }

    private companion object {
        const val SUGGESTION = "帮我总结今天的日志"
    }
}
