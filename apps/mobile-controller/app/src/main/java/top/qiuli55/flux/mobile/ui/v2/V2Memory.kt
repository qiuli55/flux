package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.UserMemoryEntry

/**
 * 用户记忆编辑面板（设置 → 用户记忆）。
 *
 * 这里的条目是**账号级**的：云端为权威存储，本机只是缓存。换设备登录同一账号
 * 就能读到同一份画像；每轮任务结束后平台会自动提炼（开关可关）。
 */
@Composable
fun MemorySheet(
    memories: List<UserMemoryEntry>,
    loading: Boolean,
    saving: Boolean,
    autoCapture: Boolean,
    cloudReady: Boolean,
    cloudError: String,
    onRefresh: () -> Unit,
    onSync: (onResult: (String) -> Unit) -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onAdd: (String, (String) -> Unit) -> Unit,
    onUpdate: (String, String, (String) -> Unit) -> Unit,
    onDelete: (String, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var formOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<UserMemoryEntry?>(null) }
    var syncing by remember { mutableStateOf(false) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .93f) {
        SheetHeader(
            title = "用户记忆",
            subtitle = "账号级长期记忆 · 云端同步，换设备也认识你",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 云端状态：就绪 / 未连接。同步失败时如实展示原因，不假装已同步
            WhiteCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (cloudReady) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                        null,
                        tint = if (cloudReady) V2Colors.emerald else V2Colors.ink(.40f),
                        modifier = Modifier.width(18.dp).height(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label(
                            if (cloudReady) "已连接账号云" else "云端暂未连接",
                            12f,
                            V2Colors.ink(.88f),
                            FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Label(
                            if (cloudReady) "记忆会同步到你的账号，其他设备登录后自动拉取"
                            else cloudError.ifBlank { "本机可正常编辑，恢复连接后会自动补传" },
                            10f,
                            if (cloudReady) V2Colors.ink(.42f) else V2Colors.rose.copy(alpha = .85f),
                            maxLines = 3,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    MemoryPill(
                        if (syncing) "同步中…" else "立即同步",
                        primary = true,
                        enabled = !syncing,
                    ) {
                        syncing = true
                        error = ""
                        onSync { err ->
                            syncing = false
                            if (err.isBlank()) notice = "已与账号云对账" else error = err
                        }
                    }
                }
            }

            WhiteCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        null,
                        tint = V2Colors.ink(.55f),
                        modifier = Modifier.width(17.dp).height(17.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label("自动记录", 13.5f, V2Colors.ink(.90f))
                        Spacer(Modifier.height(2.dp))
                        Label(
                            "每轮对话结束后自动提炼稳定的偏好与禁忌，最多 3 条",
                            10f,
                            V2Colors.ink(.38f),
                        )
                    }
                    LightSwitch(autoCapture, onChange = onToggleAuto)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(if (loading) "读取中…" else "共 ${memories.size} 条", 10.5f, V2Colors.ink(.40f))
                Spacer(Modifier.weight(1f))
                MemoryPill("刷新") { onRefresh() }
                Spacer(Modifier.width(8.dp))
                MemoryPill("添加", primary = true) {
                    formOpen = true
                    editingId = null
                    draft = ""
                    deleteTarget = null
                    error = ""
                }
            }

            if (notice.isNotBlank()) {
                Label(notice, 10.5f, V2Colors.emerald, maxLines = 2)
            }
            if (error.isNotBlank()) {
                Label(error, 10.5f, V2Colors.rose, maxLines = 3)
            }

            if (!loading && memories.isEmpty()) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("还没有记忆", 12.5f, V2Colors.ink(.80f), FontWeight.Medium)
                        Label(
                            "完成任务后会自动长出来；也可以手动写一条，比如「回答先给结论」「删除文件前必须确认」。",
                            10.5f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }

            memories.forEach { entry ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label(entry.content, 12.5f, V2Colors.ink(.88f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MemoryPill(
                                if (entry.isAuto) "自动" else "手动",
                                tint = if (entry.isAuto) V2Colors.cyan700 else V2Colors.ink(.45f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Label(
                                entry.createdAt?.take(10).orEmpty(),
                                9f,
                                V2Colors.ink(.30f),
                                mono = true,
                            )
                            Spacer(Modifier.weight(1f))
                            MemoryPill("修改") {
                                formOpen = true
                                editingId = entry.id
                                draft = entry.content
                                deleteTarget = null
                                error = ""
                            }
                            Spacer(Modifier.width(6.dp))
                            MemoryPill("删除", danger = true) {
                                deleteTarget = entry
                                formOpen = false
                            }
                        }
                    }
                }
            }

            deleteTarget?.let { entry ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("删除这条记忆？", 12f, V2Colors.rose, FontWeight.Medium)
                        Label(entry.content, 10f, V2Colors.ink(.45f), maxLines = 3)
                        Label("删除会同时从账号云移除，其他设备也不会再看到。", 10f, V2Colors.ink(.40f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MemoryPill("取消") { deleteTarget = null }
                            MemoryPill("确认删除", danger = true) {
                                onDelete(entry.id) { err ->
                                    if (err.isBlank()) {
                                        deleteTarget = null
                                        notice = "已删除一条记忆"
                                    } else {
                                        error = err
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (formOpen) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(
                            if (editingId == null) "添加一条记忆" else "修改这条记忆",
                            13f,
                            V2Colors.ink(.90f),
                            FontWeight.SemiBold,
                        )
                        Label(
                            "写稳定的偏好或禁忌，不要放密钥与一次性任务。",
                            10f,
                            V2Colors.ink(.40f),
                        )
                        MemoryField(
                            value = draft,
                            onChange = { draft = it },
                            placeholder = "例如：回答先给结论，再展开细节",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MemoryPill("取消") {
                                formOpen = false
                                editingId = null
                                draft = ""
                                error = ""
                            }
                            MemoryPill("保存", primary = true, enabled = !saving) {
                                val text = draft.trim()
                                if (text.isBlank()) {
                                    error = "内容不能为空"
                                } else {
                                    error = ""
                                    val done: (String) -> Unit = { err ->
                                        if (err.isBlank()) {
                                            draft = ""
                                            formOpen = false
                                            editingId = null
                                            notice = "已保存"
                                        } else {
                                            error = err
                                        }
                                    }
                                    val target = editingId
                                    if (target == null) onAdd(text, done)
                                    else onUpdate(target, text, done)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .62f), RoundedCornerShape(12.dp))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isBlank()) {
            Label(placeholder, 11f, V2Colors.ink(.28f))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = V2Colors.ink(.88f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            cursorBrush = SolidColor(V2Colors.accent),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
    }
}

@Composable
private fun MemoryPill(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> V2Colors.ink(.06f)
        primary -> V2Colors.cyan400
        danger -> V2Colors.rose.copy(alpha = .10f)
        else -> Color.White.copy(alpha = .7f)
    }
    val content = when {
        !enabled -> V2Colors.ink(.30f)
        primary -> V2Colors.cyan800
        danger -> V2Colors.rose
        else -> V2Colors.ink(.62f)
    }
    Box(
        Modifier
            .background(background, RoundedCornerShape(9.dp))
            .border(
                1.dp,
                if (primary || danger) Color.Transparent else V2Colors.ink(.10f),
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 10f, content, maxLines = 1)
    }
}

@Composable
private fun MemoryPill(
    text: String,
    tint: Color,
) {
    Box(
        Modifier
            .background(tint.copy(alpha = .10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Label(text, 8.5f, tint, maxLines = 1)
    }
}
