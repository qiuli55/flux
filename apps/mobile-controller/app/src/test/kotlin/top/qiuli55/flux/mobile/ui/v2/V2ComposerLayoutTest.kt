package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 输入区工具条的交互回归测试。
 *
 * 复现用户截图里的问题：模型 chip 文案很长时（例如
 * 「codex-agent · proxy-deepseek-v4.1-flash」被后端拼得更长），窄屏（320 / 360dp）
 * 叠加大字号（fontScale 1.5）会把发送按钮挤出屏幕，用户无法发送。
 *
 * 断言：
 *  - 发送 / 加号 / 盾牌三个按钮都完整落在输入卡根容器内，宽度 >= 48dp，可点击；
 *  - 模型名很短（正常路径）时同样成立；
 *  - 空输入时发送按钮禁用（与 V2ChatScreen 现有行为一致）。
 *
 * 跑在 JVM 上（Robolectric），由 :app:testDebugUnitTest 执行，不依赖模拟器。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ComposerLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val agentId = "codex-agent"

    /** 80 个字符的超长模型名：足以在 320dp 屏上挤掉右侧按钮。 */
    private val longModel = "proxy-deepseek-v4.1-flash-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOP"

    private val shortModel = "deepseek-v4.1-flash"

    private val sent = mutableListOf<String>()

    private fun render(
        widthDp: Int,
        fontScale: Float,
        modelLabel: String,
        input: String? = "帮我做一个网页",
    ) {
        sent.clear()
        rule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(baseDensity, fontScale)) {
                Box(Modifier.width(widthDp.dp).testTag(TEST_ROOT)) {
                    V2ChatScreen(
                        chat = V2ChatState(
                            conversationId = null,
                            title = "新的对话",
                            device = "本机",
                            agentId = agentId,
                            model = modelLabel,
                        ),
                        messages = emptyList(),
                        extraTop = 0.dp,
                        onBack = {},
                        onOpenLogs = {},
                        onDelete = {},
                        onOpenAgentPicker = {},
                        onSend = { sent += it },
                        agentOptions = listOf(
                            V2AgentOption(
                                V2Agent(agentId, "codex-agent", "编码 Agent", 0, 0, V2Colors.accent),
                                listOf(V2ModelOption(modelLabel, modelLabel)),
                            ),
                        ),
                        suggestions = emptyList(),
                        showHandoff = false,
                    )
                }
            }
        }
        rule.waitForIdle()
        if (!input.isNullOrEmpty()) {
            rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput(input)
            rule.waitForIdle()
        }
    }

    private fun assertActionFits(tag: String) {
        val root = rule.onNodeWithTag(TEST_ROOT).getUnclippedBoundsInRoot()
        val node = rule.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val nodeWidth = bounds.right - bounds.left
        assertTrue("$tag width $nodeWidth should be >= 48dp", nodeWidth >= 48.dp)
        assertTrue("$tag left ${bounds.left} < root left ${root.left}", bounds.left >= root.left)
        assertTrue("$tag top ${bounds.top} < root top ${root.top}", bounds.top >= root.top)
        assertTrue("$tag right ${bounds.right} > root right ${root.right}", bounds.right <= root.right)
        assertTrue("$tag bottom ${bounds.bottom} > root bottom ${root.bottom}", bounds.bottom <= root.bottom)
    }

    @Test
    fun `long model name keeps all composer actions visible at 320dp font scale 1`() {
        render(widthDp = 320, fontScale = 1f, modelLabel = longModel)
        assertActionFits(V2ComposerTags.SEND)
        assertActionFits(V2ComposerTags.ATTACH)
        assertActionFits(V2ComposerTags.PERMISSION)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertHasClickAction()
        rule.onNodeWithTag(V2ComposerTags.MODEL_LABEL, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `long model name keeps all composer actions visible at 320dp font scale 1_5`() {
        render(widthDp = 320, fontScale = 1.5f, modelLabel = longModel)
        assertActionFits(V2ComposerTags.SEND)
        assertActionFits(V2ComposerTags.ATTACH)
        assertActionFits(V2ComposerTags.PERMISSION)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertHasClickAction()
    }

    @Test
    fun `long model name keeps all composer actions visible at 360dp font scale 1`() {
        render(widthDp = 360, fontScale = 1f, modelLabel = longModel)
        assertActionFits(V2ComposerTags.SEND)
        assertActionFits(V2ComposerTags.ATTACH)
        assertActionFits(V2ComposerTags.PERMISSION)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertHasClickAction()
    }

    @Test
    fun `long model name keeps all composer actions visible at 360dp font scale 1_5`() {
        render(widthDp = 360, fontScale = 1.5f, modelLabel = longModel)
        assertActionFits(V2ComposerTags.SEND)
        assertActionFits(V2ComposerTags.ATTACH)
        assertActionFits(V2ComposerTags.PERMISSION)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertHasClickAction()
    }

    @Test
    fun `short model name keeps composer usable on the normal path`() {
        render(widthDp = 360, fontScale = 1f, modelLabel = shortModel)
        assertActionFits(V2ComposerTags.SEND)
        assertActionFits(V2ComposerTags.ATTACH)
        assertActionFits(V2ComposerTags.PERMISSION)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertHasClickAction()
        rule.onNodeWithTag(V2ComposerTags.MODEL_LABEL, useUnmergedTree = true).assertIsDisplayed()
        // 短模型名时 chip 不撑满，发送钮仍应贴在工具条右端，
        // 防止 weight(fill = false) 留下整段右侧空白。
        val toolbar = rule.onNodeWithTag(V2ComposerTags.TOOLBAR).getUnclippedBoundsInRoot()
        val send = rule.onNodeWithTag(V2ComposerTags.SEND).getUnclippedBoundsInRoot()
        val rightGap = toolbar.right - send.right
        assertTrue("send right gap $rightGap should be <= 1dp", rightGap <= 1.dp)
    }

    @Test
    fun `empty input disables send and produces no message`() {
        render(widthDp = 360, fontScale = 1f, modelLabel = shortModel, input = null)
        rule.onNodeWithTag(V2ComposerTags.SEND).assertIsNotEnabled()
        assertEquals(emptyList<String>(), sent)
    }

    private companion object {
        const val TEST_ROOT = "v2_composer_test_root"
    }
}
