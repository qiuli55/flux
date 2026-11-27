package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.CodexAccountStatus
import top.qiuli55.flux.mobile.data.UserMemoryEntry

/**
 * 设置 / 记忆面板的误触保护（Robolectric / JVM）。
 *
 * 覆盖：
 *  - 退出登录：先确认，取消不退出，确认只退出一次；
 *  - 密集小胶囊（记忆操作）的触摸目标 >= 48dp。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2DestructiveConfirmTest {

    @get:Rule
    val rule = createComposeRule()

    private var logoutCount = 0

    private fun renderSettings() {
        logoutCount = 0
        rule.setContent {
            SettingsSheet(
                account = null,
                deviceName = "本机",
                deviceCount = 1,
                haptics = true,
                reduceMotion = false,
                decisionAlerts = true,
                taskAlerts = true,
                strict = false,
                onHaptics = {},
                onReduceMotion = {},
                onDecisionAlerts = {},
                onTaskAlerts = {},
                onStrict = {},
                onOpenDevices = {},
                onOpenTerminals = {},
                onOpenKeyVault = {},
                onOpenMemories = {},
                codexAccount = CodexAccountStatus(),
                codexLogin = null,
                codexLoading = false,
                onCodexLogin = {},
                onCodexLogout = {},
                onRefreshCodex = {},
                onOpenCodexUrl = {},
                githubAccount = null,
                githubLoading = false,
                githubRepos = emptyList(),
                githubReposLoading = false,
                onGitHubLogin = {},
                onGitHubLogout = {},
                onRefreshGitHub = {},
                onLoadGitHubRepos = {},
                onManualConnect = {},
                onLogout = { logoutCount++ },
                updateHint = "",
                updateBadge = false,
                onCheckUpdate = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
    }

    private fun assertMinTouchTarget(tag: String) {
        val bounds = rule.onNodeWithTag(tag).getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("'$tag' height $height should be >= 48dp", height >= 48.dp)
    }

    @Test
    fun `logout asks first and cancel keeps the session`() {
        renderSettings()
        rule.onNodeWithText("退出登录").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("退出登录？").assertIsDisplayed()
        assertEquals(0, logoutCount)

        rule.onNodeWithTag(FluxConfirmTags.DISMISS).performClick()
        rule.waitForIdle()
        assertEquals(0, logoutCount)
    }

    @Test
    fun `confirming logout logs out once`() {
        renderSettings()
        rule.onNodeWithText("退出登录").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(FluxConfirmTags.CONFIRM).performClick()
        rule.waitForIdle()
        assertEquals(1, logoutCount)
    }

    @Test
    fun `memory action pills are at least 48dp tall`() {
        rule.setContent {
            MemorySheet(
                memories = listOf(UserMemoryEntry(id = "m1", content = "偏好简洁回答")),
                loading = false,
                saving = false,
                autoCapture = true,
                cloudReady = true,
                cloudError = "",
                onRefresh = {},
                onSync = {},
                onToggleAuto = {},
                onAdd = { _, _ -> },
                onUpdate = { _, _, _ -> },
                onDelete = { _, _ -> },
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        assertMinTouchTarget(V2UxTags.MEMORY_PILL_PREFIX + "同步")
        assertMinTouchTarget(V2UxTags.MEMORY_PILL_PREFIX + "刷新")
        assertMinTouchTarget(V2UxTags.MEMORY_PILL_PREFIX + "添加")
    }
}
