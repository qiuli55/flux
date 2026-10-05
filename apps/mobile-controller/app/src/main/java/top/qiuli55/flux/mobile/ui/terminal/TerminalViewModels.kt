package top.qiuli55.flux.mobile.ui.terminal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.TerminalEvent
import top.qiuli55.flux.mobile.data.TerminalSession
import top.qiuli55.flux.mobile.ui.FluxViewModel

// ---------------------------------------------------------------- 会话列表

data class TerminalListUiState(
    val loading: Boolean = true,
    val sessions: List<TerminalSession> = emptyList(),
    val creating: Boolean = false,
    val error: String? = null,
)

class TerminalListViewModel : FluxViewModel() {

    private val _state = MutableStateFlow(TerminalListUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loading = _state.value.sessions.isEmpty(), error = null)
                val sessions = api().listTerminalSessions()
                _state.value = _state.value.copy(loading = false, sessions = sessions)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** 新建会话：工作区根由服务端配置决定（客户端不能指定目录），成功后直接进会话页。 */
    fun createSession(onCreated: (String) -> Unit) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null)
            try {
                val session = api().createTerminalSession()
                _state.value = _state.value.copy(creating = false)
                refresh()
                onCreated(session.id)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(creating = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(creating = false, error = e.message ?: "创建失败")
            }
        }
    }
}

// ---------------------------------------------------------------- 单会话（实时输出）

/** 终端输出的一行：echo = 命令回显，output = 命令输出，system = 状态变化，exit = 退出码。 */
enum class LineKind { ECHO, OUTPUT, SYSTEM, EXIT }

data class TranscriptLine(val seq: Int, val text: String, val kind: LineKind)

data class TerminalUiState(
    val loading: Boolean = true,
    val session: TerminalSession? = null,
    val lines: List<TranscriptLine> = emptyList(),
    val command: String = "",
    val running: Boolean = false,
    /** connecting / live / closed / error —— 界面上的连接状态 */
    val streamState: String = "connecting",
    val streamError: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 终端会话：先补历史（`GET /events`），再从最后一个 seq 接 SSE 实时流。
 *
 * 事件不是直接丢给界面，而是先过一遍 [ingest] 拼成"转录行"：终端输出是不带行结构的
 * 字节流，一条命令的输出可能被拆成多个 chunk，也可能一个 chunk 里有多行；
 * 直接按事件渲染会把本该在同一行、或本该断行的地方弄错。这里按换行符重组，
 * 保证屏幕上看到的就是终端里真实的样子。
 *
 * 这是"看输出 + 停止"的移动视图（Stop / Force Stop）。关掉页面只断开观察，
 * 服务端命令照常跑完——这是设计（Agent Terminal Console §11）。
 */
class TerminalViewModel(private val sessionId: String) : FluxViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state = _state.asStateFlow()

    private var streamSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var suppressReconnect = false

    private val eventLock = Any()
    private val seenSeqs = LinkedHashSet<Int>()
    private val lines = mutableListOf<TranscriptLine>()
    private var partialText = ""
    private var partialSeq = 0

    init {
        load()
    }

    fun onCommandChange(value: String) {
        _state.value = _state.value.copy(command = value)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val api = api()
                val session = api.getTerminalSession(sessionId)
                val history = api.listTerminalEvents(sessionId, afterSeq = 0)
                history.forEach { ingest(it) }
                _state.value = _state.value.copy(
                    loading = false,
                    session = session,
                    streamState = "connecting",
                )
                openStream(lastSeenSeq())
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message, streamState = "error")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "加载失败",
                    streamState = "error",
                )
            }
        }
    }

    private fun openStream(afterSeq: Int) {
        viewModelScope.launch {
            try {
                val api = api()
                streamSource?.cancel()
                streamSource = api.openTerminalStream(
                    sessionId = sessionId,
                    afterSeq = afterSeq,
                    onEvent = { event ->
                        ingest(event)
                        _state.value = _state.value.copy(streamState = "live", streamError = null)
                    },
                    onFailure = { message ->
                        _state.value = _state.value.copy(streamState = "error", streamError = message)
                        scheduleReconnect()
                    },
                    onClosed = {
                        _state.value = _state.value.copy(streamState = "closed")
                        if (!suppressReconnect && isSessionActive()) scheduleReconnect()
                    },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(streamState = "error", streamError = e.message ?: "实时流连接失败")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (suppressReconnect || !isSessionActive() || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var backoffMs = 1_000L
            while (isActive && !suppressReconnect && isSessionActive()) {
                delay(backoffMs)
                _state.value = _state.value.copy(streamState = "connecting", streamError = null)
                try {
                    val api = api()
                    val replayFrom = maxOf(0, lastSeenSeq() - REPLAY_WINDOW)
                    api.listTerminalEvents(sessionId, afterSeq = replayFrom).forEach(::ingest)
                    streamSource?.cancel()
                    streamSource = api.openTerminalStream(
                        sessionId = sessionId,
                        afterSeq = lastSeenSeq(),
                        onEvent = { event ->
                            ingest(event)
                            _state.value = _state.value.copy(streamState = "live", streamError = null)
                        },
                        onFailure = { message ->
                            _state.value = _state.value.copy(streamState = "error", streamError = message)
                            scheduleReconnect()
                        },
                        onClosed = {
                            _state.value = _state.value.copy(streamState = "closed")
                            if (!suppressReconnect && isSessionActive()) scheduleReconnect()
                        },
                    )
                    return@launch
                } catch (e: Exception) {
                    _state.value = _state.value.copy(streamState = "error", streamError = e.message ?: "重连失败")
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private fun isSessionActive(): Boolean = _state.value.session?.status == "active"
    // --- 事件 → 转录行 ---

    private fun ingest(event: TerminalEvent) {
        synchronized(eventLock) {
            if (!rememberSeq(event.seq)) return
            when (event.kind) {
                "terminal.command.started" -> {
                    flushPartial()
                    push(event.seq, "[${sourceTag(event.source)}] $ ${event.command.orEmpty()}", LineKind.ECHO)
                }
                "terminal.output" -> appendOutput(event.seq, event.chunk.orEmpty())
                "terminal.command.finished", "terminal.command.failed" -> {
                    flushPartial()
                    val code = event.exitCode?.toString() ?: "?"
                    push(event.seq, "[exit $code]", LineKind.EXIT)
                }
                "terminal.stop.requested" -> push(event.seq, "· 收到停止请求", LineKind.SYSTEM)
                "terminal.process.terminated" -> push(event.seq, "· 进程已终止", LineKind.SYSTEM)
                "terminal.session.closed" -> push(event.seq, "· 会话已关闭", LineKind.SYSTEM)
                "terminal.session.created" -> push(event.seq, "· 会话已创建", LineKind.SYSTEM)
                else -> Unit
            }
        }
    }

    private fun rememberSeq(seq: Int): Boolean {
        if (!seenSeqs.add(seq)) return false
        while (seenSeqs.size > MAX_SEEN_SEQS) {
            seenSeqs.remove(seenSeqs.first())
        }
        return true
    }

    private fun lastSeenSeq(): Int {
        synchronized(eventLock) {
            return seenSeqs.maxOrNull() ?: 0
        }
    }
    /** 输出片段按 `\n` 断行：新行开新条，未结束的部分留在 [partialText] 里等后续 chunk。 */
    private fun appendOutput(seq: Int, chunk: String) {
        if (chunk.isEmpty()) return
        if (partialText.isEmpty()) partialSeq = seq
        val parts = chunk.split("\n")
        parts.forEachIndexed { index, part ->
            if (index == 0) {
                partialText += part
            } else {
                flushPartial()
                partialText = part
                partialSeq = seq
            }
        }
    }

    private fun flushPartial() {
        if (partialText.isEmpty()) return
        push(partialSeq, partialText, LineKind.OUTPUT)
        partialText = ""
    }

    private fun push(seq: Int, text: String, kind: LineKind) {
        lines += TranscriptLine(seq, text, kind)
        // 与后端单次续读上限一致：超出丢最早的，防止超长会话把内存吃满
        while (lines.size > MAX_LINES) lines.removeAt(0)
        _state.value = _state.value.copy(lines = lines.toList())
    }

    private fun sourceTag(source: String): String = if (source == "ai") "AI" else "USER"

    // --- 动作 ---

    fun runCommand() {
        val command = _state.value.command.trim()
        if (command.isEmpty() || _state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, error = null, command = "")
            try {
                val finished = api().runTerminalCommand(sessionId, command)
                val replayFrom = maxOf(0, finished.seq - REPLAY_WINDOW)
                api().listTerminalEvents(sessionId, afterSeq = replayFrom).forEach(::ingest)
                ingest(finished)
                _state.value = _state.value.copy(running = false)
                refreshSession()
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(running = false, command = command, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, command = command, error = e.message)
            }
        }
    }

    /** 停止会话：force=false 走 SIGTERM → grace → SIGKILL；force=true 直接 SIGKILL。 */
    fun stop(force: Boolean) {
        if (!isSessionActive()) return
        suppressReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        streamSource?.cancel()
        streamSource = null
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, notice = if (force) "正在强制停止…" else "正在停止…")
            try {
                val session = api().stopTerminalSession(sessionId, force)
                _state.value = _state.value.copy(
                    session = session,
                    notice = "会话状态：${session.status}",
                    running = false,
                )
            } catch (e: FluxApiException) {
                suppressReconnect = false
                _state.value = _state.value.copy(error = e.message, notice = null)
            } catch (e: Exception) {
                suppressReconnect = false
                _state.value = _state.value.copy(error = e.message ?: "停止失败", notice = null)
            }
        }
    }

    private fun refreshSession() {
        viewModelScope.launch {
            runCatching { api().getTerminalSession(sessionId) }
                .onSuccess { _state.value = _state.value.copy(session = it) }
        }
    }

    override fun onCleared() {
        suppressReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        streamSource?.cancel()
        streamSource = null
        // 只断开观察，不停命令（§11）：用户关页面 ≠ 停 Agent
    }

    companion object {
        private const val MAX_LINES = 3000
        private const val MAX_SEEN_SEQS = 4096
        private const val REPLAY_WINDOW = 200
        private const val MAX_BACKOFF_MS = 15_000L
    }
}