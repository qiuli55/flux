// 账号保存 / 退出时，同步的 WebView profile 键必须跟着更新。
//
// 这里不启动 WebView，只验证 SettingsStore 与 WebViewProfileStore 的接线：
// 登录后 profile 键变成账号键，退出后回到 guest；否则切换账号时数据目录不会切。
package top.qiuli55.flux.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WebViewProfileAccountTest {

    @Test
    fun `saving and clearing a flux account switches the sync webview profile`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        // 上一条用例可能留下状态：先退回游客
        store.clearAccount()
        assertEquals(WEBVIEW_GUEST_PROFILE, WebViewProfileStore(context).currentKey())

        store.saveAccount(
            AccountSession(userId = 42L, username = "alice", email = "alice@example.com"),
        )
        val aliceKey = WebViewProfileStore(context).currentKey()
        assertEquals(webViewProfileKey(42L, "alice", "alice@example.com"), aliceKey)
        assertNotEquals(WEBVIEW_GUEST_PROFILE, aliceKey)

        // token 续期：同一账号再次保存，不换 profile（否则每次续期都会重启）
        store.saveAccount(
            AccountSession(userId = 42L, username = "alice", email = "alice@example.com"),
        )
        assertEquals(aliceKey, WebViewProfileStore(context).currentKey())

        // 退出 Flux 账号：游客必须落到独立 profile，不能继承 alice 的站点登录态
        store.clearAccount()
        assertEquals(WEBVIEW_GUEST_PROFILE, WebViewProfileStore(context).currentKey())

        // 启动时按已加载账号补键：从旧版本升级、同步键还是 guest 也能对齐
        store.syncWebViewProfile(
            AccountSession(userId = 42L, username = "alice", email = "alice@example.com"),
        )
        assertEquals(aliceKey, WebViewProfileStore(context).currentKey())
        store.syncWebViewProfile(null)
        assertEquals(WEBVIEW_GUEST_PROFILE, WebViewProfileStore(context).currentKey())
    }
}
