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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
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
    /** 后台命令状态条（不混进正文正文气泡）。 */
    const val BACKGROUND = "v2_stream_background"

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
 * 本轮执行过程：摘要行（运行了几条命令 · 时长）+ 可折叠的命令行。
 *
 * 目标是不再把工具/命令输出堆成一大墙字：
 *  - 结束后默认收起，只剩一行摘要；流式中保持展开，方便看到正在跑什么；
 *  - 每条命令只占一行，输出默认隐藏，点开单条命令才看，并在框内限高滚动；
 *  - 流式正文继续生成、但命令仍在跑时，收进「后台 Bash」状态条，不混进正文。
 */
@Composable
internal fun StreamActivityCard(
    stream: StreamUiSnapshot,
    modifier: Modifier = Modifier,
) {
    if (!stream.hasActivity) return
    // 只要还在思考 / 有命令在跑 / 正文还在流，本轮就视为进行中，默认展开；
    // 结束后自动收起成一行摘要（用户手动展开后不再覆盖）。
    val activityLive = stream.liveActive ||
        stream.reasoning?.active == true ||
        stream.runningTools > 0
    var manual by remember(stream.runStartedAtMs) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(activityLive, stream.runStartedAtMs) {
        if (!activityLive && manual == null) manual = false
    }
    val expanded = manual ?: activityLive
    val summary = streamActivitySummary(stream, System.currentTimeMillis())
    val backgroundCommands = backgroundCommandSteps(stream)
    val backgroundIds = backgroundCommands.map { it.id }.toSet()
    val visibleTools = stream.tools.filterNot { it.id in backgroundIds }
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
                .clickable { manual = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = V2Colors.ink(.46f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label(
                summary,
                10f,
                V2Colors.ink(.60f),
                weight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (stream.liveActive && stream.runningTools > 0) {
                Box(Modifier.size(6.dp).background(V2Colors.emerald, CircleShape))
                Spacer(Modifier.width(4.dp))
                Label("进行中", 8f, V2Colors.emerald, mono = true)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起执行过程" else "展开执行过程",
                tint = V2Colors.ink(.34f),
                modifier = Modifier.size(15.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                stream.reasoning?.let { ReasoningSection(it) }
                if (backgroundCommands.isNotEmpty()) {
                    BackgroundCommandChip(backgroundCommands.size)
                }
                visibleTools.forEach { step -> StreamToolStepRow(step) }
                stream.errorLines.forEach { line -> StreamErrorRow(line) }
            }
        }
    }
}

/**
 * 摘要行文案：`运行了 N 条命令 · 时长`（外加读/写文件数）。
 * 纯函数，方便 JVM 单测直接断言时长格式与文案。
 */
internal fun streamActivitySummary(stream: StreamUiSnapshot, nowMs: Long): String {
    val tools = stream.tools
    val categorized = tools.count { it.category.isNotBlank() }
    val commands = if (categorized > 0) tools.count { it.category == "command" } else tools.size
    val filesChanged = tools.filter { it.category == "write" }.flatMap { it.paths }.toSet().size
    val filesRead = tools.filter { it.category == "read" }.flatMap { it.paths }.toSet().size
    val base = when {
        tools.isNotEmpty() && commands > 0 -> "运行了 $commands 条命令"
        tools.isNotEmpty() -> "${tools.size} 步"
        stream.reasoning?.active == true -> "思考中"
        stream.reasoning != null -> "思考过程"
        else -> "执行过程"
    }
    val extras = buildList {
        if (filesChanged > 0) add("改动 $filesChanged 文件")
        if (filesRead > 0) add("读取 $filesRead 文件")
        if (stream.errorLines.isNotEmpty()) add("${stream.errorLines.size} 条错误")
    }
    val withExtras = if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
    val elapsed = stream.elapsedMs(nowMs)
    return if (elapsed >= 1_000L) "$withExtras · ${formatRunDuration(elapsed)}" else withExtras
}

/** 运行时长：21秒 / 1分32秒 / 1小时5分。 */
internal fun formatRunDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1_000L).coerceAtMost(99_999_999L)
    if (totalSeconds < 60L) return "${totalSeconds}秒"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    if (minutes < 60L) {
        return if (seconds == 0L) "${minutes}分" else "${minutes}分${seconds}秒"
    }
    val hours = minutes / 60L
    val restMinutes = minutes % 60L
    return if (restMinutes == 0L) "${hours}小时" else "${hours}小时${restMinutes}分"
}

/**
 * 仍在后台运行的命令：流式正文已经开始继续生成，说明 Agent 没在等这条命令。
 * 这类步骤收进「后台 Bash」状态条，不再混进命令列表。
 */
internal fun backgroundCommandSteps(stream: StreamUiSnapshot): List<StreamToolStep> {
    // 「本轮还在继续」：正文在流，或思考在流。只有这时才把运行中的命令收进后台条。
    val turnContinues = stream.liveText.isNotBlank() || stream.reasoning?.active == true
    if (!stream.liveActive || !turnContinues) return emptyList()
    return stream.tools.filter {
        it.category == "command" && it.status == StreamToolStatus.RUNNING
    }
}

@Composable
private fun BackgroundCommandChip(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.BACKGROUND)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.blue.copy(alpha = .07f))
            .border(1.dp, V2Colors.blue.copy(alpha = .20f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = V2Colors.blue,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Label("后台 Bash", 9.5f, V2Colors.ink(.72f), weight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(5.dp).background(V2Colors.blue, CircleShape))
        Spacer(Modifier.width(4.dp))
        Label("$count", 9f, V2Colors.blue, mono = true)
        Spacer(Modifier.weight(1f))
        Label("仍在运行", 8f, V2Colors.ink(.34f), mono = true)
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
    val title = if (reasoning.active) "思考中… $elapsed" else "思考 $elapsed"
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

/**
 * 单条命令/工具：默认一行（状态图标 + 命令首行 + 状态 + 展开箭头）。
 * 点开才看完整命令与输出；输出框限高并可内部滚动，避免一屏被 stdout 铺满。
 */
@Composable
private fun StreamToolStepRow(step: StreamToolStep) {
    var open by remember(step.id) { mutableStateOf(false) }
    val (icon, tint, label) = when (step.status) {
        StreamToolStatus.RUNNING -> Triple(Icons.Outlined.Autorenew, V2Colors.cyan700, "运行中")
        StreamToolStatus.DONE -> Triple(Icons.Outlined.Check, V2Colors.emerald, "完成")
        StreamToolStatus.FAILED -> Triple(Icons.Outlined.Close, V2Colors.rose, "失败")
    }
    val command = step.detail.ifBlank { step.name }.ifBlank { "tool" }
    val firstLine = when {
        step.category == "command" -> command.lineSequence().firstOrNull()?.trim().orEmpty()
        step.paths.isNotEmpty() -> "${step.name} · ${step.paths.first()}"
        else -> step.name
    }.ifBlank { command.lineSequence().firstOrNull()?.trim().orEmpty() }.ifBlank { "tool" }
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
                firstLine,
                9.5f,
                V2Colors.ink(.74f),
                weight = FontWeight.Medium,
                mono = true,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Label(label, 8f, tint, mono = true)
            Spacer(Modifier.weight(1f))
            Icon(
                if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (open) "收起命令输出" else "展开命令输出",
                tint = V2Colors.ink(.34f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(V2StreamTags.toolBody(step.id))
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 首行已经展示了整条命令时不再重复；多行命令才补一份全文。
                if (step.detail.isNotBlank() && step.detail.trim() != firstLine) {
                    Label(
                        if (step.category == "command") "命令" else "参数",
                        8f,
                        V2Colors.ink(.34f),
                        mono = true,
                    )
                    Text(
                        text = step.detail,
                        color = V2Colors.ink(.60f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (step.output.isNotBlank()) {
                    Label("输出", 8f, V2Colors.ink(.34f), mono = true)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 168.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(V2Colors.ink(.045f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = step.output,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            color = V2Colors.ink(.52f),
                            fontSize = 9.5.sp,
                            lineHeight = 13.5.sp,
                            fontFamily = FontFamily.Monospace,
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

/** 思考耗时：不足 1 秒显示 0 秒，避免流式头几帧出现奇怪的小数。 */
internal fun formatStreamElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1000L).coerceAtMost(99_999L)
    return "${seconds}秒"
}
