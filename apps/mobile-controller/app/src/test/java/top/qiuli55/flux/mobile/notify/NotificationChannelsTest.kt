package top.qiuli55.flux.mobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 三个渠道必须存在，且名称 / 重要性 / 说明符合「不吵但可操作」的约定。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NotificationChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    @Test
    fun `decision finished and watch channels keep their importance`() {
        NotificationChannels.ensure(context)

        val decision = manager.getNotificationChannel(NotificationChannels.DECISION)
        assertEquals("决策提醒", decision.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, decision.importance)
        assertTrue(decision.description.toString().isNotBlank())

        val finished = manager.getNotificationChannel(NotificationChannels.FINISHED)
        assertEquals("任务完成提醒", finished.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, finished.importance)

        val watch = manager.getNotificationChannel(NotificationChannels.WATCH)
        assertEquals("任务关注服务", watch.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, watch.importance)
        // 常驻通知不应该响铃 / 震动打扰用户
        assertFalse(watch.shouldVibrate())
    }

    @Test
    fun `disabled channel is reported as not deliverable`() {
        NotificationChannels.ensure(context)
        manager.deleteNotificationChannel(NotificationChannels.FINISHED)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.FINISHED,
                "任务完成提醒",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        val status = readNotificationStatus(context)
        assertFalse(status.finishedChannelEnabled)
        assertTrue(status.decisionChannelEnabled)
        val problem = notificationProblem(status, decisionAlerts = false, taskAlerts = true)
        assertTrue(problem != null && problem.contains("任务完成提醒"))

        // 恢复现场，避免影响同一进程里的其它用例
        NotificationChannels.ensure(context)
        manager.deleteNotificationChannel(NotificationChannels.FINISHED)
    }
}
