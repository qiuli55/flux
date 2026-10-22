package top.qiuli55.flux.mobile.ui.v2

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
) {
    var filter by remember { mutableStateOf(QueueFilter.ALL) }
    val running = tasks.count { it.status == V2TaskStatus.RUNNING }
    val waiting = tasks.count { it.status == V2TaskStatus.WAITING }
    val finished = tasks.count {
        it.status == V2TaskStatus.DONE ||
            it.status == V2TaskStatus.FAILED ||
            it.status == V2TaskStatus.CANCELED
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
                RoundIconButton(Icons.Outlined.ArrowBack, onBack, size = 32.dp, iconSize = 16.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Label("任务队列", 23f, V2Colors.ink(.95f), FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Label("$running 个执行中 · $waiting 个等待", 11f, V2Colors.ink(.38f))
            }
            Label(
                "清空已完成",
                10f,
                V2Colors.ink(.38f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClearDone)
                    .padding(4.dp),
            )
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                icon = Icons.Outlined.ShowChart,
                onClick = onOpenLogs,
                size = 32.dp,
                iconSize = 14.dp,
                background = Color.White.copy(alpha = .55f),
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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(visible, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onCancel = { onCancel(task) },
                        onRetry = { onRetry(task) },
                        onOpen = onOpenTask?.let { open -> { open(task) } },
                    )
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
            .background(if (selected) V2Colors.cyan500.copy(alpha = .10f) else Color.White.copy(alpha = .35f))
            .border(
                1.dp,
                if (selected) V2Colors.accent.copy(alpha = .20f) else V2Colors.ink(.07f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, 11f, if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f))
        Spacer(Modifier.width(6.dp))
        Label(
            count.toString(),
            10f,
            if (selected) V2Colors.cyan700 else V2Colors.ink(.28f),
            mono = true,
        )
    }
}

@Composable
private fun TaskCard(task: V2Task, onCancel: () -> Unit, onRetry: () -> Unit, onOpen: (() -> Unit)? = null) {
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
            .clickable(onClick = onClick)
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
