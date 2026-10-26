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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Terminal
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

/**
 * 本轮 Run 的执行轨迹：思考刻度 + 命令卡。
 *
 * 形态对齐 codex 的运行视图——过程与最终答复分开：过程只在这里看，
 * 最终答复由 run_finished 落成普通助手消息（支持代码/表格/网址卡片）。
 */
@Composable
internal fun RunTimelineCard(
    items: List<V2RunItem>,
    active: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val tools = items.filter { it.kind == "tool" }
    // 服务端会随事件下发 category / paths：边执行边累加概览，Run 结束后的详情与它口径一致
    val categorized = tools.count { it.category.isNotBlank() }
    val commands = if (categorized > 0) tools.count { it.category == "command" } else tools.size
    val filesChanged = tools.filter { it.category == "write" }.flatMap { it.paths }.toSet().size
    val filesRead = tools.filter { it.category == "read" }.flatMap { it.paths }.toSet().size
    val summary = buildString {
        append(if (commands > 0) "$commands 条命令" else "${items.size} 步")
        if (filesChanged > 0) append(" · 改动 $filesChanged 文件")
        if (filesRead > 0) append(" · 读取 $filesRead 文件")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.58f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = V2Colors.cyan700,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label("执行过程", 10f, V2Colors.ink(.64f), weight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Label(
                summary,
                8.5f,
                V2Colors.ink(.32f),
                mono = true,
            )
            Spacer(Modifier.weight(1f))
            if (active) {
                Box(Modifier.size(6.dp).background(V2Colors.emerald, CircleShape))
                Spacer(Modifier.width(4.dp))
                Label("进行中", 8f, V2Colors.emerald, mono = true)
                Spacer(Modifier.width(6.dp))
            }
            // 执行中也要给出收起/展开的明确入口：用户收起后不会被新增行顶开
            Label(if (expanded) "收起" else "展开", 8.5f, V2Colors.cyan700)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { item ->
                    when (item.kind) {
                        "tool" -> ToolRunRow(item)
                        "error" -> ErrorRunRow(item)
                        else -> ThinkingRunRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingRunRow(item: V2RunItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    val text = item.text.trim()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = V2Colors.ink(.28f),
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(5.dp))
            Label("思考", 8.5f, V2Colors.ink(.34f), mono = true)
            if (text.isEmpty()) {
                Spacer(Modifier.width(6.dp))
                Label(
                    if (item.live) "正在思考…" else "等待下一步",
                    9.5f,
                    V2Colors.ink(if (item.live) .46f else .28f),
                )
            }
        }
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Label(
                text,
                10f,
                V2Colors.ink(.52f),
                maxLines = if (open) Int.MAX_VALUE else 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = text.length > 60) { open = !open },
            )
            if (text.length > 60) {
                Label(if (open) "收起" else "展开", 8f, V2Colors.cyan700)
            }
        }
    }
}

@Composable
private fun ToolRunRow(item: V2RunItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    val detail = item.detail.trim()
    val output = item.output.trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.ink(.032f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                V2Colors.ink(.74f),
                weight = FontWeight.Medium,
                mono = true,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            statusStyle(item.status)?.let { (text, color) -> V2Chip(text, color) }
            if (item.live && statusStyle(item.status) == null) {
                Spacer(Modifier.width(4.dp))
                Label("进行中", 8f, V2Colors.emerald, mono = true)
            }
        }
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Label(detail, 10f, V2Colors.ink(.56f), mono = true, maxLines = 3)
        }
        if (output.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable { open = !open }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    if (open) "收起输出" else "查看输出 · ${output.lineSequence().count()} 行",
                    8.5f,
                    V2Colors.cyan700,
                )
            }
            if (open) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(V2Colors.ink(.045f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = output,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        color = V2Colors.ink(.52f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorRunRow(item: V2RunItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.rose.copy(alpha = .07f))
            .border(1.dp, V2Colors.rose.copy(alpha = .2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(V2Colors.rose, CircleShape))
        Spacer(Modifier.width(7.dp))
        Label(item.text.ifBlank { "执行出错" }, 9.5f, V2Colors.rose, maxLines = 4)
    }
}

/** 工具状态 → 芯片文案与颜色；未知状态（运行中的原生事件）不显示芯片。 */
private fun statusStyle(status: String): Pair<String, Color>? {
    return when (status.trim().lowercase()) {
        "completed", "success", "succeeded", "done" -> "完成" to V2Colors.emerald
        "error", "failed", "failure" -> "失败" to V2Colors.rose
        "running", "in_progress", "pending", "started" -> "运行中" to V2Colors.cyan700
        else -> null
    }
}
