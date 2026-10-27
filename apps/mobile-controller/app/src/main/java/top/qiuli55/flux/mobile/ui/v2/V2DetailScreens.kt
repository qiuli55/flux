package top.qiuli55.flux.mobile.ui.v2

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.data.DesignPreview
import top.qiuli55.flux.mobile.data.ConfirmationItem
import top.qiuli55.flux.mobile.data.Decision
import top.qiuli55.flux.mobile.data.HandoffSummary
import top.qiuli55.flux.mobile.data.Run
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.TaskMessage
import top.qiuli55.flux.mobile.data.TerminalSession
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.ui.changes.ChangeViewModel
import top.qiuli55.flux.mobile.ui.tasks.TaskDetailViewModel
import top.qiuli55.flux.mobile.ui.tasks.canSwitchTaskAgent
import top.qiuli55.flux.mobile.ui.tasks.canSwitchTaskModel
import top.qiuli55.flux.mobile.ui.tasks.shouldPromptContext
import top.qiuli55.flux.mobile.ui.terminal.LineKind
import top.qiuli55.flux.mobile.ui.terminal.TerminalListViewModel
import top.qiuli55.flux.mobile.ui.terminal.TerminalViewModel

// ================================================================== 共享

/** 同一 taskId 共享同一个 ViewModel：详情 / 对话 / 确认 / 变更之间切页不会重复拉取。 */
@Composable
private fun taskViewModel(taskId: String): TaskDetailViewModel = viewModel(
    key = "v2-task-$taskId",
    factory = viewModelFactory { initializer { TaskDetailViewModel(taskId) } },
)

@Composable
private fun changeViewModel(changeId: String): ChangeViewModel = viewModel(
    key = "v2-change-$changeId",
    factory = viewModelFactory { initializer { ChangeViewModel(changeId) } },
)

@Composable
private fun terminalViewModel(sessionId: String): TerminalViewModel = viewModel(
    key = "v2-terminal-$sessionId",
    factory = viewModelFactory { initializer { TerminalViewModel(sessionId) } },
)

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String? = null,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            RoundIconButton(
                Icons.Outlined.ArrowBack,
                onBack,
                size = 32.dp,
                iconSize = 16.dp,
                contentDescription = "返回",
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Label(title, 20f, V2Colors.ink(.95f), FontWeight.SemiBold, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Label(subtitle, 9.5f, V2Colors.ink(.36f), maxLines = 1, mono = true)
            }
        }
        if (onRefresh != null) {
            RoundIconButton(
                icon = Icons.Outlined.Refresh,
                onClick = onRefresh,
                size = 32.dp,
                iconSize = 14.dp,
                background = V2Colors.surface(.55f),
                tint = V2Colors.cyan700.copy(alpha = .8f),
            )
        }
    }
}

@Composable
private fun V2Banner(text: String, tone: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.copy(alpha = .07f))
            .border(1.dp, tone.copy(alpha = .18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Label(text, 10f, tone.copy(alpha = .85f), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusChipFor(label: String, color: Color) {
    V2Chip(label, color)
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(V2Colors.accent.copy(alpha = .08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = V2Colors.cyan700, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(title, 12.5f, V2Colors.ink(.90f), FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Label(subtitle, 9.5f, V2Colors.ink(.38f), maxLines = 1)
            }
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                StatusChipFor(badge, V2Colors.cyan700)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                tint = V2Colors.ink(.22f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 紧凑确认弹层：只占内容高度，不铺满整屏。 */
@Composable
private fun V2ActionConfirm(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetMaxWidth = LocalSheetMaxWidth.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(V2Colors.scrim)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .then(if (sheetMaxWidth != null) Modifier.widthIn(max = sheetMaxWidth) else Modifier)
                .fillMaxWidth()
                .shadow(24.dp, V2SheetShape)
                .clip(V2SheetShape)
                .background(Brush.verticalGradient(listOf(V2Colors.shellTop, V2Colors.shellBottom)))
                .border(1.dp, V2Colors.line, V2SheetShape)
                .padding(top = 16.dp),
        ) {
            SheetHeader(title = title, onClose = onDismiss, centered = true)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Label(message, 11.5f, V2Colors.ink(.55f))
                Spacer(Modifier.height(16.dp))
                if (danger) {
                    DangerButton(confirmText, onConfirm, Modifier.fillMaxWidth(), enabled = !busy)
                } else {
                    PrimaryButton(confirmText, onConfirm, Modifier.fillMaxWidth(), enabled = !busy)
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("取消", onDismiss, Modifier.fillMaxWidth(), enabled = !busy)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) V2Colors.rose else V2Colors.rose.copy(alpha = .35f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 12.5f, Color.White, FontWeight.SemiBold)
    }
}

// ================================================================== 任务详情

@Composable
fun V2TaskDetailScreen(
    taskId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenConfirmation: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenRun: () -> Unit,
) {
    val vm = taskViewModel(taskId)
    val state by vm.state.collectAsState()
    val task = state.task
    var expanded by remember(taskId) { mutableStateOf(false) }

    LaunchedEffect(taskId) { vm.refresh(quiet = true) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "任务详情",
            // 头部只给可读状态，不在标题行里裸露 UUID
            subtitle = task?.let { taskStatusLabel(it.status).first }.orEmpty(),
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
            onRefresh = { vm.refresh() },
        )

        if (state.loading && task == null) {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Label("正在读取任务…", 11f, V2Colors.ink(.35f))
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val error = state.error
            if (error != null) {
                item { V2Banner(error, V2Colors.rose) }
            }
            val notice = state.notice
            if (notice != null) {
                item { V2Banner(notice, V2Colors.emerald700) }
            }

            if (task == null) {
                item { Label("任务不存在或已被清理", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }

            item {
                TaskSummaryCard(task = task, expanded = expanded, onToggle = { expanded = !expanded })
            }
            item {
                TaskActions(
                    task = task,
                    busy = state.busyAction,
                    onStart = { vm.start() },
                    onStop = { vm.interruptRun() },
                    onCancel = { vm.cancel() },
                )
            }
            item {
                val confirmationCount = (task.confirmation?.items?.size ?: 0) +
                    if (task.pendingDecision != null) 1 else 0
                EntryRow(
                    icon = Icons.Outlined.FactCheck,
                    title = "需求确认",
                    subtitle = if (confirmationCount == 0) "暂无确认项" else "$confirmationCount 项待确认 / 已确认",
                    badge = if (task.pendingDecision != null) "待决策" else null,
                    onClick = onOpenConfirmation,
                )
            }
            item {
                val chatMessages = state.messages.filter { it.kind != "run_started" }
                val last = chatMessages.lastOrNull()?.content?.replace('\n', ' ')?.take(40)
                EntryRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "对话",
                    subtitle = if (chatMessages.isEmpty()) "还没有消息" else last.orEmpty(),
                    badge = if (chatMessages.isEmpty()) null else "${chatMessages.size}",
                    onClick = onOpenChat,
                )
            }
            item {
                val added = state.changes.sumOf { it.addedLines }
                val removed = state.changes.sumOf { it.removedLines }
                EntryRow(
                    icon = Icons.Outlined.Description,
                    title = "变更",
                    subtitle = if (state.changes.isEmpty()) {
                        "还没有文件改动"
                    } else {
                        "${state.changes.size} 个文件 · +$added / -$removed"
                    },
                    badge = if (state.changes.isEmpty()) null else "${state.changes.size}",
                    onClick = onOpenChanges,
                )
            }
            item {
                val run = state.run
                EntryRow(
                    icon = Icons.Outlined.History,
                    title = "Run 记录",
                    subtitle = if (run == null) {
                        "还没有运行记录"
                    } else {
                        "${run.runId.take(10)} · ${runStatusLabel(run.status)}"
                    },
                    onClick = onOpenRun,
                )
            }
        }
    }
}

@Composable
private fun TaskSummaryCard(task: Task, expanded: Boolean, onToggle: () -> Unit) {
    val (label, color) = taskStatusLabel(task.status)
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChipFor(label, color)
            Spacer(Modifier.width(6.dp))
            StatusChipFor(
                if (task.decisionMode == "auto") "自动决策" else "手动确认",
                V2Colors.ink(.45f),
            )
            Spacer(Modifier.weight(1f))
            Label(relativeTime(task.createdAt), 9.5f, V2Colors.ink(.32f), mono = true)
        }
        Spacer(Modifier.height(10.dp))
        Label(task.firstLine(120), 13f, V2Colors.ink(.88f), FontWeight.Medium, maxLines = if (expanded) Int.MAX_VALUE else 3)
        val full = task.description.trim()
        if (full.length > task.firstLine(120).length || task.description.contains('\n')) {
            Spacer(Modifier.height(6.dp))
            Label(
                if (expanded) "收起" else "展开完整描述",
                10f,
                V2Colors.cyan700,
                modifier = Modifier.clickable(onClick = onToggle),
            )
        }
        val result = resultText(task.result)
        if (result != null) {
            Spacer(Modifier.height(10.dp))
            ThinDivider(color = V2Colors.lineSoft)
            Spacer(Modifier.height(8.dp))
            Label("执行结果", 9.5f, V2Colors.ink(.38f))
            Spacer(Modifier.height(4.dp))
            Label(result, 11f, V2Colors.ink(.62f))
        }
    }
}

@Composable
private fun TaskActions(
    task: Task,
    busy: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val canStart = task.runId == null &&
        !task.isFinished &&
        task.status != "waiting_for_user_decision"
    val canStop = task.runId != null && task.status == "running"
    val canCancel = !task.isFinished
    if (!canStart && !canStop && !canCancel) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canStart) {
            PrimaryButton(
                text = if (busy == "start") "正在启动…" else "开始执行",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.PlayArrow,
                enabled = busy == null,
            )
        }
        if (canStop) {
            GhostButton(
                text = if (busy == "interrupt") "正在停止…" else "停止 Agent",
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                contentColor = V2Colors.amber700,
                icon = Icons.Outlined.Stop,
                enabled = busy == null,
            )
        }
        if (canCancel) {
            GhostButton(
                text = if (busy == "cancel") "取消中…" else "取消任务",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                contentColor = V2Colors.rose,
                icon = Icons.Outlined.Cancel,
                enabled = busy == null,
            )
        }
    }
}

// ================================================================== 任务对话

@Composable
fun V2TaskChatScreen(
    taskId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onRequestDelete: () -> Unit,
    /** 上下文快满时选择「新建并带摘要」：把旧对话摘要交给外层去开一段新对话。 */
    onHandoffReady: (HandoffSummary) -> Unit = {},
    /** 设计稿卡片点开：外层切到原生画布。 */
    onOpenDesign: (String) -> Unit = {},
    /** 对话里的链接：外层切到内嵌浏览器。 */
    onOpenUrl: (String) -> Unit = {},
) {
    val vm = taskViewModel(taskId)
    val state by vm.state.collectAsState()
    val task = state.task
    val context = LocalContext.current

    LaunchedEffect(taskId) {
        // 「已开始执行」之类的提示只属于任务详情页，进入对话页不该再看到。
        vm.dismissNotice()
        vm.refresh(quiet = true)
    }

    // 只在对话页保持实时流：页面退出即断开，避免任务多了以后连接常驻。
    DisposableEffect(taskId) {
        vm.startStream()
        onDispose { vm.stopStream() }
    }

    val device = V2Registry.devices.firstOrNull()?.name ?: "本机"
    val agentId = task?.agentId.orEmpty()
    val option = agentsForDevice(device).firstOrNull { it.agent.id == agentId }
    val models = option?.models ?: emptyList()
    // 任务级模型覆盖优先（带 key 归属）；没有覆盖时用该 Agent 的第一个可用模型。
    val currentKeyId = task?.apiKeyId?.takeIf { it.isNotBlank() }
    val selectedModel = task?.model?.takeIf { it.isNotBlank() }?.let { modelId ->
        models.firstOrNull { it.model == modelId && it.keyId == currentKeyId }
            ?: V2ModelOption(modelId, modelId, currentKeyId)
    } ?: models.firstOrNull()
    val chat = V2ChatState(
        conversationId = taskId,
        title = task?.firstLine(40) ?: "任务对话",
        device = device,
        agentId = agentId,
        model = selectedModel?.model ?: "默认模型",
        modelKeyId = selectedModel?.keyId,
        permissionMode = task?.permissionMode ?: "normal",
    )
    val messages = remember(state.messages, task?.id, task?.decisions) {
        // 审批记录的唯一真源是 task.decisions：对话里的 decision 消息只保留发起时的文案，
        // 状态与最终选择每轮刷新都会覆盖，避免卡片一直停在「待处理」。
        val decisionStates = linkedMapOf<String, Pair<String, String?>>()
        task?.decisions.orEmpty().forEach { record ->
            val id = record.id ?: return@forEach
            decisionStates[id] = (record.status ?: "pending") to record.chosen
        }
        val mapped = state.messages.mapNotNull { it.toV2Message() }.map { message ->
            val card = message.approvalCard
            if (message.kind == "decision" && card != null && decisionStates.containsKey(card.id)) {
                val (status, chosen) = decisionStates.getValue(card.id)
                message.copy(approvalCard = card.copy(status = status, chosen = chosen))
            } else {
                message
            }
        }
        // 已有结果消息的审批不再重复渲染发起卡
        val resolvedIds = mapped
            .filter { it.kind == "decision_result" }
            .mapNotNull { it.approvalCard?.id }
            .toSet()
        val deduped = mapped.filterNot { it.kind == "decision" && it.approvalCard?.id in resolvedIds }
        if (deduped.isEmpty() && task != null && task.description.isNotBlank()) {
            // 任务描述本身就是用户的第一条需求：还没有消息时先把它当成首条用户消息展示。
            listOf(V2Message(V2MessageRole.USER, task.description.trim(), clockLabel(task.createdAt)))
        } else {
            deduped
        }
    }
    // 只在任务还没加载出来时锁输入；已结束的对话可以继续发消息（后端会把任务重开并起新一轮）。
    val locked = task == null
    // 执行中也能切换 Agent：排队到本轮结束后生效；模型仍只在非执行中可换。
    val canSwitchAgent = canSwitchTaskAgent(task)
    val canSwitchModel = canSwitchTaskModel(task)
    // 已排队的切换目标：标题栏显示「A → B · 本轮结束后切换」，下拉里也标出来源
    val pendingAgentId = task?.pendingAgentId
    val pendingAgentLabel = pendingAgentId?.let { id ->
        agentsForDevice(chat.device).firstOrNull { it.agent.id == id }?.agent?.name
    }
    // run_id → 该轮的耗时与概览：答案气泡上方的「任务耗时」入口按它渲染
    val runSummaries = remember(state.runs) { state.runs.associateBy { it.runId } }
    // 上下文占用：同一等级点过「继续聊」就不再打扰，涨到 full 会再提醒一次。
    val usage = state.contextUsage
    val contextPromptVisible = shouldPromptContext(usage, state.contextDismissedLevel)

    Box(Modifier.fillMaxSize()) {
        V2ChatScreen(
            chat = chat,
            messages = messages,
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
            onOpenLogs = onOpenLogs,
            onDelete = onRequestDelete,
            onOpenAgentPicker = {},
            onSend = { text ->
                vm.onDraftChange(text)
                vm.send()
            },
            attachments = state.pendingAttachments,
            uploading = state.uploading,
            onAddAttachments = { uris ->
                val remaining = UploadedAttachment.MAX_FILES - state.pendingAttachments.size
                if (remaining <= 0) {
                    Toast.makeText(context, "一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件", Toast.LENGTH_SHORT).show()
                } else {
                    if (uris.size > remaining) {
                        Toast.makeText(context, "最多 5 个附件，已忽略多选的 ${uris.size - remaining} 个", Toast.LENGTH_SHORT).show()
                    }
                    vm.addAttachments(uris, context.contentResolver)
                }
            },
            onRemoveAttachment = { vm.removeAttachment(it) },
            sending = state.sending,
            liveText = state.liveText,
            liveLabel = state.liveLabel,
            liveActive = state.liveActive,
            runItems = state.runItems,
            runSummaries = runSummaries,
            onOpenRunTrace = { vm.openRunTrace(it) },
            locked = locked,
            showDelete = true,
            canSwitchAgent = canSwitchAgent,
            canSwitchModel = canSwitchModel,
            pendingAgentId = pendingAgentId,
            pendingAgentLabel = pendingAgentLabel,
            notice = state.error?.let { it } ?: state.notice,
            suggestions = emptyList(),
            showHandoff = false,
            // 当前设备上已配置的 Agent 就地下拉切换，历史消息保留。
            agentOptions = if (canSwitchAgent) agentsForDevice(chat.device) else emptyList(),
            onSwitchAgent = { vm.switchAgent(it) },
            // 当前 Agent 支持的模型下拉（原生模型 + 密钥库模型）。
            modelOptions = if (canSwitchModel) models else emptyList(),
            onSwitchModel = { vm.switchModel(it.model, it.keyId) },
            contextUsage = usage,
            contextPromptVisible = contextPromptVisible,
            onContinueContext = { vm.continueInCurrentChat() },
            onStartNewWithSummary = {
                // 失败只会在当前页提示，不会把用户踢出这段对话
                vm.createHandoff { summary -> onHandoffReady(summary) }
            },
            handoffBusy = state.handoffBusy,
            onOpenUrl = onOpenUrl,
            onOpenDesign = onOpenDesign,
            // 权限模式只影响后续消息；审批走既有决策点接口（允许 / 拒绝）
            onPermissionModeChange = { vm.setPermissionMode(it) },
            onAllowApproval = { decisionId -> vm.chooseDecision(decisionId, "允许", reject = false) },
            onDenyApproval = { decisionId -> vm.chooseDecision(decisionId, null, reject = true) },
            // 执行中常驻停止条：随时能停，不用滚到消息尾部
            taskRunning = task?.status == "running" || state.liveActive,
            stopping = state.busyAction == "cancel",
            onStopTask = { vm.cancel() },
            // 审批卡固定在输入框上方，提交期间禁用按钮
            approvalBusy = state.busyAction == "decision",
            // 下拉刷新：重新拉任务与历史消息（真实数据，不是假骨架）
            onRefresh = { vm.refresh(quiet = true) },
        )
        // 执行详情弹层：概览 → 思考过程 → 命令与输出（与内容平级叠放，避免拿不到高度）
        val traceRunId = state.traceRunId
        if (traceRunId != null) {
            RunTraceSheet(
                summary = runSummaries[traceRunId],
                trace = state.trace,
                loading = state.traceLoading,
                onDismiss = { vm.closeRunTrace() },
            )
        }
    }
}

private fun TaskMessage.toV2Message(): V2Message? {
    // 「已开始执行」气泡已经下线：旧对话里残留的同 kind 消息不再展示
    if (kind == "run_started") return null
    val text = content.trim()
    val mapped = when (role.lowercase()) {
        "user", "human" -> V2MessageRole.USER
        "assistant", "agent", "ai" -> V2MessageRole.AGENT
        else -> V2MessageRole.SYSTEM
    }
    // 设计稿卡片：payload 里带设计稿摘要与第一屏预览，点开进原生画布
    if (kind == "design_card") {
        parseDesignCard(payload)?.let { card ->
            return V2Message(mapped, text, clockLabel(createdAt), kind = kind, designCard = card)
        }
    }
    // Plan 模式产出的计划卡：标题 + 步骤 + 涉及文件 + 计划文档路径
    if (kind == "plan_card") {
        parsePlanCard(payload)?.let { card ->
            return V2Message(mapped, text, clockLabel(createdAt), kind = kind, planCard = card)
        }
    }
    // 审批卡：payload.decision 是决策记录本身（含 kind / meta.command / status / chosen）
    if (kind == "decision" || kind == "decision_result") {
        parseApprovalCard(payload)?.let { card ->
            val expired = (payload as? JsonObject)?.get("expired")?.let {
                (it as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            } ?: false
            return V2Message(
                mapped,
                text,
                clockLabel(createdAt),
                kind = kind,
                approvalCard = card.copy(expired = card.expired || expired),
            )
        }
    }
    // Agent 交接卡：平台在切换生效时自动落库，标题展示「原 Agent → 新 Agent」
    if (kind == "handoff") {
        val root = payload as? JsonObject
        return V2Message(
            mapped,
            text,
            clockLabel(createdAt),
            kind = kind,
            handoffFrom = root?.stringField("from_agent").orEmpty(),
            handoffTo = root?.stringField("to_agent").orEmpty(),
        )
    }
    // 附件消息的 content 可能是空的：先按 payload.files 转成文件 chip，不要被空文本过滤掉。
    if (kind == "attachments") {
        val files = parseAttachmentFiles(payload)
        if (files.isNotEmpty()) {
            return V2Message(mapped, text, clockLabel(createdAt), kind = kind, attachments = files)
        }
    }
    if (text.isEmpty()) return null
    // run_finished 携带 run_id：答案气泡上方据此显示「任务耗时」入口
    val runId = if (kind == "run_finished") payloadRunId(payload) else ""
    return V2Message(mapped, text, clockLabel(createdAt), kind = kind, runId = runId)
}

/** 从消息 payload 里取 run_id（run_finished 才有）。 */
private fun payloadRunId(payload: JsonElement?): String =
    ((payload as? JsonObject)?.get("run_id") as? JsonPrimitive)?.contentOrNull.orEmpty()

/** 设计稿载荷里节点字段很多，按宽松模式解析，未知字段一律忽略。 */
private val designPayloadJson = Json { ignoreUnknownKeys = true }

/** 解析 design_card 消息 payload → 对话里的设计稿卡片（单测直接覆盖这条关键路径）。 */
internal fun parseDesignCard(payload: JsonElement?): V2DesignCard? {
    val root = payload as? JsonObject ?: return null
    val id = root.stringField("design_id") ?: return null
    val preview = (root["preview"] as? JsonObject)?.let { element ->
        runCatching { designPayloadJson.decodeFromJsonElement(DesignPreview.serializer(), element) }
            .getOrNull()
    }
    return V2DesignCard(
        id = id,
        name = root.stringField("name") ?: id,
        project = root.stringField("project").orEmpty(),
        description = root.stringField("description").orEmpty(),
        version = root.intField("version"),
        screenCount = root.intField("screen_count"),
        layerCount = root.intField("layer_count"),
        updatedAt = root.stringField("updated_at").orEmpty(),
        bg = root.stringField("bg") ?: "#F4F6F9",
        preview = preview,
    )
}

/** 解析 plan_card 消息 payload（plan.write 产出）→ 计划卡。 */
internal fun parsePlanCard(payload: JsonElement?): V2PlanCard? {
    val root = payload as? JsonObject ?: return null
    val title = root.stringField("title") ?: return null
    val steps = (root["steps"] as? JsonArray)?.toList().orEmpty().mapNotNull { element ->
        val step = element as? JsonObject ?: return@mapNotNull null
        val stepTitle = step.stringField("title") ?: return@mapNotNull null
        V2PlanStep(stepTitle, step.stringField("detail"))
    }
    val files = (root["files"] as? JsonArray)?.toList().orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { path -> path.isNotBlank() } }
    return V2PlanCard(
        title = title,
        steps = steps,
        files = files,
        planPath = root.stringField("plan_path").orEmpty(),
        markdown = root.stringField("markdown"),
    )
}

/**
 * 解析审批消息 payload.decision → 审批卡。
 *
 * 只有逐次审批（高风险命令 / 受保护操作）才转成审批卡；其它普通决策仍按文字气泡渲染，
 * 不改变既有决策中心的展示方式。
 */
internal fun parseApprovalCard(payload: JsonElement?): V2ApprovalCard? {
    val root = payload as? JsonObject ?: return null
    val decision = root["decision"] as? JsonObject ?: return null
    val kind = decision.stringField("kind") ?: return null
    if (kind != "high_risk_command" && kind != "protected_access") return null
    val id = decision.stringField("id") ?: return null
    val meta = decision["meta"] as? JsonObject
    val status = decision.stringField("status") ?: "pending"
    return V2ApprovalCard(
        id = id,
        kind = kind,
        question = decision.stringField("question").orEmpty(),
        detail = decision.stringField("context").orEmpty(),
        command = meta?.stringField("command").orEmpty(),
        reason = meta?.stringField("reason").orEmpty(),
        status = status,
        chosen = decision.stringField("chosen"),
        expired = status == "expired",
    )
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intField(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

/** 解析附件消息 payload 里的 files → [{id,name,size,mime,path}]。 */
private fun parseAttachmentFiles(payload: JsonElement?): List<V2MessageFile> {
    val root = payload as? JsonObject ?: return emptyList()
    val files = root["files"] as? JsonArray ?: return emptyList()
    return files.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val size = (obj["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        V2MessageFile(name, size)
    }
}

private fun clockLabel(raw: String?): String {
    val epoch = parseEpochSeconds(raw) ?: return "--:--"
    return java.time.Instant.ofEpochSecond(epoch)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}

// ================================================================== 需求确认（只读）

@Composable
fun V2ConfirmationScreen(
    taskId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenDecisionCenter: () -> Unit,
) {
    val vm = taskViewModel(taskId)
    val state by vm.state.collectAsState()
    val task = state.task

    LaunchedEffect(taskId) { vm.refresh(quiet = true) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "需求确认",
            subtitle = "只读 · 审批请到决策中心",
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val error = state.error
            if (error != null) item { V2Banner(error, V2Colors.rose) }

            if (state.loading && task == null) {
                item { Label("正在读取确认项…", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }
            if (task == null) {
                item { Label("任务不存在或已被清理", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }

            item {
                val items = task.confirmation?.items.orEmpty()
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Label("确认项", 11.5f, V2Colors.ink(.55f), FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    if (items.isEmpty()) {
                        Label("这个任务没有额外的需求确认项", 10.5f, V2Colors.ink(.35f))
                    } else {
                        items.forEachIndexed { index, item ->
                            if (index > 0) {
                                Spacer(Modifier.height(6.dp))
                                ThinDivider(color = V2Colors.lineSoft)
                                Spacer(Modifier.height(6.dp))
                            }
                            ConfirmationRow(item)
                        }
                    }
                    val actor = task.confirmation?.actor
                    if (!actor.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Label("确认人：$actor", 9.5f, V2Colors.ink(.32f), mono = true)
                    }
                    val updatedAt = task.confirmation?.updatedAt
                    if (!updatedAt.isNullOrBlank()) {
                        Label("更新于 ${relativeTime(updatedAt)}", 9.5f, V2Colors.ink(.32f), mono = true)
                    }
                }
            }

            val pending = task.pendingDecision
            if (pending != null) {
                item { PendingDecisionCard(pending) }
                item {
                    GhostButton(
                        text = "去决策中心处理",
                        onClick = onOpenDecisionCenter,
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = V2Colors.cyan700,
                    )
                }
            } else {
                item {
                    V2Banner("当前没有待处理的决策，Agent 会按已确认的需求继续执行。", V2Colors.cyan700)
                }
            }

            val resolved = task.decisions.filter { it.id != pending?.id && it.status != "pending" }
            if (resolved.isNotEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Label("已处理的决策", 11.5f, V2Colors.ink(.55f), FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        resolved.forEachIndexed { index, decision ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            Label(decision.question.orEmpty().ifBlank { "决策" }, 11f, V2Colors.ink(.75f))
                            Spacer(Modifier.height(2.dp))
                            Label(
                                "选择：${decision.chosen ?: "—"} · ${decision.status ?: "—"}",
                                9.5f,
                                V2Colors.ink(.35f),
                                mono = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationRow(item: ConfirmationItem) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Label(item.label.ifBlank { "确认项" }, 10.5f, V2Colors.ink(.44f), modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(8.dp))
        Label(item.value.ifBlank { "—" }, 11f, V2Colors.ink(.82f), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PendingDecisionCard(decision: Decision) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChipFor("待决策", V2Colors.amber700)
            Spacer(Modifier.weight(1f))
            Label(relativeTime(decision.resolvedAt), 9.5f, V2Colors.ink(.30f), mono = true)
        }
        Spacer(Modifier.height(8.dp))
        Label(
            decision.question.orEmpty().ifBlank { "Agent 需要你的确认" },
            12.5f,
            V2Colors.ink(.88f),
            FontWeight.Medium,
        )
        decision.context?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Label(it, 10.5f, V2Colors.ink(.48f))
        }
        val options = decision.options
        if (options.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Label("候选方案", 9.5f, V2Colors.ink(.38f))
            options.forEach { option ->
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (option.recommended) V2Colors.cyan500.copy(alpha = .06f) else V2Colors.ink(.03f))
                        .border(
                            1.dp,
                            if (option.recommended) V2Colors.accent.copy(alpha = .22f) else V2Colors.ink(.06f),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(
                            option.label + if (option.recommended) " · 推荐" else "",
                            10.5f,
                            V2Colors.ink(.78f),
                            FontWeight.Medium,
                        )
                        option.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Label(it, 9.5f, V2Colors.ink(.38f))
                        }
                    }
                }
            }
        }
        decision.recommendation?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Label("建议：$it", 10f, V2Colors.cyan800.copy(alpha = .8f))
        }
    }
}

// ================================================================== 变更列表

@Composable
fun V2ChangesScreen(
    taskId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenChange: (String) -> Unit,
) {
    val vm = taskViewModel(taskId)
    val state by vm.state.collectAsState()
    var bulk by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(taskId) {
        // 任务详情页留下的提示不带进变更页；本页操作产生的提示会在下方正常展示。
        vm.dismissNotice()
        vm.refresh(quiet = true)
    }

    val pending = state.changes.count { it.status == "pending" }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = "变更",
                subtitle = "${state.changes.size} 个文件 · 待审核 $pending",
                extraTop = extraTop,
                showBack = showBack,
                onBack = onBack,
                onRefresh = { vm.refresh() },
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val error = state.error
                if (error != null) item { V2Banner(error, V2Colors.rose) }
                val notice = state.notice
                if (notice != null) item { V2Banner(notice, V2Colors.emerald700) }

                if (state.loading && state.changes.isEmpty()) {
                    item { Label("正在读取变更…", 11f, V2Colors.ink(.35f)) }
                    return@LazyColumn
                }
                if (state.changes.isEmpty()) {
                    item {
                        Label(
                            "Agent 还没有产出文件改动。开始执行后，提案会出现在这里。",
                            11f,
                            V2Colors.ink(.35f),
                        )
                    }
                    return@LazyColumn
                }
                items(state.changes, key = { it.id }) { change ->
                    ChangeCard(change = change, onClick = { onOpenChange(change.id) })
                }
            }

            if (pending > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GhostButton(
                        text = "全部拒绝",
                        onClick = { bulk = "reject" },
                        modifier = Modifier.weight(1f),
                        contentColor = V2Colors.rose,
                        enabled = state.busyAction == null,
                    )
                    PrimaryButton(
                        text = "全部批准",
                        onClick = { bulk = "accept" },
                        modifier = Modifier.weight(1f),
                        enabled = state.busyAction == null,
                    )
                }
            }
        }

        when (bulk) {
            "accept" -> V2ActionConfirm(
                title = "批准全部提案",
                message = "将一次批准 $pending 个待审核文件改动。批准后还需要在每条提案里「应用」才会真正落盘。",
                confirmText = "全部批准",
                busy = state.busyAction != null,
                onConfirm = {
                    vm.acceptAllChanges()
                    bulk = null
                },
                onDismiss = { bulk = null },
            )
            "reject" -> V2ActionConfirm(
                title = "拒绝全部提案",
                message = "将拒绝 $pending 个待审核文件改动，这些改动不会再落盘。此操作不可撤销。",
                confirmText = "全部拒绝",
                danger = true,
                busy = state.busyAction != null,
                onConfirm = {
                    vm.rejectAllChanges()
                    bulk = null
                },
                onDismiss = { bulk = null },
            )
        }
    }
}

@Composable
private fun ChangeCard(change: Change, onClick: () -> Unit) {
    val (label, color) = changeStatusLabel(change.status)
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(change.filePath, 11.5f, V2Colors.ink(.85f), FontWeight.Medium, maxLines = 1, mono = true)
                Spacer(Modifier.height(3.dp))
                Label(
                    "${change.kindLabel} · +${change.addedLines} / -${change.removedLines}",
                    9.5f,
                    V2Colors.ink(.35f),
                    mono = true,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChipFor(label, color)
        }
        val summary = change.summary?.takeIf { it.isNotBlank() }
            ?: change.reason?.takeIf { it.isNotBlank() }
        if (summary != null) {
            Spacer(Modifier.height(6.dp))
            Label(summary, 9.5f, V2Colors.ink(.42f), maxLines = 2)
        }
        if (change.applyError != null) {
            Spacer(Modifier.height(6.dp))
            Label("落盘失败：${change.applyError}", 9.5f, V2Colors.rose.copy(alpha = .8f), maxLines = 2)
        }
    }
}

// ================================================================== 提案 Diff

private const val MAX_DIFF_LINES = 2000

@Composable
fun V2DiffScreen(
    changeId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val vm = changeViewModel(changeId)
    val state by vm.state.collectAsState()
    var tab by remember(changeId) { mutableIntStateOf(0) }
    var confirmAction by remember(changeId) { mutableStateOf<String?>(null) }
    val change = state.change

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "提案详情",
            subtitle = change?.filePath ?: changeId.take(12),
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
            onRefresh = { vm.refresh() },
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val error = state.error
            if (error != null) item { V2Banner(error, V2Colors.rose) }
            val notice = state.notice
            if (notice != null) item { V2Banner(notice, V2Colors.emerald700) }

            if (state.loading && change == null) {
                item { Label("正在读取提案…", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }
            if (change == null) {
                item { Label("提案不存在或已被清理", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }

            item { DiffSummaryCard(change) }

            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SegmentChip("统一 Diff", tab == 0) { tab = 0 }
                    SegmentChip("改动后", tab == 1) { tab = 1 }
                    SegmentChip("改动前", tab == 2) { tab = 2 }
                }
            }

            item {
                when (tab) {
                    0 -> MonoBlock(change.diff, colored = true)
                    1 -> MonoBlock(change.proposedContent ?: "", colored = false, emptyHint = "删除类提案没有「改动后」内容")
                    else -> MonoBlock(change.originalContent, colored = false, emptyHint = "新建文件没有「改动前」内容")
                }
            }
        }

        DiffActions(
            change = change,
            busy = state.busy,
            onAction = { confirmAction = it },
        )
    }

    when (confirmAction) {
        "accept" -> V2ActionConfirm(
            title = "批准这条提案",
            message = "批准后仍需「应用」才会写入磁盘，应用前可以随时拒绝。",
            confirmText = "批准",
            busy = state.busy,
            onConfirm = {
                vm.accept()
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
        "apply" -> V2ActionConfirm(
            title = "应用到磁盘",
            message = "会把这条改动真正写入工作区（先自动备份）。落盘后可以用「回滚」还原。",
            confirmText = "应用",
            busy = state.busy,
            onConfirm = {
                vm.apply()
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
        "reject" -> V2ActionConfirm(
            title = "拒绝这条提案",
            message = "拒绝后这条改动不会再落盘，且不可撤销。",
            confirmText = "拒绝",
            danger = true,
            busy = state.busy,
            onConfirm = {
                vm.reject()
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
        "rollback" -> V2ActionConfirm(
            title = "回滚这次改动",
            message = "会用备份还原文件，恢复到改动前的状态。",
            confirmText = "回滚",
            danger = true,
            busy = state.busy,
            onConfirm = {
                vm.rollback()
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
    }
    }
}

@Composable
private fun DiffSummaryCard(change: Change) {
    val (label, color) = changeStatusLabel(change.status)
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChipFor(change.kindLabel, V2Colors.ink(.5f))
            Spacer(Modifier.width(6.dp))
            StatusChipFor(label, color)
            Spacer(Modifier.weight(1f))
            Label("+${change.addedLines} / -${change.removedLines}", 10f, V2Colors.ink(.42f), mono = true)
        }
        Spacer(Modifier.height(8.dp))
        Label(change.filePath, 11.5f, V2Colors.ink(.85f), FontWeight.Medium, mono = true)
        change.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Label(it, 10.5f, V2Colors.ink(.55f))
        }
        change.reason?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Label("理由：$it", 10f, V2Colors.ink(.40f))
        }
        if (change.applyError != null) {
            Spacer(Modifier.height(8.dp))
            V2Banner("落盘失败：${change.applyError}", V2Colors.rose)
        }
        change.backupPath?.let {
            Spacer(Modifier.height(8.dp))
            Label("备份：$it", 9.5f, V2Colors.ink(.30f), mono = true, maxLines = 1)
        }
    }
}

@Composable
private fun DiffActions(change: Change?, busy: Boolean, onAction: (String) -> Unit) {
    if (change == null) return
    val (primary, secondary) = when (change.status) {
        "pending" -> "accept" to "reject"
        "accepted" -> "apply" to "reject"
        "applied" -> "rollback" to null
        else -> null to null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primary == null) {
            Label(
                "当前状态不可再操作（${changeStatusLabel(change.status).first}）",
                10f,
                V2Colors.ink(.35f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (secondary != null) {
                GhostButton(
                    text = if (secondary == "reject") "拒绝" else "取消",
                    onClick = { onAction(secondary) },
                    modifier = Modifier.weight(1f),
                    contentColor = V2Colors.rose,
                    enabled = !busy,
                )
            }
            PrimaryButton(
                text = when (primary) {
                    "accept" -> if (busy) "批准中…" else "批准"
                    "apply" -> if (busy) "落盘中…" else "应用"
                    else -> if (busy) "回滚中…" else "回滚"
                },
                onClick = { onAction(primary) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .10f) else V2Colors.surface(.35f))
            .border(
                1.dp,
                if (selected) V2Colors.accent.copy(alpha = .20f) else V2Colors.ink(.07f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(label, 10.5f, if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f))
    }
}

@Composable
private fun MonoBlock(
    text: String,
    colored: Boolean,
    emptyHint: String = "没有内容",
) {
    if (text.isBlank()) {
        Label(emptyHint, 10.5f, V2Colors.ink(.32f))
        return
    }
    val lines = remember(text) {
        val all = text.lines()
        if (all.size <= MAX_DIFF_LINES) {
            all
        } else {
            all.take(MAX_DIFF_LINES) + "… 已截断（共 ${all.size} 行，仅显示前 $MAX_DIFF_LINES 行）"
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.surface(.55f))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            lines.forEach { line ->
                Label(
                    text = line.ifEmpty { " " },
                    size = 10f,
                    color = if (colored) diffLineColor(line) else V2Colors.ink(.68f),
                    mono = true,
                )
            }
        }
    }
}

private fun diffLineColor(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") -> V2Colors.ink(.32f)
    line.startsWith("@@") -> V2Colors.cyan700
    line.startsWith("+") -> V2Colors.emerald700
    line.startsWith("-") -> V2Colors.rose700
    else -> V2Colors.ink(.62f)
}

// ================================================================== Run 记录

@Composable
fun V2RunScreen(
    taskId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val vm = taskViewModel(taskId)
    val state by vm.state.collectAsState()
    val run = state.run

    LaunchedEffect(taskId) { vm.refresh(quiet = true) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "Run 记录",
            subtitle = run?.runId?.take(12) ?: "尚未开始",
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
            onRefresh = { vm.refresh() },
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val error = state.error
            if (error != null) item { V2Banner(error, V2Colors.rose) }

            if (state.loading && run == null) {
                item { Label("正在读取运行记录…", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }
            if (run == null) {
                item {
                    Label(
                        "任务还没有运行记录。回到任务详情点「开始执行」后，这里会显示 Agent 的运行状态。",
                        11f,
                        V2Colors.ink(.35f),
                    )
                }
                return@LazyColumn
            }

            item {
                val (label, color) = runStatusStyle(run.status)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusChipFor(label, color)
                        Spacer(Modifier.weight(1f))
                        Label(run.runId, 9.5f, V2Colors.ink(.32f), mono = true, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    RunRow("Agent", run.agentId.orEmpty().ifBlank { "—" })
                    RunRow("进程 PID", run.pid?.toString() ?: "—")
                    RunRow("开始", run.startedAt?.let { relativeTime(it) } ?: "—")
                    RunRow("最后心跳", run.lastHeartbeatAt?.let { relativeTime(it) } ?: "—")
                    RunRow("最后输出", run.lastOutputAt?.let { relativeTime(it) } ?: "—")
                    run.finishReason?.takeIf { it.isNotBlank() }?.let { RunRow("结束原因", it) }
                    run.error?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        V2Banner(it, V2Colors.rose)
                    }
                }
            }
            if (run.status == "running") {
                item {
                    GhostButton(
                        text = "停止 Agent",
                        onClick = { vm.interruptRun() },
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = V2Colors.amber700,
                        icon = Icons.Outlined.Stop,
                        enabled = state.busyAction == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Label(key, 10f, V2Colors.ink(.38f), modifier = Modifier.width(76.dp))
        Spacer(Modifier.width(8.dp))
        Label(value, 10.5f, V2Colors.ink(.75f), modifier = Modifier.weight(1f), mono = true)
    }
}

// ================================================================== 终端列表

@Composable
fun V2TerminalListScreen(
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val vm: TerminalListViewModel = viewModel(key = "v2-terminal-list")
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "终端",
            subtitle = "在服务端工作区执行命令",
            extraTop = extraTop,
            showBack = showBack,
            onBack = onBack,
            onRefresh = { vm.refresh() },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PrimaryButton(
                text = if (state.creating) "正在创建…" else "新建终端会话",
                onClick = { if (!state.creating) vm.createSession(onOpenSession) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Add,
                enabled = !state.creating,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error
            if (error != null) item { V2Banner(error, V2Colors.rose) }

            if (state.loading && state.sessions.isEmpty()) {
                item { Label("正在读取会话…", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }
            if (state.sessions.isEmpty()) {
                item { Label("还没有终端会话。点上面的「新建终端会话」开一个。", 11f, V2Colors.ink(.35f)) }
                return@LazyColumn
            }
            items(state.sessions, key = { it.id }) { session ->
                TerminalSessionCard(session) { onOpenSession(session.id) }
            }
        }
    }
}

@Composable
private fun TerminalSessionCard(session: TerminalSession, onClick: () -> Unit) {
    val (label, color) = when (session.status) {
        "active" -> "进行中" to V2Colors.cyan500
        "stopped" -> "已停止" to V2Colors.ink(.30f)
        "closed" -> "已关闭" to V2Colors.ink(.30f)
        else -> session.status to V2Colors.ink(.35f)
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (session.status == "active") LiveDot(V2Colors.cyan500) else Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label(session.id.take(10), 11.5f, V2Colors.ink(.85f), FontWeight.Medium, mono = true)
                Spacer(Modifier.height(2.dp))
                Label(session.workspaceRoot ?: "（未配置工作区）", 9.5f, V2Colors.ink(.35f), maxLines = 1, mono = true)
            }
            Spacer(Modifier.width(8.dp))
            StatusChipFor(label, color)
        }
        if (!session.createdAt.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Label("创建于 ${relativeTime(session.createdAt)}", 9f, V2Colors.ink(.28f), mono = true)
        }
    }
}

// ================================================================== 终端会话

@Composable
fun V2TerminalSessionScreen(
    sessionId: String,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val vm = terminalViewModel(sessionId)
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var stopMode by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.lines.lastIndex) }
        }
    }

    val streamLabel = when (state.streamState) {
        "live" -> "实时"
        "connecting" -> "连接中"
        "closed" -> "已收流"
        else -> "已断开"
    }
    val streamColor = when (state.streamState) {
        "live" -> V2Colors.emerald
        "connecting" -> V2Colors.amber500
        else -> V2Colors.rose500
    }
    val active = state.session?.status == "active"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            DetailHeader(
                title = "终端会话",
                subtitle = state.session?.id?.take(12) ?: sessionId.take(12),
                extraTop = extraTop,
                showBack = showBack,
                onBack = onBack,
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChipFor(streamLabel, streamColor)
                StatusChipFor(if (active) "会话进行中" else "会话已结束", V2Colors.ink(.4f))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (state.loading) {
                    item { Label("正在读取历史输出…", 11f, V2Colors.ink(.35f)) }
                }
                if (!state.loading && state.lines.isEmpty()) {
                    item {
                        Label(
                            "这里还没有输出。在下面输入一条命令（例如 pwd），输出会实时出现。",
                            11f,
                            V2Colors.ink(.35f),
                        )
                    }
                }
                items(state.lines) { line ->
                    Label(
                        text = line.text.ifEmpty { " " },
                        size = 10f,
                        color = when (line.kind) {
                            LineKind.ECHO -> V2Colors.cyan700
                            LineKind.OUTPUT -> V2Colors.ink(.72f)
                            LineKind.SYSTEM -> V2Colors.ink(.36f)
                            LineKind.EXIT -> V2Colors.emerald700
                        },
                        mono = true,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val error = state.error
                if (error != null) V2Banner(error, V2Colors.rose)
                val notice = state.notice
                if (notice != null) {
                    V2Banner(notice, V2Colors.cyan700, Modifier.clickable { vm.dismissNotice() })
                }
                val streamError = state.streamError
                if (streamError != null) V2Banner(streamError, V2Colors.amber700)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(V2Colors.surface(.72f))
                        .border(1.dp, V2Colors.line, RoundedCornerShape(14.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (state.command.isEmpty()) {
                            Label("命令（在工作区里执行）", 11f, V2Colors.ink(.26f), mono = true)
                        }
                        BasicTextField(
                            value = state.command,
                            onValueChange = vm::onCommandChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = active && !state.running,
                            textStyle = TextStyle(
                                fontSize = 11.sp,
                                color = V2Colors.ink(.85f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(V2Colors.accent),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { vm.runCommand() }),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val enabled = active && !state.running && state.command.isNotBlank()
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (enabled) V2Colors.cyan400 else V2Colors.ink(.08f))
                            .clickable(enabled = enabled) { vm.runCommand() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = "执行",
                            tint = if (enabled) V2Colors.actionInk else V2Colors.ink(.25f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "Stop",
                        onClick = { stopMode = "stop" },
                        modifier = Modifier.weight(1f),
                        enabled = active,
                    )
                    GhostButton(
                        text = "Force Stop",
                        onClick = { stopMode = "force" },
                        modifier = Modifier.weight(1f),
                        contentColor = V2Colors.rose,
                        enabled = active,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        when (stopMode) {
            "stop" -> V2ActionConfirm(
                title = "停止会话",
                message = "会向正在运行的命令发送停止请求，已经产生的输出会保留。",
                confirmText = "停止",
                onConfirm = {
                    vm.stop(force = false)
                    stopMode = null
                },
                onDismiss = { stopMode = null },
            )
            "force" -> V2ActionConfirm(
                title = "强制停止",
                message = "会直接终止工作区里的进程，未保存的中间结果可能丢失。",
                confirmText = "强制停止",
                danger = true,
                onConfirm = {
                    vm.stop(force = true)
                    stopMode = null
                },
                onDismiss = { stopMode = null },
            )
        }
    }
}

// ================================================================== 状态映射

private fun taskStatusLabel(status: String?): Pair<String, Color> = when (status) {
    "running" -> "执行中" to V2Colors.cyan500
    "waiting_for_user_decision" -> "等待确认" to V2Colors.amber500
    "done", "completed", "succeeded" -> "已完成" to V2Colors.emerald
    "failed", "error" -> "失败" to V2Colors.rose500
    "canceled", "cancelled" -> "已取消" to V2Colors.ink(.30f)
    "pending", "queued" -> "排队中" to V2Colors.ink(.35f)
    else -> (status?.takeIf { it.isNotBlank() } ?: "未知") to V2Colors.ink(.35f)
}

private fun changeStatusLabel(status: String): Pair<String, Color> = when (status) {
    "pending" -> "待审核" to V2Colors.amber700
    "accepted" -> "已批准" to V2Colors.accent
    "applying" -> "落盘中" to V2Colors.cyan500
    "applied" -> "已落盘" to V2Colors.emerald700
    "rejected" -> "已拒绝" to V2Colors.ink(.32f)
    "failed" -> "落盘失败" to V2Colors.rose500
    "expired" -> "已失效" to V2Colors.ink(.32f)
    "rolled_back" -> "已回滚" to V2Colors.ink(.32f)
    else -> status to V2Colors.ink(.35f)
}

private fun runStatusLabel(status: String): String = when (status) {
    "pending", "queued" -> "排队中"
    "starting" -> "启动中"
    "running" -> "运行中"
    "cancelling" -> "停止中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "timeout" -> "超时"
    "cancelled", "canceled" -> "已取消"
    else -> status
}

private fun runStatusStyle(status: String): Pair<String, Color> {
    val color = when (status) {
        "running", "starting" -> V2Colors.cyan500
        "completed" -> V2Colors.emerald700
        "failed" -> V2Colors.rose500
        "timeout", "cancelling" -> V2Colors.amber700
        else -> V2Colors.ink(.35f)
    }
    return runStatusLabel(status) to color
}

private fun resultText(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.take(400)
    else -> element.toString().takeIf { it.isNotBlank() }?.take(400)
}
