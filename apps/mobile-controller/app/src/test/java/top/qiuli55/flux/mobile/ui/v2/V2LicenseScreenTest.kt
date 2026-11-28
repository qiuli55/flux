package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import top.qiuli55.flux.mobile.BuildConfig

/** 「关于 / 开源许可」入口与页面声明，以及版本号不变的回归测试。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2LicenseScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `profile exposes the open source entry and keeps the title list in sync`() {
        var clicked = false
        rule.setContent {
            V2ProfileScreen(
                accountName = "邱",
                deviceName = "本机",
                deviceCount = 1,
                memoryCount = 0,
                billingNote = "暂无数据",
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
                onOpenSettings = {},
                onCheckUpdate = {},
                onOpenLicenses = { clicked = true },
            )
        }

        rule.onNodeWithText(OPEN_SOURCE_SCREEN_TITLE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue(clicked)
        assertTrue(PROFILE_SETTINGS_TITLES.contains(OPEN_SOURCE_SCREEN_TITLE))
    }

    @Test
    fun `license page names whisper_cpp version openai model and MIT`() {
        rule.setContent {
            V2LicenseScreen(
                version = "0.9.19",
                extraTop = 0.dp,
                showBack = true,
                onBack = {},
            )
        }

        rule.onNodeWithText(OPEN_SOURCE_SCREEN_TITLE).assertIsDisplayed()
        rule.onNodeWithText("whisper.cpp").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(WHISPER_CPP_VERSION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OpenAI Whisper 官方模型").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ggml-base-q5_1.bin", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(VOICE_MODEL_SHA_LICENSE_LINE).performScrollTo().assertIsDisplayed()
        // 模型随安装包提供，不再写「首次使用再下载」。
        rule.onNodeWithText("随安装包内置", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("随安装包一起提供", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // 引擎与模型各有一份 MIT 声明。
        assertTrue(rule.onAllNodesWithText("MIT").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `version name and code stay at the agreed baseline`() {
        assertEquals("0.9.19", BuildConfig.VERSION_NAME)
        assertEquals(1004, BuildConfig.VERSION_CODE)
    }

    private companion object {
        const val VOICE_MODEL_SHA_LICENSE_LINE =
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    }
}
