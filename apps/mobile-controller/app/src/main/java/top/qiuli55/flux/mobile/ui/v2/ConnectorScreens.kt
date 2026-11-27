package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.GitHubAccountStatus
import top.qiuli55.flux.mobile.data.GitHubRepo
import top.qiuli55.flux.mobile.data.McpConnector
import java.net.URI

// ---------------------------------------------------------------- 可测逻辑

/** 连接器列表分区：需要重连的条目同时出现在「已连接」里，便于原地处理。 */
internal data class ConnectorSections(
    val needsReconnect: List<McpConnector>,
    val connected: List<McpConnector>,
    val featured: List<McpConnector>,
) {
    val isEmpty: Boolean get() = connected.isEmpty() && featured.isEmpty()
}

internal fun connectorSections(items: List<McpConnector>, query: String): ConnectorSections {
    val keyword = query.trim().lowercase()
    val visible = if (keyword.isEmpty()) {
        items
    } else {
        items.filter { item ->
            item.name.lowercase().contains(keyword) ||
                item.description.lowercase().contains(keyword) ||
                item.url.orEmpty().lowercase().contains(keyword)
        }
    }
    val needs = visible.filter { it.status == "needs_reauth" || it.status == "error" }
    val connected = visible.filter { it.status == "connected" } + needs
    val featured = visible.filter { it.status == "disconnected" || it.status == "coming_soon" }
    return ConnectorSections(needs, connected, featured)
}

internal fun connectorSubtitle(item: McpConnector): String = when (item.status) {
    "connected" -> when {
        !item.account.isNullOrBlank() -> "@${item.account} · 已连接"
        item.kind == "custom" && item.tools.isNotEmpty() -> "已连接 · ${item.tools.size} 个工具"
        item.kind == "custom" -> "已连接 · MCP 服务器"
        else -> "已连接"
    }
    "needs_reauth" -> item.error?.takeIf { it.isNotBlank() } ?: "授权已失效，需要重新连接"
    "error" -> item.error?.takeIf { it.isNotBlank() } ?: "连接不可用，需要重新连接"
    "coming_soon" -> "即将支持"
    else -> item.description.takeIf { it.isNotBlank() } ?: "未连接"
}

internal fun connectorActionLabel(item: McpConnector): String? = when (item.status) {
    "needs_reauth", "error" -> "重新连接"
    "disconnected" -> "连接"
    "coming_soon" -> "即将支持"
    else -> null
}

internal fun connectorErrorText(item: McpConnector): String? =
    if (item.status == "error" || item.status == "needs_reauth") {
        item.error?.takeIf { it.isNotBlank() } ?: "连接不可用，需要重新连接"
    } else {
        null
    }

/** 表单的本地校验（后端还会再做一次 URL / 名称校验）。 */
internal fun validateConnectorForm(name: String, url: String): String? {
    val cleanName = name.trim()
    val cleanUrl = url.trim()
    if (cleanName.isEmpty()) return "请填写连接器名称"
    if (cleanName.length > 64) return "连接器名称不能超过 64 个字符"
    if (cleanName.equals("flux", ignoreCase = true)) return "名称 flux 保留给 Flux 内置工具"
    if (cleanUrl.isEmpty()) return "请填写服务器 URL"
    if (cleanUrl.length > 2048) return "服务器 URL 不能超过 2048 个字符"
    if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
        !cleanUrl.startsWith("http://", ignoreCase = true)
    ) {
        return "服务器 URL 需要以 https:// 或 http:// 开头"
    }
    val host = runCatching { URI(cleanUrl).host }.getOrNull()
    if (host.isNullOrBlank()) return "服务器 URL 缺少主机名"
    return null
}

internal data class ConnectorFormState(
    val name: String = "",
    val url: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

internal fun ConnectorFormState.withFailure(message: String): ConnectorFormState =
    copy(busy = false, error = message)

// ---------------------------------------------------------------- 连接器页

@Composable
fun ConnectorsScreen(
    connectors: List<McpConnector>,
    loading: Boolean,
    error: String?,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onAdd: () -> Unit,
    onGitHub: () -> Unit,
    onReconnect: (McpConnector) -> Unit,
    onOpenCustom: (McpConnector) -> Unit,
    onDismissMessages: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sections = connectorSections(connectors, query)

    LaunchedEffect(Unit) { onReload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = extraTop + 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(
                    Icons.Outlined.ArrowBack,
                    onBack,
                    size = 34.dp,
                    iconSize = 16.dp,
                    contentDescription = "返回",
                )
                Spacer(Modifier.width(10.dp))
            }
            Label(
                CONNECTOR_SCREEN_TITLE,
                22f,
                V2Colors.ink(.95f),
                FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(
                Icons.Outlined.Add,
                onAdd,
                size = 34.dp,
                iconSize = 17.dp,
                contentDescription = "添加连接器",
            )
        }
        Label(
            "连接器让 Agent 使用外部工具和数据源。",
            12f,
            V2Colors.ink(.42f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        ConnectorSearchField(query) { query = it }

        val flash = error
        if (flash != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.rose.copy(alpha = .08f))
                    .clickable(onClick = onDismissMessages)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Label(flash, 10.5f, V2Colors.rose700)
            }
        }

        if (loading && connectors.isEmpty()) {
            Label(
                "正在读取连接器…",
                12f,
                V2Colors.ink(.42f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
        ) {
            if (sections.needsReconnect.isNotEmpty()) {
                item {
                    ReconnectBanner(
                        item = sections.needsReconnect.first(),
                        onReconnect = { onReconnect(sections.needsReconnect.first()) },
                    )
                }
            }
            if (sections.connected.isNotEmpty()) {
                item { ConnectorSectionLabel("已连接") }
                item {
                    ConnectorCard(
                        items = sections.connected,
                        onItemClick = { item ->
                            when {
                                item.kind == "custom" -> onOpenCustom(item)
                                item.id == "github" && item.status == "connected" -> onGitHub()
                                else -> onReconnect(item)
                            }
                        },
                    )
                }
            }
            if (sections.featured.isNotEmpty()) {
                item { ConnectorSectionLabel("精选") }
                item {
                    ConnectorCard(
                        items = sections.featured,
                        onItemClick = { item ->
                            if (item.available) onReconnect(item)
                        },
                    )
                }
            }
            if (sections.isEmpty && !loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp)) {
                        Label(
                            if (query.isBlank()) "还没有可用的连接器" else "没有匹配的连接器",
                            13f,
                            V2Colors.ink(.72f),
                            FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Label(
                            if (query.isBlank()) {
                                "点右上角「+」添加一个 MCP 服务器，或连接 GitHub。"
                            } else {
                                "换个关键词试试。"
                            },
                            11f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorSearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = V2Colors.ink(.38f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Label("搜索连接器…", 12f, V2Colors.ink(.32f), mono = false)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = V2Colors.ink(.86f),
                    fontWeight = FontWeight.Normal,
                ),
                cursorBrush = SolidColor(V2Colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReconnectBanner(item: McpConnector, onReconnect: () -> Unit) {
    WhiteCard(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onReconnect)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = V2Colors.rose,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label("需要重新连接", 13.5f, V2Colors.ink(.92f), FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Label(
                    "${item.name} · ${connectorErrorText(item).orEmpty()}",
                    10.5f,
                    V2Colors.ink(.42f),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            Label("重新连接", 11.5f, V2Colors.rose, FontWeight.Medium)
        }
    }
}

@Composable
private fun ConnectorSectionLabel(text: String) {
    Label(
        text,
        12f,
        V2Colors.ink(.46f),
        FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ConnectorCard(items: List<McpConnector>, onItemClick: (McpConnector) -> Unit) {
    WhiteCard(Modifier.padding(horizontal = 16.dp)) {
        Column {
            items.forEachIndexed { index, item ->
                ConnectorRow(item) { onItemClick(item) }
                if (index != items.lastIndex) {
                    ThinDivider(V2Colors.lineSoft)
                }
            }
        }
    }
}

@Composable
private fun ConnectorRow(item: McpConnector, onClick: () -> Unit) {
    val action = connectorActionLabel(item)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.available || item.kind == "custom", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V2IconTile(
            icon = if (item.id == "github") Icons.Outlined.Code else Icons.Outlined.Extension,
            tint = if (item.status == "coming_soon") V2Colors.ink(.35f) else V2Colors.ink(.70f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(
                item.name,
                14.5f,
                if (item.status == "coming_soon") V2Colors.ink(.50f) else V2Colors.ink(.92f),
                FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Label(
                connectorSubtitle(item),
                10.5f,
                when (item.status) {
                    "needs_reauth", "error" -> V2Colors.rose
                    else -> V2Colors.ink(.40f)
                },
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            item.status == "connected" && item.id == "github" -> {
                Label("管理", 11.5f, V2Colors.accent, FontWeight.Medium)
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = V2Colors.ink(.22f),
                    modifier = Modifier.size(15.dp),
                )
            }
            item.status == "connected" -> {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "已连接",
                    tint = V2Colors.emerald700,
                    modifier = Modifier.size(17.dp),
                )
            }
            action != null -> {
                Label(
                    action,
                    11.5f,
                    if (item.status == "needs_reauth" || item.status == "error") {
                        V2Colors.rose
                    } else {
                        V2Colors.ink(.45f)
                    },
                    FontWeight.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 自定义连接器表单

@Composable
fun CustomConnectorSheet(
    onDismiss: () -> Unit,
    onSubmit: (name: String, url: String, onResult: (String?) -> Unit) -> Unit,
) {
    var form by remember { mutableStateOf(ConnectorFormState()) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        SheetHeader(
            title = "自定义连接器",
            subtitle = "按 MCP 服务器接入；添加前会先测试连接",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ConnectorFieldLabel("名称")
            ConnectorTextField(
                value = form.name,
                placeholder = "我的 MCP 服务器",
                onChange = { form = form.copy(name = it, error = null) },
            )
            Spacer(Modifier.height(16.dp))
            ConnectorFieldLabel("服务器 URL")
            ConnectorTextField(
                value = form.url,
                placeholder = "https://example.com/mcp",
                keyboardType = KeyboardType.Uri,
                onChange = { form = form.copy(url = it, error = null) },
            )
            Spacer(Modifier.height(10.dp))
            Label(
                "会先做一次 MCP 握手并读取工具列表；通过才保存，失败不会标记为已连接。",
                10f,
                V2Colors.ink(.36f),
            )
            form.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2Colors.rose.copy(alpha = .08f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Label(message, 10.5f, V2Colors.rose700)
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = if (form.busy) "正在测试连接…" else "添加连接器",
                enabled = !form.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val invalid = validateConnectorForm(form.name, form.url)
                    if (invalid != null) {
                        form = form.withFailure(invalid)
                    } else {
                        form = form.copy(busy = true, error = null)
                        onSubmit(form.name.trim(), form.url.trim()) { error ->
                            form = if (error == null) {
                                form.copy(busy = false)
                            } else {
                                form.withFailure(error)
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ConnectorFieldLabel(text: String) {
    Label(text, 12.5f, V2Colors.ink(.62f), FontWeight.Medium)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ConnectorTextField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2Colors.surface(.72f))
            .border(1.dp, V2Colors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Label(placeholder, 13f, V2Colors.ink(.30f), mono = false)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = V2Colors.ink(.88f)),
            cursorBrush = SolidColor(V2Colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- 自定义连接器详情

@Composable
fun CustomConnectorDetailSheet(
    item: McpConnector,
    onDismiss: () -> Unit,
    onProbe: (id: String, onResult: (String?) -> Unit) -> Unit,
    onDelete: (id: String, onResult: (String?) -> Unit) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf(connectorErrorText(item)) }
    var confirmDelete by remember(item.id) { mutableStateOf(false) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .88f) {
        SheetHeader(
            title = item.name,
            subtitle = "自定义 MCP 连接器",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Label("服务器 URL", 10.5f, V2Colors.ink(.38f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(
                            item.url.orEmpty(),
                            11.5f,
                            V2Colors.ink(.80f),
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(V2Colors.ink(.06f))
                                .clickable { clipboard.setText(AnnotatedString(item.url.orEmpty())) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Label("复制", 10f, V2Colors.ink(.52f))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ThinDivider(V2Colors.lineSoft)
                    Spacer(Modifier.height(12.dp))
                    Label("状态", 10.5f, V2Colors.ink(.38f))
                    Spacer(Modifier.height(4.dp))
                    Label(
                        if (item.status == "connected") "已连接 · ${item.tools.size} 个工具" else "未连接",
                        12f,
                        if (item.status == "connected") V2Colors.ink(.82f) else V2Colors.rose,
                        FontWeight.Medium,
                    )
                    if (item.tools.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Label(
                            item.tools.take(10).joinToString(" · "),
                            10f,
                            V2Colors.ink(.42f),
                            maxLines = 4,
                        )
                        if (item.tools.size > 10) {
                            Spacer(Modifier.height(4.dp))
                            Label(
                                "另有 ${item.tools.size - 10} 个工具",
                                9.5f,
                                V2Colors.ink(.30f),
                            )
                        }
                    }
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2Colors.rose.copy(alpha = .08f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Label(message, 10.5f, V2Colors.rose700)
                }
            }

            Spacer(Modifier.height(16.dp))
            GhostButton(
                text = if (busy) "正在测试连接…" else "重新测试连接",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Refresh,
                onClick = {
                    busy = true
                    error = null
                    onProbe(item.id) { failure ->
                        busy = false
                        error = failure
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            SettingsActionRow(
                icon = Icons.Outlined.DeleteOutline,
                title = if (confirmDelete) "再次点击确认移除" else "移除连接器",
                description = "移除后对应 Agent 不再注入这个 MCP 服务器",
                danger = true,
                onClick = {
                    if (!confirmDelete) {
                        confirmDelete = true
                    } else {
                        busy = true
                        onDelete(item.id) { failure ->
                            busy = false
                            if (failure == null) onDismiss() else error = failure
                        }
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---------------------------------------------------------------- GitHub 详情

@Composable
fun GitHubConnectorSheet(
    account: GitHubAccountStatus?,
    repos: List<GitHubRepo>,
    reposLoading: Boolean,
    loading: Boolean,
    onRefreshRepos: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val loggedIn = account?.loggedIn == true
    var confirmLogout by remember { mutableStateOf(false) }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .9f) {
        SheetHeader(
            title = "GitHub",
            subtitle = "读写仓库、提交代码与创建 Pull Request",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            WhiteCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = V2Colors.ink(.62f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(
                            if (loggedIn) account?.login?.let { "@$it" } ?: "已连接" else "未连接",
                            14f,
                            V2Colors.ink(.90f),
                            FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Label(
                            account?.scopes?.takeIf { it.isNotBlank() }
                                ?: "连接后可读取全部仓库（含私有）",
                            10f,
                            V2Colors.ink(.38f),
                            maxLines = 2,
                        )
                    }
                    if (loggedIn) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(V2Colors.accent.copy(alpha = .12f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Label("已连接", 9.5f, V2Colors.accent, FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (loggedIn) {
                Spacer(Modifier.height(8.dp))
                WhiteCard(Modifier.fillMaxWidth()) {
                    GitHubRepoList(
                        repos = repos,
                        loading = reposLoading,
                        onRefresh = onRefreshRepos,
                    )
                }
                Spacer(Modifier.height(10.dp))
                SettingsActionRow(
                    icon = Icons.Outlined.Logout,
                    title = if (confirmLogout) "再次点击确认退出" else "退出 GitHub 账号",
                    description = "退出后 Agent 无法读写你的仓库",
                    danger = true,
                    onClick = {
                        if (!confirmLogout) confirmLogout = true else onLogout()
                    },
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Label(
                    if (loading) "正在启动授权…" else "回到连接器列表点 GitHub 右侧的「连接」即可发起授权。",
                    11f,
                    V2Colors.ink(.42f),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun GitHubRepoList(
    repos: List<GitHubRepo>,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(
                if (repos.isEmpty()) "我的仓库" else "我的仓库 · ${repos.size}",
                10.5f,
                V2Colors.ink(.45f),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Label(
                    if (loading) "加载中…" else "刷新",
                    10.5f,
                    if (loading) V2Colors.ink(.30f) else V2Colors.accent,
                    FontWeight.Medium,
                )
            }
        }
        when {
            repos.isEmpty() && !loading -> {
                Spacer(Modifier.height(8.dp))
                Label("还没有读到仓库，点「刷新」重试", 10.5f, V2Colors.ink(.34f))
            }
            else -> repos.take(10).forEach { repo ->
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(
                                repo.fullName.ifBlank { repo.name },
                                12.5f,
                                V2Colors.ink(.86f),
                                FontWeight.Medium,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                            )
                            if (repo.isPrivate) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(V2Colors.ink(.07f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) {
                                    Label("私有", 8.5f, V2Colors.ink(.45f), FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Label(
                            listOfNotNull(
                                repo.language?.takeIf { it.isNotBlank() },
                                repo.defaultBranch.ifBlank { "main" },
                            ).joinToString(" · "),
                            9.5f,
                            V2Colors.ink(.34f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (repos.size > 10) {
            Spacer(Modifier.height(8.dp))
            Label("仅显示最近更新的 10 个仓库", 9.5f, V2Colors.ink(.30f))
        }
    }
}

//: 供测试锁定「GitHub 已从设置弹层迁到连接器页」。
internal val CONNECTOR_SCREEN_TITLE = "连接器"
