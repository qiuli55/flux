package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 流式输出的 Compose 交互测试（Robolectric，JVM 上跑）。
 *
 * 覆盖：
 *  - 思考块：流式中展开，结束后默认收起，点一下可重新展开；
 *  - 工具步骤：参数/结果默认隐藏，展开才出现；
 *  - 连接状态行：重连提示与终态错误的重试入口；
 *  - 自动跟随：在底部时跟随、拖动后停止跟随、点「回到最新」恢复；
 *  - 停止按钮：点击发出一次取消，停止中禁用、双击只认一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2StreamUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun chatState() = V2ChatState(
        conversationId = "task-1",
        title = "构建失败排查",
        device = "本机",
        agentId = "codex-agent",
        model = "deepseek-v4.1-flash",
    )

    private fun history(count: Int): List<V2Message> = (1..count).map { index ->
        V2Message(
            role = V2MessageRole.AGENT,
            text = "历史消息 $index：" + "日志内容 ".repeat(6),
            time = "12:00",
            id = "m-$index",
        )
    }

    // ------------------------------------------------------------ 思考 / 工具

    @Test
    fun `reasoning is collapsed after it finishes and expands on tap`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    reasoning = StreamReasoning(
                        text = "先看构建日志，再定位失败的模块。",
                        startedAtMs = 1_000L,
                        endedAtMs = 4_000L,
                        active = false,
                    ),
                    tools = emptyList(),
                ),
            )
        }

        // 本轮结束后执行过程整卡默认收起，只留一行摘要。
        rule.onNodeWithText("思考过程").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.REASONING).assertDoesNotExist()
        rule.onNodeWithTag(V2StreamTags.ACTIVITY_TOGGLE).performClick()

        rule.onNodeWithTag(V2StreamTags.REASONING).assertIsDisplayed()
        rule.onNodeWithText("思考 3秒").assertIsDisplayed()
        // 思考正文仍默认收起：正文不占空间，也不会在下一轮重新冒出来
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertDoesNotExist()

        rule.onNodeWithTag(V2StreamTags.REASONING).performClick()
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertIsDisplayed()
        rule.onNodeWithText("先看构建日志", substring = true).assertIsDisplayed()
    }

    @Test
    fun `reasoning stays expanded while it is still streaming`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    reasoning = StreamReasoning(
                        text = "正在分析依赖树…",
                        startedAtMs = System.currentTimeMillis(),
                        active = true,
                    ),
                ),
            )
        }

        rule.onNodeWithText("思考中…", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertIsDisplayed()
    }

    @Test
    fun `tool args and output stay hidden until expanded`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    tools = listOf(
                        StreamToolStep(
                            id = "call-1",
                            name = "shell",
                            detail = "pytest -q",
                            output = "42 passed",
                            status = StreamToolStatus.DONE,
                            category = "command",
                        ),
                    ),
                ),
            )
        }

        // 结束后执行过程默认收起：先把摘要行展开，才看到一行行命令。
        rule.onNodeWithText("运行了 1 条命令").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.tool("call-1")).assertDoesNotExist()
        rule.onNodeWithTag(V2StreamTags.ACTIVITY_TOGGLE).performClick()

        rule.onNodeWithTag(V2StreamTags.tool("call-1")).assertIsDisplayed()
        rule.onNodeWithText("完成").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.toolBody("call-1")).assertDoesNotExist()

        rule.onNodeWithTag(V2StreamTags.tool("call-1")).performClick()
        rule.onNodeWithTag(V2StreamTags.toolBody("call-1")).assertIsDisplayed()
        rule.onNodeWithText("pytest -q").assertIsDisplayed()
        rule.onNodeWithText("42 passed").assertIsDisplayed()
    }

    @Test
    fun `running and failed tools show their status text`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    liveActive = true,
                    tools = listOf(
                        StreamToolStep(id = "run", name = "shell", status = StreamToolStatus.RUNNING),
                        StreamToolStep(id = "bad", name = "build", status = StreamToolStatus.FAILED),
                    ),
                ),
            )
        }

        rule.onNodeWithText("运行中").assertIsDisplayed()
        rule.onNodeWithText("失败").assertIsDisplayed()
    }

    @Test
    fun `finished run shows command count and duration in one summary line`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    tools = listOf(
                        StreamToolStep(id = "a", name = "shell", status = StreamToolStatus.DONE, category = "command"),
                        StreamToolStep(id = "b", name = "shell", status = StreamToolStatus.DONE, category = "command"),
                        StreamToolStep(id = "c", name = "shell", status = StreamToolStatus.DONE, category = "command"),
                    ),
                    runStartedAtMs = 10_000L,
                    runEndedAtMs = 40_000L,
                    liveActive = false,
                ),
            )
        }

        rule.onNodeWithText("运行了 3 条命令 · 30秒").assertIsDisplayed()
        // 收起状态下不铺命令行
        rule.onNodeWithTag(V2StreamTags.tool("a")).assertDoesNotExist()
    }

    @Test
    fun `running command moves into background chip while assistant keeps writing`() {
        rule.setContent {
            StreamActivityCard(
                stream = StreamUiSnapshot(
                    liveActive = true,
                    liveText = "先把结论写出来，后台命令还在跑。",
                    tools = listOf(
                        StreamToolStep(
                            id = "bg",
                            name = "shell",
                            detail = "npm run dev",
                            status = StreamToolStatus.RUNNING,
                            category = "command",
                        ),
                        StreamToolStep(
                            id = "done",
                            name = "shell",
                            detail = "pytest -q",
                            status = StreamToolStatus.DONE,
                            category = "command",
                        ),
                    ),
                ),
            )
        }

        rule.onNodeWithTag(V2StreamTags.BACKGROUND).assertIsDisplayed()
        rule.onNodeWithText("后台 Bash").assertIsDisplayed()
        // 后台命令不再以普通行混在列表里；已完成的命令仍是一行。
        rule.onNodeWithTag(V2StreamTags.tool("bg")).assertDoesNotExist()
        rule.onNodeWithTag(V2StreamTags.tool("done")).assertIsDisplayed()
    }

    @Test
    fun `run duration formatter handles seconds minutes and hours`() {
        assertEquals("0秒", formatRunDuration(0))
        assertEquals("21秒", formatRunDuration(21_900))
        assertEquals("1分32秒", formatRunDuration(92_000))
        assertEquals("30分2秒", formatRunDuration(1_802_000))
        assertEquals("1小时5分", formatRunDuration(3_900_000))
    }

    // ------------------------------------------------------------ 连接状态

    @Test
    fun `reconnecting shows inline status without blocking content`() {
        rule.setContent {
            StreamStatusRow(
                stream = StreamUiSnapshot(connection = StreamConnection.RECONNECTING),
                onRetry = {},
            )
        }

        rule.onNodeWithTag(V2StreamTags.STATUS).assertIsDisplayed()
        rule.onNodeWithText("连接中断，正在重连…").assertIsDisplayed()
    }

    @Test
    fun `terminal error offers retry`() {
        var retries = 0
        rule.setContent {
            StreamStatusRow(
                stream = StreamUiSnapshot(
                    connection = StreamConnection.FAILED,
                    terminalError = "模型超时",
                ),
                onRetry = { retries++ },
            )
        }

        rule.onNodeWithText("生成中断：模型超时").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.RETRY).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `cancelled stream shows stopped status without retry`() {
        rule.setContent {
            StreamStatusRow(
                stream = StreamUiSnapshot(cancelled = true),
                onRetry = {},
            )
        }

        rule.onNodeWithText("已停止生成").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.RETRY).assertDoesNotExist()
    }

    // ------------------------------------------------------------ 自动跟随

    @Test
    fun `auto scroll follows at bottom and pauses after user drags up`() {
        val streamState = mutableStateOf(
            StreamUiSnapshot(liveActive = true, liveText = "第一段回答", revision = 1),
        )
        rule.setContent {
            V2ChatScreen(
                chat = chatState(),
                messages = history(30),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                stream = streamState.value,
            )
        }

        // 一直在底部：流式继续时不出现「回到最新」
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertDoesNotExist()
        rule.runOnIdle {
            streamState.value = streamState.value.copy(
                liveText = streamState.value.liveText + "，继续输出更多内容。",
                revision = 2,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertDoesNotExist()

        // 用户向上拖动：立即停止跟随，浮出「回到最新」
        rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertIsDisplayed()

        // 阅读期间新内容到达：只提示「有新内容」，绝不把用户拽回底部
        rule.runOnIdle {
            streamState.value = streamState.value.copy(
                liveText = streamState.value.liveText + "新到达的一段。",
                revision = 3,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertIsDisplayed()
        rule.onNodeWithText("有新内容").assertIsDisplayed()

        // 点一下恢复跟随，按钮消失
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertDoesNotExist()
    }

    // ------------------------------------------------------------ 停止按钮

    @Test
    fun `stop button fires once, ignores double tap and is disabled while stopping`() {
        var stops = 0
        var stopping by mutableStateOf(false)
        rule.setContent {
            ChatSendButton(
                running = true,
                sendEnabled = false,
                stopping = stopping,
                onSend = {},
                onStop = { stops++ },
            )
        }

        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.runOnIdle { assertEquals(1, stops) }

        rule.runOnIdle { stopping = true }
        rule.onNodeWithTag(V2ComposerTags.SEND).assertIsNotEnabled()
    }
}
