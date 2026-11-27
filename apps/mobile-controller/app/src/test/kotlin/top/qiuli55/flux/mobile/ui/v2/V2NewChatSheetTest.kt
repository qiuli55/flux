package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 新建对话选择 Agent 的列表：
 * 保留「运行 N 个任务」，不再展示「负载 N%」——该百分比对用户选择没有帮助。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2NewChatSheetTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `agent row keeps running count but drops load percent`() {
        val agent = V2Agent(
            id = "codex-agent",
            name = "codex-agent",
            role = "编码 Agent",
            load = 73,
            running = 4,
            tint = Color.Black,
            runtime = "codex",
        )
        rule.setContent {
            NewChatSheet(
                device = "本机",
                options = listOf(V2AgentOption(agent, listOf(V2ModelOption("gpt-native")))),
                onPick = {},
                onDismiss = {},
            )
        }

        rule.onNodeWithText("编码 Agent · 运行 4 个任务").assertIsDisplayed()
        rule.onNodeWithText("73 %").assertDoesNotExist()
        rule.onNodeWithText("负载").assertDoesNotExist()
    }
}
