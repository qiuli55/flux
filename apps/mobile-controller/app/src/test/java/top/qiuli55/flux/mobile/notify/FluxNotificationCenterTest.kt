package top.qiuli55.flux.mobile.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.Decision
import top.qiuli55.flux.mobile.data.Task

/**
 * 真的调用系统通知（Robolectric 的 NotificationManager），并且失败时留下可展示的错误，
 * 而不是静默吞掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FluxNotificationCenterTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        app.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `decision alert is posted with the conversation title and question`() {
        val task = Task(
            id = "t-1",
            description = "生产库迁移方案确认",
            status = "waiting_for_user_decision",
            pendingDecision = Decision(id = "d-1", question = "是否执行迁移？"),
        )

        val posted = FluxNotificationCenter.notifyDecision(app, task, "decision:t-1:d-1")

        assertTrue(posted)
        assertNull(FluxNotificationCenter.lastError.value)
        val notifications = shadowOf(manager).allNotifications
        assertEquals(1, notifications.size)
        val notification = shadowOf(notifications.first())
        assertTrue(notification.contentTitle.contains("生产库迁移方案确认"))
        assertTrue(notification.contentText.contains("是否执行迁移"))
    }

    @Test
    fun `denied permission is reported instead of silently dropped`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val task = Task(
            id = "t-2",
            description = "失败提醒测试",
            status = "done",
        )

        val posted = FluxNotificationCenter.notifyFinished(app, task, "finished:t-2:run")

        assertFalse(posted)
        val error = FluxNotificationCenter.lastError.value
        assertNotNull(error)
        assertTrue(error!!.contains("系统设置"))
    }
}
