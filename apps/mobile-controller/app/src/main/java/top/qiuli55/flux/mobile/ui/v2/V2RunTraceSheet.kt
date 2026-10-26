package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.RunTrace
import top.qiuli55.flux.mobile.data.RunTraceItem
import top.qiuli55.flux.mobile.data.TaskRunSummary

/**
 * 一轮 Run 的执行详情（分层与 codex 一致）：
 *
 * 1. 概览：耗时、改动文件数、运行命令数、读取文件数；
 * 2. 思考过程：模型这一轮的推理（按段展示，可展开）；
 * 3. 干了什么：每次工具调用，点开看完整命令与输出；
 * 4. 错误行：工具或运行时抛出的错误。
 *
 * 数据来自持久化轨迹接口，刷新页面或换设备后仍可回看；执行中的实时轨迹仍走 SSE。
 */
@Composable
internal fun RunTraceSheet(
    summary: TaskRunSummary?,
    trace: RunTrace?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .92f) {
        SheetHeader(
            title = "执行详情",
            subtitle = summary?.let { statusLabel(it.status) },
            onClose = onDismiss,
        )
        when {
            loading && trace == null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = V2Colors.cyan700,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }

            trace == null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Label("这一轮的轨迹暂时取不到，稍后重试", 10.5f, V2Colors.ink(.45f))
            }

            else -> TraceBody(trace, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TraceBody(trace: RunTrace, modifier: Modifier) {
    val thinking = trace.items.filter { it.kind == "thinking" && it.text.isNotBlank() }
    val tools = trace.items.filter { it.kind == "tool" }
    val errors = trace.items.filter { it.kind == "error" }
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { OverviewCard(trace) }
        if (thinking.isNotEmpty()) {
            item { SectionTitle("思考过程", "${thinking.size} 段") }
            items(thinking, key = { "think-${it.seq}" }) { ThinkingTraceRow(it) }
        }
        if (tools.isNotEmpty()) {
            item { SectionTitle("干了什么", "${tools.size} 步") }
            items(tools, key = { "tool-${it.seq}" }) { ToolTraceRow(it) }
        }
        if (errors.isNotEmpty()) {
            item { SectionTitle("错误", "${errors.size} 条") }
            items(errors, key = { "error-${it.seq}" }) { ErrorTraceRow(it) }
        }
        if (thinking.isEmpty() && tools.isEmpty() && errors.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label("这一轮没有留下执行轨迹", 10.5f, V2Colors.ink(.38f))
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(trace: RunTrace) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.72f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
    ) {
        StatCell("耗时", formatRunDuration(trace.durationMs), Modifier.weight(1f))
        StatDivider()
        StatCell("改动文件", trace.filesChanged.toString(), Modifier.weight(1f), V2Colors.emerald)
        StatDivider()
        StatCell("运行命令", trace.commands.toString(), Modifier.weight(1f), V2Colors.cyan700)
        StatDivider()
        StatCell("读取文件", trace.filesRead.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Color = V2Colors.ink(.82f),
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(value, 14f, tone, FontWeight.SemiBold, mono = true, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Label(label, 8.5f, V2Colors.ink(.36f), maxLines = 1)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(26.dp).background(V2Colors.ink(.07f)))
}

@Composable
private fun SectionTitle(title: String, note: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, 10.5f, V2Colors.ink(.72f), FontWeight.Medium)
        if (note != null) {
            Spacer(Modifier.width(6.dp))
            Label(note, 8.5f, V2Colors.ink(.32f), mono = true)
        }
    }
}

@Composable
private fun ThinkingTraceRow(item: RunTraceItem) {
    var open by remember(item.seq) { mutableStateOf(false) }
    val text = item.text.trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.surface(.58f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = V2Colors.ink(.34f),
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(5.dp))
            Label("思考", 8.5f, V2Colors.ink(.38f), mono = true)
        }
        Spacer(Modifier.height(3.dp))
        Label(
            text,
            10f,
            V2Colors.ink(.56f),
            maxLines = if (open) Int.MAX_VALUE else 6,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = text.length > 120) { open = !open },
        )
        if (text.length > 120) {
            Label(if (open) "收起" else "展开全部", 8.5f, V2Colors.cyan700)
        }
    }
}

@Composable
private fun ToolTraceRow(item: RunTraceItem) {
    var open by remember(item.seq) { mutableStateOf(false) }
    val detail = item.detail.trim()
    val output = item.output.trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.surface(.62f))
            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = detail.isNotEmpty() || output.isNotEmpty()) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label(
                item.name.ifBlank { "tool" },
                9.5f,
                V2Colors.ink(.76f),
                FontWeight.Medium,
                mono = true,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            categoryBadge(item.category)
            statusBadge(item.status)
            Spacer(Modifier.weight(1f))
            if (detail.isNotEmpty() || output.isNotEmpty()) {
                Icon(
                    if (open) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = V2Colors.ink(.30f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (detail.isNotEmpty() && !open) {
            Spacer(Modifier.height(3.dp))
            Label(detail, 9.5f, V2Colors.ink(.52f), mono = true, maxLines = 2)
        }
        if (open) {
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                CodeSection("命令", detail)
            }
            if (output.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                CodeSection("输出", output)
            }
        } else if (output.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label(
                "查看命令与输出 · ${output.lineSequence().count()} 行",
                8.5f,
                V2Colors.cyan700,
            )
        }
        if (item.paths.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Label(item.paths.joinToString("、"), 8.5f, V2Colors.ink(.36f), maxLines = 1, mono = true)
        }
    }
}

@Composable
private fun CodeSection(label: String, code: String) {
    Label(label, 8.5f, V2Colors.ink(.36f), mono = true)
    Spacer(Modifier.height(3.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(V2Colors.ink(.045f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 9.dp, vertical = 7.dp),
            color = V2Colors.ink(.58f),
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

@Composable
private fun ErrorTraceRow(item: RunTraceItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.rose.copy(alpha = .07f))
            .border(1.dp, V2Colors.rose.copy(alpha = .2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(V2Colors.rose, RoundedCornerShape(999.dp)))
        Spacer(Modifier.width(7.dp))
        Label(item.text.ifBlank { "执行出错" }, 9.5f, V2Colors.rose, maxLines = 6)
    }
}

@Composable
private fun categoryBadge(category: String) {
    val (text, color) = when (category) {
        "command" -> "命令" to V2Colors.cyan700
        "read" -> "读取" to V2Colors.ink(.44f)
        "write" -> "改动" to V2Colors.emerald
        else -> return
    }
    V2Chip(text, color, modifier = Modifier.padding(end = 4.dp))
}

@Composable
private fun statusBadge(status: String) {
    val (text, color) = when (status.trim().lowercase()) {
        "completed", "success", "succeeded", "done" -> "完成" to V2Colors.emerald
        "error", "failed", "failure" -> "失败" to V2Colors.rose
        "running", "in_progress", "pending", "started" -> "运行中" to V2Colors.cyan700
        else -> return
    }
    V2Chip(text, color)
}

private fun statusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "failed" -> "执行失败"
    "cancelled" -> "已取消"
    "running" -> "执行中"
    else -> status
}
