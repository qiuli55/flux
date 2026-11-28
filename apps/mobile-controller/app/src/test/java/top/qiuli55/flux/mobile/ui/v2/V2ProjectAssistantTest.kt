// 项目助理进度面板的数据契约：进度汇总 / 读历史 / 接着干 / 开副本。
//
// 这些结构解析错了会表现为空白面板或把副本误当成临时子任务，必须在单测里守住。
package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.AssistantForkResult
import top.qiuli55.flux.mobile.data.AssistantHistoryDetail
import top.qiuli55.flux.mobile.data.AssistantProgress

class V2ProjectAssistantTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `progress parses the four buckets and stall threshold`() {
        val progress = json.decodeFromString<AssistantProgress>(
            """
            {
              "assistant_id": "a1",
              "assistant_name": "登录改造",
              "stalled_after_hours": 6,
              "counts": {"running": 1, "possibly_stalled": 1, "failed": 1, "completed": 2},
              "conversations": [
                {"task_id":"t1","title":"刚开的对话","status":"running","bucket":"running",
                 "bucket_label":"进行中","idle_hours":0.2,"read_only":true},
                {"task_id":"t2","title":"停了很久","status":"running","bucket":"possibly_stalled",
                 "bucket_label":"可能已停住","idle_hours":10.0,"read_only":true}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("登录改造", progress.assistantName)
        assertEquals(6, progress.stalledAfterHours)
        assertEquals(1, progress.counts["running"])
        assertEquals(1, progress.counts["possibly_stalled"])
        assertEquals(1, progress.counts["failed"])
        assertEquals(2, progress.counts["completed"])
        assertEquals(listOf("running", "possibly_stalled"), progress.conversations.map { it.bucket })
        assertTrue(progress.conversations.all { it.readOnly })
    }

    @Test
    fun `continue is blocked only while the current round is running`() {
        assertFalse(canContinueConversation("running"))
        assertTrue(canContinueConversation("possibly_stalled"))
        assertTrue(canContinueConversation("completed"))
        assertTrue(canContinueConversation("failed"))
        assertTrue(canContinueConversation("cancelled"))
    }

    @Test
    fun `read history detail stays read only and keeps messages`() {
        val detail = json.decodeFromString<AssistantHistoryDetail>(
            """
            {
              "assistant_id": "a1",
              "read_only": true,
              "has_more": false,
              "task": {"id":"t1","description":"登录改造","origin":"chat"},
              "messages": [
                {"id":"m1","role":"user","content":"登录页已经改完"},
                {"id":"m2","role":"assistant","content":"接下来补错误分支"}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(detail.readOnly)
        assertEquals("登录改造", detail.task.description)
        assertEquals(2, detail.messages.size)
        assertEquals("登录页已经改完", detail.messages.first().content)
    }

    @Test
    fun `fork result is a persistent chat and not a subtask`() {
        val fork = json.decodeFromString<AssistantForkResult>(
            """
            {
              "task": {"id":"fork-1","description":"登录改造副本","origin":"chat","parent_task_id":null},
              "source_task_id": "t1",
              "summary_chars": 128,
              "parent_task_id": null,
              "origin": "chat"
            }
            """.trimIndent(),
        )

        assertEquals("chat", fork.origin)
        assertEquals("chat", fork.task.origin)
        assertNull(fork.parentTaskId)
        assertNull(fork.task.parentTaskId)
        assertFalse(fork.task.origin == "subtask")
        assertEquals("t1", fork.sourceTaskId)
    }
}
