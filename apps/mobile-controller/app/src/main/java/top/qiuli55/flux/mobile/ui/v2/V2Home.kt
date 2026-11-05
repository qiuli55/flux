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
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SmartToy
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
import top.qiuli55.flux.mobile.data.Bot
import top.qiuli55.flux.mobile.data.ChatGroup
import kotlin.math.roundToInt

/** 首页分区：对话（人工）/ Bot（常驻执行者）/ 群聊。 */
private enum class HomeSection { CHATS, BOTS, GROUPS }

/** 首页：设备筛选 + 助理/额度/设计入口 + 对话/Bot/群聊三个分区。 */
@Composable
fun V2HomeScreen(
    device: String,
    onDeviceChange: (String) -> Unit,
    conversations: List<V2Conversation>,
    bots: List<Bot>,
    botUnreadCount: Int = 0,
    groups: List<ChatGroup>,
    groupsLoading: Boolean = false,
    billingHeadline: String,
    extraTop: androidx.compose.ui.unit.Dp,
    onOpenBilling: () -> Unit,
    onOpenDesigns: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenConversation: (V2Conversation) -> Unit,
    onDeleteConversation: (V2Conversation) -> Unit,
    onOpenBot: (Bot) -> Unit,
    onCreateBot: () -> Unit,
    onOpenGroup: (ChatGroup) -> Unit,
    onCreateGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    var section by remember { mutableStateOf(HomeSection.CHATS) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deviceMenu by remember { mutableStateOf(false) }
    var openRowId by remember { mutableStateOf<String?>(null) }
    var openRowDir by remember { mutableIntStateOf(-1) }
    var quickOpen by remember { mutableStateOf(false) }
    var quickHover by remember { mutableIntStateOf(-1) }
    val haptics = LocalV2Haptics.current
    val singleList = rememberLazyListState()
    val botList = rememberLazyListState()
    val multiList = rememberLazyListState()
    val compact = singleList.firstVisibleItemIndex > 0 ||
        singleList.firstVisibleItemScrollOffset > 24 ||
        botList.firstVisibleItemIndex > 0 ||
        botList.firstVisibleItemScrollOffset > 24 ||
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
    val filteredGroups = remember(groups, normalized) {
        groups.filter { group ->
            normalized.isEmpty() ||
                group.name.lowercase().contains(normalized) ||
                group.members.any { it.name.lowercase().contains(normalized) }
        }
    }
    val filteredBots = remember(bots, normalized) {
        bots.filter { bot ->
            normalized.isEmpty() ||
                bot.name.lowercase().contains(normalized) ||
                bot.description.lowercase().contains(normalized)
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
                    icon = Icons.Outlined.SmartToy,
                    title = "助理",
                    meta = "观察 · 判定 · 派活",
                    metaColor = V2Colors.violet,
                    onClick = onOpenAssistant,
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
                section = section,
                botUnread = botUnreadCount,
                onSelect = {
                    section = it
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
                when (section) {
                    HomeSection.GROUPS -> LazyColumn(Modifier.fillMaxSize(), state = multiList) {
                        if (filteredGroups.isEmpty()) {
                            item {
                                GroupsEmptyState(
                                    loading = groupsLoading,
                                    searching = normalized.isNotEmpty(),
                                    onCreate = onCreateGroup,
                                )
                            }
                        }
                        itemsIndexed(filteredGroups, key = { _, item -> item.id }) { index, group ->
                            V2Stagger(index) {
                                GroupRow(group = group, onClick = { onOpenGroup(group) })
                            }
                            if (index != filteredGroups.lastIndex) {
                                ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                        item {
                            NewGroupRow(onClick = onCreateGroup)
                            Spacer(Modifier.height(96.dp))
                        }
                    }

                    HomeSection.BOTS -> LazyColumn(Modifier.fillMaxSize(), state = botList) {
                        if (filteredBots.isEmpty()) {
                            item {
                                BotsEmptyState(
                                    searching = normalized.isNotEmpty(),
                                    onCreate = onCreateBot,
                                )
                            }
                        }
                        itemsIndexed(filteredBots, key = { _, item -> item.id }) { index, bot ->
                            V2Stagger(index) {
                                BotRow(bot = bot, onClick = { onOpenBot(bot) })
                            }
                            if (index != filteredBots.lastIndex) {
                                ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                        item {
                            NewBotRow(onClick = onCreateBot)
                            Spacer(Modifier.height(96.dp))
                        }
                    }

                    HomeSection.CHATS -> LazyColumn(Modifier.fillMaxSize(), state = singleList) {
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
                        // 快捷动作栈自下而上排列：最近的是「设计工作室」，最远的是「新建对话」
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
                Icons.Outlined.AddComment,
                contentDescription = "新建对话，按住可拖动选择快捷动作",
                tint = V2Colors.actionInk,
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
                    contentDescription = if (searchOpen) "关闭搜索" else "搜索 Bot",
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
                Label("搜索对话、Bot 或群聊", 12.5f, V2Colors.ink(.28f))
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
        Label(meta, 10.5f, metaColor, maxLines = 1)
    }
}

@Composable
private fun SectionTabs(section: HomeSection, botUnread: Int, onSelect: (HomeSection) -> Unit) {
    val density = LocalDensity.current
    var chatsX by remember { mutableFloatStateOf(0f) }
    var chatsW by remember { mutableFloatStateOf(0f) }
    var botsX by remember { mutableFloatStateOf(0f) }
    var botsW by remember { mutableFloatStateOf(0f) }
    var groupsX by remember { mutableFloatStateOf(0f) }
    var groupsW by remember { mutableFloatStateOf(0f) }
    val targetX = when (section) {
        HomeSection.CHATS -> chatsX
        HomeSection.BOTS -> botsX
        HomeSection.GROUPS -> groupsX
    }
    val targetW = when (section) {
        HomeSection.CHATS -> chatsW
        HomeSection.BOTS -> botsW
        HomeSection.GROUPS -> groupsW
    }
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
                text = "对话",
                selected = section == HomeSection.CHATS,
                modifier = Modifier.onGloballyPositioned {
                    chatsX = it.positionInParent().x
                    chatsW = it.size.width.toFloat()
                },
            ) { onSelect(HomeSection.CHATS) }
            SectionTab(
                text = if (botUnread > 0) "Bot $botUnread" else "Bot",
                selected = section == HomeSection.BOTS,
                modifier = Modifier.onGloballyPositioned {
                    botsX = it.positionInParent().x
                    botsW = it.size.width.toFloat()
                },
            ) { onSelect(HomeSection.BOTS) }
            SectionTab(
                text = "群聊",
                selected = section == HomeSection.GROUPS,
                modifier = Modifier.onGloballyPositioned {
                    groupsX = it.positionInParent().x
                    groupsW = it.size.width.toFloat()
                },
            ) { onSelect(HomeSection.GROUPS) }
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
        Triple(Icons.Outlined.AddComment, "新建对话", "开始一段新对话"),
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
            if (conversation.avatarShape.isNotBlank()) {
                Box(Modifier.size(46.dp)) {
                    BotAvatar(
                        shape = conversation.avatarShape,
                        color = conversation.avatarColor,
                        size = 46.dp,
                    )
                    if (online) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(10.dp)
                                .background(V2Colors.cyan500, CircleShape)
                                .border(2.dp, V2Colors.shellTop, CircleShape),
                        )
                    }
                }
            } else {
                Avatar(tint = agent.tint, online = online)
            }
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
                        Label("${conversation.unread}", 9.5f, V2Colors.actionInk, FontWeight.Medium)
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
private fun BotRow(bot: Bot, onClick: () -> Unit) {
    val paused = bot.status == "paused"
    val statusText = when {
        paused -> "已暂停"
        bot.schedule.isNullOrBlank() && bot.eventTriggers.isEmpty() -> "手动开工"
        else -> "定时开工"
    }
    val moment = when {
        !bot.nextRunAt.isNullOrBlank() -> "下次 ${shortMoment(bot.nextRunAt)}"
        !bot.lastRunAt.isNullOrBlank() -> "上次 ${relativeTime(bot.lastRunAt)}"
        else -> "还没开过工"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp)) {
            BotAvatar(shape = bot.avatarShape, color = bot.avatarColor, size = 46.dp)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .background(if (paused) V2Colors.amber500 else V2Colors.emerald, CircleShape)
                    .border(2.dp, V2Colors.shellTop, CircleShape),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(bot.name, 14f, V2Colors.ink(.90f), FontWeight.Medium, maxLines = 1)
                if (bot.runsFailed > 0) {
                    Spacer(Modifier.width(6.dp))
                    Label("失败 ${bot.runsFailed}", 9.5f, V2Colors.rose, FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Label(
                listOf(statusText, moment).joinToString(" · "),
                11f,
                V2Colors.ink(.40f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Label(
            if (bot.tokensUsedToday > 0) "${bot.tokensUsedToday} tok" else "",
            10f,
            V2Colors.ink(.28f),
            maxLines = 1,
        )
    }
}

@Composable
private fun BotsEmptyState(searching: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(
            if (searching) "没有匹配的 Bot" else "还没有 Bot",
            13f,
            V2Colors.ink(.45f),
        )
        if (!searching) {
            Spacer(Modifier.height(6.dp))
            Label("Bot 是常驻执行者：可定时开工、也能手动催一次", 11f, V2Colors.ink(.32f))
            Spacer(Modifier.height(14.dp))
            GhostButton(text = "新建 Bot", onClick = onCreate)
        }
    }
}

@Composable
private fun NewBotRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(V2Colors.ink(.05f))
                .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Label("新建 Bot", 13.5f, V2Colors.ink(.72f), FontWeight.Medium)
    }
}

/** Bot 列表里的绝对时间（下次开工 / 上次开工），只用于紧凑展示。 */
private fun shortMoment(raw: String?): String {
    val epoch = parseEpochSeconds(raw) ?: return "—"
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epoch * 1000))
}

@Composable
private fun GroupRow(group: ChatGroup, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatarStack(members = group.members)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(group.name, 14f, V2Colors.ink(.90f), FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Label(
                "${group.memberCount} 个 Bot · ${group.maxRounds} 轮讨论",
                11f,
                V2Colors.ink(.40f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Label(
            relativeTime(group.lastMessageAt ?: group.createdAt),
            11f,
            V2Colors.ink(.30f),
        )
    }
}

@Composable
private fun GroupsEmptyState(loading: Boolean, searching: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(
            when {
                loading -> "正在读取群聊…"
                searching -> "没有匹配的群聊"
                else -> "还没有群聊"
            },
            13f,
            V2Colors.ink(.45f),
        )
        if (!loading && !searching) {
            Spacer(Modifier.height(6.dp))
            Label("把几个 Bot 拉进同一个群，它们会互相接力讨论", 11f, V2Colors.ink(.32f))
            Spacer(Modifier.height(14.dp))
            GhostButton(text = "新建群聊", onClick = onCreate)
        }
    }
}

@Composable
private fun NewGroupRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(V2Colors.ink(.05f))
                .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Label("新建群聊", 13.5f, V2Colors.ink(.72f), FontWeight.Medium)
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
