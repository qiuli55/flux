package top.qiuli55.flux.mobile.ui.v2

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.data.TaskContextUsage

/**
 * 输入栏右侧的小控件：Agent 官方图标、上下文占用环、模型线框立方体。
 *
 * 这些控件只改变输入栏的排列与入口，不改变对话页的整体信息架构；
 * 深浅色都从 [V2Colors] 取色，图标填充色由 values / values-night 资源切换。
 */

/** runtime → 官方图标资源；未知 runtime 返回 null，由调用方回落中性占位。 */
@DrawableRes
internal fun agentRuntimeIconRes(runtime: String): Int? = when (runtime.trim().lowercase()) {
    "claude-code", "claude_code", "claude" -> R.drawable.ic_agent_claude_code
    "codex" -> R.drawable.ic_agent_codex
    "opencode" -> R.drawable.ic_agent_opencode
    else -> null
}

/** Agent 官方图标（带圆角底），未知 runtime 用中性机器人图标，不伪造官方标。 */
@Composable
internal fun AgentRuntimeIcon(
    runtime: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val res = agentRuntimeIconRes(runtime)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * .28f))
            .background(V2Colors.ink(.06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(size * .72f),
            )
        } else {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = V2Colors.ink(.52f),
                modifier = Modifier.size(size * .62f),
            )
        }
    }
}

/**
 * Agent 触发器：只画官方运行时图标，不再显示 Agent 名称。
 *
 * 视觉尺寸向旁边的权限盾牌靠拢（外 48dp 触摸目标 / 内 32dp 圆钮）；
 * 名称放在 contentDescription 和点开后的下拉菜单里，避免名字后拖出一长截空尾。
 */
@Composable
internal fun AgentPill(
    agent: V2Agent,
    pendingAgentLabel: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier
            .size(size)
            .testTag(V2ComposerTags.AGENT_BUTTON)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append("切换 Agent：${agent.name}")
                    if (pendingAgentLabel != null) append("，本轮结束后切换到 $pendingAgentLabel")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(V2Colors.ink(.05f)),
            contentAlignment = Alignment.Center,
        ) {
            AgentRuntimeIcon(
                runtime = agent.runtime,
                modifier = Modifier.testTag(V2ComposerTags.AGENT_ICON),
                size = 18.dp,
            )
        }
    }
}

/** 上下文占用环：灰色底环 + 蓝色占用弧（警告/将满时换琥珀/玫红）。 */
@Composable
internal fun ContextRingButton(
    usage: TaskContextUsage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
) {
    val percent = usage.percent.coerceIn(0, 100)
    val arcColor = contextRingColor(usage)
    Box(
        modifier
            .testTag(V2ComposerTags.CONTEXT_RING)
            .size(diameter)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "上下文占用 $percent%" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.6.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = V2Colors.ringTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // 0% 也留一小段，让「有仪表但还没用量」在视觉上可辨识。
            val sweep = if (percent <= 0) 0f else 360f * (percent / 100f)
            if (sweep > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

internal fun contextRingColor(usage: TaskContextUsage): Color = when (usage.level) {
    "full" -> V2Colors.rose
    "warning" -> V2Colors.amber
    else -> V2Colors.blue
}

/** 点环後展开的占用详情文案：等级提示 + 具体数字，不丢信息。 */
internal fun contextUsageHint(level: String): String = when (level) {
    "full" -> "已满：建议新建一段对话，并带上这段的摘要。"
    "warning" -> "快满了：再聊几轮，最早的消息会被挤出模型视野。"
    else -> "还有余量，可以继续当前的对话。"
}

/** 上下文占用详情：环形点击后的 DropdownMenu 内容。 */
@Composable
internal fun ContextInfoContent(usage: TaskContextUsage, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(224.dp)
            .testTag(V2ComposerTags.CONTEXT_INFO)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("上下文占用", 11f, V2Colors.ink(.50f), weight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Label("${usage.percent.coerceIn(0, 100)}%", 18f, contextRingColor(usage), weight = FontWeight.SemiBold, mono = true)
        }
        Spacer(Modifier.height(6.dp))
        if (usage.limit > 0) {
            Label(
                "消息 ${usage.usedMessages} / ${usage.limit} 条",
                10f,
                V2Colors.ink(.62f),
                mono = true,
            )
            Spacer(Modifier.height(2.dp))
        }
        if (usage.budgetChars > 0) {
            Label(
                "字符 ${usage.usedChars} / ${usage.budgetChars}",
                10f,
                V2Colors.ink(.62f),
                mono = true,
            )
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(6.dp))
        Label(contextUsageHint(usage.level), 10f, V2Colors.ink(.52f))
    }
}

/** 模型选择入口：线框立方体，与上下文环并排放在输入栏右侧。 */
@Composable
internal fun ModelCubeButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
) {
    val tint = if (enabled) V2Colors.ink(.62f) else V2Colors.ink(.20f)
    Box(
        modifier
            .testTag(V2ComposerTags.MODEL_BUTTON)
            .size(diameter)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "切换模型" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(21.dp)) {
            val stroke = 1.5.dp.toPx()
            val w = size.width
            val h = size.height
            val top = Offset(w * .50f, h * .06f)
            val upperLeft = Offset(w * .08f, h * .30f)
            val upperRight = Offset(w * .92f, h * .30f)
            val lowerLeft = Offset(w * .08f, h * .72f)
            val lowerRight = Offset(w * .92f, h * .72f)
            val bottom = Offset(w * .50f, h * .96f)
            val center = Offset(w * .50f, h * .50f)
            val outline = Path().apply {
                moveTo(top.x, top.y)
                lineTo(upperRight.x, upperRight.y)
                lineTo(lowerRight.x, lowerRight.y)
                lineTo(bottom.x, bottom.y)
                lineTo(lowerLeft.x, lowerLeft.y)
                lineTo(upperLeft.x, upperLeft.y)
                close()
            }
            drawPath(outline, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            // 顶部立方体的三条内棱，形成线框立方体而不是普通六边形。
            drawLine(tint, top, center, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, upperLeft, center, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, upperRight, center, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
