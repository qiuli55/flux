package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import top.qiuli55.flux.mobile.data.ChatGroup

/**
 * 「对话」一级页面：大标题 + 设备筛选 + 助理入口 + 最近对话。
 *
 * 设计稿只有对话本身：管理类入口全部下沉到「我的」二级页面。
 */
@Composable
fun V2HomeScreen(
    device: String,
    onDeviceChange: (String) -> Unit,
    conversations: List<V2Conversation>,
    groups: List<ChatGroup>,
    groupsLoading: Boolean = false,
    extraTop: androidx.compose.ui.unit.Dp,
    onOpenDesigns: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenConversation: (V2Conversation) -> Unit,
    onDeleteConversation: (V2Conversation) -> Unit,
    onOpenGroup: (ChatGroup) -> Unit,
    onCreateGroup: () -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deviceMenu by remember { mutableStateOf(false) }
    var openRowId by remember { mutableStateOf<String?>(null) }
    var openRowDir by remember { mutableIntStateOf(-1) }
    var quickOpen by remember { mutableStateOf(false) }
    var quickHover by remember { mutableIntStateOf(-1) }
    val haptics = LocalV2Haptics.current
    val listState = rememberLazyListState()
    val compact = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 24
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
    val filteredGroups = remember(groups, normalized) {
        groups.filter { group ->
            normalized.isEmpty() ||
                group.name.lowercase().contains(normalized) ||
                group.members.any { it.name.lowercase().contains(normalized) }
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

            V2PullRefresh(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRefresh = {
                    onRefresh()
                    delay(650)
                },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                ) {
                    item { AssistantCard(onClick = onOpenAssistant) }
                    item { V2SectionLabel("最近") }
                    item {
                        WhiteCard(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                val empty = filteredConversations.isEmpty() &&
                                    filteredGroups.isEmpty() && !groupsLoading
                                if (empty) {
                                    EmptyConversations(
                                        searching = normalized.isNotEmpty(),
                                        onNewChat = onNewChat,
                                    )
                                }
                                filteredConversations.forEachIndexed { index, conversation ->
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
                                        ConversationRow(
                                            conversation = conversation,
                                            onClick = {
                                                if (openRowId == conversation.id) {
                                                    openRowId = null
                                                } else {
                                                    onOpenConversation(conversation)
                                                }
                                            },
                                            onDelete = {
                                                openRowId = null
                                                onDeleteConversation(conversation)
                                            },
                                        )
                                    }
                                    if (index != filteredConversations.lastIndex || filteredGroups.isNotEmpty()) {
                                        RowDivider()
                                    }
                                }
                                filteredGroups.forEachIndexed { index, group ->
                                    GroupRow(group = group, onClick = { onOpenGroup(group) })
                                    if (index != filteredGroups.lastIndex) RowDivider()
                                }
                                if (filteredConversations.isNotEmpty() || filteredGroups.isNotEmpty()) {
                                    RowDivider()
                                }
                                NewGroupRow(onClick = onCreateGroup)
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
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
                                val slot = ((travel - startOffset) / slotSize).toInt().coerceIn(0, 1)
                                1 - slot
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
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "新建对话，按住可拖动选择快捷动作",
                tint = V2Colors.actionInk,
                modifier = Modifier.size(26.dp),
            )
        }

        QuickActionStack(
            visible = quickOpen,
            hover = quickHover,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 90.dp),
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
) {
    val chevron by animateFloatAsState(
        targetValue = if (deviceMenuOpen) 180f else 0f,
        animationSpec = tween(180),
        label = "device-chevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 6.dp + extraTop, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = "对话",
                color = V2Colors.ink(.96f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 36.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleDevice)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(device, 13f, V2Colors.ink(.42f), maxLines = 1)
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "切换设备",
                    tint = V2Colors.ink(.35f),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(15.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(V2Colors.surface)
                .clickable(onClick = onToggleSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索对话",
                tint = V2Colors.ink(.65f),
                modifier = Modifier.size(19.dp),
            )
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

/** 助理入口：黑圆角方块 + 星芒图标。 */
@Composable
private fun AssistantCard(onClick: () -> Unit) {
    WhiteCard(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(V2Colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = V2Colors.actionInk,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label("助理", 17f, V2Colors.ink(.94f), FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Label("读取全部对话，汇总项目进度", 13f, V2Colors.ink(.42f), maxLines = 1)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = V2Colors.ink(.22f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(V2Colors.lineSoft),
    )
}

@Composable
private fun ConversationRow(
    conversation: V2Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .v2CombinedPressable(
                    pressScale = .99f,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Label(
                    conversation.title,
                    17f,
                    V2Colors.ink(.92f),
                    FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Label(conversation.updated, 13f, V2Colors.ink(.32f), maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            ConversationStatusLine(conversation)
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
private fun ConversationStatusLine(conversation: V2Conversation) {
    val status = conversation.status
    val note = conversation.statusNote
    when (status) {
        V2TaskStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(V2Colors.emerald)
            Spacer(Modifier.width(6.dp))
            Label(note ?: "Agent 正在执行…", 13f, V2Colors.ink(.45f), maxLines = 1)
        }

        V2TaskStatus.WAITING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                note ?: "有改动待审核",
                13f,
                V2Colors.ink(.45f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            V2Chip(
                "待审核",
                V2Colors.amber700,
                background = V2Colors.amber.copy(alpha = .16f),
                border = Color.Transparent,
            )
        }

        null -> Label(
            "${conversation.device} · ${demoAgent(conversation.agentId).name}",
            13f,
            V2Colors.ink(.38f),
            maxLines = 1,
        )

        else -> Label(
            note ?: status.label,
            13f,
            if (status == V2TaskStatus.FAILED) V2Colors.rose else V2Colors.ink(.45f),
            maxLines = 1,
        )
    }
}

@Composable
private fun GroupRow(group: ChatGroup, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .99f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Label(
                group.name,
                17f,
                V2Colors.ink(.92f),
                FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            Label(
                relativeTime(group.lastMessageAt ?: group.createdAt),
                13f,
                V2Colors.ink(.32f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Label("${group.memberCount} 个 Bot · 群聊", 13f, V2Colors.ink(.42f), maxLines = 1)
    }
}

@Composable
private fun NewGroupRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .99f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = V2Colors.ink(.42f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Label("新建群聊", 15f, V2Colors.ink(.62f), FontWeight.Medium)
    }
}

@Composable
private fun EmptyConversations(searching: Boolean, onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(
            if (searching) "没有匹配的对话" else "还没有对话",
            14f,
            V2Colors.ink(.48f),
        )
        if (!searching) {
            Spacer(Modifier.height(6.dp))
            Label("点右下角 + 开一段新对话", 12f, V2Colors.ink(.34f))
            Spacer(Modifier.height(14.dp))
            GhostButton(text = "新建对话", onClick = onNewChat)
        }
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
                .padding(start = 20.dp, top = 92.dp)
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
