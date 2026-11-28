package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

// ------------------------------------------------------------------ 背景

fun Modifier.outerBackground(): Modifier = drawBehind {
    drawRect(V2Colors.bg)
    drawRect(
        brush = Brush.radialGradient(
            listOf(V2Colors.ink(.06f), Color.Transparent),
            center = Offset(size.width * .5f, 0f),
            radius = size.height * .62f,
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

/**
 * 内发光：光从边缘往里面晕开，而不是投影到外面。
 *
 * 深色底用 [BlendMode.Plus] 叠亮；浅色底加亮只会洗白，所以改成直接叠加很淡的青，
 * 视觉上同样是「面板内部被点亮」。最后补一圈 1dp 的高光描边勾出边缘。
 */
fun Modifier.innerGlow(
    shape: Shape,
    color: Color = V2Colors.cyan400,
    alpha: Float = .22f,
): Modifier = drawWithContent {
    drawContent()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawWithContent
    val blend = if (V2Colors.isDark) BlendMode.Plus else BlendMode.SrcOver
    val strength = if (V2Colors.isDark) alpha else alpha * .8f
    // 左上主光
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = Offset(w * .12f, 0f),
            radius = maxOf(w, h) * .95f,
        ),
        blendMode = blend,
    )
    // 右下补一层同样单色的光，冷暖对冲改成明暗对冲，保持「内部发光的玻璃」
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength * .7f), Color.Transparent),
            center = Offset(w, h),
            radius = maxOf(w, h) * 1.05f,
        ),
        blendMode = blend,
    )
    // 贴边的内描边：让边缘比中间亮一点点
    val rimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = strength * .9f),
            color.copy(alpha = strength * .5f),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(w, h),
    )
    val rim = Stroke(width = 1.2.dp.toPx())
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> {
            val rect = outline.roundRect
            drawRoundRect(
                brush = rimBrush,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.right - rect.left, rect.bottom - rect.top),
                cornerRadius = rect.topLeftCornerRadius,
                style = rim,
                blendMode = blend,
            )
        }
        is Outline.Generic -> drawPath(
            path = outline.path,
            brush = rimBrush,
            style = rim,
            blendMode = blend,
        )
        else -> Unit
    }
}

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
                    listOf(V2Colors.surface(.86f), V2Colors.surface(.64f)),
                ),
            )
            .innerGlow(shape)
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
            .background(V2Colors.surface)
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

// ------------------------------------------------------------------ 任务状态徽标

/** 状态色：队列卡片、详情页、对话运行条共用一套。 */
internal val V2TaskStatus.tint: Color
    get() = when (this) {
        V2TaskStatus.RUNNING -> V2Colors.cyan500
        V2TaskStatus.WAITING -> V2Colors.amber500
        V2TaskStatus.QUEUED -> V2Colors.ink(.45f)
        V2TaskStatus.FAILED -> V2Colors.rose500
        V2TaskStatus.DONE -> V2Colors.emerald
        V2TaskStatus.CANCELED -> V2Colors.ink(.30f)
    }

internal val V2TaskStatus.label: String
    get() = when (this) {
        V2TaskStatus.RUNNING -> "执行中"
        V2TaskStatus.WAITING -> "等待确认"
        V2TaskStatus.QUEUED -> "排队中"
        V2TaskStatus.DONE -> "已完成"
        V2TaskStatus.FAILED -> "失败"
        V2TaskStatus.CANCELED -> "已取消"
    }

/** 统一状态徽标：执行中带脉冲点，其余状态带实心点。 */
@Composable
fun V2TaskStatusBadge(
    status: V2TaskStatus,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
) {
    val tint = status.tint
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = .10f))
            .border(1.dp, tint.copy(alpha = .24f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            if (status == V2TaskStatus.RUNNING) {
                LiveDot(tint)
            } else {
                Box(Modifier.size(5.dp).background(tint, CircleShape))
            }
            Spacer(Modifier.width(5.dp))
        }
        Label(status.label, 10f, tint, FontWeight.Medium)
    }
}

// ------------------------------------------------------------------ 输入区主操作

/**
 * 输入区主操作按钮：空闲时是「发送」，任务运行时原地变成「停止」。
 * 对话输入区与终端命令输入区共用，避免两套尺寸/动效各写一遍。
 */
@Composable
fun V2ComposerActionButton(
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    corner: Dp = 12.dp,
    sendEnabled: Boolean = true,
    stopEnabled: Boolean = true,
    stopping: Boolean = false,
    focused: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val fly = remember { Animatable(0f) }
    val shape = RoundedCornerShape(corner)
    val flyPx = with(LocalDensity.current) { (size * .5f).toPx() }
    val iconSize = size * .45f

    val background by animateColorAsState(
        targetValue = when {
            running -> if (stopping) V2Colors.rose.copy(alpha = .45f) else V2Colors.rose
            sendEnabled || focused -> V2Colors.cyan400
            else -> V2Colors.ink(.08f)
        },
        animationSpec = tween(180),
        label = "composer-action-bg",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(background, background.copy(alpha = .74f)),
                ),
            )
            .innerGlow(shape, color = Color.White, alpha = .50f)
            .v2Pressable(
                enabled = if (running) stopEnabled && !stopping else sendEnabled,
                pressScale = .94f,
                haptic = if (running) V2HapticLevel.DENY else V2HapticLevel.SUCCESS,
            ) {
                if (running) {
                    onStop()
                } else {
                    // 发送：图标沿右上角飞出，给一次明确的提交反馈。
                    scope.launch {
                        fly.snapTo(0f)
                        fly.animateTo(1f, tween(160))
                        fly.snapTo(0f)
                    }
                    onSend()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            if (stopping) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size * .4f),
                    color = Color.White,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Box(
                    Modifier
                        .size(size * .34f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = "发送",
                tint = if (sendEnabled || focused) V2Colors.actionInk else V2Colors.ink(.25f),
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        translationX = flyPx * fly.value
                        translationY = -flyPx * fly.value
                        alpha = 1f - fly.value
                    },
            )
        }
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
    background: Color = V2Colors.surface(.55f),
    tint: Color = V2Colors.ink(.6f),
    contentDescription: String? = null,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, V2Colors.lineSoft, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
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
            .v2Pressable(enabled = enabled, pressScale = .98f, onClick = onClick),
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
            .background(V2Colors.surface(.55f))
            .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp))
            .v2Pressable(enabled = enabled, pressScale = .98f, onClick = onClick),
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
    val haptics = LocalV2Haptics.current
    val reduce = LocalReduceMotion.current
    val slide by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduce) tween(120) else spring(stiffness = 380f, dampingRatio = .26f),
        label = "switch-slide",
    )
    val track by animateColorAsState(
        targetValue = if (checked) V2Colors.cyan500 else V2Colors.ink(.10f),
        animationSpec = tween(160),
        label = "switch-track",
    )
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .border(1.dp, if (checked) V2Colors.cyan700.copy(alpha = .4f) else V2Colors.ink(.10f), RoundedCornerShape(999.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics?.play(V2HapticLevel.TICK)
                onChange(!checked)
            },
    ) {
        val travel = with(LocalDensity.current) { 24.dp.toPx() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = 3.dp.toPx() + travel * slide }
                .size(22.dp)
                .background(V2Colors.surface, CircleShape),
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
    /** 只按内容高度铺开（紧凑确认层用），[maxHeightFraction] 退化为高度上限。 */
    wrapContent: Boolean = false,
    /** 是否允许上拉展开到全高；紧凑确认层不需要。 */
    expandable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetMaxWidth = LocalSheetMaxWidth.current
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val haptics = LocalV2Haptics.current
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val offsetY = remember { Animatable(0f) }
    val handleZone = with(density) { 96.dp.toPx() }
    val dismissThreshold = with(density) { 90.dp.toPx() }
    val expandThreshold = with(density) { 56.dp.toPx() }
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestReduceMotion = rememberUpdatedState(reduce)
    /** 手指累计位移（含 nested scroll 接手的部分），向下为正。 */
    var dragPx by remember { mutableFloatStateOf(0f) }
    /** 本次手势的位移是否来自「内容已到顶」的 nested scroll：此时把手不再重复驱动。 */
    var nestedDragging by remember { mutableStateOf(false) }
    /** 同一次手势只允许产生一次松手决定（手势观察者与 nested fling 可能都会触发）。 */
    var releaseHandled by remember { mutableStateOf(false) }
    /** 关闭已经决定：关闭动画期间继续挡住指针，且不重复回调。 */
    var dismissing by remember { mutableStateOf(false) }
    /** 当前还按在弹层上的手指数：移除弹层前必须归零。 */
    var pointersDown by remember { mutableIntStateOf(0) }
    val fraction by animateFloatAsState(
        targetValue = if (expanded) 1f else maxHeightFraction,
        animationSpec = if (reduce) {
            tween(V2_SHEET_REDUCE_MOTION_MS)
        } else {
            spring(
                dampingRatio = V2_SHEET_STANDARD_DAMPING,
                stiffness = V2_SHEET_STANDARD_STIFFNESS,
            )
        },
        label = "sheet-fraction",
    )

    /** 等所有手指抬起后才真正移除弹层，避免拦截层先拆、指针落到背后的页面。 */
    suspend fun dismissWhenPointersUp() {
        if (pointersDown > 0) {
            snapshotFlow { pointersDown }.first { it == 0 }
        }
        latestDismiss.value()
    }

    /** 松手后的统一出口：手势拖动与 nested scroll 共用同一套判定与动画。 */
    fun releaseSheet(velocityY: Float) {
        if (releaseHandled || dismissing) return
        releaseHandled = true
        val release = if (abs(dragPx) < 4f && abs(velocityY) < 4f) {
            V2SheetRelease.Settle
        } else {
            v2SheetReleaseAction(
                dragPx = dragPx,
                velocityY = velocityY,
                heightPx = heightPx,
                expanded = expanded,
                dismissThresholdPx = dismissThreshold,
                expandThresholdPx = expandThreshold,
            ).let { if (!expandable && it == V2SheetRelease.Expand) V2SheetRelease.Settle else it }
        }
        when (release) {
            V2SheetRelease.Collapse -> {
                expanded = false
                haptics?.play(V2HapticLevel.TICK)
            }
            V2SheetRelease.Expand -> {
                expanded = true
                haptics?.play(V2HapticLevel.TICK)
            }
            V2SheetRelease.Dismiss, V2SheetRelease.Settle -> Unit
        }
        val plan = v2SheetMotionPlan(
            release = release,
            heightPx = heightPx,
            velocityY = velocityY,
            reduceMotion = latestReduceMotion.value,
        )
        if (release == V2SheetRelease.Dismiss) dismissing = true
        scope.launch {
            offsetY.animateTo(
                targetValue = plan.targetOffsetPx,
                animationSpec = plan.spec,
                initialVelocity = plan.initialVelocity,
            )
            if (release == V2SheetRelease.Dismiss) {
                // 关闭动画走完、剩余速度清零、且手指全部抬起之后才移除弹层，
                // 不会出现拦截层先拆、指针落到背后的情况。
                offsetY.stop()
                dismissWhenPointersUp()
            } else {
                dragPx = 0f
                releaseHandled = false
            }
        }
    }

    val nestedConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只处理向上的位移：向下的先交给内容自己滚，到顶后的剩余在 onPostScroll 接手。
                if (available.y >= 0f) return Offset.Zero
                val result = v2SheetNestedDrag(
                    availableDownPx = available.y,
                    currentOffsetPx = dragPx,
                    heightPx = heightPx,
                    expanded = expanded,
                )
                if (result.consumedPx == 0f) return Offset.Zero
                dragPx = result.newOffsetPx
                scope.launch { offsetY.snapTo(result.newOffsetPx) }
                return Offset(0f, result.consumedPx)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                // 内容已经到顶，剩下的下拉由弹层接手；全部消费，绝不冒泡给祖先。
                // 即使弹层已经拖到高度上限，位移不再增加，也要把本次 available 全部吃掉，
                // 否则差额会沿 nested scroll 冒泡给背后页面。
                nestedDragging = true
                val result = v2SheetNestedDrag(
                    availableDownPx = available.y,
                    currentOffsetPx = dragPx,
                    heightPx = heightPx,
                    expanded = expanded,
                )
                dragPx = result.newOffsetPx
                scope.launch { offsetY.snapTo(result.newOffsetPx) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 弹层被拖出后，松手速度全部由弹层消化，交给同一套回位/关闭判定。
                val consumed = v2SheetConsumedVelocityY(available.y, dragPx)
                if (consumed == 0f) return Velocity.Zero
                releaseSheet(consumed)
                return Velocity(0f, consumed)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 兜底：弹层参与的这次手势，任何没被 pre 吃掉的向下速度都在这里清零。
                return if ((dragPx > 0f || nestedDragging) && available.y > 0f) {
                    Velocity(0f, available.y)
                } else {
                    Velocity.Zero
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .nestedScroll(nestedConnection)
            // 根层兜底：主 pass 之后剩余的指针事件一律吃掉，不让任何残留位移落到背后页面；
            // 同时记录还按着的手指，供移除前等待。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val changes = awaitPointerEvent(PointerEventPass.Final).changes
                        pointersDown = changes.count { it.pressed }
                        changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(V2Colors.scrim)
                .graphicsLayer {
                    alpha = 1f - (offsetY.value / heightPx.coerceAtLeast(1f)).coerceIn(0f, .55f)
                }
                .clickable(interactionSource = interaction, indication = null) {
                    if (!dismissing) {
                        dismissing = true
                        scope.launch { dismissWhenPointersUp() }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                        }
                    }
                },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (sheetMaxWidth != null) Modifier.widthIn(max = sheetMaxWidth)
                    else Modifier,
                )
                .fillMaxWidth()
                .onSizeChanged { heightPx = it.height.toFloat() }
                .then(
                    if (wrapContent) {
                        // 只占内容高度；maxHeightFraction 充当天花板，小屏也不会铺满。
                        Modifier.heightIn(max = maxHeight * fraction)
                    } else {
                        Modifier.fillMaxHeight(fraction)
                    },
                )
                .graphicsLayer { translationY = offsetY.value }
                .shadow(24.dp, V2SheetShape)
                .clip(V2SheetShape)
                .background(Brush.verticalGradient(listOf(V2Colors.shellTop, V2Colors.shellBottom)))
                .border(1.dp, V2Colors.line, V2SheetShape)
                .pointerInput(handleZone) {
                    // 观察整个弹层上的手势：把手区直接驱动位移；
                    // 内容区在顶部时的下拉由 nestedScroll 驱动，这里只负责松手判定。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val inHandle = down.position.y <= handleZone
                        var total = 0f
                        var moved = false
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        nestedDragging = false
                        releaseHandled = false
                        if (!offsetY.isRunning) dragPx = offsetY.value.coerceAtLeast(0f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // 内容滚动已消费的位移不再重复驱动弹层；
                            // nested scroll 接手时把手也让出去，避免同一段位移被算两次。
                            if (inHandle && !change.isConsumed && !nestedDragging) {
                                total += dy
                                if (abs(total) > 4f) moved = true
                                dragPx = total
                                // 上限内 1:1 跟手，向上越界走橡胶带，不再硬 clamp。
                                scope.launch {
                                    offsetY.snapTo(v2SheetDragOffset(total, heightPx, expanded))
                                }
                                change.consume()
                            }
                        }
                        val velocity = tracker.calculateVelocity().y
                        when {
                            nestedDragging -> releaseSheet(velocity)
                            inHandle && moved -> releaseSheet(velocity)
                            else -> Unit
                        }
                        nestedDragging = false
                    }
                },
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
                background = V2Colors.surface,
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
                background = V2Colors.surface(.6f),
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
    icon: ImageVector = Icons.Outlined.Security,
    holdLabel: String = "按住确认",
    hint: String? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalV2Haptics.current
    var progress by remember { mutableStateOf(0f) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(108.dp)
                .pointerInput(durationMs) {
                    detectTapGestures(
                        onPress = {
                            var done = false
                            var milestone = 0
                            val start = android.os.SystemClock.elapsedRealtime()
                            val job = scope.launch {
                                while (true) {
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                                    val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                                    progress = fraction
                                    val reached = (fraction * 3).toInt()
                                    if (reached > milestone) {
                                        milestone = reached
                                        haptics?.play(V2HapticLevel.TICK)
                                    }
                                    if (fraction >= 1f) {
                                        done = true
                                        haptics?.play(V2HapticLevel.SUCCESS)
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
                    color = V2Colors.ink(.07f),
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
                    .background(V2Colors.surface(.86f))
                    .border(1.dp, V2Colors.ink(.10f), CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = V2Colors.rose,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Label(
                    if (progress >= 1f) "已确认" else holdLabel,
                    9f,
                    V2Colors.ink(.38f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Label(
            hint ?: "按住 ${if (durationMs >= 1600) "1.6" else "0.8"} 秒批准 · 松手即取消",
            11f,
            V2Colors.ink(.38f),
        )
    }
}

fun Float.toDp(density: Float): Dp = (this / density).dp

// ------------------------------------------------------------------ 页面骨架

/** 一级页面大标题：左对齐 30sp 粗体，可选副标题与右侧操作。 */
@Composable
fun V2PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = V2Colors.ink(.96f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 36.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Label(subtitle, 13f, V2Colors.ink(.40f))
            }
        }
        trailing()
    }
}

/** 设置项左侧的浅灰圆角图标块。 */
@Composable
fun V2IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = V2Colors.ink(.70f),
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.ink(.05f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * .50f))
    }
}

/** 设置分组白卡：行与行之间用左缩进的发丝线分隔。 */
@Composable
fun V2SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhiteCard(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** 设置行：图标块 + 标题 + 右侧灰色值 + 细箭头。 */
@Composable
fun V2SettingRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    showDivider: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V2IconTile(icon)
            Spacer(Modifier.width(12.dp))
            Label(title, 15f, V2Colors.ink(.92f), FontWeight.Medium, modifier = Modifier.weight(1f))
            if (value != null) {
                Label(value, 13f, V2Colors.ink(.38f))
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = V2Colors.ink(.22f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = 16.dp)
                    .height(1.dp)
                    .background(V2Colors.lineSoft),
            )
        }
    }
}

/** 小标题（如「最近」）。 */
@Composable
fun V2SectionLabel(text: String, modifier: Modifier = Modifier) {
    Label(
        text,
        13f,
        V2Colors.ink(.42f),
        FontWeight.Medium,
        modifier = modifier.padding(start = 24.dp, top = 14.dp, bottom = 8.dp),
    )
}

// ------------------------------------------------------------------ 底部 Tab

/** 三个一级页面：对话 / Artifacts / 我的。 */
enum class V2TopTab(val label: String, val icon: ImageVector) {
    CHATS("对话", Icons.Outlined.ChatBubbleOutline),
    ARTIFACTS("Artifacts", Icons.Outlined.Dashboard),
    PROFILE("我的", Icons.Outlined.Person),
}

/** 手机底部三 Tab：选中黑、未选灰，顶部一条发丝线。 */
@Composable
fun V2BottomTabBar(
    selected: V2TopTab,
    onSelect: (V2TopTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(V2Colors.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(V2Colors.lineSoft))
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 8.dp)) {
            V2TopTab.values().forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) V2Colors.ink(.92f) else V2Colors.ink(.32f),
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Label(
                        tab.label,
                        11f,
                        if (active) V2Colors.ink(.92f) else V2Colors.ink(.36f),
                        if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 一级页面右下角的黑色圆形新建按钮。 */
@Composable
fun V2Fab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Add,
    contentDescription: String = "新建",
) {
    Box(
        modifier
            .size(56.dp)
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .background(V2Colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = V2Colors.actionInk, modifier = Modifier.size(24.dp))
    }
}

/**
 * 统一的可撤销操作确认弹窗。
 *
 * 用于误触代价高的动作（退出登录、清空日志、丢弃草稿、停止运行）。视觉沿用
 * Material3 AlertDialog，不做自定义样式；[onDismiss] 与 [onConfirm] 都由
 * 调用方负责回收自己的弹窗状态，避免这里持有外部状态。
 */
/** 确认弹窗的稳定测试锚点：多个页面复用同一套按钮语义。 */
internal object FluxConfirmTags {
    const val CONFIRM = "flux_confirm_confirm"
    const val DISMISS = "flux_confirm_dismiss"
    const val ALTERNATE = "flux_confirm_alternate"
}

@Composable
fun FluxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    /** 可选的第三个动作（例如「新开对话」）；为空时保持原来的双按钮布局。 */
    alternateLabel: String? = null,
    onAlternate: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Label(title, 16f, V2Colors.ink(.92f), FontWeight.SemiBold) },
        text = { Label(message, 12.5f, V2Colors.ink(.55f)) },
        confirmButton = {
            Row {
                if (alternateLabel != null) {
                    TextButton(
                        onClick = onAlternate,
                        modifier = Modifier.testTag(FluxConfirmTags.ALTERNATE),
                    ) {
                        Label(alternateLabel, 13f, V2Colors.rose)
                    }
                }
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(FluxConfirmTags.CONFIRM)) {
                    Label(confirmLabel, 13f, if (destructive) V2Colors.rose else V2Colors.ink(.9f))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(FluxConfirmTags.DISMISS)) {
                Label(dismissLabel, 13f, V2Colors.ink(.62f))
            }
        },
    )
}
