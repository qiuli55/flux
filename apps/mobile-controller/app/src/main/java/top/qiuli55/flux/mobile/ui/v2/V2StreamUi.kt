package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 实时流日志 tag：抓 logcat 时按 FluxStream 过滤连接/重连/去重事件。 */
internal const val FLUX_STREAM_LOG_TAG = "FluxStream"

/** 流式交互的测试 tag（Compose 测试与后续回归都靠它定位）。 */
internal object V2StreamTags {
    const val STAGES = "v2_stream_stages"
    const val STATUS_FOOTER = "v2_stream_status_footer"
    const val REASONING = "v2_stream_reasoning"
    const val REASONING_BODY = "v2_stream_reasoning_body"
    const val STATUS = "v2_stream_status"
    const val RETRY = "v2_stream_retry"
    const val ERROR = "v2_stream_error"
    const val JUMP_TO_BOTTOM = "v2_stream_jump_bottom"

    fun text(id: String): String = "v2_stream_text_$id"
    fun group(id: String): String = "v2_stream_group_$id"
    fun groupToggle(id: String): String = "v2_stream_group_toggle_$id"
    fun tool(id: String): String = "v2_stream_tool_$id"
    fun toolBody(id: String): String = "v2_stream_tool_body_$id"
    fun toolStatus(id: String): String = "v2_stream_tool_status_$id"
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
 * 终态时间线的最后一段正文是不是就是落库消息里的那段最终答复。
 *
 * 落库正文是完整内容，流式单段正文有 [STREAM_TEXT_LIMIT] 上限，长回答只剩尾部；
 * 两种情况都视为「已经由时间线画过」，调用方应该只保留落库那一份，避免同一段话出现两遍。
 */
internal fun streamAnswerMatchesPersisted(streamText: String, persistedText: String): Boolean {
    val live = streamText.trim()
    val persisted = persistedText.trim()
    if (live.isEmpty() || persisted.isEmpty()) return false
    if (live == persisted) return true
    return live.length >= STREAM_TEXT_LIMIT && persisted.endsWith(live)
}

/**
 * 一条助手消息里的多阶段时间线：思考 / 正文小结 / 命令组 / 工具组按发生顺序穿插。
 *
 * 这是**统一前端合同**的消费端：阶段完全由 `run_event` 的 kind（thinking / text / tool）
 * 与 `category` 推导，不按 Agent 名或 runtime 名分支。新 Agent 只要产出同一套事件，
 * 就自动得到同样的展示。
 *
 * @param omitTrailingAnswer 终态时把最后一段正文交给落库消息渲染：时间线只画过程
 *   （思考 / 中途正文 / 命令组 / 工具组），最终答复紧接在它们之后，避免重复。
 * @param showFooter 是否在时间线底部画状态 / 用时；调用方需要把「共用时」放到
 *   文件审核卡之后时，可以自己单独渲染 [StreamRunFooter]。
 */
@Composable
internal fun StreamStagesColumn(
    stream: StreamUiSnapshot,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
    onSend: (String) -> Unit = {},
    omitTrailingAnswer: Boolean = false,
    showFooter: Boolean = true,
) {
    if (!stream.hasActivity) return
    // 只省略「最后一段正文」；它是本轮最终答复，由落库消息在原位之后补上。
    val stages = if (omitTrailingAnswer && stream.stages.lastOrNull() is StreamTextStage) {
        stream.stages.dropLast(1)
    } else {
        stream.stages
    }
    // 光标只挂在「时间线最后一段且仍是正文」上；切到命令/工具后旧小结不再闪。
    val streamingTextId = (stages.lastOrNull() as? StreamTextStage)?.id
    Column(
        modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.STAGES),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stages.forEach { stage ->
            when (stage) {
                is StreamThoughtStage -> ThoughtBlock(stage)
                is StreamTextStage -> TextStageBubble(
                    stage = stage,
                    streaming = stream.liveActive && stage.id == streamingTextId,
                    onOpenUrl = onOpenUrl,
                    onSend = onSend,
                )

                is StreamToolGroupStage -> ToolGroupBlock(stage, onOpenUrl)
            }
        }
        stream.errorLines.forEach { line -> StreamErrorRow(line) }
        if (showFooter) StreamRunFooter(stream)
    }
}

/**
 * 进行中状态文案：思考中 / 正在运行命令 / 正在调用工具 / 工作中。
 * 纯函数，JVM 单测可直接断言；UI 与 reducer 都不需要知道是哪个 Agent。
 */
internal fun streamActivityLabel(stream: StreamUiSnapshot): String? {
    if (!stream.liveActive) return null
    return when (val last = stream.stages.lastOrNull()) {
        is StreamThoughtStage -> if (last.active) "思考中" else "工作中"
        is StreamToolGroupStage ->
            if (last.active) {
                if (last.command) "正在运行命令" else "正在调用工具"
            } else {
                "工作中"
            }

        is StreamTextStage -> "工作中"
        null -> stream.liveLabel?.removeSuffix("…") ?: "工作中"
    }
}

/** 组标题：进行中「正在运行命令 · 3秒」；结束后「运行了 3 条命令」。 */
internal fun toolGroupTitle(group: StreamToolGroupStage, nowMs: Long): String {
    val runningLabel = if (group.command) "正在运行命令" else "正在调用工具"
    val doneLabel = if (group.command) {
        "运行了 ${group.steps.size} 条命令"
    } else {
        "调用了 ${group.steps.size} 个工具"
    }
    if (!group.active) return doneLabel
    val seconds = formatStreamElapsed(group.elapsedMs(nowMs))
    return "$runningLabel · $seconds"
}

/** 思考块：进行中展开 + 灯泡扫光；结束后自动收起成「思考过程」。 */
@Composable
private fun ThoughtBlock(stage: StreamThoughtStage) {
    var manual by remember(stage.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(stage.id, stage.active) {
        if (!stage.active && manual == null) manual = false
    }
    val expanded = manual ?: stage.active
    val nowMs = rememberTicker(stage.active)
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
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = V2Colors.ink(.34f),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (stage.active) {
                ShimmerLabel("思考中…", 9.5f)
            } else {
                Label("思考过程", 9.5f, V2Colors.ink(.44f), mono = true)
                if (stage.elapsedMs(nowMs) >= 1000L) {
                    Spacer(Modifier.width(6.dp))
                    Label(formatStreamElapsed(stage.elapsedMs(nowMs)), 8.5f, V2Colors.ink(.30f), mono = true)
                }
            }
            Spacer(Modifier.weight(1f))
            Label(if (expanded) "收起" else "展开", 8.5f, V2Colors.cyan700)
        }
        if (expanded && stage.text.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Label(
                stage.text,
                10f,
                V2Colors.ink(.50f),
                maxLines = 200,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp)
                    .testTag(V2StreamTags.REASONING_BODY),
            )
        }
    }
}

/** 正文小结：沿用助手气泡样式与流式 Markdown 渲染（标题 / 列表 / 代码块 + 光标）。 */
@Composable
private fun TextStageBubble(
    stage: StreamTextStage,
    streaming: Boolean,
    onOpenUrl: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    if (stage.text.isBlank()) return
    val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 344.dp)
                .testTag(V2StreamTags.text(stage.id))
                .clip(shape)
                .background(V2Colors.surface(.75f))
                .innerGlow(shape)
                .border(1.dp, V2Colors.ink(.08f), shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            RichMessageBody(stage.text, streaming = streaming, onOpenUrl = onOpenUrl, onAction = onSend)
        }
    }
}

/**
 * 命令组 / 工具组：一个可折叠父级，组内每条也是可展开行。
 *
 * 进行中默认展开，标题显示已用时；全部结束后自动收起并改成「运行了 N 条命令」。
 * 用户手动展开/收起后不再被自动状态覆盖（换一组才重新开始）。
 */
@Composable
private fun ToolGroupBlock(group: StreamToolGroupStage, onOpenUrl: (String) -> Unit) {
    var manual by remember(group.id) { mutableStateOf<Boolean?>(null) }
    val active = group.active
    LaunchedEffect(group.id, active) {
        if (!active && manual == null) manual = false
    }
    val expanded = manual ?: active
    val nowMs = rememberTicker(active)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.group(group.id))
            .clip(shape)
            .background(V2Colors.surface(.58f))
            .border(1.dp, V2Colors.ink(.08f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.groupToggle(group.id))
                .clickable { manual = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (group.command) Icons.Outlined.Terminal else Icons.Outlined.Build,
                contentDescription = null,
                tint = if (active) V2Colors.blue else V2Colors.ink(.46f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Label(
                toolGroupTitle(group, nowMs),
                10f,
                if (active) V2Colors.ink(.66f) else V2Colors.ink(.60f),
                weight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                PulseDot(color = V2Colors.blue, size = 6.dp)
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
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.steps.forEach { step -> ToolStepRow(step, onOpenUrl) }
            }
        }
    }
}

/**
 * 单条命令/工具：默认一行（运行/调用 + 名称 + 状态），点开才看参数与输出。
 * 输出放在深色等宽块里并限高滚动，不把一屏铺满。
 */
@Composable
private fun ToolStepRow(step: StreamToolStep, onOpenUrl: (String) -> Unit) {
    var open by remember(step.id) { mutableStateOf(false) }
    val command = step.category == "command"
    val title = when {
        command -> step.detail.lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { step.name }

        step.name.isNotBlank() -> step.name
        else -> step.detail.lineSequence().firstOrNull()?.trim().orEmpty()
    }.ifBlank { "tool" }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(V2StreamTags.tool(step.id))
            .clip(RoundedCornerShape(9.dp))
            .clickable { open = !open }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(if (command) "运行" else "调用", 8.5f, V2Colors.ink(.38f), mono = true)
        Spacer(Modifier.width(5.dp))
        Label(
            title,
            9.5f,
            V2Colors.ink(.76f),
            weight = FontWeight.Medium,
            mono = true,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.testTag(V2StreamTags.toolStatus(step.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (step.status) {
                StreamToolStatus.RUNNING -> PulseDot(color = V2Colors.blue, size = 7.dp)
                StreamToolStatus.DONE -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = "完成",
                    tint = V2Colors.emerald,
                    modifier = Modifier.size(12.dp),
                )

                StreamToolStatus.FAILED -> Icon(
                    Icons.Outlined.Close,
                    contentDescription = "失败",
                    tint = V2Colors.rose,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (open) "收起命令输出" else "展开命令输出",
            tint = V2Colors.ink(.30f),
            modifier = Modifier.size(13.dp),
        )
    }
    if (open) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.toolBody(step.id))
                .padding(start = 8.dp, end = 4.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (step.detail.isNotBlank() && step.detail.trim() != title) {
                Label("参数", 8f, V2Colors.ink(.34f), mono = true)
                Text(
                    text = step.detail,
                    color = V2Colors.ink(.60f),
                    fontSize = 9.5.sp,
                    lineHeight = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (step.paths.isNotEmpty()) {
                Label("文件 · ${step.paths.take(3).joinToString(", ")}", 8f, V2Colors.ink(.38f), mono = true)
            }
            if (step.output.isNotBlank()) {
                Label("输出", 8f, V2Colors.ink(.34f), mono = true)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OutputBackground)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = step.output,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        color = OutputForeground,
                        fontSize = 9.5.sp,
                        lineHeight = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * 底部状态：进行中显示当前动作，结束后只留一行短 meta（总用时）。
 *
 * [onOpenTrace] 非空时这一行同时是「执行详情」入口：顶部耗时胶囊在插入式时间线里
 * 会让同一轮出现两个对不上的用时，因此由这里统一提供入口。
 */
@Composable
internal fun StreamRunFooter(
    stream: StreamUiSnapshot,
    modifier: Modifier = Modifier,
    onOpenTrace: (() -> Unit)? = null,
) {
    val label = streamActivityLabel(stream)
    val nowMs = rememberTicker(stream.liveActive)
    val elapsed = stream.elapsedMs(nowMs)
    if (label != null) {
        Row(
            modifier
                .fillMaxWidth()
                .testTag(V2StreamTags.STATUS_FOOTER)
                .padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulseDot(color = V2Colors.blue, size = 6.dp)
            Spacer(Modifier.width(7.dp))
            Label("$label…", 9.5f, V2Colors.ink(.46f), mono = true)
            if (elapsed > 0L) {
                Spacer(Modifier.width(8.dp))
                Label(formatStreamElapsed(elapsed), 9f, V2Colors.ink(.30f), mono = true)
            }
        }
    } else if (stream.runEndedAtMs != null && stream.runStartedAtMs > 0L) {
        val done = "共用时 ${formatRunDuration(elapsed)}"
        val text = if (onOpenTrace != null) "$done · 执行详情" else done
        val clickable = if (onOpenTrace != null) {
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenTrace)
        } else {
            Modifier
        }
        Label(
            text,
            9f,
            V2Colors.ink(.30f),
            mono = true,
            modifier = modifier
                .testTag(V2StreamTags.STATUS_FOOTER)
                .then(clickable)
                .padding(start = 4.dp, top = 2.dp),
        )
    }
}

/** 组内/状态行的蓝色脉冲圆点：进行中语义，完成后由绿色对勾替换。 */
@Composable
private fun PulseDot(color: Color, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = .25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(Modifier.size(size).background(color.copy(alpha = alpha), CircleShape))
}

/** 「思考中」的扫光文字：只在进行中出现，结束后立即换成普通静态文字。 */
@Composable
private fun ShimmerLabel(text: String, size: Float) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-progress",
    )
    val brush = Brush.linearGradient(
        colors = listOf(V2Colors.ink(.42f), V2Colors.ink(.95f), V2Colors.ink(.42f)),
        start = Offset(progress * 900f - 300f, 0f),
        end = Offset(progress * 900f, 0f),
    )
    Text(
        text = text,
        style = TextStyle(
            brush = brush,
            fontSize = size.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

/**
 * 进行中的秒表：只在 active 时每秒推一次；结束后用阶段自己的 endedAtMs 冻结。
 * 不用无限动画，避免测试时钟一直有待处理帧。
 */
@Composable
private fun rememberTicker(active: Boolean): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }
    return nowMs
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

/** 思考/命令用时：不足 1 秒显示 0 秒，避免流式头几帧出现奇怪的小数。 */
internal fun formatStreamElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1000L).coerceAtMost(99_999L)
    return "${seconds}秒"
}

/** 工具输出块：深浅主题都用深色底，和参考页的等宽输出一致。 */
private val OutputBackground = Color(0xFF0A0A0B)
private val OutputForeground = Color(0xFFF4F4F5)
