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
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "手机只读查看电脑上已有的终端会话；电脑离线时不可用。手机不再新建或操作终端。",
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
                EmptyHint("还没有终端会话。请先在电脑上打开会话；手机只读查看。")
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
                Text(
                    text = "只读：手机不再执行命令或停止会话。需要操作请在电脑上打开 Flux。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FluxColors.text3,
                )
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
                    EmptyHint("这里还没有输出。手机只读查看已有会话；电脑离线时不可用。")
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