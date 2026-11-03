package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.data.SupervisorReport
import top.qiuli55.flux.mobile.data.SupervisorTicket
import top.qiuli55.flux.mobile.data.SupervisorTimelineItem
import top.qiuli55.flux.mobile.data.SupervisorTrigger

/** 工单看板筛选。 */
private enum class AssistantTicketFilter(val label: String) {
    ALL("全部"),
    TODO("待派发"),
    DOING("进行中"),
    REVIEW("待验收"),
    ENDED("已结束"),
}

private fun ticketFilterOf(stage: String): AssistantTicketFilter = when (stage) {
    "intake", "planned" -> AssistantTicketFilter.TODO
    "running", "waiting" -> AssistantTicketFilter.DOING
    "reviewing" -> AssistantTicketFilter.REVIEW
    "done", "stopped" -> AssistantTicketFilter.ENDED
    else -> AssistantTicketFilter.ALL
}

private fun stageTone(stage: String): Color = when (stage) {
    "done" -> V2Colors.emerald700
    "stopped" -> V2Colors.rose700
    "running" -> V2Colors.cyan700
    "waiting" -> V2Colors.amber700
    "reviewing" -> V2Colors.violet
    "intake", "planned" -> V2Colors.indigo
    else -> V2Colors.ink(.5f)
}

private fun decisionTone(decision: String): Color = when (decision) {
    "accept" -> V2Colors.emerald700
    "stop" -> V2Colors.rose700
    "ignore" -> V2Colors.ink(.45f)
    "unavailable" -> V2Colors.amber700
    else -> V2Colors.ink(.4f)
}

private fun reportTone(status: String): Color = when (status) {
    "completed" -> V2Colors.emerald700
    "failed", "timed_out" -> V2Colors.rose700
    "blocked" -> V2Colors.rose700
    "needs_input" -> V2Colors.amber700
    else -> V2Colors.ink(.5f)
}

private fun timelineTone(kind: String): Color = when (kind) {
    "trigger" -> V2Colors.indigo
    "ticket" -> V2Colors.cyan700
    "report" -> V2Colors.emerald700
    else -> V2Colors.ink(.45f)
}

/** 助理页：观察事件 → 判定 → 拆工单 → 派发给子 Agent。 */
@Composable
fun V2AssistantScreen(
    state: V2AssistantState,
    extraTop: Dp = 0.dp,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSaveSettings: (String, String, String, Int, Int, Int) -> Unit,
    onAssess: (String) -> Unit,
    onPlan: (String, Boolean) -> Unit,
    onProcess: () -> Unit,
    onWatchdog: () -> Unit,
    onDispatch: (String) -> Unit,
    onRetry: (String) -> Unit,
    onStop: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onCloseDetail: () -> Unit,
    onDismissMessages: () -> Unit = {},
) {
    var filter by remember { mutableStateOf(AssistantTicketFilter.ALL) }

    LaunchedEffect(state.error, state.notice) {
        if (state.error != null || state.notice != null) {
            delay(if (state.error != null) 5000L else 2600L)
            onDismissMessages()
        }
    }

    val counts = state.counts
    val todo = (counts["intake"] ?: 0) + (counts["planned"] ?: 0)
    val doing = (counts["running"] ?: 0) + (counts["waiting"] ?: 0)
    val review = counts["reviewing"] ?: 0
    val ended = (counts["done"] ?: 0) + (counts["stopped"] ?: 0)
    val visibleTickets = remember(state.tickets, filter) {
        state.tickets
            .filter { filter == AssistantTicketFilter.ALL || ticketFilterOf(it.stage) == filter }
            .sortedBy { it.sequence }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 10.dp + extraTop)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
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
                Column(Modifier.weight(1f)) {
                    Label("助理", 23f, V2Colors.ink(.95f), FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Label(
                        "观察 · 判定 · 拆单 · 派活",
                        11f,
                        V2Colors.ink(.38f),
                    )
                }
                RoundIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    onClick = onProcess,
                    size = 32.dp,
                    iconSize = 15.dp,
                    background = V2Colors.surface(.55f),
                    tint = V2Colors.cyan700.copy(alpha = .85f),
                    contentDescription = "推进队列",
                )
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    icon = Icons.Outlined.Autorenew,
                    onClick = onWatchdog,
                    size = 32.dp,
                    iconSize = 15.dp,
                    background = V2Colors.surface(.55f),
                    tint = V2Colors.amber700.copy(alpha = .9f),
                    contentDescription = "超时回收",
                )
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    icon = Icons.Outlined.Refresh,
                    onClick = onRefresh,
                    size = 32.dp,
                    iconSize = 15.dp,
                    contentDescription = "刷新",
                )
            }

            Spacer(Modifier.height(12.dp))

            val flash = state.error ?: state.notice
            if (flash != null) {
                AssistantFlash(state.error != null, flash)
                Spacer(Modifier.height(8.dp))
            }

            if (state.loading && !state.hasData) {
                Spacer(Modifier.height(28.dp))
                Label("正在连接助理…", 11f, V2Colors.ink(.35f))
            } else if (!state.loading && !state.hasData) {
                Spacer(Modifier.height(28.dp))
                Label("助理还没有数据", 12f, V2Colors.ink(.45f))
                Spacer(Modifier.height(4.dp))
                Label("确认后端已开启 Supervisor，并接入了平台事件。", 10.5f, V2Colors.ink(.32f))
            } else {
                V2PullRefresh(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRefresh = {
                        onRefresh()
                        delay(650)
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item(key = "settings") {
                            AssistantSettingsCard(
                                state = state,
                                onSave = onSaveSettings,
                            )
                        }
                        item(key = "stats") {
                            AssistantStats(
                                todo = todo,
                                doing = doing,
                                review = review,
                                ended = ended,
                                pendingOutbox = state.pendingOutbox,
                            )
                        }

                        item(key = "obs-title") {
                            SectionTitle("观察事件", "${state.observations.size} 条")
                        }
                        if (state.observations.isEmpty()) {
                            item(key = "obs-empty") { EmptyHint("还没有观察事件；接入平台事件后会出现在这里") }
                        } else {
                            items(state.observations, key = { "obs-${it.id}" }) { trigger ->
                                ObservationCard(
                                    trigger = trigger,
                                    busy = state.busyAction != null,
                                    onAssess = { onAssess(trigger.id) },
                                    onPlan = { dispatch -> onPlan(trigger.id, dispatch) },
                                )
                            }
                        }

                        item(key = "tickets-title") {
                            SectionTitle("工单看板", "${state.tickets.size} 张")
                        }
                        item(key = "tickets-filter") {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AssistantTicketFilter.entries.forEach { item ->
                                    AssistantFilterChip(
                                        label = item.label,
                                        count = when (item) {
                                            AssistantTicketFilter.ALL -> state.tickets.size
                                            AssistantTicketFilter.TODO -> todo
                                            AssistantTicketFilter.DOING -> doing
                                            AssistantTicketFilter.REVIEW -> review
                                            AssistantTicketFilter.ENDED -> ended
                                        },
                                        selected = filter == item,
                                        onClick = { filter = item },
                                    )
                                }
                            }
                        }
                        if (visibleTickets.isEmpty()) {
                            item(key = "tickets-empty") { EmptyHint("这个分区还没有工单") }
                        } else {
                            items(visibleTickets, key = { "ticket-${it.id}" }) { ticket ->
                                TicketCard(
                                    ticket = ticket,
                                    busy = state.busyAction != null,
                                    onOpen = { onOpenDetail(ticket.id) },
                                    onDispatch = { onDispatch(ticket.id) },
                                    onRetry = { onRetry(ticket.id) },
                                    onStop = { onStop(ticket.id) },
                                )
                            }
                        }

                        item(key = "reports-title") {
                            SectionTitle("最近报告", "${state.reports.size} 条")
                        }
                        if (state.reports.isEmpty()) {
                            item(key = "reports-empty") { EmptyHint("子 Agent 还没交回报告") }
                        } else {
                            items(state.reports.take(10), key = { "report-${it.id}" }) { report ->
                                ReportCard(report)
                            }
                        }

                        item(key = "timeline-title") { SectionTitle("最近动态", "") }
                        if (state.timeline.isEmpty()) {
                            item(key = "timeline-empty") { EmptyHint("暂无动态") }
                        } else {
                            items(state.timeline.take(20), key = { "tl-${it.kind}-${it.id}-${it.at}" }) { item ->
                                TimelineRow(item)
                            }
                        }
                    }
                }
            }
        }

        val detail = state.detail
        if (detail != null) {
            AssistantDetailSheet(
                ticket = detail,
                loading = state.detailLoading,
                busy = state.busyAction != null,
                onDismiss = onCloseDetail,
                onDispatch = { onDispatch(detail.id) },
                onRetry = { onRetry(detail.id) },
                onStop = { onStop(detail.id) },
            )
        }
    }
}

@Composable
private fun AssistantFlash(error: Boolean, text: String) {
    val tone = if (error) V2Colors.rose700 else V2Colors.emerald700
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tone.copy(alpha = .10f))
            .border(1.dp, tone.copy(alpha = .22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 10.5f, tone, maxLines = 2)
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, 13f, V2Colors.ink(.78f), FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        if (meta.isNotBlank()) Label(meta, 10f, V2Colors.ink(.34f))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Label(text, 10.5f, V2Colors.ink(.32f), modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun AssistantSettingsCard(
    state: V2AssistantState,
    onSave: (String, String, String, Int, Int, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var mode by remember(state.settings.acceptMode) { mutableStateOf(state.settings.acceptMode) }
    var provider by remember(state.settings.modelProvider) { mutableStateOf(state.settings.modelProvider) }
    var modelName by remember(state.settings.modelName) { mutableStateOf(state.settings.modelName) }
    var maxTickets by remember(state.settings.maxTicketsPerThread) {
        mutableStateOf(state.settings.maxTicketsPerThread.toString())
    }
    var maxDepth by remember(state.settings.maxTicketDepth) {
        mutableStateOf(state.settings.maxTicketDepth.toString())
    }
    var maxRuns by remember(state.settings.maxConcurrentRuns) {
        mutableStateOf(state.settings.maxConcurrentRuns.toString())
    }

    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .v2Pressable(pressScale = .99f) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("运行设置", 12.5f, V2Colors.ink(.8f), FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                V2Chip(
                    text = acceptModeLabel(state.settings.acceptMode),
                    color = when (state.settings.acceptMode) {
                        "auto" -> V2Colors.emerald700
                        "off" -> V2Colors.ink(.45f)
                        else -> V2Colors.amber700
                    },
                )
                Spacer(Modifier.weight(1f))
                Label(if (expanded) "收起" else "调整", 10.5f, V2Colors.cyan700)
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("off" to "关闭", "observe" to "只观察", "auto" to "自动执行").forEach { (value, label) ->
                        AssistantModeChip(label, mode == value) { mode = value }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Label("模型提供方（留空用默认）", 10f, V2Colors.ink(.42f))
                Spacer(Modifier.height(4.dp))
                AssistantField(provider, { provider = it }, "如 anthropic / openai")
                Spacer(Modifier.height(8.dp))
                Label("模型名称", 10f, V2Colors.ink(.42f))
                Spacer(Modifier.height(4.dp))
                AssistantField(modelName, { modelName = it }, "如 claude-sonnet-4")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("每线程工单", 9.5f, V2Colors.ink(.4f))
                        Spacer(Modifier.height(4.dp))
                        AssistantField(maxTickets, { maxTickets = it }, "8", numeric = true)
                    }
                    Column(Modifier.weight(1f)) {
                        Label("工单层级", 9.5f, V2Colors.ink(.4f))
                        Spacer(Modifier.height(4.dp))
                        AssistantField(maxDepth, { maxDepth = it }, "3", numeric = true)
                    }
                    Column(Modifier.weight(1f)) {
                        Label("并发运行", 9.5f, V2Colors.ink(.4f))
                        Spacer(Modifier.height(4.dp))
                        AssistantField(maxRuns, { maxRuns = it }, "2", numeric = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "保存设置",
                    onClick = {
                        onSave(
                            mode,
                            provider,
                            modelName,
                            maxTickets.toIntOrNull() ?: state.settings.maxTicketsPerThread,
                            maxDepth.toIntOrNull() ?: state.settings.maxTicketDepth,
                            maxRuns.toIntOrNull() ?: state.settings.maxConcurrentRuns,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.busyAction == null,
                )
            }
        }
    }
}

@Composable
private fun AssistantModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .16f) else V2Colors.surface(.55f))
            .border(
                1.dp,
                if (selected) V2Colors.cyan500.copy(alpha = .4f) else V2Colors.ink(.08f),
                RoundedCornerShape(999.dp),
            )
            .v2Pressable(pressScale = .97f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(label, 10.5f, if (selected) V2Colors.cyan800 else V2Colors.ink(.5f), FontWeight.Medium)
    }
}

@Composable
private fun AssistantFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .14f) else V2Colors.surface(.55f))
            .border(
                1.dp,
                if (selected) V2Colors.cyan500.copy(alpha = .36f) else V2Colors.ink(.08f),
                RoundedCornerShape(999.dp),
            )
            .v2Pressable(pressScale = .97f, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, 10.5f, if (selected) V2Colors.cyan800 else V2Colors.ink(.5f), FontWeight.Medium)
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Label("$count", 9.5f, if (selected) V2Colors.cyan700 else V2Colors.ink(.32f))
        }
    }
}

@Composable
private fun AssistantField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(V2Colors.surface(.6f), RoundedCornerShape(10.dp))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) {
            Label(placeholder, 11f, V2Colors.ink(.26f))
        }
        BasicTextField(
            value = value,
            onValueChange = { next ->
                if (!numeric || next.all { it.isDigit() }) onChange(next)
            },
            textStyle = TextStyle(color = V2Colors.ink(.88f), fontSize = 12.sp),
            cursorBrush = SolidColor(V2Colors.accent),
            singleLine = true,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AssistantStats(
    todo: Int,
    doing: Int,
    review: Int,
    ended: Int,
    pendingOutbox: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantStat("待派发", todo, V2Colors.indigo, Modifier.weight(1f))
            AssistantStat("进行中", doing, V2Colors.cyan700, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantStat("待验收", review, V2Colors.violet, Modifier.weight(1f))
            AssistantStat("已结束", ended, V2Colors.emerald700, Modifier.weight(1f))
        }
        if (pendingOutbox > 0) {
            Label("事件队列还有 $pendingOutbox 条待推进，点右上角 ▶ 处理", 9.5f, V2Colors.amber700)
        }
    }
}

@Composable
private fun AssistantStat(label: String, value: Int, tone: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.surface(.55f))
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Label("$value", 19f, tone, FontWeight.SemiBold)
        Spacer(Modifier.height(1.dp))
        Label(label, 9.5f, V2Colors.ink(.42f))
    }
}

@Composable
private fun ObservationCard(
    trigger: SupervisorTrigger,
    busy: Boolean,
    onAssess: () -> Unit,
    onPlan: (Boolean) -> Unit,
) {
    val eval = trigger.eval
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                V2Chip(
                    text = trigger.status.ifBlank { "received" },
                    color = if (eval == null) V2Colors.ink(.5f) else decisionTone(eval.decision),
                )
                Spacer(Modifier.width(6.dp))
                V2Chip(text = eval?.let { decisionLabel(it.decision) } ?: "未判定", color = decisionTone(eval?.decision ?: ""))
                Spacer(Modifier.weight(1f))
                Label(relativeTime(trigger.receivedAt ?: trigger.createdAt), 9.5f, V2Colors.ink(.3f))
            }
            Spacer(Modifier.height(8.dp))
            Label(
                trigger.content.ifBlank { "（无正文）" },
                12f,
                V2Colors.ink(.85f),
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                listOf(trigger.sourceKey, trigger.actorPlatform, trigger.actorId)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                9.5f,
                V2Colors.ink(.32f),
                mono = true,
                maxLines = 1,
            )
            if (eval != null && eval.reason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Label("判定依据：${eval.reason}", 10f, decisionTone(eval.decision), maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistantAction("重判", V2Colors.cyan700, enabled = !busy, onClick = onAssess)
                AssistantAction("拆单", V2Colors.violet, enabled = !busy) { onPlan(false) }
                AssistantAction("拆并派", V2Colors.emerald700, enabled = !busy) { onPlan(true) }
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: SupervisorTicket,
    busy: Boolean,
    onOpen: () -> Unit,
    onDispatch: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    val tone = stageTone(ticket.stage)
    val canDispatch = ticket.stage == "intake" || ticket.stage == "planned"
    val canRetry = ticket.stage == "stopped"
    val canStop = ticket.stage == "running" || ticket.stage == "waiting" || ticket.stage == "reviewing"
    val summary = ticket.report?.summary.orEmpty()

    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("#${ticket.sequence}", 10f, V2Colors.ink(.32f), mono = true)
                Spacer(Modifier.width(6.dp))
                Label(ticket.title, 12.5f, V2Colors.ink(.86f), FontWeight.SemiBold, Modifier.weight(1f), maxLines = 2)
                Spacer(Modifier.width(6.dp))
                V2Chip(text = ticketStageLabel(ticket.stage), color = tone)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                    ticket.role.ifBlank { "未指定角色" },
                    9.5f,
                    V2Colors.ink(.35f),
                    mono = true,
                )
                if (ticket.degraded) {
                    Spacer(Modifier.width(6.dp))
                    V2Chip(text = "降级", color = V2Colors.amber700)
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Label(summary, 10.5f, V2Colors.ink(.55f), maxLines = 3)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistantAction("详情", V2Colors.ink(.55f), enabled = true, onClick = onOpen)
                if (canDispatch) {
                    AssistantAction("派发", V2Colors.emerald700, enabled = !busy, onClick = onDispatch)
                }
                if (canRetry) {
                    AssistantAction("重试", V2Colors.cyan700, enabled = !busy, onClick = onRetry)
                }
                if (canStop) {
                    AssistantAction("停止", V2Colors.rose700, enabled = !busy, onClick = onStop)
                }
                Spacer(Modifier.weight(1f))
                Label(relativeTime(ticket.updatedAt), 9f, V2Colors.ink(.28f))
            }
        }
    }
}

@Composable
private fun AssistantAction(
    label: String,
    tone: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = if (enabled) .10f else .04f))
            .border(1.dp, tone.copy(alpha = if (enabled) .22f else .08f), RoundedCornerShape(999.dp))
            .v2Pressable(enabled = enabled, pressScale = .96f, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Label(label, 10f, if (enabled) tone else V2Colors.ink(.28f), FontWeight.Medium)
    }
}

@Composable
private fun ReportCard(report: SupervisorReport) {
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                V2Chip(text = report.status.ifBlank { "unknown" }, color = reportTone(report.status))
                Spacer(Modifier.width(6.dp))
                Label("工单 ${report.ticketId.take(8)}", 9.5f, V2Colors.ink(.32f), mono = true)
                Spacer(Modifier.weight(1f))
                Label(relativeTime(report.createdAt), 9f, V2Colors.ink(.28f))
            }
            if (report.summary.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Label(report.summary, 11f, V2Colors.ink(.7f), maxLines = 4)
            }
        }
    }
}

@Composable
private fun TimelineRow(item: SupervisorTimelineItem) {
    val tone = timelineTone(item.kind)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tone.copy(alpha = .16f))
                .border(1.dp, tone.copy(alpha = .3f), RoundedCornerShape(999.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Label(
                when (item.kind) {
                    "trigger" -> "事件"
                    "ticket" -> "工单"
                    "report" -> "报告"
                    else -> item.kind
                },
                8.5f,
                tone,
                FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Label(item.title.ifBlank { item.id.take(8) }, 11f, V2Colors.ink(.72f), maxLines = 2)
            if (!item.summary.isNullOrBlank()) {
                Label(item.summary, 9.5f, V2Colors.ink(.36f), maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Label(relativeTime(item.at), 9f, V2Colors.ink(.28f))
    }
}

@Composable
private fun AssistantDetailSheet(
    ticket: SupervisorTicket,
    loading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDispatch: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
) {
    val tone = stageTone(ticket.stage)
    val canDispatch = ticket.stage == "intake" || ticket.stage == "planned"
    val canRetry = ticket.stage == "stopped"
    val canStop = ticket.stage == "running" || ticket.stage == "waiting" || ticket.stage == "reviewing"

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .9f) {
        SheetHeader(
            title = ticket.title.ifBlank { "工单详情" },
            subtitle = "${ticketStageLabel(ticket.stage)} · ${ticket.role.ifBlank { "未指定角色" }}",
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
            if (loading) Label("正在刷新详情…", 10f, V2Colors.ink(.35f))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V2Chip(text = ticketStageLabel(ticket.stage), color = tone)
                if (ticket.degraded) V2Chip(text = "降级执行", color = V2Colors.amber700)
                V2Chip(text = "#${ticket.sequence}", color = V2Colors.ink(.45f))
            }

            WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Label("任务说明", 11f, V2Colors.ink(.5f), FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Label(
                        ticket.description.ifBlank { "（没有补充说明）" },
                        11.5f,
                        V2Colors.ink(.78f),
                    )
                    Spacer(Modifier.height(10.dp))
                    ThinDivider()
                    Spacer(Modifier.height(8.dp))
                    Label("工单 ${ticket.id.take(12)}", 9.5f, V2Colors.ink(.3f), mono = true)
                    Label("线程 ${ticket.threadId.take(12)}", 9.5f, V2Colors.ink(.3f), mono = true)
                    if (ticket.dependsOn.isNotEmpty()) {
                        Label(
                            "依赖 ${ticket.dependsOn.size} 张前置工单",
                            9.5f,
                            V2Colors.ink(.3f),
                        )
                    }
                }
            }

            if (ticket.assignments.isNotEmpty()) {
                SectionTitle("派发记录", "${ticket.assignments.size} 次")
                ticket.assignments.forEach { assignment ->
                    WhiteCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                V2Chip(
                                    text = assignment.status.ifBlank { "unknown" },
                                    color = when (assignment.status) {
                                        "finished" -> V2Colors.emerald700
                                        "failed" -> V2Colors.rose700
                                        "running" -> V2Colors.cyan700
                                        else -> V2Colors.ink(.45f)
                                    },
                                )
                                Spacer(Modifier.weight(1f))
                                Label(relativeTime(assignment.startedAt), 9f, V2Colors.ink(.3f))
                            }
                            Spacer(Modifier.height(5.dp))
                            Label(
                                listOfNotNull(
                                    assignment.agentId?.take(14),
                                    assignment.taskId?.let { "task ${it.take(8)}" },
                                    assignment.runId?.let { "run ${it.take(8)}" },
                                ).joinToString(" · ").ifBlank { "未记录执行者" },
                                9.5f,
                                V2Colors.ink(.38f),
                                mono = true,
                            )
                        }
                    }
                }
            }

            if (ticket.reports.isNotEmpty()) {
                SectionTitle("子 Agent 报告", "${ticket.reports.size} 条")
                ticket.reports.forEach { report ->
                    WhiteCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                V2Chip(text = report.status, color = reportTone(report.status))
                                Spacer(Modifier.weight(1f))
                                Label(relativeTime(report.createdAt), 9f, V2Colors.ink(.3f))
                            }
                            if (report.summary.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Label(report.summary, 10.5f, V2Colors.ink(.62f), maxLines = 8)
                            }
                        }
                    }
                }
            }

            if (ticket.subscriptions.isNotEmpty()) {
                SectionTitle("外部订阅", "${ticket.subscriptions.size} 个")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ticket.subscriptions.forEach { sub ->
                        V2Chip(text = "${sub.sourceKey}:${sub.mode}", color = V2Colors.indigo)
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canDispatch) {
                    PrimaryButton(
                        text = "派发",
                        onClick = onDispatch,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                if (canRetry) {
                    GhostButton(
                        text = "重试",
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
                if (canStop) {
                    GhostButton(
                        text = "停止",
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        contentColor = V2Colors.rose700,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}
