package top.qiuli55.flux.mobile.ui.tasks

import androidx.lifecycle.viewModelScope
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
    val tasks: List<Task> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val error: String? = null,
)

/** 任务列表：打开即拉、下拉刷新、新建任务。 */
class TaskListViewModel : FluxViewModel() {

    private val _state = MutableStateFlow(TaskListUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, error = null)
            try {
                val api = api()
                val tasksDeferred = async { api.listTasks() }
                val agentsDeferred = async { runCatching { api.listAgents() }.getOrDefault(emptyList()) }
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    tasks = tasksDeferred.await(),
                    agents = agentsDeferred.await(),
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

    /** 新建任务（不自动开始执行——先让用户在详情页看需求确认，再决定开工）。 */
    fun createTask(description: String, decisionMode: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val agentId = _state.value.agents.firstOrNull { it.spec?.name == "flux-builtin" }?.id
                    ?: _state.value.agents.firstOrNull()?.id
                val task = api().createTask(description.trim(), agentId, decisionMode)
                refresh()
                onCreated(task.id)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "创建失败")
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

/**
 * 任务详情：对话、需求确认、执行控制、提案审核，全在一个页面里。
 *
 * 轮询策略与桌面端一致（running / waiting_for_user_decision 时每 3 秒拉一次任务状态）：
 * 手机上没有 SSE 的任务状态流，轮询是最省电且不会漏终态的做法——一旦离开这两个状态
 * 就停止轮询，并把消息与提案列表各刷一次（终态那一刻的变化必须落到界面上）。
 */
class TaskDetailViewModel(private val taskId: String) : FluxViewModel() {

    private val _state = MutableStateFlow(TaskDetailUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
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
        viewModelScope.launch {
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

    fun send() {
        val content = _state.value.draft.trim()
        if (content.isEmpty() || _state.value.sending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null, draft = "")
            try {
                val outcome = api().sendMessage(taskId, content)
                _state.value = _state.value.copy(
                    sending = false,
                    task = outcome.task,
                    messages = _state.value.messages + outcome.messages,
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(sending = false, draft = content, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(sending = false, draft = content, error = e.message)
            }
        }
    }

    fun start() = runAction("start") { api ->
        val outcome = api.startTask(taskId)
        _state.value = _state.value.copy(
            task = outcome.task,
            messages = _state.value.messages + listOfNotNull(outcome.message),
            notice = "已开始执行，Agent 的改动会进入下面的变更列表",
        )
    }

    fun cancel() = runAction("cancel") { api ->
        val task = api.cancelTask(taskId)
        _state.value = _state.value.copy(task = task, notice = "任务已取消")
    }

    /** 停止正在跑的 Agent（Run 终态以进程树确认清理为准，服务端不谎报）。 */
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

    // --- 提案动作（列表里直接操作，与详情页共用同一套语义）---

    fun acceptChange(changeId: String) = changeAction(changeId, "accept") { api -> api.acceptChanges(listOf(changeId)) }

    fun applyChange(changeId: String) = changeAction(changeId, "apply") { api -> api.applyChanges(listOf(changeId)) }

    fun rejectChange(changeId: String) = changeAction(changeId, "reject") { api ->
        api.rejectChanges(listOf(changeId), reason = null)
    }

    fun rollbackChange(changeId: String) = changeAction(changeId, "rollback") { api -> listOf(api.rollbackChange(changeId)) }

    private fun changeAction(
        changeId: String,
        action: String,
        block: suspend (FluxApi) -> List<Change>,
    ) {
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
                // 409（状态变了、文件被外部改过）与 500（落盘失败）都原样显示服务端的话
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
            "apply" -> if (change?.applyError != null) {
                "落盘失败：${change.applyError}"
            } else {
                "已落盘：${change?.filePath ?: changeId}"
            }
            "reject" -> "已拒绝：${change?.filePath ?: changeId}"
            else -> "已回滚：${change?.filePath ?: changeId}"
        }
    }

    private fun runAction(action: String, block: suspend (FluxApi) -> Unit) {
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

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
    }
}