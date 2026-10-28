package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Task

/** 对话页 Agent 切换的可用条件：执行中也可以切（排队到本轮结束后生效），只有没有任务时才不可切。 */
class TaskSwitchAgentTest {

    @Test
    fun `running task can queue switch`() {
        assertTrue(canSwitchTaskAgent(Task(id = "t1", status = "running")))
    }

    @Test
    fun `finished task can still switch`() {
        assertTrue(canSwitchTaskAgent(Task(id = "t1", status = "completed")))
        assertTrue(canSwitchTaskAgent(Task(id = "t1", status = "failed")))
        assertTrue(canSwitchTaskAgent(Task(id = "t1", status = "cancelled")))
    }

    @Test
    fun `pending task can switch`() {
        assertTrue(canSwitchTaskAgent(Task(id = "t1", status = "pending")))
    }

    @Test
    fun `missing task cannot switch`() {
        assertFalse(canSwitchTaskAgent(null))
    }
}
