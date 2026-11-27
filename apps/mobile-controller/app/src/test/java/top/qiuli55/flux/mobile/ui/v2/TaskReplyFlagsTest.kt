package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.TaskMessage

class TaskReplyFlagsTest {

    private fun message(role: String, json: String? = null): TaskMessage =
        TaskMessage(
            role = role,
            content = "回复",
            payload = json?.let { Json.parseToJsonElement(it) },
        )

    @Test
    fun `assistant_only is only true for a boolean true`() {
        assertTrue(message("assistant", """{"assistant_only":true}""").assistantOnly())
        assertFalse(message("assistant", """{"assistant_only":false}""").assistantOnly())
        assertFalse(message("assistant", """{"assistant_only":"true"}""").assistantOnly())
        assertFalse(message("assistant", """{"assistant_only":1}""").assistantOnly())
        assertFalse(message("assistant", """{"model":{}}""").assistantOnly())
        assertFalse(message("assistant").assistantOnly())
    }

    @Test
    fun `needs_execution is only true for a boolean true`() {
        assertTrue(message("assistant", """{"needs_execution":true}""").needsExecution())
        assertFalse(message("assistant", """{"needs_execution":false}""").needsExecution())
        assertFalse(message("assistant", """{"needs_execution":"true"}""").needsExecution())
        assertFalse(message("assistant").needsExecution())
    }

    @Test
    fun `execution plan steps count as a request to execute`() {
        val withPlan = message("assistant", """{"reply":"好","steps":["改配置","跑测试"]}""")
        assertTrue(withPlan.requestsExecution())
        assertFalse(message("assistant", """{"steps":[]}""").requestsExecution())
        assertFalse(message("assistant", """{"steps":"改配置"}""").requestsExecution())
    }

    @Test
    fun `last assistant message is the reply of the turn`() {
        val messages = listOf(
            message("user"),
            message("assistant", """{"needs_execution":true}"""),
            message("user"),
        )
        assertTrue(messages.assistantRequestsExecution())
        assertTrue(
            listOf(message("user"), message("assistant", """{"steps":["一步"]}"""))
                .assistantRequestsExecution(),
        )
        assertNull(emptyList<TaskMessage>().lastAssistantMessage())
        assertFalse(listOf(message("user")).assistantRequestsExecution())
    }

    @Test
    fun `legacy payload without flags is treated as normal agent reply`() {
        val reply = message("assistant", """{"model":{"provider":"x"}}""")
        assertFalse(reply.assistantOnly())
        assertFalse(reply.needsExecution())
    }
}
