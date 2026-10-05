package top.qiuli55.flux.mobile.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.KeyValueRow
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.SectionTitle
import top.qiuli55.flux.mobile.ui.StatusChip
import top.qiuli55.flux.mobile.ui.changeStatusStyle
import top.qiuli55.flux.mobile.ui.shortTime
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/**
 * 单条提案详情：diff 与新旧内容都可看，审核动作与任务页一致。
 *
 * 内容区用等宽字体 + 横向滚动：代码不允许自动换行（换行后的 diff 没法读缩进）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeDetailScreen(viewModel: ChangeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(0) }   // 0 diff / 1 改动后 / 2 改动前
    val change = state.change

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("变更", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = change?.filePath ?: "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = FluxColors.text3,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = FluxColors.text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FluxColors.bg,
                    titleContentColor = FluxColors.text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (state.loading) {
                Spacer(Modifier.height(12.dp))
                LoadingRow("正在读取提案…")
                return@Column
            }
            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(state.error!!)
            }
            if (state.notice != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.notice!!, style = MaterialTheme.typography.bodySmall, color = FluxColors.green)
            }
            if (change == null) {
                Spacer(Modifier.height(12.dp))
                Text("提案不存在或已被清理", style = MaterialTheme.typography.bodySmall, color = FluxColors.text3)
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            MetricCard(change)

            Spacer(Modifier.height(12.dp))
            ActionRow(
                change = change,
                busy = state.busy,
                onAccept = viewModel::accept,
                onApply = viewModel::apply,
                onReject = viewModel::reject,
                onRollback = viewModel::rollback,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Diff") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("改动后") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("改动前") })
            }
            Spacer(Modifier.height(10.dp))
            when (tab) {
                0 -> DiffBlock(change.diff)
                1 -> ContentBlock(change.proposedContent, "（删除类提案没有“改动后内容”）")
                else -> ContentBlock(change.originalContent, "（新建文件没有“改动前内容”）")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun MetricCard(change: Change) {
    val (label, color) = changeStatusStyle(change.status)
    FluxCard {
        Row {
            Text(
                text = change.kindLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = FluxColors.text,
                modifier = Modifier.weight(1f),
            )
            StatusChip(label, color)
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("文件", change.filePath, mono = true)
        KeyValueRow("行数", "+${change.addedLines} / -${change.removedLines}", mono = true)
        change.agentSource?.let { KeyValueRow("来源", it, mono = true) }
        change.expiresAt?.let { KeyValueRow("有效期至", shortTime(it), mono = true) }
        change.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Spacer(Modifier.height(8.dp))
            SectionTitle("摘要")
            Text(summary, style = MaterialTheme.typography.bodySmall, color = FluxColors.text2)
        }
        change.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Spacer(Modifier.height(8.dp))
            SectionTitle("提案理由")
            Text(reason, style = MaterialTheme.typography.bodySmall, color = FluxColors.text2)
        }
        if (change.applyError != null) {
            Spacer(Modifier.height(8.dp))
            ErrorBanner(change.applyError)
        }
        change.backupPath?.let {
            Spacer(Modifier.height(8.dp))
            KeyValueRow("备份", it, mono = true)
        }
    }
}

@Composable
private fun ActionRow(
    change: Change,
    busy: Boolean,
    onAccept: () -> Unit,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onRollback: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (change.status) {
            "pending" -> {
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "处理中…" else "批准")
                }
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拒绝") }
            }
            "accepted" -> {
                Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "落盘中…" else "应用（落盘）")
                }
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拒绝") }
            }
            "applied" -> {
                OutlinedButton(onClick = onRollback, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "回滚中…" else "回滚这次改动")
                }
            }
            else -> {
                Text(
                    text = "当前状态不可再操作（$change.status）",
                    style = MaterialTheme.typography.bodySmall,
                    color = FluxColors.text3,
                )
            }
        }
    }
}

/** diff 渲染：+ 绿 / - 红 / @@ 紫 / 其余弱化色。整块横向滚动，不折行。 */
@Composable
private fun DiffBlock(diff: String) {
    if (diff.isBlank()) {
        Text("没有 diff 内容", style = MaterialTheme.typography.bodySmall, color = FluxColors.text3)
        return
    }
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FluxColors.bg2)
            .horizontalScroll(hScroll)
            .padding(10.dp),
    ) {
        Column {
            diff.lines().forEach { line ->
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = diffLineColor(line),
                    softWrap = false,
                )
            }
        }
    }
}

private fun diffLineColor(line: String): Color = when {
    line.startsWith("+++") || line.startsWith("---") -> FluxColors.text3
    line.startsWith("@@") -> FluxColors.accent
    line.startsWith("+") -> FluxColors.green
    line.startsWith("-") -> FluxColors.red
    else -> FluxColors.text2
}

/** 整份文件内容（改动前 / 改动后）：同样等宽 + 横向滚动。 */
@Composable
private fun ContentBlock(content: String?, emptyHint: String) {
    if (content == null) {
        Text(emptyHint, style = MaterialTheme.typography.bodySmall, color = FluxColors.text3)
        return
    }
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FluxColors.bg2)
            .horizontalScroll(hScroll)
            .padding(10.dp),
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = FluxColors.text2,
            softWrap = false,
        )
    }
}