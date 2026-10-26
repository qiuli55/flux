package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 首页：设备筛选 + 决策/队列入口 + 单一/多 Agent 两个分区。 */
@Composable
fun V2HomeScreen(
    device: String,
    onDeviceChange: (String) -> Unit,
    conversations: List<V2Conversation>,
    groups: List<V2Group>,
    pendingCount: Int,
    runningCount: Int,
    billingHeadline: String,
    extraTop: androidx.compose.ui.unit.Dp,
    onOpenDecisions: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenBilling: () -> Unit,
    onOpenDesigns: () -> Unit,
    onOpenConversation: (V2Conversation) -> Unit,
    onDeleteConversation: (V2Conversation) -> Unit,
    onOpenGroup: (V2Group) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    var multiMode by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deviceMenu by remember { mutableStateOf(false) }
    var openRowId by remember { mutableStateOf<String?>(null) }
    var openRowDir by remember { mutableIntStateOf(-1) }
    var quickOpen by remember { mutableStateOf(false) }
    var quickHover by remember { mutableIntStateOf(-1) }
    val haptics = LocalV2Haptics.current
    val singleList = rememberLazyListState()
    val multiList = rememberLazyListState()
    val compact = singleList.firstVisibleItemIndex > 0 ||
        singleList.firstVisibleItemScrollOffset > 24 ||
        multiList.firstVisibleItemIndex > 0 ||
        multiList.firstVisibleItemScrollOffset > 24
    val fabScale by animateFloatAsState(
        targetValue = if (compact) .786f else 1f,
        animationSpec = tween(180),
        label = "fab-scale",
    )

    val normalized = query.trim().lowercase()
    val filteredConversations = remember(conversations, device, normalized) {
        conversations.filter { conversation ->
            (device == ALL_DEVICES || conversation.device == device) &&
                (normalized.isEmpty() ||
                    conversation.title.lowercase().contains(normalized) ||
                    conversation.device.lowercase().contains(normalized) ||
                    demoAgent(conversation.agentId).name.lowercase().contains(normalized))
        }
    }
    val filteredGroups = remember(groups, device, normalized) {
        groups.filter { group ->
            (device == ALL_DEVICES || group.device == device) &&
                (normalized.isEmpty() ||
                    group.title.lowercase().contains(normalized) ||
                    group.device.lowercase().contains(normalized) ||
                    group.agents.any { demoAgent(it).name.lowercase().contains(normalized) })
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HomeHeader(
                device = device,
                searchOpen = searchOpen,
                deviceMenuOpen = deviceMenu,
                extraTop = extraTop,
                onToggleDevice = { deviceMenu = !deviceMenu },
                onToggleSearch = {
                    if (searchOpen) query = ""
                    searchOpen = !searchOpen
                },
                onOpenSettings = onOpenSettings,
            )

            AnimatedVisibility(
                visible = searchOpen,
                enter = fadeIn(tween(160)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = .94f,
                    transformOrigin = TransformOrigin(1f, .5f),
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    animationSpec = tween(120),
                    targetScale = .96f,
                    transformOrigin = TransformOrigin(1f, .5f),
                ),
            ) {
                SearchField(query = query, onQueryChange = { query = it })
            }

            Column(Modifier.padding(top = if (searchOpen) 8.dp else 16.dp)) {
                EntryRow(
                    icon = Icons.Outlined.Schedule,
                    title = "决策中心",
                    count = pendingCount,
                    unit = "项待确认",
                    metaColor = V2Colors.amber,
                    onClick = onOpenDecisions,
                )
                EntryRow(
                    icon = Icons.Outlined.List,
                    title = "任务队列",
                    count = runningCount,
                    unit = "个执行中",
                    metaColor = V2Colors.cyan700,
                    onClick = onOpenQueue,
                )
                EntryRow(
                    icon = Icons.Outlined.DataUsage,
                    title = "套餐额度",
                    meta = billingHeadline,
                    metaColor = V2Colors.emerald,
                    onClick = onOpenBilling,
                )
                EntryRow(
                    icon = Icons.Outlined.DesignServices,
                    title = "设计工作室",
                    meta = "画布 · 轻编辑 · AI",
                    metaColor = V2Colors.indigo,
                    onClick = onOpenDesigns,
                )
            }

            ThinDivider(modifier = Modifier.padding(top = 12.dp))

            SectionTabs(
                multiMode = multiMode,
                onSelect = {
                    multiMode = it
                    deviceMenu = false
                    openRowId = null
                },
            )

            V2PullRefresh(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRefresh = {
                    onRefresh()
                    delay(650)
                },
            ) {
                if (multiMode) {
                    LazyColumn(Modifier.fillMaxSize(), state = multiList) {
                        itemsIndexed(filteredGroups, key = { _, item -> item.id }) { index, group ->
                            V2Stagger(index) {
                                GroupRow(group = group, onClick = { onOpenGroup(group) })
                            }
                            if (index != filteredGroups.lastIndex) {
                                ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = singleList) {
                        itemsIndexed(filteredConversations, key = { _, item -> item.id }) { index, conversation ->
                            val isOpen = openRowId == conversation.id
                            V2SwipeRow(
                                open = isOpen,
                                openDir = openRowDir,
                                onOpenChange = { open, dir ->
                                    if (open) {
                                        openRowId = conversation.id
                                        openRowDir = dir
                                    } else if (openRowId == conversation.id) {
                                        openRowId = null
                                    }
                                },
                                rightAction = {
                                    SwipeDeleteAction {
                                        openRowId = null
                                        onDeleteConversation(conversation)
                                    }
                                },
                            ) {
                                V2Stagger(index) {
                                    ConversationRow(
                                        conversation = conversation,
                                        online = conversation.unread > 0,
                                        onClick = {
                                            if (openRowId == conversation.id) {
                                                openRowId = null
                                            } else {
                                                onOpenConversation(conversation)
                                            }
                                        },
                                        // 长按不再直接删：先弹一个小菜单，避免误删整段对话
                                        onDelete = {
                                            openRowId = null
                                            onDeleteConversation(conversation)
                                        },
                                    )
                                }
                            }
                            if (index != filteredConversations.lastIndex) {
                                ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 22.dp)
                .graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                    alpha = if (compact) .75f else 1f
                }
                .size(56.dp)
                .shadow(14.dp, CircleShape)
                .clip(CircleShape)
                .background(V2Colors.accent)
                .pointerInput(compact) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 260ms 内松手 = 轻点新建；按住则弹出快捷动作，可拖选
                        val up = withTimeoutOrNull(260L) { waitForUpOrCancellation() }
                        if (up != null) {
                            if (quickOpen) {
                                quickOpen = false
                            } else {
                                onNewChat()
                            }
                            quickHover = -1
                            haptics?.play(V2HapticLevel.TICK)
                            return@awaitEachGesture
                        }
                        quickOpen = true
                        quickHover = -1
                        haptics?.play(V2HapticLevel.TICK)
                        // 快捷动作栈自下而上排列：最近的是「任务队列」，最远的是「新建对话」
                        val slotSize = 52.dp.toPx()
                        val startOffset = 16.dp.toPx()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val travel = (down.position.y - change.position.y).coerceAtLeast(0f)
                            quickHover = if (travel < startOffset) {
                                -1
                            } else {
                                val slot = ((travel - startOffset) / slotSize).toInt().coerceIn(0, 2)
                                2 - slot
                            }
                            change.consume()
                        }
                        val chosen = quickHover
                        quickOpen = false
                        quickHover = -1
                        if (chosen >= 0) {
                            haptics?.play(V2HapticLevel.SUCCESS)
                            when (chosen) {
                                0 -> onNewChat()
                                1 -> onOpenDesigns()
                                2 -> onOpenQueue()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AddComment,
                contentDescription = "新建对话，按住可拖动选择快捷动作",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        QuickActionStack(
            visible = quickOpen,
            hover = quickHover,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 88.dp),
        )

        if (deviceMenu) {
            DeviceMenu(
                device = device,
                onPick = {
                    onDeviceChange(it)
                    deviceMenu = false
                },
                onDismiss = { deviceMenu = false },
            )
        }
    }
}

@Composable
private fun HomeHeader(
    device: String,
    searchOpen: Boolean,
    deviceMenuOpen: Boolean,
    extraTop: androidx.compose.ui.unit.Dp,
    onToggleDevice: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (deviceMenuOpen) 180f else 0f,
        animationSpec = tween(180),
        label = "device-chevron",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp + extraTop, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .v2Pressable(pressScale = .99f, onClick = onToggleDevice),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                text = device,
                size = 26f,
                color = V2Colors.ink(.95f),
                weight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                null,
                tint = V2Colors.ink(.35f),
                modifier = Modifier
                    .padding(start = 6.dp, top = 3.dp)
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }
        Row(
            Modifier
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(V2Colors.surface)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .v2Pressable(onClick = onToggleSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searchOpen) "关闭搜索" else "搜索对话",
                    tint = V2Colors.ink(.6f),
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(V2Colors.avatarBlue)
                    .v2Pressable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = "打开账户与设置",
                    tint = V2Colors.avatarInk,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            null,
            tint = V2Colors.ink(.30f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Label("搜索对话或 Agent", 12.5f, V2Colors.ink(.28f))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = TextStyle(fontSize = 12.5.sp, color = V2Colors.ink(.85f)),
                cursorBrush = SolidColor(V2Colors.accent),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    meta: String = "",
    count: Int? = null,
    unit: String = "",
    metaColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(.70f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Label(title, 15f, V2Colors.ink(.90f), modifier = Modifier.weight(1f), maxLines = 1)
        if (count != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                V2RollingNumber(count, 10.5f, metaColor)
                Spacer(Modifier.width(3.dp))
                Label(unit, 10.5f, metaColor, maxLines = 1)
            }
        } else {
            Label(meta, 10.5f, metaColor, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTabs(multiMode: Boolean, onSelect: (Boolean) -> Unit) {
    val density = LocalDensity.current
    var firstX by remember { mutableFloatStateOf(0f) }
    var firstW by remember { mutableFloatStateOf(0f) }
    var secondX by remember { mutableFloatStateOf(0f) }
    var secondW by remember { mutableFloatStateOf(0f) }
    val targetX = if (multiMode) secondX else firstX
    val targetW = if (multiMode) secondW else firstW
    val underlineX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = spring(stiffness = 520f, dampingRatio = .42f),
        label = "tab-x",
    )
    val underlineW by animateFloatAsState(
        targetValue = targetW,
        animationSpec = spring(stiffness = 520f, dampingRatio = .42f),
        label = "tab-w",
    )

    Box {
        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SectionTab(
                text = "单一 Agent 对话",
                selected = !multiMode,
                modifier = Modifier.onGloballyPositioned {
                    firstX = it.positionInParent().x
                    firstW = it.size.width.toFloat()
                },
            ) { onSelect(false) }
            SectionTab(
                text = "多 Agent 协作任务",
                selected = multiMode,
                modifier = Modifier.onGloballyPositioned {
                    secondX = it.positionInParent().x
                    secondW = it.size.width.toFloat()
                },
            ) { onSelect(true) }
        }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        underlineX.roundToInt(),
                        (16.dp + 26.dp).roundToPx(),
                    )
                }
                .width(with(density) { underlineW.coerceAtLeast(0f).toDp() })
                .height(2.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(V2Colors.accent),
        )
    }
}

@Composable
private fun SectionTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(28.dp)
            .v2Pressable(pressScale = .96f, onClick = onClick),
        contentAlignment = Alignment.TopStart,
    ) {
        Label(
            text,
            13.5f,
            if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f),
            if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun SwipeDeleteAction(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.rose500)
            .v2Pressable(pressScale = .95f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(3.dp))
            Label("删除", 9.5f, Color.White, FontWeight.Medium)
        }
    }
}

@Composable
private fun QuickActionStack(visible: Boolean, hover: Int, modifier: Modifier = Modifier) {
    val actions = listOf(
        Triple(Icons.Outlined.AddComment, "新建对话", "直接开一段新对话"),
        Triple(Icons.Outlined.DesignServices, "设计工作室", "画布与 AI 改稿"),
        Triple(Icons.Outlined.List, "任务队列", "查看执行中任务"),
    )
    Column(
        modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEachIndexed { index, action ->
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(160, delayMillis = index * 30)) +
                    slideInHorizontally(tween(160, delayMillis = index * 30)) { it / 2 } +
                    scaleIn(
                        animationSpec = tween(160, delayMillis = index * 30),
                        initialScale = .92f,
                        transformOrigin = TransformOrigin(1f, 1f),
                    ),
                exit = fadeOut(tween(100)),
            ) {
                Row(
                    Modifier
                        .width(196.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (hover == index) V2Colors.cyan500.copy(alpha = .14f)
                            else V2Colors.surface(.94f),
                        )
                        .border(
                            1.dp,
                            if (hover == index) V2Colors.cyan500.copy(alpha = .5f)
                            else V2Colors.ink(.08f),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        action.first,
                        contentDescription = null,
                        tint = if (hover == index) V2Colors.cyan700 else V2Colors.ink(.55f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Label(
                            action.second,
                            12f,
                            if (hover == index) V2Colors.cyan800 else V2Colors.ink(.85f),
                            FontWeight.Medium,
                            maxLines = 1,
                        )
                        Label(action.third, 9f, V2Colors.ink(.36f), maxLines = 1)
                    }
                    if (hover == index) {
                        Label("松手", 8.5f, V2Colors.cyan700, FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: V2Conversation,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val agent = demoAgent(conversation.agentId)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .v2CombinedPressable(
                    pressScale = .985f,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(tint = agent.tint, online = online)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(
                    conversation.title,
                    14f,
                    V2Colors.ink(.90f),
                    FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceIcon(deviceKind(conversation.device), V2Colors.ink(.30f), 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Label(
                        "${conversation.device} · ${agent.name}",
                        11f,
                        V2Colors.ink(.40f),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Label(conversation.updated, 11f, V2Colors.ink(.30f))
                if (conversation.unread > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .height(16.dp)
                            .width(16.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(V2Colors.cyan500),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label("${conversation.unread}", 9.5f, Color.White, FontWeight.Medium)
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Label("删除对话", 12f, V2Colors.rose) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = V2Colors.rose,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun GroupRow(group: V2Group, onClick: () -> Unit) {
    val stageName = group.stages.getOrNull(group.stage)?.name ?: "已完成"
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(tint = V2Colors.indigo, icon = Icons.Outlined.AccountTree, online = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(group.title, 14f, V2Colors.ink(.90f), FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Label(
                "${group.agents.size} 个 Agent · $stageName",
                11f,
                V2Colors.ink(.40f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Label(group.updated, 11f, V2Colors.ink(.30f))
            Spacer(Modifier.height(4.dp))
            Label(groupStatusLabel(group.status), 10f, groupStatusColor(group.status))
        }
    }
}

@Composable
private fun DeviceMenu(device: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(160),
        label = "device-menu",
    )
    LaunchedEffect(Unit) { entered = true }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .padding(start = 20.dp, top = 58.dp)
                .width(232.dp)
                .graphicsLayer {
                    alpha = appear
                    scaleX = .96f + .04f * appear
                    scaleY = .96f + .04f * appear
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(V2Colors.surface)
                .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            DeviceMenuRow(
                label = ALL_DEVICES,
                selected = device == ALL_DEVICES,
                icon = Icons.Outlined.Devices,
                onClick = { onPick(ALL_DEVICES) },
            )
            V2Registry.devices.forEach { item ->
                DeviceMenuRow(
                    label = item.name,
                    selected = device == item.name,
                    icon = when (item.kind) {
                        V2DeviceKind.LAPTOP -> Icons.Outlined.Laptop
                        V2DeviceKind.DESKTOP -> Icons.Outlined.Computer
                        V2DeviceKind.PHONE -> Icons.Outlined.Smartphone
                    },
                    onClick = { onPick(item.name) },
                )
            }
        }
    }
}

@Composable
private fun DeviceMenuRow(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .10f) else Color.Transparent)
            .v2Pressable(pressScale = .98f, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) V2Colors.cyan700 else V2Colors.ink(.45f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Label(
            label,
            13f,
            if (selected) V2Colors.cyan800 else V2Colors.ink(.75f),
            if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
