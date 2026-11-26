package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.TaskContextUsage

/**
 * 输入栏新控件与权限模式视觉的回归测试。
 *
 * 覆盖：
 *  - runtime → 官方图标的匹配（claude-code / codex / opencode，未知回落占位）；
 *  - 切换 Agent 后模型清单跟着换（不会沿用旧 Agent 的模型）；
 *  - 上下文占用改到输入栏环形指示：点开仍有具体数字，顶部不再有整行进度条；
 *  - 四个权限模式仍有各自的图标与标题色，完全访问不与免审核撞红。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ComposerControlsTest {

    @get:Rule
    val rule = createComposeRule()

    private val agentA = V2Agent(
        id = "a",
        name = "claude-code-agent",
        role = "编码 Agent",
        load = 0,
        running = 0,
        tint = Color.Black,
        runtime = "claude-code",
    )
    private val agentB = V2Agent(
        id = "b",
        name = "codex-agent",
        role = "编码 Agent",
        load = 0,
        running = 0,
        tint = Color.Black,
        runtime = "codex",
    )

    private fun usage(percent: Int, level: String = "normal") = TaskContextUsage(
        limit = 20,
        messageCount = 8,
        usedMessages = 8,
        usedChars = 12_300,
        budgetChars = 30_000,
        ratio = percent / 100.0,
        percent = percent,
        level = level,
    )

    @Test
    fun `known runtimes resolve to distinct official icons and unknown falls back`() {
        val claude = agentRuntimeIconRes("claude-code")
        val codex = agentRuntimeIconRes("codex")
        val opencode = agentRuntimeIconRes("opencode")
        assertNotNull(claude)
        assertNotNull(codex)
        assertNotNull(opencode)
        assertEquals(3, setOf(claude, codex, opencode).size)
        // 大小写 / 下划线兼容
        assertEquals(claude, agentRuntimeIconRes("Claude_Code"))
        assertNull(agentRuntimeIconRes("dsh"))
        assertNull(agentRuntimeIconRes(""))
    }

    @Test
    fun `models follow the selected agent`() {
        val options = listOf(
            V2AgentOption(agentA, listOf(V2ModelOption("claude-native"))),
            V2AgentOption(
                agentB,
                listOf(V2ModelOption("gpt-native"), V2ModelOption("vault-model", keyId = "k1")),
            ),
        )
        assertEquals(listOf("claude-native"), modelsForAgent(options, "a").map { it.model })
        assertEquals(listOf("gpt-native", "vault-model"), modelsForAgent(options, "b").map { it.model })
        // 找不到 Agent 时返回空，模型入口禁用，而不是沿用旧列表
        assertEquals(emptyList<V2ModelOption>(), modelsForAgent(options, "missing"))
    }

    @Test
    fun `permission modes keep four semantics with distinct icons and colors`() {
        val plan = permissionModeMeta("plan")
        val normal = permissionModeMeta("normal")
        val full = permissionModeMeta("full")
        val unattended = permissionModeMeta("unattended")

        assertEquals(
            listOf("Plan 模式", "正常模式", "完全访问", "免审核"),
            listOf(plan.name, normal.name, full.name, unattended.name),
        )
        assertEquals(4, setOf(plan.icon, normal.icon, full.icon, unattended.icon).size)
        // 完全访问与免审核不能撞色：一个是明确放行（蓝），一个是警示红。
        assertNotEquals(full.color, unattended.color)
        assertEquals(V2Colors.rose, unattended.color)
        assertEquals(V2Colors.amber700, normal.color)
    }

    @Test
    fun `context hint and ring color reflect usage level`() {
        assertNotEquals(contextRingColor(usage(20, "normal")), contextRingColor(usage(85, "warning")))
        assertNotEquals(contextRingColor(usage(85, "warning")), contextRingColor(usage(100, "full")))
        assertEquals(V2Colors.rose, contextRingColor(usage(100, "full")))
        assertEquals(V2Colors.amber, contextRingColor(usage(85, "warning")))
        assertNotEquals(contextUsageHint("normal"), contextUsageHint("warning"))
        assertNotEquals(contextUsageHint("warning"), contextUsageHint("full"))
    }

    @Test
    fun `composer permission icon follows the selected mode`() {
        var mode by mutableStateOf("normal")
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = "task-1",
                    title = "构建失败排查",
                    device = "本机",
                    agentId = agentA.id,
                    model = "claude-native",
                    permissionMode = mode,
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                agentOptions = listOf(V2AgentOption(agentA, listOf(V2ModelOption("claude-native")))),
                modelOptions = listOf(V2ModelOption("claude-native")),
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        rule.onNodeWithContentDescription("对话权限模式：正常模式").assertIsDisplayed()
        rule.runOnIdle { mode = "plan" }
        rule.onNodeWithContentDescription("对话权限模式：Plan 模式").assertIsDisplayed()
        rule.runOnIdle { mode = "full" }
        rule.onNodeWithContentDescription("对话权限模式：完全访问").assertIsDisplayed()
        rule.runOnIdle { mode = "unattended" }
        rule.onNodeWithContentDescription("对话权限模式：免审核").assertIsDisplayed()
    }

    @Test
    fun `context ring replaces top strip and still exposes numbers on tap`() {
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = "task-1",
                    title = "构建失败排查",
                    device = "本机",
                    agentId = agentA.id,
                    model = "claude-native",
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                agentOptions = listOf(V2AgentOption(agentA, listOf(V2ModelOption("claude-native")))),
                modelOptions = listOf(V2ModelOption("claude-native")),
                contextUsage = usage(42),
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        // 顶部整行进度条已去掉；占用信息只在输入栏旁的环里。
        rule.onNodeWithText("上下文").assertDoesNotExist()
        rule.onNodeWithTag(V2ComposerTags.CONTEXT_RING).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.MODEL_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.AGENT_BUTTON).assertIsDisplayed()

        rule.onNodeWithTag(V2ComposerTags.CONTEXT_RING).performClick()
        rule.onNodeWithTag(V2ComposerTags.CONTEXT_INFO).assertIsDisplayed()
        rule.onNodeWithText("42%").assertIsDisplayed()
        rule.onNodeWithText("消息 8 / 20 条").assertIsDisplayed()
    }

    @Test
    fun `switching agent swaps the model list in the composer`() {
        var state by mutableStateOf(
            V2ChatState(
                conversationId = "task-1",
                title = "构建失败排查",
                device = "本机",
                agentId = agentA.id,
                model = "claude-native",
            ),
        )
        val options = listOf(
            V2AgentOption(agentA, listOf(V2ModelOption("claude-native"))),
            V2AgentOption(agentB, listOf(V2ModelOption("gpt-native"), V2ModelOption("gpt-mini"))),
        )
        rule.setContent {
            V2ChatScreen(
                chat = state,
                messages = emptyList(),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                agentOptions = options,
                modelOptions = modelsForAgent(options, state.agentId),
                onSwitchAgent = { agentId ->
                    val next = modelsForAgent(options, agentId).firstOrNull()
                    state = state.copy(
                        agentId = agentId,
                        model = next?.model.orEmpty(),
                        modelKeyId = next?.keyId,
                    )
                },
                onSwitchModel = { option ->
                    state = state.copy(model = option.model, modelKeyId = option.keyId)
                },
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        // 先看旧 Agent 的模型（点当前项关闭菜单，不改状态）
        rule.onNodeWithTag(V2ComposerTags.MODEL_BUTTON).performClick()
        rule.onNodeWithText("claude-native").assertIsDisplayed()
        rule.onNodeWithText("claude-native").performClick()

        // 切到 codex-agent
        rule.onNodeWithTag(V2ComposerTags.AGENT_BUTTON).performClick()
        rule.onNodeWithText("codex-agent").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.MODEL_BUTTON).performClick()
        rule.onNodeWithText("gpt-native").assertIsDisplayed()
        rule.onNodeWithText("gpt-mini").assertIsDisplayed()
        rule.onNodeWithText("claude-native").assertDoesNotExist()
    }
}
