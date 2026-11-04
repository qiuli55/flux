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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.data.ChatGroup
import top.qiuli55.flux.mobile.data.ChatGroupMember
import top.qiuli55.flux.mobile.data.ChatGroupMemoryState
import top.qiuli55.flux.mobile.data.ChatGroupMessage
import top.qiuli55.flux.mobile.data.Task

/**
 * 内部多 Bot 群聊页。
 *
 * 消息整条渲染（不做流式），靠 [state] 的 2s 增量轮询补充；
 * 用户发言后 90s 内视为「Bot 们正在讨论」，可手动打断。
 */
@Composable
fun GroupChatScreen(
    state: V2GroupChatState,
    botCandidates: List<Task>,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    /** 角色：admin = 群主，member = 普通成员。 */
    onSetRole: (String, String) -> Unit,
    /** 权限预设：plan / normal / full。 */
    onSetPermission: (String, String) -> Unit,
    /** 模型覆盖；keyId 为 null 表示原生模型。 */
    onSetModel: (String, String, String?) -> Unit,
    onLoadMemory: () -> Unit,
    onCreateBot: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val group = state.group
    val members = group?.members.orEmpty()
    var input by remember { mutableStateOf("") }
    var membersOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 讨论窗口倒计时：用一个本地时钟驱动，避免一直显示「正在讨论」。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.discussUntil) {
        while (true) {
            now = System.currentTimeMillis()
            if (state.discussUntil <= now) break
            delay(500)
        }
    }
    val discussing = state.discussUntil > now

    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToItem(state.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 6.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatarStack(members, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label(group?.name ?: "群聊", 15f, V2Colors.ink(.95f), FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (discussing) {
                                LiveDot(V2Colors.accent)
                                Spacer(Modifier.width(5.dp))
                            }
                            Label(
                                "${members.size} 个 Bot · ${group?.maxRounds ?: 2} 轮讨论",
                                10.5f,
                                V2Colors.ink(.40f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                Icons.Outlined.Group,
                { membersOpen = true },
                size = 32.dp,
                iconSize = 16.dp,
                contentDescription = "群成员",
            )
        }

        ThinDivider(color = V2Colors.lineSoft)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state.loadingMessages) {
                        LiveDot(V2Colors.accent)
                        Spacer(Modifier.height(10.dp))
                        Label("正在载入消息…", 12f, V2Colors.ink(.40f))
                    } else {
                        Label("群聊已就绪", 13f, V2Colors.ink(.55f), FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Label(
                            "打个招呼，或 @ 某个 Bot 让它先发言",
                            11f,
                            V2Colors.ink(.34f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        GroupMessageRow(
                            message = message,
                            members = members,
                        )
                    }
                }
            }
        }

        if (discussing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveDot(V2Colors.accent)
                Spacer(Modifier.width(6.dp))
                Label("Bot 们正在讨论…", 10.5f, V2Colors.ink(.50f), modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(V2Colors.ink(.06f))
                        .v2Pressable(pressScale = .94f, onClick = onInterrupt)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Stop, null, tint = V2Colors.ink(.62f), modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                    Label("打断", 10f, V2Colors.ink(.62f), FontWeight.Medium)
                }
            }
        }

        val composerShape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 52.dp)
                .clip(composerShape)
                .background(V2Colors.surface(.86f))
                .border(1.dp, V2Colors.ink(.10f), composerShape)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Label(
                        if (state.sending) "已发送，等待 Bot 回应…" else "发送消息，@Bot 可让它先发言",
                        13.5f,
                        V2Colors.ink(.30f),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = V2Colors.ink(.88f)),
                    cursorBrush = SolidColor(V2Colors.accent),
                    maxLines = 4,
                )
            }
            Spacer(Modifier.width(8.dp))
            val enabled = input.isNotBlank() && !state.sending
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (enabled) V2Colors.accent else V2Colors.ink(.07f))
                    .v2Pressable(pressScale = .94f) {
                        if (!enabled) return@v2Pressable
                        val text = input.trim()
                        input = ""
                        onSend(text)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送",
                    tint = if (enabled) V2Colors.actionInk else V2Colors.ink(.30f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (membersOpen && group != null) {
        GroupMembersSheet(
            group = group,
            candidates = botCandidates,
            memory = state.memory,
            loadingMemory = state.loadingMemory,
            onAdd = { onAddMember(it) },
            onRemove = { onRemoveMember(it) },
            onSetRole = { taskId, role -> onSetRole(taskId, role) },
            onSetPermission = { taskId, mode -> onSetPermission(taskId, mode) },
            onSetModel = { taskId, model, keyId -> onSetModel(taskId, model, keyId) },
            onLoadMemory = onLoadMemory,
            onCreateBot = {
                membersOpen = false
                onCreateBot()
            },
            onDeleteGroup = {
                membersOpen = false
                onDeleteGroup()
            },
            onDismiss = { membersOpen = false },
        )
    }
}

@Composable
private fun GroupMessageRow(message: ChatGroupMessage, members: List<ChatGroupMember>) {
    when (message.role) {
        "user" -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp))
                    .background(V2Colors.ink(.90f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Label(message.content, 14f, V2Colors.surface)
            }
        }

        "system" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Label(message.content, 10.5f, V2Colors.ink(.34f))
        }

        else -> {
            val member = members.firstOrNull { it.taskId == message.taskId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BotAvatar(
                    shape = member?.avatarShape ?: V2DefaultAvatarShape,
                    color = member?.avatarColor ?: V2DefaultAvatarColor,
                    size = 30.dp,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Label(
                        message.authorName.ifBlank { "Bot" },
                        10.5f,
                        V2Colors.ink(.45f),
                        FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
                            .background(V2Colors.surface(.92f))
                            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Label(message.content, 14f, V2Colors.ink(.86f))
                    }
                }
            }
        }
    }
}

// ================================================================== 群成员

@Composable
private fun GroupMembersSheet(
    group: ChatGroup,
    candidates: List<Task>,
    memory: ChatGroupMemoryState?,
    loadingMemory: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetRole: (String, String) -> Unit,
    onSetPermission: (String, String) -> Unit,
    onSetModel: (String, String, String?) -> Unit,
    onLoadMemory: () -> Unit,
    onCreateBot: () -> Unit,
    onDeleteGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val memberIds = group.members.map { it.taskId }.toSet()
    val addable = candidates.filterNot { it.id in memberIds }
    val candidatesById = remember(candidates) { candidates.associateBy { it.id } }
    var confirmDelete by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onLoadMemory() }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = group.name,
            subtitle = "${group.memberCount} 个 Bot · ${group.maxRounds} 轮讨论",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            val description = group.description.orEmpty()
            if (description.isNotBlank()) {
                Label(description, 11.5f, V2Colors.ink(.45f))
                Spacer(Modifier.height(14.dp))
            }
            Label("群成员", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            group.members.forEach { member ->
                val expanded = expandedId == member.taskId
                val cardShape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(V2Colors.surface(.55f))
                        .border(1.dp, V2Colors.ink(.07f), cardShape),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .v2Pressable(pressScale = .98f) {
                                expandedId = if (expanded) null else member.taskId
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(member.avatarShape, member.avatarColor, size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label(
                                    member.name,
                                    12.5f,
                                    V2Colors.ink(.88f),
                                    FontWeight.Medium,
                                    maxLines = 1,
                                )
                                if (member.isAdmin) {
                                    Spacer(Modifier.width(6.dp))
                                    V2Chip(text = "群主", color = V2Colors.accent)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Label(
                                "${member.model ?: "默认模型"} · ${permissionTitle(member.permissionMode)}",
                                10f,
                                V2Colors.ink(.36f),
                                maxLines = 1,
                            )
                        }
                        Label(
                            "移除",
                            10f,
                            V2Colors.rose,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .v2Pressable(pressScale = .94f) { onRemove(member.taskId) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    if (expanded) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                            ThinDivider(color = V2Colors.lineSoft)
                            Spacer(Modifier.height(10.dp))
                            Label("发言权", 10.5f, V2Colors.ink(.45f), FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                V2Chip(
                                    text = "群主",
                                    color = if (member.isAdmin) V2Colors.accent else V2Colors.ink(.45f),
                                    modifier = Modifier.v2Pressable(pressScale = .94f) {
                                        onSetRole(member.taskId, "admin")
                                    },
                                )
                                V2Chip(
                                    text = "普通成员",
                                    color = if (member.isAdmin) V2Colors.ink(.45f) else V2Colors.accent,
                                    modifier = Modifier.v2Pressable(pressScale = .94f) {
                                        onSetRole(member.taskId, "member")
                                    },
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            Label("权限预设", 10.5f, V2Colors.ink(.45f), FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("plan" to "计划", "normal" to "标准", "full" to "全权")
                                    .forEach { (mode, title) ->
                                        val active = member.permissionMode == mode
                                        V2Chip(
                                            text = title,
                                            color = if (active) V2Colors.accent else V2Colors.ink(.45f),
                                            modifier = Modifier.v2Pressable(pressScale = .94f) {
                                                onSetPermission(member.taskId, mode)
                                            },
                                        )
                                    }
                            }

                            Spacer(Modifier.height(12.dp))
                            Label("模型", 10.5f, V2Colors.ink(.45f), FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            val options = modelOptionsFor(member.taskId, candidatesById)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    val active = member.model.isNullOrBlank()
                                    V2Chip(
                                        text = "默认",
                                        color = if (active) V2Colors.accent else V2Colors.ink(.45f),
                                        modifier = Modifier.v2Pressable(pressScale = .94f) {
                                            onSetModel(member.taskId, "", null)
                                        },
                                    )
                                }
                                items(options) { option ->
                                    val active = member.model == option.model &&
                                        member.apiKeyId == option.keyId
                                    V2Chip(
                                        text = option.label,
                                        color = if (active) V2Colors.accent else V2Colors.ink(.45f),
                                        modifier = Modifier.v2Pressable(pressScale = .94f) {
                                            onSetModel(member.taskId, option.model, option.keyId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("添加 Bot", 11f, V2Colors.ink(.45f), FontWeight.Medium, modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(V2Colors.ink(.05f))
                        .v2Pressable(pressScale = .94f, onClick = onCreateBot)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, tint = V2Colors.ink(.62f), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Label("新建 Bot", 10f, V2Colors.ink(.62f), FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (addable.isEmpty()) {
                Label("没有可添加的 Bot，先新建一个吧", 10.5f, V2Colors.ink(.32f))
            } else {
                addable.take(30).forEach { task ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .v2Pressable(pressScale = .98f) { onAdd(task.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(task.avatarShape, task.avatarColor, size = 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Label(
                            task.displayName,
                            12f,
                            V2Colors.ink(.75f),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Icon(Icons.Outlined.Check, null, tint = V2Colors.ink(.22f), modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            ThinDivider(color = V2Colors.lineSoft)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("群记忆", 11f, V2Colors.ink(.45f), FontWeight.Medium, modifier = Modifier.weight(1f))
                if (loadingMemory) {
                    LiveDot(V2Colors.accent)
                    Spacer(Modifier.width(5.dp))
                    Label("读取中", 10f, V2Colors.ink(.34f))
                }
            }
            Spacer(Modifier.height(8.dp))
            val groupMemory = memory?.group?.content?.trim().orEmpty()
            val teamMemory = memory?.team?.content?.trim().orEmpty()
            if (groupMemory.isBlank() && teamMemory.isBlank()) {
                Label(
                    "还没有摘要：讨论累计到一定长度后会自动压成记忆",
                    10.5f,
                    V2Colors.ink(.32f),
                )
            } else {
                if (groupMemory.isNotBlank()) {
                    Label("本群共享", 10f, V2Colors.ink(.40f), FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Label(groupMemory, 10.5f, V2Colors.ink(.55f))
                }
                if (teamMemory.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Label("团队共享 · 跨群可见", 10f, V2Colors.ink(.40f), FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Label(teamMemory, 10.5f, V2Colors.ink(.55f))
                }
            }

            Spacer(Modifier.height(24.dp))
            ThinDivider(color = V2Colors.lineSoft)
            Spacer(Modifier.height(16.dp))
            if (confirmDelete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("删除后聊天记录一并消失，确定？", 11f, V2Colors.rose, modifier = Modifier.weight(1f))
                    GhostButton(text = "取消", onClick = { confirmDelete = false })
                    Spacer(Modifier.width(8.dp))
                    GhostButton(text = "删除", onClick = onDeleteGroup, contentColor = V2Colors.rose)
                }
            } else {
                GhostButton(
                    text = "删除群聊",
                    onClick = { confirmDelete = true },
                    contentColor = V2Colors.rose,
                    icon = Icons.Outlined.DeleteOutline,
                )
            }
        }
    }
}

// ================================================================== 新建群聊

@Composable
fun CreateGroupSheet(
    candidates: List<Task>,
    onCreateBot: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        maxRounds: Int,
        memberIds: List<String>,
        adminTaskId: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var rounds by remember { mutableStateOf(2) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var adminId by remember { mutableStateOf<String?>(null) }

    // 取消勾选后不能继续当群主
    LaunchedEffect(selected) {
        if (adminId != null && adminId !in selected) adminId = null
    }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        SheetHeader(
            title = "新建群聊",
            subtitle = "选 2 个以上 Bot，它们会接力讨论",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            GroupTextField(value = name, placeholder = "群名称", onValueChange = { name = it })
            Spacer(Modifier.height(10.dp))
            GroupTextField(
                value = description,
                placeholder = "群说明（可选）",
                onValueChange = { description = it },
            )

            Spacer(Modifier.height(16.dp))
            Label("讨论轮数", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { count ->
                    val active = rounds == count
                    V2Chip(
                        text = "$count 轮",
                        color = if (active) V2Colors.accent else V2Colors.ink(.55f),
                        modifier = Modifier.v2Pressable(pressScale = .94f) { rounds = count },
                    )
                }
                Label(
                    "轮数越多发言越久",
                    10f,
                    V2Colors.ink(.30f),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(
                    "选择 Bot（${selected.size}）",
                    11f,
                    V2Colors.ink(.45f),
                    FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(V2Colors.ink(.05f))
                        .v2Pressable(pressScale = .94f, onClick = onCreateBot)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PersonAdd, null, tint = V2Colors.ink(.62f), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Label("新建 Bot", 10f, V2Colors.ink(.62f), FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (candidates.isEmpty()) {
                Label("还没有可用的 Bot，先新建一个", 10.5f, V2Colors.ink(.32f))
            } else {
                candidates.forEach { task ->
                    val picked = task.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (picked) V2Colors.accent.copy(alpha = .08f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (picked) V2Colors.accent.copy(alpha = .30f) else V2Colors.ink(.06f),
                                RoundedCornerShape(14.dp),
                            )
                            .v2Pressable(pressScale = .98f) {
                                selected = if (picked) selected - task.id else selected + task.id
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BotAvatar(task.avatarShape, task.avatarColor, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Label(task.displayName, 12.5f, V2Colors.ink(.88f), FontWeight.Medium, maxLines = 1)
                            if (task.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Label(task.description, 10f, V2Colors.ink(.36f), maxLines = 1)
                            }
                        }
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (picked) V2Colors.accent else Color.Transparent)
                                .border(1.dp, if (picked) V2Colors.accent else V2Colors.ink(.20f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) {
                                Icon(Icons.Outlined.Check, null, tint = V2Colors.actionInk, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            Label("群主（可选）", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Label("群主每轮先发言，并负责 @ 其他 Bot 接力", 10f, V2Colors.ink(.32f))
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    V2Chip(
                        text = "不设群主",
                        color = if (adminId == null) V2Colors.accent else V2Colors.ink(.45f),
                        modifier = Modifier.v2Pressable(pressScale = .94f) { adminId = null },
                    )
                }
                items(candidates.filter { it.id in selected }) { task ->
                    val active = adminId == task.id
                    V2Chip(
                        text = task.displayName,
                        color = if (active) V2Colors.accent else V2Colors.ink(.45f),
                        modifier = Modifier.v2Pressable(pressScale = .94f) { adminId = task.id },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            val canCreate = name.isNotBlank() && selected.isNotEmpty()
            PrimaryButton(
                text = "创建群聊",
                onClick = {
                    if (!canCreate) return@PrimaryButton
                    onCreate(
                        name.trim(),
                        description.trim(),
                        rounds,
                        selected.toList(),
                        adminId,
                    )
                },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!canCreate) {
                Spacer(Modifier.height(8.dp))
                Label(
                    if (name.isBlank()) "先给群起个名字" else "至少选一个 Bot",
                    10f,
                    V2Colors.ink(.32f),
                )
            }
        }
    }
}

// ================================================================== 新建 Bot

@Composable
fun CreateBotSheet(
    onCreate: (name: String, shape: String, color: String, onResult: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(V2AvatarShapes.random()) }
    var color by remember { mutableStateOf(V2AvatarColors.random()) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        SheetHeader(
            title = "新建 Bot",
            subtitle = "名称自定义 · 头像由形状与颜色组成",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(shape = shape, color = color, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    GroupTextField(
                        value = name,
                        placeholder = "Bot 名称",
                        onValueChange = {
                            name = it
                            error = null
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Label("之后它的每条发言都会显示这个头像", 9.5f, V2Colors.ink(.30f))
                }
            }

            Spacer(Modifier.height(20.dp))
            Label("形状", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            V2AvatarShapes.chunked(6).forEach { rowShapes ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowShapes.forEach { item ->
                        val active = item == shape
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) V2Colors.ink(.06f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (active) V2Colors.ink(.28f) else V2Colors.ink(.08f),
                                    RoundedCornerShape(12.dp),
                                )
                                .v2Pressable(pressScale = .92f) { shape = item },
                            contentAlignment = Alignment.Center,
                        ) {
                            BotAvatar(shape = item, color = color, size = 30.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Label("颜色", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(V2AvatarColors) { item ->
                    AvatarColorDot(
                        color = item,
                        selected = item == color,
                        size = 28.dp,
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Label(it, 10.5f, V2Colors.rose)
            }

            Spacer(Modifier.height(22.dp))
            val canSubmit = name.isNotBlank() && !submitting
            PrimaryButton(
                text = if (submitting) "正在创建…" else "创建 Bot",
                onClick = {
                    if (!canSubmit) return@PrimaryButton
                    submitting = true
                    onCreate(name.trim(), shape, color) { result ->
                        submitting = false
                        if (result.isNotEmpty()) error = result
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ================================================================== 复用小组件

/** 权限预设的中文标签。 */
private fun permissionTitle(mode: String): String = when (mode) {
    "plan" -> "计划"
    "full" -> "全权"
    else -> "标准"
}

/** 成员可选模型 = 所属 Agent 的模型（原生 + 密钥库），缺失时退回全局模型。 */
private fun modelOptionsFor(taskId: String, candidates: Map<String, Task>): List<V2ModelOption> {
    val agentId = candidates[taskId]?.agentId.orEmpty()
    return V2Registry.agentModels[agentId]?.takeIf { it.isNotEmpty() }
        ?: V2Registry.models.map { V2ModelOption(it, it) }
}


/** 群聊页/建群面板共用的单行输入框。 */
@Composable
private fun GroupTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(V2Colors.surface(.70f))
            .border(1.dp, V2Colors.ink(.09f), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Label(placeholder, 13f, V2Colors.ink(.30f))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 13.5.sp, color = V2Colors.ink(.88f)),
            cursorBrush = SolidColor(V2Colors.accent),
            maxLines = 3,
        )
    }
}

/** 首页与群聊页头像叠放：最多三个头像，其余只显示数量。 */
@Composable
internal fun GroupAvatarStack(
    members: List<ChatGroupMember>,
    size: Dp = 46.dp,
) {
    val unit = size.value / 46f
    Box(Modifier.size(size)) {
        val shown = members.take(3)
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(V2Colors.ink(.05f))
                    .border(1.dp, V2Colors.ink(.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Group, null, tint = V2Colors.ink(.45f), modifier = Modifier.size(size * .42f))
            }
        } else {
            shown.forEachIndexed { index, member ->
                BotAvatar(
                    shape = member.avatarShape,
                    color = member.avatarColor,
                    size = (26 * unit).dp,
                    modifier = Modifier.offset(
                        x = (if (index == 2) 10 * unit else index * 20 * unit).dp,
                        y = (if (index == 2) 20 * unit else 0f).dp,
                    ),
                )
            }
        }
    }
}
