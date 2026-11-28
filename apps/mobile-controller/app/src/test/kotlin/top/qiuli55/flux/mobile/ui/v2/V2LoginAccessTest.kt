package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudAuthApi
import top.qiuli55.flux.mobile.ui.auth.LoginViewModel

/**
 * V2 登录页：按账号云发信能力露出「注册」「忘记密码」。
 *
 * 只锁入口是否出现与注册面板能打开；真正的注册 / 重置流程由
 * [top.qiuli55.flux.mobile.ui.auth.LoginAccessViewModelTest] 覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2LoginAccessTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeCloudAuth(private val mailEnabled: Boolean) : CloudAuthApi {
        override fun oauthStartUrl(
            provider: String,
            redirect: String,
            codeChallenge: String,
        ): String = "https://cloud.test/oauth"

        override suspend fun login(identifier: String, password: String): AccountSession =
            AccountSession(accessToken = "a", refreshToken = "r")

        override suspend fun exchange(code: String, codeVerifier: String): AccountSession =
            AccountSession(accessToken = "a", refreshToken = "r")

        override suspend fun mailStatus(): Boolean = mailEnabled

        override suspend fun sendRegisterCode(email: String) = Unit

        override suspend fun register(
            email: String,
            username: String,
            password: String,
            code: String?,
        ): AccountSession = AccountSession(accessToken = "a", refreshToken = "r")

        override suspend fun forgotPassword(email: String) = Unit

        override suspend fun resetPassword(
            email: String,
            code: String,
            newPassword: String,
        ): String = ""
    }

    private fun render(mailEnabled: Boolean): LoginViewModel {
        val vm = LoginViewModel(FakeCloudAuth(mailEnabled)) {}
        rule.setContent {
            V2LoginScreen(
                onSignedIn = {},
                onManualConnected = {},
                vm = vm,
            )
        }
        rule.waitForIdle()
        return vm
    }

    @Test
    fun `mail enabled shows register and forgot password entries`() {
        render(mailEnabled = true)

        rule.onNodeWithText("注册").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("忘记密码").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `mail disabled hides forgot password but keeps register`() {
        render(mailEnabled = false)

        rule.onNodeWithText("注册").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("忘记密码").assertDoesNotExist()
    }

    @Test
    fun `register entry opens the register sheet`() {
        render(mailEnabled = true)

        rule.onNodeWithText("注册").performScrollTo().performClick()
        rule.onNodeWithText("注册并登录").assertIsDisplayed()
    }
}
