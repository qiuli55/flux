package top.qiuli55.flux.mobile.ui.tasks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import top.qiuli55.flux.mobile.data.TaskStreamEvent

/** 首次接入轮询时的旧缓冲过滤：只保留最后一段已结束 Run 之后的事件。 */
class TaskStreamReplayTest {

    private fun event(seq: Int, type: String, text: String = ""): TaskStreamEvent =
        TaskStreamEvent(
            seq = seq,
            type = type,
            data = if (text.isBlank()) JsonObject(emptyMap()) else buildJsonObject { put("text", text) },
        )

    @Test
    fun keepsEventsAfterLastTerminal() {
        // 重进已结束的对话：上一轮的思考与正文整段重放，终态之后才是新一轮
        val events = listOf(
            event(1, "run_started"),
            event(2, "run_event", "旧的思考"),
            event(3, "run_event", "旧的正文"),
            event(4, "run_finished"),
            event(5, "run_started"),
            event(6, "run_event", "新的正文"),
        )
        assertEquals(listOf(5, 6), dropStaleStreamEvents(events).map { it.seq })
    }

    @Test
    fun keepsRunningEventsWhenNoTerminal() {
        // 对话正在执行：缓冲里没有终态，全部按实时事件播放
        val events = listOf(event(1, "run_started"), event(2, "run_event", "进行中"))
        assertEquals(events, dropStaleStreamEvents(events))
    }

    @Test
    fun dropsReplayedAssistantErrorToo() {
        val events = listOf(
            event(1, "assistant_start"),
            event(2, "assistant_error"),
        )
        assertEquals(emptyList<TaskStreamEvent>(), dropStaleStreamEvents(events))
    }
}
