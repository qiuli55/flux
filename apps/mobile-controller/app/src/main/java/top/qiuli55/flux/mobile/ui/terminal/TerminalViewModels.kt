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

    init { refresh() }

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

enum class LineKind { ECHO, OUTPUT, SYSTEM, EXIT }
data class TranscriptLine(val seq: Int, val text: String, val kind: LineKind)

data class TerminalUiState(
    val loading: Boolean = true,
    val session: TerminalSession? = null,
    val lines: List<TranscriptLine> = emptyList(),
    val command: String = "",
    val running: Boolean = false,
    val streamState: String = "connecting",
    val streamError: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

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

    init { load() }

    fun onCommandChange(value: String) { _state.value = _state.value.copy(command = value) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    private fun load() {
        viewModelScope.launch {
            try {
                val api = api()
                val session = api.getTerminalSession(sessionId)
                api.listTerminalEvents(sessionId, afterSeq = 0).forEach(::ingest)
                _state.value = _state.value.copy(loading = false, session = session, streamState = "connecting")
                openStream(lastSeenSeq())
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message, streamState = "error")
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败", streamState = "error")
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
                    api.listTerminalEvents(sessionId, afterSeq = maxOf(0, lastSeenSeq() - REPLAY_WINDOW)).forEach(::ingest)
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
                    // EventSource 已经交给回调生命周期管理；允许失败回调创建下一轮重连 Job。
                    reconnectJob = null
                    return@launch
                } catch (e: Exception) {
                    _state.value = _state.value.copy(streamState = "error", streamError = e.message ?: "重连失败")
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            reconnectJob = null
        }
    }

    private fun isSessionActive(): Boolean = _state.value.session?.status == "active"

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
                    push(event.seq, "[exit ${event.exitCode?.toString() ?: "?"}]", LineKind.EXIT)
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
        while (seenSeqs.size > MAX_SEEN_SEQS) seenSeqs.remove(seenSeqs.first())
        return true
    }

    private fun lastSeenSeq(): Int = synchronized(eventLock) { seenSeqs.maxOrNull() ?: 0 }

    private fun appendOutput(seq: Int, chunk: String) {
        if (chunk.isEmpty()) return
        if (partialText.isEmpty()) partialSeq = seq
        chunk.split("\n").forEachIndexed { index, part ->
            if (index == 0) partialText += part
            else {
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
        while (lines.size > MAX_LINES) lines.removeAt(0)
        _state.value = _state.value.copy(lines = lines.toList())
    }

    private fun sourceTag(source: String): String = if (source == "ai") "AI" else "USER"

    fun runCommand() {
        val command = _state.value.command.trim()
        if (command.isEmpty() || _state.value.running || !isSessionActive()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, error = null, command = "")
            try {
                val finished = api().runTerminalCommand(sessionId, command)
                api().listTerminalEvents(sessionId, afterSeq = maxOf(0, finished.seq - REPLAY_WINDOW)).forEach(::ingest)
                ingest(finished)
                _state.value = _state.value.copy(running = false)
                refreshSession()
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(running = false, command = command, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, command = command, error = e.message ?: "执行失败")
            }
        }
    }

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
                _state.value = _state.value.copy(session = session, notice = "会话状态：${session.status}", running = false)
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
        viewModelScope.launch { runCatching { api().getTerminalSession(sessionId) }.onSuccess { _state.value = _state.value.copy(session = it) } }
    }

    override fun onCleared() {
        suppressReconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        streamSource?.cancel()
        streamSource = null
    }

    companion object {
        private const val MAX_LINES = 3000
        private const val MAX_SEEN_SEQS = 4096
        private const val REPLAY_WINDOW = 200
        private const val MAX_BACKOFF_MS = 15_000L
    }
}