package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.TaskContextUsage

/** 上下文快满提醒的出现规则：normal 不提醒，同一等级「继续聊」后不再打扰。 */
class TaskContextPromptTest {

    private fun usage(level: String) = TaskContextUsage(
        limit = 18,
        messageCount = 16,
        usedMessages = 16,
        percent = 89,
        level = level,
    )

    @Test
    fun `normal never prompts`() {
        assertFalse(shouldPromptContext(usage("normal"), null))
        assertFalse(shouldPromptContext(usage("normal"), "warning"))
    }

    @Test
    fun `warning prompts until user continues`() {
        assertTrue(shouldPromptContext(usage("warning"), null))
        assertTrue(shouldPromptContext(usage("warning"), "normal"))
        assertFalse(shouldPromptContext(usage("warning"), "warning"))
    }

    @Test
    fun `full prompts again after continuing at warning`() {
        assertTrue(shouldPromptContext(usage("full"), "warning"))
        assertFalse(shouldPromptContext(usage("full"), "full"))
    }

    @Test
    fun `missing usage does not prompt`() {
        assertFalse(shouldPromptContext(null, null))
    }
}

