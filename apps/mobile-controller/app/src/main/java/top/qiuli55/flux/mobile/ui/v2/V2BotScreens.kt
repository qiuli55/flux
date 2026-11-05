package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.data.Bot
import top.qiuli55.flux.mobile.data.BotNotification
import top.qiuli55.flux.mobile.data.BotRoutine
import top.qiuli55.flux.mobile.data.Task

/**
 * 常驻 Bot 详情页：状态 / 定时 / 例程 / 最近开工 / 失败通知。
 *
 * 数据由 [V2BotDetailState] 一次性灌入，页面只负责展示与转发动作；
 * 建例程用一个内嵌 Sheet，不跳转新页面。
 */
@Composable
fun V2BotDetailScreen(
    detail: V2BotDetailState,
    extraTop: Dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRunNow: () -> Unit,
    onToggleStatus: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onToggleRoutine: (BotRoutine, Boolean) -> Unit,
    onCreateRoutine: (
        name: String,
        instruction: String,
        schedule: String?,
        onResult: (String) -> Unit,
    ) -> Unit,
    onDeleteRoutine: (BotRoutine) -> Unit,
    onUpdateConfig: (schedule: String?, notifyOnFailure: Boolean?, tokenBudget: Int?) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    val bot = detail.bot
    var routineOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
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
                BotAvatar(
                    shape = bot?.avatarShape,
                    color = bot?.avatarColor,
                    size = 38.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(
                        bot?.name ?: "Bot",
                        15f,
                        V2Colors.ink(.95f),
                        FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Label(
                        bot?.let(::botSubtitle) ?: "正在读取…",
                        10.5f,
                        V2Colors.ink(.40f),
                        maxLines = 1,
                    )
                }
                if (bot != null) {
                    V2Chip(
                        text = if (bot.isPaused) "已暂停" else "运行中",
                        color = if (bot.isPaused) V2Colors.amber else V2Colors.emerald,
                    )
                }
            }

            ThinDivider(color = V2Colors.lineSoft)

            when {
                bot == null -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (detail.loading) {
                            LiveDot(V2Colors.accent)
                            Spacer(Modifier.height(10.dp))
                            Label("正在读取 Bot…", 12f, V2Colors.ink(.40f))
                        } else {
                            Label(detail.error ?: "Bot 不存在", 12f, V2Colors.ink(.45f))
                            Spacer(Modifier.height(12.dp))
                            GhostButton(text = "重试", onClick = onRefresh)
                        }
                    }
                }

                else -> V2PullRefresh(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRefresh = {
                        onRefresh()
                        delay(650)
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            BotOverviewCard(
                                bot = bot,
                                error = detail.error,
                                notice = detail.notice,
                            )
                        }

                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PrimaryButton(
                                    text = if (detail.busy) "开工中…" else "开工一次",
                                    icon = Icons.Outlined.PlayArrow,
                                    enabled = !detail.busy,
                                    onClick = onRunNow,
                                    modifier = Modifier.weight(1f),
                                )
                                GhostButton(
                                    text = if (bot.isPaused) "恢复定时" else "暂停定时",
                                    enabled = !detail.busy,
                                    onClick = {
                                        onToggleStatus(if (bot.isPaused) "active" else "paused")
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        item { BotSectionTitle("定时") }
                        item {
                            BotScheduleCard(
                                bot = bot,
                                busy = detail.busy,
                                onSave = { schedule -> onUpdateConfig(schedule, null, null) },
                                onToggleNotify = { onUpdateConfig(null, it, null) },
                            )
                        }

                        item {
                            BotSectionTitle(
                                text = "例程",
                                trailing = "新建",
                                onTrailing = { routineOpen = true },
                            )
                        }
                        if (detail.routines.isEmpty()) {
                            item { BotEmptyHint("还没有例程。加上一条，Bot 会按 cron 自己开工。") }
                        } else {
                            items(detail.routines, key = { it.id }) { routine ->
                                BotRoutineRow(
                                    routine = routine,
                                    onToggle = { onToggleRoutine(routine, it) },
                                    onDelete = { onDeleteRoutine(routine) },
                                )
                            }
                        }

                        item { BotSectionTitle("最近开工") }
                        if (detail.tasks.isEmpty()) {
                            item { BotEmptyHint("还没有开工记录。") }
                        } else {
                            items(detail.tasks, key = { it.id }) { task ->
                                BotTaskRow(task = task, onClick = { onOpenTask(task.id) })
                            }
                        }

                        item {
                            val unread = detail.notifications.count { it.isUnread }
                            BotSectionTitle(
                                text = "失败通知",
                                trailing = if (unread > 0) "全部已读" else null,
                                onTrailing = onMarkAllRead,
                            )
                        }
                        if (detail.notifications.isEmpty()) {
                            item { BotEmptyHint("没有通知，说明一直很顺利。") }
                        } else {
                            items(detail.notifications, key = { it.id }) { notification ->
                                BotNotificationRow(
                                    notification = notification,
                                    onClick = { onMarkNotificationRead(notification.id) },
                                )
                            }
                        }

                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }

        if (routineOpen) {
            BotRoutineSheet(
                onCreate = { name, instruction, schedule, done ->
                    onCreateRoutine(name, instruction, schedule) { error ->
                        done(error)
                        if (error.isEmpty()) routineOpen = false
                    }
                },
                onDismiss = { routineOpen = false },
            )
        }
    }
}

private fun botSubtitle(bot: Bot): String = when {
    bot.isPaused -> bot.pausedReason?.takeIf { it.isNotBlank() }?.let { "已暂停 · $it" } ?: "已暂停"
    bot.schedule.isNullOrBlank() -> "手动开工"
    else -> "定时 ${bot.schedule}"
}

@Composable
private fun BotOverviewCard(bot: Bot, error: String?, notice: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(V2Colors.surface(.60f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BotStat("累计开工", "${bot.runsTotal}", Modifier.weight(1f))
            BotStat("失败", "${bot.runsFailed}", Modifier.weight(1f), tone = V2Colors.rose)
            BotStat(
                text = "今日 tok",
                value = if (bot.tokenBudget != null) {
                    "${bot.tokensUsedToday}/${bot.tokenBudget}"
                } else {
                    "${bot.tokensUsedToday}"
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (bot.consecutiveFailures > 0) {
            Spacer(Modifier.height(12.dp))
            Label(
                "连续失败 ${bot.consecutiveFailures}/${bot.failureThreshold} 次（达到阈值会自动暂停）",
                10.5f,
                V2Colors.rose,
            )
        }

        bot.pausedReason?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Label("暂停原因：$it", 10.5f, V2Colors.amber, maxLines = 3)
        }

        bot.lastError?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.rose.copy(alpha = .08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Label("最近一次错误", 9.5f, V2Colors.rose, FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Label(it, 10.5f, V2Colors.ink(.55f), maxLines = 5)
            }
        }

        val flash = error ?: notice
        if (flash != null) {
            Spacer(Modifier.height(10.dp))
            Label(
                flash,
                10.5f,
                if (error != null) V2Colors.rose else V2Colors.emerald700,
            )
        }
    }
}

@Composable
private fun BotStat(text: String, value: String, modifier: Modifier = Modifier, tone: Color? = null) {
    Column(modifier) {
        Label(value, 17f, tone ?: V2Colors.ink(.90f), FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Label(text, 9.5f, V2Colors.ink(.36f), maxLines = 1)
    }
}

@Composable
private fun BotSectionTitle(text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 11f, V2Colors.ink(.45f), FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(V2Colors.ink(.06f))
                    .v2Pressable(pressScale = .94f, onClick = onTrailing)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Label(trailing, 10f, V2Colors.ink(.55f), FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BotScheduleCard(
    bot: Bot,
    busy: Boolean,
    onSave: (String) -> Unit,
    onToggleNotify: (Boolean) -> Unit,
) {
    var draft by remember(bot.id, bot.schedule) { mutableStateOf(bot.schedule.orEmpty()) }
    val dirty = draft.trim() != bot.schedule.orEmpty()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BotTextField(
            value = draft,
            placeholder = "cron，例如 0 9 * * *（留空 = 不自动开工）",
            onValueChange = { draft = it },
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                if (bot.nextRunAt.isNullOrBlank()) {
                    "暂无下次开工时间"
                } else {
                    "下次开工 ${botMoment(bot.nextRunAt)}"
                },
                9.5f,
                V2Colors.ink(.32f),
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(V2Colors.accent)
                        .v2Pressable(enabled = !busy, pressScale = .94f) {
                            onSave(draft.trim())
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Label("保存", 10f, V2Colors.actionInk, FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("失败时通知", 12.5f, V2Colors.ink(.82f), FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Label("开工失败会写一条通知", 9.5f, V2Colors.ink(.34f))
            }
            LightSwitch(
                checked = bot.notifyOnFailure,
                onChange = { onToggleNotify(it) },
            )
        }
    }
}

@Composable
private fun BotRoutineRow(
    routine: BotRoutine,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val meta = buildList {
        routine.schedule?.takeIf { it.isNotBlank() }?.let { add(it) }
        routine.nextRunAt?.takeIf { it.isNotBlank() }?.let { add("下次 ${botMoment(it)}") }
    }.joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(routine.name, 13f, V2Colors.ink(.85f), FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Label(
                routine.instruction.take(48).ifBlank { meta },
                10f,
                V2Colors.ink(.36f),
                maxLines = 1,
            )
            if (meta.isNotBlank() && routine.instruction.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Label(meta, 9.5f, V2Colors.ink(.28f), maxLines = 1)
            }
        }
        LightSwitch(checked = routine.enabled, onChange = onToggle)
        Spacer(Modifier.width(4.dp))
        RoundIconButton(
            Icons.Outlined.DeleteOutline,
            onDelete,
            size = 28.dp,
            iconSize = 15.dp,
            tint = V2Colors.ink(.40f),
            background = Color.Transparent,
            contentDescription = "删除例程",
        )
    }
}

@Composable
private fun BotTaskRow(task: Task, onClick: () -> Unit) {
    val (label, color) = when (task.status.toV2TaskStatus()) {
        V2TaskStatus.RUNNING -> "执行中" to V2Colors.accent
        V2TaskStatus.WAITING -> "待确认" to V2Colors.amber
        V2TaskStatus.QUEUED -> "排队中" to V2Colors.ink(.45f)
        V2TaskStatus.DONE -> "完成" to V2Colors.emerald
        V2TaskStatus.FAILED -> "失败" to V2Colors.rose
        V2TaskStatus.CANCELED -> "已取消" to V2Colors.ink(.40f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(task.displayName, 12.5f, V2Colors.ink(.84f), FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Label(
                task.description.lineSequence().firstOrNull()?.take(60).orEmpty(),
                10f,
                V2Colors.ink(.36f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Label(label, 9.5f, color, FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Label(relativeTime(task.createdAt), 9.5f, V2Colors.ink(.28f))
        }
    }
}

@Composable
private fun BotNotificationRow(notification: BotNotification, onClick: () -> Unit) {
    val tone = when (notification.level) {
        "error" -> V2Colors.rose
        "warning" -> V2Colors.amber
        else -> V2Colors.ink(.40f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .v2Pressable(pressScale = .985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .background(tone, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                    notification.title.ifBlank { "开工失败" },
                    12.5f,
                    V2Colors.ink(if (notification.isUnread) .88f else .60f),
                    FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notification.isUnread) {
                    Spacer(Modifier.width(6.dp))
                    Label("未读", 9f, V2Colors.rose, FontWeight.Medium)
                }
            }
            if (notification.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Label(notification.body, 10.5f, V2Colors.ink(.42f), maxLines = 4)
            }
            Spacer(Modifier.height(3.dp))
            Label(relativeTime(notification.createdAt), 9.5f, V2Colors.ink(.26f))
        }
    }
}

@Composable
private fun BotEmptyHint(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Label(text, 10.5f, V2Colors.ink(.34f))
    }
}

/** 例程新建面板：名称 + 指令 + 可选 cron。 */
@Composable
private fun BotRoutineSheet(
    onCreate: (
        name: String,
        instruction: String,
        schedule: String?,
        onResult: (String) -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        SheetHeader(
            title = "新建例程",
            subtitle = "一条例程 = 一句指令 + 一个 cron；到点 Bot 自己开工",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Label("名称", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            BotTextField(
                value = name,
                placeholder = "例如「每日整理收件箱」",
                onValueChange = {
                    name = it
                    error = null
                },
                maxLines = 1,
            )

            Spacer(Modifier.height(16.dp))
            Label("指令", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            BotTextField(
                value = instruction,
                placeholder = "开工时交给 Agent 的完整指令",
                onValueChange = {
                    instruction = it
                    error = null
                },
                maxLines = 5,
            )

            Spacer(Modifier.height(16.dp))
            Label("定时（可选）", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            BotTextField(
                value = schedule,
                placeholder = "cron，例如 0 9 * * *；留空 = 手动触发",
                onValueChange = { schedule = it },
                maxLines = 1,
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Label(error.orEmpty(), 10.5f, V2Colors.rose)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (submitting) "创建中…" else "创建例程",
                enabled = !submitting && name.isNotBlank() && instruction.isNotBlank(),
                onClick = {
                    submitting = true
                    error = null
                    onCreate(name, instruction, schedule.trim().ifBlank { null }) { message ->
                        submitting = false
                        error = message.ifBlank { null }
                    }
                },
            )
        }
    }
}

@Composable
private fun BotTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    maxLines: Int = 1,
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
            Label(placeholder, 12.5f, V2Colors.ink(.30f))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 13.sp, color = V2Colors.ink(.88f)),
            cursorBrush = SolidColor(V2Colors.accent),
            maxLines = maxLines,
        )
    }
}

/** Bot 详情里的绝对时间（下次开工 / 通知时间），只用于紧凑展示。 */
private fun botMoment(raw: String?): String {
    val epoch = parseEpochSeconds(raw) ?: return "—"
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epoch * 1000))
}
