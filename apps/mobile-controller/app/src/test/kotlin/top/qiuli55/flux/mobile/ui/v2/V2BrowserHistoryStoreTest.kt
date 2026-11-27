package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 最近访问的本地持久化（Robolectric / JVM）。
 *
 * 纯函数用例已覆盖去重与上限；这里只锁 SharedPreferences 往返、重启后仍在、
 * 以及存储损坏时不让浏览页打不开。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class V2BrowserHistoryStoreTest {

    private fun prefs(context: Context) =
        context.getSharedPreferences("flux_browser", Context.MODE_PRIVATE)

    @Test
    fun `history survives a store recreation and corrupted json falls back to empty`() {
        val context = RuntimeEnvironment.getApplication()
        prefs(context).edit().clear().commit()

        val store = V2BrowserHistoryStore(context)
        assertTrue(store.load().isEmpty())

        val entries = listOf(
            BrowserHistoryEntry("https://example.com/a", "A", 10),
            BrowserHistoryEntry("https://example.com/b", "B", 20),
        )
        store.save(entries)
        // 新建实例模拟重新进入浏览页 / 进程重启后读取
        assertEquals(entries, V2BrowserHistoryStore(context).load())

        prefs(context).edit().putString("history", "not json").commit()
        assertTrue(V2BrowserHistoryStore(context).load().isEmpty())

        prefs(context).edit().clear().commit()
    }
}
