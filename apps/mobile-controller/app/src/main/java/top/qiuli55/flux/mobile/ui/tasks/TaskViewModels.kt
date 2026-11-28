package top.qiuli55.flux.mobile.ui.tasks

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import top.qiuli55.flux.mobile.ui.FluxViewModel
import top.qiuli55.flux.mobile.ui.v2.AttachmentDraftStatus
import top.qiuli55.flux.mobile.ui.v2.ComposerAttachmentController
import top.qiuli55.flux.mobile.ui.v2.ComposerAttachmentDraft
import top.qiuli55.flux.mobile.ui.v2.FLUX_STREAM_LOG_TAG
import top.qiuli55.flux.mobile.ui.v2.StreamApply
import top.qiuli55.flux.mobile.ui.v2.StreamUiSnapshot
import top.qiuli55.flux.mobile.ui.v2.TaskStreamReducer
import top.qiuli55.flux.mobile.ui.v2.attachmentSendBlocker
import top.qiuli55.flux.mobile.ui.v2.isFinished
import top.qiuli55.flux.mobile.ui.v2.readable
import top.qiuli55.flux.mobile.ui.v2.reconnectDelayMs
import top.qiuli55.flux.mobile.ui.v2.readyAttachments
import top.qiuli55.flux.mobile.ui.v2.uploadPickedAttachment

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
    //: 流式回复 + 执行过程 + 连接状态：由 TaskStreamReducer 归约后的不可变快照。
    //: UI 只消费快照；增量在 ViewModel 里按帧合并，不再每条事件都整页重组。
    val stream: StreamUiSnapshot = StreamUiSnapshot(),
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
    //: 待发送附件草稿（上传中 / 已就绪 / 失败）；发送成功才清空，失败保留便于重试
    val attachmentDrafts: List<ComposerAttachmentDraft> = emptyList(),
) {
    //: 附件上传中：禁止发送，UI 显示「上传中…」
    val uploading: Boolean get() = attachmentDrafts.any {
        it.status == AttachmentDraftStatus.UPLOADING
    }
}

/** 用户点 X 关掉会话提示：error / notice 一起清空。 */
internal fun TaskDetailUiState.withDismissedNotice(): TaskDetailUiState =
    copy(notice = null, error = null)

/**
 * 开始新一轮发送：清掉上一轮遗留的状态提示（任务已取消 / 已切换模型 / 已排队切换 Agent 等）。
 *
 * 这些提示不是发送失败；若继续留着，发送成功后 UI 可能把它们误判成本轮失败而不清空草稿。
 * 真正的发送失败由随后写入的 [TaskDetailUiState.error] 表达。
 */
internal fun TaskDetailUiState.withSendStarted(): TaskDetailUiState =
    copy(sending = true, error = null, notice = null, draft = "")

/** 发送失败：输入文字和附件草稿都保留，用户可以直接再点发送。 */
internal fun TaskDetailUiState.withSendFailed(content: String, message: String?): TaskDetailUiState =
    copy(sending = false, draft = content, error = message)

/** 发送成功：清空附件草稿；输入框由 V2ChatScreen 在 sending 结束后清空。 */
internal fun TaskDetailUiState.withSendSucceeded(
    task: Task,
    newMessages: List<TaskMessage>,
): TaskDetailUiState = copy(
    sending = false,
    task = task,
    messages = (messages + newMessages).distinctBy { it.id },
    attachmentDrafts = emptyList(),
)

/**
 * 把一次成功的刷新并入当前 UI 状态。
 *
 * 只覆盖服务端数据与错误位；[TaskDetailUiState.notice] 原样保留：用户刚用 X 关掉的
 * 会话提示（已切换模型 / 已排队切换 Agent 等），不能因为一次轮询或下拉刷新又出现在
 * 输入区上方。新的提示只由明确的用户操作写入，刷新不负责「补回」。
 */
internal fun TaskDetailUiState.withRefreshResult(
    task: Task,
    messages: List<TaskMessage>,
    changes: List<Change>,
    contextUsage: TaskContextUsage?,
    run: Run?,
    runs: List<TaskRunSummary>,
    stream: StreamUiSnapshot,
): TaskDetailUiState = copy(
    loading = false,
    error = null,
    task = task,
    messages = messages,
    changes = changes,
    contextUsage = contextUsage ?: this.contextUsage,
    run = run,
    runs = runs,
    stream = stream,
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

/**
 * 实时气泡什么时候该收尾。
 *
 * 两条判据都会单独出现：
 *  - 任务已经到终态，但终态事件丢了/晚到 → 运行条会一直亮着；
 *  - 这条正文已经被落库成正式消息 → 流式气泡与历史气泡同时在场，同一段话显示两遍。
 * 两种情况都应收起 [TaskDetailUiState.stream] 里还在进行中的阶段；时间线本身保留事件顺序，
 * 最终正文由时间线末段与落库消息合成展示（见 V2ChatScreen），不会各画一遍。
 */
internal fun shouldSettleLiveBubble(
    finished: Boolean,
    messages: List<TaskMessage>,
    liveText: String,
): Boolean {
    if (finished) return true
    val live = liveText.trim()
    if (live.isEmpty()) return false
    val lastAssistant = messages.lastOrNull {
        it.role.lowercase() in setOf("assistant", "agent", "ai")
    } ?: return false
    val persisted = lastAssistant.content.trim()
    if (persisted.isEmpty()) return false
    // 完整相等是最常见的重复；流式正文只保留尾部 3000 字，长回复退化成「落库文本以实时文本结尾」
    return persisted == live || (live.length >= 80 && persisted.endsWith(live))
}


class TaskDetailViewModel(private val taskId: String) : FluxViewModel() {

    private var refreshJob: Job? = null
    private var pollingJob: Job? = null
    private var streamSource: EventSource? = null
    private var streamRetryJob: Job? = null
    private var streamPollJob: Job? = null
    private var streamEnabled = false
    /** 上次建立流时是否走云中转：重试要沿用同一传输方式，不能把轮询切成 SSE。 */
    private var streamRelay = false
    private var streamGeneration = 0
    private var lastStreamSeq = 0
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state = _state.asStateFlow()

    /**
     * 已有对话的附件草稿：与新建对话共用同一套上传状态机。
     * 失败/上传中的草稿会阻止发送，成功后清空。
     */
    private val attachmentController = ComposerAttachmentController(
        scope = viewModelScope,
        upload = { uri, picked, resolver ->
            uploadPickedAttachment({ api() }, uri, picked, resolver)
        },
        onProblem = { message -> _state.value = _state.value.copy(error = message) },
    )

    /**
     * SSE 每条事件原来各起一个协程，落到 [applyStreamEvent] 的顺序无法保证：
     * assistant_delta 乱序会让正文错位，终态事件抢在前面又会让后到的 delta 把气泡重新点亮。
     * 统一进队列，由单个协程按到达顺序消费。
     */
    private val streamEvents = Channel<TaskStreamEvent>(Channel.UNLIMITED)

    /** 纯归约器：增量攒批、seq 去重、思考/工具状态、连接状态都在这里。 */
    private val streamReducer = TaskStreamReducer()
    private var streamDirty = false
    private var cancelledByUser = false

    init {
        refresh()
        startPolling()
        // 草稿状态只由 controller 维护，ViewModel 只做镜像，避免两套状态漂移。
        viewModelScope.launch {
            attachmentController.drafts.collect { drafts ->
                _state.value = _state.value.copy(attachmentDrafts = drafts)
            }
        }
        viewModelScope.launch {
            for (event in streamEvents) applyStreamEvent(event)
        }
        // 约 30fps 把缓冲的增量合并进 UI：token 再快也不会一条一次重组 / 滚动。
        viewModelScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(STREAM_BATCH_MS)
                if (streamDirty) {
                    streamDirty = false
                    val snapshot = streamReducer.flush()
                    applyStreamSnapshot(snapshot)
                    Log.d(
                        FLUX_STREAM_LOG_TAG,
                        "flush liveChars=${snapshot.liveText.length} " +
                            "reasoningChars=${snapshot.reasoning?.text?.length ?: 0} " +
                            "revision=${snapshot.revision}",
                    )
                }
            }
        }
    }

    /** 把归约快照写进 UI 状态；只有这里会触发对话页重组。 */
    private fun applyStreamSnapshot(snapshot: StreamUiSnapshot) {
        _state.value = _state.value.copy(stream = snapshot)
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
        _state.value = _state.value.withDismissedNotice()
    }

    /**
     * 选中文件后交给共用控制器：上限 5 个、50MB、空文件、重复 Uri 的规则只实现一处。
     * 上传中/失败禁止发送由 [send] 与 UI 共同兜住。
     */
    fun addAttachments(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        _state.value = _state.value.copy(error = null)
        attachmentController.add(uris, resolver)
    }

    /** 大文本粘贴转成的文本附件：与图片 / 文件共用同一条上传通道。 */
    fun attachPastedText(name: String, text: String) {
        attachmentController.addText(name, text) {
            api().uploadAttachment(
                name = name,
                mime = "text/plain",
                size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            ) { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** 设计稿当附件：先导出 SVG，再走同一条上传通道，不落地临时文件。 */
    fun attachDesign(designId: String, name: String) {
        attachmentController.addDesign(name) {
            val svg = api().exportDesignSvg(designId)
            api().uploadAttachment(
                name = "${name.ifBlank { "design" }}.svg",
                mime = "image/svg+xml",
                size = svg.size.toLong(),
            ) { ByteArrayInputStream(svg) }
        }
    }

    /** 按草稿 localId 移除；失败项也占名额，需要能单独删掉。 */
    fun removeAttachment(localId: String) {
        attachmentController.remove(localId)
    }

    /** 失败草稿重试：沿用同一份本地文件重新上传。 */
    fun retryAttachment(localId: String) {
        attachmentController.retry(localId)
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
                val messages = messagesDeferred.await()
                // 终态事件可能丢失或晚于这次刷新：只按事件收尾会留下常亮的运行条，
                // 以及「流式气泡 + 落库消息」两份同样的回复。这里以接口状态为准收尾。
                val snapshot = _state.value.stream
                val settleLive = shouldSettleLiveBubble(finished, messages, snapshot.liveText)
                // liveActive 已经为 false（例如 assistant_error / 手动停止后）但阶段里还留着
                // 部分正文：任务落定后同样要收尾，否则流式气泡会和落库消息同时挂着。
                val stream = if (settleLive && (snapshot.liveActive || snapshot.stages.isNotEmpty())) {
                    Log.i(FLUX_STREAM_LOG_TAG, "refresh settles live bubble finished=$finished")
                    streamReducer.settleFinished()
                } else {
                    snapshot
                }
                _state.value = _state.value.withRefreshResult(
                    task = task,
                    messages = messages,
                    changes = changesDeferred.await(),
                    contextUsage = contextDeferred.await(),
                    run = runDeferred.await(),
                    runs = runsDeferred.await(),
                    // 时间线按事件顺序保留思考 / 中途正文 / 命令组 / 最终正文；
                    // 最终正文由落库消息接在时间线末尾，不再整篇顶在前面重复一遍。
                    stream = stream,
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
            streamRelay = relay
            Log.i(FLUX_STREAM_LOG_TAG, "start stream relay=$relay afterSeq=$lastStreamSeq")
            applyStreamSnapshot(streamReducer.onConnecting())
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
            var failures = 0
            var announcedLive = false
            while (streamEnabled && isActive) {
                if (client == null) {
                    delay(reconnectDelayMs(failures))
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
                    failures++
                    announcedLive = false
                    val snapshot = streamReducer.onDisconnected()
                    applyStreamSnapshot(snapshot)
                    Log.w(
                        FLUX_STREAM_LOG_TAG,
                        "relay poll failed attempt=${snapshot.reconnectAttempt}: ${e.message}",
                    )
                    delay(reconnectDelayMs(failures))
                    continue
                }
                failures = 0
                if (!announcedLive) {
                    announcedLive = true
                    applyStreamSnapshot(streamReducer.onOpen())
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
                handleStreamDrop(generation, "无法获取 API 配置")
                return@launch
            }
            // api() 是挂起调用：期间页面可能已经离开，这里再确认一次，避免留下孤儿连接
            if (!streamEnabled) return@launch
            streamSource = runCatching {
                api.openTaskStream(
                    taskId = taskId,
                    afterSeq = lastStreamSeq,
                    onOpen = {
                        viewModelScope.launch {
                            if (generation != streamGeneration) return@launch
                            applyStreamSnapshot(streamReducer.onOpen())
                            Log.i(FLUX_STREAM_LOG_TAG, "stream connected afterSeq=$lastStreamSeq")
                        }
                    },
                    onEvent = { event -> streamEvents.trySend(event) },
                    onFailure = { reason ->
                        viewModelScope.launch {
                            if (generation != streamGeneration) return@launch
                            streamSource = null
                            handleStreamDrop(generation, reason)
                        }
                    },
                    onClosed = {
                        viewModelScope.launch {
                            if (generation != streamGeneration) return@launch
                            streamSource = null
                            handleStreamDrop(generation, "连接已关闭")
                        }
                    },
                )
            }.onFailure { handleStreamDrop(generation, it.message ?: "连接失败") }.getOrNull()
        }
    }

    /**
     * 断线处理：保留已渲染的部分正文，按指数退避重连；
     * 连续失败超过上限才转成终态错误（UI 给「重试」），避免无限静默重试。
     */
    private fun handleStreamDrop(generation: Int, reason: String) {
        if (!streamEnabled || generation != streamGeneration) return
        if (streamRetryJob?.isActive == true) return
        val snapshot = streamReducer.onDisconnected()
        applyStreamSnapshot(snapshot)
        Log.w(
            FLUX_STREAM_LOG_TAG,
            "stream dropped attempt=${snapshot.reconnectAttempt} reason=$reason " +
                "partialChars=${snapshot.liveText.length}",
        )
        if (snapshot.reconnectAttempt > MAX_STREAM_ATTEMPTS) {
            Log.e(FLUX_STREAM_LOG_TAG, "stream gave up after ${snapshot.reconnectAttempt} attempts")
            // 标记为未启用：用户点「重试」或发下一条消息时还能重新建流，不会被 startStream 的幂等直接挡掉
            streamEnabled = false
            applyStreamSnapshot(streamReducer.onTerminal("实时连接失败，请重试"))
            return
        }
        scheduleStreamReconnect(snapshot.reconnectAttempt)
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
        applyStreamSnapshot(streamReducer.stopStream())
        Log.i(FLUX_STREAM_LOG_TAG, "stream stopped lastSeq=$lastStreamSeq")
    }

    private fun scheduleStreamReconnect(attempt: Int) {
        if (!streamEnabled || streamRetryJob?.isActive == true) return
        val delayMs = reconnectDelayMs(attempt)
        Log.i(FLUX_STREAM_LOG_TAG, "reconnect in ${delayMs}ms attempt=$attempt")
        streamRetryJob = viewModelScope.launch {
            delay(delayMs)
            connectStream()
        }
    }

    /**
     * 手动重试：清掉错误、重置退避，立刻重连并重新拉一次消息。
     * 用于连接失败 / assistant_error 后的「重试」按钮。
     */
    fun retryStream() {
        Log.i(FLUX_STREAM_LOG_TAG, "manual stream retry enabled=$streamEnabled")
        streamRetryJob?.cancel()
        streamRetryJob = null
        streamGeneration++
        streamSource?.cancel()
        streamSource = null
        streamPollJob?.cancel()
        streamPollJob = null
        applyStreamSnapshot(streamReducer.retry())
        if (!streamEnabled) {
            streamEnabled = true
            viewModelScope.launch {
                val relay = runCatching { config().isRelay }.getOrDefault(false)
                streamRelay = relay
                if (relay) startStreamPolling() else connectStream()
            }
        } else if (streamRelay) {
            startStreamPolling()
        } else {
            connectStream()
        }
        refresh(quiet = true)
    }

    /**
     * 单条事件入口：增量交给归约器缓冲，结构事件立刻出快照并处理副作用。
     * 归约器按 seq 去重，重连时不会把已经渲染过的正文再追加一遍。
     */
    private fun applyStreamEvent(event: TaskStreamEvent) {
        if (event.seq > lastStreamSeq) lastStreamSeq = event.seq
        when (val outcome = streamReducer.apply(event)) {
            StreamApply.BUFFERED -> {
                streamDirty = true
            }

            StreamApply.DUPLICATE -> {
                Log.w(
                    FLUX_STREAM_LOG_TAG,
                    "duplicate stream event seq=${event.seq} type=${event.type} ignored",
                )
            }

            StreamApply.IGNORED -> Unit

            StreamApply.COMMITTED -> {
                streamDirty = false
                applyStreamSnapshot(streamReducer.snapshot())
                handleStreamSideEffect(event)
            }
        }
    }

    /** 与渲染无关的副作用：刷新历史、跨端 Agent 切换提示。 */
    private fun handleStreamSideEffect(event: TaskStreamEvent) {
        val current = _state.value
        when (event.type) {
            "assistant_message", "run_started", "run_status", "run_finished" -> refresh(quiet = true)
            "assistant_error" -> {
                val reason = streamString(event, "error").ifBlank { "回复中断" }
                Log.w(
                    FLUX_STREAM_LOG_TAG,
                    "assistant error keptChars=${current.stream.liveText.length} reason=$reason",
                )
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

    private fun streamString(event: TaskStreamEvent, key: String): String =
        runCatching { event.data[key]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .getOrDefault("")

    fun send(
        specifiedSkillIds: List<String> = emptyList(),
        specifiedConnectorIds: List<String> = emptyList(),
    ) {
        val current = _state.value
        val content = current.draft.trim()
        if (current.sending || current.busyAction != null) return
        val drafts = attachmentController.drafts.value
        // 有失败 / 上传中的草稿时不发：避免只把成功的发出去、留下失败项造成错觉。
        val blocker = attachmentSendBlocker(drafts)
        if (blocker != null) {
            _state.value = current.copy(error = blocker)
            return
        }
        val attachmentIds = readyAttachments(drafts).map { it.id }
        if (content.isEmpty() && attachmentIds.isEmpty()) return
        viewModelScope.launch {
            // 新的一轮：上一轮的思考/命令过程清掉（连接与 seq 水位保留）。
            cancelledByUser = false
            applyStreamSnapshot(streamReducer.resetActivity())
            _state.value = _state.value.withSendStarted()
            try {
                val api = api()
                // 已结束的对话：新消息走续跑，重开任务并起新一轮 Agent Run；
                // 进行中的对话：仍走追加消息由助手回话。
                val outcome = if (current.task?.isFinished == true) {
                    api.continueTask(
                        taskId,
                        content,
                        attachmentIds,
                        specifiedSkillIds = specifiedSkillIds,
                        specifiedConnectorIds = specifiedConnectorIds,
                    )
                } else {
                    api.sendMessage(
                        taskId,
                        content,
                        attachmentIds,
                        specifiedSkillIds = specifiedSkillIds,
                        specifiedConnectorIds = specifiedConnectorIds,
                    )
                }
                // 发送成功：清空附件；失败时保留，用户可以直接重试
                attachmentController.clear()
                _state.value = _state.value.withSendSucceeded(outcome.task, outcome.messages)
                // 拉一次全量历史：既补齐上下文，也避免终态/续跑后的状态漂移。
                refresh()
                // 上次连接重试耗尽后流是停的：新一轮消息发出时顺手把实时流重新建起来
                if (!streamEnabled) startStream()
            } catch (e: FluxApiException) {
                _state.value = _state.value.withSendFailed(content, e.message)
            } catch (e: Exception) {
                _state.value = _state.value.withSendFailed(content, e.message ?: "发送失败")
            }
        }
    }

    fun start() = runAction("start") { api ->
        val outcome = api.startTask(taskId)
        cancelledByUser = false
        applyStreamSnapshot(streamReducer.startRun())
        _state.value = _state.value.copy(
            task = outcome.task,
            // 不再弹「已开始执行」提示：过程看执行轨迹，结果看下面的答复与变更列表
            notice = null,
        )
        // 上一轮把重连次数用尽后流是停的：手动开始时一并恢复实时流
        if (!streamEnabled) startStream()
    }

    /**
     * 停止本轮：按钮与 UI 都做了防抖，这里再用同步状态抢占一次，
     * 保证连点两下只会发出一个取消请求。已生成的部分正文保留。
     */
    fun cancel() {
        if (cancelledByUser || _state.value.busyAction == "cancel") {
            Log.i(FLUX_STREAM_LOG_TAG, "cancel ignored: already in flight")
            return
        }
        cancelledByUser = true
        // 先同步占位再起协程：两次点击落在同一帧时第二次也进不来
        _state.value = _state.value.copy(busyAction = "cancel", error = null, notice = null)
        viewModelScope.launch {
            applyStreamSnapshot(streamReducer.markCancelled())
            Log.i(
                FLUX_STREAM_LOG_TAG,
                "cancel requested keptChars=${_state.value.stream.liveText.length}",
            )
            try {
                val task = api().cancelTask(taskId)
                _state.value = _state.value.copy(task = task, notice = "任务已取消")
                refresh(quiet = true)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "取消失败")
            } finally {
                _state.value = _state.value.copy(busyAction = null)
            }
        }
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

    /**
     * 安全输入卡片：把密钥一次性交给后端密钥库。
     *
     * 明文不进消息表也不回显，成功后刷新让卡片切成「已安全存储」。
     */
    fun submitSecureInput(messageId: String, value: String) {
        if (_state.value.busyAction != null || _state.value.sending) return
        if (value.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "secure-input", error = null, notice = null)
            try {
                val outcome = api().submitSecureInput(taskId, messageId, value)
                _state.value = _state.value.copy(
                    busyAction = null,
                    task = outcome.task,
                    messages = (_state.value.messages + outcome.message).distinctBy { it.id },
                    notice = "已安全存储，密钥不会出现在对话里",
                )
                refresh(quiet = true)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = e.message ?: "保存失败")
            }
        }
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
        /** 增量合并窗口：约 30fps，token 再快也只重组一次/帧。 */
        private const val STREAM_BATCH_MS = 33L
        /** 连续重连失败上限：超过后转终态错误，由用户点「重试」。 */
        private const val MAX_STREAM_ATTEMPTS = 6
        /** 中转模式轮询间隔：有增量时快回合保证逐字效果，空闲时降频省请求。 */
        private const val STREAM_POLL_FAST_MS = 250L
        private const val STREAM_POLL_IDLE_MS = 1500L
    }
}