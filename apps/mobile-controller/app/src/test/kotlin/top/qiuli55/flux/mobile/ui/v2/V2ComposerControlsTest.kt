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
import androidx.compose.ui.test.performTextInput
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
    fun `agent trigger shows only the runtime icon and keeps the name in the menu`() {
        rule.setContent {
            AgentPill(
                agent = agentB,
                pendingAgentLabel = null,
                enabled = true,
                onClick = {},
            )
        }

        rule.onNodeWithTag(V2ComposerTags.AGENT_BUTTON).assertIsDisplayed()
        // 名称藏在 contentDescription 里，触发器表面不再有文本。
        rule.onNodeWithContentDescription("切换 Agent：codex-agent").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.AGENT_ICON, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("codex-agent").assertDoesNotExist()
    }

    @Test
    fun `high privilege modes no longer show the notice above the composer`() {
        var mode by mutableStateOf("full")
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
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        // 完全访问 / 免审核的红色提示条已从输入区上方去掉。
        rule.onNodeWithText("完全访问 · 高风险命令", substring = true).assertDoesNotExist()
        rule.runOnIdle { mode = "unattended" }
        rule.onNodeWithText("免审核 · 高风险命令", substring = true).assertDoesNotExist()
        // 盾牌仍然随模式变化。
        rule.onNodeWithContentDescription("对话权限模式：免审核").assertIsDisplayed()
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
    fun `agent popup shows readable model subtitle instead of role`() {
        val vault = V2ModelOption("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", keyId = "k1")
        val options = listOf(
            V2AgentOption(
                agentA,
                listOf(V2ModelOption("claude-native", "Claude Sonnet 4.6"), vault),
            ),
            V2AgentOption(agentB, listOf(V2ModelOption("gpt-native", "GPT-5.6 Luna"))),
        )
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = "task-1",
                    title = "构建失败排查",
                    device = "本机",
                    agentId = agentA.id,
                    model = vault.model,
                    modelKeyId = vault.keyId,
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                agentOptions = options,
                modelOptions = modelsForAgent(options, agentA.id),
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        rule.onNodeWithTag(V2ComposerTags.AGENT_BUTTON).performClick()
        rule.onNodeWithTag(V2ComposerTags.AGENT_MENU).assertIsDisplayed()
        // 当前 Agent：跟正在用的模型走；未选中 Agent：显示切过去会用的模型。
        rule.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
        rule.onNodeWithText("GPT-5.6 Luna").assertIsDisplayed()
        // 不再出现旧的「role · 模型」内部写法。
        rule.onNodeWithText("developer", substring = true).assertDoesNotExist()
    }

    @Test
    fun `queued agent switch keeps pending hint and target model`() {
        val options = listOf(
            V2AgentOption(agentA, listOf(V2ModelOption("claude-native", "Claude Sonnet 4.6"))),
            V2AgentOption(agentB, listOf(V2ModelOption("gpt-native", "GPT-5.6 Luna"))),
        )
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
                agentOptions = options,
                modelOptions = modelsForAgent(options, agentA.id),
                pendingAgentId = agentB.id,
                pendingAgentLabel = agentB.name,
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        rule.onNodeWithTag(V2ComposerTags.AGENT_BUTTON).performClick()
        rule.onNodeWithText("本轮结束后切换").assertIsDisplayed()
        // 副行显示排队生效后会用到的模型，而不是当前 Agent 的模型。
        rule.onNodeWithTag(V2ComposerTags.AGENT_MENU_ROW_PREFIX + agentB.id)
            .assertIsDisplayed()
        rule.onNodeWithText("GPT-5.6 Luna").assertIsDisplayed()
    }

    @Test
    fun `model popup highlights current model and vault source`() {
        val vault = V2ModelOption("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", keyId = "k1")
        val options = listOf(V2ModelOption("claude-native", "Claude Sonnet 4.6"), vault)
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
                agentOptions = listOf(V2AgentOption(agentA, options)),
                modelOptions = options,
                suggestions = emptyList(),
                showHandoff = false,
            )
        }

        rule.onNodeWithTag(V2ComposerTags.MODEL_BUTTON).performClick()
        rule.onNodeWithTag(V2ComposerTags.MODEL_MENU).assertIsDisplayed()
        // 第一层是当前模型卡片：只显示当前选中模型，其余模型进第二层
        rule.onNodeWithText("Claude Sonnet 4.6").assertIsDisplayed()
        rule.onNodeWithText("当前在用").assertDoesNotExist()
        rule.onNodeWithTag(V2ComposerTags.MODEL_MORE).performClick()
        // 第二层是大标题「切换模型」，不是整页新导航，也没有左上角返回栈。
        rule.onNodeWithText("切换模型").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.MODEL_SEARCH).assertIsDisplayed()
        rule.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
        rule.onNodeWithText("当前在用").assertIsDisplayed()

        // 搜索只过滤当前 Agent 的模型（可按模型名 / 通道名）
        rule.onNodeWithTag(V2ComposerTags.MODEL_SEARCH).performTextInput("deepseek")
        rule.onNodeWithText("DeepSeek V4.1 Flash").assertIsDisplayed()
        rule.onNodeWithText("Claude Sonnet 4.6").assertDoesNotExist()
    }

    @Test
    fun `switch model sheet keeps two accounts and each key as separate chips`() {
        val options = listOf(
            V2ModelOption(
                "kimi-k2",
                "kimi-k2",
                accountId = "a1",
                channelId = "subscription:a1",
                channelKind = "subscription",
                channelLabel = "Kimi 订阅 · qiuli",
                thinking = V2Thinking(kind = "toggle", levels = listOf("on", "off"), labels = listOf("开", "关"), default = "on", canDisable = true),
            ),
            V2ModelOption(
                "kimi-k2-thinking",
                "kimi-k2-thinking",
                accountId = "a2",
                channelId = "subscription:a2",
                channelKind = "subscription",
                channelLabel = "Kimi 订阅 · qiuli1",
                thinking = V2Thinking(kind = "always", default = "always"),
            ),
            V2ModelOption(
                "deepseek-v4-pro",
                "deepseek-v4-pro",
                keyId = "k1",
                channelId = "key:k1",
                channelKind = "key",
                channelLabel = "密钥库 · opencode",
                thinking = V2Thinking(kind = "levels", levels = listOf("none", "low", "high", "max"), labels = listOf("无思考", "低", "高", "最高"), default = "low", canDisable = true),
            ),
        )
        val channels = listOf(
            V2ModelChannel("subscription:a1", "subscription", "Kimi 订阅 · qiuli", chipLabel = "qiuli"),
            V2ModelChannel("subscription:a2", "subscription", "Kimi 订阅 · qiuli1", chipLabel = "qiuli1"),
            V2ModelChannel("key:k1", "key", "密钥库 · opencode", chipLabel = "opencode"),
        )
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = "task-2",
                    title = "选模",
                    device = "本机",
                    agentId = agentA.id,
                    model = "kimi-k2",
                    modelAccountId = "a1",
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                showBack = false,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = {},
                modelOptions = options,
                modelChannels = channels,
                suggestions = emptyList(),
                showHandoff = false,
            )
        }
        rule.onNodeWithTag(V2ComposerTags.MODEL_BUTTON).performClick()
        // 小卡片显示来源与思考档
        rule.onNodeWithText("Kimi 订阅 · qiuli").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.MODEL_MORE).performClick()
        // 两个同厂订阅账号是两个分栏，不会合并
        rule.onNodeWithTag(V2ComposerTags.MODEL_CHANNEL_PREFIX + "subscription:a1").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.MODEL_CHANNEL_PREFIX + "subscription:a2").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.MODEL_CHANNEL_PREFIX + "key:k1").assertIsDisplayed()
        rule.onNodeWithText("qiuli").assertIsDisplayed()
        rule.onNodeWithText("qiuli1").assertIsDisplayed()
        rule.onNodeWithText("opencode").assertIsDisplayed()
        // 来源行
        rule.onNodeWithText("Kimi 订阅 · qiuli1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("密钥库 · opencode", substring = true).assertIsDisplayed()
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
        // 其余模型在第二层「更多模型」里
        rule.onNodeWithTag(V2ComposerTags.MODEL_MORE).performClick()
        rule.onNodeWithText("gpt-mini").assertIsDisplayed()
        rule.onNodeWithText("claude-native").assertDoesNotExist()
    }
}
