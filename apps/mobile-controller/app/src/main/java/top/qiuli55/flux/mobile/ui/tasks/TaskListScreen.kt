package top.qiuli55.flux.mobile.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.ui.EmptyHint
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.MonoText
import top.qiuli55.flux.mobile.ui.StatusChip
import top.qiuli55.flux.mobile.ui.fluxFieldColors
import top.qiuli55.flux.mobile.ui.shortTime
import top.qiuli55.flux.mobile.ui.taskStatusStyle
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/** 任务列表（Solo 任务执行中心的移动视图）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onOpenTask: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("任务", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Default.Terminal, contentDescription = "终端", tint = FluxColors.text2)
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = FluxColors.text2)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = FluxColors.text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FluxColors.bg,
                    titleContentColor = FluxColors.text,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                containerColor = FluxColors.accent,
                contentColor = FluxColors.text,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建任务") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(state.error!!)
            }
            if (state.loading) {
                Spacer(Modifier.height(16.dp))
                LoadingRow("正在读取任务…")
            } else if (state.tasks.isEmpty()) {
                EmptyHint("还没有任务。点右下角「新建任务」，写清楚要做什么，Agent 的改动会以提案形式等你审核。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(task = task, onClick = { onOpenTask(task.id) })
                    }
                }
            }
        }
    }

    if (showCreate) {
        NewTaskDialog(
            creating = state.creating,
            error = state.error,
            onDismiss = { if (!state.creating) showCreate = false },
            onCreate = { description, mode ->
                viewModel.createTask(description, mode) { taskId ->
                    showCreate = false
                    onOpenTask(taskId)
                }
            },
        )
    }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit) {
    val (label, color) = taskStatusStyle(task.status)
    FluxCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = task.description, style = MaterialTheme.typography.bodyLarge, color = FluxColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StatusChip(label, color, Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(text = task.id.take(8), color = FluxColors.text3, modifier = Modifier.weight(1f))
            MonoText(text = shortTime(task.createdAt), color = FluxColors.text3)
        }
        if (task.runId != null) {
            Spacer(Modifier.height(4.dp))
            MonoText(text = "run ${task.runId.take(8)}", color = FluxColors.text3)
        }
    }
}

/** 新建任务：只收"要做什么"与决策方式，不在这里配置 Agent（服务端按档案选默认 runtime）。 */
@Composable
private fun NewTaskDialog(
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FluxColors.panel,
        title = { Text("新建任务", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("要做什么") },
                    minLines = 3,
                    colors = fluxFieldColors(),
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(text = "遇到可选方案时", style = MaterialTheme.typography.labelSmall, color = FluxColors.text3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "auto", onClick = { mode = "auto" }, enabled = !creating, label = { Text("AI 自己定") })
                    FilterChip(selected = mode == "manual", onClick = { mode = "manual" }, enabled = !creating, label = { Text("停下来问我") })
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    ErrorBanner(error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(description, mode) },
                enabled = description.isNotBlank() && !creating,
            ) { Text(if (creating) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}