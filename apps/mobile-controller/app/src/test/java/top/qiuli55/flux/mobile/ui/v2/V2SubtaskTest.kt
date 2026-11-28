// 临时子任务卡片与「对话」列表过滤的核心用例。
//
// 子任务是主助理对话下的条目，不是独立聊天；解析错了会变成空白气泡，
// 过滤错了会让它混进对话列表或项目助理切换器。
package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.AssistantList
import top.qiuli55.flux.mobile.data.Task

class V2SubtaskTest {

    @Test
    fun `subtask payload keeps id status agent and result`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "subtask_id": "6f1c2d3e-0000-4000-8000-000000000001",
              "parent_task_id": "6f1c2d3e-0000-4000-8000-000000000000",
              "status": "completed",
              "status_label": "已完成",
              "agent_id": "agent-1",
              "model_name": "deepseek-v4-flash",
              "title": "修登录页",
              "result": "子任务完成：已修复"
            }
            """.trimIndent(),
        )

        val card = parseSubtaskCard(payload)

        assertTrue(card != null)
        assertEquals("6f1c2d3e-0000-4000-8000-000000000001", card!!.subtaskId)
        assertEquals("completed", card.status)
        assertEquals("已完成", card.statusLabel)
        assertEquals("agent-1", card.agentId)
        assertEquals("deepseek-v4-flash", card.model)
        assertEquals("修登录页", card.title)
        assertEquals("子任务完成：已修复", card.result)
    }

    @Test
    fun `subtask payload without id is ignored instead of crashing`() {
        val payload = Json.parseToJsonElement("""{"status":"running"}""")
        assertNull(parseSubtaskCard(payload))
    }

    @Test
    fun `assistant switcher starts with master and only lists created projects`() {
        val list = Json { ignoreUnknownKeys = true }.decodeFromString<AssistantList>(
            """
            {
              "master": {"id":"master","name":"主助理","kind":"master","conversation_task_id":"t-master"},
              "projects": [
                {"id":"p1","name":"登录改造","kind":"project","conversation_task_id":"t-p1"}
              ]
            }
            """.trimIndent(),
        )

        val options = list.switcherOptions()
        assertEquals(listOf("master", "p1"), options.map { it.id })
        assertEquals("主助理", options.first().name)
        // 切换器只有主助理 + 已创建项目助理，不含临时子任务
        assertTrue(options.none { it.kind == "subtask" })
    }

    @Test
    fun `subtask and project assistant are not ordinary conversations`() {
        assertFalse(Task(origin = "subtask").isOrdinaryConversation())
        assertFalse(Task(origin = "assistant").isOrdinaryConversation())
        assertFalse(Task(origin = "project_assistant").isOrdinaryConversation())
        assertTrue(Task(origin = "chat").isOrdinaryConversation())
    }
}
