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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.qiuli55.flux.mobile.BuildConfig
import top.qiuli55.flux.mobile.data.AccountSession

// ================================================================== 设置

@Composable
fun SettingsSheet(
    account: AccountSession?,
    deviceName: String,
    deviceCount: Int,
    haptics: Boolean,
    reduceMotion: Boolean,
    decisionAlerts: Boolean,
    taskAlerts: Boolean,
    strict: Boolean,
    onHaptics: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    onDecisionAlerts: (Boolean) -> Unit,
    onTaskAlerts: (Boolean) -> Unit,
    onStrict: (Boolean) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTerminals: () -> Unit,
    onManualConnect: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val displayName = account?.displayName?.takeIf { it.isNotBlank() }
        ?: account?.username?.takeIf { it.isNotBlank() }
        ?: "本机连接"
    val accountLine = account?.email?.takeIf { it.isNotBlank() } ?: "手动连接 · $deviceName"
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .93f) {
        SheetHeader(title = "我的", onClose = onDismiss, centered = true)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(V2Colors.avatarBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, null, tint = V2Colors.avatarInk, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(displayName, 19f, V2Colors.ink(.95f), FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(V2Colors.ink(.05f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Label(if (account != null) "云账号" else "直连", 9.5f, V2Colors.ink(.45f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(accountLine, 11.5f, V2Colors.ink(.40f), maxLines = 1)
            }

            WhiteCard(Modifier.fillMaxWidth()) {
                SettingsValueRow(Icons.Outlined.Person, "账号", accountLine)
                ThinDivider(V2Colors.ink(.05f))
                SettingsValueRow(Icons.Outlined.Devices, "当前设备", deviceName)
                ThinDivider(V2Colors.ink(.05f))
                SettingsValueRow(
                    Icons.Outlined.Language,
                    "连接方式",
                    when {
                        account == null -> "手动连接"
                        deviceCount > 0 -> "账号 · $deviceCount 台设备"
                        else -> "账号"
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth()) {
                if (account != null) {
                    SettingsActionRow(
                        icon = Icons.Outlined.Devices,
                        title = "切换设备",
                        description = "选择同一账号下的其他设备",
                        danger = false,
                        onClick = onOpenDevices,
                    )
                    ThinDivider(V2Colors.ink(.05f))
                }
                SettingsActionRow(
                    icon = Icons.Outlined.Terminal,
                    title = "终端会话",
                    description = "在工作区执行命令，查看实时输出",
                    danger = false,
                    onClick = onOpenTerminals,
                )
                ThinDivider(V2Colors.ink(.05f))
                SettingsActionRow(
                    icon = Icons.Outlined.Link,
                    title = "手动连接服务器",
                    description = "用服务地址 + 令牌直连",
                    danger = false,
                    onClick = onManualConnect,
                )
                ThinDivider(V2Colors.ink(.05f))
                SettingsActionRow(
                    icon = Icons.Outlined.Logout,
                    title = "退出登录",
                    description = "清除本机保存的账号与令牌",
                    danger = true,
                    onClick = onLogout,
                )
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(12.dp))
                        Label("确认强度", 13.5f, V2Colors.ink(.90f), modifier = Modifier.weight(1f))
                        Label(if (strict) "1.6 秒" else "0.8 秒", 11f, V2Colors.ink(.35f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(V2Colors.ink(.05f))
                            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                    ) {
                        StrengthOption("标准 · 0.8 秒", !strict, Modifier.weight(1f)) { onStrict(false) }
                        StrengthOption("严格 · 1.6 秒", strict, Modifier.weight(1f)) { onStrict(true) }
                    }
                }
                ThinDivider(V2Colors.ink(.05f))
                ToggleRow(
                    icon = Icons.Outlined.Vibration,
                    title = "触感反馈",
                    description = "按操作结果给出不同强度的振动",
                    checked = haptics,
                    onChange = onHaptics,
                )
                ThinDivider(V2Colors.ink(.05f))
                ToggleRow(
                    icon = Icons.Outlined.Waves,
                    title = "减少动态",
                    description = "关闭位移、弹性与脉冲动画",
                    checked = reduceMotion,
                    onChange = onReduceMotion,
                )
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth()) {
                ToggleRow(
                    icon = Icons.Outlined.Notifications,
                    title = "决策提醒",
                    description = "新决策到达时弹出顶部提示",
                    checked = decisionAlerts,
                    onChange = onDecisionAlerts,
                )
                ThinDivider(V2Colors.ink(.05f))
                ToggleRow(
                    icon = Icons.Outlined.Verified,
                    title = "任务完成提醒",
                    description = "队列中的任务执行完成时提示",
                    checked = taskAlerts,
                    onChange = onTaskAlerts,
                )
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth()) {
                SettingsValueRow(Icons.Outlined.Info, "版本", "v${BuildConfig.VERSION_NAME}")
            }

            Spacer(Modifier.height(16.dp))
            Label(
                "Flux Mobile Controller · Build ${BuildConfig.VERSION_CODE}",
                9f,
                V2Colors.ink(.25f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingsValueRow(icon: ImageVector, title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Label(title, 13.5f, V2Colors.ink(.90f), modifier = Modifier.weight(1f))
        Label(value, 12f, V2Colors.ink(.40f), maxLines = 1)
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(.55f), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, 13.5f, V2Colors.ink(.90f))
            Spacer(Modifier.height(2.dp))
            Label(description, 10f, V2Colors.ink(.35f))
        }
        Spacer(Modifier.width(10.dp))
        LightSwitch(checked, onChange)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (danger) V2Colors.rose.copy(alpha = .8f) else V2Colors.ink(.55f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, 13.5f, if (danger) V2Colors.rose else V2Colors.ink(.90f))
            Spacer(Modifier.height(2.dp))
            Label(description, 10f, V2Colors.ink(.35f))
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            null,
            tint = V2Colors.ink(.25f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StrengthOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) V2Colors.ink(.05f) else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 11.5f, if (selected) V2Colors.ink else V2Colors.ink(.40f))
    }
}

// ================================================================== 实时日志

private enum class LogFilter(val label: String) {
    ALL("全部"), INFO("信息"), WARN("警告"), ERROR("错误")
}

@Composable
fun LogsSheet(
    logs: List<V2Log>,
    onAppendHeartbeat: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var following by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(following) {
        if (following) {
            while (true) {
                delay(6000)
                onAppendHeartbeat()
            }
        }
    }
    LaunchedEffect(logs.size, following) {
        if (following && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    val visible = remember(logs, filter) {
        when (filter) {
            LogFilter.ALL -> logs
            LogFilter.INFO -> logs.filter { it.level == V2LogLevel.INFO }
            LogFilter.WARN -> logs.filter { it.level == V2LogLevel.WARN }
            LogFilter.ERROR -> logs.filter { it.level == V2LogLevel.ERROR }
        }
    }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = "实时日志",
            subtitle = "Flux Kernel v2.1 · 每 6 秒追加一条心跳",
            onClose = onDismiss,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F8FA).copy(alpha = .92f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterPill("全部", logs.size, filter == LogFilter.ALL) { filter = LogFilter.ALL }
                FilterPill("信息", logs.count { it.level == V2LogLevel.INFO }, filter == LogFilter.INFO) { filter = LogFilter.INFO }
                FilterPill("警告", logs.count { it.level == V2LogLevel.WARN }, filter == LogFilter.WARN) { filter = LogFilter.WARN }
                FilterPill("错误", logs.count { it.level == V2LogLevel.ERROR }, filter == LogFilter.ERROR) { filter = LogFilter.ERROR }
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (following) V2Colors.cyan500.copy(alpha = .10f) else V2Colors.ink(.06f))
                    .clickable { following = !following }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (following) V2Colors.cyan500 else V2Colors.ink(.30f), CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Label(
                    if (following) "跟随" else "暂停",
                    10f,
                    if (following) V2Colors.cyan700 else V2Colors.ink(.40f),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                "${logs.size} 条 · 保留最近 240 条",
                9.5f,
                V2Colors.ink(.24f),
                mono = true,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DeleteSweep, null, tint = V2Colors.ink(.40f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Label("清空", 10f, V2Colors.ink(.40f))
            }
        }
        if (visible.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Label("暂无日志", 10f, V2Colors.ink(.30f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visible, key = { "${it.time}-${it.message}" }) { entry ->
                    Row(Modifier.fillMaxWidth()) {
                        Label(entry.time, 10f, V2Colors.ink(.22f), mono = true)
                        Spacer(Modifier.width(8.dp))
                        Label(
                            when (entry.level) {
                                V2LogLevel.INFO -> "INFO"
                                V2LogLevel.WARN -> "WARN"
                                V2LogLevel.ERROR -> "ERROR"
                            },
                            10f,
                            when (entry.level) {
                                V2LogLevel.INFO -> V2Colors.cyan700.copy(alpha = .70f)
                                V2LogLevel.WARN -> V2Colors.amber700.copy(alpha = .90f)
                                V2LogLevel.ERROR -> V2Colors.rose
                            },
                            mono = true,
                            modifier = Modifier.width(34.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Label(
                            entry.message,
                            10f,
                            V2Colors.ink(.58f),
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, 11f, if (selected) V2Colors.ink(.95f) else V2Colors.ink(.40f))
        Spacer(Modifier.width(5.dp))
        Label(
            count.toString(),
            10f,
            if (selected) V2Colors.cyan700 else V2Colors.ink(.28f),
            mono = true,
        )
    }
}

// ================================================================== 新建对话

@Composable
fun NewChatSheet(
    device: String,
    options: List<V2AgentOption>,
    onPick: (V2AgentOption) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = "新建对话",
            subtitle = "选择一个 Agent 开始单独对话 · $device",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (options.isEmpty()) {
                Label("当前设备没有配置 Agent", 11f, V2Colors.ink(.40f), modifier = Modifier.padding(16.dp))
            }
            options.forEach { option ->
                val agent = option.agent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = .55f))
                        .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(16.dp))
                        .clickable { onPick(option) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(agent.tint.copy(alpha = .10f))
                                .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.SmartToy, null, tint = agent.tint, modifier = Modifier.size(16.dp))
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .background(V2Colors.emerald, CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(agent.name, 12.5f, V2Colors.ink(.90f), FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Label("${agent.role} · 运行 ${agent.running} 个任务", 10f, V2Colors.ink(.36f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Label("${agent.load} %", 11f, V2Colors.ink(.55f))
                        Label("负载", 9f, V2Colors.ink(.28f))
                    }
                }
            }
        }
    }
}

// ================================================================== 多 Agent 详情

@Composable
fun MultiGroupSheet(group: V2Group, onOpenQueue: () -> Unit, onDismiss: () -> Unit) {
    val statusColor = groupStatusColor(group.status)
    val stage = group.stages.getOrNull(group.stage)
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = group.title,
            subtitle = "${group.agents.size} 个 Agent 协作 · ${group.device}",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .55f))
                    .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusColor.copy(alpha = .10f))
                            .border(1.dp, statusColor.copy(alpha = .20f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AccountTree, null, tint = statusColor, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Label(groupStatusLabel(group.status), 10f, statusColor, FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    Label("${group.progress} %", 12f, V2Colors.ink(.70f), FontWeight.SemiBold, mono = true)
                }
                Spacer(Modifier.height(12.dp))
                ProgressBar(group.progress, V2Colors.cyan500, height = 6.dp)
                Spacer(Modifier.height(10.dp))
                Label(
                    "当前阶段：${stage?.name ?: "已完成"} · 最近更新 ${group.updated}",
                    10.5f,
                    V2Colors.ink(.40f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Label("参与 Agent", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.agents.forEach { agentId ->
                    val agent = demoAgent(agentId)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = .45f))
                            .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(999.dp))
                            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(agent.tint.copy(alpha = .12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.SmartToy, null, tint = agent.tint, modifier = Modifier.size(11.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Label(agent.name, 10.5f, V2Colors.ink(.65f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Label("阶段进度", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .45f))
                    .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            ) {
                group.stages.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (item.state) {
                                            V2StageState.DONE -> V2Colors.emerald.copy(alpha = .14f)
                                            V2StageState.ACTIVE -> V2Colors.cyan500.copy(alpha = .16f)
                                            V2StageState.PENDING -> V2Colors.ink(.04f)
                                        },
                                    )
                                    .border(
                                        1.dp,
                                        when (item.state) {
                                            V2StageState.DONE -> V2Colors.emerald.copy(alpha = .30f)
                                            V2StageState.ACTIVE -> V2Colors.accent.copy(alpha = .30f)
                                            V2StageState.PENDING -> V2Colors.ink(.15f)
                                        },
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (item.state) {
                                    V2StageState.DONE -> Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        tint = Color(0xFF059669),
                                        modifier = Modifier.size(10.dp),
                                    )
                                    V2StageState.ACTIVE -> Box(
                                        Modifier.size(6.dp).background(V2Colors.cyan500, CircleShape),
                                    )
                                    V2StageState.PENDING -> Box(
                                        Modifier.size(6.dp).background(V2Colors.ink(.20f), CircleShape),
                                    )
                                }
                            }
                            if (index != group.stages.lastIndex) {
                                Box(
                                    Modifier
                                        .padding(top = 4.dp)
                                        .width(1.dp)
                                        .height(34.dp)
                                        .background(
                                            if (item.state == V2StageState.DONE) V2Colors.emerald.copy(alpha = .25f)
                                            else V2Colors.ink(.10f),
                                        ),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                            Label(
                                item.name,
                                11.5f,
                                when (item.state) {
                                    V2StageState.DONE -> V2Colors.ink(.65f)
                                    V2StageState.ACTIVE -> V2Colors.ink(.85f)
                                    V2StageState.PENDING -> V2Colors.ink(.35f)
                                },
                                if (item.state == V2StageState.ACTIVE) FontWeight.Medium else FontWeight.Normal,
                            )
                            Spacer(Modifier.height(2.dp))
                            Label(
                                if (item.time == "—") item.note else "${item.note} · ${item.time}",
                                10f,
                                V2Colors.ink(.33f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Label("最近动态", 11f, V2Colors.ink(.45f), FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.events.forEach { event ->
                    val agent = demoAgent(event.agentId)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = .45f))
                            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(agent.name, 10f, agent.tint, FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Label(event.time, 9f, V2Colors.ink(.25f), mono = true)
                        }
                        Spacer(Modifier.height(4.dp))
                        Label(event.text, 11f, V2Colors.ink(.55f))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F8FA))
                .border(1.dp, V2Colors.ink(.04f))
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
        ) {
            PrimaryButton(
                text = "在任务队列中查看",
                onClick = onOpenQueue,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.ArrowForward,
            )
        }
    }
}

// ================================================================== 高危确认

@Composable
fun ConfirmSheet(
    decision: V2Decision,
    strict: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val agent = demoAgent(decision.agentId)
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = "确认执行高危操作",
            subtitle = "${agent.name} · 高危请求",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .55f))
                    .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Label(decision.title, 13.5f, V2Colors.ink(.92f), FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Label(decision.reason, 11f, V2Colors.ink(.42f))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    decision.impact.forEach { impact ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(V2Colors.ink(.04f))
                                .border(1.dp, V2Colors.ink(.07f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Label("${impact.key} ", 10f, V2Colors.ink(.45f))
                            Label(impact.value, 10f, V2Colors.ink(.75f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            HoldToConfirm(
                durationMs = if (strict) 1600 else 800,
                onConfirmed = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Label(
                "${decision.eta} · 执行过程可在队列中随时中止",
                10f,
                V2Colors.ink(.24f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            GhostButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================================================================== Agent / 模型选择

@Composable
fun AgentPickerSheet(
    chat: V2ChatState,
    options: List<V2AgentOption>,
    onPick: (agentId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expandedAgent by remember(chat.agentId) { mutableStateOf(chat.agentId) }
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .82f) {
        SheetHeader(
            title = "切换 Agent 与模型",
            subtitle = "仅列出当前设备（${chat.device}）已配置的 Agent 与可用模型",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val agent = option.agent
                val selectedAgent = agent.id == chat.agentId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selectedAgent) V2Colors.cyan500.copy(alpha = .06f) else Color.White.copy(alpha = .55f))
                        .border(
                            1.dp,
                            if (selectedAgent) V2Colors.accent.copy(alpha = .28f) else V2Colors.ink(.07f),
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { expandedAgent = agent.id }
                        .padding(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(agent.tint.copy(alpha = .10f))
                                .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.SmartToy, null, tint = agent.tint, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Label(agent.name, 12.5f, V2Colors.ink(.90f), FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Label("${agent.role} · 运行 ${agent.running} 个任务", 10f, V2Colors.ink(.36f))
                        }
                        if (selectedAgent) {
                            Icon(Icons.Outlined.Check, null, tint = V2Colors.cyan700, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (expandedAgent == agent.id) {
                        Spacer(Modifier.height(10.dp))
                        Label("可用模型", 9.5f, V2Colors.ink(.38f))
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            option.models.forEach { model ->
                                val selectedModel = selectedAgent && model == chat.model
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selectedModel) V2Colors.accent else Color.White.copy(alpha = .55f))
                                        .border(
                                            1.dp,
                                            if (selectedModel) V2Colors.accent else V2Colors.ink(.08f),
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable { onPick(agent.id, model) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Label(
                                        model,
                                        11f,
                                        if (selectedModel) Color.White else V2Colors.ink(.65f),
                                        if (selectedModel) FontWeight.Medium else FontWeight.Normal,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Label(
                                        if (selectedModel) "使用中" else "选择",
                                        9.5f,
                                        if (selectedModel) Color.White.copy(alpha = .8f) else V2Colors.ink(.35f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
