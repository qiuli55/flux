package top.qiuli55.flux.mobile.ui.v2

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class QueueFilter(val label: String) {
    ALL("全部"),
    WAITING("等待"),
    RUNNING("执行中"),
    FINISHED("已结束"),
}

@Composable
fun V2QueueScreen(
    tasks: List<V2Task>,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onClearDone: () -> Unit,
    onCancel: (V2Task) -> Unit,
    onRetry: (V2Task) -> Unit,
    onOpenTask: ((V2Task) -> Unit)? = null,
    onRequestDelete: (V2Task) -> Unit = {},
) {
    var filter by remember { mutableStateOf(QueueFilter.ALL) }
    var openTaskId by remember { mutableStateOf<String?>(null) }
    var openTaskDir by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val haptics = LocalV2Haptics.current
    val previousStatuses = remember { mutableStateMapOf<String, V2TaskStatus>() }
    var flashId by remember { mutableStateOf<String?>(null) }
    val running = tasks.count { it.status == V2TaskStatus.RUNNING }
    val waiting = tasks.count { it.status == V2TaskStatus.WAITING }
    val finished = tasks.count {
        it.status == V2TaskStatus.DONE ||
            it.status == V2TaskStatus.FAILED ||
            it.status == V2TaskStatus.CANCELED
    }

    // 任务从非完成态翻到完成态时闪一下淡青色，并给一次成功触感和提示
    LaunchedEffect(tasks) {
        val justDone = tasks.filter { task ->
            val previous = previousStatuses[task.id]
            previous != null && previous != V2TaskStatus.DONE && task.status == V2TaskStatus.DONE
        }
        // 先把新一轮状态落账，避免 delay 期间重复判定
        tasks.forEach { previousStatuses[it.id] = it.status }
        val done = justDone.lastOrNull() ?: return@LaunchedEffect
        flashId = done.id
        haptics?.play(V2HapticLevel.SUCCESS)
        Toast.makeText(context, "任务已完成 · ${done.title}", Toast.LENGTH_SHORT).show()
        delay(360)
        if (flashId == done.id) flashId = null
    }

    val visible = when (filter) {
        QueueFilter.ALL -> tasks
        QueueFilter.WAITING -> tasks.filter { it.status == V2TaskStatus.WAITING }
        QueueFilter.RUNNING -> tasks.filter { it.status == V2TaskStatus.RUNNING }
        QueueFilter.FINISHED -> tasks.filter {
            it.status == V2TaskStatus.DONE ||
                it.status == V2TaskStatus.FAILED ||
                it.status == V2TaskStatus.CANCELED
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 10.dp)) {
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
                Label("任务队列", 23f, V2Colors.ink(.95f), FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V2RollingNumber(running, 11f, V2Colors.ink(.38f), FontWeight.Normal)
                    Label(" 个执行中 · ", 11f, V2Colors.ink(.38f))
                    V2RollingNumber(waiting, 11f, V2Colors.ink(.38f), FontWeight.Normal)
                    Label(" 个等待", 11f, V2Colors.ink(.38f))
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .v2Pressable(pressScale = .97f, onClick = onClearDone)
                    .padding(4.dp),
            ) {
                Label("清空已完成", 10f, V2Colors.ink(.38f))
            }
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                icon = Icons.Outlined.ShowChart,
                onClick = onOpenLogs,
                size = 32.dp,
                iconSize = 14.dp,
                background = V2Colors.surface(.55f),
                tint = V2Colors.cyan700.copy(alpha = .8f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChipItem("全部", tasks.size, filter == QueueFilter.ALL) { filter = QueueFilter.ALL }
            FilterChipItem("等待", waiting, filter == QueueFilter.WAITING) { filter = QueueFilter.WAITING }
            FilterChipItem("执行中", running, filter == QueueFilter.RUNNING) { filter = QueueFilter.RUNNING }
            FilterChipItem("已结束", finished, filter == QueueFilter.FINISHED) { filter = QueueFilter.FINISHED }
        }

        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Label("这个筛选下没有任务", 11f, V2Colors.ink(.35f))
        } else {
            V2PullRefresh(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onRefresh = { delay(650) },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(visible, key = { _, task -> task.id }) { index, task ->
                        val open = openTaskId == task.id
                        V2Stagger(index) {
                            V2SwipeRow(
                                open = open,
                                openDir = if (open) openTaskDir else -1,
                                onOpenChange = { isOpen, dir ->
                                    if (isOpen) {
                                        openTaskId = task.id
                                        openTaskDir = dir
                                    } else if (openTaskId == task.id) {
                                        openTaskId = null
                                    }
                                },
                                leftAction = {
                                    SwipeQueueAction(
                                        label = "详情",
                                        icon = Icons.Outlined.ShowChart,
                                        color = V2Colors.cyan700,
                                        background = V2Colors.cyan500.copy(alpha = .16f),
                                    ) {
                                        openTaskId = null
                                        onOpenTask?.invoke(task)
                                    }
                                },
                                rightAction = {
                                    when (task.status) {
                                        V2TaskStatus.RUNNING, V2TaskStatus.WAITING -> SwipeQueueAction(
                                            label = "停止",
                                            icon = Icons.Outlined.Close,
                                            color = V2Colors.rose700,
                                            background = V2Colors.rose500.copy(alpha = .16f),
                                        ) {
                                            openTaskId = null
                                            onCancel(task)
                                        }
                                        V2TaskStatus.FAILED -> SwipeQueueAction(
                                            label = "重试",
                                            icon = Icons.Outlined.Refresh,
                                            color = V2Colors.cyan700,
                                            background = V2Colors.cyan500.copy(alpha = .16f),
                                        ) {
                                            openTaskId = null
                                            onRetry(task)
                                        }
                                        else -> SwipeQueueAction(
                                            label = "删除",
                                            icon = Icons.Outlined.Delete,
                                            color = V2Colors.rose700,
                                            background = V2Colors.rose500.copy(alpha = .16f),
                                        ) {
                                            openTaskId = null
                                            onRequestDelete(task)
                                        }
                                    }
                                },
                            ) {
                                TaskCard(
                                    task = task,
                                    flash = flashId == task.id,
                                    onCancel = { onCancel(task) },
                                    onRetry = { onRetry(task) },
                                    onOpen = onOpenTask?.let { openTask ->
                                        {
                                            if (openTaskId == task.id) openTaskId = null else openTask(task)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) V2Colors.cyan500.copy(alpha = .10f) else V2Colors.surface(.35f))
            .border(
                1.dp,
                if (selected) V2Colors.accent.copy(alpha = .20f) else V2Colors.ink(.07f),
                RoundedCornerShape(999.dp),
            )
            .v2Pressable(pressScale = .965f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, 11f, if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f))
        Spacer(Modifier.width(6.dp))
        V2RollingNumber(
            value = count,
            size = 10f,
            color = if (selected) V2Colors.cyan700 else V2Colors.ink(.28f),
            weight = FontWeight.Normal,
            mono = true,
        )
    }
}

/** 滑动行揭示的单个操作：84dp 宽，整行可点，按压有反馈。 */
@Composable
private fun SwipeQueueAction(
    label: String,
    icon: ImageVector,
    color: Color,
    background: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .v2Pressable(pressScale = .95f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(3.dp))
        Label(label, 9.5f, color)
    }
}

@Composable
private fun TaskCard(
    task: V2Task,
    flash: Boolean = false,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val flashAlpha by animateFloatAsState(
        targetValue = if (flash) 1f else 0f,
        animationSpec = tween(if (flash) 120 else 480),
        label = "task-flash",
    )
    val dotColor = when (task.status) {
        V2TaskStatus.RUNNING -> V2Colors.cyan500
        V2TaskStatus.WAITING -> V2Colors.amber500
        V2TaskStatus.FAILED -> V2Colors.rose500
        V2TaskStatus.DONE -> V2Colors.emerald
        V2TaskStatus.CANCELED -> V2Colors.ink(.30f)
    }
    val statusText = when (task.status) {
        // 服务端没有进度字段：-1 表示执行中但进度未知
        V2TaskStatus.RUNNING -> if (task.progress < 0) "执行中" else "${task.progress}%"
        V2TaskStatus.WAITING -> "排队中"
        V2TaskStatus.FAILED -> "可重试"
        V2TaskStatus.DONE -> "已完成"
        V2TaskStatus.CANCELED -> "已取消"
    }
    val canCancel = task.status == V2TaskStatus.RUNNING || task.status == V2TaskStatus.WAITING

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = 1f + flashAlpha * .012f
                scaleX = scale
                scaleY = scale
            },
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (task.status == V2TaskStatus.RUNNING) {
                    LiveDot(dotColor)
                } else {
                    Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(task.title, 12.5f, V2Colors.ink(.88f), maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Label(
                        "${demoAgent(task.agentId).name} · ${task.priority} · $statusText",
                        9.5f,
                        V2Colors.ink(.32f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (canCancel) {
                    ActionChip("取消", V2Colors.ink(.45f), V2Colors.ink(.08f), Color.White.copy(alpha = .35f), onCancel)
                } else {
                    ActionChip(
                        "重试",
                        V2Colors.cyan700,
                        V2Colors.accent.copy(alpha = .25f),
                        V2Colors.cyan500.copy(alpha = .08f),
                        onRetry,
                        icon = true,
                    )
                }
            }
            if (task.status == V2TaskStatus.RUNNING && task.progress >= 0) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(task.progress, V2Colors.cyan500)
            }
            if (!task.note.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Label(task.note, 9.5f, V2Colors.rose.copy(alpha = .75f), modifier = Modifier.fillMaxWidth())
            }
        }
        if (flashAlpha > .01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(V2Colors.cyan500.copy(alpha = .10f * flashAlpha))
                    .border(
                        1.dp,
                        V2Colors.accent.copy(alpha = .42f * flashAlpha),
                        RoundedCornerShape(16.dp),
                    ),
            )
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    color: Color,
    borderColor: Color,
    background: Color,
    onClick: () -> Unit,
    icon: Boolean = false,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .v2Pressable(pressScale = .94f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                Icons.Outlined.Refresh,
                null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Label(text, 10f, color)
    }
}
