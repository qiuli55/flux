package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.qiuli55.flux.mobile.data.TaskStreamEvent

/**
 * 对话实时流的纯状态归约器。
 *
 * 为什么要单独一层：
 *  - SSE 的 `assistant_delta` 可能几十毫秒一条，直接每条都写 StateFlow 会让整页重组、
 *    列表反复滚动；这里先把增量攒在 pending 缓冲里，由 ViewModel 每 ~33ms flush 一次。
 *  - 断线重连按 `after_seq` 续传，但缓冲区边界上仍可能重叠；按 `seq` 去重，
 *    已经渲染过的正文不会再来一遍。
 *  - 连接状态、终态错误、取消都只改这里，UI 只消费不可变快照。
 *
 * 本类不依赖 Android（无 Log），可直接在 JVM 单测里跑。
 */
enum class StreamConnection { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED }

enum class StreamToolStatus { RUNNING, DONE, FAILED }

/** 一次 Run 的推理内容：同一轮的多段 thinking 合并成一段，收起后只占一行。 */
data class StreamReasoning(
    val text: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val active: Boolean = false,
) {
    fun elapsedMs(nowMs: Long): Long = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0L)
}

/** 一次工具调用；同名 `call_id` 的 started / completed 事件合并到同一步。 */
data class StreamToolStep(
    val id: String,
    val name: String = "tool",
    val detail: String = "",
    val output: String = "",
    val status: StreamToolStatus = StreamToolStatus.RUNNING,
    val category: String = "",
    val paths: List<String> = emptyList(),
)

data class StreamErrorLine(val id: Int, val text: String)

/** UI 消费的不可变快照；[revision] 只在真正需要重绘时变化（flush / 结构事件）。 */
data class StreamUiSnapshot(
    val liveText: String = "",
    val liveLabel: String? = null,
    val liveActive: Boolean = false,
    val reasoning: StreamReasoning? = null,
    val tools: List<StreamToolStep> = emptyList(),
    val errorLines: List<StreamErrorLine> = emptyList(),
    val connection: StreamConnection = StreamConnection.IDLE,
    val terminalError: String? = null,
    val cancelled: Boolean = false,
    val lastSeq: Int = 0,
    val reconnectAttempt: Int = 0,
    /** 重连时被 seq 去重挡掉的事件数，便于日志与单测断言。 */
    val duplicateEvents: Int = 0,
    val revision: Int = 0,
) {
    val hasActivity: Boolean
        get() = reasoning != null || tools.isNotEmpty() || errorLines.isNotEmpty()

    val runningTools: Int
        get() = tools.count { it.status == StreamToolStatus.RUNNING }
}

internal enum class StreamApply {
    /** 结构变化（起止 / 工具 / 错误 / 重连），可以立刻推给 UI。 */
    COMMITTED,

    /** 正文/推理增量已进缓冲，等下一次 flush；此时不要重组 UI。 */
    BUFFERED,

    /** seq 不大于已应用水位：重连重叠的事件，丢弃。 */
    DUPLICATE,

    /** 与对话渲染无关的事件（agent 切换等由 ViewModel 另行处理）。 */
    IGNORED,
}

/** 正文只留尾部一段，长任务不无限堆积。 */
internal const val STREAM_TEXT_LIMIT = 3000

/** 推理内容上限：思考过程可能很长，收起后仍保留最近一段可回看。 */
internal const val STREAM_REASONING_LIMIT = 4000

/**
 * 指数退避：第 1 次重试 1s，之后 2s / 4s / 8s / 15s 封顶。
 * 纯函数，单测可直接断言（不引入随机抖动，保证可复现）。
 */
internal fun reconnectDelayMs(attempt: Int, baseMs: Long = 1_000L, capMs: Long = 15_000L): Long {
    if (attempt <= 1) return baseMs
    var delay = baseMs
    repeat((attempt - 1).coerceAtMost(8)) {
        delay = (delay * 2).coerceAtMost(capMs)
    }
    return delay
}

internal class TaskStreamReducer(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var state = StreamUiSnapshot()
    private val pendingText = StringBuilder()
    private val pendingReasoning = StringBuilder()

    fun snapshot(): StreamUiSnapshot = state

    fun hasPendingDeltas(): Boolean = pendingText.isNotEmpty() || pendingReasoning.isNotEmpty()

    /**
     * 应用一条事件。
     *
     * 增量事件只进缓冲（返回 [StreamApply.BUFFERED]），结构事件会先 flush 缓冲
     * 再改状态，保证「先到正文、后到终态」的顺序不会把正文吞掉。
     */
    fun apply(event: TaskStreamEvent): StreamApply {
        if (event.seq > 0) {
            if (event.seq <= state.lastSeq) {
                state = state.copy(duplicateEvents = state.duplicateEvents + 1)
                return StreamApply.DUPLICATE
            }
            state = state.copy(lastSeq = event.seq)
        }
        return when (event.type) {
            "assistant_start" -> structural {
                it.copy(
                    liveActive = true,
                    liveText = "",
                    liveLabel = "正在生成回复…",
                    // 普通回话也可能接在上一轮工具之后：不继承上一轮的过程。
                    reasoning = null,
                    tools = emptyList(),
                    errorLines = emptyList(),
                    terminalError = null,
                    cancelled = false,
                )
            }

            "assistant_delta" -> {
                val text = string(event, "text")
                if (text.isEmpty()) {
                    if (!state.liveActive || state.liveLabel == null) {
                        state = state.copy(liveActive = true)
                    }
                    StreamApply.BUFFERED
                } else {
                    pendingText.append(text)
                    // 正文一到就不再显示「正在生成」占位；真正重组等 flush。
                    state = state.copy(liveActive = true, liveLabel = null)
                    StreamApply.BUFFERED
                }
            }

            "assistant_message" -> structural {
                it.copy(
                    liveActive = false,
                    liveText = "",
                    liveLabel = null,
                    reasoning = endReasoning(it.reasoning),
                    tools = finishRunning(it.tools),
                    terminalError = null,
                )
            }

            "assistant_error" -> {
                val message = string(event, "error")
                    .ifBlank { string(event, "text") }
                    .ifBlank { "回复中断" }
                structural {
                    it.copy(
                        liveActive = false,
                        liveLabel = null,
                        // 保留已经生成的正文：用户至少能看到停在哪
                        terminalError = message,
                        reasoning = endReasoning(it.reasoning),
                        tools = it.tools.map { step ->
                            if (step.status == StreamToolStatus.RUNNING) {
                                step.copy(status = StreamToolStatus.FAILED)
                            } else {
                                step
                            }
                        },
                    )
                }
            }

            "run_started" -> structural {
                it.copy(
                    liveActive = true,
                    liveText = "",
                    liveLabel = "正在思考…",
                    reasoning = null,
                    tools = emptyList(),
                    errorLines = emptyList(),
                    terminalError = null,
                    cancelled = false,
                )
            }

            "run_event" -> applyRunEvent(event)

            "run_status" -> {
                val status = string(event, "status").lowercase()
                val failed = status == "failed" || status == "error"
                structural {
                    it.copy(
                        liveActive = false,
                        liveLabel = null,
                        // 先到正文、后到终态：这里只收尾，不清正文；由 run_finished 统一交棒，
                        // 避免执行结束瞬间正文先消失、落库消息随后才到造成的闪烁。
                        reasoning = endReasoning(it.reasoning),
                        tools = finishRunning(it.tools, failed),
                        terminalError = if (failed) {
                            it.terminalError ?: string(event, "error").ifBlank { "执行失败" }
                        } else {
                            it.terminalError
                        },
                    )
                }
            }

            "run_finished" -> structural {
                // 终态由落库的 run_finished 消息接管：清空实时内容，
                // 思考/工具改从消息下方的执行轨迹卡回看，避免与持久化消息重复。
                it.copy(
                    liveActive = false,
                    liveText = "",
                    liveLabel = null,
                    reasoning = null,
                    tools = emptyList(),
                    errorLines = emptyList(),
                    terminalError = null,
                )
            }

            "user_message" -> structural {
                it.copy(
                    liveActive = false,
                    liveText = "",
                    liveLabel = null,
                    reasoning = null,
                    tools = emptyList(),
                    errorLines = emptyList(),
                    terminalError = null,
                    cancelled = false,
                )
            }

            else -> StreamApply.IGNORED
        }
    }

    /** 把缓冲的正文/推理增量合并进快照；没有缓冲时原样返回（不产生新 revision）。 */
    fun flush(): StreamUiSnapshot {
        if (!hasPendingDeltas()) return state
        flushInternal()
        return state
    }

    /** 手动停止 / 取消：保留已生成正文，运行中的工具标成已结束，避免一直转圈。 */
    fun markCancelled(): StreamUiSnapshot {
        flushInternal()
        state = touch(
            state.copy(
                liveActive = false,
                liveLabel = null,
                cancelled = true,
                terminalError = null,
                reasoning = endReasoning(state.reasoning),
                tools = finishRunning(state.tools),
            ),
        )
        return state
    }

    /** 任务已到终态（refresh 兜底）：与 run_finished 一致，交给落库消息与轨迹卡。 */
    fun settleFinished(): StreamUiSnapshot {
        flushInternal()
        state = touch(
            state.copy(
                liveActive = false,
                liveText = "",
                liveLabel = null,
                reasoning = null,
                tools = emptyList(),
                errorLines = emptyList(),
                terminalError = null,
            ),
        )
        return state
    }

    /** 新一轮开始（用户发消息 / 手动 start）：清空上一轮过程，保留连接与 seq 水位。 */
    fun startRun(label: String = "正在思考…"): StreamUiSnapshot {
        dropPending()
        state = touch(
            state.copy(
                liveActive = true,
                liveText = "",
                liveLabel = label,
                reasoning = null,
                tools = emptyList(),
                errorLines = emptyList(),
                terminalError = null,
                cancelled = false,
            ),
        )
        return state
    }

    /** 只清本轮内容，不动连接状态与去重水位。 */
    fun resetActivity(): StreamUiSnapshot {
        dropPending()
        state = touch(
            state.copy(
                liveActive = false,
                liveText = "",
                liveLabel = null,
                reasoning = null,
                tools = emptyList(),
                errorLines = emptyList(),
                terminalError = null,
                cancelled = false,
            ),
        )
        return state
    }

    /** 离开对话页：断开连接，停掉流式光标，但保留已收到的过程。 */
    fun stopStream(): StreamUiSnapshot {
        dropPending()
        state = touch(
            state.copy(
                liveActive = false,
                liveText = "",
                liveLabel = null,
                reasoning = endReasoning(state.reasoning),
                connection = StreamConnection.IDLE,
                reconnectAttempt = 0,
            ),
        )
        return state
    }

    fun onConnecting(): StreamUiSnapshot {
        state = touch(state.copy(connection = StreamConnection.CONNECTING))
        return state
    }

    fun onOpen(): StreamUiSnapshot {
        state = touch(
            state.copy(
                connection = StreamConnection.LIVE,
                reconnectAttempt = 0,
            ),
        )
        return state
    }

    /** 连接断开：首次失败记 1 次重试，供 ViewModel 计算退避。 */
    fun onDisconnected(): StreamUiSnapshot {
        state = touch(
            state.copy(
                connection = StreamConnection.RECONNECTING,
                reconnectAttempt = state.reconnectAttempt + 1,
            ),
        )
        return state
    }

    /**
     * 终态错误（assistant_error 或重连次数耗尽）：保留部分输出，
     * 连接标记 FAILED，由 UI 提供「重试」。
     */
    fun onTerminal(message: String): StreamUiSnapshot {
        flushInternal()
        state = touch(
            state.copy(
                connection = StreamConnection.FAILED,
                terminalError = message.ifBlank { "实时连接失败" },
                liveActive = false,
                liveLabel = null,
                reasoning = endReasoning(state.reasoning),
                tools = state.tools.map { step ->
                    if (step.status == StreamToolStatus.RUNNING) {
                        step.copy(status = StreamToolStatus.FAILED)
                    } else {
                        step
                    }
                },
            ),
        )
        return state
    }

    /** 用户点「重试」：清掉错误、重置退避，回到正在连接。 */
    fun retry(): StreamUiSnapshot {
        state = touch(
            state.copy(
                connection = StreamConnection.CONNECTING,
                terminalError = null,
                reconnectAttempt = 0,
            ),
        )
        return state
    }

    // ------------------------------------------------------------ internals

    private fun applyRunEvent(event: TaskStreamEvent): StreamApply {
        val kind = string(event, "kind")
        val text = string(event, "text")
        when (kind) {
            "thinking" -> {
                if (text.isBlank()) {
                    flushInternal()
                    val reasoning = state.reasoning ?: StreamReasoning(startedAtMs = now(), active = true)
                    state = touch(
                        state.copy(
                            reasoning = reasoning.copy(active = true),
                            liveActive = true,
                            liveLabel = if (state.liveText.isEmpty()) "正在思考…" else state.liveLabel,
                        ),
                    )
                    return StreamApply.COMMITTED
                }
                // 推理也是逐段到达，和正文一样先进缓冲，避免一条一条重组。
                val reasoning = state.reasoning ?: StreamReasoning(startedAtMs = now(), active = true)
                state = state.copy(
                    reasoning = reasoning.copy(active = true),
                    liveActive = true,
                    liveLabel = if (state.liveText.isEmpty()) "正在思考…" else state.liveLabel,
                )
                pendingReasoning.append(text)
                return StreamApply.BUFFERED
            }

            "tool" -> {
                flushInternal()
                state = touch(applyToolEvent(event))
                return StreamApply.COMMITTED
            }

            "error" -> {
                flushInternal()
                state = touch(
                    state.copy(
                        errorLines = state.errorLines + StreamErrorLine(
                            id = event.seq,
                            text = text.ifBlank { "执行出错" },
                        ),
                        liveActive = true,
                        liveLabel = "执行出错",
                    ),
                )
                return StreamApply.COMMITTED
            }

            "text" -> {
                if (text.isEmpty()) {
                    state = state.copy(liveActive = true)
                    return StreamApply.BUFFERED
                }
                pendingText.append(text)
                state = state.copy(liveActive = true, liveLabel = null)
                return StreamApply.BUFFERED
            }
        }
        return StreamApply.IGNORED
    }

    private fun applyToolEvent(event: TaskStreamEvent): StreamUiSnapshot {
        val callId = string(event, "call_id").ifBlank { "tool-${event.seq}" }
        val name = string(event, "name")
        val detail = string(event, "detail")
        val output = string(event, "output")
        val statusRaw = string(event, "status")
        val category = string(event, "category")
        val paths = strings(event, "paths")
        val steps = state.tools.toMutableList()
        val index = steps.indexOfFirst { it.id == callId }
        if (index >= 0) {
            // codex 的同一 item 先 started 再 completed：按 call_id 合并成一张卡；
            // 收尾事件可能只带输出与状态（DSH），不能整条覆盖掉已有的命令与分类。
            val previous = steps[index]
            steps[index] = previous.copy(
                name = name.takeIf { it.isNotBlank() && it != "tool" } ?: previous.name,
                detail = detail.ifBlank { previous.detail },
                output = output.ifBlank { previous.output },
                status = if (statusRaw.isBlank()) previous.status else toolStatus(statusRaw),
                category = category.ifBlank { previous.category },
                paths = if (paths.isNotEmpty()) paths else previous.paths,
            )
        } else {
            steps += StreamToolStep(
                id = callId,
                name = name.ifBlank { "tool" },
                detail = detail,
                output = output,
                status = toolStatus(statusRaw),
                category = category,
                paths = paths,
            )
        }
        return state.copy(
            tools = steps,
            liveActive = true,
            liveLabel = if (state.liveText.isEmpty()) "正在执行命令…" else state.liveLabel,
        )
    }

    private inline fun structural(block: (StreamUiSnapshot) -> StreamUiSnapshot): StreamApply {
        flushInternal()
        state = touch(block(state))
        return StreamApply.COMMITTED
    }

    private fun flushInternal() {
        if (!hasPendingDeltas()) return
        val text = pendingText.toString()
        val reasoningText = pendingReasoning.toString()
        pendingText.setLength(0)
        pendingReasoning.setLength(0)
        var next = state
        if (text.isNotEmpty()) {
            next = next.copy(
                liveText = (next.liveText + text).takeLast(STREAM_TEXT_LIMIT),
                liveLabel = null,
                liveActive = true,
            )
        }
        if (reasoningText.isNotEmpty()) {
            val reasoning = next.reasoning ?: StreamReasoning(startedAtMs = now(), active = true)
            next = next.copy(
                reasoning = reasoning.copy(
                    text = (reasoning.text + reasoningText).takeLast(STREAM_REASONING_LIMIT),
                    active = true,
                ),
            )
        }
        state = touch(next)
    }

    private fun dropPending() {
        pendingText.setLength(0)
        pendingReasoning.setLength(0)
    }

    private fun endReasoning(reasoning: StreamReasoning?): StreamReasoning? =
        reasoning?.copy(active = false, endedAtMs = reasoning.endedAtMs ?: now())

    private fun finishRunning(
        tools: List<StreamToolStep>,
        failed: Boolean = false,
    ): List<StreamToolStep> =
        tools.map { step ->
            if (step.status != StreamToolStatus.RUNNING) {
                step
            } else {
                step.copy(status = if (failed) StreamToolStatus.FAILED else StreamToolStatus.DONE)
            }
        }

    private fun touch(next: StreamUiSnapshot): StreamUiSnapshot =
        next.copy(revision = state.revision + 1)

    private fun toolStatus(raw: String): StreamToolStatus = when (raw.trim().lowercase()) {
        "completed", "complete", "success", "succeeded", "done" -> StreamToolStatus.DONE
        "error", "failed", "failure" -> StreamToolStatus.FAILED
        else -> StreamToolStatus.RUNNING
    }

    private fun string(event: TaskStreamEvent, key: String): String =
        runCatching { event.data[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    private fun strings(event: TaskStreamEvent, key: String): List<String> =
        runCatching {
            (event.data[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.getOrDefault(emptyList())
}
