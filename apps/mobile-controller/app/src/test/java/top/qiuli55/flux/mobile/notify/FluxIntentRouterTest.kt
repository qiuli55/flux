package top.qiuli55.flux.mobile.notify

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 启动 Intent 分流：通知点击 / 系统分享 / OAuth 回跳 / 普通启动互不干扰。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FluxIntentRouterTest {

    private fun notification(taskId: String = "t-1", reason: String? = REASON_DECISION) =
        Intent().apply {
            putExtra(EXTRA_OPEN_TASK_ID, taskId)
            if (reason != null) putExtra(EXTRA_OPEN_REASON, reason)
        }

    @Test
    fun `notification click opens the task for decision`() {
        val launch = parseFluxLaunch(notification())
        assertEquals(FluxLaunch.OpenTask("t-1", OpenTaskReason.DECISION), launch)
    }

    @Test
    fun `notification click can ask for the finished task page`() {
        val launch = parseFluxLaunch(notification(reason = REASON_FINISHED))
        assertEquals(FluxLaunch.OpenTask("t-1", OpenTaskReason.FINISHED), launch)
    }

    @Test
    fun `unknown reason defaults to decision`() {
        val launch = parseFluxLaunch(notification(reason = "something-else"))
        assertEquals(FluxLaunch.OpenTask("t-1", OpenTaskReason.DECISION), launch)
    }

    @Test
    fun `blank task id is ignored`() {
        assertEquals(FluxLaunch.None, parseFluxLaunch(notification(taskId = "  ")))
    }

    @Test
    fun `oauth auth redirect is not a share or a task`() {
        val launch = parseFluxLaunch(
            Intent(Intent.ACTION_VIEW, Uri.parse("flux://auth?code=abc123")),
        )
        assertEquals(FluxLaunch.OAuth("auth", "abc123", ""), launch)
    }

    @Test
    fun `github link redirect keeps code and state`() {
        val launch = parseFluxLaunch(
            Intent(Intent.ACTION_VIEW, Uri.parse("flux://github-link?code=c&state=s")),
        )
        assertEquals(FluxLaunch.OAuth("github-link", "c", "s"), launch)
    }

    @Test
    fun `text share is a share`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "看看这段")
        }
        assertEquals(FluxLaunch.Share, parseFluxLaunch(intent))
    }

    @Test
    fun `multiple share is a share`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }
        assertEquals(FluxLaunch.Share, parseFluxLaunch(intent))
    }

    @Test
    fun `normal launch is ignored`() {
        assertEquals(FluxLaunch.None, parseFluxLaunch(null))
        assertEquals(FluxLaunch.None, parseFluxLaunch(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `notification extras win over a share action`() {
        // 理论上的混搭：只要带明确的任务 extra，就按通知点击处理，不会落草稿。
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "分享内容")
            putExtra(EXTRA_OPEN_TASK_ID, "t-9")
        }
        assertEquals(FluxLaunch.OpenTask("t-9", OpenTaskReason.DECISION), parseFluxLaunch(intent))
    }

    @Test
    fun `consumed extras are removed`() {
        val intent = notification()
        clearOpenTaskExtras(intent)
        assertNull(intent.getStringExtra(EXTRA_OPEN_TASK_ID))
        assertNull(intent.getStringExtra(EXTRA_OPEN_REASON))
        // 清掉之后就不再被当成通知点击
        assertEquals(FluxLaunch.None, parseFluxLaunch(intent))
    }

    @Test
    fun `open task intent carries explicit extras and single top flags`() {
        val intent = openTaskIntent(
            org.robolectric.RuntimeEnvironment.getApplication(),
            "t-42",
            OpenTaskReason.FINISHED,
        )
        assertEquals("t-42", intent.getStringExtra(EXTRA_OPEN_TASK_ID))
        assertEquals(REASON_FINISHED, intent.getStringExtra(EXTRA_OPEN_REASON))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `open task bus queues until consumed`() {
        OpenTaskBus.resetForTest()
        try {
            OpenTaskBus.publish("t-1", OpenTaskReason.DECISION)
            OpenTaskBus.publish("t-2", OpenTaskReason.FINISHED)
            val queued = OpenTaskBus.pending.value
            assertEquals(listOf("t-1", "t-2"), queued.map { it.taskId })
            assertEquals(OpenTaskReason.FINISHED, queued.last().reason)

            OpenTaskBus.consume(queued.map { it.id }.toSet())
            assertTrue(OpenTaskBus.pending.value.isEmpty())
            OpenTaskBus.consume(emptySet())
            assertFalse(OpenTaskBus.pending.value.isNotEmpty())
        } finally {
            OpenTaskBus.resetForTest()
        }
    }
}
