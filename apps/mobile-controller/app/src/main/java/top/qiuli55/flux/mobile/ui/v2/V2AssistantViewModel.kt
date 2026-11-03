package top.qiuli55.flux.mobile.ui.v2

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.SupervisorSettings
import top.qiuli55.flux.mobile.data.SupervisorTicket
import top.qiuli55.flux.mobile.data.SupervisorTimelineItem
import top.qiuli55.flux.mobile.data.SupervisorTrigger
import top.qiuli55.flux.mobile.data.SupervisorReport
import top.qiuli55.flux.mobile.ui.FluxViewModel

data class V2AssistantState(
    val loading: Boolean = false,
    val busyAction: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val settings: SupervisorSettings = SupervisorSettings(),
    val counts: Map<String, Int> = emptyMap(),
    val tickets: List<SupervisorTicket> = emptyList(),
    val observations: List<SupervisorTrigger> = emptyList(),
    val reports: List<SupervisorReport> = emptyList(),
    val timeline: List<SupervisorTimelineItem> = emptyList(),
    val pendingOutbox: Int = 0,
    val detail: SupervisorTicket? = null,
    val detailLoading: Boolean = false,
) {
    val hasData: Boolean
        get() = observations.isNotEmpty() || tickets.isNotEmpty() || timeline.isNotEmpty()
}

/** 助理（Supervisor）：观察事件 → 判定 → 拆工单 → 派发给子 Agent。 */
class V2AssistantViewModel : FluxViewModel() {

    private val _state = MutableStateFlow(V2AssistantState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    fun load(quiet: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = true, error = null)
            try {
                val api = api()
                val overview = api.supervisorOverview(50)
                val observations = runCatching { api.supervisorObservations(50) }.getOrDefault(emptyList())
                val reports = runCatching { api.supervisorReports(20) }.getOrDefault(emptyList())
                val timeline = runCatching { api.supervisorTimeline(30) }.getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    settings = overview.settings,
                    counts = overview.counts,
                    tickets = overview.tickets,
                    pendingOutbox = overview.pendingOutbox,
                    observations = observations,
                    reports = reports,
                    timeline = timeline,
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = readable(e))
            }
        }
    }

    fun saveSettings(
        acceptMode: String,
        modelProvider: String,
        modelName: String,
        maxTicketsPerThread: Int,
        maxTicketDepth: Int,
        maxConcurrentRuns: Int,
    ) = runAction("settings") {
        val updated = it.updateSupervisorSettings(
            SupervisorSettings(
                acceptMode = acceptMode,
                modelProvider = modelProvider.trim(),
                modelName = modelName.trim(),
                maxTicketsPerThread = maxTicketsPerThread,
                maxTicketDepth = maxTicketDepth,
                maxConcurrentRuns = maxConcurrentRuns,
            ),
        )
        "运行设置已保存：${acceptModeLabel(updated.acceptMode)}"
    }

    fun assess(triggerId: String) = runAction("assess:$triggerId") {
        val result = it.supervisorAssess(triggerId)
        val decision = result.assessment?.decision.orEmpty()
        "重新判定完成：${decisionLabel(decision)}"
    }

    fun plan(triggerId: String, dispatch: Boolean = false) = runAction("plan:$triggerId") {
        val result = it.supervisorPlan(triggerId, dispatch)
        val count = result.tickets.size
        if (dispatch) "已拆解 ${count} 张工单并尝试派发" else "已拆解 $count 张工单"
    }

    fun dispatchTicket(ticketId: String) = runAction("dispatch:$ticketId") {
        val outcome = it.supervisorDispatchTicket(ticketId)
        if (outcome.dispatched) "已派发" else "未派发：${outcome.reason.ifBlank { "条件未满足" }}"
    }

    fun retryTicket(ticketId: String) = runAction("retry:$ticketId") {
        val outcome = it.supervisorRetryTicket(ticketId)
        if (outcome.dispatched) "已重新派发" else "未派发：${outcome.reason.ifBlank { "条件未满足" }}"
    }

    fun stopTicket(ticketId: String) = runAction("stop:$ticketId") {
        it.supervisorStopTicket(ticketId, note = "由手机端手动停止")
        "已停止该工单"
    }

    fun processOutbox() = runAction("process") {
        val result = it.supervisorProcess(20)
        "已推进 ${result.processed} 个事件，队列剩余 ${result.pendingOutbox}"
    }

    fun runWatchdog() = runAction("watchdog") {
        val result = it.supervisorWatchdog(50)
        "检查 ${result.checked} 个工单，回收 ${result.expired} 个超时"
    }

    fun openDetail(ticketId: String) {
        _state.value = _state.value.copy(
            detail = _state.value.tickets.firstOrNull { it.id == ticketId },
            detailLoading = true,
        )
        viewModelScope.launch {
            try {
                val ticket = api().supervisorTicket(ticketId)
                if (_state.value.detail?.id == ticketId) {
                    _state.value = _state.value.copy(detail = ticket, detailLoading = false)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(detailLoading = false, error = readable(e))
            }
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null, detailLoading = false)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    private fun runAction(key: String, block: suspend (FluxApi) -> String) {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = key, error = null, notice = null)
            try {
                val note = block(api())
                _state.value = _state.value.copy(busyAction = null, notice = note)
                load(quiet = true)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }
}

internal fun acceptModeLabel(mode: String): String = when (mode) {
    "off" -> "关闭"
    "auto" -> "自动执行"
    "observe" -> "只观察"
    else -> mode.ifBlank { "只观察" }
}

internal fun decisionLabel(decision: String): String = when (decision) {
    "accept" -> "受理"
    "stop" -> "停止"
    "ignore" -> "忽略"
    "unavailable" -> "无法判定"
    else -> decision.ifBlank { "未判定" }
}

internal fun ticketStageLabel(stage: String): String = when (stage) {
    "intake" -> "待派发"
    "planned" -> "待派发"
    "running" -> "进行中"
    "waiting" -> "等待依赖"
    "reviewing" -> "待验收"
    "done" -> "已完成"
    "stopped" -> "已停止"
    else -> stage.ifBlank { "未知" }
}
