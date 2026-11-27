package top.qiuli55.flux.mobile.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Decision
import top.qiuli55.flux.mobile.data.NotificationPrefs
import top.qiuli55.flux.mobile.data.Task

/**
 * 提醒规则全部是纯函数：开关、前台抑制、去重键、文案、权限状态。
 * 不依赖 Android 框架，跑得也快。
 */
class NotificationPolicyTest {

    private fun task(
        status: String = "running",
        decision: Decision? = null,
        runId: String? = "run-1",
        description: String = "生产库迁移方案确认",
    ) = Task(
        id = "t-1",
        description = description,
        status = status,
        pendingDecision = decision,
        runId = runId,
    )

    // ---------------------------------------------------------- 默认值

    @Test
    fun `notification prefs keep the old defaults`() {
        val prefs = NotificationPrefs()
        assertTrue(prefs.decisionAlerts)
        assertFalse(prefs.taskAlerts)
        assertTrue(prefs.haptics)
        assertFalse(prefs.reduceMotion)
        assertTrue(prefs.anyAlertEnabled)
    }

    // ---------------------------------------------------------- 开关

    @Test
    fun `decision alert only fires when the switch is on`() {
        val on = NotificationPrefs(decisionAlerts = true)
        val off = NotificationPrefs(decisionAlerts = false)
        assertTrue(shouldSendAlert(AlertKind.DECISION, on, "t-1", appForeground = false, viewedTaskId = null))
        assertFalse(shouldSendAlert(AlertKind.DECISION, off, "t-1", appForeground = false, viewedTaskId = null))
    }

    @Test
    fun `finished alert only fires when the switch is on`() {
        val on = NotificationPrefs(taskAlerts = true)
        val off = NotificationPrefs(taskAlerts = false)
        assertTrue(shouldSendAlert(AlertKind.FINISHED, on, "t-1", appForeground = false, viewedTaskId = null))
        assertFalse(shouldSendAlert(AlertKind.FINISHED, off, "t-1", appForeground = false, viewedTaskId = null))
    }

    // ---------------------------------------------------------- 前台抑制

    @Test
    fun `viewing the same task in foreground suppresses the alert`() {
        val prefs = NotificationPrefs(decisionAlerts = true, taskAlerts = true)
        assertFalse(shouldSendAlert(AlertKind.DECISION, prefs, "t-1", true, "t-1"))
        assertFalse(shouldSendAlert(AlertKind.FINISHED, prefs, "t-1", true, "t-1"))
    }

    @Test
    fun `background or another page still alerts`() {
        val prefs = NotificationPrefs(decisionAlerts = true, taskAlerts = true)
        // 后台，即使上次停在同一条对话
        assertTrue(shouldSendAlert(AlertKind.DECISION, prefs, "t-1", false, "t-1"))
        // 前台，但在别的页面 / 别的对话
        assertTrue(shouldSendAlert(AlertKind.DECISION, prefs, "t-1", true, null))
        assertTrue(shouldSendAlert(AlertKind.DECISION, prefs, "t-1", true, "t-2"))
    }

    // ---------------------------------------------------------- 去重键

    @Test
    fun `decision key is stable for the same decision id`() {
        val decision = Decision(id = "d-9", question = "是否继续？")
        val first = decisionKey(task(status = "waiting_for_user_decision", decision = decision))
        val second = decisionKey(task(status = "waiting_for_user_decision", decision = decision))
        assertEquals(first, second)
        assertEquals("decision:t-1:d-9", first)
    }

    @Test
    fun `decision key falls back to waiting status and run id`() {
        val key = decisionKey(task(status = "waiting_for_user_decision", decision = null, runId = "run-7"))
        assertEquals("decision:t-1:run-7", key)
        assertNull(decisionKey(task(status = "running")))
    }

    @Test
    fun `finished key only exists for terminal states`() {
        assertNull(finishedKey(task(status = "running")))
        assertEquals("finished:t-1:run-1", finishedKey(task(status = "done")))
        assertEquals("finished:t-1:run-1", finishedKey(task(status = "failed")))
        assertEquals("finished:t-1:run-1", finishedKey(task(status = "canceled")))
    }

    @Test
    fun `dedupe keeps each key once and forgets on failure`() {
        val dedupe = AlertDeduplicator(limit = 3)
        assertTrue(dedupe.firstTime("a"))
        assertFalse(dedupe.firstTime("a"))
        assertTrue(dedupe.firstTime("b"))
        dedupe.forget("a")
        assertTrue(dedupe.firstTime("a"))
        // 超出上限时丢掉最早的键，但不会误伤刚加入的
        dedupe.firstTime("c")
        dedupe.firstTime("d")
        assertTrue(dedupe.contains("d"))
        assertTrue(dedupe.snapshot().size <= 3)
    }

    // ---------------------------------------------------------- 文案

    @Test
    fun `decision notification shows the conversation and the question`() {
        val content = decisionContent(
            task(
                status = "waiting_for_user_decision",
                decision = Decision(id = "d-1", question = "是否执行生产库迁移？"),
            ),
        )
        assertTrue(content.title.contains("生产库迁移方案确认"))
        assertEquals("是否执行生产库迁移？", content.body)
    }

    @Test
    fun `decision notification falls back to a clear generic body`() {
        val content = decisionContent(task(status = "waiting_for_user_decision", decision = null))
        assertTrue(content.title.contains("等你决策"))
        assertTrue(content.body.isNotBlank())
        assertFalse(content.body == "有新决策")
    }

    @Test
    fun `finished notification distinguishes done failed and canceled`() {
        val done = finishedContent(task(status = "done"))
        assertTrue(done.title.contains("已完成"))
        assertTrue(done.title.contains("生产库迁移方案确认"))

        val failed = finishedContent(task(status = "failed"))
        assertTrue(failed.title.contains("执行失败"))

        val canceled = finishedContent(task(status = "canceled"))
        assertTrue(canceled.title.contains("已取消"))
    }

    // ---------------------------------------------------------- 权限状态

    @Test
    fun `missing permission explains how to open system settings`() {
        val problem = notificationProblem(
            NotificationStatus(permissionGranted = false),
            decisionAlerts = true,
            taskAlerts = false,
        )
        assertTrue(problem != null && problem.contains("系统设置"))
        assertFalse(NotificationStatus(permissionGranted = false).canDeliver(AlertKind.DECISION))
    }

    @Test
    fun `app level switch off is reported`() {
        val problem = notificationProblem(
            NotificationStatus(appNotificationsEnabled = false),
            decisionAlerts = true,
            taskAlerts = false,
        )
        assertTrue(problem != null && problem.contains("系统设置"))
    }

    @Test
    fun `only the enabled alert channel is checked`() {
        val disabledDecision = NotificationStatus(decisionChannelEnabled = false)
        // 决策提醒关着时，不因为它渠道被关就报警
        assertNull(notificationProblem(disabledDecision, decisionAlerts = false, taskAlerts = false))
        assertTrue(notificationProblem(disabledDecision, decisionAlerts = true, taskAlerts = false) != null)

        val disabledFinished = NotificationStatus(finishedChannelEnabled = false)
        assertNull(notificationProblem(disabledFinished, decisionAlerts = true, taskAlerts = false))
        assertTrue(notificationProblem(disabledFinished, decisionAlerts = false, taskAlerts = true) != null)
    }

    @Test
    fun `healthy status has no problem and can deliver both kinds`() {
        val status = NotificationStatus()
        assertNull(notificationProblem(status, decisionAlerts = true, taskAlerts = true))
        assertTrue(status.canDeliver(AlertKind.DECISION))
        assertTrue(status.canDeliver(AlertKind.FINISHED))
    }
}
