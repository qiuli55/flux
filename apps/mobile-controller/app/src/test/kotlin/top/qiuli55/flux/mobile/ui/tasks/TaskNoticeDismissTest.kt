package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.ui.v2.StreamUiSnapshot

/**
 * 会话内提示（已切换模型 / 已排队切换 Agent）的关闭语义：
 * 用户点 X 后 error / notice 一起清空，后续轮询 / 下拉刷新只更新任务数据，
 * 不会把同一条提示重新写回输入区上方。
 */
class TaskNoticeDismissTest {

    private fun refreshed(state: TaskDetailUiState): TaskDetailUiState = state.withRefreshResult(
        task = Task(id = "t1", status = "running"),
        messages = emptyList(),
        changes = emptyList(),
        contextUsage = null,
        run = null,
        runs = emptyList(),
        stream = StreamUiSnapshot(),
    )

    @Test
    fun `dismissed notice stays dismissed after refresh`() {
        val dismissed = TaskDetailUiState()
            .copy(
                notice = "已切换模型：proxy-deepseek-v4.1-flash",
                error = "已切换模型：proxy-deepseek-v4.1-flash",
            )
            .withDismissedNotice()

        val after = refreshed(dismissed)

        assertNull(after.notice)
        assertNull(after.error)
    }

    @Test
    fun `a new notice is not swallowed by refresh`() {
        val next = TaskDetailUiState()
            .withDismissedNotice()
            .copy(notice = "已排队：本轮结束后切换到「codex-agent」")

        assertEquals(
            "已排队：本轮结束后切换到「codex-agent」",
            refreshed(next).notice,
        )
    }
}
