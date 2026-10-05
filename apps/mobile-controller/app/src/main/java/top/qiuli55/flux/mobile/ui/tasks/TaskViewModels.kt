package top.qiuli55.flux.mobile.ui.tasks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.Agent
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.Run
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.TaskMessage
import top.qiuli55.flux.mobile.ui.FluxViewModel

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
                val tasksDeferred = async { api.listTasks() }
                val agentsDeferred = async { runCatching { api.listAgents() }.getOrDefault(emptyList()) }
                val tasks = tasksDeferred.await()
                val agents = agentsDeferred.await()
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
)

class TaskDetailViewModel(private val taskId: String) : FluxViewModel() {

    private var refreshJob: Job? = null
    private var pollingJob: Job? = null
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
                val runDeferred = async {
                    task.runId?.let { runId -> runCatching { api.getRun(runId) }.getOrNull() }
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    task = task,
                    messages = messagesDeferred.await(),
                    changes = changesDeferred.await(),
                    run = runDeferred.await(),
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

    fun send() {
        val current = _state.value
        val content = current.draft.trim()
        if (content.isEmpty() || current.sending || current.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null, draft = "")
            try {
                val outcome = api().sendMessage(taskId, content)
                _state.value = _state.value.copy(sending = false, task = outcome.task, messages = outcome.messages)
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
            messages = outcome.message?.let { _state.value.messages + it } ?: _state.value.messages,
            notice = "已开始执行，Agent 的改动会进入下面的变更列表",
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
        stopPolling()
        refreshJob?.cancel()
        refreshJob = null
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
    }
}