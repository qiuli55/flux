package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.List as ListIcon
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 输入栏的两个小窗：Agent 胶囊 / 模型立方体。
 *
 * 两者共用同一套 Flux 卡片语言（圆角、描边、阴影、行尾勾选），只锚定在各自按钮上，
 * 不再使用 Material3 默认 DropdownMenu 的等宽菜单壳，也不做成半屏底栏。
 */

// ------------------------------------------------------------------ 副行文案（纯函数）

/**
 * Agent 小窗副行：该 Agent 实际会用的可读模型名；没有可用模型时返回 null（副行不渲染）。
 *
 * 真实切换口径（backend/task_engine/repository.py）：
 *  - `set_agent` / `apply_pending_agent` 都会清掉任务级 `model` / `api_key_id`，
 *    所以切到另一个 Agent 后用的是该 Agent 的第一个可用模型；
 *  - 排队中的目标（pendingAgentId）在本轮结束后走同一条路径，副行要显示即将生效的模型；
 *  - 当前 Agent 则跟着正在用的模型走（按 model + keyId 匹配可读 label）。
 */
internal fun agentMenuModelLabel(
    option: V2AgentOption,
    currentAgentId: String,
    currentModel: String,
    currentModelKeyId: String?,
    pendingAgentId: String?,
): String? {
    val models = option.models
    // 没有可用模型：不给空行，也不拿 role / 内部 id 凑数。
    if (models.isEmpty()) return null
    if (option.agent.id == pendingAgentId) {
        return models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
    }
    if (option.agent.id == currentAgentId) {
        val inUse = models.firstOrNull { it.model == currentModel && it.keyId == currentModelKeyId }
        return (inUse?.label ?: currentModel).takeIf { it.isNotBlank() }
            ?: models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
    }
    return models.firstOrNull()?.label?.takeIf { it.isNotBlank() }
}

/**
 * 模型小窗副行：只写真实状态。
 *
 * 模型切换是立即写入任务、下一条 Run 生效，没有「本轮结束后切换」的排队，
 * 因此这里只说「当前在用」和「密钥库」，不编造生效时机。
 */
internal fun modelMenuSubtitle(
    option: V2ModelOption,
    currentModel: String,
    currentModelKeyId: String?,
): String? {
    val inUse = option.model == currentModel && option.keyId == currentModelKeyId
    return when {
        inUse && option.keyId != null -> "当前在用 · 密钥库"
        inUse -> "当前在用"
        option.keyId != null -> "密钥库"
        else -> null
    }
}

// ------------------------------------------------------------------ 定位

/**
 * 贴着锚点（Agent 胶囊 / 模型立方体）定位的小窗：
 * 优先在锚点上方展开；上方空间不足时落到下方，最后兜底夹在窗口内。
 *
 * [insetPx] 是 Popup 内容给投影预留的内边距：窗口只有和内容一样大时，卡片外侧的
 * 阴影才不会被窗口裁掉，因此定位按「卡片本身」计算，再整体减去这圈留白。
 */
private class AnchoredMenuPositionProvider(
    private val alignToAnchorEnd: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
    private val insetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cardWidth = (popupContentSize.width - insetPx * 2).coerceAtLeast(0)
        val cardHeight = (popupContentSize.height - insetPx * 2).coerceAtLeast(0)

        val maxX = (windowSize.width - cardWidth - marginPx).coerceAtLeast(marginPx)
        val rawX = if (alignToAnchorEnd) {
            anchorBounds.right - cardWidth
        } else {
            anchorBounds.left
        }
        val x = rawX.coerceIn(marginPx, maxX)

        val maxY = (windowSize.height - cardHeight - marginPx).coerceAtLeast(marginPx)
        val above = anchorBounds.top - cardHeight - gapPx
        val below = anchorBounds.bottom + gapPx
        val y = when {
            above >= marginPx -> above
            below <= maxY -> below
            else -> maxY
        }
        return IntOffset(x - insetPx, y - insetPx)
    }
}

// ------------------------------------------------------------------ 共用卡片 / 行

/** 两个小窗共用的卡片壳：深色浅色同一套圆角、描边与阴影。 */
@Composable
internal fun FluxPickerMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    testTag: String,
    alignToAnchorEnd: Boolean = false,
    maxHeight: Dp = 360.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { 10.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val shadowInset = 14.dp
    val insetPx = with(density) { shadowInset.roundToPx() }
    val positionProvider = remember(alignToAnchorEnd, gapPx, marginPx, insetPx) {
        AnchoredMenuPositionProvider(alignToAnchorEnd, gapPx, marginPx, insetPx)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box {
            // 投影留白里点一下也关窗：这一圈属于 Popup 窗口，不接管就会变成死区。
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { onDismissRequest() }
                    },
            )
            Surface(
                modifier = Modifier
                    .padding(shadowInset)
                    .width(width)
                    .testTag(testTag),
                shape = RoundedCornerShape(20.dp),
                color = V2Colors.surface,
                contentColor = V2Colors.ink,
                tonalElevation = 0.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(1.dp, V2Colors.line),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 小窗里的一行：整行可点，选中行用底色 + 描边强调，勾选固定在行尾。
 * 最小高度 46dp，保证触摸目标不小于 44dp。
 */
@Composable
internal fun FluxPickerRow(
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .testTag(testTag)
            .clip(shape)
            .background(if (selected) V2Colors.ink(.06f) else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, V2Colors.ink(.12f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), content = content)
        Spacer(Modifier.width(6.dp))
        // 勾选固定占位：有选中时不会把名字挤走，也不会挤在名字后面。
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "使用中",
                    tint = V2Colors.ink(.85f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Agent 小窗

/** Agent 胶囊点开的小窗：官方运行时图标 + 名称 + 会实际使用的模型副行。 */
@Composable
internal fun FluxAgentMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<V2AgentOption>,
    currentAgentId: String,
    currentModel: String,
    currentModelKeyId: String?,
    pendingAgentId: String?,
    onPick: (String) -> Unit,
) {
    FluxPickerMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        width = 272.dp,
        testTag = V2ComposerTags.AGENT_MENU,
        alignToAnchorEnd = false,
    ) {
        options.forEach { option ->
            val agent = option.agent
            val selected = agent.id == currentAgentId
            val pending = agent.id == pendingAgentId
            val subtitle = agentMenuModelLabel(
                option = option,
                currentAgentId = currentAgentId,
                currentModel = currentModel,
                currentModelKeyId = currentModelKeyId,
                pendingAgentId = pendingAgentId,
            )
            FluxPickerRow(
                selected = selected,
                testTag = V2ComposerTags.AGENT_MENU_ROW_PREFIX + agent.id,
                onClick = {
                    onDismissRequest()
                    onPick(agent.id)
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentRuntimeIcon(agent.runtime, size = 20.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(
                                agent.name,
                                13f,
                                V2Colors.ink(.92f),
                                FontWeight.Medium,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                            )
                            if (!agent.nativeStreaming) {
                                Spacer(Modifier.width(6.dp))
                                NonStreamingBadge()
                            }
                        }
                        if (subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Label(subtitle, 10.5f, V2Colors.ink(.44f), maxLines = 1)
                        }
                        if (pending) {
                            Spacer(Modifier.height(3.dp))
                            Label("本轮结束后切换", 9.5f, V2Colors.amber700, mono = true)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 模型小窗

/**
 * 模型立方体点开的底部面板：两层。
 *
 * 第一层是「当前模型」小卡片（`SheetFrame(wrapContent = true)`，贴着输入栏上沿）；
 * 点「更多模型」打开第二层「切换模型」大面板，按通道筛选并支持搜索。
 * 只列**当前 Agent** 的模型；通道集合由后端按当前 Agent 的 runtime 组装。
 *
 * 两层都复用 [SheetFrame] 的遮罩、拖动条、下拉 / 点遮罩关闭，不另做一套手势。
 */
@Composable
internal fun FluxModelMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<V2ModelOption>,
    currentModel: String,
    currentModelKeyId: String?,
    channels: List<V2ModelChannel> = emptyList(),
    favorites: Set<String> = emptySet(),
    currentModelAccountId: String? = null,
    currentThinking: String? = null,
    thinkingEnabled: Boolean = true,
    /** 小卡片底部让出的输入栏高度：输入区仍然露在面板下方可见。 */
    bottomInset: Dp = 0.dp,
    onToggleFavorite: (V2ModelOption) -> Unit = {},
    onThinkingChange: (V2ModelOption, String) -> Unit = { _, _ -> },
    onPick: (V2ModelOption) -> Unit,
) {
    var showAll by remember(expanded) { mutableStateOf(false) }
    var channelFilter by remember(expanded) { mutableStateOf(ALL_MODELS) }
    var query by remember(expanded) { mutableStateOf("") }
    val current = options.firstOrNull {
        it.model == currentModel &&
            it.keyId == currentModelKeyId &&
            it.accountId == currentModelAccountId
    } ?: options.firstOrNull { it.model == currentModel && it.keyId == currentModelKeyId }
        ?: options.firstOrNull()

    LaunchedEffect(expanded) {
        if (!expanded) {
            showAll = false
            channelFilter = ALL_MODELS
            query = ""
        }
    }
    if (!expanded) return

    if (!showAll) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            SheetFrame(
                onDismiss = onDismissRequest,
                maxHeightFraction = .5f,
                wrapContent = true,
                expandable = false,
            ) {
                CurrentModelCard(
                    current = current,
                    currentModel = currentModel,
                    currentThinking = currentThinking,
                    thinkingEnabled = thinkingEnabled,
                    onThinkingChange = onThinkingChange,
                    onMore = { showAll = true },
                )
            }
        }
    } else {
        ModelSwitchSheet(
            options = options,
            channels = channels,
            favorites = favorites,
            channelFilter = channelFilter,
            query = query,
            onQuery = { query = it },
            onFilter = { channelFilter = it },
            currentModel = currentModel,
            currentModelKeyId = currentModelKeyId,
            currentModelAccountId = currentModelAccountId,
            onToggleFavorite = onToggleFavorite,
            onPick = {
                onDismissRequest()
                onPick(it)
            },
            onDismiss = onDismissRequest,
        )
    }
}

/** 第一层：当前通道 + 当前模型 + 思考控制 + 更多模型入口。 */
@Composable
private fun CurrentModelCard(
    current: V2ModelOption?,
    currentModel: String,
    currentThinking: String?,
    thinkingEnabled: Boolean,
    onThinkingChange: (V2ModelOption, String) -> Unit,
    onMore: () -> Unit,
) {
    val channelLabel = current?.channelLabel.orEmpty().ifBlank {
        when (current?.channelKind) {
            "key" -> "密钥库"
            "subscription" -> "订阅账号"
            else -> "本机默认"
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(V2ComposerTags.MODEL_MENU)
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Label(
            channelLabel,
            10f,
            V2Colors.ink(.46f),
            FontWeight.SemiBold,
            mono = true,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        val selected = current
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V2Colors.ink(.06f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "当前模型",
                tint = V2Colors.ink(.78f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Label(
                    selected?.label ?: currentModel,
                    13.5f,
                    V2Colors.ink(.92f),
                    FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        // 不支持思考的模型不展示思考控制；不做「不保证支持」之类的脚注。
        if (selected != null && selected.thinking.supported) {
            Spacer(Modifier.height(6.dp))
            ThinkingControl(
                option = selected,
                level = currentThinking,
                enabled = thinkingEnabled,
                onLevel = { onThinkingChange(selected, it) },
            )
        }
        Spacer(Modifier.height(8.dp))
        FluxPickerRow(
            selected = false,
            testTag = V2ComposerTags.MODEL_MORE,
            onClick = onMore,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ListIcon,
                    contentDescription = null,
                    tint = V2Colors.ink(.55f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Label("更多模型", 12.5f, V2Colors.ink(.80f), modifier = Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = V2Colors.ink(.42f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 思考控制：按模型元数据渲染，不写死同一套开关。
 * - none：不展示可点击控件，只说明不支持；
 * - toggle：开 / 关；
 * - levels：官方档位原样展示；
 * - always：只读说明，不提供 Off。
 */
@Composable
private fun ThinkingControl(
    option: V2ModelOption,
    level: String?,
    enabled: Boolean,
    onLevel: (String) -> Unit,
) {
    val thinking = option.thinking
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Label("思考", 10f, V2Colors.ink(.44f), FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        when (thinking.kind) {
            "always" -> Label(
                "始终思考（不可关闭）",
                11f,
                V2Colors.ink(.58f),
                maxLines = 1,
            )
            "toggle", "levels" -> {
                val levels = if (thinking.kind == "toggle") listOf("on", "off") else thinking.levels
                val labels = if (thinking.kind == "toggle") listOf("开", "关") else thinking.labels
                val active = level?.takeIf { it.isNotBlank() } ?: thinking.default
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    levels.forEachIndexed { index, value ->
                        val label = labels.getOrNull(index) ?: value
                        val selected = value == active
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) V2Colors.ink(.82f) else V2Colors.ink(.06f)
                                )
                                .clickable(enabled = enabled) { onLevel(value) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag(V2ComposerTags.MODEL_THINKING_PREFIX + value),
                        ) {
                            Label(
                                label,
                                10.5f,
                                if (selected) V2Colors.surface else V2Colors.ink(.62f),
                                FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            // none：不展示任何控件（调用方已按 supported 过滤），不留按了无效的开关。
            else -> Unit
        }
    }
}

/**
 * 第二层「切换模型」：左对齐大标题 + 搜索 + 通道 chips + 完整模型列表。
 *
 * 从底部升起，maxHeightFraction 不接近全屏，上方仍能看到对话标题与气泡；
 * 下拉或点遮罩关闭都交给 [SheetFrame]。
 */
@Composable
private fun ModelSwitchSheet(
    options: List<V2ModelOption>,
    channels: List<V2ModelChannel>,
    favorites: Set<String>,
    channelFilter: String,
    query: String,
    onQuery: (String) -> Unit,
    onFilter: (String) -> Unit,
    currentModel: String,
    currentModelKeyId: String?,
    currentModelAccountId: String?,
    onToggleFavorite: (V2ModelOption) -> Unit,
    onPick: (V2ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .72f) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Label("切换模型", 21f, V2Colors.ink(.95f), FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            // 搜索框：按模型名 / 通道名过滤；options 已限定在当前 Agent。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .background(V2Colors.ink(.05f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Label("搜索模型或提供商…", 12.5f, V2Colors.ink(.30f))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = V2Colors.ink(.90f),
                        fontSize = 12.5.sp,
                    ),
                    cursorBrush = SolidColor(V2Colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(V2ComposerTags.MODEL_SEARCH),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChannelChip(
                    label = "全部",
                    selected = channelFilter == ALL_MODELS || channelFilter.isBlank(),
                    tag = V2ComposerTags.MODEL_CHANNEL_PREFIX + ALL_MODELS,
                    onClick = { onFilter(ALL_MODELS) },
                )
                channels.filter { it.enabled }.forEach { channel ->
                    ChannelChip(
                        label = channelChipLabel(channel),
                        selected = channelFilter == channel.id,
                        tag = V2ComposerTags.MODEL_CHANNEL_PREFIX + channel.id,
                        onClick = { onFilter(channel.id) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(V2Colors.ink(.07f)))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp, bottom = 18.dp),
            ) {
                val visible = filterModelOptions(
                    modelsForChannel(options, channelFilter),
                    query,
                )
                if (visible.isEmpty()) {
                    Label(
                        if (query.isBlank()) "这个通道还没有可用模型" else "没有匹配的模型",
                        11f,
                        V2Colors.ink(.40f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
                sortedModelOptions(visible, favorites).forEach { option ->
                    val selected = option.model == currentModel &&
                        option.keyId == currentModelKeyId &&
                        option.accountId == currentModelAccountId
                    FluxPickerRow(
                        selected = selected,
                        testTag = V2ComposerTags.MODEL_MENU_ROW_PREFIX +
                            option.model + "|" + (option.keyId ?: option.accountId ?: "native"),
                        onClick = { onPick(option) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label(option.label, 12.5f, V2Colors.ink(.90f), maxLines = 1)
                                modelDetailLine(option, selected)?.let { detail ->
                                    Spacer(Modifier.height(2.dp))
                                    Label(detail, 10f, V2Colors.ink(.42f), maxLines = 1)
                                }
                            }
                            IconButton(
                                onClick = { onToggleFavorite(option) },
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag(V2ComposerTags.MODEL_STAR_PREFIX + option.favoriteKey),
                            ) {
                                val starred = option.favoriteKey in favorites
                                Icon(
                                    if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = if (starred) "取消收藏" else "收藏",
                                    tint = if (starred) V2Colors.amber700 else V2Colors.ink(.30f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.ink(.84f) else V2Colors.ink(.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(tag),
    ) {
        Label(
            label,
            10.5f,
            if (selected) V2Colors.surface else V2Colors.ink(.62f),
            FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 列表副行：来源 · 上下文 · 能力标签；没有的项不写。 */
internal fun modelDetailLine(option: V2ModelOption, selected: Boolean): String? {
    val parts = buildList {
        option.channelLabel.takeIf { it.isNotBlank() }?.let { add(it) }
        contextWindowLabel(option.contextWindow)?.let { add(it) }
        option.capabilities.forEach { add(it) }
        if (selected) add("当前在用")
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

/** 订阅账号的展示名：邮箱已由后端给出；没有邮箱时用账号 id 的短标识。 */
private fun accountLabel(accountId: String): String =
    if (accountId == "default") "默认账号" else "账号 ${accountId.take(8)}"
