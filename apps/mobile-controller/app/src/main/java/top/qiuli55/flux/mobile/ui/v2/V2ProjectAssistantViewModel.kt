package top.qiuli55.flux.mobile.ui.v2

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.AssistantHistory
import top.qiuli55.flux.mobile.data.AssistantHistoryDetail
import top.qiuli55.flux.mobile.data.AssistantProgress
import top.qiuli55.flux.mobile.ui.FluxViewModel

data class V2ProjectAssistantState(
    val loading: Boolean = false,
    val busyAction: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val assistantName: String = "",
    val progress: AssistantProgress? = null,
    val history: AssistantHistory? = null,
    /** 读历史打开的只读对话；null 表示没打开。 */
    val historyDetail: AssistantHistoryDetail? = null,
)

/**
 * 项目助理进度面板：读历史 / 接着干 / 开副本。
 *
 * 进度汇总只读服务端已有记录，不启动电脑 Agent、不派子任务。
 * 接着干与开副本都必须由界面先取得用户确认，才调用这里的 confirmed=true 动作。
 */
class V2ProjectAssistantViewModel : FluxViewModel() {

    private val _state = MutableStateFlow(V2ProjectAssistantState())
    val state = _state.asStateFlow()

    fun load(assistantId: String, quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = true, error = null)
            try {
                val api = api()
                val progress: AssistantProgress = api.assistantProgress(assistantId)
                val history: AssistantHistory = api.assistantHistory(assistantId)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    assistantName = progress.assistantName.ifBlank { history.assistantName },
                    progress = progress,
                    history = history,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = readable(e))
            }
        }
    }

    /** 读历史：只读打开一条相关对话，不发送任何消息。 */
    fun openHistory(assistantId: String, taskId: String) {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "read:$taskId", error = null)
            try {
                val detail = api().readAssistantHistory(assistantId, taskId)
                _state.value = _state.value.copy(busyAction = null, historyDetail = detail)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }

    fun closeHistory() {
        _state.value = _state.value.copy(historyDetail = null)
    }

    /** 接着干：界面已二次确认，才走到这里；成功后回调目标对话 id。 */
    fun continueConversation(
        assistantId: String,
        sourceTaskId: String,
        content: String,
        onDone: (String) -> Unit,
        onFailed: (String) -> Unit = {},
    ) {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "continue:$sourceTaskId", error = null)
            try {
                val result = api().continueAssistant(
                    assistantId = assistantId,
                    sourceTaskId = sourceTaskId,
                    content = content,
                    confirmed = true,
                )
                _state.value = _state.value.copy(
                    busyAction = null,
                    notice = "已接着干：原对话收到新任务并开始续跑",
                )
                onDone(result.task.id.ifBlank { sourceTaskId })
                load(assistantId, quiet = true)
            } catch (e: Exception) {
                val message = readable(e)
                _state.value = _state.value.copy(busyAction = null, error = message)
                onFailed(message)
            }
        }
    }

    /** 开副本：界面已二次确认，才走到这里；成功后回调新对话 id。 */
    fun forkConversation(
        assistantId: String,
        sourceTaskId: String,
        name: String?,
        onDone: (String) -> Unit,
        onFailed: (String) -> Unit = {},
    ) {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "fork:$sourceTaskId", error = null)
            try {
                val result = api().forkAssistant(
                    assistantId = assistantId,
                    sourceTaskId = sourceTaskId,
                    name = name,
                    confirmed = true,
                )
                _state.value = _state.value.copy(
                    busyAction = null,
                    notice = "已开副本：新对话已带上原对话的上下文",
                )
                onDone(result.task.id)
                load(assistantId, quiet = true)
            } catch (e: Exception) {
                val message = readable(e)
                _state.value = _state.value.copy(busyAction = null, error = message)
                onFailed(message)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, notice = null)
    }
}
