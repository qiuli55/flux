package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ 背景

fun Modifier.outerBackground(): Modifier = drawBehind {
    drawRect(Color(0xFFE9EDF3))
    drawRect(
        brush = Brush.radialGradient(
            listOf(V2Colors.accent.copy(alpha = .10f), Color.Transparent),
            center = Offset(size.width * .5f, 0f),
            radius = size.height * .62f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            listOf(Color(0xFF6366F1).copy(alpha = .08f), Color.Transparent),
            center = Offset(size.width, size.height * 1.1f),
            radius = size.width * .8f,
        ),
    )
}

fun Modifier.shellBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(V2Colors.shellTop, V2Colors.shellBottom)))
    drawRect(
        brush = Brush.radialGradient(
            listOf(V2Colors.accent.copy(alpha = .07f), Color.Transparent),
            center = Offset(size.width * .5f, 0f),
            radius = size.height * .5f,
        ),
    )
}

// ------------------------------------------------------------------ 面板

/** demo 的 `.glass`：白色渐变 + 发丝线 + 极轻阴影。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(4.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .86f), Color.White.copy(alpha = .64f)),
                ),
            )
            .border(1.dp, V2Colors.line, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** demo 的 `.account-card`：纯白底、20dp 圆角、更淡的描边。 */
@Composable
fun WhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .shadow(3.dp, shape)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, V2Colors.lineSoft, shape),
        content = content,
    )
}

@Composable
fun ThinDivider(color: Color = V2Colors.line, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

// ------------------------------------------------------------------ 文本 / 胶囊

@Composable
fun Label(
    text: String,
    size: Float,
    color: Color = V2Colors.ink,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = (size * 1.5f).sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        textAlign = textAlign,
    )
}

@Composable
fun V2Chip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    background: Color = color.copy(alpha = .10f),
    border: Color = color.copy(alpha = .24f),
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 10f, color, FontWeight.Medium)
    }
}

@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    Box(modifier.size(6.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(
                color = color.copy(alpha = .38f * (1f - pulse)),
                radius = size.minDimension / 2f * (0.45f + pulse * .5f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Box(Modifier.size(6.dp).background(color, CircleShape))
    }
}

// ------------------------------------------------------------------ 头像 / 图标

@Composable
fun Avatar(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    icon: ImageVector = Icons.Outlined.SmartToy,
    online: Boolean = false,
    ringColor: Color = V2Colors.shellTop,
) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(tint.copy(alpha = .094f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * .41f),
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .background(V2Colors.cyan500, CircleShape)
                    .border(2.dp, ringColor, CircleShape),
            )
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 16.dp,
    background: Color = Color.White.copy(alpha = .55f),
    tint: Color = V2Colors.ink(.6f),
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, V2Colors.lineSoft, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun DeviceIcon(kind: V2DeviceKind, tint: Color = V2Colors.ink(.45f), size: Dp = 16.dp) {
    Icon(
        imageVector = when (kind) {
            V2DeviceKind.LAPTOP -> Icons.Outlined.Laptop
            V2DeviceKind.DESKTOP -> Icons.Outlined.Computer
            V2DeviceKind.PHONE -> Icons.Outlined.Smartphone
        },
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

@Composable
fun BotIcon(tint: Color, size: Dp = 18.dp) {
    Icon(Icons.Outlined.SmartToy, null, tint = tint, modifier = Modifier.size(size))
}

// ------------------------------------------------------------------ 按钮 / 开关 / 进度

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) V2Colors.cyan400 else V2Colors.cyan400.copy(alpha = .35f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = V2Colors.actionInk, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Label(text, 12.5f, V2Colors.actionInk, FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = V2Colors.ink(.58f),
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = .55f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Label(text, 12.5f, contentColor, FontWeight.Medium)
    }
}

@Composable
fun LightSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) V2Colors.cyan500 else V2Colors.ink(.10f))
            .border(1.dp, if (checked) V2Colors.cyan700.copy(alpha = .4f) else V2Colors.ink(.10f), RoundedCornerShape(999.dp))
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(top = 3.dp, start = if (checked) 26.dp else 3.dp)
                .size(22.dp)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
fun ProgressBar(
    progress: Int,
    tint: Color = V2Colors.cyan500,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(V2Colors.ink(.07f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth((progress / 100f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(tint, RoundedCornerShape(999.dp)),
        )
    }
}

// ------------------------------------------------------------------ 底部弹层

/** 平板下弹层居中收窄，手机保持通栏。 */
val LocalSheetMaxWidth = staticCompositionLocalOf<Dp?> { null }

@Composable
fun SheetFrame(
    onDismiss: () -> Unit,
    maxHeightFraction: Float = .82f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetMaxWidth = LocalSheetMaxWidth.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(V2Colors.scrim)
                .clickable(interactionSource = interaction, indication = null, onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (sheetMaxWidth != null) Modifier.widthIn(max = sheetMaxWidth)
                    else Modifier,
                )
                .fillMaxWidth()
                .fillMaxHeight(maxHeightFraction)
                .shadow(24.dp, V2SheetShape)
                .clip(V2SheetShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFF8F9FB), Color(0xFFF1F3F6))))
                .border(1.dp, V2Colors.line, V2SheetShape),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(V2Colors.ink(.20f), RoundedCornerShape(999.dp)),
                )
            }
            content()
        }
    }
}

@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    centered: Boolean = false,
) {
    if (centered) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Label(title, 17f, V2Colors.ink(.95f), FontWeight.SemiBold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Label(subtitle, 11f, V2Colors.ink(.40f))
                }
            }
            RoundIconButton(
                icon = Icons.Outlined.Close,
                onClick = onClose,
                size = 36.dp,
                iconSize = 16.dp,
                background = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Label(title, 15f, V2Colors.ink(.95f), FontWeight.SemiBold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Label(subtitle, 11f, V2Colors.ink(.40f))
                }
            }
            RoundIconButton(
                icon = Icons.Outlined.Close,
                onClick = onClose,
                size = 28.dp,
                iconSize = 14.dp,
                background = Color.White.copy(alpha = .6f),
                tint = V2Colors.ink(.5f),
            )
        }
    }
}

// ------------------------------------------------------------------ 长按确认环

/**
 * demo 的高危确认：按住进度环走满才批准，松手立即取消。
 * 标准 0.8 秒 / 严格 1.6 秒，由调用方传入。
 */
@Composable
fun HoldToConfirm(
    durationMs: Int,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var progress by remember { mutableStateOf(0f) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(108.dp)
                .pointerInput(durationMs) {
                    detectTapGestures(
                        onPress = {
                            var done = false
                            val start = android.os.SystemClock.elapsedRealtime()
                            val job = scope.launch {
                                while (true) {
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                                    val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                                    progress = fraction
                                    if (fraction >= 1f) {
                                        done = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onConfirmed()
                                        break
                                    }
                                    kotlinx.coroutines.delay(16)
                                }
                            }
                            tryAwaitRelease()
                            if (!done) {
                                job.cancel()
                                progress = 0f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawArc(
                    color = Color(0xFF0F172A).copy(alpha = .07f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = V2Colors.rose500,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = .86f))
                    .border(1.dp, V2Colors.ink(.10f), CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Security,
                    null,
                    tint = V2Colors.rose,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Label(
                    if (progress >= 1f) "已确认" else "按住确认",
                    9f,
                    V2Colors.ink(.38f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Label(
            "按住 ${if (durationMs >= 1600) "1.6" else "0.8"} 秒批准 · 松手即取消",
            11f,
            V2Colors.ink(.38f),
        )
    }
}

fun Float.toDp(density: Float): Dp = (this / density).dp
