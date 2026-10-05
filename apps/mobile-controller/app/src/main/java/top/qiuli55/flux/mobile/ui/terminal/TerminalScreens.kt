package top.qiuli55.flux.mobile.ui.terminal

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.ui.EmptyHint
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.MonoText
import top.qiuli55.flux.mobile.ui.StatusChip
import top.qiuli55.flux.mobile.ui.fluxFieldColors
import top.qiuli55.flux.mobile.ui.shortTime
import top.qiuli55.flux.mobile.ui.terminalStatusStyle
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/** 终端会话列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalListScreen(
    viewModel: TerminalListViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("终端", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = FluxColors.text2)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = FluxColors.text2)
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
                onClick = { viewModel.createSession(onOpenSession) },
                containerColor = FluxColors.accent,
                contentColor = FluxColors.text,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (state.creating) "创建中…" else "新建会话") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "终端命令在服务端的工作区里执行。这里能看实时输出，也能停止正在跑的命令。",
                style = MaterialTheme.typography.bodySmall,
                color = FluxColors.text3,
            )
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(state.error!!)
            }
            if (state.loading) {
                Spacer(Modifier.height(14.dp))
                LoadingRow("正在读取会话…")
            } else if (state.sessions.isEmpty()) {
                EmptyHint("还没有终端会话。点右下角「新建会话」开一个。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        val (label, color) = terminalStatusStyle(session.status)
                        FluxCard(modifier = Modifier.clickable { onOpenSession(session.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MonoText(
                                    text = session.id.take(8),
                                    color = FluxColors.text,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusChip(label, color)
                            }
                            Spacer(Modifier.height(6.dp))
                            MonoText(text = session.workspaceRoot ?: "（未配置工作区）", maxLines = 1)
                            MonoText(text = shortTime(session.createdAt), color = FluxColors.text3)
                        }
                    }
                }
            }
        }
    }
}

/** 单个终端会话：实时输出 + 命令输入 + 停止。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: TerminalViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.lines.lastIndex) }
        }
    }

    val (statusLabel, statusColor) = terminalStatusStyle(state.session?.status ?: "active")
    val streamLabel = when (state.streamState) {
        "live" -> "实时"
        "connecting" -> "连接中"
        "closed" -> "已收流"
        else -> "断开"
    }
    val streamColor = when (state.streamState) {
        "live" -> FluxColors.green
        "connecting" -> FluxColors.orange
        else -> FluxColors.red
    }

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("会话", style = MaterialTheme.typography.titleMedium)
                        MonoText(text = state.session?.id?.take(8) ?: "…", color = FluxColors.text3)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = FluxColors.text2)
                    }
                },
                actions = {
                    StatusChip(streamLabel, streamColor, Modifier.padding(end = 6.dp))
                    StatusChip(statusLabel, statusColor, Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FluxColors.bg,
                    titleContentColor = FluxColors.text,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                state.error?.let { ErrorBanner(it) }
                state.notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = FluxColors.text2,
                        modifier = Modifier.clickable { viewModel.dismissNotice() },
                    )
                }
                state.streamError?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = FluxColors.orange)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.command,
                        onValueChange = viewModel::onCommandChange,
                        placeholder = { Text("命令（在工作区里执行）", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.runCommand() }),
                        colors = fluxFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::runCommand, enabled = !state.running && state.command.isNotBlank()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "执行",
                            tint = if (!state.running && state.command.isNotBlank()) FluxColors.accent else FluxColors.text3,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.stop(force = false) },
                        enabled = state.session?.status == "active",
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                    Button(
                        onClick = { viewModel.stop(force = true) },
                        enabled = state.session?.status == "active",
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = FluxColors.red.copy(alpha = 0.18f),
                            contentColor = FluxColors.red,
                        ),
                    ) { Text("Force Stop") }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                Box(Modifier.padding(16.dp)) { LoadingRow("正在读取历史输出…") }
                return@Column
            }
            if (state.lines.isEmpty()) {
                Box(Modifier.padding(16.dp)) {
                    EmptyHint("这里还没有输出。在下面输入一条命令（例如 `pwd`），输出会实时出现。")
                }
                return@Column
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(state.lines) { line ->
                    Text(
                        text = line.text.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.kind) {
                            LineKind.ECHO -> FluxColors.accent
                            LineKind.OUTPUT -> FluxColors.text2
                            LineKind.EXIT -> FluxColors.green
                            LineKind.SYSTEM -> FluxColors.text3
                        },
                        softWrap = false,
                    )
                }
            }
        }
    }
}