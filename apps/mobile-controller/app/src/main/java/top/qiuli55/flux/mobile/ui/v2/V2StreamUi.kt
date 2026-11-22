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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 实时流日志 tag：抓 logcat 时按 FluxStream 过滤连接/重连/去重事件。 */
internal const val FLUX_STREAM_LOG_TAG = "FluxStream"

/** 流式交互的测试 tag（Compose 测试与后续回归都靠它定位）。 */
internal object V2StreamTags {
    const val ACTIVITY = "v2_stream_activity"
    const val ACTIVITY_TOGGLE = "v2_stream_activity_toggle"
    const val REASONING = "v2_stream_reasoning"
    const val REASONING_BODY = "v2_stream_reasoning_body"
    const val STATUS = "v2_stream_status"
    const val RETRY = "v2_stream_retry"
    const val ERROR = "v2_stream_error"
    const val JUMP_TO_BOTTOM = "v2_stream_jump_bottom"

    fun tool(id: String): String = "v2_stream_tool_$id"
    fun toolBody(id: String): String = "v2_stream_tool_body_$id"
}

/**
 * 输入区「停止」防抖：两次点击间隔小于 [windowMs] 时只认第一次。
 *
 * UI 有按钮防抖，ViewModel 还有一层同步状态抢占，双保险保证不会发出两个取消请求。
 * 纯逻辑，JVM 单测可直接覆盖。
 */
internal class StopTapGuard(private val windowMs: Long = 700L) {
    private var lastAcceptedMs: Long? = null

    fun tryAccept(nowMs: Long): Boolean {
        val last = lastAcceptedMs
        if (last != null && nowMs >= last && nowMs - last < windowMs) return false
        lastAcceptedMs = nowMs
        return true
    }
}

/**
 * 本轮执行过程：思考（可折叠，结束后默认收起）+ 工具步骤（逐步可折叠）。
 *
 * 沿用 RunTimelineCard 的卡片样式，只把「思考」和「工具」拆成独立可折叠项：
 *  - 思考中显示耗时，结束时自动收起，正文点开才看；
 *  - 工具默认只占一行，状态用 spinner / check / error 图标，参数与结果点开才看。
 */
@Composable
internal fun StreamActivityCard(
    stream: StreamUiSnapshot,
    modifier: Modifier = Modifier,
) {
    if (!stream.hasActivity) return
    var expanded by remember { mutableStateOf(true) }
    val tools = stream.tools
    val categorized = tools.count { it.category.isNotBlank() }
    val commands = if (categorized > 0) tools.count { it.category == "command" } else tools.size
    val filesChanged = tools.filter { it.category == "write" }.flatMap { it.paths }.toSet().size
    val filesRead = tools.filter { it.category == "read" }.flatMap { it.paths }.toSet().size
    val summary = buildString {
        val reasoning = stream.reasoning
        if (reasoning != null) {
            // 与 Claude 一致：思考中只报状态，结束后固化成「思考了 N 秒」。
            append(
                if (reasoning.active) {
                    "思考中"
                } else {
                    "思考了 ${formatStreamElapsed(reasoning.elapsedMs(System.currentTimeMillis()))}"
                },
            )
        }
        if (tools.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(if (commands > 0) "$commands 条命令" else "${tools.size} 步")
            if (filesChanged > 0) append(" · 改动 $filesChanged 文件")
            if (filesRead > 0) append(" · 读取 $filesRead 文件")
        }
        if (isEmpty()) append("执行过程")
    }
    Column(
        modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.ACTIVITY)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.58f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.ACTIVITY_TOGGLE)
                .clickable { expanded = !expanded },
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
            Label(summary, 8.5f, V2Colors.ink(.32f), mono = true)
            Spacer(Modifier.weight(1f))
            if (stream.liveActive && stream.runningTools > 0) {
                Box(Modifier.size(6.dp).background(V2Colors.emerald, CircleShape))
                Spacer(Modifier.width(4.dp))
                Label("进行中", 8f, V2Colors.emerald, mono = true)
                Spacer(Modifier.width(6.dp))
            }
            Label(if (expanded) "收起" else "展开", 8.5f, V2Colors.cyan700)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stream.reasoning?.let { ReasoningSection(it) }
                tools.forEach { step -> StreamToolStepRow(step) }
                stream.errorLines.forEach { line -> StreamErrorRow(line) }
            }
        }
    }
}

/** 思考块：流式中展开并显示「思考中… N 秒」，结束后自动收起成一行。 */
@Composable
private fun ReasoningSection(reasoning: StreamReasoning) {
    // 用户手动展开/收起后不再被自动收起覆盖；换一轮（startedAt 变化）重新开始。
    var manual by remember(reasoning.startedAtMs) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(reasoning.startedAtMs, reasoning.active) {
        if (!reasoning.active && manual == null) manual = false
    }
    val expanded = manual ?: reasoning.active
    val elapsed = formatStreamElapsed(reasoning.elapsedMs(System.currentTimeMillis()))
    // Claude 的写法：进行中是「思考中…」，结束后固化成「思考了 N 秒」。
    val title = if (reasoning.active) "思考中… $elapsed" else "思考了 $elapsed"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.REASONING)
                .clip(RoundedCornerShape(8.dp))
                .clickable { manual = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = V2Colors.ink(.34f),
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label(title, 9.5f, V2Colors.ink(if (reasoning.active) .52f else .40f), mono = true)
            Spacer(Modifier.weight(1f))
            Label(if (expanded) "收起" else "展开", 8.5f, V2Colors.cyan700)
        }
        if (expanded && reasoning.text.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Label(
                reasoning.text,
                10f,
                V2Colors.ink(.52f),
                maxLines = 80,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(V2StreamTags.REASONING_BODY),
            )
        }
    }
}

/** 单个工具步骤：默认一行（状态图标 + 名称 + 状态），参数/结果点开才展开。 */
@Composable
private fun StreamToolStepRow(step: StreamToolStep) {
    var open by remember(step.id) { mutableStateOf(false) }
    val (icon, tint, label) = when (step.status) {
        StreamToolStatus.RUNNING -> Triple(Icons.Outlined.Autorenew, V2Colors.cyan700, "运行中")
        StreamToolStatus.DONE -> Triple(Icons.Outlined.Check, V2Colors.emerald, "完成")
        StreamToolStatus.FAILED -> Triple(Icons.Outlined.Close, V2Colors.rose, "失败")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.ink(.032f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.tool(step.id))
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label(
                step.name.ifBlank { "tool" },
                9.5f,
                V2Colors.ink(.74f),
                weight = FontWeight.Medium,
                mono = true,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            // Claude 的工具行直接带目标（Read xxx / Bash 命令），
            // 不用点开就能知道这一步在做什么。
            val summary = compactToolDetail(step.detail)
            if (summary.isNotEmpty()) {
                Label(
                    summary,
                    9f,
                    V2Colors.ink(.38f),
                    mono = true,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            Label(label, 8f, tint, mono = true)
            Spacer(Modifier.width(6.dp))
            Label(if (open) "收起" else "展开", 8f, V2Colors.cyan700)
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(V2StreamTags.toolBody(step.id))
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (step.detail.isNotBlank()) {
                    Label("参数", 8f, V2Colors.ink(.34f), mono = true)
                    Text(
                        text = step.detail,
                        color = V2Colors.ink(.60f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (step.output.isNotBlank()) {
                    Label("结果", 8f, V2Colors.ink(.34f), mono = true)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(V2Colors.ink(.045f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = step.output,
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
                if (step.paths.isNotEmpty()) {
                    Label("文件 · ${step.paths.take(3).joinToString(", ")}", 8f, V2Colors.ink(.38f), mono = true)
                }
            }
        }
    }
}

@Composable
private fun StreamErrorRow(line: StreamErrorLine) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.ERROR)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.rose.copy(alpha = .07f))
            .border(1.dp, V2Colors.rose.copy(alpha = .2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(V2Colors.rose, CircleShape))
        Spacer(Modifier.width(7.dp))
        Label(line.text, 9.5f, V2Colors.rose, maxLines = 4)
    }
}

/**
 * 连接/终态错误的行内状态：不遮罩、不弹窗，只占列表尾部一行。
 * 终态错误保留已生成内容，并在右侧给「重试」。
 */
@Composable
internal fun StreamStatusRow(
    stream: StreamUiSnapshot,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = stream.terminalError
    val reconnecting = stream.connection == StreamConnection.RECONNECTING
    val failed = stream.connection == StreamConnection.FAILED
    if (error == null && !reconnecting && !failed && !stream.cancelled) return
    Row(
        modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.STATUS)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when {
            error != null -> "生成中断：$error"
            reconnecting -> "连接中断，正在重连…"
            failed -> "实时连接已断开"
            else -> "已停止生成"
        }
        val color = if (error != null || failed) {
            V2Colors.rose
        } else {
            V2Colors.ink(.42f)
        }
        Label(text, 10f, color, mono = error == null && !stream.cancelled, maxLines = 2)
        if (error != null || failed) {
            Spacer(Modifier.width(8.dp))
            Label(
                "重试",
                10f,
                V2Colors.cyan700,
                weight = FontWeight.Medium,
                modifier = Modifier
                    .testTag(V2StreamTags.RETRY)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 悬浮在消息区右下角的「回到最新」：离开底部后出现，有新内容时换文案。 */
@Composable
internal fun JumpToBottomButton(
    hasNewContent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .testTag(V2StreamTags.JUMP_TO_BOTTOM)
            .clip(RoundedCornerShape(999.dp))
            .background(V2Colors.surface(.94f))
            .border(1.dp, V2Colors.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ArrowDownward,
            contentDescription = "回到最新",
            tint = V2Colors.ink(.62f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Label(
            if (hasNewContent) "有新内容" else "回到最新",
            10f,
            V2Colors.ink(.66f),
            weight = FontWeight.Medium,
        )
    }
}

/** 工具行的一行摘要：取第一段非空内容，过长时截断，避免撑破单行。 */
internal fun compactToolDetail(detail: String, limit: Int = 80): String {
    val line = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (line.isEmpty()) return ""
    return if (line.length <= limit) line else line.take(limit - 1).trimEnd() + "…"
}

/** 运行状态条上的当前动作：与 Claude 的「Using Read…」同一层信息。 */
internal fun runningToolPhase(step: StreamToolStep): String = when (step.category) {
    "command" -> "正在执行命令"
    "read" -> "正在读取文件"
    "write" -> "正在修改文件"
    else -> "正在使用 ${step.name.ifBlank { "工具" }}"
}

/** 思考/执行耗时：不足 1 分钟只报秒，超过后与 Claude 一样用「分 + 秒」。 */
internal fun formatStreamElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1000L).coerceAtMost(99_999L)
    if (seconds < 60L) return "${seconds} 秒"
    return "${seconds / 60L} 分 ${seconds % 60L} 秒"
}
