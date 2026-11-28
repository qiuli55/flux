package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.data.AssistantHistoryDetail
import top.qiuli55.flux.mobile.data.AssistantProgressItem

private fun bucketTone(bucket: String): Color = when (bucket) {
    "possibly_stalled" -> V2Colors.amber700
    "running" -> V2Colors.cyan700
    "failed" -> V2Colors.rose700
    "completed" -> V2Colors.emerald700
    "cancelled" -> V2Colors.ink(.45f)
    else -> V2Colors.ink(.5f)
}

/**
 * 项目助理进度面板：先汇总相关对话状态，再让用户选择读历史 / 接着干 / 开副本。
 *
 * 读历史只读，不向来源对话发送；接着干与开副本都会在本地先弹一次确认，
 * 取消就不调用后端。
 */
@Composable
fun V2ProjectAssistantScreen(
    state: V2ProjectAssistantState,
    extraTop: Dp = 0.dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReadHistory: (String) -> Unit,
    onCloseHistory: () -> Unit,
    onContinue: (sourceTaskId: String, content: String) -> Unit,
    onFork: (sourceTaskId: String, name: String?) -> Unit,
    onDismissMessages: () -> Unit = {},
) {
    var continueTarget by remember { mutableStateOf<AssistantProgressItem?>(null) }
    var continueText by remember { mutableStateOf("") }
    var forkTarget by remember { mutableStateOf<AssistantProgressItem?>(null) }
    var forkName by remember { mutableStateOf("") }

    LaunchedEffect(state.error, state.notice) {
        if (state.error != null || state.notice != null) {
            delay(if (state.error != null) 5000L else 2600L)
            onDismissMessages()
        }
    }

    val progress = state.progress
    val conversations = state.history?.conversations ?: progress?.conversations.orEmpty()
    val busy = state.busyAction != null

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp + extraTop),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) {
                    RoundIconButton(
                        Icons.Outlined.ArrowBack,
                        onBack,
                        size = 34.dp,
                        iconSize = 18.dp,
                        contentDescription = "返回",
                    )
                } else {
                    Spacer(Modifier.width(34.dp))
                }
                Column(
                    Modifier.weight(1f).padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Label(
                        state.assistantName.ifBlank { "项目进度" },
                        16f,
                        V2Colors.ink(.95f),
                        FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Label("只读汇总相关对话", 11f, V2Colors.ink(.40f), maxLines = 1)
                }
                RoundIconButton(
                    Icons.Outlined.Refresh,
                    onRefresh,
                    size = 34.dp,
                    iconSize = 18.dp,
                    contentDescription = "刷新",
                )
            }

            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Label("进度汇总", 13f, V2Colors.ink(.88f), FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if (progress == null) {
                            Label(
                                if (state.loading) "正在只读汇总…" else "还没有进度数据",
                                12f,
                                V2Colors.ink(.45f),
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                CountCell("进行中", progress.counts["running"] ?: 0, V2Colors.cyan700)
                                CountCell(
                                    "可能已停住",
                                    progress.counts["possibly_stalled"] ?: 0,
                                    V2Colors.amber700,
                                )
                                CountCell("失败", progress.counts["failed"] ?: 0, V2Colors.rose700)
                                CountCell(
                                    "已完成",
                                    progress.counts["completed"] ?: 0,
                                    V2Colors.emerald700,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Label(
                                "超过 ${progress.stalledAfterHours} 小时没有新消息，就算「可能已停住」。",
                                11f,
                                V2Colors.ink(.42f),
                            )
                            Label(
                                "这里只读服务端已有记录，不会替你开电脑，也不会派子任务。",
                                11f,
                                V2Colors.ink(.42f),
                            )
                        }
                    }
                }

                if (conversations.isEmpty()) {
                    WhiteCard(Modifier.fillMaxWidth()) {
                        Label(
                            "这个项目助理还没有关联已有对话。可以先在创建时关联，或回到主助理继续聊。",
                            12f,
                            V2Colors.ink(.45f),
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                conversations.forEach { item ->
                    WhiteCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Label(
                                    item.title.ifBlank { "未命名对话" },
                                    13f,
                                    V2Colors.ink(.90f),
                                    FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Label(item.bucketLabel, 11f, bucketTone(item.bucket), mono = true)
                            }
                            Spacer(Modifier.height(3.dp))
                            Label(
                                "最后更新 ${item.idleHours} 小时前" +
                                    if (item.lastMessage.isNotBlank()) " · ${item.lastMessage}" else "",
                                11f,
                                V2Colors.ink(.42f),
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionChip("读历史", enabled = !busy) {
                                    onReadHistory(item.taskId)
                                }
                                ActionChip(
                                    "接着干",
                                    // 仍在进行中 / 可能已停住的对话先别叠第二轮，等这一轮收尾
                                    enabled = !busy &&
                                        item.bucket != "running" &&
                                        item.bucket != "possibly_stalled",
                                ) {
                                    continueTarget = item
                                    continueText = ""
                                }
                                ActionChip("开副本", enabled = !busy) {
                                    forkTarget = item
                                    forkName = "${item.title.ifBlank { "对话" }}（副本）"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val continueItem = continueTarget
    if (continueItem != null) {
        InputConfirmDialog(
            title = "接着干",
            hint = "要向「${continueItem.title}」发送什么？确认后才会发送并继续执行。",
            value = continueText,
            onValueChange = { continueText = it },
            confirmLabel = "确认发送",
            busy = busy,
            onDismiss = { if (!busy) continueTarget = null },
            onConfirm = {
                val text = continueText.trim()
                if (text.isNotEmpty()) {
                    onContinue(continueItem.taskId, text)
                    continueTarget = null
                }
            },
        )
    }

    val targetFork = forkTarget
    if (targetFork != null) {
        InputConfirmDialog(
            title = "开副本",
            hint = "确认后新建一条独立常驻对话，带上原对话的思路和上下文；" +
                "原文一条消息都不改。",
            value = forkName,
            onValueChange = { forkName = it },
            confirmLabel = "确认开副本",
            busy = busy,
            onDismiss = { if (!busy) forkTarget = null },
            onConfirm = {
                onFork(targetFork.taskId, forkName.trim().ifBlank { null })
                forkTarget = null
            },
        )
    }

    state.historyDetail?.let { detail ->
        HistoryDialog(detail = detail, onClose = onCloseHistory)
    }
}

@Composable
private fun CountCell(label: String, count: Int, tone: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label(count.toString(), 18f, tone, FontWeight.SemiBold)
        Label(label, 10.5f, V2Colors.ink(.45f), maxLines = 1)
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(V2Colors.ink(if (enabled) .06f else .03f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Label(
            label,
            12f,
            if (enabled) V2Colors.ink(.80f) else V2Colors.ink(.25f),
        )
    }
}

@Composable
private fun InputConfirmDialog(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Label(title, 17f, V2Colors.ink(.94f), FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Label(hint, 11.5f, V2Colors.ink(.45f))
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(V2Colors.ink(.04f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isEmpty()) {
                        Label("写一句要接着做的事", 13f, V2Colors.ink(.28f))
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(color = V2Colors.ink(.92f), fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy, onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) { Label("取消", 13f, V2Colors.ink(.55f)) }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(V2Colors.accent)
                            .clickable(enabled = !busy && value.isNotBlank(), onClick = onConfirm)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Label(
                            if (busy) "处理中…" else confirmLabel,
                            13f,
                            V2Colors.actionInk,
                            FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(detail: AssistantHistoryDetail, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("读历史", 17f, V2Colors.ink(.94f), FontWeight.SemiBold)
                        Label(
                            detail.task.displayName,
                            11f,
                            V2Colors.ink(.42f),
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Label("关闭", 12f, V2Colors.ink(.62f)) }
                }
                Spacer(Modifier.height(6.dp))
                Label("只读：不会向这段对话发送消息。", 11f, V2Colors.ink(.45f))
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (detail.messages.isEmpty()) {
                        Label("这段对话还没有消息。", 12f, V2Colors.ink(.45f))
                    }
                    detail.messages.forEach { message ->
                        val who = if (message.role == "user") "用户" else "助理/Agent"
                        Column {
                            Label(who, 10.5f, V2Colors.ink(.40f), mono = true)
                            Spacer(Modifier.height(2.dp))
                            Label(
                                message.content.ifBlank { "（无正文）" },
                                12.5f,
                                V2Colors.ink(.80f),
                            )
                        }
                    }
                    if (detail.hasMore) {
                        Label("只显示最近一段消息。", 11f, V2Colors.ink(.40f))
                    }
                }
            }
        }
    }
}
