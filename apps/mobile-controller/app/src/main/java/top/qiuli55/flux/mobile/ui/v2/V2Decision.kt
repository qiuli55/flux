package top.qiuli55.flux.mobile.ui.v2

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LaunchedEffect(front?.id) { expandedId = null }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 10.dp)) {
        DecisionHeader(
            pendingCount = decisions.size,
            showBack = showBack,
            onBack = onBack,
            onOpenLogs = onOpenLogs,
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(decisions.size.toString(), "待确认", V2Colors.amber, Modifier.weight(1f))
            StatCard(runningCount.toString(), "执行中", V2Colors.cyan700, Modifier.weight(1f))
            StatCard(processedCount.toString(), "今日已处理", V2Colors.ink(.85f), Modifier.weight(1f))
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardHeight: Dp = if (expandedId != null) minOf(maxHeight, 360.dp) else minOf(maxHeight, 328.dp)
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
                            onApprove = { onRequestApprove(front) },
                            onReject = { onReject(front) },
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
                modifier = Modifier.weight(1f),
                contentColor = V2Colors.rose.copy(alpha = .9f),
                icon = Icons.Outlined.Close,
                enabled = front != null,
            )
            PrimaryButton(
                text = "通过并执行",
                onClick = { front?.let(onRequestApprove) },
                modifier = Modifier.weight(1.45f),
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
            RoundIconButton(Icons.Outlined.ArrowBack, onBack, size = 32.dp, iconSize = 16.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Label("决策中心", 23f, V2Colors.ink(.95f), FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Label("$pendingCount 项操作等待你确认", 11f, V2Colors.ink(.38f))
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
            background = Color.White.copy(alpha = .55f),
            tint = V2Colors.cyan700.copy(alpha = .8f),
        )
    }
}

@Composable
private fun StatCard(number: String, label: String, color: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp), contentPadding = padding(12.dp, 8.dp)) {
        Label(number, 16f, color, FontWeight.SemiBold)
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
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var dragX by remember(decision.id) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val shape = RoundedCornerShape(28.dp)
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
                    if (dragX > threshold) onApprove() else if (dragX < -threshold) onReject()
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f },
                onHorizontalDrag = { _, amount ->
                    dragX = (dragX + amount).coerceIn(-threshold * 3f, threshold * 3f)
                },
            )
        }
    } else {
        Modifier
    }

    Column(
        modifier
            .graphicsLayer {
                if (interactive) {
                    translationX = dragX
                    rotationZ = dragX / 60f
                }
            }
            .then(swipeModifier)
            .shadow(if (interactive) 10.dp else 4.dp, shape)
            .clip(shape)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        if (interactive) listOf(Color.White, Color(0xFFF6F8FA))
                        else listOf(Color(0xFFF1F4F8), Color(0xFFE5EAF1)),
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
            .border(1.dp, V2Colors.ink(.08f), shape)
            .padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 16.dp),
    ) {
        if (interactive) {
            val stampAlpha = (abs(dragX) / threshold).coerceIn(0f, 1f)
            if (stampAlpha > 0f) {
                Box(Modifier.fillMaxWidth()) {
                    Stamp(
                        "批准",
                        V2Colors.emerald700,
                        V2Colors.emerald.copy(alpha = .7f * stampAlpha),
                        stampAlpha,
                        Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 44.dp).graphicsLayer { rotationZ = -13f },
                    )
                    Stamp(
                        "拒绝",
                        V2Colors.rose700,
                        V2Colors.rose500.copy(alpha = .7f * stampAlpha),
                        stampAlpha,
                        Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 44.dp).graphicsLayer { rotationZ = 13f },
                    )
                }
            }
        }

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
            if (!expanded) {
                Label(
                    decision.reason,
                    12f,
                    V2Colors.ink(.58f),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            } else {
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
            Row(
                Modifier.clickable { if (interactive) onToggleDetail() }.padding(vertical = 2.dp),
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
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = .55f))
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

