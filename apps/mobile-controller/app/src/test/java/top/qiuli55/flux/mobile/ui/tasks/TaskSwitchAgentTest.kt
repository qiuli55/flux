package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Task

/** 对话页 Agent / 模型切换的可用条件：执行中禁用，结束的对话仍可切换（切换只影响下一次续聊）。 */
class TaskSwitchAgentTest {

    @Test
    fun `running task cannot switch`() {
        assertFalse(canSwitchTaskAgent(Task(id = "t1", status = "running")))
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
