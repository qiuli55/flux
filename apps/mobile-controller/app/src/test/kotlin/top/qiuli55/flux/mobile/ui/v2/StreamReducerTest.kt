package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 覆盖：阶段时间线（思考 / 正文 / 命令组 / 工具组交替）、增量合并、结构事件前强制 flush、
 * 工具状态流转、跨 runtime 的统一事件合同、收尾（settle）与迟到事件、重连去重、退避序列。
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

    private fun text(seq: Int, value: String) =
        event(seq, "run_event", "kind" to "text", "text" to value)

    /** 与后端一致：落库消息带 kind，只有 text / secure_input 才是本轮最终答复。 */
    private fun assistantMessage(seq: Int, kind: String = "text") = TaskStreamEvent(
        seq = seq,
        type = "assistant_message",
        data = buildJsonObject {
            put("message", buildJsonObject { put("kind", kind) })
        },
    )

    private fun tool(
        seq: Int,
        callId: String,
        status: String,
        name: String = "shell",
        detail: String = "",
        output: String = "",
        category: String = "command",
    ) = event(
        seq,
        "run_event",
        "kind" to "tool",
        "call_id" to callId,
        "name" to name,
        "detail" to detail,
        "output" to output,
        "status" to status,
        "category" to category,
    )

    private fun types(): List<StreamStageType> =
        reducer.snapshot().stages.map { stage ->
            when (stage) {
                is StreamThoughtStage -> StreamStageType.THOUGHT
                is StreamTextStage -> StreamStageType.TEXT
                is StreamToolGroupStage -> stage.type
            }
        }

    private fun group(index: Int): StreamToolGroupStage =
        reducer.snapshot().stages.filterIsInstance<StreamToolGroupStage>()[index]

    // ------------------------------------------------------------ 阶段时间线

    @Test
    fun `deltas are buffered until flush`() {
        assertEquals(StreamApply.BUFFERED, reducer.apply(delta(1, "Hello ")))
        assertEquals(StreamApply.BUFFERED, reducer.apply(delta(2, "world")))

        // 未 flush 前 UI 快照不变：每条 token 都不触发整页重组
        assertEquals("", reducer.snapshot().liveText)
        assertTrue(reducer.snapshot().stages.isEmpty())

        val flushed = reducer.flush()
        assertEquals("Hello world", flushed.liveText)
        assertEquals(listOf(StreamStageType.TEXT), types())
        assertTrue(flushed.liveActive)
    }

    @Test
    fun `structural event flushes pending text before changing state`() {
        reducer.apply(delta(1, "partial answer"))
        val outcome = reducer.apply(tool(2, "call-1", "running", detail = "ls -la"))
        assertEquals(StreamApply.COMMITTED, outcome)

        val snapshot = reducer.snapshot()
        // 工具事件到达时先把已缓冲正文落进快照，再追加步骤，顺序不会颠倒
        assertEquals(listOf(StreamStageType.TEXT, StreamStageType.COMMAND), types())
        assertEquals("partial answer", (snapshot.stages[0] as StreamTextStage).text)
        assertEquals(1, snapshot.tools.size)
        assertEquals(StreamToolStatus.RUNNING, snapshot.tools[0].status)
    }

    @Test
    fun `thinking streams while active and collapses when text arrives`() {
        now = 5_000L
        reducer.apply(thinking(1, "先看日志"))
        reducer.flush()
        var thought = reducer.snapshot().thoughtStages.single()
        assertTrue(thought.active)
        assertEquals("先看日志", thought.text)

        now = 8_000L
        reducer.apply(text(2, "好的，我开始查。"))
        reducer.flush()
        thought = reducer.snapshot().thoughtStages.single()
        assertFalse(thought.active)
        assertEquals(5_000L, thought.startedAtMs)
        assertEquals(3_000L, thought.elapsedMs(now))
        assertEquals(3_000L, thought.endedAtMs!! - thought.startedAtMs)
        // 思考与正文是两个阶段，先思考后正文
        assertEquals(listOf(StreamStageType.THOUGHT, StreamStageType.TEXT), types())
    }

    @Test
    fun `text then thinking keeps event order even when both were buffered`() {
        // 极端交错：正文增量还没 flush，思考增量就到了
        reducer.apply(delta(1, "先给结论。"))
        reducer.apply(thinking(2, "再解释原因。"))
        reducer.flush()

        val stages = reducer.snapshot().stages
        assertEquals(listOf(StreamStageType.TEXT, StreamStageType.THOUGHT), types())
        assertEquals("先给结论。", (stages[0] as StreamTextStage).text)
        assertEquals("再解释原因。", (stages[1] as StreamThoughtStage).text)
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
    fun `tool events without call id merge by name`() {
        // 老 generic adapter 可能不带 call_id：开始 + 收尾仍要合并成一张卡
        reducer.apply(
            event(
                1,
                "run_event",
                "kind" to "tool",
                "name" to "bash",
                "detail" to "ls",
                "status" to "running",
                "category" to "command",
            ),
        )
        reducer.apply(
            event(
                2,
                "run_event",
                "kind" to "tool",
                "output" to "a.txt",
                "status" to "completed",
                "category" to "command",
            ),
        )

        val snapshot = reducer.snapshot()
        assertEquals(1, snapshot.tools.size)
        assertEquals(StreamToolStatus.DONE, snapshot.tools.single().status)
        assertEquals("bash", snapshot.tools.single().name)
        assertEquals("a.txt", snapshot.tools.single().output)
    }

    @Test
    fun `failed tool keeps failed status`() {
        reducer.apply(tool(1, "call-2", "running", detail = "make"))
        reducer.apply(tool(2, "call-2", "failed", output = "exit 2"))
        assertEquals(StreamToolStatus.FAILED, reducer.snapshot().tools[0].status)
    }

    @Test
    fun `command batches and tool batches form separate groups`() {
        reducer.apply(tool(1, "c1", "running", detail = "ls"))
        reducer.apply(tool(2, "c1", "completed", output = "a.txt"))
        reducer.apply(tool(3, "c2", "running", detail = "cat package.json"))
        reducer.apply(tool(4, "c2", "completed"))
        reducer.apply(text(5, "前端在 apps/web。"))
        reducer.flush()
        reducer.apply(tool(6, "t1", "running", name = "read_file", detail = "src/a.ts", category = "read"))
        // 收尾事件通常不带工具名（后端会留空），不能把上一帧的 read_file 覆盖成兜底 shell
        reducer.apply(tool(7, "t1", "completed", name = "", output = "42 行", category = "read"))

        assertEquals(
            listOf(StreamStageType.COMMAND, StreamStageType.TEXT, StreamStageType.TOOL),
            types(),
        )
        assertEquals(2, group(0).steps.size)
        assertFalse(group(0).open)
        assertFalse(group(0).active)
        assertEquals("read_file", group(1).steps.single().name)
    }

    @Test
    fun `multiple text summaries alternate with tool groups`() {
        reducer.apply(thinking(1, "先确认目录。"))
        reducer.flush()
        reducer.apply(text(2, "先看结构。"))
        reducer.flush()
        reducer.apply(tool(3, "c1", "running", detail = "ls apps/"))
        reducer.apply(tool(4, "c1", "completed", output = "web"))
        reducer.apply(text(5, "前端在 apps/web。"))
        reducer.flush()
        reducer.apply(tool(6, "c2", "running", detail = "npm run dev"))
        reducer.apply(tool(7, "c2", "completed", output = "ready"))
        reducer.apply(text(8, "服务已启动。"))
        reducer.flush()

        assertEquals(
            listOf(
                StreamStageType.THOUGHT,
                StreamStageType.TEXT,
                StreamStageType.COMMAND,
                StreamStageType.TEXT,
                StreamStageType.COMMAND,
                StreamStageType.TEXT,
            ),
            types(),
        )
        assertEquals(3, reducer.snapshot().textStages.size)
        assertEquals(2, reducer.snapshot().toolGroups.size)
    }

    @Test
    fun `background command completion after text updates the original group in place`() {
        reducer.apply(tool(1, "bg", "running", detail = "npm run dev"))
        reducer.apply(text(2, "趁服务启动，我先写结论。"))
        reducer.flush()
        // 组已经被正文关闭，但命令仍在跑；完成事件必须回到原组，而不是新开一张卡
        reducer.apply(tool(3, "bg", "completed", output = "ready"))

        val groups = reducer.snapshot().toolGroups
        assertEquals(1, groups.size)
        assertEquals(StreamToolStatus.DONE, groups[0].steps.single().status)
        assertEquals(listOf(StreamStageType.COMMAND, StreamStageType.TEXT), types())
    }

    // ------------------------------------------------------------ 统一事件合同

    /**
     * 同一套规范化事件分别由 Claude Code / Codex / OpenCode / DSH / generic 的原始事件映射而来。
     * 只要字段统一，reducer 产出的阶段序列就完全一致——UI 不需要按 runtime 分支。
     */
    @Test
    fun `every runtime produces the same stage timeline from normalized events`() {
        val runtimes = listOf(
            // claude_code：Bash + Read，category 由后端 classify_tool 给出
            listOf(
                thinking(1, "先看看目录"),
                text(2, "我查一下。"),
                tool(3, "cc-1", "running", name = "Bash", detail = "ls", category = "command"),
                tool(4, "cc-1", "completed", output = "a.txt", category = "command"),
                tool(5, "cc-2", "running", name = "Read", detail = "{\"file_path\":\"a.ts\"}", category = "read"),
                tool(6, "cc-2", "completed", output = "42 行", category = "read"),
            ),
            // codex：command_execution / mcp_tool_call
            listOf(
                thinking(1, "先看看目录"),
                text(2, "我查一下。"),
                tool(3, "cx-1", "running", name = "shell", detail = "ls", category = "command"),
                tool(4, "cx-1", "completed", output = "a.txt", category = "command"),
                tool(5, "cx-2", "running", name = "Read", detail = "{\"file_path\":\"a.ts\"}", category = "read"),
                tool(6, "cx-2", "completed", output = "42 行", category = "read"),
            ),
            // opencode：tool_use part → bash / read
            listOf(
                thinking(1, "先看看目录"),
                text(2, "我查一下。"),
                tool(3, "oc-1", "running", name = "bash", detail = "ls", category = "command"),
                tool(4, "oc-1", "completed", output = "a.txt", category = "command"),
                tool(5, "oc-2", "running", name = "read", detail = "{\"file_path\":\"a.ts\"}", category = "read"),
                tool(6, "oc-2", "completed", output = "42 行", category = "read"),
            ),
            // DSH 内置 Agent：tool/call → tool/result
            listOf(
                thinking(1, "先看看目录"),
                text(2, "我查一下。"),
                tool(3, "dsh-1", "running", name = "bash", detail = "ls", category = "command"),
                tool(4, "dsh-1", "completed", output = "a.txt", category = "command"),
                tool(5, "dsh-2", "running", name = "read", detail = "{\"file_path\":\"a.ts\"}", category = "read"),
                tool(6, "dsh-2", "completed", output = "42 行", category = "read"),
            ),
            // generic CLI：唯一差异是没有 category（老 adapter），只能靠 name 归类
            listOf(
                thinking(1, "先看看目录"),
                text(2, "我查一下。"),
                tool(3, "g-1", "running", name = "run_command", detail = "ls", category = ""),
                tool(4, "g-1", "completed", output = "a.txt", category = ""),
                tool(5, "g-2", "running", name = "read_file", detail = "{\"file_path\":\"a.ts\"}", category = ""),
                tool(6, "g-2", "completed", output = "42 行", category = ""),
            ),
        )

        val timelines = runtimes.map { fixture ->
            val local = TaskStreamReducer { now }
            fixture.forEach { local.apply(it) }
            local.flush()
            local.snapshot().stages.map { stage ->
                when (stage) {
                    is StreamThoughtStage -> "thought"
                    is StreamTextStage -> "text"
                    is StreamToolGroupStage ->
                        if (stage.command) "commands(${stage.steps.size})" else "tools(${stage.steps.size})"
                }
            }
        }

        val expected = listOf("thought", "text", "commands(1)", "tools(1)")
        timelines.forEach { timeline -> assertEquals(expected, timeline) }
    }

    @Test
    fun `tool events are never swallowed into plain text`() {
        reducer.apply(text(1, "开始执行。"))
        reducer.apply(tool(2, "c1", "running", detail = "ls"))
        reducer.flush()
        reducer.apply(text(3, "命令跑完了。"))
        reducer.flush()

        val snapshot = reducer.snapshot()
        assertTrue(snapshot.toolGroups.isNotEmpty())
        assertEquals("ls", snapshot.tools.single().detail)
        // 正文仍然完整，但工具步骤没有被并进正文
        assertEquals("开始执行。命令跑完了。", snapshot.liveText)
    }

    // ------------------------------------------------------------ 收尾 / 稳定性

    @Test
    fun `run terminal keeps text in place and closes groups`() {
        reducer.apply(text(1, "中途小结"))
        reducer.flush()
        reducer.apply(tool(2, "call-3", "running", detail = "make"))
        reducer.apply(event(3, "run_finished"))

        val snapshot = reducer.snapshot()
        assertFalse(snapshot.liveActive)
        // 正文不能因为终态被丢掉：它仍留在时间线原来的位置
        assertEquals(listOf(StreamStageType.TEXT, StreamStageType.COMMAND), types())
        assertEquals("中途小结", snapshot.liveText)
        // 运行中的步骤收尾为完成
        assertEquals(StreamToolStatus.DONE, snapshot.tools.single().status)
        assertFalse(group(0).open)
        assertNotNull(snapshot.runEndedAtMs)
    }

    @Test
    fun `late deltas after run terminal are ignored and do not resurrect the bubble`() {
        reducer.apply(text(1, "最终答案"))
        reducer.flush()
        reducer.apply(event(2, "run_finished"))
        assertEquals(StreamApply.IGNORED, reducer.apply(delta(3, "迟到的增量")))
        reducer.flush()

        val snapshot = reducer.snapshot()
        // 终态正文保留，但迟到的增量不会追加进时间线
        assertEquals("最终答案", snapshot.liveText)
        assertEquals(listOf(StreamStageType.TEXT), types())
        assertFalse(snapshot.liveActive)
        assertNotNull(snapshot.runEndedAtMs)

        // 新一轮开始后重新接受增量
        reducer.apply(event(4, "run_started"))
        reducer.apply(delta(5, "新回复"))
        reducer.flush()
        assertEquals("新回复", reducer.snapshot().liveText)
        assertTrue(reducer.snapshot().liveActive)
    }

    @Test
    fun `settle finished is idempotent and keeps thought text and tools`() {
        now = 10_000L
        reducer.apply(thinking(1, "先看日志"))
        reducer.flush()
        reducer.apply(text(2, "中途正文"))
        reducer.flush()
        reducer.apply(tool(3, "c1", "running", detail = "ls"))
        now = 20_000L

        val first = reducer.settleFinished()
        val second = reducer.settleFinished()

        // 幂等 settle 不得把正文丢掉，也不得再产生新 revision
        assertEquals(first.stages, second.stages)
        assertEquals(first.revision, second.revision)
        assertFalse(second.liveActive)
        assertEquals(listOf(StreamStageType.THOUGHT, StreamStageType.TEXT, StreamStageType.COMMAND), types())
        assertEquals("中途正文", second.textStages.single().text)
        assertEquals(1, second.thoughtStages.size)
        assertEquals(1, second.toolGroups.size)
        assertEquals(StreamToolStatus.DONE, second.tools.single().status)
        assertEquals(20_000L, second.runEndedAtMs)
    }

    @Test
    fun `settled timeline keeps interleaved text in event order with the final answer last`() {
        reducer.apply(thinking(1, "先确认目录。"))
        reducer.flush()
        reducer.apply(text(2, "先看一下项目结构。"))
        reducer.flush()
        reducer.apply(tool(3, "c1", "running", detail = "ls apps/"))
        reducer.apply(tool(4, "c1", "completed", output = "web"))
        reducer.apply(text(5, "## 结果\n\n前端已启动。"))
        reducer.flush()
        reducer.apply(event(6, "run_finished"))

        val snapshot = reducer.snapshot()
        assertEquals(
            listOf(
                StreamStageType.THOUGHT,
                StreamStageType.TEXT,
                StreamStageType.COMMAND,
                StreamStageType.TEXT,
            ),
            types(),
        )
        // 中途正文保留在命令组之前，最终正文仍是最后一块
        assertEquals("先看一下项目结构。", (snapshot.stages[1] as StreamTextStage).text)
        assertEquals("## 结果\n\n前端已启动。", (snapshot.stages.last() as StreamTextStage).text)
        assertFalse(snapshot.thoughtStages.single().active)
        assertFalse(snapshot.toolGroups.single().open)
        assertFalse(snapshot.liveActive)
    }

    @Test
    fun `settle after terminal tolerates late tool events`() {
        reducer.apply(event(1, "run_finished"))
        // 收尾后仍有工具补发：不崩、不重新点亮运行条
        reducer.apply(tool(2, "c1", "completed", output = "late"))
        reducer.settleFinished()
        assertEquals("", reducer.snapshot().liveText)
        assertFalse(reducer.snapshot().liveActive)
    }

    @Test
    fun `assistant error keeps partial text and fails running tools`() {
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
    fun `reconnect replay with older seq is deduped`() {
        reducer.apply(delta(10, "already rendered"))
        reducer.flush()
        assertEquals("already rendered", reducer.snapshot().liveText)

        val outcome = reducer.apply(delta(10, "already rendered"))
        assertEquals(StreamApply.DUPLICATE, outcome)
        assertEquals(1, reducer.snapshot().duplicateEvents)

        reducer.apply(delta(11, " + next"))
        reducer.flush()
        assertEquals("already rendered + next", reducer.snapshot().liveText)
    }

    @Test
    fun `malformed event payloads never throw`() {
        val messy = TaskStreamEvent(
            seq = 1,
            type = "run_event",
            data = buildJsonObject {
                put("kind", "tool")
                put("call_id", JsonPrimitive("c1"))
                // 本该是字符串的字段给对象/数组：解析失败只当缺省，不能让整页闪退
                put("name", JsonObject(mapOf("bad" to JsonPrimitive(true))))
                put("detail", JsonArray(listOf(JsonPrimitive(1))))
                put("status", JsonPrimitive("completed"))
            },
        )
        reducer.apply(messy)
        reducer.apply(delta(2, "ok"))
        assertEquals("ok", reducer.flush().liveText)
        assertEquals(1, reducer.snapshot().tools.size)
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
    fun `stage count is bounded for pathological runs`() {
        var seq = 1
        repeat(STREAM_STAGE_LIMIT + 20) { index ->
            reducer.apply(thinking(seq++, "思考 $index"))
            reducer.flush()
            reducer.apply(text(seq++, "正文 $index"))
            reducer.flush()
        }
        assertTrue(reducer.snapshot().stages.size <= STREAM_STAGE_LIMIT)
        // 最后一段一定保留（可能是正在生成的内容）
        assertTrue(reducer.snapshot().stages.last() is StreamTextStage)
    }

    @Test
    fun `run start and finish drive summary duration`() {
        now = 10_000L
        reducer.apply(event(1, "run_started"))
        assertEquals(10_000L, reducer.snapshot().runStartedAtMs)
        assertNull(reducer.snapshot().runEndedAtMs)

        now = 31_000L
        reducer.apply(tool(2, "call-8", "completed"))
        reducer.apply(event(3, "run_finished"))
        val snapshot = reducer.snapshot()
        assertEquals(10_000L, snapshot.runStartedAtMs)
        assertEquals(31_000L, snapshot.runEndedAtMs)
        assertEquals(21_000L, snapshot.elapsedMs(now))
        // 结束后时间冻结，不再跟着 now 增长
        assertEquals(21_000L, snapshot.elapsedMs(99_000L))
    }

    @Test
    fun `start run clears previous activity but keeps seq watermark`() {
        reducer.apply(delta(7, "上一轮"))
        reducer.flush()
        reducer.apply(tool(8, "call-7", "completed"))
        val snapshot = reducer.startRun("正在思考…")

        assertEquals("", snapshot.liveText)
        assertTrue(snapshot.stages.isEmpty())
        assertTrue(snapshot.liveActive)
        assertEquals(8, snapshot.lastSeq)
    }

    @Test
    fun `assistant start keeps process stages and starts a new text run`() {
        reducer.apply(thinking(1, "思考"))
        reducer.flush()
        reducer.apply(tool(2, "c1", "running", detail = "ls"))
        reducer.apply(event(3, "assistant_start"))
        reducer.apply(delta(4, "新的回复"))
        reducer.flush()

        val snapshot = reducer.snapshot()
        // 旧的过程阶段不丢，新的正文接在后面
        assertEquals(1, snapshot.toolGroups.size)
        assertEquals(listOf(StreamStageType.THOUGHT, StreamStageType.COMMAND, StreamStageType.TEXT), types())
        assertEquals("新的回复", snapshot.liveText)
        assertTrue(snapshot.liveActive)
    }

    @Test
    fun `assistant message keeps streaming text in place and closes process stages`() {
        reducer.apply(thinking(1, "思考"))
        reducer.flush()
        reducer.apply(delta(2, "已落库的回复"))
        reducer.flush()
        reducer.apply(assistantMessage(3))

        val snapshot = reducer.snapshot()
        assertFalse(snapshot.liveActive)
        // 落库之后时间线仍完整：思考在前、正文在后，不再把正文删掉
        assertEquals(listOf(StreamStageType.THOUGHT, StreamStageType.TEXT), types())
        assertEquals("已落库的回复", snapshot.textStages.single().text)
        assertEquals(1, snapshot.thoughtStages.size)
        assertFalse(snapshot.thoughtStages.single().active)

        // 文字回复落库后，迟到的增量不能把已结束的气泡复活
        assertEquals(StreamApply.IGNORED, reducer.apply(delta(4, "迟到的补充")))
        reducer.flush()
        assertEquals("已落库的回复", reducer.snapshot().liveText)
    }

    @Test
    fun `mid run approval message does not swallow later process events`() {
        // 审批 / 计划消息也会以 assistant_message 落库，但一轮还没结束：
        // 后续的思考 / 命令 / 正文必须继续进入时间线。
        reducer.apply(tool(1, "c1", "running", detail = "ls"))
        reducer.apply(assistantMessage(2, kind = "decision"))
        reducer.apply(text(3, "批准后继续。"))
        reducer.flush()
        reducer.apply(tool(4, "c2", "running", detail = "cat package.json"))

        val snapshot = reducer.snapshot()
        // 中间消息没有把这一轮判死：后续过程仍在继续
        assertTrue(snapshot.liveActive)
        assertEquals(
            listOf(StreamStageType.COMMAND, StreamStageType.TEXT, StreamStageType.COMMAND),
            types(),
        )
        assertEquals("批准后继续。", snapshot.textStages.single().text)
        assertEquals(2, snapshot.toolGroups.size)
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
    fun `stop tap guard accepts first tap and ignores rapid double tap`() {
        val guard = StopTapGuard(windowMs = 700L)
        assertTrue(guard.tryAccept(1_000L))
        assertFalse(guard.tryAccept(1_120L))
        // 超过窗口后的再次点击（例如用户确认要再停一次）正常放行
        assertTrue(guard.tryAccept(1_800L))
    }
}
