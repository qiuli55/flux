package top.qiuli55.flux.mobile.ui.tasks

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.sse.EventSource
import top.qiuli55.flux.mobile.data.Agent
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.HandoffSummary
import top.qiuli55.flux.mobile.data.Run
import top.qiuli55.flux.mobile.data.RunTrace
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.TaskContextUsage
import top.qiuli55.flux.mobile.data.TaskMessage
import top.qiuli55.flux.mobile.data.TaskRunSummary
import top.qiuli55.flux.mobile.data.TaskStreamEvent
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.ui.FluxViewModel
import top.qiuli55.flux.mobile.ui.v2.V2RunItem
import top.qiuli55.flux.mobile.ui.v2.isFinished
import top.qiuli55.flux.mobile.ui.v2.readable

// ---------------------------------------------------------------- 任务列表

data class TaskListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val creating: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val error: String? = null,
)

class TaskListViewModel : FluxViewModel() {

    private var refreshJob: Job? = null
    private val _state = MutableStateFlow(TaskListUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = _state.value.tasks.isEmpty(),
                refreshing = true,
                error = null,
            )
            try {
                val api = api()
                // 顺序调用：两个请求互不依赖，用 async 时任一失败会取消整组，
                // 异常还可能绕过 catch 直接打到主线程（真机崩溃过），不如直接串行。
                val tasks = api.listTasks()
                val agents = runCatching { api.listAgents() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    tasks = tasks,
                    agents = agents,
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, refreshing = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = e.message ?: "加载失败",
                )
            }
        }
    }

    fun createTask(description: String, decisionMode: String, onCreated: (String) -> Unit) {
        val cleanDescription = description.trim()
        if (cleanDescription.isEmpty() || _state.value.creating) {
            if (cleanDescription.isEmpty()) {
                _state.value = _state.value.copy(error = "任务描述不能为空")
            }
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null)
            try {
                val agentId = _state.value.agents.firstOrNull { it.spec?.name == "flux-builtin" }?.id
                    ?: _state.value.agents.firstOrNull()?.id
                val task = api().createTask(cleanDescription, agentId, decisionMode)
                _state.value = _state.value.copy(creating = false)
                refresh()
                onCreated(task.id)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(creating = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(creating = false, error = e.message ?: "创建失败")
            }
        }
    }
}

// ---------------------------------------------------------------- 任务详情

data class TaskDetailUiState(
    val loading: Boolean = true,
    val task: Task? = null,
    val messages: List<TaskMessage> = emptyList(),
    val changes: List<Change> = emptyList(),
    val run: Run? = null,
    val error: String? = null,
    val notice: String? = null,
    val sending: Boolean = false,
    val busyAction: String? = null,
    val draft: String = "",
    //: 流式回复：已收到的增量正文；liveLabel 用于还没有正文时的状态提示
    val liveText: String = "",
    val liveLabel: String? = null,
    val liveActive: Boolean = false,
    //: 执行轨迹（思考 / 命令）：本轮 Run 的实时事件，按到达顺序累积
    val runItems: List<V2RunItem> = emptyList(),
    //: 每一轮 Run 的摘要（耗时 / 改动 / 命令 / 读取）：对话里的「任务耗时」入口靠它
    val runs: List<TaskRunSummary> = emptyList(),
    //: 正在查看的 Run 轨迹：非空时详情弹层打开
    val traceRunId: String? = null,
    val trace: RunTrace? = null,
    val traceLoading: Boolean = false,
    //: 上下文占用（「上下文快满」提醒的唯一口径）
    val contextUsage: TaskContextUsage? = null,
    //: 用户点过「继续聊」的等级（warning / full）：同一等级不再打扰，涨档再提醒
    val contextDismissedLevel: String? = null,
    val handoffBusy: Boolean = false,
    //: 已上传、等待随下一条消息发送的附件；发送成功才清空，失败保留便于重试
    val pendingAttachments: List<UploadedAttachment> = emptyList(),
    //: 附件上传中：禁止发送，UI 显示「上传中…」
    val uploading: Boolean = false,
)

/** 上下文提醒是否该出现：normal 不提醒；同一等级点过「继续聊」就不再打扰。 */
internal fun shouldPromptContext(usage: TaskContextUsage?, dismissedLevel: String?): Boolean =
    usage != null && usage.level != "normal" && usage.level != dismissedLevel

/**
 * 对话页能不能切换 Agent：执行中也可以——切换会排队到本轮结束后生效
 * （`pending_agent_id`），当前这一轮仍由原 Agent 跑完；已结束的对话立即生效。
 */
internal fun canSwitchTaskAgent(task: Task?): Boolean = task != null

/**
 * 能不能切换模型：执行中不行（模型只影响下一轮，但正在跑的一轮已按快照执行，
 * 中途换模型容易让用户误以为本轮已换）；已结束/等待决策时可以。
 */
internal fun canSwitchTaskModel(task: Task?): Boolean =
    task != null && task.status != "running"

/** 系统文件选择器返回的 Uri 对应的展示信息。 */
internal data class PickedAttachment(val name: String, val size: Long, val mime: String?)

/**
 * 用 ContentResolver 读文件名 / 大小；SIZE 拿不到时退回 AssetFileDescriptor，
 * 仍拿不到就用 -1（上传时按分块发送，由服务端兜底 50MB 限制）。
 */
internal fun readPickedAttachment(resolver: ContentResolver, uri: Uri): PickedAttachment? {
    var name: String? = null
    var size = -1L
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    if (size < 0) {
        size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }
    val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val displayName = name?.takeIf { it.isNotBlank() } ?: fallback ?: "file"
    return PickedAttachment(displayName, size, resolver.getType(uri))
}


/**
 * 首次接入轮询时过滤旧缓冲：如果这批事件里已经出现某一轮 Run 的终态，
 * 说明它是上一次执行留下的（重进已结束的对话会整段重放），
 * 当实时事件播放会闪一下上一轮的思考与正文；只保留终态之后的新事件。
 */
internal fun dropStaleStreamEvents(events: List<TaskStreamEvent>): List<TaskStreamEvent> {
    val lastTerminal = events.indexOfLast {
        it.type == "run_status" || it.type == "run_finished" || it.type == "assistant_error"
    }
    return if (lastTerminal >= 0) events.drop(lastTerminal + 1) else events
}


class TaskDetailViewModel(private val taskId: String) : FluxViewModel() {

    private var refreshJob: Job? = null
    private var pollingJob: Job? = null
    private var streamSource: EventSource? = null
    private var streamRetryJob: Job? = null
    private var streamPollJob: Job? = null
    private var streamEnabled = false
    private var streamGeneration = 0
    private var lastStreamSeq = 0
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        startPolling()
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(POLL_INTERVAL_MS)
                val status = _state.value.task?.status ?: continue
                if (status == "running" || status == "waiting_for_user_decision") {
                    refresh(quiet = true)
                }
            }
        }
    }

    fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    /**
     * 选中文件后逐个上传；超过 5 个的部分跳过，单个超过 50MB 的文件也跳过并提示。
     * 上传中禁止发送由 [send] 与 UI 共同兜住。
     */
    fun addAttachments(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty() || _state.value.uploading) return
        val remaining = UploadedAttachment.MAX_FILES - _state.value.pendingAttachments.size
        if (remaining <= 0) {
            _state.value = _state.value.copy(error = "一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件")
            return
        }
        val accepted = uris.take(remaining)
        val ignored = uris.size - accepted.size
        viewModelScope.launch {
            _state.value = _state.value.copy(uploading = true, error = null)
            val problems = mutableListOf<String>()
            accepted.forEach { uri ->
                val picked = withContext(Dispatchers.IO) { readPickedAttachment(resolver, uri) }
                if (picked == null) {
                    problems += "无法读取所选文件"
                    return@forEach
                }
                if (picked.size > UploadedAttachment.MAX_BYTES) {
                    problems += "「${picked.name}」超过 50MB，已跳过"
                    return@forEach
                }
                try {
                    val uploaded = api().uploadAttachment(
                        name = picked.name,
                        mime = picked.mime,
                        size = picked.size,
                    ) {
                        resolver.openInputStream(uri)
                            ?: throw FluxApiException("attachment_unreadable", "无法读取「${picked.name}」")
                    }
                    _state.value = _state.value.copy(
                        pendingAttachments = _state.value.pendingAttachments + uploaded,
                    )
                } catch (e: Exception) {
                    problems += readable(e)
                }
            }
            if (ignored > 0) {
                problems += "一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件，已忽略多选的 $ignored 个"
            }
            _state.value = _state.value.copy(
                uploading = false,
                error = problems.take(2).joinToString("；").takeIf { it.isNotBlank() },
            )
        }
    }

    fun removeAttachment(id: String) {
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.id == id },
        )
    }

    /** 「继续聊」：同一占用等级不再打扰；涨到下一档（full）会再提醒一次。 */
    fun continueInCurrentChat() {
        val level = _state.value.contextUsage?.level ?: return
        _state.value = _state.value.copy(contextDismissedLevel = level)
    }

    /**
     * 生成前情摘要，交给页面去新建对话。
     *
     * 失败只提示、不切换页面：当前对话还在，用户不该因为摘要生成失败被踢出去。
     */
    fun createHandoff(onReady: (HandoffSummary) -> Unit) {
        if (_state.value.handoffBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(handoffBusy = true, error = null, notice = null)
            try {
                val summary = api().createHandoffSummary(taskId)
                if (summary.summary.isBlank()) {
                    _state.value = _state.value.copy(handoffBusy = false, notice = "摘要生成为空，请稍后重试")
                    return@launch
                }
                _state.value = _state.value.copy(handoffBusy = false)
                onReady(summary)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(handoffBusy = false, notice = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    handoffBusy = false,
                    notice = e.message ?: "摘要生成失败",
                )
            }
        }
    }

    fun refresh(quiet: Boolean = false) {
        if (quiet && refreshJob?.isActive == true) return
        if (!quiet) refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = _state.value.task == null)
            try {
                val api = api()
                val task = api.getTask(taskId)
                val messagesDeferred = async { api.listMessages(taskId, limit = 60) }
                val changesDeferred = async { api.listChanges(taskId = taskId) }
                val contextDeferred = async {
                    // 上下文占用是提醒用的锦上添花：取不到时保留上一次读数，不打断对话
                    runCatching { api.getTaskContext(taskId) }.getOrNull()
                }
                val runDeferred = async {
                    task.runId?.let { runId -> runCatching { api.getRun(runId) }.getOrNull() }
                }
                val runsDeferred = async {
                    // 历史轮次的耗时与概览：取不到时不打断对话，先用实时轨迹顶上
                    runCatching { api.listTaskRuns(taskId) }.getOrDefault(emptyList())
                }
                val finished = task.isFinished
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    task = task,
                    messages = messagesDeferred.await(),
                    changes = changesDeferred.await(),
                    contextUsage = contextDeferred.await() ?: _state.value.contextUsage,
                    run = runDeferred.await(),
                    runs = runsDeferred.await(),
                    // 一轮结束后实时轨迹收进「任务耗时 → 详情」里，聊天只留结论气泡，
                    // 避免执行步骤和详情弹层重复铺满屏幕
                    runItems = if (finished) emptyList() else _state.value.runItems,
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 打开某一轮 Run 的执行详情：概览、思考过程、命令与输出都从轨迹接口读。 */
    fun openRunTrace(runId: String) {
        if (runId.isBlank()) return
        _state.value = _state.value.copy(traceRunId = runId, trace = null, traceLoading = true)
        viewModelScope.launch {
            try {
                val trace = api().getRunTrace(taskId, runId)
                if (_state.value.traceRunId == runId) {
                    _state.value = _state.value.copy(trace = trace, traceLoading = false)
                }
            } catch (e: FluxApiException) {
                if (_state.value.traceRunId == runId) {
                    _state.value = _state.value.copy(traceLoading = false, error = e.message)
                }
            } catch (e: Exception) {
                if (_state.value.traceRunId == runId) {
                    _state.value = _state.value.copy(
                        traceLoading = false,
                        error = e.message ?: "执行详情加载失败",
                    )
                }
            }
        }
    }

    fun closeRunTrace() {
        _state.value = _state.value.copy(traceRunId = null, trace = null, traceLoading = false)
    }

    /** 建立对话实时流；断线自动重连，并从最后收到的 seq 续传。 */
    fun startStream() {
        if (streamEnabled) return
        streamEnabled = true
        viewModelScope.launch {
            // 账号云中转只做请求-响应，SSE 长连接根本到不了手机：
            // 中转模式（Terminal 页同理）改为轮询增量事件，直连才走长连接。
            val relay = runCatching { config().isRelay }.getOrDefault(false)
            if (!streamEnabled) return@launch
            if (relay) startStreamPolling() else connectStream()
        }
    }

    /**
     * 中转模式的实时来源：按 seq 增量拉取缓冲事件，逐条走 [applyStreamEvent]。
     * 有事件时快轮询保证逐字效果，空闲时降频，避免常驻请求打满云端通道。
     */
    private fun startStreamPolling() {
        if (streamPollJob?.isActive == true) return
        streamPollJob = viewModelScope.launch {
            // 复用同一个 FluxApi：它自带连接池，250ms 快轮询不能每轮都新建客户端
            var client = runCatching { api() }.getOrNull()
            var firstPoll = true
            while (streamEnabled && isActive) {
                if (client == null) {
                    delay(STREAM_RETRY_MS)
                    client = runCatching { api() }.getOrNull()
                    continue
                }
                val fetched = try {
                    client.pollTaskEvents(taskId, afterSeq = lastStreamSeq)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 账号令牌可能刚过期：下一轮重新取配置（顺带续期）再试
                    client = null
                    delay(STREAM_RETRY_MS)
                    continue
                }
                // 缓冲区可能保留着上一轮已结束的事件：先把 seq 推进到队尾，再过滤掉旧内容
                fetched.lastOrNull()?.let { lastStreamSeq = maxOf(lastStreamSeq, it.seq) }
                val events = if (firstPoll) dropStaleStreamEvents(fetched) else fetched
                firstPoll = false
                events.forEach { applyStreamEvent(it) }
                if (!streamEnabled) break
                delay(if (fetched.isEmpty()) STREAM_POLL_IDLE_MS else STREAM_POLL_FAST_MS)
            }
        }
    }

    private fun connectStream() {
        if (!streamEnabled) return
        viewModelScope.launch {
            if (!streamEnabled) return@launch
            val generation = ++streamGeneration
            streamSource?.cancel()
            val api = runCatching { api() }.getOrNull()
            if (api == null) {
                scheduleStreamReconnect()
                return@launch
            }
            // api() 是挂起调用：期间页面可能已经离开，这里再确认一次，避免留下孤儿连接
            if (!streamEnabled) return@launch
            streamSource = runCatching {
                api.openTaskStream(
                    taskId = taskId,
                    afterSeq = lastStreamSeq,
                    onEvent = { event -> viewModelScope.launch { applyStreamEvent(event) } },
                    onFailure = {
                        viewModelScope.launch {
                            if (generation != streamGeneration) return@launch
                            streamSource = null
                            _state.value = _state.value.copy(
                                liveActive = false,
                                liveText = "",
                                liveLabel = null,
                            )
                            scheduleStreamReconnect()
                        }
                    },
                    onClosed = {
                        viewModelScope.launch {
                            if (generation != streamGeneration) return@launch
                            streamSource = null
                            scheduleStreamReconnect()
                        }
                    },
                )
            }.onFailure { scheduleStreamReconnect() }.getOrNull()
        }
    }

    /** 离开对话页时断开实时流，避免常驻连接占着心跳；再次进入从上次 seq 续传。 */
    fun stopStream() {
        streamEnabled = false
        // 让已经在路上的回调失效，不会把旧连接的事件再写回状态
        streamGeneration++
        streamRetryJob?.cancel()
        streamRetryJob = null
        streamPollJob?.cancel()
        streamPollJob = null
        streamSource?.cancel()
        streamSource = null
        _state.value = _state.value.copy(
            liveActive = false,
            liveText = "",
            liveLabel = null,
            runItems = _state.value.runItems.map { it.copy(live = false) },
        )
    }

    private fun scheduleStreamReconnect() {
        if (!streamEnabled || streamRetryJob?.isActive == true) return
        streamRetryJob = viewModelScope.launch {
            delay(STREAM_RETRY_MS)
            connectStream()
        }
    }

    private fun applyStreamEvent(event: TaskStreamEvent) {
        if (event.seq > lastStreamSeq) lastStreamSeq = event.seq
        val current = _state.value
        when (event.type) {
            "assistant_start" -> _state.value = current.copy(
                liveActive = true,
                liveText = "",
                liveLabel = "正在生成回复…",
            )

            "assistant_delta" -> {
                val text = streamText(event)
                _state.value = current.copy(
                    liveActive = true,
                    liveText = (current.liveText + text).takeLast(LIVE_TEXT_LIMIT),
                    liveLabel = if (text.isEmpty()) current.liveLabel else null,
                )
            }

            "assistant_error" -> _state.value = current.copy(
                liveActive = false,
                liveText = "",
                liveLabel = null,
            )

            "assistant_message" -> {
                _state.value = current.copy(liveActive = false, liveText = "", liveLabel = null)
                refresh(quiet = true)
            }

            "run_started" -> {
                // 新一轮 Run：清空上一轮轨迹，聊天里不再落「已开始执行」气泡
                _state.value = current.copy(
                    liveActive = true,
                    liveText = "",
                    liveLabel = "正在思考…",
                    runItems = emptyList(),
                )
                refresh(quiet = true)
            }

            "run_event" -> _state.value = applyRunEvent(current, event)

            "run_status", "run_finished" -> {
                _state.value = current.copy(
                    liveActive = false,
                    liveText = "",
                    liveLabel = null,
                    // 轨迹保留在聊天里：Run 结束后还能回看刚才执行了什么
                    runItems = current.runItems.map { it.copy(live = false) },
                )
                refresh(quiet = true)
            }

            // 多端同步：别的设备发起的 Agent 切换，在本端也要看到排队/生效/失败
            "agent_switch_queued" -> {
                val name = streamString(event, "to_agent")
                if (name.isNotBlank()) {
                    _state.value = current.copy(notice = "已排队：本轮结束后切换到「$name」")
                }
                refresh(quiet = true)
            }

            "agent_switched" -> {
                val name = streamString(event, "to_agent")
                _state.value = current.copy(
                    notice = if (name.isBlank()) {
                        "Agent 已切换，后续执行交给新 Agent"
                    } else {
                        "已切换到「$name」，新 Agent 已接手"
                    },
                )
                // 交接卡已落库：强制重拉一次，别让进行中的旧刷新把卡片漏掉
                refresh()
            }

            "agent_switch_failed" -> {
                val reason = streamString(event, "reason")
                _state.value = current.copy(
                    notice = if (reason.isBlank()) {
                        "排队的 Agent 切换未生效，仍由原 Agent 执行"
                    } else {
                        "Agent 切换未生效：$reason"
                    },
                )
                refresh(quiet = true)
            }
        }
    }

    /** 运行事件 → 执行轨迹：思考刻度、命令卡、错误行；正文增量仍进答复气泡。 */
    private fun applyRunEvent(current: TaskDetailUiState, event: TaskStreamEvent): TaskDetailUiState {
        val kind = streamString(event, "kind")
        val text = streamText(event)
        when (kind) {
            "thinking" -> {
                val items = current.runItems.toMutableList()
                if (items.isNotEmpty()) items[items.lastIndex] = items.last().copy(live = false)
                if (text.isBlank()) {
                    val phase = streamString(event, "phase").ifBlank { "step_start" }
                    val last = items.lastOrNull()
                    if (last != null && last.kind == "thinking" && last.text.isBlank()) {
                        items[items.lastIndex] = last.copy(phase = phase, live = true)
                    } else {
                        items += V2RunItem(
                            id = "think-${event.seq}",
                            kind = "thinking",
                            phase = phase,
                            live = true,
                        )
                    }
                } else {
                    // server 模式按增量推思考：同一段推理要接着上一张思考卡长，
                    // 不能每个片段另起一行（否则时间线会被几百条碎思考刷屏）
                    val last = items.lastOrNull()
                    if (last != null && last.kind == "thinking" && last.phase == "reasoning") {
                        items[items.lastIndex] = last.copy(text = last.text + text, live = true)
                    } else if (last != null && last.kind == "thinking" && last.text.isBlank()) {
                        items[items.lastIndex] = last.copy(text = text, phase = "reasoning", live = true)
                    } else {
                        items += V2RunItem(
                            id = "think-${event.seq}",
                            kind = "thinking",
                            text = text,
                            phase = "reasoning",
                            live = true,
                        )
                    }
                }
                return current.copy(
                    runItems = items,
                    liveActive = true,
                    liveLabel = if (current.liveText.isEmpty()) "正在思考…" else null,
                )
            }

            "tool" -> {
                val items = current.runItems.toMutableList()
                if (items.isNotEmpty()) items[items.lastIndex] = items.last().copy(live = false)
                val callId = streamString(event, "call_id")
                val item = V2RunItem(
                    id = callId.ifBlank { "tool-${event.seq}" },
                    kind = "tool",
                    name = streamString(event, "name").ifBlank { "tool" },
                    detail = streamString(event, "detail"),
                    output = streamString(event, "output"),
                    status = streamString(event, "status"),
                    category = streamString(event, "category"),
                    paths = streamStrings(event, "paths"),
                    live = true,
                )
                // codex 的同一 item 会先 started 再 completed：按调用 id 合并成一张卡；
                // 收尾事件可能只带输出与状态（DSH），不能整条覆盖掉已有的命令与分类
                val index = items.indexOfFirst { it.kind == "tool" && it.id == item.id }
                if (index >= 0) {
                    val previous = items[index]
                    items[index] = previous.copy(
                        name = item.name.takeIf { it.isNotBlank() && it != "tool" } ?: previous.name,
                        detail = item.detail.ifBlank { previous.detail },
                        output = item.output.ifBlank { previous.output },
                        status = item.status.ifBlank { previous.status },
                        category = item.category.ifBlank { previous.category },
                        paths = if (item.paths.isNotEmpty()) item.paths else previous.paths,
                        live = true,
                    )
                } else {
                    items += item
                }
                return current.copy(
                    runItems = items,
                    liveActive = true,
                    liveLabel = if (current.liveText.isEmpty()) "正在执行命令…" else null,
                )
            }

            "error" -> {
                val items = current.runItems.map { it.copy(live = false) } +
                    V2RunItem(
                        id = "error-${event.seq}",
                        kind = "error",
                        text = text.ifBlank { "执行出错" },
                        live = true,
                    )
                return current.copy(
                    runItems = items,
                    liveActive = true,
                    liveLabel = "执行出错",
                )
            }

            "text" -> {
                if (text.isEmpty()) return current.copy(liveActive = true)
                return current.copy(
                    liveActive = true,
                    liveText = (current.liveText + text).takeLast(LIVE_TEXT_LIMIT),
                    liveLabel = null,
                )
            }
        }
        return current
    }

    private fun streamText(event: TaskStreamEvent): String = streamString(event, "text")

    private fun streamString(event: TaskStreamEvent, key: String): String =
        runCatching { event.data[key]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .getOrDefault("")

    /** 取字符串数组（工具事件的 paths）：解析失败按空数组处理。 */
    private fun streamStrings(event: TaskStreamEvent, key: String): List<String> =
        runCatching {
            (event.data[key] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())

    fun send() {
        val current = _state.value
        val content = current.draft.trim()
        val attachmentIds = current.pendingAttachments.map { it.id }
        if (current.sending || current.busyAction != null || current.uploading) return
        if (content.isEmpty() && attachmentIds.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                sending = true,
                error = null,
                draft = "",
                // 新的一轮：上一轮的执行轨迹不再堆在输入框上方
                runItems = emptyList(),
            )
            try {
                val api = api()
                // 已结束的对话：新消息走续跑，重开任务并起新一轮 Agent Run；
                // 进行中的对话：仍走追加消息由助手回话。
                val outcome = if (current.task?.isFinished == true) {
                    api.continueTask(taskId, content, attachmentIds)
                } else {
                    api.sendMessage(taskId, content, attachmentIds)
                }
                _state.value = _state.value.copy(
                    sending = false,
                    task = outcome.task,
                    messages = (_state.value.messages + outcome.messages).distinctBy { it.id },
                    // 发送成功：清空附件；失败时保留，用户可以直接重试
                    pendingAttachments = emptyList(),
                )
                // 拉一次全量历史：既补齐上下文，也避免终态/续跑后的状态漂移。
                refresh()
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(sending = false, draft = content, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(sending = false, draft = content, error = e.message ?: "发送失败")
            }
        }
    }

    fun start() = runAction("start") { api ->
        val outcome = api.startTask(taskId)
        _state.value = _state.value.copy(
            task = outcome.task,
            runItems = emptyList(),
            liveActive = true,
            liveText = "",
            liveLabel = "正在思考…",
            // 不再弹「已开始执行」提示：过程看执行轨迹，结果看下面的答复与变更列表
            notice = null,
        )
    }

    fun cancel() = runAction("cancel") { api ->
        val task = api.cancelTask(taskId)
        _state.value = _state.value.copy(task = task, notice = "任务已取消")
    }

    fun interruptRun() = runAction("interrupt") { api ->
        val runId = _state.value.task?.runId ?: return@runAction
        val run = api.interruptRun(runId)
        _state.value = _state.value.copy(run = run, notice = "已请求停止 Agent：${run.status}")
    }

    /** 对话页切换执行 Agent：历史消息保留；执行中只排队，本轮结束后自动生效。 */
    fun switchAgent(agentId: String) = runAction("switch-agent") { api ->
        val task = api.setTaskAgent(taskId, agentId)
        val queued = task.pendingAgentId == agentId
        _state.value = _state.value.copy(
            task = task,
            notice = if (queued) {
                "已排队：本轮结束后自动切换 Agent，当前这一轮仍由原 Agent 跑完"
            } else {
                "已切换 Agent，后续执行交给新 Agent"
            },
        )
        // 立即生效会落一张交接卡：刷新一次把它带进对话
        if (!queued) refresh(quiet = true)
    }

    /** 对话页切换模型：必须是当前 Agent 支持的模型，后续 Run 用它执行；keyId 非空表示来自密钥库。 */
    fun switchModel(model: String, keyId: String? = null) = runAction("switch-model") { api ->
        val task = api.setTaskModel(taskId, model, keyId)
        _state.value = _state.value.copy(task = task, notice = "已切换模型：$model")
    }

    /** 切换对话权限模式：只影响后续消息，正在跑的一轮按原模式跑完。 */
    fun setPermissionMode(mode: String) = runAction("permission-mode") { api ->
        val task = api.setTaskPermissionMode(taskId, mode)
        val label = when (mode) {
            "plan" -> "Plan（只读 + 计划文档）"
            "full" -> "完全访问（跳过逐步审批）"
            "unattended" -> "免审核（无人值守，全部自动放行）"
            else -> "正常模式（高风险命令才问）"
        }
        _state.value = _state.value.copy(task = task, notice = "已切换为$label，下一轮生效")
    }

    fun chooseDecision(decisionId: String, option: String?, reject: Boolean) = runAction("decision") { api ->
        val task = api.chooseDecision(
            taskId = taskId,
            decisionId = decisionId,
            action = if (reject) "reject" else "choose",
            option = option,
            note = null,
        )
        _state.value = _state.value.copy(task = task, notice = if (reject) "已拒绝全部候选方案" else "已提交你的选择")
    }

    fun acceptChange(changeId: String) = changeAction(changeId, "accept") { api -> api.acceptChanges(listOf(changeId)) }
    fun applyChange(changeId: String) = changeAction(changeId, "apply") { api -> api.applyChanges(listOf(changeId)) }
    fun rejectChange(changeId: String) = changeAction(changeId, "reject") { api -> api.rejectChanges(listOf(changeId), reason = null) }
    fun rollbackChange(changeId: String) = changeAction(changeId, "rollback") { api -> listOf(api.rollbackChange(changeId)) }

    /** 变更列表页的一次性批准：只处理当前所有待审核提案。 */
    fun acceptAllChanges() = bulkChangeAction("accept") { api, ids -> api.acceptChanges(ids) }

    /** 变更列表页的一次性拒绝：只处理当前所有待审核提案。 */
    fun rejectAllChanges() = bulkChangeAction("reject") { api, ids -> api.rejectChanges(ids, reason = null) }

    private fun bulkChangeAction(action: String, block: suspend (FluxApi, List<String>) -> List<Change>) {
        val ids = _state.value.changes.filter { it.status == "pending" }.map { it.id }
        if (ids.isEmpty() || _state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "$action:all", error = null, notice = null)
            try {
                block(api(), ids)
                val fresh = api().listChanges(taskId = taskId)
                _state.value = _state.value.copy(
                    busyAction = null,
                    changes = fresh,
                    notice = if (action == "accept") {
                        "已批准 ${ids.size} 条提案（还需逐条应用才会落盘）"
                    } else {
                        "已拒绝 ${ids.size} 条提案"
                    },
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = e.message ?: "操作失败")
            }
        }
    }

    private fun changeAction(changeId: String, action: String, block: suspend (FluxApi) -> List<Change>) {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "$action:$changeId", error = null, notice = null)
            try {
                block(api())
                val fresh = api().listChanges(taskId = taskId)
                _state.value = _state.value.copy(
                    busyAction = null,
                    changes = fresh,
                    notice = noticeFor(action, changeId, fresh),
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = e.message ?: "操作失败")
            }
        }
    }

    private fun noticeFor(action: String, changeId: String, fresh: List<Change>): String {
        val change = fresh.firstOrNull { it.id == changeId }
        return when (action) {
            "accept" -> "已批准：${change?.filePath ?: changeId}（还需「应用」才会落到磁盘）"
            "apply" -> if (change?.applyError != null) "落盘失败：${change.applyError}" else "已落盘：${change?.filePath ?: changeId}"
            "reject" -> "已拒绝：${change?.filePath ?: changeId}"
            else -> "已回滚：${change?.filePath ?: changeId}"
        }
    }

    private fun runAction(action: String, block: suspend (FluxApi) -> Unit) {
        if (_state.value.busyAction != null || _state.value.sending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = action, error = null, notice = null)
            try {
                block(api())
                _state.value = _state.value.copy(busyAction = null)
                refresh(quiet = true)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = e.message ?: "操作失败")
            }
        }
    }

    override fun onCleared() {
        streamEnabled = false
        streamRetryJob?.cancel()
        streamRetryJob = null
        streamPollJob?.cancel()
        streamPollJob = null
        streamSource?.cancel()
        streamSource = null
        stopPolling()
        refreshJob?.cancel()
        refreshJob = null
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val STREAM_RETRY_MS = 3000L
        /** 中转模式轮询间隔：有增量时快回合保证逐字效果，空闲时降频省请求。 */
        private const val STREAM_POLL_FAST_MS = 250L
        private const val STREAM_POLL_IDLE_MS = 1500L
        /** 流式气泡只保留最近一段：长任务的事件正文不无限堆积。 */
        private const val LIVE_TEXT_LIMIT = 3000
    }
}