package top.qiuli55.flux.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/**
 * 全应用共用的展示件与状态文案。
 *
 * 设计口径（用户已确认的降噪要求）：一块内容只用一层卡片、颜色只用来表示状态、
 * 字号只用主题里的几档、留白靠 12/16dp 的固定节奏。
 */

/** 一块内容卡片：--panel 底 + --line-soft 描边 + 10dp 圆角（与桌面端 --r 一致）。 */
@Composable
fun FluxCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FluxColors.panel)
            .padding(14.dp),
        content = { content() },
    )
}

/** 小节标题（12sp、弱化色、全大写字母间距不额外加——中文用不上）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = FluxColors.text3,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

/** 状态胶囊：唯一允许用颜色的地方（绿=成功、红=失败、橙=等待、紫=进行中、灰=终态）。 */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

/** 空态：一句话说清"这里为什么是空的"。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FluxColors.text3,
        modifier = modifier.padding(vertical = 18.dp),
    )
}

/** 加载中：只在首次加载时占位，刷新时不遮内容。 */
@Composable
fun LoadingRow(text: String = "加载中…") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = FluxColors.text3,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 错误条：把服务端原话显示出来，不替换成"出错了"。 */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FluxColors.red.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = FluxColors.red,
        )
    }
}

/** 等宽文本（路径、UUID、命令、diff、终端输出）。 */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FluxColors.text2,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 一行"标签 + 值"，值默认等宽（时间、路径、ID 这类信息量在字符本身）。 */
@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = FluxColors.text3,
            modifier = Modifier.width(76.dp),
        )
        if (mono) {
            MonoText(text = value, modifier = Modifier.weight(1f))
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = FluxColors.text2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 极细分隔线（1px，--line-soft）。 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FluxColors.lineSoft),
    )
}

/** 输入框配色：深色面板一致，避免 material3 默认的浅色描边在深色底上跳出来。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fluxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = FluxColors.text,
    unfocusedTextColor = FluxColors.text,
    focusedBorderColor = FluxColors.accent,
    unfocusedBorderColor = FluxColors.line,
    focusedLabelColor = FluxColors.accent,
    unfocusedLabelColor = FluxColors.text3,
    cursorColor = FluxColors.accent,
    focusedContainerColor = FluxColors.panel,
    unfocusedContainerColor = FluxColors.panel,
)

// ---------------------------------------------------------------- 状态 → 文案/颜色

/** 任务状态。 */
fun taskStatusStyle(status: String): Pair<String, Color> = when (status) {
    "pending" -> "待处理" to FluxColors.text2
    "running" -> "执行中" to FluxColors.accent
    "waiting_for_user_decision" -> "等你决策" to FluxColors.orange
    "completed" -> "已完成" to FluxColors.green
    "failed" -> "失败" to FluxColors.red
    "cancelled" -> "已取消" to FluxColors.text3
    else -> status to FluxColors.text2
}

/** 提案状态。 */
fun changeStatusStyle(status: String): Pair<String, Color> = when (status) {
    "pending" -> "待审核" to FluxColors.orange
    "accepted" -> "已批准" to FluxColors.blue
    "applying" -> "落盘中" to FluxColors.accent
    "applied" -> "已落盘" to FluxColors.green
    "rejected" -> "已拒绝" to FluxColors.text3
    "failed" -> "落盘失败" to FluxColors.red
    "expired" -> "已失效" to FluxColors.text3
    "rolled_back" -> "已回滚" to FluxColors.text3
    else -> status to FluxColors.text2
}

/** Run 状态。 */
fun runStatusStyle(status: String): Pair<String, Color> = when (status) {
    "pending", "queued" -> "排队中" to FluxColors.text2
    "starting" -> "启动中" to FluxColors.accent
    "running" -> "运行中" to FluxColors.accent
    "cancelling" -> "停止中" to FluxColors.orange
    "completed" -> "已完成" to FluxColors.green
    "failed" -> "失败" to FluxColors.red
    "timeout" -> "超时" to FluxColors.orange
    "cancelled" -> "已取消" to FluxColors.text3
    "interrupted" -> "已中断" to FluxColors.orange
    else -> status to FluxColors.text2
}

/** 终端会话状态。 */
fun terminalStatusStyle(status: String): Pair<String, Color> = when (status) {
    "active" -> "进行中" to FluxColors.accent
    "stopped" -> "已停止" to FluxColors.text3
    "closed" -> "已关闭" to FluxColors.text3
    else -> status to FluxColors.text2
}

/** 时间戳：服务端给的是 ISO8601（UTC），界面只显示到分钟并转成可读形态。 */
fun shortTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        OffsetDateTime.parse(iso)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrElse { iso }
}