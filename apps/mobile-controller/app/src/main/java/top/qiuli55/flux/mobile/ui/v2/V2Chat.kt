package top.qiuli55.flux.mobile.ui.v2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.TaskContextUsage
import top.qiuli55.flux.mobile.data.TaskRunSummary
import top.qiuli55.flux.mobile.data.UploadedAttachment
import java.util.Locale

/** 对话页运行态：会话、Agent、模型都由当前设备配置推导。 */
data class V2ChatState(
    val conversationId: String?,
    val title: String,
    val device: String,
    val agentId: String,
    val model: String,
    /** 模型来自哪条 key 库条目；null = 原生模型。 */
    val modelKeyId: String? = null,
    /** 新建任务时的决策方式：auto = AI 自己定，manual = 停下来问我。 */
    val decisionMode: String = "auto",
    /** 对话权限模式：plan = 只读 + 计划文档；normal = 高风险命令才问；full = 跳过逐步审批。 */
    val permissionMode: String = "normal",
    /** 从上一段对话接手时的前情摘要；非空时新对话以摘要卡开篇。 */
    val pendingSummary: String? = null,
    /** 摘要来源任务 id，随新对话首条消息一起带给后端（仅溯源）。 */
    val summaryFrom: String? = null,
)

@Composable
fun V2ChatScreen(
    chat: V2ChatState,
    messages: List<V2Message>,
    extraTop: androidx.compose.ui.unit.Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onDelete: () -> Unit,
    onOpenAgentPicker: () -> Unit,
    onSend: (String) -> Unit,
    /** 已上传、等待随下一条消息发送的附件。 */
    attachments: List<UploadedAttachment> = emptyList(),
    /** 附件正在上传：输入区显示「上传中…」，发送按钮禁用。 */
    uploading: Boolean = false,
    onAddAttachments: (List<Uri>) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    /** 首条消息发出后等待 Agent 回复。 */
    sending: Boolean = false,
    /** 流式回复：已经收到的增量正文与状态提示。 */
    liveText: String = "",
    liveLabel: String? = null,
    liveActive: Boolean = false,
    /** 本轮 Run 的执行轨迹：思考刻度与命令卡（Run 结束后保留可回看）。 */
    runItems: List<V2RunItem> = emptyList(),
    /** run_id → 该轮的耗时与概览：历史答案气泡上方的「任务耗时」入口用。 */
    runSummaries: Map<String, TaskRunSummary> = emptyMap(),
    /** 打开某一轮的执行详情（概览 / 思考过程 / 命令输出）。 */
    onOpenRunTrace: (String) -> Unit = {},
    /** 对话尚未加载出来：暂时不允许发送。 */
    locked: Boolean = false,
    /** 真实任务对话没有「删除会话」接口，隐藏该按钮。 */
    showDelete: Boolean = true,
    /** 能不能切换执行 Agent（执行中也可以：排队到本轮结束后生效）。 */
    canSwitchAgent: Boolean = true,
    /** 能不能切换模型（执行中不行）。 */
    canSwitchModel: Boolean = true,
    /** 已排队的切换目标 id / 展示名：标题栏与下拉里标出「本轮结束后切换」。 */
    pendingAgentId: String? = null,
    pendingAgentLabel: String? = null,
    notice: String? = null,
    /** 关掉会话内提示条（上下文/审批类提示可手动收起）。 */
    onDismissNotice: () -> Unit = {},
    suggestions: List<String> = demoChatSuggestions,
    /** 真实任务对话没有「从某设备接续」这一层，隐藏那条横幅。 */
    showHandoff: Boolean = true,
    /** 新建对话时切换任务级决策方式（auto / manual）。 */
    onDecisionModeChange: (String) -> Unit = {},
    /** 非空时点击 Agent 名称展开的是就地下拉栏（任务对话切换执行 Agent）。 */
    agentOptions: List<V2AgentOption> = emptyList(),
    onSwitchAgent: (String) -> Unit = {},
    /** 当前 Agent 支持的模型；非空时 Agent 名下提供模型下拉。 */
    modelOptions: List<V2ModelOption> = emptyList(),
    onSwitchModel: (V2ModelOption) -> Unit = {},
    /** 上下文占用：真实任务对话才有；新建对话传 null，不显示占用条。 */
    contextUsage: TaskContextUsage? = null,
    /** 「上下文快满」提示条是否可见（页面按等级与用户是否选择继续计算）。 */
    contextPromptVisible: Boolean = false,
    onContinueContext: () -> Unit = {},
    onStartNewWithSummary: () -> Unit = {},
    handoffBusy: Boolean = false,
    /** 对话里的链接：交给 App 内嵌浏览器打开，不走外部浏览器。 */
    onOpenUrl: (String) -> Unit = {},
    /** 设计稿卡片：点开进入原生画布预览。 */
    onOpenDesign: (String) -> Unit = {},
    /** 切换对话权限模式（plan / normal / full）；只影响后续消息。 */
    onPermissionModeChange: (String) -> Unit = {},
    /** 审批卡：允许本次执行 / 拒绝本次执行。 */
    onAllowApproval: (String) -> Unit = {},
    onDenyApproval: (String) -> Unit = {},
    /** 下拉刷新：真实任务对话 = 重新拉取任务与消息；null 时不启用该手势。 */
    onRefresh: (suspend () -> Unit)? = null,
    /** 任务正在执行：输入框上方常驻停止条，随时能停，不用滚到消息尾部。 */
    taskRunning: Boolean = false,
    /** 停止请求已发出、等服务端确认。 */
    stopping: Boolean = false,
    onStopTask: () -> Unit = {},
    /** 审批请求正在提交：底部审核条的按钮临时禁用。 */
    approvalBusy: Boolean = false,
) {
    val agent = demoAgent(chat.agentId)
    val currentModelOption = modelOptions.firstOrNull {
        it.model == chat.model && it.keyId == chat.modelKeyId
    }
    val currentModelLabel = currentModelOption?.label ?: chat.model.ifBlank { "默认模型" }
    val listState = rememberLazyListState()
    var input by remember(chat.conversationId) { mutableStateOf("") }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var modeSheetOpen by remember { mutableStateOf(false) }
    // 执行轨迹的展开状态按对话记忆：用户收起后，流式新增的思考/命令不再把它顶开
    var timelineExpanded by remember(chat.conversationId) { mutableStateOf(true) }
    // 待审批卡固定在输入框上方：AI 输出再长也不用往回滚
    val pendingApproval = messages.lastOrNull {
        it.kind == "decision" && it.approvalCard?.status == "pending" && it.approvalCard.expired != true
    }?.approvalCard
    val inputEnabled = !locked && !sending
    // Plan 模式只读：不允许挂附件（附件通常会触发写盘与执行）
    val canPickFiles = inputEnabled && !uploading && chat.permissionMode != "plan"

    val context = LocalContext.current
    val haptics = LocalV2Haptics.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var sendFlying by remember { mutableStateOf(false) }
    var bubbleMenu by remember { mutableStateOf<BubbleMenuTarget?>(null) }

    // 发送纸飞机：向右上飞出 18px 后淡出，160ms 后复位
    LaunchedEffect(sendFlying) {
        if (sendFlying) {
            delay(170)
            sendFlying = false
        }
    }
    val fly by animateFloatAsState(
        targetValue = if (sendFlying) 1f else 0f,
        animationSpec = tween(160),
        label = "chat-send-fly",
    )
    val composerMinHeight by animateDpAsState(
        targetValue = if (inputFocused || input.isNotBlank()) 56.dp else 44.dp,
        animationSpec = tween(160),
        label = "chat-composer-height",
    )
    val composerBorder by animateColorAsState(
        targetValue = if (inputFocused) V2Colors.accent.copy(alpha = .45f) else V2Colors.line,
        animationSpec = tween(160),
        label = "chat-composer-border",
    )

    // 微信式：消息直接出现，不做错峰入场；进入对话与新增消息都瞬时贴底，不慢慢滚到底。
    // 目标索引按当前内容自己算（摘要卡 / 消息 / 执行轨迹 / 流式气泡 / 等待行），
    // 不能依赖 layoutInfo（首帧还没量出来，会得到 0 并停在最早的消息）。
    val bottomIndex = messages.size +
        (if (!chat.pendingSummary.isNullOrBlank()) 1 else 0) +
        (if (runItems.isNotEmpty()) 1 else 0) +
        (if (liveActive && (runItems.isEmpty() || liveText.isNotBlank())) 1 else 0) +
        (if (!liveActive && sending) 1 else 0) - 1
    LaunchedEffect(messages.size, liveActive, liveText.length, runItems.size, sending, chat.conversationId) {
        if (bottomIndex < 0) return@LaunchedEffect
        // 等首帧布局完成再定位：还没量出高度时滚动会被夹到顶部
        withFrameNanos { }
        listState.scrollToItem(bottomIndex)
    }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    // 气泡长按菜单：以气泡在根容器里的位置为锚点，越界时翻到上方
    val openBubbleMenu: (V2Message, Rect) -> Unit = { message, rect ->
        haptics?.play(V2HapticLevel.TICK)
        val leftPx = v2ClampMenuLeftPx(
            bubbleLeftPx = rect.left,
            rootWidthPx = with(density) { maxWidth.toPx() },
            menuWidthPx = with(density) { 146.dp.toPx() },
            marginPx = with(density) { 8.dp.toPx() },
        )
        val left = with(density) { leftPx.toDp() }
        val below = with(density) { rect.bottom.toDp() } + 6.dp
        val above = with(density) { rect.top.toDp() } - 138.dp
        val top = if (below + 132.dp > maxHeight - 8.dp) above.coerceAtLeast(8.dp) else below
        bubbleMenu = BubbleMenuTarget(message.text, left, top)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 6.dp),
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
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = canSwitchAgent) {
                            // 任务对话：就地下拉切换执行 Agent；新建对话：打开 Agent 与模型选择面板。
                            if (agentOptions.isNotEmpty()) agentMenuOpen = true else onOpenAgentPicker()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(tint = agent.tint, size = 36.dp, icon = Icons.Outlined.SmartToy)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(agent.name, 13f, V2Colors.ink(.95f), FontWeight.Medium, maxLines = 1)
                            if (!agent.nativeStreaming) {
                                Spacer(Modifier.width(5.dp))
                                NonStreamingBadge()
                            }
                            if (canSwitchAgent) {
                                Icon(
                                    Icons.Outlined.KeyboardArrowDown,
                                    null,
                                    tint = V2Colors.ink(.32f),
                                    modifier = Modifier.padding(start = 3.dp).size(12.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(V2Colors.emerald, CircleShape))
                            Spacer(Modifier.width(4.dp))
                            Label(
                                "${chat.title} · ${chat.device}",
                                9.5f,
                                V2Colors.ink(.36f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (canSwitchModel && modelOptions.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(V2Colors.cyan500.copy(alpha = .10f))
                                        .border(
                                            1.dp,
                                            V2Colors.accent.copy(alpha = .20f),
                                            RoundedCornerShape(999.dp),
                                        )
                                        .clickable {
                                            agentMenuOpen = false
                                            modelMenuOpen = true
                                        }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Label(
                                        currentModelLabel,
                                        8.5f,
                                        V2Colors.cyan800,
                                        mono = true,
                                        maxLines = 1,
                                    )
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        null,
                                        tint = V2Colors.cyan800.copy(alpha = .7f),
                                        modifier = Modifier.padding(start = 2.dp).size(10.dp),
                                    )
                                }
                            }
                            // 已排队的 Agent 切换：告诉用户当前这轮还是原 Agent，结束后才换人
                            if (pendingAgentLabel != null) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(V2Colors.amber.copy(alpha = .12f))
                                        .border(
                                            1.dp,
                                            V2Colors.amber.copy(alpha = .30f),
                                            RoundedCornerShape(999.dp),
                                        )
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Label(
                                        "→ $pendingAgentLabel · 本轮结束后",
                                        8.5f,
                                        V2Colors.amber700,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                DropdownMenu(
                    expanded = agentMenuOpen && agentOptions.isNotEmpty(),
                    onDismissRequest = { agentMenuOpen = false },
                    modifier = Modifier.background(V2Colors.surface),
                ) {
                    agentOptions.forEach { option ->
                        val selected = option.agent.id == chat.agentId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Label(option.agent.name, 12f, V2Colors.ink(.90f), FontWeight.Medium)
                                        if (!option.agent.nativeStreaming) {
                                            Spacer(Modifier.width(5.dp))
                                            NonStreamingBadge()
                                        }
                                        if (selected) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Outlined.Check,
                                                null,
                                                tint = V2Colors.cyan700,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Label(
                                        option.models.firstOrNull()
                                            ?.let { "${option.agent.role} · ${it.label}" }
                                            ?: option.agent.role,
                                        9.5f,
                                        V2Colors.ink(.40f),
                                        maxLines = 1,
                                    )
                                    if (option.agent.id == pendingAgentId) {
                                        Spacer(Modifier.height(2.dp))
                                        Label(
                                            "本轮结束后切换",
                                            8.5f,
                                            V2Colors.amber700,
                                            mono = true,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                agentMenuOpen = false
                                // 已排队时再点当前 Agent = 撤销排队，后端会把 pending 清掉
                                if (!selected || pendingAgentId != null) onSwitchAgent(option.agent.id)
                            },
                        )
                    }
                }

                DropdownMenu(
                    expanded = modelMenuOpen && modelOptions.isNotEmpty(),
                    onDismissRequest = { modelMenuOpen = false },
                    modifier = Modifier.background(V2Colors.surface),
                ) {
                    modelOptions.forEach { option ->
                        val selected = option.model == chat.model && option.keyId == chat.modelKeyId
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Label(option.label, 12f, V2Colors.ink(.88f), mono = true)
                                    if (selected) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Outlined.Check,
                                            null,
                                            tint = V2Colors.cyan700,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            },
                            onClick = {
                                modelMenuOpen = false
                                if (!selected) onSwitchModel(option)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                icon = Icons.Outlined.ShowChart,
                onClick = onOpenLogs,
                size = 32.dp,
                iconSize = 14.dp,
                background = V2Colors.surface(.55f),
                tint = V2Colors.cyan700.copy(alpha = .8f),
            )
            if (showDelete) {
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    icon = Icons.Outlined.Delete,
                    onClick = onDelete,
                    size = 32.dp,
                    iconSize = 14.dp,
                    background = V2Colors.surface(.55f),
                    tint = V2Colors.rose.copy(alpha = .7f),
                )
            }
        }

        if (showHandoff) {
            HandoffBanner(device = chat.device)
        }

        // 上下文占用条：真实对话常驻；新建对话还没有用量，不占位置。
        if (chat.conversationId != null && contextUsage != null) {
            ContextUsageStrip(contextUsage)
        }

        if (notice != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.amber500.copy(alpha = .08f))
                    .border(1.dp, V2Colors.amber500.copy(alpha = .22f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(notice, 10f, V2Colors.amber700, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭提示",
                    tint = V2Colors.amber700.copy(alpha = .7f),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable(onClick = onDismissNotice),
                )
            }
        }

        val messageList: @Composable () -> Unit = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusManager.clearFocus() },
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!chat.pendingSummary.isNullOrBlank()) {
                    // 新对话开篇：只带上一段对话的摘要，不搬旧消息
                    item { SummaryCard(chat.pendingSummary, "刚刚", note = "从上一段对话接手") }
                }
                itemsIndexed(messages) { index, message ->
                    // 不做入场动画：像微信一样，消息直接出现。
                    // 待审批卡已固定在输入框上方，列表里不再重复渲染（避免两处按钮）。
                    val pinned = message.kind == "decision" &&
                        message.approvalCard?.id == pendingApproval?.id
                    if (pinned) return@itemsIndexed
                    MessageRow(
                        message = message,
                        run = runSummaries[message.runId],
                        onOpenRunTrace = onOpenRunTrace,
                        onOpenUrl = onOpenUrl,
                        onOpenDesign = onOpenDesign,
                        onLongPress = openBubbleMenu,
                    )
                }
                if (runItems.isNotEmpty()) {
                    // codex 式的执行轨迹：思考与命令单独成卡，最终答复仍是下面的助手消息。
                    // 固定 key：上方新增消息时卡片不会被当成新 item 重建（否则展开状态被重置）。
                    item(key = "run-timeline") {
                        RunTimelineCard(
                            items = runItems,
                            active = liveActive,
                            expanded = timelineExpanded,
                            onToggle = { timelineExpanded = !timelineExpanded },
                        )
                    }
                }
                if (liveActive && (runItems.isEmpty() || liveText.isNotBlank())) {
                    item(key = "live-streaming") { StreamingRow(liveText, liveLabel, onOpenUrl) }
                } else if (sending) {
                    item(key = "waiting-agent") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Label("Agent 正在处理…", 9f, V2Colors.ink(.30f), mono = true)
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val refresh = onRefresh
            if (refresh != null) {
                V2PullRefresh(
                    modifier = Modifier.fillMaxSize(),
                    onRefresh = refresh,
                ) { messageList() }
            } else {
                messageList()
            }
        }

        if (suggestions.isNotEmpty() && !locked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { suggestion ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(V2Colors.surface(.45f))
                            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(999.dp))
                            .v2Pressable(pressScale = .96f, enabled = inputEnabled) { onSend(suggestion) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Label(suggestion, 10f, V2Colors.ink(.42f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 待审批：固定在输入框上方，AI 输出再长也不用拉回去点同意
        if (pendingApproval != null) {
            ApprovalDock(
                card = pendingApproval,
                busy = approvalBusy,
                onAllow = onAllowApproval,
                onDeny = onDenyApproval,
            )
            Spacer(Modifier.height(4.dp))
        }

        // 执行中：停止条固定在输入区上方，消息滚到哪里都能停
        if (taskRunning) {
            RunningStopBar(stopping = stopping, onStop = onStopTask)
            Spacer(Modifier.height(2.dp))
        }

        // 对话权限模式：常驻胶囊，点开四选一面板；只影响后续消息
        PermissionModePill(chat.permissionMode, enabled = !locked) { modeSheetOpen = true }
        Spacer(Modifier.height(2.dp))

        if (chat.conversationId == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("决策方式", 9.5f, V2Colors.ink(.32f), mono = true)
                DecisionModeChip("AI 自己定", chat.decisionMode != "manual") {
                    onDecisionModeChange("auto")
                }
                DecisionModeChip("停下来问我", chat.decisionMode == "manual") {
                    onDecisionModeChange("manual")
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // 上下文将满：就在输入框上方问一句，不打断正在进行的对话
        if (contextPromptVisible && contextUsage != null) {
            ContextPromptCard(
                usage = contextUsage,
                busy = handoffBusy,
                onContinue = onContinueContext,
                onNewChat = onStartNewWithSummary,
            )
            Spacer(Modifier.height(6.dp))
        }

        // 完全访问 / 免审核：输入框上方常驻红色提示，提醒逐步审批已跳过
        if (chat.permissionMode == "full" || chat.permissionMode == "unattended") {
            FullAccessNotice(chat.permissionMode)
            Spacer(Modifier.height(4.dp))
        }

        // 输入框上方：待发送附件 / 上传中状态；发送成功后由状态层清空
        if (attachments.isNotEmpty() || uploading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uploading) UploadingAttachmentChip()
                attachments.forEach { file ->
                    AttachmentChip(
                        name = file.name,
                        size = file.size,
                        onRemove = { onRemoveAttachment(file.id) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = composerMinHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(V2Colors.surface(.86f), V2Colors.surface(.64f)),
                    ),
                )
                .border(1.dp, composerBorder, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachmentPickerButton(enabled = canPickFiles, onPicked = onAddAttachments)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Label(
                        if (locked) "正在加载对话…"
                        else if (uploading) "附件上传中…"
                        else if (sending) "等待 ${agent.name} 回复…"
                        else if (chat.permissionMode == "plan") "让 Agent 先出计划，不动手…"
                        else "继续向 ${agent.name} 发送",
                        12f,
                        V2Colors.ink(.26f),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocus)
                        .onFocusChanged { inputFocused = it.isFocused },
                    enabled = inputEnabled,
                    textStyle = TextStyle(fontSize = 12.sp, color = V2Colors.ink(.85f)),
                    cursorBrush = SolidColor(V2Colors.accent),
                    maxLines = 4,
                )
            }
            Spacer(Modifier.width(8.dp))
            val enabled = (input.isNotBlank() || attachments.isNotEmpty()) && inputEnabled && !uploading
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled || inputFocused) V2Colors.cyan400 else V2Colors.ink(.08f))
                    .v2Pressable(
                        enabled = enabled,
                        pressScale = .94f,
                        haptic = V2HapticLevel.SUCCESS,
                    ) {
                        val text = input.trim()
                        input = ""
                        sendFlying = true
                        onSend(text)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (enabled || inputFocused) V2Colors.actionInk else V2Colors.ink(.25f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            translationX = 18.dp.toPx() * fly
                            translationY = -18.dp.toPx() * fly
                            alpha = 1f - fly
                        },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    // 模式面板与内容平级叠放，避免嵌在 Column 里拿不到高度（设计工作室踩过的坑）
    if (modeSheetOpen) {
        PermissionModeSheet(
            mode = chat.permissionMode,
            onSelect = { mode ->
                modeSheetOpen = false
                onPermissionModeChange(mode)
            },
            onDismiss = { modeSheetOpen = false },
        )
    }
    // 气泡长按菜单：透明遮罩关闭，菜单按气泡位置浮层显示
    val menu = bubbleMenu
    if (menu != null) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { bubbleMenu = null }
                },
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(120)) + scaleIn(initialScale = .94f, animationSpec = tween(160)),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = .96f, animationSpec = tween(120)),
                modifier = Modifier.offset(x = menu.left, y = menu.top),
            ) {
                BubbleMenuCard(
                    canResend = inputEnabled,
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("消息", menu.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        bubbleMenu = null
                    },
                    onQuote = {
                        input = menu.text
                        bubbleMenu = null
                        inputFocus.requestFocus()
                    },
                    onResend = {
                        bubbleMenu = null
                        if (inputEnabled) {
                            onSend(menu.text)
                        } else {
                            Toast.makeText(context, "Agent 正在回复，稍后再重发", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
    }
}

/** 长按气泡后浮出的菜单目标：文案 + 锚点位置。 */
private data class BubbleMenuTarget(
    val text: String,
    val left: Dp,
    val top: Dp,
)

@Composable
private fun BubbleMenuCard(
    canResend: Boolean,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onResend: () -> Unit,
) {
    Column(
        Modifier
            .width(146.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface(.98f))
            .border(1.dp, V2Colors.ink(.09f), RoundedCornerShape(16.dp))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        BubbleMenuItem(Icons.Outlined.ContentCopy, "复制", enabled = true, onClick = onCopy)
        BubbleMenuItem(Icons.Outlined.FormatQuote, "引用", enabled = true, onClick = onQuote)
        BubbleMenuItem(Icons.Outlined.Refresh, "重发", enabled = canResend, onClick = onResend)
    }
}

@Composable
private fun BubbleMenuItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .v2Pressable(enabled = enabled, pressScale = .97f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = V2Colors.ink(if (enabled) .42f else .18f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Label(label, 12f, V2Colors.ink(if (enabled) .78f else .22f))
    }
}

@Composable
private fun DecisionModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .14f) else V2Colors.surface(.45f))
            .border(
                1.dp,
                if (selected) V2Colors.accent.copy(alpha = .35f) else V2Colors.ink(.07f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Label(
            text,
            9.5f,
            if (selected) V2Colors.cyan800 else V2Colors.ink(.42f),
            if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun HandoffBanner(device: String) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
            append("已从 $device 接续")
        }
        append("：历史消息已同步，这里发出的内容会实时回传电脑端。")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.cyan500.copy(alpha = .07f))
            .border(1.dp, V2Colors.accent.copy(alpha = .15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Outlined.Devices,
            null,
            tint = V2Colors.cyan800.copy(alpha = .7f),
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Text(
            text = text,
            color = V2Colors.cyan800.copy(alpha = .85f),
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ContextUsageStrip(usage: TaskContextUsage) {
    val tone = when (usage.level) {
        "full" -> V2Colors.rose
        "warning" -> V2Colors.amber
        else -> V2Colors.cyan500
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("上下文", 8.5f, V2Colors.ink(.28f), mono = true)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(V2Colors.ink(.07f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(usage.percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(tone),
            )
        }
        Spacer(Modifier.width(8.dp))
        Label("${usage.percent}%", 8.5f, tone, mono = true)
    }
}

@Composable
private fun ContextPromptCard(
    usage: TaskContextUsage,
    busy: Boolean,
    onContinue: () -> Unit,
    onNewChat: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.amber500.copy(alpha = .09f))
            .border(1.dp, V2Colors.amber500.copy(alpha = .26f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.History,
                null,
                tint = V2Colors.amber700,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label("上下文快满了", 10.5f, V2Colors.amber700, weight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Label("已用 ${usage.percent}%", 9f, V2Colors.amber700, mono = true)
        }
        Spacer(Modifier.height(4.dp))
        Label(
            if (usage.level == "full") {
                "再聊下去，最早的消息就会被挤出模型视野。新建一段对话，这段的摘要会一起带过去。"
            } else {
                "再聊几轮，最早的消息就会被挤出模型视野。要新建一段对话吗？"
            },
            9.5f,
            V2Colors.ink(.55f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PromptAction("继续聊", primary = false, enabled = !busy, onClick = onContinue)
            PromptAction(
                if (busy) "正在生成摘要…" else "新建并带摘要",
                primary = true,
                enabled = !busy,
                onClick = onNewChat,
            )
        }
    }
}

@Composable
private fun PromptAction(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) V2Colors.amber else V2Colors.surface(.58f))
            .border(
                1.dp,
                if (primary) Color.Transparent else V2Colors.ink(.10f),
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Label(
            text,
            9.5f,
            if (primary) Color.White else V2Colors.ink(.55f),
            weight = FontWeight.Medium,
        )
    }
}

/** 执行中的停止条：常驻在输入区上方，消息滚到哪里都能一键停下。 */
@Composable
private fun RunningStopBar(stopping: Boolean, onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(V2Colors.emerald.copy(alpha = .06f))
            .border(1.dp, V2Colors.emerald.copy(alpha = .20f), RoundedCornerShape(999.dp))
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(V2Colors.emerald, CircleShape))
        Spacer(Modifier.width(6.dp))
        Label("Agent 正在执行", 9.5f, V2Colors.emerald700, weight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (stopping) V2Colors.rose.copy(alpha = .45f) else V2Colors.rose)
                .v2Pressable(enabled = !stopping, pressScale = .94f, haptic = V2HapticLevel.DENY) { onStop() }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Label(
                if (stopping) "停止中…" else "停止",
                9.5f,
                Color.White,
                weight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HandoffCard(message: V2Message) {
    var expanded by remember(message.text) { mutableStateOf(false) }
    val from = message.handoffFrom.ifBlank { "原 Agent" }
    val to = message.handoffTo.ifBlank { "新 Agent" }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.amber.copy(alpha = .07f))
            .border(1.dp, V2Colors.amber.copy(alpha = .22f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Refresh,
                null,
                tint = V2Colors.amber700,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label("Agent 交接", 10f, V2Colors.amber700, weight = FontWeight.Medium, mono = true)
            Spacer(Modifier.weight(1f))
            Label(if (expanded) "收起" else "展开", 9f, V2Colors.amber700)
        }
        Spacer(Modifier.height(5.dp))
        Label("$from → $to", 11.5f, V2Colors.ink(.82f), weight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Label(
            message.text,
            10.5f,
            V2Colors.ink(.58f),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
        )
        Spacer(Modifier.height(4.dp))
        Label(
            "下一轮由 $to 按这张卡接手 · ${message.time}",
            8.5f,
            V2Colors.ink(.26f),
        )
    }
}

@Composable
private fun SummaryCard(text: String, time: String, note: String = "来自上一段对话") {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.cyan500.copy(alpha = .06f))
            .border(1.dp, V2Colors.cyan500.copy(alpha = .18f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.History,
                null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label("前情摘要", 10f, V2Colors.cyan800, weight = FontWeight.Medium, mono = true)
            Spacer(Modifier.weight(1f))
            Label(if (expanded) "收起" else "展开", 9f, V2Colors.cyan700)
        }
        Spacer(Modifier.height(5.dp))
        Label(
            text,
            10.5f,
            V2Colors.ink(.58f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
        )
        Spacer(Modifier.height(4.dp))
        Label("$note · $time", 8.5f, V2Colors.ink(.26f))
    }
}

@Composable
private fun StreamingRow(text: String, label: String?, onOpenUrl: (String) -> Unit = {}) {
    // 流式气泡沿用在场 Agent 气泡的样式，只比最终消息多一个光标，避免跳动
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                .background(V2Colors.surface(.75f))
                .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (text.isBlank()) {
                Label((label ?: "正在处理…") + "▍", 12f, V2Colors.ink(.42f))
            } else {
                // 流式正文与最终答复共用同一套卡片渲染：代码/表格在生成过程中就能成卡
                RichMessageBody(text, streaming = true, onOpenUrl = onOpenUrl)
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: V2Message,
    run: TaskRunSummary? = null,
    onOpenRunTrace: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onOpenDesign: (String) -> Unit = {},
    /** 长按气泡：回传消息与气泡在根容器中的位置，用于浮出复制/引用/重发菜单。 */
    onLongPress: (V2Message, Rect) -> Unit = { _, _ -> },
) {
    var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
    if (message.kind == "design_card" && message.designCard != null) {
        // Agent 通过 design.share 写入的设计稿卡片：点开进原生画布，不套普通文字气泡
        DesignCardMessage(message.designCard, message.time) { onOpenDesign(message.designCard.id) }
        return
    }
    if (message.kind == "plan_card" && message.planCard != null) {
        // Plan 模式产出的实施计划：独立白卡，点标题展开步骤
        PlanCardMessage(message.planCard, message.time)
        return
    }
    if (message.kind == "decision_result" && message.approvalCard != null) {
        // 审批已有结论：只留一行结果，不再给按钮
        ApprovalResultRow(message.approvalCard, message.time)
        return
    }
    if (message.kind == "decision" && message.approvalCard != null) {
        // 待审批卡固定在输入框上方（ApprovalDock）；列表里只保留已有结论的历史行
        ApprovalResultRow(message.approvalCard, message.time)
        return
    }
    if (message.kind == "summary") {
        // 上一段对话的前情摘要：默认收成 3 行，点开看全
        SummaryCard(message.text, message.time)
        return
    }
    if (message.kind == "handoff") {
        // 平台生成的 Agent 交接卡：切换生效时出现，新 Agent 就是照这张卡接手的
        HandoffCard(message)
        return
    }
    if (message.kind == "attachments") {
        // 历史附件：渲染成一排文件 chip，不套普通文字气泡
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                message.attachments.forEach { file ->
                    AttachmentChip(name = file.name, size = file.size)
                }
                Label(message.time, 8.5f, V2Colors.ink(.22f), modifier = Modifier.padding(start = 4.dp))
            }
        }
        return
    }
    when (message.role) {
        V2MessageRole.SYSTEM -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Label(
                    message.text,
                    9f,
                    V2Colors.ink(.24f),
                    mono = true,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        V2MessageRole.AGENT -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                        .background(V2Colors.surface(.75f))
                        .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                        .onGloballyPositioned { bubbleBounds = it.boundsInRoot() }
                        .v2CombinedPressable(pressScale = .98f, onLongClick = { onLongPress(message, bubbleBounds) }) {}
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (run != null) RunDurationRow(run) { onOpenRunTrace(run.runId) }
                    RichMessageBody(message.text, onOpenUrl = onOpenUrl)
                    Spacer(Modifier.height(4.dp))
                    Label(
                        message.time,
                        8.5f,
                        V2Colors.ink(.22f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        V2MessageRole.USER -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp))
                        .background(V2Colors.accent)
                        .onGloballyPositioned { bubbleBounds = it.boundsInRoot() }
                        .v2CombinedPressable(pressScale = .98f, onLongClick = { onLongPress(message, bubbleBounds) }) {}
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Label(message.text, 12f, Color.White)
                    Spacer(Modifier.height(4.dp))
                    Label(
                        message.time,
                        8.5f,
                        Color.White.copy(alpha = .55f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/**
 * 设计稿卡片：Agent 通过 design.share 写入对话。
 *
 * 卡片本身是完整宽度的「白卡 + 小画布预览」，点开进入原生画布（不是 HTML 预览）；
 * 小画布只渲染消息载荷带回的第一屏节点子集，和设计列表缩略图同一条绘制路径。
 */
@Composable
private fun DesignCardMessage(card: V2DesignCard, time: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(V2Colors.surface(.80f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(V2Colors.accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ShowChart,
                    contentDescription = null,
                    tint = V2Colors.accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Label(card.name, 13f, V2Colors.ink(.94f), FontWeight.SemiBold, maxLines = 1)
                val subtitle = listOf(card.project, card.description)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Label(subtitle, 9f, V2Colors.ink(.42f), maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(V2Colors.accent)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("点开预览", 9f, Color.White, FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(9.dp))
        DesignMiniPreview(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            preview = card.preview,
            bg = card.bg,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                "${card.screenCount} 画板 · ${card.layerCount} 图层",
                9f,
                V2Colors.ink(.52f),
                mono = true,
            )
            Spacer(Modifier.weight(1f))
            Label("v${card.version} · $time", 8.5f, V2Colors.ink(.26f), mono = true)
        }
    }
}

/**
 * 一轮 Run 的「任务耗时」入口：答案气泡上方一行，点开看执行详情。
 * 失败/取消的轮次用对应颜色标注，避免把失败误读成正常耗时。
 */
@Composable
private fun RunDurationRow(run: TaskRunSummary, onClick: () -> Unit) {
    val tone = when (run.status) {
        "failed" -> V2Colors.rose
        "cancelled" -> V2Colors.amber700
        else -> V2Colors.ink(.40f)
    }
    val suffix = when (run.status) {
        "failed" -> " · 失败"
        "cancelled" -> " · 已取消"
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Timer,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(5.dp))
        Label("任务耗时 ${formatRunDuration(run.durationMs)}$suffix", 9f, tone, mono = true)
        Spacer(Modifier.weight(1f))
        Label("详情", 8.5f, V2Colors.cyan700)
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = V2Colors.cyan700,
            modifier = Modifier.size(12.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** 耗时展示：1 分 10 秒 → 「1分10秒」；不足 1 秒单独说明，避免显示成 0 秒。 */
internal fun formatRunDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0) return "—"
    val totalSeconds = durationMs / 1000
    if (totalSeconds < 1) return "不到 1 秒"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
}

// ------------------------------------------------------------------ 附件

/** 共用的「+」按钮：打开系统文件选择器（任意类型，可多选）。 */
@Composable
internal fun AttachmentPickerButton(
    enabled: Boolean,
    onPicked: (List<Uri>) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.surface(.55f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { launcher.launch(arrayOf("*/*")) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = "添加附件",
            tint = if (enabled) V2Colors.cyan700 else V2Colors.ink(.25f),
            modifier = Modifier.size(17.dp),
        )
    }
}

/** 附件 chip：文件名 + 大小；[onRemove] 非空时右侧带 ×。 */
@Composable
internal fun AttachmentChip(
    name: String,
    size: Long,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.cyan500.copy(alpha = .08f))
            .border(1.dp, V2Colors.accent.copy(alpha = .18f), RoundedCornerShape(10.dp))
            .padding(start = 8.dp, end = if (onRemove != null) 4.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Description,
            null,
            tint = V2Colors.cyan700.copy(alpha = .75f),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Label(name, 9.5f, V2Colors.ink(.72f), maxLines = 1, modifier = Modifier.widthIn(max = 140.dp))
            Label(formatFileSize(size), 8f, V2Colors.ink(.34f), mono = true)
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除附件",
                tint = V2Colors.ink(.38f),
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
            )
        }
    }
}

@Composable
private fun UploadingAttachmentChip() {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.amber500.copy(alpha = .08f))
            .border(1.dp, V2Colors.amber500.copy(alpha = .20f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp,
            color = V2Colors.amber700,
        )
        Spacer(Modifier.width(5.dp))
        Label("上传中…", 9.5f, V2Colors.amber700, mono = true)
    }
}

/** 非逐字流式 Agent 的标注：能力可用，只是正文不会逐 token 到达。 */
@Composable
internal fun NonStreamingBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(V2Colors.amber500.copy(alpha = .14f))
            .border(1.dp, V2Colors.amber500.copy(alpha = .32f), RoundedCornerShape(999.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Label("非逐字流式", 8f, V2Colors.amber700, maxLines = 1)
    }
}

/** 文件大小：给用户看的一行短标签。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
