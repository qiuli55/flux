package top.qiuli55.flux.mobile.ui.v2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Publish
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.BuildConfig
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.GitHubLinkBus
import top.qiuli55.flux.mobile.data.NotificationPrefs
import top.qiuli55.flux.mobile.data.HandoffSummary
import top.qiuli55.flux.mobile.data.McpConnector
import top.qiuli55.flux.mobile.data.ShareIntakeBus
import top.qiuli55.flux.mobile.notify.AlertKind
import top.qiuli55.flux.mobile.notify.AppVisibility
import top.qiuli55.flux.mobile.notify.FluxNotificationCenter
import top.qiuli55.flux.mobile.notify.NotificationChannels
import top.qiuli55.flux.mobile.notify.NotificationStatus
import top.qiuli55.flux.mobile.notify.OpenTaskBus
import top.qiuli55.flux.mobile.notify.OpenTaskReason
import top.qiuli55.flux.mobile.notify.PendingOpenTask
import top.qiuli55.flux.mobile.notify.TaskAlertService
import top.qiuli55.flux.mobile.notify.notificationProblem
import top.qiuli55.flux.mobile.notify.readNotificationStatus
import top.qiuli55.flux.mobile.update.UpdateCenter
import top.qiuli55.flux.mobile.update.UpdateKind
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

internal sealed interface V2Route {
    data object Home : V2Route
    data object Assistant : V2Route
    data object Designs : V2Route
    /** 一级页面：对话产出的成品（设计稿 / 页面 / 文档）。 */
    data object Artifacts : V2Route
    data class ArtifactDetail(val artifactId: String) : V2Route
    /** 「我的」：账号与全部管理入口（设备、终端、密钥、额度、记忆、偏好）。 */
    data object Profile : V2Route
    /** 「我的」二级页：连接器（GitHub + 自定义 MCP）。 */
    data object Connectors : V2Route
    /** 「我的」二级页：统一订阅登录入口（ChatGPT / Claude Code）。 */
    data object Subscriptions : V2Route
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

/** 平板左侧主导航的入口（与手机底部三个 Tab 一致）。 */
private enum class V2RailItem { CHATS, ARTIFACTS, PROFILE }

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
    val artifactsVm: ArtifactsViewModel = viewModel()
    val artifactsState by artifactsVm.state.collectAsState()
    val assistantVm: V2AssistantViewModel = viewModel()
    val assistantState by assistantVm.state.collectAsState()
    val update by UpdateCenter.state.collectAsState()

    var route by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var taskOrigin by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var chatOrigin by remember { mutableStateOf<V2Route>(V2Route.Home) }
    var device by remember { mutableStateOf(ALL_DEVICES) }

    // 对话链接、Codex / GitHub 授权页一律交给 App 内的浏览器，不跳系统浏览器
    fun openInternalBrowser(url: String) {
        route = V2Route.Browser(url, route)
    }

    var chat by remember { mutableStateOf<V2ChatState?>(null) }
    val chatMessages = remember { mutableStateListOf<V2Message>() }
    // 新建对话的附件草稿：带本地 Uri 与上传状态；离开草稿页就清空。
    val rootScope = rememberCoroutineScope()
    var draftAttachmentError by remember { mutableStateOf<String?>(null) }
    val draftAttachments = remember {
        ComposerAttachmentController(
            scope = rootScope,
            upload = { uri, picked, resolver ->
                vm.uploadPickedFile(uri, picked, resolver)
            },
            onProblem = { message -> draftAttachmentError = message },
        )
    }
    val draftAttachmentDrafts by draftAttachments.drafts.collectAsState()
    // 系统分享注入：新对话与已有对话各一份，消费后清空，避免旋转重复落文字。
    val seedCounter = remember { AtomicLong(0L) }
    var chatSeed by remember { mutableStateOf<ComposerSeed?>(null) }
    var taskSeed by remember { mutableStateOf<Pair<String, ComposerSeed>?>(null) }
    var shareQueue by remember { mutableStateOf(ShareIntakeState()) }
    val context = LocalContext.current
    // 全局触感控制器：设置里的开关只改 enabled，不重建振动器
    val hapticsController = remember(context) { createV2Haptics(context) }

    // GitHub 授权码回跳（flux://github-link）：交给后端换票，成功后刷新账号与仓库。
    LaunchedEffect(Unit) {
        GitHubLinkBus.events.collect { event ->
            vm.completeGitHubLogin(event.code, event.state)
            // 内置浏览器完成回跳后回到来源页，不要让用户停在授权页。
            val current = route
            if (current is V2Route.Browser) route = current.origin
        }
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var keyVaultOpen by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    var billingOpen by remember { mutableStateOf(false) }
    var customConnectorOpen by remember { mutableStateOf(false) }
    var githubSheetOpen by remember { mutableStateOf(false) }
    var connectorDetail by remember { mutableStateOf<McpConnector?>(null) }
    var newChatOpen by remember { mutableStateOf(false) }
    var agentPickerOpen by remember { mutableStateOf(false) }
    var manualConnectOpen by remember { mutableStateOf(false) }

    // 授权码登录第一步拿到的 GitHub 授权页也走内置浏览器；flux://github-link 回跳由
    // shouldOverrideUrlLoading 交回系统，再由 MainActivity 分发给 GitHubLinkBus。
    LaunchedEffect(app.githubAuthorizeUrl) {
        val url = app.githubAuthorizeUrl ?: return@LaunchedEffect
        settingsOpen = false
        openInternalBrowser(url)
        vm.clearGitHubAuthorizeUrl()
    }

    // 画布有未保存改动时，返回先问一句：保存并退出 / 放弃 / 继续编辑
    var designExitOpen by remember { mutableStateOf(false) }
    // 画布来源：从设计列表进默认回列表；从对话卡片进则回原对话
    var designOrigin by remember { mutableStateOf<V2Route>(V2Route.Designs) }
    // 产物详情来源：从 Artifacts 列表进默认回列表；从对话卡片进则回原对话
    var artifactOrigin by remember { mutableStateOf<V2Route>(V2Route.Artifacts) }

    // 长按对话 / 对话页垃圾桶 → 删除面板；面板按 id 实时读任务状态（运行中要先停止）。
    var deleteConversationId by remember { mutableStateOf<String?>(null) }
    var deleteConversationError by remember { mutableStateOf<String?>(null) }

    // 本机提醒与动效偏好：落到 DataStore，进程被杀再打开仍然保留。
    var notifPrefs by remember { mutableStateOf(NotificationPrefs()) }
    var notifPrefsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        notifPrefs = FluxEnv.settings.notificationPrefs.first()
        notifPrefsReady = true
    }
    LaunchedEffect(notifPrefs, notifPrefsReady) {
        if (notifPrefsReady) FluxEnv.settings.saveNotificationPrefs(notifPrefs)
    }
    var strict by remember { mutableStateOf(false) }
    SideEffect { hapticsController.enabled = notifPrefs.haptics }

    // 通知权限 / 渠道状态：设置页据此说明「为什么现在收不到提醒」。
    // API 33+ 先按「未授权」起步，等真实检查回来再决定是否启动前台服务，
    // 避免无权限时短暂启动一个看不见的通知服务。
    var notificationStatus by remember {
        mutableStateOf(
            NotificationStatus(
                permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
            ),
        )
    }
    fun refreshNotificationStatus() {
        NotificationChannels.ensure(context)
        notificationStatus = readNotificationStatus(context)
    }
    LaunchedEffect(Unit) { refreshNotificationStatus() }
    val notificationError by FluxNotificationCenter.lastError.collectAsState()

    fun openNotificationSettings() {
        val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(appIntent) }
            .recoverCatching { context.startActivity(fallback) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshNotificationStatus()
        if (!granted) {
            Toast.makeText(
                context,
                "系统通知权限未开启，提醒暂时不会出现。可在设置里点「去系统设置开启」。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        refreshNotificationStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val problem = notificationProblem(
            notificationStatus,
            notifPrefs.decisionAlerts,
            notifPrefs.taskAlerts,
        )
        if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
    }

    fun updateAlerts(decision: Boolean? = null, task: Boolean? = null) {
        val wasEnabled = notifPrefs.decisionAlerts || notifPrefs.taskAlerts
        notifPrefs = notifPrefs.copy(
            decisionAlerts = decision ?: notifPrefs.decisionAlerts,
            taskAlerts = task ?: notifPrefs.taskAlerts,
        )
        val nowEnabled = notifPrefs.decisionAlerts || notifPrefs.taskAlerts
        // 打开开关时立即检查权限 / 渠道，不能假装已经能提醒。
        if (nowEnabled && (!wasEnabled || decision == true || task == true)) {
            requestNotificationPermissionIfNeeded()
        } else {
            refreshNotificationStatus()
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

    // 首页 / 设计列表：进入或切回时拉一次真实数据，不用干等 3 秒轮询
    LaunchedEffect(route) {
        if (app.boot == V2Boot.READY) {
            when (route) {
                V2Route.Home -> {
                    vm.refresh(quiet = true)
                }
                V2Route.Assistant -> assistantVm.load(quiet = true)
                V2Route.Artifacts -> artifactsVm.load(quiet = true)
                V2Route.Designs -> designVm.loadDesigns(quiet = true)
                V2Route.Profile -> {
                    vm.loadMemories()
                    // 「我的」上的入口会显示已连接数量：进页时顺手刷新一次。
                    vm.loadCodexAccount()
                    vm.loadClaudeAccount()
                    vm.loadConnectors(quiet = true)
                }
                V2Route.Connectors -> {
                    vm.loadConnectors()
                    vm.loadGitHubAccount()
                }
                V2Route.Subscriptions -> {
                    vm.loadCodexAccount()
                    vm.loadClaudeAccount()
                }
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
                    AppVisibility.foreground = true
                    refreshNotificationStatus()
                    vm.refresh(quiet = true)
                    UpdateCenter.onForeground()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    vm.setForeground(false)
                    AppVisibility.foreground = false
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

    // 从对话里的产物卡片进详情：记住当前对话路由，返回时回到这段对话
    fun openArtifactFromChat(artifactId: String) {
        artifactOrigin = route
        artifactsVm.open(artifactId)
        route = V2Route.ArtifactDetail(artifactId)
    }

    fun createChat(option: V2AgentOption, assistant: Boolean = false) {
        val chatDevice = if (device == ALL_DEVICES) {
            V2Registry.devices.firstOrNull()?.name ?: "本机"
        } else {
            device
        }
        val firstModel = option.models.firstOrNull()
        chat = V2ChatState(
            conversationId = null,
            title = if (assistant) "助理" else "新的对话",
            device = chatDevice,
            agentId = option.agent.id,
            model = firstModel?.model ?: V2Registry.models.firstOrNull() ?: "",
            modelKeyId = firstModel?.keyId,
            isAssistant = assistant,
        )
        chatMessages.clear()
        draftAttachments.clear()
        draftAttachmentError = null
        chatMessages.add(
            V2Message(
                V2MessageRole.SYSTEM,
                if (assistant) {
                    "描述你想梳理的事，助理会读取你的全部对话并汇总项目进度"
                } else {
                    "描述你的需求，发送后会创建一个任务并交给 Agent 执行"
                },
                nowMinute(),
            ),
        )
        newChatOpen = false
        chatSeed = null
        route = V2Route.Chat
    }

    /** 首页/侧栏的助理入口：优先回到最近一段助理对话，没有就新开一段。 */
    fun openAssistant() {
        val recent = app.tasks.firstOrNull { it.origin == "assistant" }
        if (recent != null) {
            openChat(recent.id, V2Route.Home)
            return
        }
        val chatDevice = if (device == ALL_DEVICES) {
            V2Registry.devices.firstOrNull()?.name ?: "本机"
        } else {
            device
        }
        createChat(agentsForDevice(chatDevice).first(), assistant = true)
    }

    fun sendDraftMessage(text: String) {
        Log.i(
            FLUX_UX_LOG_TAG,
            "sendDraftMessage invoked textLen=${text.length} " +
                "attachments=${draftAttachmentDrafts.size}",
        )
        val current = chat ?: run {
            Log.w(FLUX_UX_LOG_TAG, "send ignored: no active chat")
            return
        }
        // 失败 / 上传中的草稿不能跳过：先让用户重试或移除。
        val blocker = attachmentSendBlocker(draftAttachmentDrafts)
        if (blocker != null) {
            Toast.makeText(context, blocker, Toast.LENGTH_SHORT).show()
            Log.i(FLUX_UX_LOG_TAG, "send ignored: attachment blocker=$blocker")
            return
        }
        val ready = readyAttachments(draftAttachmentDrafts)
        val attachmentIds = ready.map { it.id }
        // 只有附件没有文字也能发；后端任务描述必填，这里给 Agent 一句最小上下文
        val description = text.ifBlank { "请查看我附上的文件" }
        if (text.isBlank() && attachmentIds.isEmpty()) {
            Log.i(FLUX_UX_LOG_TAG, "send ignored: empty input and no attachments")
            return
        }
        // 同一帧内的第二次点击（建议 chip / 连点）不能落成两个任务：
        // busyAction 由 ViewModel 在协程里置位，这里先用当前快照挡一次。
        if (app.busyAction != null) {
            Log.i(FLUX_UX_LOG_TAG, "send ignored: another action in flight busy=${app.busyAction}")
            return
        }
        Log.i(
            FLUX_UX_LOG_TAG,
            "sendDraftMessage accepted textLen=${text.length} attachments=${attachmentIds.size}",
        )
        if (attachmentIds.isEmpty()) {
            chatMessages.add(V2Message(V2MessageRole.USER, text, nowMinute()))
        } else {
            chatMessages.add(
                V2Message(
                    role = V2MessageRole.USER,
                    text = text,
                    time = nowMinute(),
                    kind = "attachments",
                    attachments = ready.map { V2MessageFile(it.name, it.size) },
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
            origin = if (current.isAssistant) "assistant" else "chat",
            onFailed = { message ->
                // 建任务失败：把乐观气泡回滚掉，避免用户重试后出现两条一样的消息。
                // 输入框里的文字由 V2ChatScreen 在失败时保留，用户可以直接再点发送。
                val last = chatMessages.lastOrNull()
                if (last != null && last.role == V2MessageRole.USER && last.text == text) {
                    chatMessages.remove(last)
                    Log.i(FLUX_UX_LOG_TAG, "optimistic message rolled back after createTask failure")
                }
                Log.w(FLUX_UX_LOG_TAG, "draft send failed, draft kept: ${message.take(160)}")
            },
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
        chatSeed = null
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

    // 系统分享：先收进本地队列。登录墙 / 选设备期间只暂存，进入主界面后一条一条落地。
    LaunchedEffect(Unit) {
        ShareIntakeBus.pending.collect { pending ->
            if (pending.isEmpty()) return@collect
            ShareIntakeBus.consume(pending.map { it.id }.toSet())
            shareQueue = shareQueue.receive(pending.map { it.share })
        }
    }
    // 落地规则：正在看的任务 → 该任务；新对话草稿 → 当前草稿；其它页面 → 新开对话。
    LaunchedEffect(app.boot, shareQueue, route) {
        // 上一份分享还没被当前页面消费时先不动队列，避免连续分享时后一份覆盖前一份的文字。
        val activeSeed = when (val current = route) {
            is V2Route.TaskChat -> taskSeed?.takeIf { it.first == current.taskId }?.second
            V2Route.Chat -> chatSeed
            else -> null
        }
        if (activeSeed != null) return@LaunchedEffect
        val (nextQueue, share) = shareQueue.nextIfReady(app.boot == V2Boot.READY)
        if (share == null) return@LaunchedEffect
        val landing = resolveShareLanding(
            route = route,
            bootReady = true,
            hasDraftChat = chat != null,
        )
        shareQueue = nextQueue
        val notice = share.errors.takeIf { it.isNotEmpty() }?.joinToString("；")
        if (share.text.isBlank() && share.attachments.isEmpty()) {
            // 全部附件都读不到：只提示原因，不为一条空内容新开对话。
            if (notice != null) Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val seed = ComposerSeed(
            token = seedCounter.incrementAndGet(),
            text = share.text,
            attachmentUris = share.attachments.map { it.uri },
            notice = notice,
        )
        when (landing) {
            ShareLanding.CURRENT_TASK -> {
                val taskId = (route as? V2Route.TaskChat)?.taskId ?: return@LaunchedEffect
                taskSeed = taskId to seed
            }
            ShareLanding.CURRENT_DRAFT -> chatSeed = seed
            ShareLanding.NEW_CHAT -> {
                val chatDevice = if (device == ALL_DEVICES) {
                    V2Registry.devices.firstOrNull()?.name ?: "本机"
                } else {
                    device
                }
                createChat(agentsForDevice(chatDevice).first())
                chatSeed = seed
            }
            ShareLanding.HOLD -> Unit
        }
    }

    // 记录用户正在看哪条对话：提醒服务在「前台 + 正看这条对话」时不打扰。
    LaunchedEffect(route) {
        AppVisibility.viewedTaskId = (route as? V2Route.TaskChat)?.taskId
    }

    // 通知点击回跳：登录墙 / 选设备期间先排队，主界面就绪后再跳。
    var pendingOpenTask by remember { mutableStateOf<PendingOpenTask?>(null) }
    LaunchedEffect(Unit) {
        OpenTaskBus.pending.collect { queued ->
            val next = queued.lastOrNull() ?: return@collect
            OpenTaskBus.consume(queued.map { it.id }.toSet())
            pendingOpenTask = next
        }
    }
    LaunchedEffect(app.boot, pendingOpenTask) {
        val request = pendingOpenTask ?: return@LaunchedEffect
        if (app.boot != V2Boot.READY) return@LaunchedEffect
        pendingOpenTask = null
        // 用户是从通知点进来的：关掉当前盖在页面上的弹层 / 面板，确保真的回到目标页。
        settingsOpen = false
        keyVaultOpen = false
        memoryOpen = false
        billingOpen = false
        customConnectorOpen = false
        githubSheetOpen = false
        connectorDetail = null
        newChatOpen = false
        agentPickerOpen = false
        manualConnectOpen = false
        deleteConversationId = null
        deleteConversationError = null
        designExitOpen = false
        when (request.reason) {
            // 决策：进能直接处理审批的那条对话。
            OpenTaskReason.DECISION -> {
                chatOrigin = V2Route.Home
                route = V2Route.TaskChat(request.taskId)
            }
            // 完成：进任务详情看结果 / 失败原因。
            OpenTaskReason.FINISHED -> {
                taskOrigin = V2Route.Home
                route = V2Route.TaskDetail(request.taskId)
            }
        }
        vm.refresh(quiet = true)
    }

    // 提醒开着且有进行中的任务时，用前台服务把监听保住（离开聊天也能收到）。
    // 任务全部结束后由服务自己退出，避免常驻一条无意义的「正在关注」。
    val watchableTaskCount = app.tasks.count { !it.status.toV2TaskStatus().isFinished }
    LaunchedEffect(
        app.boot,
        notifPrefs.decisionAlerts,
        notifPrefs.taskAlerts,
        watchableTaskCount,
        notificationStatus.ready,
    ) {
        val alertsEnabled = notifPrefs.decisionAlerts || notifPrefs.taskAlerts
        when {
            // 退出登录 / 权限被关 / 两种提醒都关：立刻停掉后台监听。
            !alertsEnabled || !notificationStatus.ready || app.boot != V2Boot.READY ->
                TaskAlertService.stop(context)
            watchableTaskCount > 0 -> TaskAlertService.start(context)
        }
    }
    // 关掉开关立即停止该类提醒；已发出的同类通知也一并取消。
    LaunchedEffect(notifPrefs.decisionAlerts) {
        if (!notifPrefs.decisionAlerts) {
            FluxNotificationCenter.cancelKind(context, AlertKind.DECISION)
        }
    }
    LaunchedEffect(notifPrefs.taskAlerts) {
        if (!notifPrefs.taskAlerts) {
            FluxNotificationCenter.cancelKind(context, AlertKind.FINISHED)
        }
    }

    val hasBack = update.kind != UpdateKind.HIDDEN || deleteConversationId != null ||
        settingsOpen || keyVaultOpen || newChatOpen ||
        agentPickerOpen || manualConnectOpen || route != V2Route.Home ||
        designExitOpen || memoryOpen || customConnectorOpen || githubSheetOpen ||
        connectorDetail != null

    BackHandler(enabled = hasBack) {
        when {
            designExitOpen -> designExitOpen = false
            update.kind != UpdateKind.HIDDEN -> UpdateCenter.dismiss()
            deleteConversationId != null -> {
                deleteConversationId = null
                deleteConversationError = null
            }
            manualConnectOpen -> manualConnectOpen = false
            agentPickerOpen -> agentPickerOpen = false
            newChatOpen -> newChatOpen = false
            keyVaultOpen -> keyVaultOpen = false
            memoryOpen -> memoryOpen = false
            customConnectorOpen -> customConnectorOpen = false
            githubSheetOpen -> githubSheetOpen = false
            connectorDetail != null -> connectorDetail = null
            settingsOpen -> settingsOpen = false
            else -> route = when (val current = route) {
                  is V2Route.TaskDetail -> taskOrigin
                  is V2Route.TaskChat -> chatOrigin
                  is V2Route.Confirmation -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Changes -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Diff -> V2Route.Changes(current.taskId)
                  is V2Route.Run -> V2Route.TaskDetail(current.taskId)
                  is V2Route.Terminal -> V2Route.Terminals
                  V2Route.Connectors, V2Route.Subscriptions -> V2Route.Profile
                  is V2Route.Browser -> current.origin
                  is V2Route.ArtifactDetail -> {
                      artifactsVm.closeDetail()
                      artifactOrigin
                  }
                  V2Route.Designs -> V2Route.Artifacts
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

                val renderContent: @Composable (V2Route, Dp, Boolean) -> Unit = { pageRoute, extraTop, showBack ->
                    when (val current = pageRoute) {
                        V2Route.Home -> V2HomeScreen(
                            device = device,
                            onDeviceChange = { device = it },
                            conversations = app.conversations,
                            extraTop = extraTop,
                            onOpenArtifacts = { route = V2Route.Artifacts },
                            onOpenAssistant = { openAssistant() },
                            onOpenConversation = { openChat(it.id, V2Route.Home) },
                            onDeleteConversation = {
                                deleteConversationError = null
                                deleteConversationId = it.id
                            },
                            onNewChat = { newChatOpen = true },
                            onRefresh = { vm.refresh(quiet = true) },
                        )

                        V2Route.Assistant -> V2AssistantScreen(
                            state = assistantState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onRefresh = { assistantVm.load() },
                            onSaveSettings = { mode, provider, model, maxTickets, maxDepth, maxRuns ->
                                assistantVm.saveSettings(mode, provider, model, maxTickets, maxDepth, maxRuns)
                            },
                            onAssess = { assistantVm.assess(it) },
                            onPlan = { id, dispatch -> assistantVm.plan(id, dispatch) },
                            onProcess = { assistantVm.processOutbox() },
                            onWatchdog = { assistantVm.runWatchdog() },
                            onDispatch = { assistantVm.dispatchTicket(it) },
                            onRetry = { assistantVm.retryTicket(it) },
                            onStop = { assistantVm.stopTicket(it) },
                            onOpenDetail = { assistantVm.openDetail(it) },
                            onCloseDetail = { assistantVm.closeDetail() },
                            onDismissMessages = { assistantVm.clearMessages() },
                        )

                        V2Route.Artifacts -> ArtifactsScreen(
                            state = artifactsState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Home },
                            onReload = { artifactsVm.load() },
                            onFilter = { artifactsVm.setFilter(it) },
                            onOpenArtifact = { artifactId ->
                                artifactOrigin = V2Route.Artifacts
                                artifactsVm.open(artifactId)
                                route = V2Route.ArtifactDetail(artifactId)
                            },
                            onOpenDesign = { designId ->
                                designOrigin = V2Route.Artifacts
                                designVm.openDesign(designId)
                                route = V2Route.DesignCanvas
                            },
                            onCreate = { type, title ->
                                artifactsVm.create(type, title) { artifactId ->
                                    artifactsVm.open(artifactId)
                                    route = V2Route.ArtifactDetail(artifactId)
                                }
                            },
                            onOpenDesignStudio = { route = V2Route.Designs },
                            onDismissMessages = { artifactsVm.clearMessages() },
                        )

                        is V2Route.ArtifactDetail -> ArtifactDetailScreen(
                            artifactId = current.artifactId,
                            state = artifactsState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = {
                                artifactsVm.closeDetail()
                                route = artifactOrigin
                            },
                            onSave = { title, content -> artifactsVm.save(title, content) },
                            onDelete = { artifactId ->
                                artifactsVm.delete(artifactId) { route = V2Route.Artifacts }
                            },
                            onOpenSourceTask = { taskId ->
                                // 返回时仍回到这份产物详情，用户不会丢掉上下文。
                                openChat(taskId, V2Route.ArtifactDetail(current.artifactId))
                            },
                            onDismissMessages = { artifactsVm.clearMessages() },
                        )

                        V2Route.Designs -> DesignStudioListScreen(
                            state = designState,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Artifacts },
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

                        V2Route.Profile -> V2ProfileScreen(
                            accountName = app.account?.displayName?.takeIf { it.isNotBlank() }
                                ?: app.account?.username?.takeIf { it.isNotBlank() }
                                ?: "未登录",
                            deviceName = app.account?.deviceName?.takeIf { it.isNotBlank() }
                                ?: app.deviceLabel,
                            deviceCount = if (app.account != null) app.devices.size else 1,
                            memoryCount = app.memories.size,
                            billingNote = if (app.billingError != null) {
                                "部分官方额度不可用"
                            } else {
                                app.billing?.headline ?: "暂无数据"
                            },
                            version = BuildConfig.VERSION_NAME,
                            connectorCount = app.connectors.count { it.status == "connected" },
                            subscriptionCount = listOf(
                                app.codexAccount?.loggedIn == true,
                                app.claudeAccount?.loggedIn == true,
                            ).count { it },
                            extraTop = extraTop,
                            onOpenDevices = { vm.showDevices() },
                            onOpenConnectors = { route = V2Route.Connectors },
                            onOpenSubscriptions = { route = V2Route.Subscriptions },
                            onOpenTerminals = { route = V2Route.Terminals },
                            onOpenKeys = {
                                keyVaultOpen = true
                                vm.loadKeys()
                            },
                            onOpenBilling = {
                                billingOpen = true
                                vm.loadBilling()
                            },
                            onOpenMemories = {
                                memoryOpen = true
                                vm.loadMemories()
                            },
                            // 独立入口：从「我的」开一个空白浏览会话，不占底栏 Tab
                            onOpenBrowser = {
                                route = V2Route.Browser(
                                    url = blankBrowserSessionUrl(),
                                    origin = V2Route.Profile,
                                )
                            },
                            onOpenSettings = { settingsOpen = true },
                            onCheckUpdate = { UpdateCenter.checkManually() },
                        )

                        V2Route.Connectors -> ConnectorsScreen(
                            connectors = app.connectors,
                            loading = app.connectorsLoading,
                            error = app.connectorsError,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Profile },
                            onReload = { vm.loadConnectors() },
                            onAdd = { customConnectorOpen = true },
                            onGitHub = { githubSheetOpen = true },
                            onReconnect = { item ->
                                if (item.id == "github") {
                                    githubSheetOpen = false
                                    vm.startGitHubLogin()
                                } else {
                                    vm.probeConnector(item.id) { failure ->
                                        if (failure != null) {
                                            Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onOpenCustom = { connectorDetail = it },
                            onDismissMessages = { vm.clearMessages() },
                        )

                        V2Route.Subscriptions -> SubscriptionsScreen(
                            codexAccount = app.codexAccount,
                            codexLogin = app.codexLogin,
                            codexLoading = app.codexLoading,
                            claudeAccount = app.claudeAccount,
                            claudeLogin = app.claudeLogin,
                            claudeLoading = app.claudeLoading,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.Profile },
                            onRefresh = {
                                vm.loadCodexAccount()
                                vm.loadClaudeAccount()
                            },
                            onCodexLogin = { vm.startCodexLogin() },
                            onCodexLogout = { vm.logoutCodexAccount() },
                            onOpenCodexUrl = { openInternalBrowser(it) },
                            onClaudeLogin = { vm.startClaudeLogin() },
                            onClaudeLogout = { vm.logoutClaudeAccount() },
                            onSubmitClaudeCode = { loginId, code, onResult ->
                                vm.submitClaudeLoginCode(loginId, code, onResult)
                            },
                            onOpenClaudeUrl = { openInternalBrowser(it) },
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
                            onRedo = { designVm.redo() },
                            onBeginDrag = { designVm.beginNodeDrag(it) },
                            onDrag = { ids, dx, dy -> designVm.dragNodes(ids, dx, dy) },
                            onSelectNodes = { designVm.selectNodes(it) },
                            onTransformNode = { id, x, y, w, h ->
                                designVm.updateNodeFrame(id, x, y, w, h)
                            },
                            onRotateNode = { id, degrees -> designVm.setNodeRotation(id, degrees) },
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
                                    onDelete = {
                                        chat = null
                                        chatMessages.clear()
                                        draftAttachments.clear()
                                        draftAttachmentError = null
                                        route = V2Route.Home
                                    },
                                    onOpenAgentPicker = { agentPickerOpen = true },
                                    onSend = { sendDraftMessage(it) },
                                    attachmentDrafts = draftAttachmentDrafts,
                                    // 上限（5 个）、单文件 50MB、空文件、重复 Uri 都在控制器里统一处理，
                                    // 新对话与已有对话不会一边紧一边松。
                                    onAddAttachments = { uris ->
                                        draftAttachmentError = null
                                        draftAttachments.add(uris, context.contentResolver)
                                    },
                                    onRemoveAttachment = { draftAttachments.remove(it) },
                                    onRetryAttachment = { draftAttachments.retry(it) },
                                    seed = chatSeed,
                                    onSeedApplied = { token ->
                                        if (chatSeed?.token == token) chatSeed = null
                                    },
                                    designOptions = designState.designs,
                                    designsLoading = designState.loading,
                                    onRequestDesigns = { designVm.loadDesigns(quiet = true) },
                                    onPickDesign = { design ->
                                        draftAttachmentError = null
                                        draftAttachments.addDesign(design.name) {
                                            vm.uploadDesignAsAttachment(design.id, design.name)
                                        }
                                    },
                                    // 附件上传不算「发送中」：否则上传结束时会被误判成本轮发送成功，
                                    // 把用户已经打好的文字清空。上传状态由草稿自己表达。
                                    sending = app.busyAction == "create",
                                    notice = draftAttachmentError ?: app.error,
                                    // 只有建任务/发送失败才保留草稿；附件上传提示不算。
                                    sendError = app.error,
                                    onDismissNotice = {
                                        draftAttachmentError = null
                                        vm.clearMessages()
                                    },
                                    // 新建对话不是从设备接续的历史会话，不需要接续横幅。
                                    showHandoff = false,
                                    // 新对话也在输入栏里就地切 Agent / 模型：
                                    // 切 Agent 后模型清单立刻换成新 Agent 支持的模型（后端注入）。
                                    agentOptions = agentsForDevice(active.device),
                                    modelOptions = modelsForAgent(
                                        agentsForDevice(active.device),
                                        active.agentId,
                                    ),
                                    onSwitchAgent = { agentId ->
                                        val nextOptions = agentsForDevice(active.device)
                                        val firstModel = modelsForAgent(nextOptions, agentId).firstOrNull()
                                        chat = chat?.copy(
                                            agentId = agentId,
                                            model = firstModel?.model.orEmpty(),
                                            modelKeyId = firstModel?.keyId,
                                        )
                                    },
                                    onSwitchModel = { option ->
                                        chat = chat?.copy(
                                            model = option.model,
                                            modelKeyId = option.keyId,
                                        )
                                    },
                                    onDecisionModeChange = { mode -> chat = chat?.copy(decisionMode = mode) },
                                    // 新建对话还没有任务：模式只记在本地，发首条消息时随建任务请求带上
                                    onPermissionModeChange = { mode -> chat = chat?.copy(permissionMode = mode) },
                                    onOpenUrl = { openInternalBrowser(it) },
                                    onOpenDesign = { openDesignFromChat(it) },
                                    onOpenAssistantBoard = if (active.isAssistant) {
                                        ({ route = V2Route.Assistant })
                                    } else {
                                        null
                                    },
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
                                taskSeed = null
                                route = chatOrigin
                                if (chatOrigin == V2Route.Home) {
                                    chat = null
                                    chatMessages.clear()
                                }
                            },
                            onRequestDelete = {
                                deleteConversationError = null
                                deleteConversationId = current.taskId
                            },
                            onHandoffReady = { summary ->
                                startHandoffChat(current.taskId, summary)
                            },
                            onOpenDesign = { openDesignFromChat(it) },
                            onOpenArtifact = { openArtifactFromChat(it) },
                            onOpenUrl = { openInternalBrowser(it) },
                            designOptions = designState.designs,
                            designsLoading = designState.loading,
                            onRequestDesigns = { designVm.loadDesigns(quiet = true) },
                            // 助理对话右上角可回到进度看板；普通对话不给这个入口
                            onOpenAssistantBoard = { route = V2Route.Assistant },
                            onOpenChanges = { route = V2Route.Changes(current.taskId) },
                            // 系统分享落到当前正在看的这段对话
                            seed = taskSeed?.takeIf { it.first == current.taskId }?.second,
                            onSeedApplied = { token ->
                                if (taskSeed?.second?.token == token) taskSeed = null
                            },
                        )

                        is V2Route.Confirmation -> V2ConfirmationScreen(
                            taskId = current.taskId,
                            extraTop = extraTop,
                            showBack = showBack,
                            onBack = { route = V2Route.TaskDetail(current.taskId) },
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
                    LocalReduceMotion provides notifPrefs.reduceMotion,
                ) {
                    if (tablet) {
                        Row(Modifier.fillMaxSize().then(insets)) {
                            V2NavRail(
                                selected = when (route) {
                                    V2Route.Artifacts,
                                    V2Route.Designs,
                                    V2Route.DesignCanvas,
                                    is V2Route.ArtifactDetail -> V2RailItem.ARTIFACTS
                                    V2Route.Profile,
                                    V2Route.Connectors,
                                    V2Route.Subscriptions -> V2RailItem.PROFILE
                                    else -> V2RailItem.CHATS
                                },
                                onSelect = { item ->
                                    when (item) {
                                        V2RailItem.CHATS -> route = V2Route.Home
                                        V2RailItem.ARTIFACTS -> route = V2Route.Artifacts
                                        V2RailItem.PROFILE -> route = V2Route.Profile
                                    }
                                },
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
                        // 手机：三个一级页面（对话 / Artifacts / 我的）带底部 Tab，二三级页面全屏
                        val topTab = when (route) {
                            V2Route.Home -> V2TopTab.CHATS
                            V2Route.Artifacts -> V2TopTab.ARTIFACTS
                            V2Route.Profile -> V2TopTab.PROFILE
                            else -> null
                        }
                        Column(Modifier.fillMaxSize().then(insets)) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                V2PageTransition(
                                    target = route,
                                    depthOf = { v2RouteDepth(it as V2Route) },
                                ) { page ->
                                    val current = page as V2Route
                                    renderContent(
                                        current,
                                        0.dp,
                                        current != V2Route.Home &&
                                            current != V2Route.Artifacts &&
                                            current != V2Route.Profile,
                                    )
                                }
                            }
                            if (topTab != null) {
                                V2BottomTabBar(
                                    selected = topTab,
                                    onSelect = { tab ->
                                        when (tab) {
                                            V2TopTab.CHATS -> if (route != V2Route.Home) {
                                                chat = null
                                                chatMessages.clear()
                                                route = V2Route.Home
                                            }
                                            V2TopTab.ARTIFACTS -> if (route != V2Route.Artifacts) {
                                                route = V2Route.Artifacts
                                            }
                                            V2TopTab.PROFILE -> if (route != V2Route.Profile) {
                                                route = V2Route.Profile
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    val flash = app.error ?: app.notice
                    val flashIsError = app.error != null
                    // 提示条到点自动收起：错误留久一点，成功提示更快消失，避免长期糊在顶栏。
                    LaunchedEffect(flash) {
                        if (flash != null) {
                            delay(if (flashIsError) 5000L else 2600L)
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
                                .background(if (flashIsError) V2Colors.rose else V2Colors.emerald700)
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
                            haptics = notifPrefs.haptics,
                            reduceMotion = notifPrefs.reduceMotion,
                            decisionAlerts = notifPrefs.decisionAlerts,
                            taskAlerts = notifPrefs.taskAlerts,
                            strict = strict,
                            notificationStatus = notificationStatus,
                            notificationProblem = notificationProblem(
                                notificationStatus,
                                notifPrefs.decisionAlerts,
                                notifPrefs.taskAlerts,
                            ),
                            notificationError = notificationError,
                            onHaptics = { notifPrefs = notifPrefs.copy(haptics = it) },
                            onReduceMotion = { notifPrefs = notifPrefs.copy(reduceMotion = it) },
                            onDecisionAlerts = { updateAlerts(decision = it) },
                            onTaskAlerts = { updateAlerts(task = it) },
                            onOpenNotificationSettings = { openNotificationSettings() },
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

                    if (customConnectorOpen) {
                        CustomConnectorSheet(
                            onDismiss = { customConnectorOpen = false },
                            onSubmit = { name, url, onResult ->
                                vm.addConnector(name, url) { failure ->
                                    if (failure == null) customConnectorOpen = false
                                    onResult(failure)
                                }
                            },
                        )
                    }

                    if (githubSheetOpen) {
                        GitHubConnectorSheet(
                            account = app.githubAccount,
                            repos = app.githubRepos,
                            reposLoading = app.githubReposLoading,
                            loading = app.githubLoading,
                            onRefreshRepos = { vm.loadGitHubRepos() },
                            onLogout = {
                                vm.logoutGitHubAccount()
                                githubSheetOpen = false
                            },
                            onDismiss = { githubSheetOpen = false },
                        )
                    }

                    connectorDetail?.let { selected ->
                        // 探测后状态可能已变：优先用列表里的最新副本渲染。
                        val current = app.connectors.firstOrNull { it.id == selected.id } ?: selected
                        CustomConnectorDetailSheet(
                            item = current,
                            onDismiss = { connectorDetail = null },
                            onProbe = { id, onResult -> vm.probeConnector(id, onResult) },
                            onDelete = { id, onResult ->
                                vm.deleteConnector(id) { failure ->
                                    if (failure == null) connectorDetail = null
                                    onResult(failure)
                                }
                            },
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

                    if (newChatOpen) {
                        NewChatSheet(
                            device = device,
                            options = agentsForDevice(device),
                            onPick = { createChat(it) },
                            onDismiss = { newChatOpen = false },
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
                            running = target?.status?.toV2TaskStatus()?.isActive == true,
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
    onSelect: (V2RailItem) -> Unit,
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
            icon = Icons.Outlined.Dashboard,
            label = "Artifacts",
            selected = selected == V2RailItem.ARTIFACTS,
        ) { onSelect(V2RailItem.ARTIFACTS) }
        Spacer(Modifier.height(4.dp))
        RailButton(
            icon = Icons.Outlined.Person,
            label = "我的",
            selected = selected == V2RailItem.PROFILE,
        ) { onSelect(V2RailItem.PROFILE) }

        Spacer(Modifier.weight(1f))

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(V2Colors.avatarBlue)
                .clickable { onSelect(V2RailItem.PROFILE) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "打开我的",
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
    onBadgeClick: (() -> Unit)? = null,
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
            // 角标本身是快捷入口：外面套一层 28dp 的热区，避免 16dp 圆点难点中。
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .height(28.dp)
                    .widthIn(min = 28.dp)
                    .then(
                        if (onBadgeClick != null) {
                            Modifier.v2Pressable(pressScale = .92f, onClick = onBadgeClick)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    Modifier
                        .height(16.dp)
                        .widthIn(min = 16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeColor)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label("$badge", 9f, V2Colors.actionInk, FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}


/** 页面层级：用于判断转场方向（前进 / 返回）。 */
private fun v2RouteDepth(route: V2Route): Int = when (route) {
    V2Route.Home, V2Route.Artifacts, V2Route.Profile -> 0
    V2Route.Assistant, V2Route.Terminals, V2Route.Chat -> 1
    V2Route.Connectors, V2Route.Subscriptions -> 1
    V2Route.Designs, is V2Route.ArtifactDetail -> 1
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
