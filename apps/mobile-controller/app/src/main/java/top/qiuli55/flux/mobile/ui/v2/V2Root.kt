package top.qiuli55.flux.mobile.ui.v2

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.data.HandoffSummary
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.update.UpdateCenter
import top.qiuli55.flux.mobile.update.UpdateKind
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private sealed interface V2Route {
    data object Home : V2Route
    data object Decisions : V2Route
    data object Queue : V2Route
    data object Designs : V2Route
    data object DesignCanvas : V2Route

    /**
     * App 内嵌浏览器：对话里点开的网址一律走这里，返回时回到来源页。
     *
     * [html] 不为空时离线渲染这段单文件 HTML（设计稿网页预览）。
     */
    data class Browser(
        val url: String,
        val origin: V2Route,
        val html: String? = null,
        val title: String? = null,
    ) : V2Route

    /** 尚未创建任务的新对话（首条消息才落成任务）。 */
    data object Chat : V2Route

    data object Terminals : V2Route
    data class TaskDetail(val taskId: String) : V2Route
    data class TaskChat(val taskId: String) : V2Route
    data class Confirmation(val taskId: String) : V2Route
    data class Changes(val taskId: String) : V2Route
    data class Diff(val changeId: String, val taskId: String) : V2Route
    data class Run(val taskId: String) : V2Route
    data class Terminal(val sessionId: String) : V2Route
}

/** 平板左侧主导航的入口。 */
private enum class V2RailItem { CHATS, DECISIONS, QUEUE }

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun nowTime(): String = LocalTime.now().format(clockFormatter)

private fun nowMinute(): String = nowTime().take(5)

/**
 * v2 根容器：启动分流（登录 / 选设备 / 主界面）+ 路由 + 全局弹层。
 *
 * 手机全屏单栏；平板（≥600dp）改为左侧主导航 + 右侧内容区，弹层居中收窄。
 * 数据全部来自 [V2AppViewModel]（真实接口），演示数据只在未登录时兜底。
 */
@Composable
fun FluxV2Root() {
    val vm: V2AppViewModel = viewModel()
    val app by vm.state.collectAsState()
    val designVm: DesignStudioViewModel = viewModel()
    val designState by designVm.state.collectAsState()
    val update by UpdateCenter.state.collectAsState()

    var route by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var taskOrigin by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var chatOrigin by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var device by remember { mutableStateOf(ALL_DEVICES) }

    val logs = remember { mutableStateListOf<V2Log>() }
    var heartbeatIndex by remember { mutableIntStateOf(0) }

    var chat by remember { mutableStateOf<V2ChatState?>(null) }
    val chatMessages = remember { mutableStateListOf<V2Message>() }
    // 新建对话的附件是页面本地状态：离开草稿页就清空，避免残留一组没人发送的附件
    val draftAttachments = remember { mutableStateListOf<UploadedAttachment>() }
    var draftUploading by remember { mutableStateOf(false) }
    var draftAttachmentError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // 全局触感控制器：设置里的开关只改 enabled，不重建振动器
    val hapticsController = remember(context) { createV2Haptics(context) }

    var settingsOpen by remember { mutableStateOf(false) }
    var keyVaultOpen by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    var billingOpen by remember { mutableStateOf(false) }
    var logsOpen by remember { mutableStateOf(false) }
    var newChatOpen by remember { mutableStateOf(false) }
    var groupDetail by remember { mutableStateOf<V2Group?>(null) }
    var confirmDecision by remember { mutableStateOf<V2Decision?>(null) }
    var agentPickerOpen by remember { mutableStateOf(false) }
    var manualConnectOpen by remember { mutableStateOf(false) }
    // 画布有未保存改动时，返回先问一句：保存并退出 / 放弃 / 继续编辑
    var designExitOpen by remember { mutableStateOf(false) }
    // 画布来源：从设计列表进默认回列表；从对话卡片进则回原对话
    var designOrigin by remember { mutableStateOf<V2Route>(V2Route.Designs) }

    // 长按对话 / 对话页垃圾桶 → 删除面板；面板按 id 实时读任务状态（运行中要先停止）。
    var deleteConversationId by remember { mutableStateOf<String?>(null) }
    var deleteConversationError by remember { mutableStateOf<String?>(null) }

    var haptics by remember { mutableStateOf(true) }
    var reduceMotion by remember { mutableStateOf(false) }
    var decisionAlerts by remember { mutableStateOf(true) }
    var taskAlerts by remember { mutableStateOf(false) }
    var strict by remember { mutableStateOf(false) }
    SideEffect { hapticsController.enabled = haptics }

    LaunchedEffect(Unit) {
        if (logs.isEmpty()) {
            val time = nowTime()
            demoInitialLogs.forEach { logs.add(it.copy(time = time)) }
        }
    }

    // 退回登录 / 设备页时清掉上一轮的页面状态
    LaunchedEffect(app.boot) {
        if (app.boot != V2Boot.READY) {
            route = V2Route.Home
            chat = null
            chatMessages.clear()
            draftAttachments.clear()
            draftAttachmentError = null
        } else {
            UpdateCenter.onForeground()
        }
    }

    // 首页 / 决策 / 队列 / 设计列表：进入或切回时拉一次真实数据，不用干等 3 秒轮询
    LaunchedEffect(route) {
        if (app.boot == V2Boot.READY) {
            when (route) {
                V2Route.Home, V2Route.Decisions, V2Route.Queue -> vm.refresh(quiet = true)
                V2Route.Designs -> designVm.loadDesigns(quiet = true)
                else -> Unit
            }
        }
    }

    // 前后台：退到后台时暂停 3 秒轮询
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.setForeground(true)
                    vm.refresh(quiet = true)
                    UpdateCenter.onForeground()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    vm.setForeground(false)
                    UpdateCenter.onBackground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openChat(taskId: String, origin: V2Route) {
        chatOrigin = origin
        route = V2Route.TaskChat(taskId)
    }

    // 从对话里的设计稿卡片进原生画布：记住当前对话路由，返回时回到这段对话
    fun openDesignFromChat(designId: String) {
        designOrigin = route
        designVm.openDesign(designId)
        route = V2Route.DesignCanvas
    }

    // 对话里的链接一律交给 App 内的浏览器，不跳系统浏览器
    fun openInternalBrowser(url: String) {
        route = V2Route.Browser(url, route)
    }

    fun createChat(option: V2AgentOption) {
        val chatDevice = if (device == ALL_DEVICES) {
            V2Registry.devices.firstOrNull()?.name ?: "本机"
        } else {
            device
        }
        val firstModel = option.models.firstOrNull()
        chat = V2ChatState(
            conversationId = null,
            title = "新的对话",
            device = chatDevice,
            agentId = option.agent.id,
            model = firstModel?.model ?: V2Registry.models.firstOrNull() ?: "",
            modelKeyId = firstModel?.keyId,
        )
        chatMessages.clear()
        draftAttachments.clear()
        draftAttachmentError = null
        chatMessages.add(
            V2Message(
                V2MessageRole.SYSTEM,
                "描述你的需求，发送后会创建一个任务并交给 Agent 执行",
                nowMinute(),
            ),
        )
        newChatOpen = false
        route = V2Route.Chat
    }

    fun sendDraftMessage(text: String) {
        val current = chat ?: return
        if (draftUploading) return
        val attachmentIds = draftAttachments.map { it.id }
        // 只有附件没有文字也能发；后端任务描述必填，这里给 Agent 一句最小上下文
        val description = text.ifBlank { "请查看我附上的文件" }
        if (text.isBlank() && attachmentIds.isEmpty()) return
        if (attachmentIds.isEmpty()) {
            chatMessages.add(V2Message(V2MessageRole.USER, text, nowMinute()))
        } else {
            chatMessages.add(
                V2Message(
                    role = V2MessageRole.USER,
                    text = text,
                    time = nowMinute(),
                    kind = "attachments",
                    attachments = draftAttachments.map { V2MessageFile(it.name, it.size) },
                ),
            )
        }
        vm.createTask(
            description,
            current.agentId,
            current.decisionMode,
            current.permissionMode,
            current.model,
            current.modelKeyId,
            // 从旧对话接手：摘要随首条消息落库，新对话只带摘要、不搬旧消息
            summary = current.pendingSummary,
            summaryFrom = current.summaryFrom,
            attachmentIds = attachmentIds,
        ) { taskId ->
            // 发送成功才清空附件；失败时保留在页面本地状态里等用户重试
            draftAttachments.clear()
            draftAttachmentError = null
            // 新对话第一次发送 = 建任务并立即交给 Agent；只建不跑会让用户以为消息没发出去。
            vm.startTask(taskId)
            chat = current.copy(
                conversationId = taskId,
                title = text.take(40).ifBlank { "附件对话" },
                pendingSummary = null,
                summaryFrom = null,
            )
            chatMessages.clear()
            chatOrigin = V2Route.Home
            route = V2Route.TaskChat(taskId)
        }
    }

    /**
     * 上下文快满 → 新建并带摘要：沿用当前任务的项目 / Agent / 模型，
     * 只把摘要带进新对话。任务本身要等用户发出第一条消息才落成，
     * 避免在列表里留下一段没有用户意图的孤儿对话。
     */
    fun startHandoffChat(fromTaskId: String, summary: HandoffSummary) {
        val source = app.tasks.firstOrNull { it.id == fromTaskId }
        val chatDevice = if (device == ALL_DEVICES) {
            V2Registry.devices.firstOrNull()?.name ?: "本机"
        } else {
            device
        }
        chat = V2ChatState(
            conversationId = null,
            title = "延续 · " + (source?.firstLine(30) ?: "上一段对话"),
            device = chatDevice,
            agentId = source?.agentId.orEmpty(),
            model = source?.model.orEmpty(),
            modelKeyId = source?.apiKeyId?.takeIf { it.isNotBlank() },
            // 接手一段对话时沿用它的权限模式，避免用户刚切到 Plan 又悄悄回到正常模式
            permissionMode = source?.permissionMode ?: "normal",
            pendingSummary = summary.summary,
            summaryFrom = fromTaskId,
        )
        chatMessages.clear()
        draftAttachments.clear()
        draftAttachmentError = null
        chatMessages.add(
            V2Message(
                V2MessageRole.SYSTEM,
                "已带上上一段对话的摘要，接着说你想做的事",
                nowMinute(),
            ),
        )
        chatOrigin = V2Route.TaskChat(fromTaskId)
        route = V2Route.Chat
    }

    fun appendHeartbeat() {
        val heartbeat = demoHeartbeats[heartbeatIndex % demoHeartbeats.size]
        heartbeatIndex += 1
        logs.add(V2Log(nowTime(), heartbeat.first, heartbeat.second))
        while (logs.size > 240) {
            logs.removeAt(0)
        }
    }

    val hasBack = update.kind != UpdateKind.HIDDEN || deleteConversationId != null ||
        confirmDecision != null || settingsOpen || keyVaultOpen || logsOpen || newChatOpen ||
        groupDetail != null || agentPickerOpen || manualConnectOpen || route != V2Route.Home ||
        designExitOpen || memoryOpen

    BackHandler(enabled = hasBack) {
        when {
            designExitOpen -> designExitOpen = false
            update.kind != UpdateKind.HIDDEN -> UpdateCenter.dismiss()
            deleteConversationId != null -> {
                deleteConversationId = null
                deleteConversationError = null
            }
            manualConnectOpen -> manualConnectOpen = false
            confirmDecision != null -> confirmDecision = null
            agentPickerOpen -> agentPickerOpen = false
            groupDetail != null -> groupDetail = null
            newChatOpen -> newChatOpen = false
            keyVaultOpen -> keyVaultOpen = false
            memoryOpen -> memoryOpen = false
            logsOpen -> logsOpen = false
            settingsOpen -> settingsOpen = false
            else -> route = when (val current = route) {
                  is V2Route.TaskDetail -> taskOrigin
                  is V2Route.TaskChat -> chatOrigin
                  is V2Route.Confirmation -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Changes -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Diff -> V2Route.Changes(current.taskId)
                  is V2Route.Run -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Terminal -> V2Route.Terminals
                  is V2Route.Browser -> current.origin
                  V2Route.DesignCanvas -> {
                      if (designState.dirty) {
                          designExitOpen = true
                          V2Route.DesignCanvas
                      } else {
                          designVm.closeDesign()
                          designOrigin
                      }
                  }
                  else -> V2Route.Home
              }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (app.boot) {
            V2Boot.LOADING -> Box(
                modifier = Modifier.fillMaxSize().shellBackground(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(V2Colors.accent.copy(alpha = .10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_flux_mark),
                            contentDescription = null,
                            tint = V2Colors.cyan700,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LiveDot(V2Colors.cyan500)
                    Spacer(Modifier.height(12.dp))
                    Label("正在连接 Flux…", 12f, V2Colors.ink(.45f))
                }
            }

            V2Boot.LOGIN -> V2LoginScreen(
                onSignedIn = { vm.onSignedIn(it) },
                onManualConnected = { vm.onManualConnected() },
                hint = app.bootError,
            )

            V2Boot.DEVICES -> V2DeviceScreen(
                state = app,
                onReload = { vm.loadDevices() },
                onSelect = { vm.selectDevice(it) },
                onLogout = { vm.logout() },
                onManualConnect = { manualConnectOpen = true },
            )

            V2Boot.READY -> BoxWithConstraints(Modifier.fillMaxSize().shellBackground()) {
                val tablet = maxWidth >= 600.dp
                val insets = Modifier.statusBarsPadding().navigationBarsPadding()
                val processedCount = app.tasks.sumOf { task ->
                    task.decisions.count { it.status != null && it.status != "pending" }
                }

                val renderContent: @Composable (V2Route, Dp, Boolean) -> Unit = { pageRoute, extraTop, showBack ->
                    when (val current = pageRoute) {
                        V2Route.Home -> V2HomeScreen(
                            device = device,
                            onDeviceChange = { device = it },
                            conversations = app.conversations,
                            groups = app.groups,
                            pendingCount = app.pendingCount,
                            runningCount = app.runningCount,
                            billingHeadline = app.billing?.headline ?: "暂无额度数据",
                            extraTop = extraTop,
                            onOpenDecisions = { route = V2Route.Decisions },
                            onOpenQueue = { route = V2Route.Queue },
                            onOpenBilling = {
                                billingOpen = true
                                vm.loadBilling()
                            },
                            onOpenDesigns = { route = V2Route.Designs },
                            onOpenConversation = { openChat(it.id, V2Route.Home) },
                            onDeleteConversation = {
                                deleteConversationError = null
                                deleteConversationId = it.id
                            },
                            onOpenGroup = { groupDetail = it },
                            onOpenSettings = { settingsOpen = true },
                            onNewChat = { newChatOpen = true },
                            onRefresh = { vm.refresh(quiet = true) },
                        )

                        V2Route.Decisions -> V2DecisionScreen(
                            decisions = app.decisions,
                            runningCount = app.runningCount,
                            processedCount = processedCount,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onOpenLogs = { logsOpen = true },
                            onRequestApprove = { decision ->
                                if (decision.risk == V2Risk.HIGH) {
                                    confirmDecision = decision
                                } else {
                                    vm.approveDecision(decision.taskId, decision.id)
                                }
                            },
                            onReject = { decision ->
                                vm.rejectDecision(decision.taskId, decision.id)
                            },
                        )

                        V2Route.Queue -> V2QueueScreen(
                            tasks = app.queueTasks,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onOpenLogs = { logsOpen = true },
                            onClearDone = { vm.dismissFinished() },
                            onCancel = { vm.cancelTask(it.id) },
                            onRetry = { vm.startTask(it.id) },
                            onOpenTask = { task ->
                                taskOrigin = V2Route.Queue
                                route = V2Route.TaskDetail(task.id)
                            },
                            onRequestDelete = { task ->
                                deleteConversationError = null
                                deleteConversationId = task.id
                            },
                        )

                        V2Route.Designs -> DesignStudioListScreen(
                            state = designState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onReload = { designVm.loadDesigns() },
                            onOpen = { designId ->
                                designOrigin = V2Route.Designs
                                designVm.openDesign(designId)
                                route = V2Route.DesignCanvas
                            },
                            onCreate = {
                                designVm.createDesign(it) {
                                    designOrigin = V2Route.Designs
                                    route = V2Route.DesignCanvas
                                }
                            },
                            onRename = { designId, name -> designVm.renameDesign(designId, name) },
                            onDelete = { designId -> designVm.deleteDesign(designId) },
                            onImport = { name, size, openStream ->
                                designVm.importDesign(name, size, openStream) {
                                    designOrigin = V2Route.Designs
                                    route = V2Route.DesignCanvas
                                }
                            },
                            onExport = { designId, onReady ->
                                designVm.exportDesignSvg(designId, onReady = onReady)
                            },
                            onDismissMessages = { designVm.clearMessages() },
                        )

                        V2Route.DesignCanvas -> DesignCanvasScreen(
                            state = designState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = {
                                if (designState.dirty) {
                                    designExitOpen = true
                                } else {
                                    designVm.closeDesign()
                                    route = designOrigin
                                }
                            },
                            onSelectNode = { designVm.selectNode(it) },
                            onSelectScreen = { designVm.selectScreen(it) },
                            onToggleMultiSelect = { designVm.toggleMultiSelect() },
                            onSetAiScope = { designVm.setAiScope(it) },
                            onUndo = { designVm.undo() },
                            onBeginDrag = { designVm.beginNodeDrag(it) },
                            onDrag = { ids, dx, dy -> designVm.dragNodes(ids, dx, dy) },
                            onMoveLayer = { id, up -> designVm.moveLayer(id, up) },
                            onDeleteLayer = { designVm.deleteLayer(it) },
                            onDuplicateLayer = { designVm.duplicateLayer(it) },
                            onRenameLayer = { id, name -> designVm.renameLayer(id, name) },
                            onLoadHistory = { designVm.loadRevisions() },
                            onRevert = { designVm.revertTo(it) },
                            onSave = { designVm.save() },
                            onWebPreview = {
                                val designId = designState.document?.id
                                if (designId != null) {
                                    designVm.loadWebPreview(designId) { preview ->
                                        route = V2Route.Browser(
                                            url = "",
                                            origin = V2Route.DesignCanvas,
                                            html = preview.html,
                                            title = preview.name,
                                        )
                                    }
                                }
                            },
                            onSetText = { designVm.setNodeText(it) },
                            onSetFill = { designVm.setNodeFill(it) },
                            onSetTextColor = { designVm.setNodeTextColor(it) },
                            onSetRadius = { designVm.setNodeRadius(it) },
                            onSetOpacity = { designVm.setNodeOpacity(it) },
                            onAskAi = { designVm.askAi(it) },
                            onDismissMessages = { designVm.clearMessages() },
                        )

                          is V2Route.Browser -> {
                            V2BrowserScreen(
                                url = current.url,
                                extraTop = extraTop,
                                showBack = showBack,
                                html = current.html,
                                title = current.title,
                                onBack = { route = current.origin },
                            )
                        }

                        V2Route.Chat -> {
                            val active = chat
                            if (active != null) {
                                V2ChatScreen(
                                    chat = active,
                                    messages = chatMessages,
                                    extraTop = extraTop,
                                    showBack = showBack,
                                    onBack = {
                                        chat = null
                                        chatMessages.clear()
                                        draftAttachments.clear()
                                        draftAttachmentError = null
                                        route = V2Route.Home
                                    },
                                    onOpenLogs = { logsOpen = true },
                                    onDelete = {
                                        chat = null
                                        chatMessages.clear()
                                        draftAttachments.clear()
                                        draftAttachmentError = null
                                        route = V2Route.Home
                                    },
                                    onOpenAgentPicker = { agentPickerOpen = true },
                                    onSend = { sendDraftMessage(it) },
                                    attachments = draftAttachments,
                                    uploading = draftUploading,
                                    onAddAttachments = { uris ->
                                        val remaining = UploadedAttachment.MAX_FILES - draftAttachments.size
                                        if (remaining <= 0) {
                                            Toast.makeText(
                                                context,
                                                "一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            if (uris.size > remaining) {
                                                Toast.makeText(
                                                    context,
                                                    "最多 5 个附件，已忽略多选的 ${uris.size - remaining} 个",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            draftAttachmentError = null
                                            draftUploading = true
                                            vm.uploadDraftAttachments(
                                                uris = uris.take(remaining),
                                                resolver = context.contentResolver,
                                                onUploaded = { draftAttachments.add(it) },
                                                onError = { draftAttachmentError = it },
                                                onFinished = { draftUploading = false },
                                            )
                                        }
                                    },
                                    onRemoveAttachment = { id ->
                                        draftAttachments.removeAll { it.id == id }
                                    },
                                    sending = app.busyAction == "create" || draftUploading,
                                    notice = draftAttachmentError ?: app.error,
                                    onDismissNotice = {
                                        draftAttachmentError = null
                                        vm.clearMessages()
                                    },
                                    // 新建对话不是从设备接续的历史会话，不需要接续横幅。
                                    showHandoff = false,
                                    onDecisionModeChange = { mode -> chat = chat?.copy(decisionMode = mode) },
                                    // 新建对话还没有任务：模式只记在本地，发首条消息时随建任务请求带上
                                    onPermissionModeChange = { mode -> chat = chat?.copy(permissionMode = mode) },
                                    onOpenUrl = { openInternalBrowser(it) },
                                    onOpenDesign = { openDesignFromChat(it) },
                                )
                            }
                        }

                        is V2Route.TaskDetail -> V2TaskDetailScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = taskOrigin },
                            onOpenConfirmation = { route = V2Route.Confirmation(current.taskId) },
                            onOpenChat = { openChat(current.taskId, V2Route.TaskDetail(current.taskId)) },
                            onOpenChanges = { route = V2Route.Changes(current.taskId) },
                            onOpenRun = { route = V2Route.Run(current.taskId) },
                        )

                        is V2Route.TaskChat -> V2TaskChatScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = {
                                route = chatOrigin
                                if (chatOrigin == V2Route.Home) {
                                    chat = null
                                    chatMessages.clear()
                                }
                            },
                            onOpenLogs = { logsOpen = true },
                            onRequestDelete = {
                                deleteConversationError = null
                                deleteConversationId = current.taskId
                            },
                            onHandoffReady = { summary ->
                                startHandoffChat(current.taskId, summary)
                            },
                            onOpenDesign = { openDesignFromChat(it) },
                            onOpenUrl = { openInternalBrowser(it) },
                        )

                        is V2Route.Confirmation -> V2ConfirmationScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.TaskDetail(current.taskId) },
                            onOpenDecisionCenter = { route = V2Route.Decisions },
                        )

                        is V2Route.Changes -> V2ChangesScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.TaskDetail(current.taskId) },
                            onOpenChange = { changeId ->
                                route = V2Route.Diff(changeId, current.taskId)
                            },
                        )

                        is V2Route.Diff -> V2DiffScreen(
                            changeId = current.changeId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Changes(current.taskId) },
                        )

                        is V2Route.Run -> V2RunScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.TaskDetail(current.taskId) },
                        )

                        V2Route.Terminals -> V2TerminalListScreen(
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onOpenSession = { route = V2Route.Terminal(it) },
                        )

                        is V2Route.Terminal -> V2TerminalSessionScreen(
                            sessionId = current.sessionId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Terminals },
                        )
                    }
                }

                CompositionLocalProvider(
                    LocalSheetMaxWidth provides if (tablet) 560.dp else null,
                    LocalV2Haptics provides hapticsController,
                    LocalReduceMotion provides reduceMotion,
                ) {
                    if (tablet) {
                        Row(Modifier.fillMaxSize().then(insets)) {
                            V2NavRail(
                                selected = when (route) {
                                    V2Route.Decisions -> V2RailItem.DECISIONS
                                    V2Route.Queue -> V2RailItem.QUEUE
                                    else -> V2RailItem.CHATS
                                },
                                pendingCount = app.pendingCount,
                                runningCount = app.runningCount,
                                onSelect = { item ->
                                    route = when (item) {
                                        V2RailItem.CHATS -> V2Route.Home
                                        V2RailItem.DECISIONS -> V2Route.Decisions
                                        V2RailItem.QUEUE -> V2Route.Queue
                                    }
                                },
                                onOpenLogs = { logsOpen = true },
                                onOpenSettings = { settingsOpen = true },
                            )
                            Box(Modifier.width(1.dp).fillMaxHeight().background(V2Colors.ink(.07f)))
                            Box(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight()) {
                                    V2PageTransition(
                                        target = route,
                                        depthOf = { v2RouteDepth(it as V2Route) },
                                    ) { page ->
                                        renderContent(page as V2Route, 0.dp, false)
                                    }
                                }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().then(insets)) {
                            V2PageTransition(
                                target = route,
                                depthOf = { v2RouteDepth(it as V2Route) },
                            ) { page ->
                                renderContent(page as V2Route, 0.dp, true)
                            }
                        }
                    }

                    val flash = app.error ?: app.notice
                    // 提示条到点自动收起：错误留久一点，成功提示更快消失，避免长期糊在顶栏。
                    LaunchedEffect(flash) {
                        if (flash != null) {
                            delay(if (app.error != null) 5000L else 2600L)
                            vm.clearMessages()
                        }
                    }
                    if (flash != null) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (app.error != null) V2Colors.rose else V2Colors.emerald700)
                                .clickable { vm.clearMessages() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Label(flash, 10.5f, Color.White)
                        }
                    }

                    if (settingsOpen) {
                        SettingsSheet(
                            account = app.account,
                            deviceName = app.deviceLabel,
                            deviceCount = if (app.account != null) app.devices.size else 1,
                            haptics = haptics,
                            reduceMotion = reduceMotion,
                            decisionAlerts = decisionAlerts,
                            taskAlerts = taskAlerts,
                            strict = strict,
                            onHaptics = { haptics = it },
                            onReduceMotion = { reduceMotion = it },
                            onDecisionAlerts = { decisionAlerts = it },
                            onTaskAlerts = { taskAlerts = it },
                            onStrict = { strict = it },
                            onOpenDevices = {
                                settingsOpen = false
                                vm.showDevices()
                            },
                            onOpenTerminals = {
                                settingsOpen = false
                                route = V2Route.Terminals
                            },
                            onOpenKeyVault = {
                                settingsOpen = false
                                keyVaultOpen = true
                                vm.loadKeys()
                            },
                            onOpenMemories = {
                                settingsOpen = false
                                memoryOpen = true
                                vm.loadMemories()
                            },
                            codexAccount = app.codexAccount,
                            codexLogin = app.codexLogin,
                            codexLoading = app.codexLoading,
                            onCodexLogin = { vm.startCodexLogin() },
                            onCodexLogout = { vm.logoutCodexAccount() },
                            onRefreshCodex = { vm.loadCodexAccount() },
                            onOpenCodexUrl = { url ->
                                settingsOpen = false
                                openInternalBrowser(url)
                            },
                            onManualConnect = {
                                settingsOpen = false
                                manualConnectOpen = true
                            },
                            onLogout = {
                                settingsOpen = false
                                vm.logout()
                            },
                            updateHint = if (update.checking) "正在检查…" else update.hint,
                            updateBadge = update.kind != UpdateKind.HIDDEN,
                            onCheckUpdate = { UpdateCenter.checkManually() },
                            onDismiss = { settingsOpen = false },
                        )
                    }

                    if (keyVaultOpen) {
                        KeyVaultSheet(
                            keys = app.keys,
                            loading = app.keysLoading,
                            onRefresh = { vm.loadKeys() },
                            onSave = { draft, onResult ->
                                vm.saveKey(
                                    keyId = draft.keyId,
                                    name = draft.name,
                                    provider = draft.provider,
                                    baseUrl = draft.baseUrl,
                                    apiKey = draft.apiKey.takeIf { it.isNotBlank() },
                                    models = draft.models,
                                    wireApi = draft.wireApi,
                                    enabled = draft.enabled,
                                    onResult = onResult,
                                )
                            },
                            onToggle = { entry, value, onResult ->
                                vm.setKeyEnabled(entry.id, value, onResult)
                            },
                            onDelete = { entry, onResult -> vm.deleteKey(entry.id, onResult) },
                            onDismiss = { keyVaultOpen = false },
                        )
                    }

                    if (memoryOpen) {
                        MemorySheet(
                            memories = app.memories,
                            loading = app.memoriesLoading,
                            saving = app.memorySaving,
                            autoCapture = app.memoryAutoCapture,
                            cloudReady = app.memoryCloudReady,
                            cloudError = app.memoryCloudError,
                            onRefresh = { vm.loadMemories(force = true) },
                            onSync = { onResult -> vm.syncMemories(onResult) },
                            onToggleAuto = { vm.setMemoryAutoCapture(it) },
                            onAdd = { content, onResult -> vm.addMemory(content, onResult) },
                            onUpdate = { id, content, onResult ->
                                vm.updateMemory(id, content, onResult)
                            },
                            onDelete = { id, onResult -> vm.deleteMemory(id, onResult) },
                            onDismiss = { memoryOpen = false },
                        )
                    }

                    if (billingOpen) {
                        BillingSheet(
                            overview = app.billing,
                            prices = app.billingPrices,
                            loading = app.billingLoading,
                            error = app.billingError,
                            onRefresh = { vm.loadBilling() },
                            onSync = { vm.loadBilling(sync = true) },
                            onDismiss = { billingOpen = false },
                        )
                    }

                    if (logsOpen) {
                        LogsSheet(
                            logs = logs,
                            onAppendHeartbeat = { appendHeartbeat() },
                            onClear = { logs.clear() },
                            onDismiss = { logsOpen = false },
                        )
                    }

                    if (newChatOpen) {
                        NewChatSheet(
                            device = device,
                            options = agentsForDevice(device),
                            onPick = { createChat(it) },
                            onDismiss = { newChatOpen = false },
                        )
                    }

                    groupDetail?.let { group ->
                        MultiGroupSheet(
                            group = group,
                            onOpenQueue = {
                                groupDetail = null
                                route = V2Route.Queue
                            },
                            onDismiss = { groupDetail = null },
                        )
                    }

                    confirmDecision?.let { decision ->
                        ConfirmSheet(
                            decision = decision,
                            strict = strict,
                            onConfirm = {
                                vm.approveDecision(decision.taskId, decision.id)
                                confirmDecision = null
                            },
                            onDismiss = { confirmDecision = null },
                        )
                    }

                    val currentChat = chat
                    if (agentPickerOpen && currentChat != null) {
                        AgentPickerSheet(
                            chat = currentChat,
                            options = agentsForDevice(currentChat.device),
                            onPick = { agentId, model ->
                                chat = currentChat.copy(
                                    agentId = agentId,
                                    model = model.model,
                                    modelKeyId = model.keyId,
                                )
                                agentPickerOpen = false
                            },
                            onDismiss = { agentPickerOpen = false },
                        )
                    }

                    deleteConversationId?.let { deleteId ->
                        val target = app.tasks.firstOrNull { it.id == deleteId }
                        ConversationDeleteSheet(
                            title = target?.description?.lineSequence()?.firstOrNull()?.take(40)
                                ?.takeIf { it.isNotBlank() } ?: "这段对话",
                            running = target?.status == "running" ||
                                target?.status == "waiting_for_user_decision",
                            deleting = app.busyAction == "delete:$deleteId",
                            stopping = app.busyAction == "cancel:$deleteId",
                            error = deleteConversationError,
                            onStop = { vm.cancelTask(deleteId) },
                            onDelete = {
                                vm.deleteConversation(deleteId) { error ->
                                    if (error.isEmpty()) {
                                        deleteConversationId = null
                                        deleteConversationError = null
                                        val chatRoute = route as? V2Route.TaskChat
                                        if (chatRoute?.taskId == deleteId) {
                                            chat = null
                                            chatMessages.clear()
                                            chatOrigin = V2Route.Home
                                            route = V2Route.Home
                                        }
                                    } else {
                                        deleteConversationError = error
                                    }
                                }
                            },
                            onDismiss = {
                                deleteConversationId = null
                                deleteConversationError = null
                            },
                        )
                    }

                    if (designExitOpen && route == V2Route.DesignCanvas) {
                        DesignExitSheet(
                            saving = designState.saving,
                            onSaveAndExit = {
                                designVm.save {
                                    designExitOpen = false
                                    designVm.closeDesign()
                                    route = designOrigin
                                }
                            },
                            onDiscard = {
                                designExitOpen = false
                                designVm.closeDesign()
                                route = designOrigin
                            },
                            onDismiss = { designExitOpen = false },
                        )
                    }

                    // 更新弹层放最后，保证盖在其他面板之上。
                    if (update.kind != UpdateKind.HIDDEN) {
                        UpdateSheet(
                            state = update,
                            onDownload = { UpdateCenter.download() },
                            onInstall = { UpdateCenter.installApk() },
                            onOpenPermission = { UpdateCenter.openInstallPermission() },
                            onCancelDownload = { UpdateCenter.cancelDownload() },
                            onLater = { UpdateCenter.later() },
                            onSkip = { UpdateCenter.skipVersion() },
                            onDismiss = { UpdateCenter.dismiss() },
                        )
                    }
                }
            }
        }

        if (manualConnectOpen) {
            ManualConnectSheet(
                onDismiss = { manualConnectOpen = false },
                onConnected = {
                    manualConnectOpen = false
                    vm.onManualConnected()
                },
            )
        }
    }
}

@Composable
private fun V2NavRail(
    selected: V2RailItem,
    pendingCount: Int,
    runningCount: Int,
    onSelect: (V2RailItem) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(V2Colors.surface(.52f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(V2Colors.accent.copy(alpha = .12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flux_mark),
                contentDescription = null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(18.dp))

        RailButton(Icons.Outlined.AddComment, "对话", selected == V2RailItem.CHATS) {
            onSelect(V2RailItem.CHATS)
        }
        Spacer(Modifier.height(4.dp))
        RailButton(
            icon = Icons.Outlined.Schedule,
            label = "决策",
            selected = selected == V2RailItem.DECISIONS,
            badge = pendingCount,
            badgeColor = V2Colors.amber,
        ) { onSelect(V2RailItem.DECISIONS) }
        Spacer(Modifier.height(4.dp))
        RailButton(
            icon = Icons.Outlined.List,
            label = "任务",
            selected = selected == V2RailItem.QUEUE,
            badge = runningCount,
            badgeColor = V2Colors.cyan500,
        ) { onSelect(V2RailItem.QUEUE) }

        Spacer(Modifier.weight(1f))

        RailButton(Icons.Outlined.ShowChart, "日志", selected = false, onClick = onOpenLogs)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(V2Colors.avatarBlue)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "打开账户与设置",
                tint = V2Colors.avatarInk,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int? = null,
    badgeColor: Color = V2Colors.cyan500,
    onClick: () -> Unit,
) {
    Box {
        Column(
            Modifier
                .width(72.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) V2Colors.cyan500.copy(alpha = .12f) else Color.Transparent)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) V2Colors.cyan700 else V2Colors.ink(.45f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(3.dp))
            Label(
                label,
                9.5f,
                if (selected) V2Colors.cyan800 else V2Colors.ink(.38f),
                if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
        if (badge != null && badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 3.dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeColor)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label("$badge", 9f, Color.White, FontWeight.Medium, maxLines = 1)
            }
        }
    }
}


/** 页面层级：用于判断转场方向（前进 / 返回）。 */
private fun v2RouteDepth(route: V2Route): Int = when (route) {
    V2Route.Home -> 0
    V2Route.Decisions, V2Route.Queue, V2Route.Designs, V2Route.Terminals, V2Route.Chat -> 1
    is V2Route.Browser -> v2RouteDepth(route.origin) + 1
    is V2Route.TaskDetail -> 2
    V2Route.DesignCanvas -> 2
    is V2Route.TaskChat -> 3
    is V2Route.Confirmation -> 3
    is V2Route.Changes -> 3
    is V2Route.Run -> 3
    is V2Route.Terminal -> 2
    is V2Route.Diff -> 4
}
