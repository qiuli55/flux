package top.qiuli55.flux.mobile.ui.auth

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudAuthApi

/**
 * 登录页注册 / 找回密码的状态机（JVM / Robolectric）。
 *
 * 用假的 [CloudAuthApi] 验证：注册成功会写会话并直接登录、重置密码不写会话、
 * 发信冷却与密码本地预检。网络与真机流程不在这里覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LoginAccessViewModelTest {

    private class FakeCloudAuth(var mailEnabled: Boolean = true) : CloudAuthApi {
        val session = AccountSession(
            accessToken = "access",
            refreshToken = "refresh",
            username = "newuser",
        )
        var registerCalls = 0
        var lastEmail: String? = null
        var lastUsername: String? = null
        var lastCode: String? = null
        var resetCalls = 0
        var lastResetEmail: String? = null
        var lastResetCode: String? = null
        val sentCodes = mutableListOf<String>()

        override fun oauthStartUrl(
            provider: String,
            redirect: String,
            codeChallenge: String,
        ): String = "https://cloud.test/oauth"

        override suspend fun login(identifier: String, password: String): AccountSession = session

        override suspend fun exchange(code: String, codeVerifier: String): AccountSession = session

        override suspend fun mailStatus(): Boolean = mailEnabled

        override suspend fun sendRegisterCode(email: String) {
            sentCodes += email
        }

        override suspend fun register(
            email: String,
            username: String,
            password: String,
            code: String?,
        ): AccountSession {
            registerCalls += 1
            lastEmail = email
            lastUsername = username
            lastCode = code
            return session.copy(username = username)
        }

        override suspend fun forgotPassword(email: String) {
            sentCodes += email
        }

        override suspend fun resetPassword(
            email: String,
            code: String,
            newPassword: String,
        ): String {
            resetCalls += 1
            lastResetEmail = email
            lastResetCode = code
            return """{"message":"ok"}"""
        }
    }

    @Test
    fun `register with mail disabled skips the code and signs in`() {
        val cloud = FakeCloudAuth(mailEnabled = false)
        val saved = mutableListOf<AccountSession>()
        val vm = LoginViewModel(cloud) { saved += it }
        shadowOf(Looper.getMainLooper()).idle()

        vm.openRegister()
        vm.onRegisterEmailChange("new@example.com")
        vm.onRegisterUsernameChange("newuser")
        vm.onRegisterPasswordChange("password123")

        var signedIn: AccountSession? = null
        vm.submitRegister { signedIn = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, cloud.registerCalls)
        assertEquals("new@example.com", cloud.lastEmail)
        assertNull(cloud.lastCode)
        assertEquals(1, saved.size)
        assertEquals("access", saved.single().accessToken)
        assertNotNull(signedIn)
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `register with mail enabled requires a code`() {
        val cloud = FakeCloudAuth(mailEnabled = true)
        val vm = LoginViewModel(cloud) {}
        shadowOf(Looper.getMainLooper()).idle()

        vm.openRegister()
        vm.onRegisterEmailChange("new@example.com")
        vm.onRegisterUsernameChange("newuser")
        vm.onRegisterPasswordChange("password123")

        vm.submitRegister {}
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, cloud.registerCalls)
        assertEquals("请输入邮箱验证码", vm.state.value.register.error)
    }

    @Test
    fun `send register code starts a local cooldown`() {
        val cloud = FakeCloudAuth(mailEnabled = true)
        val vm = LoginViewModel(cloud) {}
        vm.openRegister()
        vm.onRegisterEmailChange("new@example.com")

        vm.sendRegisterCode()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("new@example.com"), cloud.sentCodes)
        assertEquals(LoginViewModel.CODE_COOLDOWN_SEC, vm.state.value.register.cooldownSec)
        vm.dismissAccessSheet()
    }

    @Test
    fun `reset password returns to login without writing a session`() {
        val cloud = FakeCloudAuth(mailEnabled = true)
        val saved = mutableListOf<AccountSession>()
        val vm = LoginViewModel(cloud) { saved += it }
        shadowOf(Looper.getMainLooper()).idle()

        vm.openForgot()
        vm.onForgotEmailChange("user@example.com")
        vm.onForgotCodeChange("123456")
        vm.onForgotPasswordChange("newpass123")

        vm.submitForgotReset()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, cloud.resetCalls)
        assertEquals("user@example.com", cloud.lastResetEmail)
        assertTrue(saved.isEmpty())
        assertNull(vm.state.value.sheet)
        assertEquals("密码已重置，请使用新密码登录", vm.state.value.notice)
        assertEquals("user@example.com", vm.state.value.identifier)
    }

    @Test
    fun `password precheck rejects weak and self referential passwords`() {
        assertEquals("密码至少 8 位", validateNewPassword("a1", "", ""))
        assertEquals(
            "密码需要同时包含字母和数字",
            validateNewPassword("password", "user@example.com", "someone"),
        )
        assertEquals(
            "密码不能和邮箱相同",
            validateNewPassword("abc12345@example.com", "abc12345@example.com", "someone"),
        )
        assertEquals(
            "密码不能和邮箱前缀相同",
            validateNewPassword("abc12345", "abc12345@example.com", "someone"),
        )
        assertEquals(
            "密码不能和用户名相同",
            validateNewPassword("username1", "user@example.com", "username1"),
        )
        assertNull(validateNewPassword("password123", "user@example.com", "someone"))
    }

    @Test
    fun `email precheck accepts plain addresses only`() {
        assertTrue(isEmail("user@example.com"))
        assertTrue(!isEmail("user"))
        assertTrue(!isEmail("@example.com"))
        assertTrue(!isEmail("user@"))
    }
}
