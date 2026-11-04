package top.qiuli55.flux.mobile.ui.v2

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.Agent
import top.qiuli55.flux.mobile.data.ApiKeyEntry
import top.qiuli55.flux.mobile.data.BillingOverview
import top.qiuli55.flux.mobile.data.BillingPrices
import top.qiuli55.flux.mobile.data.Bot
import top.qiuli55.flux.mobile.data.ChatGroup
import top.qiuli55.flux.mobile.data.ChatGroupMember
import top.qiuli55.flux.mobile.data.ChatGroupMemoryState
import top.qiuli55.flux.mobile.data.ChatGroupMessage
import top.qiuli55.flux.mobile.data.CloudApi
import top.qiuli55.flux.mobile.data.CodexAccountStatus
import top.qiuli55.flux.mobile.data.CodexLoginSession
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.DeviceInfo
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.KeyProbeResult
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.data.UserMemoryEntry
import top.qiuli55.flux.mobile.ui.FluxViewModel
import top.qiuli55.flux.mobile.ui.tasks.readPickedAttachment
import java.io.ByteArrayInputStream
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

/** 群聊页面的本地状态：群列表 + 当前群的消息流。 */
data class V2GroupChatState(
    val groups: List<ChatGroup> = emptyList(),
    val loadingGroups: Boolean = false,
    val groupsError: String? = null,
    /** 当前打开的群（含成员列表）；null = 没进群聊页。 */
    val group: ChatGroup? = null,
    val messages: List<ChatGroupMessage> = emptyList(),
    val loadingMessages: Boolean = false,
    val sending: Boolean = false,
    /** 「Bot 们正在讨论」窗口结束时间（epoch ms）；<= now 即视为已停。 */
    val discussUntil: Long = 0L,
    /** 分层记忆（本群 + 团队）；打开成员面板时按需加载。 */
    val memory: ChatGroupMemoryState? = null,
    val loadingMemory: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

data class V2AppState(
    val boot: V2Boot = V2Boot.LOADING,
    val account: AccountSession? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val tasks: List<Task> = emptyList(),
    /** 常驻 Bot 列表；群聊的成员与候选都来自这里。 */
    val bots: List<Bot> = emptyList(),
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
    /** 账号级用户记忆；只在打开「用户记忆」面板时加载。 */
    val memories: List<UserMemoryEntry> = emptyList(),
    val memoriesLoading: Boolean = false,
    val memorySaving: Boolean = false,
    val memoryAutoCapture: Boolean = true,
    val memoryCloudReady: Boolean = false,
    val memoryCloudError: String = "",
    /** ChatGPT 账号（Codex 订阅通道）；设置页打开时加载。 */
    val codexAccount: CodexAccountStatus? = null,
    val codexLogin: CodexLoginSession? = null,
    val codexLoading: Boolean = false,
    /** 套餐额度（成本中心）；进首页时加载，失败不影响任务主流程。 */
    val billing: BillingOverview? = null,
    /** 官方 API 价目表；与额度一起加载，失败只是不展示。 */
    val billingPrices: BillingPrices? = null,
    val billingLoading: Boolean = false,
    val billingError: String? = null,
    /** 内部群聊：群列表与当前打开群的消息流。 */
    val groupChat: V2GroupChatState = V2GroupChatState(),
) {
    /** 助理自己的对话单独在助理页展示，不挤占首页的对话列表与统计。 */
    private val chatTasks: List<Task> get() = tasks.filterNot { it.origin == "assistant" }

    /** 建群 / 加成员时的候选：全部常驻 Bot。 */
    val botCandidates: List<Bot> get() = bots
    val conversations: List<V2Conversation>
        get() = chatTasks.map { it.toV2Conversation(deviceLabel) }
    val queueTasks: List<V2Task>
        get() = chatTasks
            .filterNot { hideFinished && it.isFinished }
            .map { it.toV2Task() }
            // 稳定分区：执行中永远在最前，其次等待，其余保持后端原序。
            // sortedBy 是稳定排序，同级顺序不会每 3 秒轮询乱跳。
            .sortedBy { it.status.queueRank }
    val decisions: List<V2Decision> get() = chatTasks.mapNotNull { it.toV2Decision() }
    val pendingCount: Int get() = chatTasks.count { it.pendingDecision != null }
    val runningCount: Int
        get() = chatTasks.count { it.status.toV2TaskStatus() == V2TaskStatus.RUNNING }
    val finishedCount: Int get() = chatTasks.count { it.isFinished }
}

class V2AppViewModel : FluxViewModel() {

    private var bootJob: Job? = null
    private var devicesJob: Job? = null
    private var refreshJob: Job? = null
    private var pollingJob: Job? = null
    private var codexLoginJob: Job? = null
    private var billingJob: Job? = null
    private var groupListJob: Job? = null
    private var groupPollJob: Job? = null
    /** 当前打开的群 id；用于丢弃「切群后晚到的旧请求」。 */
    private var openGroupId: String? = null
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
                val bots = runCatching { api.listBots() }.getOrDefault(emptyList())
                val groups = runCatching { api.listGroups() }.getOrNull()
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
                    bots = bots,
                    agents = mapped,
                    models = models,
                    refreshing = false,
                    error = null,
                    groupChat = if (groups != null) {
                        _state.value.groupChat.copy(groups = groups, groupsError = null)
                    } else {
                        _state.value.groupChat
                    },
                )
                ensurePolling()
                // 额度只在显式刷新（进首页 / 切设备 / 下拉刷新）时读，静默轮询不额外打这个接口。
                if (!quiet) loadBilling()
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
                val live = current.tasks.any { it.status.toV2TaskStatus().isActive }
                if (live) refresh(quiet = true)
            }
        }
    }

    // ------------------------------------------------------------ 套餐额度

    /**
     * 读成本中心视图；[sync] 为真时先让后端抓一次官方额度（OpenCode Go 接口 / arkcli）。
     * 对账是可选动作：抓不到只影响官方百分比，本地账本照常展示。
     */
    fun loadBilling(sync: Boolean = false) {
        if (billingJob?.isActive == true && !sync) return
        billingJob = viewModelScope.launch {
            _state.value = _state.value.copy(billingLoading = true, billingError = null)
            try {
                val api = api()
                if (sync) runCatching { api.refreshBilling() }
                val overview = api.billingOverview()
                val prices = runCatching { api.billingPrices() }.getOrNull()
                _state.value = _state.value.copy(
                    billing = overview,
                    billingPrices = prices,
                    billingLoading = false,
                    billingError = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(billingLoading = false, billingError = readable(e))
            }
        }
    }

    // ------------------------------------------------------------ 动作

    fun createTask(
        description: String,
        agentId: String?,
        decisionMode: String = "auto",
        permissionMode: String = "normal",
        model: String = "",
        keyId: String? = null,
        summary: String? = null,
        summaryFrom: String? = null,
        attachmentIds: List<String> = emptyList(),
        origin: String = "chat",
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
                    permissionMode,
                    model.takeIf { it.isNotBlank() },
                    keyId?.takeIf { it.isNotBlank() },
                    summary = summary?.takeIf { it.isNotBlank() },
                    summaryFrom = summaryFrom?.takeIf { it.isNotBlank() },
                    attachmentIds = attachmentIds,
                    origin = origin,
                )
                _state.value = _state.value.copy(busyAction = null)
                refresh(quiet = true)
                onCreated(task.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null, error = readable(e))
            }
        }
    }

    /**
     * 新建对话草稿区：把选中的文件逐个上传，结果通过回调交给页面的本地状态。
     * 附件状态不进 ViewModel，避免离开页面后还挂着一组没人用的草稿。
     */
    fun uploadDraftAttachments(
        uris: List<Uri>,
        resolver: ContentResolver,
        onUploaded: (UploadedAttachment) -> Unit,
        onError: (String) -> Unit,
        onFinished: () -> Unit,
    ) {
        if (uris.isEmpty()) {
            onFinished()
            return
        }
        viewModelScope.launch {
            val problems = mutableListOf<String>()
            try {
                val api = api()
                uris.forEach { uri ->
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
                        val uploaded = api.uploadAttachment(
                            name = picked.name,
                            mime = picked.mime,
                            size = picked.size,
                        ) {
                            resolver.openInputStream(uri)
                                ?: throw FluxApiException("attachment_unreadable", "无法读取「${picked.name}」")
                        }
                        onUploaded(uploaded)
                    } catch (e: Exception) {
                        problems += readable(e)
                    }
                }
                if (problems.isNotEmpty()) onError(problems.take(2).joinToString("；"))
            } finally {
                onFinished()
            }
        }
    }

    /**
     * 设计稿当附件：先按设计稿 id 导出 SVG，再走同一条上传通道，不落地临时文件。
     * 上传结果通过回调交回页面，与 [uploadDraftAttachments] 的状态管理保持一致。
     */
    fun uploadDraftDesign(
        designId: String,
        name: String,
        onUploaded: (UploadedAttachment) -> Unit,
        onError: (String) -> Unit,
        onFinished: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val api = api()
                val svg = api.exportDesignSvg(designId)
                val uploaded = api.uploadAttachment(
                    name = "${name.ifBlank { "design" }}.svg",
                    mime = "image/svg+xml",
                    size = svg.size.toLong(),
                ) { ByteArrayInputStream(svg) }
                onUploaded(uploaded)
            } catch (e: Exception) {
                onError(readable(e))
            } finally {
                onFinished()
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

    /** 新建/修改密钥；onResult 的 error 为空串表示成功，notice 是成功后的提示（含连通性校验结果）。 */
    fun saveKey(
        keyId: String?,
        name: String,
        provider: String,
        baseUrl: String,
        apiKey: String?,
        models: List<String>,
        wireApi: String,
        enabled: Boolean,
        onResult: (error: String, notice: String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val api = api()
                val saved = if (keyId.isNullOrBlank()) {
                    api.createKey(name, provider, baseUrl, apiKey.orEmpty(), models, wireApi, enabled)
                } else {
                    api.updateKey(keyId, name, provider, baseUrl, apiKey, models, wireApi, enabled)
                }
                refresh(quiet = true)
                val keys = api.listKeys()
                _state.value = _state.value.copy(keys = keys, error = null)
                val probe = runCatching { api.probeKey(saved.id) }.getOrNull()
                onResult("", probeNotice(probe))
            } catch (e: Exception) {
                onResult(readable(e), "")
            }
        }
    }

    /** 保存后的连通性校验结果 → 一句提示；探测本身失败只降级为"未校验"，不影响保存结果。 */
    private fun probeNotice(result: KeyProbeResult?): String = when {
        result == null -> "已保存（未能校验连通性）"
        result.skipped -> "已保存 · ${result.error}"
        result.ok -> "已保存 · 连通性正常（${result.latencyMs} ms）"
        else -> "已保存，但连通性校验失败：${result.error}"
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

    // ------------------------------------------------------------ 账号级用户记忆

    /** 打开面板时加载：条目 + 自动记录开关 + 云端就绪状态。 */
    fun loadMemories(force: Boolean = false) {
        if (_state.value.memoriesLoading && !force) return
        viewModelScope.launch {
            _state.value = _state.value.copy(memoriesLoading = true)
            try {
                val memories = api().listUserMemories()
                val settings = api().memorySettings()
                _state.value = _state.value.copy(
                    memories = memories,
                    memoriesLoading = false,
                    memoryAutoCapture = settings.autoCapture,
                    memoryCloudReady = settings.cloudReady,
                    memoryCloudError = settings.cloudError,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    memoriesLoading = false,
                    memoryCloudError = readable(e),
                )
            }
        }
    }

    /** 手动添加一条记忆；onResult 为空串表示成功。 */
    fun addMemory(content: String, onResult: (String) -> Unit = {}) {
        val text = content.trim()
        if (text.isBlank()) {
            onResult("内容不能为空")
            return
        }
        if (_state.value.memorySaving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(memorySaving = true)
            try {
                api().createUserMemory(text)
                val memories = api().listUserMemories()
                _state.value = _state.value.copy(
                    memories = memories,
                    memorySaving = false,
                    error = null,
                )
                onResult("")
            } catch (e: Exception) {
                _state.value = _state.value.copy(memorySaving = false)
                onResult(readable(e))
            }
        }
    }

    /** 修改一条记忆；云端同步失败时后端会退化为待补推，不影响本地内容。 */
    fun updateMemory(memoryId: String, content: String, onResult: (String) -> Unit = {}) {
        val text = content.trim()
        if (text.isBlank()) {
            onResult("内容不能为空")
            return
        }
        if (_state.value.memorySaving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(memorySaving = true)
            try {
                api().updateUserMemory(memoryId, text)
                val memories = api().listUserMemories()
                _state.value = _state.value.copy(
                    memories = memories,
                    memorySaving = false,
                    error = null,
                )
                onResult("")
            } catch (e: Exception) {
                _state.value = _state.value.copy(memorySaving = false)
                onResult(readable(e))
            }
        }
    }

    fun deleteMemory(memoryId: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                api().deleteUserMemory(memoryId)
                val memories = api().listUserMemories()
                _state.value = _state.value.copy(memories = memories, error = null)
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    /** 自动记录开关；失败时回滚到服务端实际值。 */
    fun setMemoryAutoCapture(enabled: Boolean) {
        viewModelScope.launch {
            val previous = _state.value.memoryAutoCapture
            _state.value = _state.value.copy(memoryAutoCapture = enabled)
            try {
                api().setMemoryAutoCapture(enabled)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    memoryAutoCapture = previous,
                    error = readable(e),
                )
            }
        }
    }

    /** 立即与账号云对一次账，然后刷新列表。 */
    fun syncMemories(onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                api().syncUserMemories()
                loadMemories(force = true)
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    // ------------------------------------------------------------ ChatGPT 账号（Codex 订阅）

    /** 设置页打开时读取账号态；有进行中的设备码登录就继续轮询。 */
    fun loadCodexAccount() {
        viewModelScope.launch {
            _state.value = _state.value.copy(codexLoading = true)
            try {
                val status = api().codexAccount()
                val active = status.pendingLogin ?: _state.value.codexLogin?.takeIf { it.isPending }
                _state.value = _state.value.copy(
                    codexAccount = status,
                    codexLogin = active,
                    codexLoading = false,
                )
                if (active != null) pollCodexLogin(active.loginId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(codexLoading = false, error = readable(e))
            }
        }
    }

    fun startCodexLogin() {
        viewModelScope.launch {
            _state.value = _state.value.copy(codexLoading = true, error = null)
            try {
                val session = api().startCodexLogin()
                _state.value = _state.value.copy(codexLogin = session, codexLoading = false)
                if (session.isPending) pollCodexLogin(session.loginId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(codexLoading = false, error = readable(e))
            }
        }
    }

    fun logoutCodexAccount() {
        codexLoginJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(codexLoading = true, error = null)
            try {
                val result = api().logoutCodexAccount()
                _state.value = _state.value.copy(
                    codexAccount = result.status,
                    codexLogin = null,
                    codexLoading = false,
                    notice = "已退出 ChatGPT 账号",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(codexLoading = false, error = readable(e))
            }
        }
    }

    /** 设备码有效期 15 分钟：20 分钟内每 2 秒轮询，成功后刷新账号态。 */
    private fun pollCodexLogin(loginId: String) {
        codexLoginJob?.cancel()
        codexLoginJob = viewModelScope.launch {
            repeat(600) {
                delay(2000)
                val current = _state.value.codexLogin ?: return@launch
                if (current.loginId != loginId || !current.isPending) return@launch
                val next = runCatching { api().codexLoginState(loginId) }.getOrNull()
                    ?: return@launch
                val refreshed = if (next.status == "success") {
                    runCatching { api().codexAccount() }.getOrNull()
                } else {
                    null
                }
                _state.value = _state.value.copy(
                    codexLogin = next,
                    codexAccount = refreshed ?: _state.value.codexAccount,
                    notice = if (next.status == "success") "ChatGPT 账号已连接" else _state.value.notice,
                )
                if (!next.isPending) return@launch
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

    /** 删除一段对话（任务 + 消息 + 运行记录）；失败文案回传给删除面板。 */
    fun deleteConversation(taskId: String, onResult: (String) -> Unit) {
        if (_state.value.busyAction != null) {
            onResult("有操作正在进行，稍后再试")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "delete:$taskId", error = null, notice = null)
            try {
                api().deleteTask(taskId)
                // 先本地摘掉再刷新：正在进行的轮询可能带着旧列表晚到，避免对话短暂复活。
                _state.value = _state.value.copy(
                    busyAction = null,
                    notice = "Bot 已删除",
                    tasks = _state.value.tasks.filterNot { it.id == taskId },
                )
                refresh(quiet = true)
                onResult("")
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busyAction = null)
                onResult(e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null)
                onResult(readable(e))
            }
        }
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

    // ------------------------------------------------------------ 内部群聊

    /** 群列表：进首页时与对话一起刷新；失败保留上一份。 */
    fun loadGroups(quiet: Boolean = true) {
        if (groupListJob?.isActive == true) return
        groupListJob = viewModelScope.launch {
            if (!quiet) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(loadingGroups = true, groupsError = null),
                )
            }
            try {
                val groups = api().listGroups()
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        groups = groups,
                        loadingGroups = false,
                        groupsError = null,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        loadingGroups = false,
                        groupsError = if (quiet) _state.value.groupChat.groupsError else readable(e),
                    ),
                )
            }
        }
    }

    /** 进入群聊页：拉群详情 + 全量消息，然后开 2s 增量轮询。 */
    fun openGroupChat(groupId: String) {
        if (openGroupId == groupId) return
        openGroupId = groupId
        groupPollJob?.cancel()
        _state.value = _state.value.copy(
            groupChat = _state.value.groupChat.copy(
                group = null,
                messages = emptyList(),
                loadingMessages = true,
                sending = false,
                discussUntil = 0L,
                memory = null,
                loadingMemory = false,
                error = null,
                notice = null,
            ),
        )
        viewModelScope.launch {
            try {
                val api = api()
                val group = api.getGroup(groupId)
                if (openGroupId != groupId) return@launch
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(group = group),
                )
                val messages = fetchAllGroupMessages(api, groupId)
                if (openGroupId != groupId) return@launch
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        messages = messages,
                        loadingMessages = false,
                    ),
                )
                startGroupPolling(groupId)
            } catch (e: Exception) {
                if (openGroupId != groupId) return@launch
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        loadingMessages = false,
                        error = readable(e),
                    ),
                )
            }
        }
    }

    /** 离开群聊页：停轮询并清空当前群，避免下次进来看到旧消息。 */
    fun closeGroupChat() {
        if (openGroupId == null) return
        openGroupId = null
        groupPollJob?.cancel()
        _state.value = _state.value.copy(
            groupChat = _state.value.groupChat.copy(
                group = null,
                messages = emptyList(),
                loadingMessages = false,
                sending = false,
                discussUntil = 0L,
                memory = null,
                loadingMemory = false,
                error = null,
                notice = null,
            ),
        )
        loadGroups(quiet = true)
    }

    /** 发言：先本地插入自己的消息，再等 bot 逐个回复（轮询增量拉取）。 */
    fun sendGroupMessage(groupId: String, content: String) {
        val text = content.trim()
        if (text.isEmpty() || _state.value.groupChat.sending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                groupChat = _state.value.groupChat.copy(sending = true, error = null),
            )
            try {
                val outcome = api().sendGroupMessage(groupId, text)
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        sending = false,
                        messages = mergeGroupMessages(
                            _state.value.groupChat.messages,
                            listOf(outcome.message),
                        ),
                        discussUntil = System.currentTimeMillis() + DISCUSS_WINDOW_MS,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(sending = false, error = readable(e)),
                )
            }
        }
    }

    /** 打断本轮讨论：后端会停掉后续发言。 */
    fun interruptGroupChat(groupId: String) {
        viewModelScope.launch {
            try {
                api().interruptGroup(groupId)
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        discussUntil = 0L,
                        notice = "已打断本轮讨论",
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(error = readable(e)),
                )
            }
        }
    }

    fun createGroup(
        name: String,
        description: String,
        maxRounds: Int,
        botIds: List<String>,
        adminBotId: String? = null,
        onResult: (ChatGroup?) -> Unit,
    ) {
        val title = name.trim()
        if (title.isEmpty()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                groupChat = _state.value.groupChat.copy(error = null),
            )
            try {
                val group = api().createGroup(
                    name = title,
                    botIds = botIds,
                    description = description.trim().takeIf { it.isNotEmpty() },
                    maxRounds = maxRounds,
                    adminBotId = adminBotId?.takeIf { it in botIds },
                )
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        groups = listOf(group) + _state.value.groupChat.groups,
                        notice = "群聊已创建",
                    ),
                )
                onResult(group)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(error = readable(e)),
                )
                onResult(null)
            }
        }
    }

    fun deleteGroupChat(groupId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                api().deleteGroup(groupId)
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        groups = _state.value.groupChat.groups.filterNot { it.id == groupId },
                        notice = "群聊已删除",
                    ),
                )
                if (openGroupId == groupId) closeGroupChat()
                onResult("")
            } catch (e: Exception) {
                onResult(readable(e))
            }
        }
    }

    fun addGroupMember(groupId: String, botId: String) {
        viewModelScope.launch {
            try {
                val members = api().addGroupMember(groupId, botId)
                applyGroupMembers(groupId, members)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(error = readable(e)),
                )
            }
        }
    }

    fun removeGroupMember(groupId: String, botId: String) {
        viewModelScope.launch {
            try {
                val api = api()
                api.removeGroupMember(groupId, botId)
                val group = api.getGroup(groupId)
                if (openGroupId == groupId) {
                    _state.value = _state.value.copy(
                        groupChat = _state.value.groupChat.copy(group = group),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(error = readable(e)),
                )
            }
        }
    }

    /**
     * 调整群成员：角色（admin/member）、模型、权限预设。只传需要改的项。
     * 传 role="admin" 时后端会把原群主降为普通成员（一个群最多一个群主）。
     */
    fun updateGroupMember(
        groupId: String,
        botId: String,
        role: String? = null,
        model: String? = null,
        keyId: String? = null,
        permissionMode: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val members = api().updateGroupMember(
                    groupId = groupId,
                    botId = botId,
                    role = role,
                    model = model,
                    keyId = keyId,
                    permissionMode = permissionMode,
                )
                applyGroupMembers(groupId, members)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(error = readable(e)),
                )
            }
        }
    }

    /** 读取分层记忆（群共享 + 团队共享）；由成员面板打开时触发。 */
    fun loadGroupMemory(groupId: String) {
        if (_state.value.groupChat.loadingMemory) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                groupChat = _state.value.groupChat.copy(memory = null, loadingMemory = true),
            )
            try {
                val memory = api().getGroupMemory(groupId)
                if (openGroupId != groupId) return@launch
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(memory = memory, loadingMemory = false),
                )
            } catch (e: Exception) {
                if (openGroupId != groupId) return@launch
                _state.value = _state.value.copy(
                    groupChat = _state.value.groupChat.copy(
                        loadingMemory = false,
                        error = readable(e),
                    ),
                )
            }
        }
    }

    /** 新建一个对话（本质是一条不立即执行的 tasks 记录）：名称 + 头像，可选执行 Agent。 */
    fun createConversation(
        name: String,
        shape: String,
        color: String,
        agentId: String? = null,
        onResult: (error: String, taskId: String) -> Unit,
    ) {
        val title = name.trim()
        if (title.isEmpty()) {
            onResult("名称不能为空", "")
            return
        }
        if (_state.value.busyAction != null) {
            onResult("有操作正在进行，稍后再试", "")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "create-bot", error = null, notice = null)
            try {
                val created = api().createTask(
                    description = title,
                    agentId = agentId,
                    decisionMode = "auto",
                    botName = title,
                    avatarShape = shape,
                    avatarColor = color,
                    start = false,
                )
                _state.value = _state.value.copy(busyAction = null)
                refresh(quiet = true)
                onResult("", created.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null)
                onResult(readable(e), "")
            }
        }
    }

    /** 新建一个常驻 Bot（群聊成员）：落到 /bots，返回 botId。 */
    fun createBot(
        name: String,
        shape: String,
        color: String,
        agentId: String? = null,
        onResult: (error: String, botId: String) -> Unit,
    ) {
        val title = name.trim()
        if (title.isEmpty()) {
            onResult("名称不能为空", "")
            return
        }
        if (_state.value.busyAction != null) {
            onResult("有操作正在进行，稍后再试", "")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busyAction = "create-bot", error = null, notice = null)
            try {
                val created = api().createBot(
                    name = title,
                    shape = shape,
                    color = color,
                    agentId = agentId,
                )
                _state.value = _state.value.copy(
                    busyAction = null,
                    bots = listOf(created) + _state.value.bots,
                )
                onResult("", created.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busyAction = null)
                onResult(readable(e), "")
            }
        }
    }

    private fun applyGroupMembers(groupId: String, members: List<ChatGroupMember>) {
        val current = _state.value.groupChat
        if (current.group?.id != groupId) {
            // 成员变化不影响其他群；首页只显示数量，下次刷新会同步。
            return
        }
        _state.value = _state.value.copy(
            groupChat = current.copy(
                group = current.group.copy(
                    members = members,
                    memberCount = members.size,
                ),
            ),
        )
    }

    private suspend fun fetchAllGroupMessages(api: FluxApi, groupId: String): List<ChatGroupMessage> {
        var afterSeq = 0
        var pages = 0
        var collected: List<ChatGroupMessage> = emptyList()
        while (pages < GROUP_MAX_PAGES) {
            val page = api.listGroupMessages(groupId, afterSeq, GROUP_PAGE_SIZE)
            collected = mergeGroupMessages(collected, page.messages)
            val lastSeq = page.messages.maxOfOrNull { it.seq } ?: break
            afterSeq = lastSeq
            pages += 1
            if (!page.hasMore) break
        }
        return collected
    }

    private fun startGroupPolling(groupId: String) {
        groupPollJob?.cancel()
        groupPollJob = viewModelScope.launch {
            while (isActive) {
                delay(GROUP_POLL_MS)
                if (!foreground) continue
                if (openGroupId != groupId) break
                val current = _state.value.groupChat
                val afterSeq = current.messages.lastOrNull()?.seq ?: 0
                try {
                    val page = api().listGroupMessages(groupId, afterSeq, GROUP_PAGE_SIZE)
                    if (page.messages.isEmpty()) continue
                    val botSpoke = page.messages.any { it.role == "bot" }
                    _state.value = _state.value.copy(
                        groupChat = _state.value.groupChat.copy(
                            messages = mergeGroupMessages(
                                _state.value.groupChat.messages,
                                page.messages,
                            ),
                            discussUntil = if (botSpoke) {
                                System.currentTimeMillis() + DISCUSS_WINDOW_MS
                            } else {
                                _state.value.groupChat.discussUntil
                            },
                        ),
                    )
                } catch (_: Exception) {
                    // 轮询失败静默：下一拍自动重试，不打断阅读。
                }
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    /** 清掉群聊页自己的错误/提示（顶部提示条到点收起时调用）。 */
    fun clearGroupMessages() {
        _state.value = _state.value.copy(
            groupChat = _state.value.groupChat.copy(notice = null, error = null),
        )
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
        groupListJob?.cancel()
        groupPollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val GROUP_POLL_MS = 2000L
        private const val GROUP_PAGE_SIZE = 200
        private const val GROUP_MAX_PAGES = 10
        /** 发言后「Bot 们正在讨论」的显示窗口；后端没有 epoch 状态查询接口。 */
        private const val DISCUSS_WINDOW_MS = 90_000L
    }
}

// ---------------------------------------------------------------- 映射

internal val Task.isFinished: Boolean
    get() = status.toV2TaskStatus().isFinished

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
    // 网络层异常的原生 message 往往是一串英文类名，直接摆在页面上用户读不懂
    is java.net.UnknownHostException -> "连不上服务器，请检查网络或地址"
    is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
    is java.net.ConnectException -> "无法连接服务器，请确认服务在运行"
    is java.net.NoRouteToHostException -> "网络不可达，请检查网络连接"
    is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，请检查服务器证书"
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
        val running = tasks.count {
            it.agentId == agent.id && it.status.toV2TaskStatus() == V2TaskStatus.RUNNING
        }
        V2Agent(
            id = agent.id,
            name = name,
            role = agent.spec?.role?.takeIf { it.isNotBlank() }
                ?: agent.state.takeIf { it.isNotBlank() }
                ?: "Agent",
            load = if (running > 0) 100 else 0,
            running = running,
            tint = agentTints[index % agentTints.size],
            nativeStreaming = agent.spec?.nativeStreaming ?: true,
        )
    }

internal fun Task.toV2Conversation(device: String): V2Conversation = V2Conversation(
    id = id,
    agentId = agentId.orEmpty(),
    title = botName?.takeIf { it.isNotBlank() } ?: firstLine(40),
    device = device,
    source = "desktop",
    updated = relativeTime(createdAt),
    unread = if (pendingDecision != null) 1 else 0,
    messages = emptyList(),
    botName = botName.orEmpty(),
    avatarShape = avatarShape,
    avatarColor = avatarColor,
)

internal fun Task.toV2Task(): V2Task {
    val mapped = status.toV2TaskStatus()
    return V2Task(
        id = id,
        title = firstLine(),
        agentId = agentId.orEmpty(),
        priority = when {
            priority <= 50 -> "P1"
            priority <= 100 -> "P2"
            else -> "P3"
        },
        status = mapped,
        // 服务端没有进度字段：运行中用 -1 表示「进度未知」。
        progress = when (mapped) {
            V2TaskStatus.RUNNING -> -1
            V2TaskStatus.DONE -> 100
            else -> 0
        },
        note = resultSummary(),
    )
}

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

// ---------------------------------------------------------------- 群聊

/** 按 seq 合并去重；轮询可能重复返回同一条消息。 */
internal fun mergeGroupMessages(
    prev: List<ChatGroupMessage>,
    incoming: List<ChatGroupMessage>,
): List<ChatGroupMessage> {
    if (incoming.isEmpty()) return prev
    val seen = prev.mapTo(HashSet()) { it.id }
    return (prev + incoming.filter { seen.add(it.id) }).sortedBy { it.seq }
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
