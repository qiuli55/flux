package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 对话页运行态：会话、Agent、模型都由当前设备配置推导。 */
data class V2ChatState(
    val conversationId: String?,
    val title: String,
    val device: String,
    val agentId: String,
    val model: String,
    /** 新建任务时的决策方式：auto = AI 自己定，manual = 停下来问我。 */
    val decisionMode: String = "auto",
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
    /** 首条消息发出后等待 Agent 回复。 */
    sending: Boolean = false,
    /** 任务已结束：只读历史，不允许继续发送。 */
    locked: Boolean = false,
    /** 真实任务对话没有「删除会话」接口，隐藏该按钮。 */
    showDelete: Boolean = true,
    /** 任务创建后 Agent 已固定，不允许再切换。 */
    canSwitchAgent: Boolean = true,
    notice: String? = null,
    suggestions: List<String> = demoChatSuggestions,
    /** 真实任务对话没有「从某设备接续」这一层，隐藏那条横幅。 */
    showHandoff: Boolean = true,
    /** 新建对话时切换任务级决策方式（auto / manual）。 */
    onDecisionModeChange: (String) -> Unit = {},
    /** 非空时点击 Agent 名称展开的是就地下拉栏（任务对话切换执行 Agent）。 */
    agentOptions: List<V2AgentOption> = emptyList(),
    onSwitchAgent: (String) -> Unit = {},
) {
    val agent = demoAgent(chat.agentId)
    val listState = rememberLazyListState()
    var input by remember(chat.conversationId) { mutableStateOf("") }
    var agentMenuOpen by remember { mutableStateOf(false) }
    val inputEnabled = !locked && !sending

    LaunchedEffect(messages.size, chat.conversationId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(Icons.Outlined.ArrowBack, onBack, size = 32.dp, iconSize = 16.dp)
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
                            Label("${chat.title} · ${chat.device}", 9.5f, V2Colors.ink(.36f), maxLines = 1)
                        }
                    }
                }

                DropdownMenu(
                    expanded = agentMenuOpen && agentOptions.isNotEmpty(),
                    onDismissRequest = { agentMenuOpen = false },
                    modifier = Modifier.background(Color.White),
                ) {
                    agentOptions.forEach { option ->
                        val selected = option.agent.id == chat.agentId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Label(option.agent.name, 12f, V2Colors.ink(.90f), FontWeight.Medium)
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
                                            ?.let { "${option.agent.role} · $it" }
                                            ?: option.agent.role,
                                        9.5f,
                                        V2Colors.ink(.40f),
                                        maxLines = 1,
                                    )
                                }
                            },
                            onClick = {
                                agentMenuOpen = false
                                if (!selected) onSwitchAgent(option.agent.id)
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
                background = Color.White.copy(alpha = .55f),
                tint = V2Colors.cyan700.copy(alpha = .8f),
            )
            if (showDelete) {
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    icon = Icons.Outlined.Delete,
                    onClick = onDelete,
                    size = 32.dp,
                    iconSize = 14.dp,
                    background = Color.White.copy(alpha = .55f),
                    tint = V2Colors.rose.copy(alpha = .7f),
                )
            }
        }

        if (showHandoff) {
            HandoffBanner(device = chat.device)
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
            ) {
                Label(notice, 10f, V2Colors.amber700, modifier = Modifier.weight(1f))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(messages) { _, message ->
                MessageRow(message)
            }
            if (sending) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Label("Agent 正在处理…", 9f, V2Colors.ink(.30f), mono = true)
                    }
                }
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
                            .background(Color.White.copy(alpha = .45f))
                            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(999.dp))
                            .clickable(enabled = inputEnabled) { onSend(suggestion) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Label(suggestion, 10f, V2Colors.ink(.42f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

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

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .86f), Color.White.copy(alpha = .64f)),
                    ),
                )
                .border(1.dp, V2Colors.line, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Label(
                        if (locked) "任务已结束，无法继续发送"
                        else if (sending) "等待 ${agent.name} 回复…"
                        else "继续向 ${agent.name} 发送",
                        12f,
                        V2Colors.ink(.26f),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputEnabled,
                    textStyle = TextStyle(fontSize = 12.sp, color = V2Colors.ink(.85f)),
                    cursorBrush = SolidColor(V2Colors.accent),
                    maxLines = 4,
                )
            }
            Spacer(Modifier.width(8.dp))
            val enabled = input.isNotBlank() && inputEnabled
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) V2Colors.cyan400 else V2Colors.ink(.08f))
                    .clickable(enabled = enabled) {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            input = ""
                            onSend(text)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (enabled) V2Colors.actionInk else V2Colors.ink(.25f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DecisionModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .14f) else Color.White.copy(alpha = .45f))
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
private fun MessageRow(message: V2Message) {
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
                        .background(Color.White.copy(alpha = .75f))
                        .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Label(message.text, 12f, V2Colors.ink(.70f))
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
