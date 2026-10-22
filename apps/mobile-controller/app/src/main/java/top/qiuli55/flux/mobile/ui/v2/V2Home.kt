package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首页：设备筛选 + 决策/队列入口 + 单一/多 Agent 两个分区。 */
@Composable
fun V2HomeScreen(
    device: String,
    onDeviceChange: (String) -> Unit,
    conversations: List<V2Conversation>,
    groups: List<V2Group>,
    pendingCount: Int,
    runningCount: Int,
    extraTop: androidx.compose.ui.unit.Dp,
    onOpenDecisions: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenConversation: (V2Conversation) -> Unit,
    onOpenGroup: (V2Group) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
) {
    var multiMode by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deviceMenu by remember { mutableStateOf(false) }

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
                extraTop = extraTop,
                onToggleDevice = { deviceMenu = !deviceMenu },
                onToggleSearch = {
                    if (searchOpen) query = ""
                    searchOpen = !searchOpen
                },
                onOpenSettings = onOpenSettings,
            )

            if (searchOpen) {
                SearchField(query = query, onQueryChange = { query = it })
            }

            Column(Modifier.padding(top = if (searchOpen) 8.dp else 16.dp)) {
                EntryRow(
                    icon = Icons.Outlined.Schedule,
                    title = "决策中心",
                    meta = "$pendingCount 项待确认",
                    metaColor = V2Colors.amber,
                    onClick = onOpenDecisions,
                )
                EntryRow(
                    icon = Icons.Outlined.List,
                    title = "任务队列",
                    meta = "$runningCount 个执行中",
                    metaColor = V2Colors.cyan700,
                    onClick = onOpenQueue,
                )
            }

            ThinDivider(modifier = Modifier.padding(top = 12.dp))

            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SectionTab("单一 Agent 对话", !multiMode) {
                    multiMode = false
                    deviceMenu = false
                }
                SectionTab("多 Agent 协作任务", multiMode) {
                    multiMode = true
                    deviceMenu = false
                }
            }

            if (multiMode) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(filteredGroups, key = { _, item -> item.id }) { index, group ->
                        GroupRow(group = group, onClick = { onOpenGroup(group) })
                        if (index != filteredGroups.lastIndex) {
                            ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(filteredConversations, key = { _, item -> item.id }) { index, conversation ->
                        ConversationRow(
                            conversation = conversation,
                            online = conversation.unread > 0,
                            onClick = { onOpenConversation(conversation) },
                        )
                        if (index != filteredConversations.lastIndex) {
                            ThinDivider(color = V2Colors.lineSoft, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 22.dp)
                .size(56.dp)
                .shadow(14.dp, CircleShape)
                .clip(CircleShape)
                .background(V2Colors.accent)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AddComment,
                contentDescription = "新建对话",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

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
    extraTop: androidx.compose.ui.unit.Dp,
    onToggleDevice: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp + extraTop, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleDevice),
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
                modifier = Modifier.padding(start = 6.dp, top = 3.dp).size(16.dp),
            )
        }
        Row(
            Modifier
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onToggleSearch),
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
            .background(Color.White)
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
    meta: String,
    metaColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(.70f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Label(title, 15f, V2Colors.ink(.90f), modifier = Modifier.weight(1f), maxLines = 1)
        Label(meta, 10.5f, metaColor, maxLines = 1)
    }
}

@Composable
private fun SectionTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clickable(onClick = onClick)
            .padding(bottom = 10.dp),
    ) {
        Label(
            text,
            13.5f,
            if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f),
            if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (selected) V2Colors.accent else Color.Transparent),
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: V2Conversation,
    online: Boolean,
    onClick: () -> Unit,
) {
    val agent = demoAgent(conversation.agentId)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
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
}

@Composable
private fun GroupRow(group: V2Group, onClick: () -> Unit) {
    val stageName = group.stages.getOrNull(group.stage)?.name ?: "已完成"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
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
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
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
            .clickable(onClick = onClick)
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
