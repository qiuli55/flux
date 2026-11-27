package top.qiuli55.flux.mobile.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.NotificationPrefs
import top.qiuli55.flux.mobile.data.SettingsStore

/**
 * 偏好确实落在 DataStore 上：写进去再读出来与写入一致，不依赖 Compose 的记忆。
 * 默认值在 [NotificationPolicyTest] 里单独覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NotificationPrefsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `notification prefs survive a save and read`() = runBlocking {
        val store = SettingsStore(context)
        val custom = NotificationPrefs(
            decisionAlerts = false,
            taskAlerts = true,
            haptics = false,
            reduceMotion = true,
        )

        store.saveNotificationPrefs(custom)
        val loaded = store.notificationPrefs.first()
        assertEquals(custom, loaded)
        assertFalse(loaded.decisionAlerts)
        assertTrue(loaded.taskAlerts)
        assertFalse(loaded.haptics)
        assertTrue(loaded.reduceMotion)

        // 还原默认值，避免同一 DataStore 文件影响同一进程里的其它用例。
        store.saveNotificationPrefs(NotificationPrefs())
        assertEquals(NotificationPrefs(), store.notificationPrefs.first())
    }
}
