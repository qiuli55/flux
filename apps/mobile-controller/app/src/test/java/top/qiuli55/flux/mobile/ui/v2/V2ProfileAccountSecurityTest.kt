package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「我的」账号入口接线：账号卡和「账号与安全」都走账号云入口，
 * 「通知与偏好」仍走原来的设置面板。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ProfileAccountSecurityTest {

    @get:Rule
    val rule = createComposeRule()

    private var securityCount = 0
    private var settingsCount = 0

    private fun render() {
        securityCount = 0
        settingsCount = 0
        rule.setContent {
            V2ProfileScreen(
                accountName = "测试账号",
                deviceName = "本机",
                deviceCount = 1,
                memoryCount = 0,
                billingNote = "额度正常",
                version = "0.9.19",
                connectorCount = 0,
                subscriptionCount = 0,
                extraTop = 0.dp,
                onOpenDevices = {},
                onOpenConnectors = {},
                onOpenSubscriptions = {},
                onOpenTerminals = {},
                onOpenKeys = {},
                onOpenBilling = {},
                onOpenMemories = {},
                onOpenBrowser = {},
                onOpenAccountSecurity = { securityCount += 1 },
                onOpenSettings = { settingsCount += 1 },
                onCheckUpdate = {},
            )
        }
    }

    @Test
    fun `account security row is visible`() {
        render()
        rule.onNodeWithText("账号与安全").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `account card opens account security instead of settings`() {
        render()
        rule.onNodeWithText("测试账号").performClick()

        assertEquals(1, securityCount)
        assertEquals(0, settingsCount)
    }

    @Test
    fun `account security row opens account security`() {
        render()
        rule.onNodeWithText("账号与安全").performScrollTo().performClick()

        assertEquals(1, securityCount)
        assertEquals(0, settingsCount)
    }

    @Test
    fun `notification row still opens settings`() {
        render()
        rule.onNodeWithText("通知与偏好").performScrollTo().performClick()

        assertEquals(0, securityCount)
        assertEquals(1, settingsCount)
    }
}
