package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.ApiKeyEntry

/** 新建/修改密钥的请求草稿；keyId 为空表示新建。 */
data class V2KeyDraft(
    val keyId: String?,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>,
    val wireApi: String,
    val enabled: Boolean,
)

private data class V2KeyProvider(val value: String, val label: String)

private val keyProviders = listOf(
    V2KeyProvider("deepseek", "DeepSeek"),
    V2KeyProvider("openai", "OpenAI"),
    V2KeyProvider("openai_compatible", "兼容"),
    V2KeyProvider("anthropic", "Claude"),
)

@Composable
fun KeyVaultSheet(
    keys: List<ApiKeyEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSave: (V2KeyDraft, (String) -> Unit) -> Unit,
    onToggle: (ApiKeyEntry, Boolean, (String) -> Unit) -> Unit,
    onDelete: (ApiKeyEntry, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var formOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var name by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("deepseek") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelsText by remember { mutableStateOf("") }
    var wireApi by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ApiKeyEntry?>(null) }

    fun startForm(entry: ApiKeyEntry?) {
        editing = entry
        formOpen = true
        name = entry?.name.orEmpty()
        provider = entry?.provider?.takeIf { it.isNotBlank() } ?: "deepseek"
        baseUrl = entry?.baseUrl.orEmpty()
        apiKey = ""
        modelsText = entry?.models?.joinToString("\n").orEmpty()
        wireApi = entry?.wireApi.orEmpty()
        enabled = entry?.enabled ?: true
        error = ""
        notice = ""
        deleteTarget = null
    }

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .93f) {
        SheetHeader(
            title = "密钥库",
            subtitle = "加密保存，只显示末 4 位；模型下拉会列出可用 key 的模型",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(if (loading) "读取中…" else "共 ${keys.size} 条", 10.5f, V2Colors.ink(.40f))
                Spacer(Modifier.weight(1f))
                SmallPill("刷新", onClick = onRefresh)
                Spacer(Modifier.width(8.dp))
                SmallPill("添加密钥", primary = true) { startForm(null) }
            }

            if (notice.isNotBlank()) {
                Label(notice, 10.5f, V2Colors.emerald, maxLines = 2)
            }

            if (!formOpen && keys.isEmpty() && !loading) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("还没有密钥", 12.5f, V2Colors.ink(.80f), FontWeight.Medium)
                        Label(
                            "添加一条 DeepSeek / OpenAI / 兼容端点 / Claude 的 key 后，登记模型会出现在对应 Agent 的模型下拉里。",
                            10.5f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }

            keys.forEach { entry ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(entry.name, 12.5f, V2Colors.ink(.90f), FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
                            KeyPill(entry.providerTitle, V2Colors.cyan700)
                            Spacer(Modifier.width(6.dp))
                            KeyPill(if (entry.enabled) "启用" else "停用", if (entry.enabled) V2Colors.emerald else V2Colors.ink(.38f))
                        }
                        Label(
                            "${entry.baseUrl.ifBlank { "默认地址" }} · ${entry.keyHint}",
                            9.5f,
                            V2Colors.ink(.38f),
                            mono = true,
                            maxLines = 1,
                        )
                        Label(entry.models.joinToString(" / "), 9.5f, V2Colors.ink(.52f), mono = true)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label("启用", 10f, V2Colors.ink(.45f))
                            Spacer(Modifier.width(8.dp))
                            LightSwitch(entry.enabled, onChange = { value ->
                                onToggle(entry, value) { err ->
                                    if (err.isNotBlank()) notice = err
                                }
                            })
                            Spacer(Modifier.weight(1f))
                            SmallPill("编辑") { startForm(entry) }
                            Spacer(Modifier.width(8.dp))
                            SmallPill("删除", danger = true) { deleteTarget = entry }
                        }
                    }
                }
            }

            deleteTarget?.let { entry ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("删除「${entry.name}」？", 12f, V2Colors.rose, FontWeight.Medium)
                        Label("引用它的任务会回到 Agent 默认模型，此操作不可撤销。", 10f, V2Colors.ink(.45f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallPill("取消") { deleteTarget = null }
                            SmallPill("确认删除", danger = true) {
                                val target = deleteTarget
                                if (target != null) {
                                    onDelete(target) { err ->
                                        if (err.isBlank()) {
                                            deleteTarget = null
                                            notice = "已删除 ${target.name}"
                                        } else {
                                            notice = err
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (formOpen) {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(if (editing != null) "编辑密钥" else "添加密钥", 13f, V2Colors.ink(.90f), FontWeight.SemiBold)

                        KeyField("名称", name, { name = it }, "DeepSeek 官方")
                        Column {
                            Label("供应商", 9.5f, V2Colors.ink(.45f))
                            Spacer(Modifier.height(5.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                keyProviders.forEach { item ->
                                    val active = provider == item.value
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .background(
                                                if (active) V2Colors.cyan500.copy(alpha = .14f) else Color.White.copy(alpha = .6f),
                                                RoundedCornerShape(9.dp),
                                            )
                                            .border(
                                                1.dp,
                                                if (active) V2Colors.accent.copy(alpha = .35f) else V2Colors.ink(.10f),
                                                RoundedCornerShape(9.dp),
                                            )
                                            .clickable {
                                                provider = item.value
                                                if (item.value != "openai_compatible") wireApi = ""
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Label(item.label, 9.5f, if (active) V2Colors.cyan800 else V2Colors.ink(.50f))
                                    }
                                }
                            }
                        }

                        KeyField(
                            "Base URL",
                            baseUrl,
                            { baseUrl = it },
                            if (provider == "openai_compatible") "必填，例如 https://api.siliconflow.cn/v1" else "留空使用供应商默认地址",
                        )
                        KeyField(
                            "API Key",
                            apiKey,
                            { apiKey = it },
                            if (editing != null) "留空保持原 key" else "sk-...",
                            password = true,
                        )
                        KeyField(
                            "模型（每行一个）",
                            modelsText,
                            { modelsText = it },
                            "deepseek-v4-pro\ndeepseek-flash",
                            singleLine = false,
                        )

                        if (provider == "openai_compatible") {
                            Column {
                                Label("wire_api", 9.5f, V2Colors.ink(.45f))
                                Spacer(Modifier.height(5.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("" to "自动", "chat" to "chat", "responses" to "responses").forEach { (value, text) ->
                                        val active = wireApi == value
                                        SmallPill(text, active = active) { wireApi = value }
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("启用", 10f, V2Colors.ink(.45f))
                            Spacer(Modifier.width(8.dp))
                            LightSwitch(enabled, onChange = { enabled = it })
                        }

                        if (error.isNotBlank()) {
                            Label(error, 10f, V2Colors.rose, maxLines = 3)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallPill("取消") {
                                formOpen = false
                                editing = null
                                error = ""
                            }
                            SmallPill(if (editing != null) "保存修改" else "创建", primary = true, enabled = !saving) {
                                val cleanName = name.trim()
                                val models = modelsText
                                    .split('\n', ',', '，')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                when {
                                    cleanName.isBlank() -> error = "请填写密钥名称"
                                    models.isEmpty() -> error = "至少填写一个模型 id"
                                    editing == null && apiKey.isBlank() -> error = "请填写 API Key"
                                    else -> {
                                        error = ""
                                        saving = true
                                        onSave(
                                            V2KeyDraft(
                                                keyId = editing?.id,
                                                name = cleanName,
                                                provider = provider,
                                                baseUrl = baseUrl.trim(),
                                                apiKey = apiKey,
                                                models = models,
                                                wireApi = if (provider == "openai_compatible") wireApi else "",
                                                enabled = enabled,
                                            ),
                                        ) { err ->
                                            saving = false
                                            if (err.isBlank()) {
                                                formOpen = false
                                                editing = null
                                                notice = "已保存"
                                            } else {
                                                error = err
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    singleLine: Boolean = true,
) {
    Column {
        Label(label, 9.5f, V2Colors.ink(.45f))
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 38.dp else 84.dp)
                .background(V2Colors.surface(.65f), RoundedCornerShape(10.dp))
                .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Label(placeholder, 10.5f, V2Colors.ink(.28f))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = V2Colors.ink(.90f),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(V2Colors.accent),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SmallPill(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> V2Colors.ink(.06f)
        primary -> V2Colors.cyan400
        active -> V2Colors.cyan500.copy(alpha = .14f)
        danger -> V2Colors.rose.copy(alpha = .10f)
        else -> Color.White.copy(alpha = .65f)
    }
    val color = when {
        !enabled -> V2Colors.ink(.30f)
        primary -> V2Colors.actionInk
        active -> V2Colors.cyan800
        danger -> V2Colors.rose
        else -> V2Colors.ink(.58f)
    }
    Box(
        Modifier
            .height(30.dp)
            .background(background, RoundedCornerShape(9.dp))
            .border(
                1.dp,
                if (active) V2Colors.accent.copy(alpha = .30f) else V2Colors.ink(.08f),
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, 9.5f, color, if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun KeyPill(text: String, tint: Color) {
    Box(
        Modifier
            .background(tint.copy(alpha = .10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Label(text, 8.5f, tint, maxLines = 1)
    }
}
