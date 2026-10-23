package top.qiuli55.flux.mobile.ui.v2

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.Agent
import top.qiuli55.flux.mobile.data.ApiKeyEntry
import top.qiuli55.flux.mobile.data.CloudApi
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.DeviceInfo
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.ui.FluxViewModel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 启动分流：读本地会话决定落在哪一屏。 */
enum class V2Boot { LOADING, LOGIN, DEVICES, READY }

data class V2AppState(
    val boot: V2Boot = V2Boot.LOADING,
    val account: AccountSession? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val agents: List<V2Agent> = emptyList(),
    val models: List<String> = emptyList(),
    val deviceLabel: String = "本机",
    val refreshing: Boolean = false,
    val loadingDevices: Boolean = false,
    val busyAction: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val bootError: String? = null,
    val hideFinished: Boolean = false,
    /** 密钥库（/keys）；只在打开密钥库面板时加载。 */
    val keys: List<ApiKeyEntry> = emptyList(),
    val keysLoading: Boolean = false,
) {
    val conversations: List<V2Conversation> get() = tasks.map { it.toV2Conversation(deviceLabel) }
    val queueTasks: List<V2Task>
        get() = tasks
            .filterNot { hideFinished && it.isFinished }
            .map { it.toV2Task() }
    val decisions: List<V2Decision> get() = tasks.mapNotNull { it.toV2Decision() }
    val pendingCount: Int get() = tasks.count { it.pendingDecision != null }
    val runningCount: Int get() = tasks.count { it.status == "running" }
    val finishedCount: Int get() = tasks.count { it.isFinished }
    val groups: List<V2Group> get() = tasks.toV2Groups(deviceLabel)
}

class V2AppViewModel : FluxViewModel() {

    private var bootJob: Job? = null
    private var devicesJob: Job? = null
    private var refreshJob: Job? = null
    private var pollingJob: Job? = null
    private var foreground = true

    private val _state = MutableStateFlow(V2AppState())
    val state = _state.asStateFlow()

    init {
        bootstrap()
    }

    // ------------------------------------------------------------ 启动

    fun bootstrap() {
        if (bootJob?.isActive == true) return
        bootJob = viewModelScope.launch {
            _state.value = _state.value.copy(boot = V2Boot.LOADING, bootError = null)
            try {
                var session = FluxEnv.settings.account.first()
                if (session != null && session.isSignedIn && session.isExpiringSoon &&
                    session.refreshToken.isNotBlank()
                ) {
                    session = runCatching {
                        val fresh = CloudApi(session.cloudUrl).refresh(session.refreshToken)
                        fresh.copy(deviceId = session.deviceId, deviceName = session.deviceName)
                    }.onSuccess { fresh ->
                        FluxEnv.settings.saveAccount(fresh)
                        if (fresh.hasDevice) {
                            FluxEnv.settings.save(fresh.proxyBaseUrl, fresh.accessToken)
                        }
                    }.getOrElse { session }
                }
                val config = FluxEnv.settings.config.first()
                val boot = when {
                    session?.hasDevice == true -> V2Boot.READY
                    session != null -> V2Boot.DEVICES
                    config.isComplete -> V2Boot.READY
                    else -> V2Boot.LOGIN
                }
                _state.value = _state.value.copy(
                    boot = boot,
                    account = session,
                    deviceLabel = session?.deviceName?.takeIf { it.isNotBlank() }
                        ?: hostLabel(config.baseUrl),
                )
                when (boot) {
                    V2Boot.DEVICES -> loadDevices()
                    V2Boot.READY -> refresh()
                    else -> Unit
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    boot = V2Boot.LOGIN,
                    bootError = readable(e),
                )
            }
        }
    }

    // ------------------------------------------------------------ 账号与设备

    fun onSignedIn(session: AccountSession) {
        _state.value = _state.value.copy(boot = V2Boot.DEVICES, account = session, error = null)
        loadDevices()
    }

    fun loadDevices() {
        val session = _state.value.account ?: return
        if (devicesJob?.isActive == true) return
        devicesJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingDevices = true, error = null)
            try {
                val cloud = CloudApi(session.cloudUrl)
                var active = session
                val list = runCatching { cloud.devices(active.accessToken) }.getOrElse { failure ->
                    if (active.refreshToken.isBlank()) throw failure
                    val fresh = cloud.refresh(active.refreshToken)
                    val merged = fresh.copy(deviceId = active.deviceId, deviceName = active.deviceName)
                    FluxEnv.settings.saveAccount(merged)
                    active = merged
                    cloud.devices(merged.accessToken)
                }
                _state.value = _state.value.copy(
                    loadingDevices = false,
                    account = active,
                    devices = list,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingDevices = false, error = readable(e))
            }
        }
    }

    fun selectDevice(device: DeviceInfo) {
        val session = _state.value.account ?: return
        if (!device.isOnline) {
            _state.value = _state.value.copy(
                error = "设备离线：先在 ${device.name} 上打开 Flux 并登录同一账号",
            )
            return
        }
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "device:${device.deviceId}", error = null)
            try {
                val next = session.copy(deviceId = device.deviceId, deviceName = device.name)
                FluxEnv.settings.saveDevice(device.deviceId, device.name)
                FluxEnv.settings.saveAccount(next)
                FluxEnv.settings.save(next.proxyBaseUrl, next.accessToken)
                _state.value = _state.value.copy(
                    account = next,
                    boot = V2Boot.READY,
                    busyAction = null,
                    deviceLabel = device.name.ifBlank { "本机" },
                    tasks = emptyList(),
                )
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }

    /** 手动连接保存成功后进入主界面。 */
    fun onManualConnected() {
        viewModelScope.launch {
            val config = runCatching { FluxEnv.settings.config.first() }
                .getOrDefault(ConnectionConfig())
            _state.value = _state.value.copy(
                boot = V2Boot.READY,
                account = null,
                deviceLabel = hostLabel(config.baseUrl),
                error = null,
            )
            refresh()
        }
    }

    fun showDevices() {
        if (_state.value.account == null) {
            _state.value = _state.value.copy(boot = V2Boot.LOGIN)
            return
        }
        _state.value = _state.value.copy(boot = V2Boot.DEVICES, devices = emptyList())
        loadDevices()
    }

    fun logout() {
        viewModelScope.launch {
            runCatching {
                FluxEnv.settings.clearAccount()
                FluxEnv.settings.save(ConnectionConfig.DEFAULT_BASE_URL, "")
            }
            V2Registry.agents = emptyList()
            V2Registry.devices = emptyList()
            V2Registry.models = emptyList()
            V2Registry.agentModels = emptyMap()
            _state.value = V2AppState(boot = V2Boot.LOGIN)
        }
    }

    // ------------------------------------------------------------ 数据

    fun refresh(quiet: Boolean = false) {
        if (quiet && refreshJob?.isActive == true) return
        if (!quiet) refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(refreshing = true)
            try {
                val api = api()
                // 串行请求：并发失败时会整组取消（真机踩过坑）。
                val tasks = api.listTasks()
                val agents = runCatching { api.listAgents() }.getOrDefault(emptyList())
                val models = runCatching { api.dshStatus() }.getOrNull()
                    ?.model?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                val mapped = agents.toV2Agents(tasks)
                V2Registry.agents = mapped
                V2Registry.models = models
                // 每个 Agent 的可用模型 = 原生模型 + 密钥库模型（带 keyId，切换时一起提交）
                val fallbackOptions = models.map { V2ModelOption(it, it) }
                V2Registry.agentModels = agents.associate { agent ->
                    val spec = agent.spec
                    agent.id to buildList {
                        spec?.models?.forEach { add(V2ModelOption(it, it)) }
                        spec?.keyModels?.forEach {
                            add(V2ModelOption(it.model, "${it.model} · ${it.keyName}", it.keyId))
                        }
                    }
                }
                V2Registry.devices = listOf(
                    V2Device(
                        name = _state.value.deviceLabel,
                        kind = deviceKindOf(_state.value.deviceLabel),
                        agents = mapped.map {
                            V2DeviceAgent(it.id, V2Registry.agentModels[it.id] ?: fallbackOptions)
                        },
                    ),
                )
                _state.value = _state.value.copy(
                    tasks = tasks,
                    agents = mapped,
                    models = models,
                    refreshing = false,
                    error = null,
                )
                ensurePolling()
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(refreshing = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(refreshing = false, error = readable(e))
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    private fun ensurePolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!foreground) continue
                val current = _state.value
                if (current.boot != V2Boot.READY) continue
                val live = current.tasks.any {
                    it.status == "running" || it.status == "waiting_for_user_decision"
                }
                if (live) refresh(quiet = true)
            }
        }
    }

    // ------------------------------------------------------------ 动作

    fun createTask(
        description: String,
        agentId: String?,
        decisionMode: String = "auto",
        model: String = "",
        keyId: String? = null,
        onCreated: (String) -> Unit,
    ) {
        val text = description.trim()
        if (text.isEmpty()) {
            _state.value = _state.value.copy(error = "任务描述不能为空")
            return
        }
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "create", error = null, notice = null)
            try {
                val task = api().createTask(
                    text,
                    agentId?.takeIf { it.isNotBlank() },
                    decisionMode,
                    model.takeIf { it.isNotBlank() },
                    keyId?.takeIf { it.isNotBlank() },
                )
                _state.value = _state.value.copy(busyAction = null)
                refresh(quiet = true)
                onCreated(task.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }

    // ------------------------------------------------------------ 密钥库

    fun loadKeys() {
        if (_state.value.keysLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(keysLoading = true)
            try {
                val keys = api().listKeys()
                _state.value = _state.value.copy(keys = keys, keysLoading = false, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(keysLoading = false, error = readable(e))
            }
        }
    }

    /** 新建/修改密钥；onResult 为空串表示成功，否则是错误文案。 */
    fun saveKey(
        keyId: String?,
        name: String,
        provider: String,
        baseUrl: String,
        apiKey: String?,
        models: List<String>,
        wireApi: String,
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val api = api()
                if (keyId.isNullOrBlank()) {
                    api.createKey(name, provider, baseUrl, apiKey.orEmpty(), models, wireApi, enabled)
                } else {
                    api.updateKey(keyId, name, provider, baseUrl, apiKey, models, wireApi, enabled)
                }
                refresh(quiet = true)
                val keys = api.listKeys()
                _state.value = _state.value.copy(keys = keys, error = null)
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    fun deleteKey(keyId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                api().deleteKey(keyId)
                val keys = api().listKeys()
                _state.value = _state.value.copy(keys = keys, error = null)
                refresh(quiet = true)
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    fun setKeyEnabled(keyId: String, enabled: Boolean, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                api().setKeyEnabled(keyId, enabled)
                val keys = api().listKeys()
                _state.value = _state.value.copy(keys = keys, error = null)
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    fun approveDecision(taskId: String, decisionId: String) {
        val decision = _state.value.tasks.firstOrNull { it.id == taskId }?.pendingDecision
        val option = decision?.options?.firstOrNull { it.recommended }?.label
            ?: decision?.options?.firstOrNull()?.label
        chooseDecision(taskId, decisionId, option, reject = false)
    }

    fun rejectDecision(taskId: String, decisionId: String) =
        chooseDecision(taskId, decisionId, null, reject = true)

    private fun chooseDecision(taskId: String, decisionId: String, option: String?, reject: Boolean) =
        runAction("decision:$decisionId") {
            api().chooseDecision(
                taskId = taskId,
                decisionId = decisionId,
                action = if (reject) "reject" else "choose",
                option = option,
                note = null,
            )
            if (reject) "已拒绝该决策" else "已提交你的选择"
        }

    fun startTask(taskId: String) = runAction("start:$taskId") {
        api().startTask(taskId)
        "已开始执行"
    }

    fun cancelTask(taskId: String) = runAction("cancel:$taskId") {
        api().cancelTask(taskId)
        "任务已取消"
    }

    fun interruptTask(taskId: String) = runAction("interrupt:$taskId") {
        val runId = _state.value.tasks.firstOrNull { it.id == taskId }?.runId
            ?: throw FluxApiException("no_run", "任务还没有运行记录")
        api().interruptRun(runId)
        "已请求停止 Agent"
    }

    fun dismissFinished() {
        _state.value = _state.value.copy(hideFinished = true)
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
                refresh(quiet = true)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }

    override fun onCleared() {
        bootJob?.cancel()
        devicesJob?.cancel()
        refreshJob?.cancel()
        pollingJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
    }
}

// ---------------------------------------------------------------- 映射

internal val Task.isFinished: Boolean
    get() = status in setOf("done", "completed", "succeeded", "failed", "error", "canceled", "cancelled")

internal fun Task.firstLine(max: Int = 60): String =
    description.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(max)
        ?: "未命名任务"

private fun hostLabel(baseUrl: String): String =
    runCatching { java.net.URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "本机"

private fun deviceKindOf(label: String): V2DeviceKind {
    val name = label.lowercase()
    return when {
        name.contains("mac") || name.contains("book") -> V2DeviceKind.LAPTOP
        name.contains("phone") || name.contains("iphone") || name.contains("android") ||
            name.contains("pixel") || name.contains("mate") || name.contains("mi ") -> V2DeviceKind.PHONE
        else -> V2DeviceKind.DESKTOP
    }
}

internal fun readable(e: Exception): String = when (e) {
    is FluxApiException -> e.message
    else -> e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "操作失败")
}

private val agentTints = listOf(
    V2Colors.accent,
    V2Colors.violet,
    V2Colors.emerald,
    V2Colors.amber700,
    V2Colors.indigo,
)

internal fun List<Agent>.toV2Agents(tasks: List<Task>): List<V2Agent> =
    mapIndexed { index, agent ->
        val name = agent.spec?.name?.takeIf { it.isNotBlank() } ?: agent.id.take(12)
        val running = tasks.count { it.agentId == agent.id && it.status == "running" }
        V2Agent(
            id = agent.id,
            name = name,
            role = agent.spec?.role?.takeIf { it.isNotBlank() }
                ?: agent.state.takeIf { it.isNotBlank() }
                ?: "Agent",
            load = if (running > 0) 100 else 0,
            running = running,
            tint = agentTints[index % agentTints.size],
        )
    }

internal fun Task.toV2Conversation(device: String): V2Conversation = V2Conversation(
    id = id,
    agentId = agentId.orEmpty(),
    title = firstLine(40),
    device = device,
    source = "desktop",
    updated = relativeTime(createdAt),
    unread = if (pendingDecision != null) 1 else 0,
    messages = emptyList(),
)

internal fun Task.toV2Task(): V2Task = V2Task(
    id = id,
    title = firstLine(),
    agentId = agentId.orEmpty(),
    priority = when {
        priority <= 50 -> "P1"
        priority <= 100 -> "P2"
        else -> "P3"
    },
    status = when (status) {
        "running" -> V2TaskStatus.RUNNING
        "waiting_for_user_decision" -> V2TaskStatus.WAITING
        "done", "completed", "succeeded" -> V2TaskStatus.DONE
        "failed", "error" -> V2TaskStatus.FAILED
        "canceled", "cancelled" -> V2TaskStatus.CANCELED
        else -> V2TaskStatus.WAITING
    },
    // 服务端没有进度字段：运行中用 -1 表示「进度未知」。
    progress = when {
        status == "running" -> -1
        status in setOf("done", "completed", "succeeded") -> 100
        else -> 0
    },
    note = resultSummary(),
)

private fun Task.resultSummary(): String? {
    val element = result ?: return null
    return runCatching {
        when (element) {
            is JsonPrimitive -> element.content.take(120)
            is JsonObject -> (element["summary"] ?: element["message"] ?: element["text"])
                ?.let { it as? JsonPrimitive }
                ?.content
                ?.take(120)
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private val highRiskWords = listOf(
    "删除", "清空", "覆盖", "重置", "格式化", "迁移", "生产", "回滚",
    "rm -rf", "drop ", "truncate", "delete", "force", "destructive", "migrate",
)

internal fun Task.toV2Decision(): V2Decision? {
    val decision = pendingDecision ?: return null
    val corpus = buildString {
        append(decision.question.orEmpty())
        append(' ')
        append(decision.context.orEmpty())
        append(' ')
        append(decision.recommendation.orEmpty())
        decision.options.forEach {
            append(' ')
            append(it.label)
            append(' ')
            append(it.description.orEmpty())
            append(' ')
            append(it.impact.orEmpty())
        }
        append(' ')
        append(confirmation?.items?.joinToString(" ") { "${it.label} ${it.value}" }.orEmpty())
    }.lowercase()
    val high = highRiskWords.any { corpus.contains(it) }
    val impact = buildList {
        confirmation?.items?.take(3)?.forEach {
            if (it.label.isNotBlank() || it.value.isNotBlank()) {
                add(V2Impact(it.label.ifBlank { "确认项" }, it.value.ifBlank { "—" }, high))
            }
        }
        if (isEmpty()) {
            add(V2Impact("影响范围", if (high) "高风险" else "可控", high))
            add(V2Impact("可撤销", if (high) "否" else "是", high))
            add(V2Impact("确认方式", "需人工", false))
        }
    }
    val recommendation = decision.recommendation?.takeIf { it.isNotBlank() }
    val recommended = decision.options.firstOrNull { it.recommended }
    return V2Decision(
        id = decision.id ?: "$id-d",
        risk = if (high) V2Risk.HIGH else V2Risk.MEDIUM,
        age = relativeTime(createdAt),
        agentId = agentId.orEmpty(),
        title = decision.question?.takeIf { it.isNotBlank() } ?: firstLine(80),
        reason = decision.context?.takeIf { it.isNotBlank() }
            ?: recommendation
            ?: "Agent 需要你的确认后才会继续执行。",
        eta = "确认后继续",
        reversible = !high,
        taskTitle = firstLine(40),
        impact = impact,
        sql = recommendation
            ?: recommended?.impact?.takeIf { it.isNotBlank() }
            ?: "等待确认后继续执行",
        notes = decision.options.mapNotNull { option ->
            option.description?.takeIf { it.isNotBlank() }
                ?.let { "${option.label}${if (option.recommended) "（推荐）" else ""}：$it" }
        }.take(3),
        taskId = id,
    )
}

internal fun List<Task>.toV2Groups(device: String): List<V2Group> {
    if (isEmpty()) return emptyList()
    val waiting = count { it.pendingDecision != null }
    val running = count { it.status == "running" }
    val finished = count { it.isFinished }
    val status = when {
        waiting > 0 -> V2GroupStatus.BLOCKED
        running > 0 -> V2GroupStatus.RUNNING
        else -> V2GroupStatus.DONE
    }
    val stageIndex = when (status) {
        V2GroupStatus.BLOCKED -> 2
        V2GroupStatus.RUNNING -> 1
        V2GroupStatus.DONE -> 3
    }
    val stages = listOf(
        "任务排队" to "$size 个任务",
        "执行中" to if (running > 0) "$running 个 Agent 运行中" else "暂无执行中任务",
        "等待确认" to if (waiting > 0) "$waiting 项待确认" else "无需确认",
        "完成" to "$finished 个已结束",
    ).mapIndexed { index, (name, note) ->
        V2Stage(
            name = name,
            note = note,
            time = "",
            state = when {
                status == V2GroupStatus.DONE -> V2StageState.DONE
                index < stageIndex -> V2StageState.DONE
                index == stageIndex -> V2StageState.ACTIVE
                else -> V2StageState.PENDING
            },
        )
    }
    val latest = maxByOrNull { parseEpochSeconds(it.createdAt) ?: 0L } ?: first()
    return listOf(
        V2Group(
            id = "device:$device",
            title = "$device · 协作任务",
            device = device,
            status = status,
            progress = if (isEmpty()) 0 else finished * 100 / size,
            stage = stageIndex,
            updated = relativeTime(latest.createdAt),
            agents = mapNotNull { it.agentId?.takeIf { id -> id.isNotBlank() } }.distinct(),
            stages = stages,
            events = sortedByDescending { parseEpochSeconds(it.createdAt) ?: 0L }
                .take(3)
                .map { it.toV2Event() },
        ),
    )
}

private fun Task.toV2Event(): V2Event {
    val tail = when {
        pendingDecision != null -> "等待确认：${pendingDecision?.question ?: "需要人工决策"}"
        status == "running" -> "执行中"
        else -> "已结束"
    }
    return V2Event(
        agentId = agentId.orEmpty(),
        text = "${firstLine(24)} · $tail",
        time = relativeTime(createdAt),
    )
}

// ---------------------------------------------------------------- 时间

internal fun parseEpochSeconds(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val text = raw.trim()
    return runCatching { Instant.parse(text).epochSecond }.getOrElse {
        runCatching { OffsetDateTime.parse(text).toInstant().epochSecond }.getOrElse {
            runCatching {
                // 后端给的时间戳是 UTC 且不带时区后缀（如 2026-10-22T09:07:56），
                // 不能按本机时区解析，否则会整体偏移 8 小时。
                LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).epochSecond
            }.getOrElse {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(text)?.time?.div(1000)
                }.getOrNull()
            }
        }
    }
}

internal fun relativeTime(raw: String?): String {
    val epoch = parseEpochSeconds(raw) ?: return "刚刚"
    val diff = (System.currentTimeMillis() / 1000 - epoch).coerceAtLeast(0)
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86_400 -> "${diff / 3600} 小时前"
        diff < 86_400 * 7 -> "${diff / 86_400} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(epoch * 1000))
    }
}
