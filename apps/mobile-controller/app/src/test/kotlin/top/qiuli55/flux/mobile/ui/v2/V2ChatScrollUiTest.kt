package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 对话滚到底部的回归测试。
 *
 * 复现录屏里的问题：最后一条是远超一屏的已完成回复，用户滑到它的末尾后，
 * 列表不能把视口 snap 回这条消息的开头。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ChatScrollUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var listState: LazyListState

    private fun chatState() = V2ChatState(
        conversationId = "task-scroll",
        title = "你是codex吗",
        device = "本机",
        agentId = "codex-agent",
        model = "deepseek-v4.1-flash",
    )

    private fun userMessage() = V2Message(
        role = V2MessageRole.USER,
        text = "你是codex吗",
        time = "22:54",
        id = "user-1",
    )

    /** 远高于一屏的已完成 Agent 回复；extraParagraphs 用来模拟后续高度变化。 */
    private fun longReply(extraParagraphs: Int = 0) = V2Message(
        role = V2MessageRole.AGENT,
        text = buildString {
            append("代码审查开始。\n\n")
            repeat(90) { index ->
                append(
                    "第 ${index + 1} 段：这里是一段很长的审查说明，检查了命令输出、" +
                        "文件改动和边界条件，确认没有动工作区、没有改任何服务配置。\n\n",
                )
            }
            repeat(extraParagraphs) { index ->
                append("追加第 ${index + 1} 段：继续补充审查结论与验证过程。\n\n")
            }
            append("审查结束：没有动工作区、没有改任何服务配置。")
        },
        time = "22:54",
        id = "long-reply",
    )

    private fun showChat(messages: MutableState<List<V2Message>>) {
        rule.setContent {
            listState = rememberLazyListState()
            V2ChatScreen(
                chat = chatState(),
                messages = messages.value,
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                listState = listState,
            )
        }
        rule.waitForIdle()
    }

    /** 列表必须停在最后一项（长回复）的末尾，而不是它的开头。 */
    private fun assertPinnedToLongMessageEnd() {
        rule.runOnIdle {
            val info = listState.layoutInfo
            assertFalse("停在长消息末尾时不应还能继续向前滚", listState.canScrollForward)
            val last = info.visibleItemsInfo.last()
            assertEquals("最后一项（长回复）应在视口内", 2, last.index)
            assertEquals(
                "长回复的底部应贴住可视区底部",
                info.viewportStartOffset + info.viewportEndOffset,
                last.offset + last.size,
            )
            assertTrue(
                "长回复开头应已滚出视口（firstVisibleItemScrollOffset 不能是 0）",
                listState.firstVisibleItemScrollOffset > 0,
            )
            assertTrue("长回复的顶部应在视口上方", last.offset < 0)
        }
    }

    /** 连续上滑（fling）到真正的底部。 */
    private fun swipeToTrueBottom() {
        var guard = 0
        while (guard++ < 40) {
            val atBottom = rule.runOnIdle { !listState.canScrollForward }
            if (atBottom) return
            rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        fail("连续上滑 40 次后仍未到达列表底部")
    }

    @Test
    fun `completed long last message stays at its end after fling to bottom`() {
        val messages = mutableStateOf(listOf(userMessage(), longReply()))
        showChat(messages)

        // 首次进入就应停在长回复末尾。
        assertPinnedToLongMessageEnd()

        // 用户往上滑到长消息中部阅读：暂停跟随，「回到最新」浮出。
        rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertIsDisplayed()

        // 再连续快速下滑到真正的底部：必须稳住，不能被 snap 回消息开头。
        swipeToTrueBottom()
        assertPinnedToLongMessageEnd()

        // 碰到底部后再 overscroll 一下，手指离开后仍停在末尾。
        rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertPinnedToLongMessageEnd()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertDoesNotExist()
    }

    @Test
    fun `jump to bottom lands on the end of a tall last item`() {
        val messages = mutableStateOf(listOf(userMessage(), longReply()))
        showChat(messages)
        assertPinnedToLongMessageEnd()

        rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertIsDisplayed()

        // 「回到最新」也要滚到长消息末尾，不能停在开头。
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).performClick()
        rule.waitForIdle()
        assertPinnedToLongMessageEnd()
    }

    @Test
    fun `last item growing while following keeps the viewport at its end`() {
        val messages = mutableStateOf(listOf(userMessage(), longReply()))
        showChat(messages)
        assertPinnedToLongMessageEnd()

        // 已完成消息高度变化（例如步骤条展开）：跟随模式应继续贴它的末尾。
        rule.runOnIdle {
            messages.value = listOf(userMessage(), longReply(extraParagraphs = 60))
        }
        rule.waitForIdle()
        assertPinnedToLongMessageEnd()
    }
}
