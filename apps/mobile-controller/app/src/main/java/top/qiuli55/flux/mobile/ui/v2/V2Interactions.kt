package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ 触感

/** 四级触感：轻点 / 成功 / 驳回 / 失败。 */
enum class V2HapticLevel { TICK, SUCCESS, DENY, FAIL }

/**
 * 真机触感控制器。
 *
 * 设置里的「触感反馈」直接控制 [enabled]，关闭后所有反馈静默；
 * 振动强度按结果分级，避免所有操作一个手感。
 */
@Stable
class V2Haptics internal constructor(private val vibrator: Vibrator?) {
    var enabled: Boolean = true

    fun play(level: V2HapticLevel) {
        if (!enabled) return
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val effect = when (level) {
            V2HapticLevel.TICK -> VibrationEffect.createOneShot(12, 96)
            V2HapticLevel.SUCCESS -> VibrationEffect.createWaveform(
                longArrayOf(0, 14, 40, 12),
                intArrayOf(0, 120, 0, 96),
                -1,
            )
            V2HapticLevel.DENY -> VibrationEffect.createWaveform(
                longArrayOf(0, 18, 46, 22),
                intArrayOf(0, 190, 0, 150),
                -1,
            )
            V2HapticLevel.FAIL -> VibrationEffect.createWaveform(
                longArrayOf(0, 26, 60, 26, 60, 26),
                intArrayOf(0, 220, 0, 180, 0, 150),
                -1,
            )
        }
        runCatching { device.vibrate(effect) }
    }
}

fun createV2Haptics(context: Context): V2Haptics {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    return V2Haptics(vibrator)
}

val LocalV2Haptics = staticCompositionLocalOf<V2Haptics?> { null }

/** 减少动态：所有位移/弹性动画降为 120ms 淡入淡出。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

// ------------------------------------------------------------------ 按压反馈

/**
 * 统一的按压反馈：按下 90ms 收缩，松手 spring(520, 32) 弹回；
 * 点击同时给一次轻点触感。没有水波纹，贴合 demo 的手感。
 */
fun Modifier.v2Pressable(
    enabled: Boolean = true,
    pressScale: Float = .97f,
    haptic: V2HapticLevel = V2HapticLevel.TICK,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = LocalV2Haptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = if (pressed) tween(90) else spring(stiffness = 520f, dampingRatio = .32f),
        label = "v2-press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
    ) {
        haptics?.play(haptic)
        onClick()
    }
}

/** 带长按的按压反馈（长按 450ms 打开删除面板等）。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.v2CombinedPressable(
    enabled: Boolean = true,
    pressScale: Float = .985f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = LocalV2Haptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = if (pressed) tween(90) else spring(stiffness = 520f, dampingRatio = .32f),
        label = "v2-press-combined",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.combinedClickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onLongClick = onLongClick?.let {
            {
                haptics?.play(V2HapticLevel.TICK)
                it()
            }
        },
        onClick = {
            haptics?.play(V2HapticLevel.TICK)
            onClick()
        },
    )
}

// ------------------------------------------------------------------ 页面转场

/**
 * 前进：新页从右侧 18px 滑入 + 淡入 220ms；返回反向 140ms；
 * 同层级切换（首页两个 Tab）只淡入淡出。减少动态时统一 120ms 淡入。
 */
@Composable
fun V2PageTransition(
    target: Any,
    depthOf: (Any) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val enterOffset = with(density) { 18.dp.roundToPx() }
    val exitOffset = with(density) { 14.dp.roundToPx() }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(120)) togetherWith fadeOut(tween(120))
            } else {
                val forward = depthOf(targetState) >= depthOf(initialState)
                if (forward) {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { enterOffset }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -exitOffset })
                } else {
                    (fadeIn(tween(140)) + slideInHorizontally(tween(140)) { -enterOffset }) togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(140)) { exitOffset })
                }
            }
        },
        label = "v2-page",
    ) { page -> content(page) }
}

// ------------------------------------------------------------------ 数字滚动

/** 读数变化时上下滚动 320ms；减少动态时淡入。 */
@Composable
fun V2RollingNumber(
    value: Int,
    size: Float,
    color: Color,
    weight: FontWeight = FontWeight.SemiBold,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(120)) togetherWith fadeOut(tween(120))
            } else {
                val up = targetState > initialState
                (fadeIn(tween(180)) + slideInVertically(tween(320)) { if (up) it / 2 else -it / 2 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(320)) { if (up) -it / 2 else it / 2 })
            }
        },
        label = "v2-number",
    ) { number ->
        Label(number.toString(), size, color, weight, mono = mono)
    }
}

// ------------------------------------------------------------------ 滑动行

/**
 * 左右滑操作行：松手超过 72px 停在 84px 揭示位，否则弹回；
 * 单开由调用方通过 [open] / [openDir] 控制（-1 = 左滑，1 = 右滑）。
 */
@Composable
fun V2SwipeRow(
    open: Boolean,
    openDir: Int,
    onOpenChange: (Boolean, Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leftAction: (@Composable BoxScope.() -> Unit)? = null,
    rightAction: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalV2Haptics.current
    val reveal = with(density) { 84.dp.toPx() }
    val maxSwipe = with(density) { 112.dp.toPx() }
    val threshold = with(density) { 72.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(open, openDir) {
        if (dragging) return@LaunchedEffect
        val target = if (open) openDir.coerceIn(-1, 1) * reveal else 0f
        offset.animateTo(target, spring(stiffness = 480f, dampingRatio = .42f))
    }

    Box(modifier) {
        if (leftAction != null) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                SwipeActionSlot(
                    offsetProvider = { offset.value },
                    revealPx = reveal,
                    startSide = true,
                    action = leftAction,
                )
            }
        }
        if (rightAction != null) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                SwipeActionSlot(
                    offsetProvider = { offset.value },
                    revealPx = reveal,
                    startSide = false,
                    action = rightAction,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(enabled, leftAction != null, rightAction != null) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragCancel = {
                            dragging = false
                            onOpenChange(false, 0)
                            scope.launch {
                                offset.animateTo(0f, spring(stiffness = 420f, dampingRatio = .4f))
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val current = offset.value
                            val dir = when {
                                current <= -threshold && rightAction != null -> -1
                                current >= threshold && leftAction != null -> 1
                                else -> 0
                            }
                            onOpenChange(dir != 0, dir)
                            if (dir != 0) haptics?.play(V2HapticLevel.TICK)
                            scope.launch {
                                offset.animateTo(
                                    dir * reveal,
                                    spring(stiffness = 480f, dampingRatio = .42f),
                                )
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val next = (offset.value + amount).coerceIn(-maxSwipe, maxSwipe)
                            scope.launch { offset.snapTo(next) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

/**
 * 滑动操作槽：只按当前露出的宽度裁切显示，避免行背景透明时把整块红色操作透出来。
 * 左滑露出右侧操作（取右边缘），右滑露出左侧操作（取左边缘）。
 */
@Composable
private fun SwipeActionSlot(
    offsetProvider: () -> Float,
    revealPx: Float,
    startSide: Boolean,
    action: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shownPx = if (startSide) {
        offsetProvider().coerceIn(0f, revealPx)
    } else {
        (-offsetProvider()).coerceIn(0f, revealPx)
    }
    if (shownPx <= .5f) return
    Box(
        Modifier
            .fillMaxHeight()
            .width(with(density) { shownPx.toDp() })
            .clipToBounds(),
        contentAlignment = if (startSide) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(with(density) { revealPx.toDp() }),
        ) {
            action()
        }
    }
}

// ------------------------------------------------------------------ 下拉刷新

/**
 * 下拉刷新：阻力 0.42，阈值 64px；成功后显示「已更新 · 刚刚」。
 * 用 nestedScroll 接列表的拖动，再在根节点旁路监听松手，保证不吞点击。
 */
@Composable
fun V2PullRefresh(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRefresh: suspend () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalV2Haptics.current
    val scope = rememberCoroutineScope()
    val maxPull = with(density) { 120.dp.toPx() }
    val threshold = with(density) { 64.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(false) }
    val latestRefresh by rememberUpdatedState(onRefresh)

    fun settle(trigger: Boolean) {
        if (refreshing) return
        if (trigger) {
            refreshing = true
            haptics?.play(V2HapticLevel.TICK)
            scope.launch {
                latestRefresh()
                refreshing = false
                refreshed = true
                haptics?.play(V2HapticLevel.SUCCESS)
                delay(1200)
                refreshed = false
                animate(pull, 0f) { value, _ -> pull = value }
            }
        } else {
            scope.launch {
                animate(
                    pull,
                    0f,
                    animationSpec = spring(stiffness = 420f, dampingRatio = .45f),
                ) { value, _ -> pull = value }
            }
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (available.y < 0 && pull > 0f) {
                    val consumed = available.y.coerceAtLeast(-pull)
                    pull += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (available.y > 0f && enabled && !refreshing) {
                    val before = pull
                    pull = (pull + available.y * .42f).coerceAtMost(maxPull)
                    if (before < threshold && pull >= threshold) {
                        haptics?.play(V2HapticLevel.TICK)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull > 0f && !refreshing) settle(pull >= threshold)
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        released = event.changes.none { it.pressed }
                    }
                    if (pull > 0f && !refreshing) settle(pull >= threshold)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pull },
        ) {
            content()
        }

        if (pull > 0f || refreshing || refreshed) {
            val alpha = if (refreshing || refreshed) 1f else (pull / threshold).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = (pull - 30.dp.toPx()).coerceAtLeast(-30.dp.toPx())
                        this.alpha = alpha
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(V2Colors.surface(.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RefreshGlyph(
                        refreshing = refreshing || refreshed,
                        pull = pull,
                        threshold = threshold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Label(
                        when {
                            refreshed -> "已更新 · 刚刚"
                            refreshing -> "正在刷新…"
                            pull >= threshold -> "松开刷新"
                            else -> "下拉刷新"
                        },
                        10f,
                        if (refreshed) V2Colors.emerald700 else V2Colors.ink(.45f),
                        FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshGlyph(refreshing: Boolean, pull: Float, threshold: Float) {
    val reduce = LocalReduceMotion.current
    val rotation = if (refreshing) {
        if (reduce) {
            0f
        } else {
            val transition = rememberInfiniteTransition(label = "refresh")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "refresh-angle",
            )
            angle
        }
    } else {
        (pull / threshold).coerceIn(0f, 1f) * 180f
    }
    Icon(
        Icons.Outlined.KeyboardArrowDown,
        contentDescription = null,
        tint = if (refreshing) V2Colors.cyan700 else V2Colors.ink(.38f),
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer { rotationZ = rotation },
    )
}

// ------------------------------------------------------------------ 错峰入场

/**
 * 长按菜单横向定位：默认左对齐气泡，靠近右边界时向内收，
 * 保证菜单整体落在内容区里（左右各留 [marginPx]）。
 */
internal fun v2ClampMenuLeftPx(
    bubbleLeftPx: Float,
    rootWidthPx: Float,
    menuWidthPx: Float,
    marginPx: Float,
): Float {
    val max = (rootWidthPx - menuWidthPx - marginPx).coerceAtLeast(marginPx)
    return bubbleLeftPx.coerceIn(marginPx, max)
}

/** 列表首屏错峰：每项延迟 28ms，最多 6 项。 */
@Composable
fun V2Stagger(index: Int, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reduce) {
            visible = true
        } else {
            delay((index.coerceAtMost(5) * 28).toLong())
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            fadeIn(tween(120))
        } else {
            fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 }
        },
    ) {
        content()
    }
}
