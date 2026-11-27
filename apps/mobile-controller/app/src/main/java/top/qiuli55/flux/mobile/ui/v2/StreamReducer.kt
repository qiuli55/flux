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
 * 快照是一串**按时间排列的阶段**（思考 / 正文 / 命令组 / 工具组），而不是
 * 「一段 reasoning + 一段正文 + 一个工具列表」。这样思考、小结、命令、正文可以在
 * 同一条助手消息里多次交替 —— 所有 runtime 只要产出同一套 `run_event`
 * （kind = thinking / text / tool），前端就自动得到同一个效果，不需要按 Agent 名分支。
 *
 * 本类不依赖 Android（无 Log），可直接在 JVM 单测里跑。
 */
enum class StreamConnection { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED }

enum class StreamToolStatus { RUNNING, DONE, FAILED }

/** 阶段类型：思考 / 正文 / 命令组 / 工具组。 */
enum class StreamStageType { THOUGHT, TEXT, COMMAND, TOOL }

/** 时间线阶段；[id] 在一次归约器生命周期内单调唯一，供 Compose 记住展开状态。 */
sealed interface StreamStage {
    val id: String
}

/** 一段思考：同一批增量合并，遇到正文 / 命令 / 结束就收起。 */
data class StreamThoughtStage(
    override val id: String,
    val text: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val active: Boolean = false,
) : StreamStage {
    fun elapsedMs(nowMs: Long): Long = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0L)
}

/** 一段流式正文（Markdown 小结）；工具步骤之间可以出现多段。 */
data class StreamTextStage(
    override val id: String,
    val text: String = "",
) : StreamStage

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

/**
 * 一组连续的工具调用（父级可折叠）。
 *
 * [open] 表示时间线是否还停在这一组；[active] 额外包含「组内仍有命令在跑」——
 * 后台命令在正文继续后完成时，组早已关闭，但行上的蓝点要一直亮到真正结束。
 */
data class StreamToolGroupStage(
    override val id: String,
    val type: StreamStageType,
    val steps: List<StreamToolStep> = emptyList(),
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val open: Boolean = true,
) : StreamStage {
    val command: Boolean get() = type == StreamStageType.COMMAND
    val running: Boolean get() = steps.any { it.status == StreamToolStatus.RUNNING }
    val active: Boolean get() = open || running
    fun elapsedMs(nowMs: Long): Long = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0L)
}

data class StreamErrorLine(val id: Int, val text: String)

/**
 * UI 消费的不可变快照；[revision] 只在真正需要重绘时变化（flush / 结构事件）。
 *
 * [liveText] / [tools] 等是为兼容既有调用方保留的派生视图，唯一真源是 [stages]。
 */
data class StreamUiSnapshot(
    val stages: List<StreamStage> = emptyList(),
    val liveLabel: String? = null,
    val liveActive: Boolean = false,
    val errorLines: List<StreamErrorLine> = emptyList(),
    val connection: StreamConnection = StreamConnection.IDLE,
    val terminalError: String? = null,
    val cancelled: Boolean = false,
    val lastSeq: Int = 0,
    val reconnectAttempt: Int = 0,
    /** 重连时被 seq 去重挡掉的事件数，便于日志与单测断言。 */
    val duplicateEvents: Int = 0,
    /** 本轮开始时间：底部「共用时」用。0 = 未记录（历史快照）。 */
    val runStartedAtMs: Long = 0L,
    /** 本轮结束时间；null 表示仍在进行。 */
    val runEndedAtMs: Long? = null,
    val revision: Int = 0,
) {
    val thoughtStages: List<StreamThoughtStage>
        get() = stages.filterIsInstance<StreamThoughtStage>()

    val textStages: List<StreamTextStage>
        get() = stages.filterIsInstance<StreamTextStage>()

    val toolGroups: List<StreamToolGroupStage>
        get() = stages.filterIsInstance<StreamToolGroupStage>()

    /** 所有正文按时间拼接：判断「落库消息是否已包含这段流式正文」用。 */
    val liveText: String
        get() = textStages.joinToString("") { it.text }

    val tools: List<StreamToolStep>
        get() = toolGroups.flatMap { it.steps }

    val hasActivity: Boolean
        get() = stages.isNotEmpty() || errorLines.isNotEmpty()

    val runningTools: Int
        get() = tools.count { it.status == StreamToolStatus.RUNNING }

    /** 最近一段思考的视图（日志 / 旧调用方用）。 */
    val reasoning: StreamThoughtStage?
        get() = thoughtStages.lastOrNull()

    /** 本轮已运行时长；没有开始时间时返回 0，UI 不显示时长。 */
    fun elapsedMs(nowMs: Long): Long =
        if (runStartedAtMs <= 0L) 0L
        else ((runEndedAtMs ?: nowMs) - runStartedAtMs).coerceAtLeast(0L)
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

/** 单段正文上限：长任务不无限堆积。 */
internal const val STREAM_TEXT_LIMIT = 3000

/** 单段思考上限：思考过程可能很长，收起后仍保留最近一段可回看。 */
internal const val STREAM_REASONING_LIMIT = 4000

/**
 * 阶段总数上限：异常长的 Run 也不能让快照无限膨胀。
 * 超出时丢最早的阶段，但始终保留最后一段（可能是正在跑的组/正文）。
 */
internal const val STREAM_STAGE_LIMIT = 64

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

    /** 单调递增的阶段 id：重连/续传时不会与已经渲染的 `remember` 撞车。 */
    private var nextStageId = 0

    /**
     * 本轮是否已经落定（run_status / 终态错误 / 用户停止 / refresh 收尾）。
     * 落定之后迟到的正文增量和工具事件一律忽略——否则会把已经收起的运行条
     * 重新点亮、把「已结束」改回「进行中」（收尾阶段最常见的状态错乱）。
     */
    private var settled = false

    fun snapshot(): StreamUiSnapshot = state

    fun hasPendingDeltas(): Boolean = pendingText.isNotEmpty() || pendingReasoning.isNotEmpty()

    private fun newId(prefix: String): String = "$prefix-${nextStageId++}"

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
        if (settled && event.type in LATE_IGNORED_TYPES) {
            // 落定后仍可能收到重连补发的旧增量：丢弃，且不创建新阶段。
            dropPending()
            return StreamApply.IGNORED
        }
        return when (event.type) {
            "assistant_start" -> {
                // 新的助手回复开始：旧的过程阶段保留（同一轮 Agent 可能还在跑），
                // 只把上一段时间线收起，后续正文会接在新的 TextStage 上。
                settled = false
                structural {
                    it.copy(
                        liveActive = true,
                        liveLabel = "正在生成回复…",
                        terminalError = null,
                        cancelled = false,
                        stages = closeTrailing(it.stages, now()),
                        runStartedAtMs = it.runStartedAtMs.takeIf { start -> start > 0 } ?: now(),
                        runEndedAtMs = null,
                    )
                }
            }

            "assistant_delta" -> {
                val text = string(event, "text")
                if (text.isEmpty()) {
                    if (!state.liveActive || state.liveLabel == null) {
                        state = state.copy(
                            liveActive = true,
                            runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: now(),
                        )
                    }
                    StreamApply.BUFFERED
                } else {
                    // 思考增量还攒在缓冲里就来了正文：说明思考已经结束，先把它落进时间线，
                    // 否则 flush 时正文会跑到思考前面。
                    flushReasoningOnly()
                    pendingText.append(text)
                    // 正文一到就不再显示「正在生成」占位；真正重组等 flush。
                    state = state.copy(
                        liveActive = true,
                        liveLabel = null,
                        runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: now(),
                    )
                    StreamApply.BUFFERED
                }
            }

            "assistant_message" -> {
                // 这一条回复已经落库：时间线原样保留（思考 / 中途正文 / 命令组 / 最终正文
                // 都在各自发生的位置），只把进行中的阶段收起，不再把正文从时间线里删掉。
                val outcome = structural {
                    it.copy(
                        liveActive = false,
                        liveLabel = null,
                        stages = closeAll(it.stages, now()),
                        runEndedAtMs = it.runEndedAtMs ?: now(),
                    )
                }
                // 审批 / 计划这类助手消息会在一轮执行中途落库（approvals / plan_tools），
                // 后面还有 run_event；只有真正的文字回复落库才代表本轮结束，才忽略迟到增量。
                if (persistedReplyKind(event) in FINAL_REPLY_KINDS) settled = true
                outcome
            }

            "assistant_error" -> {
                val message = string(event, "error")
                    .ifBlank { string(event, "text") }
                    .ifBlank { "回复中断" }
                // 先 flush 再落定：错误前已经缓冲的正文必须保留下来。
                val outcome = structural {
                    it.copy(
                        liveActive = false,
                        liveLabel = null,
                        // 保留已经生成的正文：用户至少能看到停在哪
                        terminalError = message,
                        stages = closeAll(it.stages, now(), failure = true),
                        runEndedAtMs = it.runEndedAtMs ?: now(),
                    )
                }
                settled = true
                outcome
            }

            "run_started" -> startFresh {
                it.copy(liveLabel = "正在思考…")
            }

            "run_event" -> applyRunEvent(event)

            "run_status", "run_finished" -> {
                // 先 flush（终态前缓冲的最后一段可能还要给「部分输出」用），再落定。
                // 时间线保持事件顺序：中途正文与最终正文都留在原位，最终正文仍是最后一块。
                val outcome = structural {
                    it.copy(
                        liveActive = false,
                        liveLabel = null,
                        stages = closeAll(it.stages, now()),
                        runEndedAtMs = it.runEndedAtMs ?: now(),
                    )
                }
                settled = true
                outcome
            }

            "user_message" -> {
                settled = false
                resetActivityState()
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
        settled = true
        state = touch(
            state.copy(
                liveActive = false,
                liveLabel = null,
                cancelled = true,
                terminalError = null,
                stages = closeAll(state.stages, now()),
                runEndedAtMs = state.runEndedAtMs ?: now(),
            ),
        )
        return state
    }

    /** 任务已到终态（refresh 兜底）：收起进行中的阶段，完整时间线原样保留供回看。 */
    fun settleFinished(): StreamUiSnapshot {
        // refresh 可能连续触发多次：已经收尾且没有新的流式增量时不再产生新 revision，
        // 避免对话页在结束时被无意义地反复重组。正文不再被丢掉，所以这里也不能再拿
        // 「textStages 为空」当幂等条件，否则第二次 settle 会把最后一段正文漏掉。
        if (settled && !hasPendingDeltas() && !state.liveActive) {
            return state
        }
        flushInternal()
        settled = true
        state = touch(
            state.copy(
                liveActive = false,
                liveLabel = null,
                // 思考 / 中途正文 / 命令组 / 最终正文都留在时间线原位，只是不再处于「进行中」。
                stages = closeAll(state.stages, now()),
                runEndedAtMs = state.runEndedAtMs ?: now(),
            ),
        )
        return state
    }

    /** 新一轮开始（用户发消息 / 手动 start）：清空上一轮过程，保留连接与 seq 水位。 */
    fun startRun(label: String = "正在思考…"): StreamUiSnapshot {
        startFreshSnapshot(label)
        return state
    }

    /** 只清本轮内容，不动连接状态与去重水位。 */
    fun resetActivity(): StreamUiSnapshot {
        startFreshSnapshot(label = null)
        return state
    }

    /** 离开对话页：断开连接，停掉流式光标，但保留已收到的时间线（含正文）。 */
    fun stopStream(): StreamUiSnapshot {
        dropPending()
        state = touch(
            state.copy(
                liveActive = false,
                liveLabel = null,
                stages = closeAll(state.stages, now()),
                connection = StreamConnection.IDLE,
                reconnectAttempt = 0,
                runEndedAtMs = if (state.runStartedAtMs > 0) state.runEndedAtMs ?: now() else null,
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
        settled = true
        state = touch(
            state.copy(
                connection = StreamConnection.FAILED,
                terminalError = message.ifBlank { "实时连接失败" },
                liveActive = false,
                liveLabel = null,
                stages = closeAll(state.stages, now(), failure = true),
                runEndedAtMs = state.runEndedAtMs ?: now(),
            ),
        )
        return state
    }

    /** 用户点「重试」：清掉错误、重置退避，回到正在连接。 */
    fun retry(): StreamUiSnapshot {
        // 重连后还可能收到这一轮的后续事件，允许重新进入时间线。
        settled = false
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
                val timestamp = now()
                if (text.isBlank()) {
                    flushInternal()
                    state = touch(
                        state.copy(
                            stages = openThought(state.stages, timestamp),
                            liveActive = true,
                            liveLabel = if (state.liveText.isEmpty()) "正在思考…" else state.liveLabel,
                            runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: timestamp,
                            runEndedAtMs = null,
                        ),
                    )
                    return StreamApply.COMMITTED
                }
                // 正文还攒在缓冲里就来了思考：先把正文落进时间线，顺序才不会反。
                flushTextOnly()
                // 推理也是逐段到达，和正文一样先进缓冲，避免一条一条重组。
                state = state.copy(
                    liveActive = true,
                    liveLabel = if (state.liveText.isEmpty()) "正在思考…" else state.liveLabel,
                    runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: timestamp,
                    runEndedAtMs = null,
                )
                pendingReasoning.append(text)
                return StreamApply.BUFFERED
            }

            "tool" -> {
                flushInternal()
                val timestamp = now()
                state = touch(
                    state.copy(
                        stages = applyTool(event, state.stages, timestamp),
                        liveActive = true,
                        liveLabel = toolLabel(event),
                        runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: timestamp,
                        runEndedAtMs = null,
                    ),
                )
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
                    state = state.copy(
                        liveActive = true,
                        runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: now(),
                    )
                    return StreamApply.BUFFERED
                }
                flushReasoningOnly()
                pendingText.append(text)
                state = state.copy(
                    liveActive = true,
                    liveLabel = null,
                    runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: now(),
                )
                return StreamApply.BUFFERED
            }
        }
        return StreamApply.IGNORED
    }

    /**
     * 工具事件：先按 `call_id` 在已有阶段里原地合并（后台命令在正文之后完成也能对上），
     * 找不到才决定追加到当前组还是新开一组。
     */
    private fun applyTool(event: TaskStreamEvent, stages: List<StreamStage>, timestamp: Long): List<StreamStage> {
        val callId = resolveCallId(event, stages)
        val category = string(event, "category")
        val existingGroupIndex = stages.indexOfLast { stage ->
            stage is StreamToolGroupStage && stage.steps.any { it.id == callId }
        }
        if (existingGroupIndex >= 0) {
            val group = stages[existingGroupIndex] as StreamToolGroupStage
            val stepIndex = group.steps.indexOfFirst { it.id == callId }
            val steps = group.steps.toMutableList()
            steps[stepIndex] = mergeStep(steps[stepIndex], event)
            val updated = settleGroup(group.copy(steps = steps), timestamp)
            return stages.toMutableList().also { it[existingGroupIndex] = updated }
        }

        val type = stageTypeFor(category, string(event, "name"))
        val step = newStep(callId, event)
        val trailing = stages.lastOrNull()
        if (trailing is StreamToolGroupStage && trailing.open && trailing.type == type) {
            val updated = trailing.copy(steps = trailing.steps + step)
            return stages.dropLast(1) + updated
        }
        val closed = closeTrailing(stages, timestamp)
        return closed + StreamToolGroupStage(
            id = newId("group"),
            type = type,
            steps = listOf(step),
            startedAtMs = timestamp,
            open = true,
        )
    }

    /**
     * 没有 `call_id` 的 adapter（老 generic / 第三方 CLI）也不能画成两张卡：
     * 收尾事件按「最近一个同名且仍在运行」的步骤兜底匹配。
     */
    private fun resolveCallId(event: TaskStreamEvent, stages: List<StreamStage>): String {
        val raw = string(event, "call_id")
        if (raw.isNotBlank()) return raw
        val status = toolStatus(string(event, "status"))
        if (status != StreamToolStatus.RUNNING) {
            val name = string(event, "name")
            val match = stages.asReversed()
                .asSequence()
                .filterIsInstance<StreamToolGroupStage>()
                .flatMap { group -> group.steps.asReversed().asSequence() }
                .firstOrNull { step ->
                    step.status == StreamToolStatus.RUNNING &&
                        (name.isBlank() || step.name == name || step.name == "tool")
                }
            if (match != null) return match.id
        }
        return "tool-${event.seq}"
    }

    private fun mergeStep(previous: StreamToolStep, event: TaskStreamEvent): StreamToolStep {
        val name = string(event, "name")
        val detail = string(event, "detail")
        val output = string(event, "output")
        val statusRaw = string(event, "status")
        val category = string(event, "category")
        val paths = strings(event, "paths")
        return previous.copy(
            name = name.takeIf { it.isNotBlank() && it != "tool" } ?: previous.name,
            detail = detail.ifBlank { previous.detail },
            output = output.ifBlank { previous.output },
            status = if (statusRaw.isBlank()) previous.status else toolStatus(statusRaw),
            category = category.ifBlank { previous.category },
            paths = if (paths.isNotEmpty()) paths else previous.paths,
        )
    }

    private fun newStep(callId: String, event: TaskStreamEvent): StreamToolStep = StreamToolStep(
        id = callId,
        name = string(event, "name").ifBlank { "tool" },
        detail = string(event, "detail"),
        output = string(event, "output"),
        status = toolStatus(string(event, "status")),
        category = string(event, "category"),
        paths = strings(event, "paths"),
    )

    /** 一组里最后一帧结束后冻结结束时间；仍在跑则继续沿用开始时间。 */
    private fun settleGroup(
        group: StreamToolGroupStage,
        timestamp: Long,
    ): StreamToolGroupStage {
        val ended = if (group.open || group.running) null else group.endedAtMs ?: timestamp
        return group.copy(endedAtMs = ended)
    }

    /** 新阶段开始：上一组 / 上一段思考收起（时间线已经走过它们）。 */
    private fun closeTrailing(stages: List<StreamStage>, timestamp: Long): List<StreamStage> {
        val last = stages.lastOrNull() ?: return stages
        return when (last) {
            is StreamToolGroupStage -> {
                if (last.open) {
                    stages.dropLast(1) + settleGroup(
                        last.copy(open = false),
                        timestamp,
                    )
                } else {
                    stages
                }
            }

            is StreamThoughtStage -> {
                if (last.active) {
                    stages.dropLast(1) + last.copy(active = false, endedAtMs = last.endedAtMs ?: timestamp)
                } else {
                    stages
                }
            }

            is StreamTextStage -> stages
        }
    }

    private fun openThought(stages: List<StreamStage>, timestamp: Long): List<StreamStage> {
        val last = stages.lastOrNull()
        if (last is StreamThoughtStage && last.active) return stages
        return closeTrailing(stages, timestamp) + StreamThoughtStage(
            id = newId("thought"),
            startedAtMs = timestamp,
            active = true,
        )
    }

    private fun appendText(stages: List<StreamStage>, text: String): List<StreamStage> {
        val last = stages.lastOrNull()
        if (last is StreamTextStage) {
            val updated = last.copy(text = (last.text + text).takeLast(STREAM_TEXT_LIMIT))
            return stages.dropLast(1) + updated
        }
        // 思考/工具后面重新出现正文：先把上一段收起，再开新的正文段。
        val closed = closeTrailing(stages, now())
        return closed + StreamTextStage(id = newId("text"), text = text.takeLast(STREAM_TEXT_LIMIT))
    }

    private fun appendReasoning(stages: List<StreamStage>, text: String): List<StreamStage> {
        val last = stages.lastOrNull()
        if (last is StreamThoughtStage) {
            val updated = last.copy(
                text = (last.text + text).takeLast(STREAM_REASONING_LIMIT),
                active = true,
                endedAtMs = null,
            )
            return stages.dropLast(1) + updated
        }
        val opened = openThought(stages, now())
        val thought = opened.last() as StreamThoughtStage
        return opened.dropLast(1) + thought.copy(text = text.takeLast(STREAM_REASONING_LIMIT))
    }

    /** 收尾：所有阶段都不再处于「正在发生」状态；失败收尾把未完成的步骤标红。 */
    private fun closeAll(
        stages: List<StreamStage>,
        timestamp: Long,
        failure: Boolean = false,
    ): List<StreamStage> = stages.map { stage ->
        when (stage) {
            is StreamThoughtStage -> stage.copy(
                active = false,
                endedAtMs = stage.endedAtMs ?: timestamp,
            )

            is StreamToolGroupStage -> settleGroup(
                stage.copy(
                    open = false,
                    steps = stage.steps.map { step ->
                        if (step.status == StreamToolStatus.RUNNING) {
                            step.copy(
                                status = if (failure) {
                                    StreamToolStatus.FAILED
                                } else {
                                    StreamToolStatus.DONE
                                },
                            )
                        } else {
                            step
                        }
                    },
                ),
                timestamp,
            )

            is StreamTextStage -> stage
        }
    }

    private fun capStages(stages: List<StreamStage>): List<StreamStage> {
        if (stages.size <= STREAM_STAGE_LIMIT) return stages
        // 丢最早的；保留最后一段（可能是正在跑的组 / 正文）
        return stages.takeLast(STREAM_STAGE_LIMIT)
    }

    private inline fun structural(block: (StreamUiSnapshot) -> StreamUiSnapshot): StreamApply {
        flushInternal()
        state = touch(block(state))
        return StreamApply.COMMITTED
    }

    private inline fun startFresh(block: (StreamUiSnapshot) -> StreamUiSnapshot): StreamApply {
        dropPending()
        settled = false
        state = touch(
            block(
                state.copy(
                    stages = emptyList(),
                    liveActive = true,
                    liveLabel = null,
                    errorLines = emptyList(),
                    terminalError = null,
                    cancelled = false,
                    runStartedAtMs = now(),
                    runEndedAtMs = null,
                ),
            ),
        )
        return StreamApply.COMMITTED
    }

    private fun startFreshSnapshot(label: String?) {
        dropPending()
        settled = false
        state = touch(
            state.copy(
                stages = emptyList(),
                liveActive = label != null,
                liveLabel = label,
                errorLines = emptyList(),
                terminalError = null,
                cancelled = false,
                runStartedAtMs = now(),
                runEndedAtMs = null,
            ),
        )
    }

    private fun resetActivityState(): StreamApply {
        dropPending()
        settled = false
        state = touch(
            state.copy(
                stages = emptyList(),
                liveActive = false,
                liveLabel = null,
                errorLines = emptyList(),
                terminalError = null,
                cancelled = false,
                runStartedAtMs = 0L,
                runEndedAtMs = null,
            ),
        )
        return StreamApply.COMMITTED
    }

    private fun flushInternal() {
        if (!hasPendingDeltas()) return
        if (settled) {
            // 已经落定：迟到的增量直接丢弃，绝不把结束状态改回进行中。
            dropPending()
            return
        }
        val text = pendingText.toString()
        val reasoningText = pendingReasoning.toString()
        pendingText.setLength(0)
        pendingReasoning.setLength(0)
        var next = state
        if (text.isNotEmpty()) {
            next = next.copy(
                stages = capStages(appendText(next.stages, text)),
                liveLabel = null,
                liveActive = true,
                runStartedAtMs = next.runStartedAtMs.takeIf { it > 0 } ?: now(),
                runEndedAtMs = null,
            )
        }
        if (reasoningText.isNotEmpty()) {
            next = next.copy(
                stages = capStages(appendReasoning(next.stages, reasoningText)),
                liveActive = true,
                runStartedAtMs = next.runStartedAtMs.takeIf { it > 0 } ?: now(),
                runEndedAtMs = null,
            )
        }
        state = touch(next)
    }

    /**
     * 只把某一类缓冲落进快照。正文与思考交替时先 flush 对方，
     * 保证时间线顺序与事件到达顺序一致（不看 Agent / runtime 名）。
     */
    private fun flushTextOnly() {
        if (pendingText.isEmpty()) return
        val text = pendingText.toString()
        pendingText.setLength(0)
        val nowMs = now()
        state = touch(
            state.copy(
                stages = capStages(appendText(state.stages, text)),
                liveLabel = null,
                liveActive = true,
                runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: nowMs,
                runEndedAtMs = null,
            ),
        )
    }

    private fun flushReasoningOnly() {
        if (pendingReasoning.isEmpty()) return
        val text = pendingReasoning.toString()
        pendingReasoning.setLength(0)
        val nowMs = now()
        state = touch(
            state.copy(
                stages = capStages(appendReasoning(state.stages, text)),
                liveActive = true,
                runStartedAtMs = state.runStartedAtMs.takeIf { it > 0 } ?: nowMs,
                runEndedAtMs = null,
            ),
        )
    }

    private fun dropPending() {
        pendingText.setLength(0)
        pendingReasoning.setLength(0)
    }

    private fun toolLabel(event: TaskStreamEvent): String =
        if (stageTypeFor(string(event, "category"), string(event, "name")) == StreamStageType.COMMAND) {
            "正在运行命令…"
        } else {
            "正在调用工具…"
        }

    private fun stageTypeFor(category: String, name: String): StreamStageType {
        if (category.equals("command", ignoreCase = true)) return StreamStageType.COMMAND
        if (category.isBlank() && name.trim().lowercase() in COMMAND_NAMES) {
            // 旧后端 / 第三方 adapter 没带 category 时，至少别把 shell 命令画成普通工具。
            return StreamStageType.COMMAND
        }
        return StreamStageType.TOOL
    }

    private fun touch(next: StreamUiSnapshot): StreamUiSnapshot =
        next.copy(revision = state.revision + 1)

    private fun toolStatus(raw: String): StreamToolStatus = when (raw.trim().lowercase()) {
        "completed", "complete", "success", "succeeded", "done" -> StreamToolStatus.DONE
        "error", "failed", "failure" -> StreamToolStatus.FAILED
        else -> StreamToolStatus.RUNNING
    }

    /** 落库消息的 kind：审批 / 计划 / 交接等中间消息与真正的文字回复要区别对待。 */
    private fun persistedReplyKind(event: TaskStreamEvent): String =
        runCatching {
            (event.data["message"] as? JsonObject)
                ?.get("kind")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        }.getOrDefault("")

    private fun string(event: TaskStreamEvent, key: String): String =
        runCatching { event.data[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    private fun strings(event: TaskStreamEvent, key: String): List<String> =
        runCatching {
            (event.data[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        }.getOrDefault(emptyList())

    private companion object {
        /** 落定后不再接受的时间线增量（结构事件仍然处理）。 */
        val LATE_IGNORED_TYPES = setOf("assistant_delta", "run_event")

        /** 落库后代表「本轮文字回复已结束」的消息类型；审批 / 计划等中间消息不在内。 */
        val FINAL_REPLY_KINDS = setOf("text", "secure_input")

        val COMMAND_NAMES = setOf(
            "bash", "shell", "sh", "command", "command_execution",
            "exec", "execute", "local_shell", "run_command", "terminal", "cmd", "powershell",
        )
    }
}
