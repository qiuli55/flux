package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 多阶段流式输出的 Compose 交互测试（Robolectric，JVM 上跑）。
 *
 * 覆盖：
 *  - 思考块：进行中扫光 + 展开，结束后自动收起成「思考过程」，点一下可重新展开；
 *  - 命令组 / 工具组：进行中展开并显示已用时，结束后自动收起为「运行了 N 条命令」；
 *    组内每条可展开，输出默认隐藏、展开后才出现；
 *  - 阶段交替：思考 / 正文 / 命令组 / 工具组按时间线顺序渲染；
 *  - 底部状态：进行中显示动作，结束后留总用时；
 *  - 连接状态行、自动跟随、停止按钮；
 *  - 收尾回归：重复消息 id 不会再撞 LazyColumn key 闪退。
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

    private fun textSnapshot(
        text: String,
        active: Boolean = true,
        revision: Int = 1,
    ) = StreamUiSnapshot(
        stages = listOf(StreamTextStage(id = "text-1", text = text)),
        liveActive = active,
        runStartedAtMs = 1_000L,
        revision = revision,
    )

    private fun commandStep(
        id: String,
        detail: String,
        status: StreamToolStatus,
        output: String = "",
    ) = StreamToolStep(
        id = id,
        name = "shell",
        detail = detail,
        output = output,
        status = status,
        category = "command",
    )

    // ------------------------------------------------------------ 思考

    @Test
    fun `thinking streams expanded with shimmer then collapses after it finishes`() {
        var snapshot by mutableStateOf(
            StreamUiSnapshot(
                stages = listOf(
                    StreamThoughtStage(
                        id = "thought-1",
                        text = "先看构建日志，再定位失败的模块。",
                        startedAtMs = System.currentTimeMillis(),
                        active = true,
                    ),
                ),
                liveActive = true,
                runStartedAtMs = System.currentTimeMillis(),
                revision = 1,
            ),
        )
        rule.setContent { StreamStagesColumn(snapshot) }

        // 进行中：灯泡 + 扫光标题（标题与底部状态各一处）+ 正文默认展开
        rule.onAllNodesWithText("思考中…").assertCountEquals(2)
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertIsDisplayed()
        rule.onNodeWithText("先看构建日志", substring = true).assertIsDisplayed()

        // 结束后：标题改成「思考过程」，取消扫光并自动收起
        rule.runOnIdle {
            snapshot = snapshot.copy(
                stages = listOf(
                    StreamThoughtStage(
                        id = "thought-1",
                        text = "先看构建日志，再定位失败的模块。",
                        startedAtMs = snapshot.runStartedAtMs,
                        endedAtMs = snapshot.runStartedAtMs + 3_000L,
                        active = false,
                    ),
                ),
                liveActive = false,
                runEndedAtMs = snapshot.runStartedAtMs + 3_000L,
                revision = 2,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("思考过程").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertDoesNotExist()

        // 手动重新展开仍可回看
        rule.onNodeWithTag(V2StreamTags.REASONING).performClick()
        rule.onNodeWithTag(V2StreamTags.REASONING_BODY).assertIsDisplayed()
    }

    // ------------------------------------------------------------ 文案（纯函数）

    @Test
    fun `activity label and group title follow stage state`() {
        val thought = StreamUiSnapshot(
            stages = listOf(StreamThoughtStage(id = "t", startedAtMs = 1_000L, active = true)),
            liveActive = true,
        )
        assertEquals("思考中", streamActivityLabel(thought))

        val runningGroup = StreamToolGroupStage(
            id = "g",
            type = StreamStageType.COMMAND,
            steps = listOf(commandStep("c1", "ls", StreamToolStatus.RUNNING)),
            startedAtMs = 1_000L,
            open = true,
        )
        val running = StreamUiSnapshot(stages = listOf(runningGroup), liveActive = true)
        assertEquals("正在运行命令", streamActivityLabel(running))
        assertEquals("正在运行命令 · 3秒", toolGroupTitle(runningGroup, 4_000L))

        val finishedGroup = runningGroup.copy(
            open = false,
            steps = listOf(commandStep("c1", "ls", StreamToolStatus.DONE)),
            endedAtMs = 4_000L,
        )
        assertEquals("运行了 1 条命令", toolGroupTitle(finishedGroup, 9_000L))
        assertNull(streamActivityLabel(StreamUiSnapshot(stages = listOf(finishedGroup), liveActive = false)))
    }

    // ------------------------------------------------------------ 命令 / 工具组

    @Test
    fun `running command group is expanded and shows elapsed time and status dots`() {
        rule.setContent {
            StreamStagesColumn(
                StreamUiSnapshot(
                    stages = listOf(
                        StreamToolGroupStage(
                            id = "group-1",
                            type = StreamStageType.COMMAND,
                            steps = listOf(
                                commandStep("c1", "npm install", StreamToolStatus.RUNNING),
                                commandStep("c2", "npm run dev", StreamToolStatus.RUNNING),
                            ),
                            startedAtMs = System.currentTimeMillis(),
                            open = true,
                        ),
                    ),
                    liveActive = true,
                    runStartedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        // 组标题与底部状态各出现一次「正在运行命令」
        rule.onAllNodesWithText("正在运行命令", substring = true).assertCountEquals(2)
        // 组内两条都在进行中：行可见，但输出还没展开
        rule.onNodeWithTag(V2StreamTags.tool("c1")).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.tool("c2")).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.toolBody("c1")).assertDoesNotExist()
        rule.onNodeWithText("npm install").assertIsDisplayed()
    }

    @Test
    fun `finished command group collapses to count and can be expanded again`() {
        val ended = System.currentTimeMillis()
        rule.setContent {
            StreamStagesColumn(
                StreamUiSnapshot(
                    stages = listOf(
                        StreamToolGroupStage(
                            id = "group-1",
                            type = StreamStageType.COMMAND,
                            steps = listOf(
                                commandStep("c1", "ls", StreamToolStatus.DONE, output = "a.txt"),
                                commandStep("c2", "cat package.json", StreamToolStatus.DONE),
                            ),
                            startedAtMs = ended - 3_000L,
                            endedAtMs = ended,
                            open = false,
                        ),
                    ),
                    liveActive = false,
                    runStartedAtMs = ended - 3_000L,
                    runEndedAtMs = ended,
                ),
            )
        }

        // 全部结束后自动收起：只剩一行「运行了 2 条命令」
        rule.onNodeWithText("运行了 2 条命令").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.tool("c1")).assertDoesNotExist()

        // 展开父级 → 看到两行（但每行输出仍隐藏）
        rule.onNodeWithTag(V2StreamTags.groupToggle("group-1")).performClick()
        rule.onNodeWithTag(V2StreamTags.tool("c1")).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.toolBody("c1")).assertDoesNotExist()

        // 再展开单条 → 输出出现（深色等宽块）
        rule.onNodeWithTag(V2StreamTags.tool("c1")).performClick()
        rule.onNodeWithTag(V2StreamTags.toolBody("c1")).assertIsDisplayed()
        rule.onNodeWithText("a.txt").assertIsDisplayed()
    }

    @Test
    fun `tool group uses call wording and completed count`() {
        rule.setContent {
            StreamStagesColumn(
                StreamUiSnapshot(
                    stages = listOf(
                        StreamToolGroupStage(
                            id = "group-t",
                            type = StreamStageType.TOOL,
                            steps = listOf(
                                StreamToolStep(
                                    id = "t1",
                                    name = "read_file",
                                    detail = "src/api.ts",
                                    output = "42 行",
                                    status = StreamToolStatus.DONE,
                                    category = "read",
                                ),
                            ),
                            startedAtMs = 1_000L,
                            endedAtMs = 2_000L,
                            open = false,
                        ),
                    ),
                    liveActive = false,
                    runStartedAtMs = 1_000L,
                    runEndedAtMs = 2_000L,
                ),
            )
        }

        rule.onNodeWithText("调用了 1 个工具").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.groupToggle("group-t")).performClick()
        rule.onNodeWithText("调用").assertIsDisplayed()
        rule.onNodeWithText("read_file").assertIsDisplayed()
    }

    // ------------------------------------------------------------ 阶段交替 / 状态

    @Test
    fun `thought text command and text stages render interleaved in order`() {
        val ended = System.currentTimeMillis()
        rule.setContent {
            StreamStagesColumn(
                StreamUiSnapshot(
                    stages = listOf(
                        StreamThoughtStage(
                            id = "thought-1",
                            text = "先确认目录。",
                            startedAtMs = ended - 8_000L,
                            endedAtMs = ended - 6_000L,
                            active = false,
                        ),
                        StreamTextStage(id = "text-1", text = "先看一下项目结构。"),
                        StreamToolGroupStage(
                            id = "group-1",
                            type = StreamStageType.COMMAND,
                            steps = listOf(commandStep("c1", "ls apps/", StreamToolStatus.DONE, output = "web")),
                            startedAtMs = ended - 4_000L,
                            endedAtMs = ended - 3_000L,
                            open = false,
                        ),
                        StreamTextStage(id = "text-2", text = "前端在 `apps/web`，用 Vite。"),
                    ),
                    liveActive = false,
                    runStartedAtMs = ended - 8_000L,
                    runEndedAtMs = ended,
                ),
            )
        }

        rule.onNodeWithText("思考过程").assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.text("text-1")).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.group("group-1")).assertIsDisplayed()
        rule.onNodeWithTag(V2StreamTags.text("text-2")).assertIsDisplayed()
        rule.onNodeWithText("先看一下项目结构。").assertIsDisplayed()
        rule.onNodeWithText("前端在", substring = true).assertIsDisplayed()
        rule.onNodeWithText("运行了 1 条命令").assertIsDisplayed()
    }

    @Test
    fun `status footer shows the current action and keeps total duration when done`() {
        var snapshot by mutableStateOf(
            StreamUiSnapshot(
                stages = listOf(
                    StreamToolGroupStage(
                        id = "group-1",
                        type = StreamStageType.TOOL,
                        steps = listOf(
                            StreamToolStep(id = "t1", name = "grep", status = StreamToolStatus.RUNNING, category = "other"),
                        ),
                        startedAtMs = System.currentTimeMillis(),
                        open = true,
                    ),
                ),
                liveActive = true,
                runStartedAtMs = System.currentTimeMillis(),
            ),
        )
        rule.setContent { StreamStagesColumn(snapshot) }

        rule.onNodeWithTag(V2StreamTags.STATUS_FOOTER).assertIsDisplayed()
        rule.onNodeWithText("正在调用工具…").assertIsDisplayed()

        rule.runOnIdle {
            val ended = snapshot.runStartedAtMs + 12_000L
            snapshot = StreamUiSnapshot(
                stages = listOf(
                    StreamToolGroupStage(
                        id = "group-1",
                        type = StreamStageType.TOOL,
                        steps = listOf(
                            StreamToolStep(
                                id = "t1",
                                name = "grep",
                                status = StreamToolStatus.DONE,
                                category = "other",
                            ),
                        ),
                        startedAtMs = snapshot.runStartedAtMs,
                        endedAtMs = ended,
                        open = false,
                    ),
                ),
                liveActive = false,
                runStartedAtMs = snapshot.runStartedAtMs,
                runEndedAtMs = ended,
                revision = 2,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("共用时 12秒").assertIsDisplayed()
    }

    // ------------------------------------------------------------ 收尾 / 崩溃回归

    @Test
    fun `duplicate message ids do not crash the lazy list`() {
        val duplicate = V2Message(
            role = V2MessageRole.AGENT,
            text = "重复消息",
            time = "12:00",
            id = "dup-id",
        )
        rule.setContent {
            V2ChatScreen(
                chat = chatState(),
                messages = listOf(duplicate, duplicate.copy(text = "重复消息 2")),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
            )
        }

        // 去重后只渲染一份，且不会抛「Key was already used」
        rule.onAllNodesWithText("重复消息").assertCountEquals(1)
        rule.onNodeWithText("重复消息 2").assertDoesNotExist()
    }

    @Test
    fun `settling a live stream leaves reviewable process and no duplicated answer`() {
        var snapshot by mutableStateOf(
            StreamUiSnapshot(
                stages = listOf(
                    StreamTextStage(id = "text-1", text = "流式答案"),
                    StreamToolGroupStage(
                        id = "group-1",
                        type = StreamStageType.COMMAND,
                        steps = listOf(commandStep("c1", "pytest -q", StreamToolStatus.RUNNING)),
                        startedAtMs = System.currentTimeMillis(),
                        open = true,
                    ),
                ),
                liveActive = true,
                runStartedAtMs = System.currentTimeMillis(),
            ),
        )
        rule.setContent { StreamStagesColumn(snapshot) }

        rule.onNodeWithTag(V2StreamTags.text("text-1")).assertIsDisplayed()

        // 收尾：正文交给落库消息（这里只验证流式正文消失），过程保留
        rule.runOnIdle {
            val ended = snapshot.runStartedAtMs + 5_000L
            snapshot = StreamUiSnapshot(
                stages = listOf(
                    StreamToolGroupStage(
                        id = "group-1",
                        type = StreamStageType.COMMAND,
                        steps = listOf(commandStep("c1", "pytest -q", StreamToolStatus.DONE)),
                        startedAtMs = snapshot.runStartedAtMs,
                        endedAtMs = ended,
                        open = false,
                    ),
                ),
                liveActive = false,
                runStartedAtMs = snapshot.runStartedAtMs,
                runEndedAtMs = ended,
                revision = 2,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.text("text-1")).assertDoesNotExist()
        rule.onNodeWithText("运行了 1 条命令").assertIsDisplayed()
        rule.onNodeWithText("共用时 5秒").assertIsDisplayed()
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
        val streamState = mutableStateOf(textSnapshot("第一段回答", revision = 1))
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
            streamState.value = textSnapshot("第一段回答，继续输出更多内容。", revision = 2)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertDoesNotExist()

        // 用户向上拖动：立即停止跟随，浮出「回到最新」
        rule.onNodeWithTag(V2ChatTags.MESSAGE_LIST).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag(V2StreamTags.JUMP_TO_BOTTOM).assertIsDisplayed()

        // 阅读期间新内容到达：只提示「有新内容」，绝不把用户拽回底部
        rule.runOnIdle {
            streamState.value = textSnapshot("第一段回答，继续输出更多内容。新到达的一段。", revision = 3)
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
