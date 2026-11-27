package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新一次 send() 开始时的状态收尾：
 * 上一轮取消 / 切换留下的状态提示必须先清掉，否则发送成功后会被 UI 误判成本轮失败，
 * 草稿留在输入框里。
 */
class TaskSendNoticeClearTest {

    @Test
    fun `new send clears a stale cancel notice`() {
        val stale = TaskDetailUiState(
            draft = "取消后再发一次",
            notice = "任务已取消",
        )

        val started = stale.withSendStarted()

        assertTrue(started.sending)
        assertNull(started.error)
        assertNull(started.notice)
        assertEquals("", started.draft)
    }

    @Test
    fun `new send clears stale switch notices`() {
        val stale = TaskDetailUiState(
            notice = "已切换模型：proxy-deepseek-v4.1-flash",
        )

        assertNull(stale.withSendStarted().notice)
        assertNull(
            stale.copy(notice = "已排队：本轮结束后切换到「codex-agent」")
                .withSendStarted()
                .notice,
        )
    }
}
