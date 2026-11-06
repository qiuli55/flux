package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 新建对话面板：名称与头像自定义，创建后直接进入该对话。
 * 头像沿用形状 × 颜色的确定性生成规则（见 V2BotAvatar.kt）。
 */
@Composable
fun CreateConversationSheet(
    onCreate: (
        name: String,
        shape: String,
        color: String,
        agentId: String?,
        onResult: (String) -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
    agentOptions: List<V2AgentOption> = emptyList(),
    title: String = "新建对话",
    subtitle: String = "名称自定义 · 头像由形状与颜色组成",
) {
    var name by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(V2AvatarShapes.random()) }
    var color by remember { mutableStateOf(V2AvatarColors.random()) }
    var agentId by remember(agentOptions) { mutableStateOf(agentOptions.firstOrNull()?.agent?.id) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        SheetHeader(
            title = title,
            subtitle = subtitle,
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
                    ConversationTextField(
                        value = name,
                        placeholder = "对话名称",
                        onValueChange = {
                            name = it
                            error = null
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Label("之后这段对话的每条发言都会显示这个头像", 9.5f, V2Colors.ink(.30f))
                }
            }

            if (agentOptions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Label("执行 Agent", 11f, V2Colors.ink(.45f), FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(agentOptions) { option ->
                        val active = option.agent.id == agentId
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) V2Colors.ink(.06f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (active) V2Colors.ink(.28f) else V2Colors.ink(.08f),
                                    RoundedCornerShape(999.dp),
                                )
                                .v2Pressable(pressScale = .95f) { agentId = option.agent.id }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(option.agent.tint, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Label(
                                option.agent.name,
                                11.5f,
                                V2Colors.ink(if (active) .88f else .50f),
                                FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
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
                text = if (submitting) "正在创建…" else "创建对话",
                onClick = {
                    if (!canSubmit) return@PrimaryButton
                    submitting = true
                    onCreate(name.trim(), shape, color, agentId) { result ->
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

/** 新建对话面板里的单行输入框。 */
@Composable
private fun ConversationTextField(
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
