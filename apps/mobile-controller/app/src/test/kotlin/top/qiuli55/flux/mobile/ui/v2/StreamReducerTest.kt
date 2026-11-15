package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.TaskStreamEvent

/**
 * 流式归约器的纯 JVM 测试（不依赖 Android / Compose）。
 *
 * 覆盖：增量合并、结构事件前强制 flush、思考与工具状态流转、
 * 重连去重、部分输出保留、取消、退避序列。
 */
class StreamReducerTest {

    private var now = 1_000L
    private val reducer = TaskStreamReducer { now }

    private fun event(seq: Int, type: String, vararg data: Pair<String, String>): TaskStreamEvent =
        TaskStreamEvent(
            seq = seq,
            type = type,
            data = if (data.isEmpty()) {
                JsonObject(emptyMap())
            } else {
                buildJsonObject { data.forEach { (key, value) -> put(key, value) } }
            },
        )

    private fun delta(seq: Int, text: String) = event(seq, "assistant_delta", "text" to text)

    private fun thinking(seq: Int, text: String) =
        event(seq, "run_event", "kind" to "thinking", "text" to text)

    private fun tool(
        seq: Int,
        callId: String,
        status: String,
        name: String = "shell",
        detail: String = "",
        output: String = "",
    ) = event(
        seq,
        "run_event",
        "kind" to "tool",
        "call_id" to callId,
        "name" to name,
        "detail" to detail,
        "output" to output,
        "status" to status,
        "category" to "command",
    )

    @Test
    fun `deltas are buffered until flush`() {
        assertEquals(StreamApply.BUFFERED, reducer.apply(delta(1, "Hello ")))
        assertEquals(StreamApply.BUFFERED, reducer.apply(delta(2, "world")))

        // 未 flush 前 UI 快照不变：每条 token 都不触发整页重组
        assertEquals("", reducer.snapshot().liveText)

        val flushed = reducer.flush()
        assertEquals("Hello world", flushed.liveText)
        assertNull(flushed.liveLabel)
        assertTrue(flushed.liveActive)
    }

    @Test
    fun `structural event flushes pending text before changing state`() {
        reducer.apply(delta(1, "partial answer"))
        val outcome = reducer.apply(tool(2, "call-1", "running", detail = "ls -la"))
        assertEquals(StreamApply.COMMITTED, outcome)

        val snapshot = reducer.snapshot()
        // 工具事件到达时先把已缓冲正文落进快照，再追加步骤，顺序不会颠倒
        assertEquals("partial answer", snapshot.liveText)
        assertEquals(1, snapshot.tools.size)
        assertEquals(StreamToolStatus.RUNNING, snapshot.tools[0].status)
    }

    @Test
    fun `reasoning streams while active and collapses after assistant message`() {
        now = 5_000L
        reducer.apply(thinking(1, "先看日志"))
        reducer.flush()
        var reasoning = reducer.snapshot().reasoning
        assertNotNull(reasoning)
        assertTrue(reasoning!!.active)
        assertEquals("先看日志", reasoning.text)

        now = 8_000L
        reducer.apply(event(2, "assistant_message"))
        reasoning = reducer.snapshot().reasoning
        assertNotNull(reasoning)
        assertFalse(reasoning!!.active)
        assertEquals(5_000L, reasoning.startedAtMs)
        assertEquals(3_000L, reasoning.elapsedMs(now))
        assertEquals(3_000L, reasoning.endedAtMs!! - reasoning.startedAtMs)
    }

    @Test
    fun `tool started and completed merge into one step`() {
        reducer.apply(tool(1, "call-9", "running", detail = "pytest -q"))
        reducer.apply(tool(2, "call-9", "completed", output = "42 passed"))
        val steps = reducer.snapshot().tools

        assertEquals(1, steps.size)
        assertEquals(StreamToolStatus.DONE, steps[0].status)
        // 收尾事件不带 detail/name 时保留开始事件的参数
        assertEquals("pytest -q", steps[0].detail)
        assertEquals("shell", steps[0].name)
        assertEquals("42 passed", steps[0].output)
    }

    @Test
    fun `failed tool keeps failed status`() {
        reducer.apply(tool(1, "call-2", "running", detail = "make"))
        reducer.apply(tool(2, "call-2", "failed", output = "exit 2"))
        assertEquals(StreamToolStatus.FAILED, reducer.snapshot().tools[0].status)
    }

    @Test
    fun `run finished completes running tools`() {
        reducer.apply(tool(1, "call-3", "running"))
        reducer.apply(event(2, "run_finished"))
        assertEquals(StreamToolStatus.DONE, reducer.snapshot().tools[0].status)
        assertFalse(reducer.snapshot().liveActive)
    }

    @Test
    fun `reconnect replay with older seq is deduped`() {
        reducer.apply(delta(10, "already rendered"))
        reducer.flush()
        assertEquals("already rendered", reducer.snapshot().liveText)

        // 重连后服务端从边界重发：seq 10 不允许再追加一次
        val outcome = reducer.apply(delta(10, "already rendered"))
        assertEquals(StreamApply.DUPLICATE, outcome)
        assertEquals(1, reducer.snapshot().duplicateEvents)

        reducer.apply(delta(11, " + next"))
        reducer.flush()
        assertEquals("already rendered + next", reducer.snapshot().liveText)
    }

    @Test
    fun `assistant error keeps partial output and fails running tools`() {
        reducer.apply(delta(1, "写了一半的答复"))
        reducer.flush()
        reducer.apply(tool(2, "call-4", "running"))
        reducer.apply(event(3, "assistant_error", "error" to "upstream timeout"))

        val snapshot = reducer.snapshot()
        assertEquals("写了一半的答复", snapshot.liveText)
        assertFalse(snapshot.liveActive)
        assertEquals("upstream timeout", snapshot.terminalError)
        assertEquals(StreamToolStatus.FAILED, snapshot.tools[0].status)
    }

    @Test
    fun `terminal connection failure keeps partial text and offers retry state`() {
        reducer.apply(delta(1, "部分内容"))
        reducer.flush()
        reducer.apply(tool(2, "call-5", "running"))
        reducer.flush()
        val snapshot = reducer.onTerminal("实时连接失败，请重试")

        assertEquals("部分内容", snapshot.liveText)
        assertEquals(StreamConnection.FAILED, snapshot.connection)
        assertEquals("实时连接失败，请重试", snapshot.terminalError)
        assertEquals(StreamToolStatus.FAILED, snapshot.tools[0].status)

        val retried = reducer.retry()
        assertNull(retried.terminalError)
        assertEquals(StreamConnection.CONNECTING, retried.connection)
    }

    @Test
    fun `cancel keeps partial text and ends running tools`() {
        reducer.apply(delta(1, "正在生成的正文"))
        reducer.flush()
        reducer.apply(tool(2, "call-6", "running"))
        val snapshot = reducer.markCancelled()

        assertTrue(snapshot.cancelled)
        assertFalse(snapshot.liveActive)
        assertNull(snapshot.terminalError)
        assertEquals("正在生成的正文", snapshot.liveText)
        assertEquals(StreamToolStatus.DONE, snapshot.tools[0].status)
    }

    @Test
    fun `disconnect and open drive connection state and backoff attempt`() {
        reducer.onConnecting()
        assertEquals(StreamConnection.CONNECTING, reducer.snapshot().connection)

        reducer.onOpen()
        assertEquals(StreamConnection.LIVE, reducer.snapshot().connection)

        val first = reducer.onDisconnected()
        assertEquals(StreamConnection.RECONNECTING, first.connection)
        assertEquals(1, first.reconnectAttempt)
        assertEquals(2, reducer.onDisconnected().reconnectAttempt)

        // 重新连上后退避归零，下一次断线从 1s 重新开始
        val reopened = reducer.onOpen()
        assertEquals(StreamConnection.LIVE, reopened.connection)
        assertEquals(0, reopened.reconnectAttempt)
    }

    @Test
    fun `reconnect backoff is exponential and capped`() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(1_000L, reconnectDelayMs(1))
        assertEquals(2_000L, reconnectDelayMs(2))
        assertEquals(4_000L, reconnectDelayMs(3))
        assertEquals(8_000L, reconnectDelayMs(4))
        assertEquals(15_000L, reconnectDelayMs(5))
        assertEquals(15_000L, reconnectDelayMs(20))
    }

    @Test
    fun `text is trimmed to the tail to bound memory`() {
        val chunk = "x".repeat(1_000)
        var seq = 1
        repeat(5) {
            reducer.apply(delta(seq++, chunk))
            reducer.flush()
        }
        assertEquals(STREAM_TEXT_LIMIT, reducer.snapshot().liveText.length)
    }

    @Test
    fun `start run clears previous activity but keeps seq watermark`() {
        reducer.apply(delta(7, "上一轮"))
        reducer.flush()
        reducer.apply(tool(8, "call-7", "completed"))
        val snapshot = reducer.startRun("正在思考…")

        assertEquals("", snapshot.liveText)
        assertTrue(snapshot.tools.isEmpty())
        assertTrue(snapshot.liveActive)
        assertEquals(8, snapshot.lastSeq)
    }

    @Test
    fun `stop tap guard accepts first tap and ignores rapid double tap`() {
        val guard = StopTapGuard(windowMs = 700L)
        assertTrue(guard.tryAccept(1_000L))
        assertFalse(guard.tryAccept(1_120L))
        // 超过窗口后的再次点击（例如用户确认要再停一次）正常放行
        assertTrue(guard.tryAccept(1_800L))
    }
}
