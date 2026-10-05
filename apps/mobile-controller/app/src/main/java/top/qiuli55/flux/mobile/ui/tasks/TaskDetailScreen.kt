package top.qiuli55.flux.mobile.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.data.ConfirmationItem
import top.qiuli55.flux.mobile.data.DecisionOption
import top.qiuli55.flux.mobile.data.Run
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.TaskMessage
import top.qiuli55.flux.mobile.ui.EmptyHint
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.KeyValueRow
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.MonoText
import top.qiuli55.flux.mobile.ui.SectionTitle
import top.qiuli55.flux.mobile.ui.StatusChip
import top.qiuli55.flux.mobile.ui.ThinDivider
import top.qiuli55.flux.mobile.ui.changeStatusStyle
import top.qiuli55.flux.mobile.ui.fluxFieldColors
import top.qiuli55.flux.mobile.ui.runStatusStyle
import top.qiuli55.flux.mobile.ui.shortTime
import top.qiuli55.flux.mobile.ui.taskStatusStyle
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/**
 * 任务详情：需求确认 → 开始执行 → 看 Run 进展 → 审提案 → 落盘，全部在这一页完成。
 *
 * 版式顺序：任务头 → 提示 → Run 状态 → 需求确认 → 待决策 → 对话 → 变更。
 * 输入框固定在底部（bottomBar），消息发出后列表自动跳到底部，不必先手动滚到对话区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    viewModel: TaskDetailViewModel,
    onBack: () -> Unit,
    onOpenChange: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val task = state.task
    val finalStatus = task?.status in setOf("completed", "failed", "cancelled")

    // 从提案详情页返回时刷新一次：审核动作（批准/落盘）发生在另一个页面，这里的状态得跟上
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.startPolling()
                    viewModel.refresh(quiet = true)
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 消息数量变化（发出去了 / 收到回复 / 轮询拉到新的）→ 跳到列表底部
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.lastIndex) }
        }
    }

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("任务", style = MaterialTheme.typography.titleMedium)
                        MonoText(text = task?.id?.take(8) ?: "…", color = FluxColors.text3)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = FluxColors.text2)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = FluxColors.text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FluxColors.bg,
                    titleContentColor = FluxColors.text,
                ),
            )
        },
        bottomBar = {
            ConversationInput(
                value = state.draft,
                onChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                enabled = !finalStatus && !state.sending,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                Box(Modifier.padding(16.dp)) { LoadingRow("正在读取任务…") }
                return@Column
            }
            if (state.error != null) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorBanner(state.error!!) }
            }
            if (state.notice != null) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = state.notice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = FluxColors.green,
                        modifier = Modifier.clickable { viewModel.dismissNotice() },
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TaskHeaderCard(task = task, onStart = viewModel::start, onCancel = viewModel::cancel, busy = state.busyAction) }

                val run = state.run
                if (run != null) {
                    item { RunCard(run = run, onStop = viewModel::interruptRun, busy = state.busyAction == "interrupt") }
                }

                val confirmation = task?.confirmation
                if (confirmation != null && confirmation.items.isNotEmpty()) {
                    item { ConfirmationCard(items = confirmation.items, updatedAt = confirmation.updatedAt) }
                }

                val pending = task?.pendingDecision
                if (pending != null) {
                    item {
                        DecisionCard(
                            question = pending.question.orEmpty(),
                            context = pending.context,
                            options = pending.options,
                            onChoose = { option -> viewModel.chooseDecision(pending.id.orEmpty(), option, reject = false) },
                            onReject = { viewModel.chooseDecision(pending.id.orEmpty(), option = null, reject = true) },
                            busy = state.busyAction == "decision",
                        )
                    }
                }

                item { SectionTitle("对话（${state.messages.size} 条）", Modifier.padding(top = 4.dp)) }
                if (state.messages.isEmpty()) {
                    item { EmptyHint("还没有对话。可以在下面的输入框里补充要求，助手会回复并给出需求确认。") }
                } else {
                    items(state.messages, key = { it.id }) { message -> MessageRow(message) }
                }

                item {
                    SectionTitle("变更（${state.changes.size} 项）", Modifier.padding(top = 8.dp))
                }
                if (state.changes.isEmpty()) {
                    item { EmptyHint("Agent 还没有提交提案。执行完成后，改动会出现在这里等你审核。") }
                } else {
                    items(state.changes, key = { it.id }) { change ->
                        ChangeCard(
                            change = change,
                            busy = state.busyAction?.endsWith(change.id) == true,
                            onOpen = { onOpenChange(change.id) },
                            onAccept = { viewModel.acceptChange(change.id) },
                            onApply = { viewModel.applyChange(change.id) },
                            onReject = { viewModel.rejectChange(change.id) },
                            onRollback = { viewModel.rollbackChange(change.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun TaskHeaderCard(task: Task?, onStart: () -> Unit, onCancel: () -> Unit, busy: String?) {
    if (task == null) return
    val (label, color) = taskStatusStyle(task.status)
    FluxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodyLarge,
                color = FluxColors.text,
                modifier = Modifier.weight(1f),
            )
            StatusChip(label, color, Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("决策方式", if (task.decisionMode == "manual") "停下来问我" else "AI 自己定")
        KeyValueRow("创建时间", shortTime(task.createdAt), mono = true)
        task.runId?.let { KeyValueRow("Run", it, mono = true) }
        task.result?.takeIf { !it.toString().isNullOrBlank() && it.toString() != "null" }?.let { result ->
            ThinDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("最终结果")
            Text(result.toString(), style = MaterialTheme.typography.bodySmall, color = FluxColors.text2)
        }

        // 动作：未开始 → 开始执行；执行中/待决策 → 取消
        val open = task.status == "pending" || task.status == "running" || task.status == "waiting_for_user_decision"
        if (open) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    enabled = task.runId == null && task.status != "waiting_for_user_decision" && busy == null,
                    modifier = Modifier.weight(1f),
                ) { Text(if (task.runId == null) "开始执行" else "执行中") }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy == "cancel") "取消中…" else "取消任务") }
            }
        }
    }
}

@Composable
private fun RunCard(run: Run, onStop: () -> Unit, busy: Boolean) {
    val (label, color) = runStatusStyle(run.status)
    val active = run.status in setOf("pending", "queued", "starting", "running", "cancelling")
    FluxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agent Run", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip(label, color)
        }
        Spacer(Modifier.height(8.dp))
        run.finishReason?.let { KeyValueRow("结束原因", it, mono = true) }
        run.error?.let { error ->
            Spacer(Modifier.height(6.dp))
            ErrorBanner(error)
        }
        run.lastEvent?.let { MonoText("最后事件：$it", color = FluxColors.text3) }
        if (active) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onStop, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "停止中…" else "停止 Agent")
            }
        }
    }
}

@Composable
private fun ConfirmationCard(
    items: List<ConfirmationItem>,
    updatedAt: String?,
) {
    FluxCard {
        SectionTitle("需求确认（执行依据）")
        items.forEach { item ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = FluxColors.text3,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = FluxColors.text2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        MonoText("更新于 ${shortTime(updatedAt)}", color = FluxColors.text3)
    }
}

@Composable
private fun DecisionCard(
    question: String,
    context: String?,
    options: List<DecisionOption>,
    onChoose: (String) -> Unit,
    onReject: () -> Unit,
    busy: Boolean,
) {
    FluxCard {
        SectionTitle("需要你决定")
        Text(question, style = MaterialTheme.typography.bodyLarge, color = FluxColors.text)
        context?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = FluxColors.text3)
        }
        Spacer(Modifier.height(10.dp))
        options.forEach { option ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = !busy) { onChoose(option.label) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FluxColors.accent,
                        modifier = Modifier.weight(1f),
                    )
                    if (option.recommended) StatusChip("推荐", FluxColors.green)
                }
                option.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = FluxColors.text3)
                }
            }
            ThinDivider()
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReject, enabled = !busy) { Text("都不合适，让 Agent 重新给方案") }
    }
}

@Composable
private fun MessageRow(message: TaskMessage) {
    val isUser = message.role == "user"
    Row(modifier = Modifier.fillMaxWidth()) {
        // 用户消息靠右（留出 15% 空白），助手消息占满整行：一眼能看出谁在说话
        if (isUser) Spacer(Modifier.weight(0.15f))
        Column(modifier = Modifier.weight(0.85f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = if (isUser) "我" else "助手",
                    color = if (isUser) FluxColors.blue else FluxColors.accent,
                )
                if (message.kind != "text") {
                    MonoText(" · ${message.kind}", color = FluxColors.text3)
                }
                Spacer(Modifier.weight(1f))
                MonoText(shortTime(message.createdAt), color = FluxColors.text3)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) FluxColors.text else FluxColors.text2,
            )
        }
        if (!isUser) Spacer(Modifier.weight(0.15f))
    }
}

@Composable
private fun ChangeCard(
    change: Change,
    busy: Boolean,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onRollback: () -> Unit,
) {
    val (statusLabel, statusColor) = changeStatusStyle(change.status)
    FluxCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = change.kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = FluxColors.text3,
            )
            Spacer(Modifier.weight(1f))
            StatusChip(statusLabel, statusColor)
        }
        Spacer(Modifier.height(6.dp))
        MonoText(
            text = change.filePath,
            color = FluxColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            MonoText("+${change.addedLines}", color = FluxColors.green)
            MonoText("  -${change.removedLines}", color = FluxColors.red)
            change.summary?.takeIf { it.isNotBlank() }?.let {
                MonoText(
                    "  ${it}",
                    color = FluxColors.text3,
                    maxLines = 1,
                )
            }
        }
        if (change.applyError != null) {
            Spacer(Modifier.height(6.dp))
            ErrorBanner(change.applyError)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (change.status) {
                "pending" -> {
                    Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) { Text("批准") }
                    OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拒绝") }
                }
                "accepted" -> {
                    Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1f)) { Text("应用（落盘）") }
                    OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拒绝") }
                }
                "applied" -> {
                    OutlinedButton(onClick = onRollback, enabled = !busy, modifier = Modifier.weight(1f)) { Text("回滚") }
                }
                else -> {
                    TextButton(onClick = onOpen) { Text("查看详情") }
                }
            }
        }
    }
}

/** 底部对话输入：始终可见，不必先滚到对话区。 */
@Composable
private fun ConversationInput(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = {
                Text(
                    if (enabled) "补充要求，或问 Agent 问题…" else "任务已结束，不能再追加消息",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            enabled = enabled,
            maxLines = 4,
            colors = fluxFieldColors(),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Icon(
                Icons.Default.Send,
                contentDescription = "发送",
                tint = if (enabled && value.isNotBlank()) FluxColors.accent else FluxColors.text3,
            )
        }
    }
}