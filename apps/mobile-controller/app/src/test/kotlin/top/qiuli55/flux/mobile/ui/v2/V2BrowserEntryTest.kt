package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp

/**
 * 「我的」里的内置浏览器入口（Robolectric / JVM）。
 *
 * 不新增底栏 Tab，从已有「我的」页面打开一个空白站点会话；这里只锁入口可点、
 * 只触发一次，真正的空白会话策略由 [V2BrowserSessionTest] 覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2BrowserEntryTest {

    @get:Rule
    val rule = createComposeRule()

    private var openBrowserCount = 0

    @Test
    fun `profile opens the built in browser once`() {
        openBrowserCount = 0
        rule.setContent {
            V2ProfileScreen(
                accountName = "测试账号",
                deviceName = "本机",
                deviceCount = 1,
                memoryCount = 0,
                billingNote = "额度正常",
                version = "0.9.14",
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
                onOpenBrowser = { openBrowserCount += 1 },
                onOpenAccountSecurity = {},
                onOpenSettings = {},
                onCheckUpdate = {},
            )
        }

        rule.onNodeWithText("内置浏览器")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, openBrowserCount)
    }
}
