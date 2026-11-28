package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.AgentCliAccountStatus
import top.qiuli55.flux.mobile.data.AgentCliLoginSession
import top.qiuli55.flux.mobile.data.GitHubAccountStatus
import top.qiuli55.flux.mobile.data.GitHubRepo
import top.qiuli55.flux.mobile.data.ImConnectorStatus
import top.qiuli55.flux.mobile.data.McpConnector
import top.qiuli55.flux.mobile.data.WechatLoginSession
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

internal fun connectorSubtitle(item: McpConnector): String = when {
    item.id in IM_CONNECTOR_IDS -> imConnectorSubtitle(item)
    else -> connectorSubtitleByStatus(item)
}

private fun connectorSubtitleByStatus(item: McpConnector): String = when (item.status) {
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

/** 连接器里新增的 IM 入口：飞书单聊 / 个人微信（官方微信 ClawBot）。 */
internal val IM_CONNECTOR_IDS = setOf("feishu", "wechat")

/** 入口文案必须点名官方产品：Cursor / 谷歌 / Antigravity。 */
internal fun agentConnectorConnectLabel(kind: String): String =
    if (kind == "cursor") "连接 Cursor" else "连接谷歌 / Antigravity"

internal fun imConnectorConnectLabel(kind: String): String =
    if (kind == "feishu") "连接飞书" else "连接个人微信"

internal fun imConnectorTitle(kind: String): String =
    if (kind == "feishu") "飞书" else "个人微信"

/** IM 连接器卡片副标题：状态来自连接器列表，不暴露任何凭据。 */
internal fun imConnectorSubtitle(item: McpConnector): String = when (item.status) {
    "connected" -> if (item.id == "feishu") "已连接 · 飞书单聊" else "已连接 · 微信 ClawBot"
    "needs_reauth" -> {
        val reason = item.error?.takeIf { it.isNotBlank() } ?: "登录已过期"
        if ("扫码" in reason) reason else "$reason，请重新扫码"
    }
    "error" -> item.error?.takeIf { it.isNotBlank() } ?: "连接异常，需要处理"
    else -> if (item.id == "feishu") "在飞书单聊里和 Flux 对话" else "通过官方微信 ClawBot 单聊"
}

internal fun agentConnectorTitle(kind: String): String =
    if (kind == "cursor") "Cursor" else "谷歌 / Antigravity"

internal fun agentConnectorPrerequisite(kind: String, binaryFound: Boolean): String =
    if (binaryFound) {
        "需要这台电脑保持在线；Flux 会在这里拉起官方 CLI 完成授权。"
    } else if (kind == "cursor") {
        "这台电脑上还没有找到官方 Cursor CLI（agent）。请先在电脑上安装，再回来连接。"
    } else {
        "这台电脑上还没有找到官方 Antigravity CLI（agy）。请先在电脑上安装，再回来连接。"
    }

internal fun agentConnectorStateText(status: AgentCliAccountStatus?): String = when {
    status == null -> "正在读取登录态…"
    status.loggedIn -> "已连接 · 官方 CLI 登录态"
    !status.binaryFound -> "未连接 · 未安装官方 CLI"
    else -> "未连接"
}

internal fun connectorActionLabel(item: McpConnector): String? = when (item.status) {
    "needs_reauth", "error" -> "重新连接"
    "disconnected" -> if (item.id in AGENT_CONNECTOR_RUNTIMES) {
        agentConnectorConnectLabel(item.id)
    } else if (item.id in IM_CONNECTOR_IDS) {
        imConnectorConnectLabel(item.id)
    } else {
        "连接"
    }
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
    onOpenIm: (McpConnector) -> Unit,
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
                                item.id in IM_CONNECTOR_IDS -> onOpenIm(item)
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
                            if (!item.available) return@ConnectorCard
                            if (item.id in IM_CONNECTOR_IDS) onOpenIm(item) else onReconnect(item)
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

// ---------------------------------------------------------------- Cursor / Antigravity 官方授权

@Composable
fun AgentConnectorSheet(
    kind: String,
    status: AgentCliAccountStatus?,
    session: AgentCliLoginSession?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onSubmitCode: (loginId: String, code: String, onResult: (String?) -> Unit) -> Unit,
    onLogout: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var code by remember(kind) { mutableStateOf("") }
    var codeError by remember(kind) { mutableStateOf<String?>(null) }
    val title = agentConnectorTitle(kind)
    val loggedIn = status?.loggedIn == true
    val pending = session?.isPending == true

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .9f) {
        SheetHeader(
            title = title,
            subtitle = "用你自己的账号，在电脑上执行编码任务",
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
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V2IconTile(
                            icon = Icons.Outlined.Extension,
                            tint = if (loggedIn) V2Colors.ink(.72f) else V2Colors.ink(.48f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Label(
                                agentConnectorStateText(status),
                                13.5f,
                                if (loggedIn) V2Colors.ink(.90f) else V2Colors.ink(.62f),
                                FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Label(
                                if (status == null) {
                                    "正在读取这台电脑上的官方 CLI…"
                                } else {
                                    agentConnectorPrerequisite(kind, status.binaryFound)
                                },
                                10.5f,
                                V2Colors.ink(.40f),
                                maxLines = 3,
                            )
                        }
                    }
                }
            }

            if (pending || session?.status == "failed") {
                Spacer(Modifier.height(10.dp))
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Label("官方授权", 10.5f, V2Colors.ink(.42f), FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Label(
                            session?.message?.takeIf { it.isNotBlank() }
                                ?: "在浏览器完成授权后，Flux 会自动收到结果。",
                            10.5f,
                            if (session?.status == "failed") V2Colors.rose else V2Colors.ink(.44f),
                            maxLines = 3,
                        )
                        if (!session?.url.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            PrimaryButton(
                                text = "打开官方授权页",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenUrl(session?.url.orEmpty()) },
                            )
                        }
                        if (session != null && session.needsCode && pending) {
                            Spacer(Modifier.height(12.dp))
                            Label(
                                "官方 CLI 要求回填授权码（不是 API token / key）：",
                                10.5f,
                                V2Colors.ink(.46f),
                            )
                            Spacer(Modifier.height(6.dp))
                            ConnectorTextField(
                                value = code,
                                placeholder = "粘贴官方页面给出的授权码",
                                onChange = {
                                    code = it
                                    codeError = null
                                },
                            )
                            codeError?.let { message ->
                                Spacer(Modifier.height(6.dp))
                                Label(message, 10f, V2Colors.rose700)
                            }
                            Spacer(Modifier.height(8.dp))
                            GhostButton(
                                text = "提交授权码",
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onSubmitCode(session.loginId, code) { failure ->
                                        codeError = failure
                                        if (failure == null) code = ""
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (loggedIn) {
                SettingsActionRow(
                    icon = Icons.Outlined.Logout,
                    title = if (confirmLogout) "再次点击确认退出" else "退出 ${title} 账号",
                    description = "退出后 Agent 无法再用你的账号执行任务",
                    danger = true,
                    onClick = {
                        if (confirmLogout) onLogout() else confirmLogout = true
                    },
                )
            } else {
                PrimaryButton(
                    text = if (loading) "正在启动官方授权…" else agentConnectorConnectLabel(kind),
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStart,
                )
                Spacer(Modifier.height(8.dp))
                Label(
                    "编码任务始终在你的电脑上执行；Flux 不保存账号令牌，也不会把令牌发到手机。",
                    9.5f,
                    V2Colors.ink(.34f),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

//: 供测试锁定「GitHub 已从设置弹层迁到连接器页」。
internal val CONNECTOR_SCREEN_TITLE = "连接器"

@Composable
fun ImConnectorSheet(
    kind: String,
    status: ImConnectorStatus?,
    login: WechatLoginSession?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConnectFeishu: () -> Unit,
    onDisconnectFeishu: () -> Unit,
    onStartWechat: () -> Unit,
    onSubmitWechatCode: (loginId: String, code: String, onResult: (String?) -> Unit) -> Unit,
    onDisconnectWechat: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    var code by remember(kind, login?.loginId) { mutableStateOf("") }
    var codeError by remember(kind, login?.loginId) { mutableStateOf<String?>(null) }
    val title = imConnectorTitle(kind)

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .94f) {
        SheetHeader(
            title = title,
            subtitle = if (kind == "feishu") "飞书官方单聊" else "腾讯官方微信 ClawBot",
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
                Column(Modifier.padding(16.dp)) {
                    Label(
                        status?.message?.takeIf { it.isNotBlank() }
                            ?: if (loading) "正在读取连接状态…" else "尚未连接",
                        12.5f,
                        if (status?.connected == true) V2Colors.ink(.88f) else V2Colors.ink(.62f),
                        FontWeight.Medium,
                        maxLines = 4,
                    )
                    if (status?.running == true) {
                        Spacer(Modifier.height(4.dp))
                        Label(
                            if (kind == "feishu") "飞书长连接运行在 Flux 服务端" else "微信长轮询运行在 Flux 服务端",
                            10f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }

            val flash = error ?: status?.error
            if (!flash.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(V2Colors.rose.copy(alpha = .08f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Label(flash, 10.5f, V2Colors.rose700, maxLines = 5)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (kind == "feishu") {
                FeishuConnectorContent(
                    status = status,
                    loading = loading,
                    onConnect = onConnectFeishu,
                    onDisconnect = onDisconnectFeishu,
                    confirmDisconnect = confirmDisconnect,
                    onAskDisconnect = { confirmDisconnect = true },
                )
            } else {
                WechatConnectorContent(
                    status = status,
                    login = login,
                    loading = loading,
                    code = code,
                    codeError = codeError,
                    onCodeChange = {
                        code = it
                        codeError = null
                    },
                    onSubmitCode = {
                        login?.let { session ->
                            onSubmitWechatCode(session.loginId, code) { failure ->
                                codeError = failure
                                if (failure == null) code = ""
                            }
                        }
                    },
                    onStart = onStartWechat,
                    onDisconnect = onDisconnectWechat,
                    confirmDisconnect = confirmDisconnect,
                    onAskDisconnect = { confirmDisconnect = true },
                    onOpenUrl = onOpenUrl,
                )
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun FeishuConnectorContent(
    status: ImConnectorStatus?,
    loading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    confirmDisconnect: Boolean,
    onAskDisconnect: () -> Unit,
) {
    Label("使用前提", 10.5f, V2Colors.ink(.42f), FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Label(
                "1. 在飞书开放平台创建「企业自建应用」，开启「长连接接收事件」，订阅 im.message.receive_v1。",
                10.5f,
                V2Colors.ink(.56f),
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "2. 开通单聊消息读取与以应用身份发消息的权限，并发布应用。",
                10.5f,
                V2Colors.ink(.56f),
                maxLines = 3,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "3. 把 App ID / App Secret 配到 Flux 服务端环境变量 FLUX_FEISHU_IM_APP_ID / FLUX_FEISHU_IM_APP_SECRET，然后重启服务端。",
                10.5f,
                V2Colors.ink(.56f),
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "Flux 只处理单聊；群消息会被忽略。密钥不会显示在 App 里。",
                10f,
                V2Colors.ink(.38f),
                maxLines = 3,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    if (status?.connected == true || status?.bound == true || status?.waitingForMessage == true) {
        SettingsActionRow(
            icon = Icons.Outlined.Logout,
            title = if (confirmDisconnect) "再次点击确认断开" else "断开飞书单聊",
            description = "断开后不再向这个飞书单聊推送任务通知",
            danger = true,
            onClick = { if (confirmDisconnect) onDisconnect() else onAskDisconnect() },
        )
    } else {
        PrimaryButton(
            text = if (loading) "正在连接…" else "启动飞书连接",
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onConnect,
        )
        Spacer(Modifier.height(8.dp))
        Label(
            if (status?.configured == false) {
                "服务端还没有配置飞书应用；配置并重启后，这个按钮才能真正连上。"
            } else {
                "启动后请在飞书里给机器人发第一条单聊消息，Flux 收到后才会记为已连接。"
            },
            9.5f,
            V2Colors.ink(.36f),
            maxLines = 3,
        )
    }
}

@Composable
private fun WechatConnectorContent(
    status: ImConnectorStatus?,
    login: WechatLoginSession?,
    loading: Boolean,
    code: String,
    codeError: String?,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onStart: () -> Unit,
    onDisconnect: () -> Unit,
    confirmDisconnect: Boolean,
    onAskDisconnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Label("使用前提", 10.5f, V2Colors.ink(.42f), FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Label(
                "需要 iOS 微信 8.0.70+ 或 Android 8.0.69+，并在「我 → 设置 → 插件」启用「微信 ClawBot」。",
                10.5f,
                V2Colors.ink(.56f),
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "如果插件列表里没有「微信 ClawBot」，说明你的微信还没有灰度到该功能，请稍后再试；Flux 不会假装连接成功。",
                10.5f,
                V2Colors.ink(.48f),
                maxLines = 4,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "同一个微信号不要再绑定到另一个 ClawBot / OpenClaw 后端，否则消息可能被分流。",
                10f,
                V2Colors.rose700,
                maxLines = 3,
            )
        }
    }

    val active = login?.takeIf { it.isActive }
    if (active != null) {
        Spacer(Modifier.height(14.dp))
        WhiteCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label(active.message.ifBlank { "等待扫码确认…" }, 11.5f, V2Colors.ink(.72f), FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                if (active.qrcodeMatrix.isNotEmpty()) {
                    QrMatrixView(active.qrcodeMatrix)
                } else {
                    Label(
                        "二维码矩阵暂不可用；请使用下面的备份链接在微信内打开。",
                        10f,
                        V2Colors.ink(.42f),
                        maxLines = 3,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Label("二维码约 5 分钟内有效，过期会自动刷新。", 9.5f, V2Colors.ink(.36f))
                if (!active.backupUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "在微信中打开备份链接",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenUrl(active.backupUrl.orEmpty()) },
                    )
                }
                if (active.needsVerifyCode) {
                    Spacer(Modifier.height(12.dp))
                    Label(
                        "手机微信显示了数字，请在下面输入以继续绑定：",
                        10.5f,
                        V2Colors.ink(.46f),
                    )
                    Spacer(Modifier.height(6.dp))
                    ConnectorTextField(
                        value = code,
                        placeholder = "输入手机微信显示的数字",
                        onChange = onCodeChange,
                    )
                    codeError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Label(message, 10f, V2Colors.rose700)
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "提交数字",
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSubmitCode,
                    )
                }
            }
        }
    }

    if (status?.connected == true && active == null) {
        Spacer(Modifier.height(14.dp))
        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Label("已连接", 12f, V2Colors.ink(.86f), FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Label(
                    "首次使用请先在微信「微信 ClawBot」会话里发一句话。没有 context_token 时，Flux 无法回复。",
                    10.5f,
                    V2Colors.ink(.52f),
                    maxLines = 4,
                )
                Spacer(Modifier.height(6.dp))
                Label(
                    status.capabilities.proactiveWindowNote
                        ?: "微信只在你最近互动后允许主动消息；推不出去时会保留，下次对话补发。",
                    9.5f,
                    V2Colors.ink(.38f),
                    maxLines = 4,
                )
                if (status.pendingNotices > 0) {
                    Spacer(Modifier.height(6.dp))
                    Label("有 ${status.pendingNotices} 条提醒等你下次开口后补发。", 9.5f, V2Colors.ink(.42f))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = "重新扫码绑定",
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStart,
        )
        Spacer(Modifier.height(8.dp))
        SettingsActionRow(
            icon = Icons.Outlined.Logout,
            title = if (confirmDisconnect) "再次点击确认断开" else "断开个人微信",
            description = "清除本机保存的微信绑定凭据",
            danger = true,
            onClick = { if (confirmDisconnect) onDisconnect() else onAskDisconnect() },
        )
    } else if (active == null) {
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = if (loading) "正在向官方申请二维码…" else "连接个人微信",
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStart,
        )
        Spacer(Modifier.height(8.dp))
        Label(
            "二维码由 Flux 服务端向腾讯官方 iLink 申请；不需要安装 OpenClaw，也不用自己搭转发器。",
            9.5f,
            V2Colors.ink(.36f),
            maxLines = 3,
        )
    }

    if (login?.isFailed == true) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V2Colors.rose.copy(alpha = .08f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Label(login.message.ifBlank { "绑定失败，请重新扫码" }, 10.5f, V2Colors.rose700, maxLines = 4)
        }
        Spacer(Modifier.height(8.dp))
        GhostButton(
            text = "重新获取二维码",
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStart,
        )
    }
}

@Composable
private fun QrMatrixView(rows: List<String>, size: Dp = 216.dp) {
    val columns = rows.firstOrNull()?.length ?: 0
    if (columns == 0) return
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(10.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / columns
            val cellHeight = this.size.height / rows.size
            for (row in rows.indices) {
                val line = rows[row]
                for (column in line.indices) {
                    if (line[column] == '1') {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(column * cell, row * cellHeight),
                            size = Size(cell + 0.3f, cellHeight + 0.3f),
                        )
                    }
                }
            }
        }
    }
}
