package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 浏览器偏好持久化（Robolectric / JVM）。
 *
 * 锁住两件容易回退的事：桌面 / 移动 UA 选择不会每次进浏览器都被重置；
 * 最后页面只保存真实网页，about:blank 之类的空会话不会覆盖它。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class V2BrowserSettingsStoreTest {

    private fun prefs(context: Context) =
        context.getSharedPreferences("flux_browser", Context.MODE_PRIVATE)

    @Test
    fun `ua choice and last page survive a store recreation`() {
        val context = RuntimeEnvironment.getApplication()
        prefs(context).edit().clear().commit()

        val store = V2BrowserSettingsStore(context)
        // 默认桌面版；没有历史页面
        assertTrue(store.desktopMode())
        assertNull(store.lastSiteUrl())

        store.saveDesktopMode(false)
        store.saveLastSiteUrl("https://example.com/inbox?tab=1")
        // 新实例模拟重新进入浏览器 / 进程重启后读取
        val reopened = V2BrowserSettingsStore(context)
        assertFalse("UA 选择不能被重置成默认桌面版", reopened.desktopMode())
        assertEquals("https://example.com/inbox?tab=1", reopened.lastSiteUrl())

        // 空会话 / 本地地址 / 预览地址都不覆盖上一次的真实页面
        reopened.saveLastSiteUrl("about:blank")
        reopened.saveLastSiteUrl("")
        reopened.saveLastSiteUrl(null)
        reopened.saveLastSiteUrl("file:///data/x")
        reopened.saveLastSiteUrl("https://flux-preview.local/page")
        assertEquals("https://example.com/inbox?tab=1", reopened.lastSiteUrl())

        // 新页面前后空格会被清洗后再保存
        reopened.saveLastSiteUrl("  https://example.com/next  ")
        assertEquals("https://example.com/next", reopened.lastSiteUrl())

        prefs(context).edit().clear().commit()
    }
}
