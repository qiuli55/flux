package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun V2DecisionScreen(
    decisions: List<V2Decision>,
    runningCount: Int,
    processedCount: Int,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onRequestApprove: (V2Decision) -> Unit,
    onReject: (V2Decision) -> Unit,
) {
    val front = decisions.firstOrNull()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var intent by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(front?.id) {
        expandedId = null
        intent = 0f
    }
    val rejectScale by animateFloatAsState(
        targetValue = when {
            intent < -8f -> 1.02f
            intent > 8f -> .98f
            else -> 1f
        },
        animationSpec = tween(160),
        label = "reject-scale",
    )
    val approveScale by animateFloatAsState(
        targetValue = when {
            intent > 8f -> 1.02f
            intent < -8f -> .98f
            else -> 1f
        },
        animationSpec = tween(160),
        label = "approve-scale",
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 10.dp)) {
        DecisionHeader(
            pendingCount = decisions.size,
            showBack = showBack,
            onBack = onBack,
            onOpenLogs = onOpenLogs,
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(decisions.size, "待确认", V2Colors.amber, Modifier.weight(1f))
            StatCard(runningCount, "执行中", V2Colors.cyan700, Modifier.weight(1f))
            StatCard(processedCount, "今日已处理", V2Colors.ink(.85f), Modifier.weight(1f))
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val targetHeight: Dp = if (expandedId != null) minOf(maxHeight, 420.dp) else minOf(maxHeight, 328.dp)
            val cardHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = spring(stiffness = 360f, dampingRatio = .42f),
                label = "decision-card-height",
            )
            if (decisions.isEmpty()) {
                EmptyDecision()
            } else {
                Box(Modifier.fillMaxWidth().height(cardHeight)) {
                    if (decisions.size > 2) {
                        DecisionCard(
                            decision = decisions[2],
                            interactive = false,
                            expanded = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = 40.dp.toPx()
                                    scaleX = .92f
                                    scaleY = .92f
                                    alpha = .65f
                                },
                            onToggleDetail = {},
                            onApprove = {},
                            onReject = {},
                        )
                    }
                    if (decisions.size > 1) {
                        DecisionCard(
                            decision = decisions[1],
                            interactive = false,
                            expanded = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = 20.dp.toPx()
                                    scaleX = .96f
                                    scaleY = .96f
                                },
                            onToggleDetail = {},
                            onApprove = {},
                            onReject = {},
                        )
                    }
                    if (front != null) {
                        DecisionCard(
                            decision = front,
                            interactive = true,
                            expanded = expandedId == front.id,
                            modifier = Modifier.fillMaxSize(),
                            onToggleDetail = {
                                expandedId = if (expandedId == front.id) null else front.id
                            },
                            onIntent = { intent = it },
                            onApprove = {
                                intent = 0f
                                onRequestApprove(front)
                            },
                            onReject = {
                                intent = 0f
                                onReject(front)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "拒绝",
                onClick = { front?.let(onReject) },
                modifier = Modifier.weight(1f).graphicsLayer {
                    scaleX = rejectScale
                    scaleY = rejectScale
                },
                contentColor = V2Colors.rose.copy(alpha = .9f),
                icon = Icons.Outlined.Close,
                enabled = front != null,
            )
            PrimaryButton(
                text = "通过并执行",
                onClick = { front?.let(onRequestApprove) },
                modifier = Modifier.weight(1.45f).graphicsLayer {
                    scaleX = approveScale
                    scaleY = approveScale
                },
                icon = Icons.Outlined.VerifiedUser,
                enabled = front != null,
            )
        }
        Spacer(Modifier.height(8.dp))
        Label(
            "左滑拒绝 · 右滑通过 · 高危操作需长按确认",
            9f,
            V2Colors.ink(.28f),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DecisionHeader(
    pendingCount: Int,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        if (showBack) {
            RoundIconButton(
                Icons.Outlined.ArrowBack,
                onBack,
                size = 32.dp,
                iconSize = 16.dp,
                contentDescription = "返回",
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Label("决策中心", 23f, V2Colors.ink(.95f), FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                V2RollingNumber(pendingCount, 11f, V2Colors.ink(.38f), FontWeight.Normal)
                Label(" 项操作等待你确认", 11f, V2Colors.ink(.38f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(V2Colors.emerald)
            Spacer(Modifier.width(6.dp))
            Label("实时", 9.5f, V2Colors.ink(.30f))
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            icon = Icons.Outlined.ShowChart,
            onClick = onOpenLogs,
            size = 32.dp,
            iconSize = 14.dp,
            background = V2Colors.surface(.55f),
            tint = V2Colors.cyan700.copy(alpha = .8f),
        )
    }
}

@Composable
private fun StatCard(number: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp), contentPadding = padding(12.dp, 8.dp)) {
        V2RollingNumber(number, 16f, color)
        Spacer(Modifier.height(4.dp))
        Label(label, 9f, V2Colors.ink(.30f))
    }
}

private fun padding(horizontal: Dp, vertical: Dp) =
    androidx.compose.foundation.layout.PaddingValues(horizontal = horizontal, vertical = vertical)

@Composable
private fun EmptyDecision() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label("没有待确认的决策", 13f, V2Colors.ink(.42f))
        Spacer(Modifier.height(4.dp))
        Label("新的高危操作会实时出现在这里", 10f, V2Colors.ink(.28f))
    }
}

@Composable
private fun DecisionCard(
    decision: V2Decision,
    interactive: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggleDetail: () -> Unit,
    onIntent: (Float) -> Unit = {},
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val dragX = remember(decision.id) { Animatable(0f) }
    val fade = remember(decision.id) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalV2Haptics.current
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val shape = RoundedCornerShape(28.dp)
    var armed by remember(decision.id) { mutableStateOf(0) }
    val riskColor = when (decision.risk) {
        V2Risk.HIGH -> V2Colors.rose
        V2Risk.MEDIUM -> V2Colors.amber
        V2Risk.LOW -> V2Colors.cyan700
    }
    val tint = when (decision.risk) {
        V2Risk.HIGH -> V2Colors.rose500.copy(alpha = .12f)
        V2Risk.MEDIUM -> V2Colors.amber500.copy(alpha = .12f)
        V2Risk.LOW -> V2Colors.cyan500.copy(alpha = .10f)
    }

    val swipeModifier = if (interactive) {
        Modifier.pointerInput(decision.id) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val current = dragX.value
                    onIntent(0f)
                    armed = 0
                    when {
                        current > threshold -> scope.launch {
                            dragX.animateTo(0f, spring(stiffness = 420f, dampingRatio = .32f))
                            onApprove()
                        }
                        current < -threshold -> scope.launch {
                            fade.animateTo(0f, tween(160))
                            dragX.animateTo(-420f, tween(220))
                            onReject()
                        }
                        else -> scope.launch {
                            dragX.animateTo(0f, spring(stiffness = 420f, dampingRatio = .4f))
                        }
                    }
                },
                onDragCancel = {
                    armed = 0
                    onIntent(0f)
                    scope.launch {
                        dragX.animateTo(0f, spring(stiffness = 420f, dampingRatio = .4f))
                    }
                },
                onHorizontalDrag = { _, amount ->
                    val next = (dragX.value + amount).coerceIn(-threshold * 4f, threshold * 4f)
                    scope.launch {
                        dragX.snapTo(next)
                        onIntent(next)
                        val zone = when {
                            next > threshold -> 1
                            next < -threshold -> -1
                            else -> 0
                        }
                        if (zone != armed) {
                            armed = zone
                            if (zone != 0) haptics?.play(V2HapticLevel.TICK)
                        }
                    }
                },
            )
        }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier
            .graphicsLayer {
                if (interactive) {
                    translationX = dragX.value
                    rotationZ = (dragX.value / 20f).coerceIn(-10f, 10f)
                    alpha = fade.value
                }
            }
            .then(swipeModifier)
            .shadow(if (interactive) 10.dp else 4.dp, shape)
            .clip(shape)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        if (interactive) listOf(V2Colors.surface, V2Colors.shellTop)
                        else listOf(V2Colors.surface(.75f), V2Colors.surface(.5f)),
                    ),
                )
                if (interactive) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(tint, Color.Transparent),
                            center = Offset(size.width * .12f, -size.height * .14f),
                            radius = size.width * 1.15f,
                        ),
                    )
                }
            }
            .border(1.dp, V2Colors.ink(.08f), shape),
    ) {
        val stampTop = maxHeight * .38f
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(riskColor.copy(alpha = .10f))
                    .border(1.dp, riskColor.copy(alpha = .30f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Security,
                    null,
                    tint = riskColor,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Label(
                    "${when (decision.risk) {
                        V2Risk.HIGH -> "高危"
                        V2Risk.MEDIUM -> "中危"
                        V2Risk.LOW -> "低危"
                    }} 操作",
                    10f,
                    riskColor,
                    FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            Label(decision.age, 10f, V2Colors.ink(.30f))
        }

        Spacer(Modifier.height(14.dp))
        Label(decision.title, 17f, V2Colors.ink(.95f), FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(demoAgent(decision.agentId).name, 10f, V2Colors.ink(.34f), maxLines = 1)
            Box(
                Modifier.padding(horizontal = 6.dp).size(2.dp).background(V2Colors.ink(.25f), RoundedCornerShape(999.dp)),
            )
            Icon(Icons.Outlined.Schedule, null, tint = V2Colors.ink(.34f), modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
            Label(decision.eta, 10f, V2Colors.ink(.34f))
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(100)),
            ) {
                Column {
                    Label(
                        decision.reason,
                        12f,
                        V2Colors.ink(.58f),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(240)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(180)),
            ) {
                Column {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(V2Colors.ink(.035f))
                            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Label(
                            decision.sql,
                            10f,
                            V2Colors.cyan800.copy(alpha = .8f),
                            mono = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    decision.notes.forEach { note ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Box(
                                Modifier.padding(top = 6.dp).size(3.dp).background(V2Colors.ink(.22f), RoundedCornerShape(999.dp)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Label(note, 10f, V2Colors.ink(.38f), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            val chevron by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(200),
                label = "detail-chevron",
            )
            Row(
                Modifier
                    .v2Pressable(enabled = interactive, pressScale = .98f) {
                        if (interactive) onToggleDetail()
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    if (expanded) "收起详情" else "查看执行详情",
                    10f,
                    V2Colors.cyan700.copy(alpha = .75f),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    null,
                    tint = V2Colors.cyan700.copy(alpha = .75f),
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V2Colors.surface(.55f))
                .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(12.dp)),
        ) {
            decision.impact.forEachIndexed { index, impact ->
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Label(
                        impact.value,
                        13f,
                        if (impact.danger) V2Colors.rose else V2Colors.ink(.85f),
                        FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Label(impact.key, 9f, V2Colors.ink(.30f))
                }
                if (index != decision.impact.lastIndex) {
                    Box(Modifier.width(1.dp).height(34.dp).background(V2Colors.ink(.06f)).align(Alignment.CenterVertically))
                }
            }
        }
        }
        if (interactive) {
            val approveAlpha = (dragX.value / threshold).coerceIn(0f, 1f)
            val rejectAlpha = (-dragX.value / threshold).coerceIn(0f, 1f)
            if (approveAlpha > 0f) {
                Stamp(
                    text = "批准",
                    color = V2Colors.emerald700,
                    border = V2Colors.emerald.copy(alpha = .7f * approveAlpha),
                    alpha = approveAlpha,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 22.dp, top = stampTop)
                        .graphicsLayer { rotationZ = -13f },
                )
            }
            if (rejectAlpha > 0f) {
                Stamp(
                    text = "拒绝",
                    color = V2Colors.rose700,
                    border = V2Colors.rose500.copy(alpha = .7f * rejectAlpha),
                    alpha = rejectAlpha,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 22.dp, top = stampTop)
                        .graphicsLayer { rotationZ = 13f },
                )
            }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, border: Color, alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .border(2.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Label(text, 11f, color, FontWeight.Bold)
    }
}

