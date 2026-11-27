package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.WorkspacePremium
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.ClaudeAccountStatus
import top.qiuli55.flux.mobile.data.ClaudeLoginSession
import top.qiuli55.flux.mobile.data.CodexAccountStatus
import top.qiuli55.flux.mobile.data.CodexLoginSession

/** 统一订阅入口的文案（测试锁定两家订阅都从这里进）。 */
internal val SUBSCRIPTION_PROVIDERS = listOf("ChatGPT", "Claude Code")

// ---------------------------------------------------------------- 页面

@Composable
fun SubscriptionsScreen(
    codexAccount: CodexAccountStatus?,
    codexLogin: CodexLoginSession?,
    codexLoading: Boolean,
    claudeAccount: ClaudeAccountStatus?,
    claudeLogin: ClaudeLoginSession?,
    claudeLoading: Boolean,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCodexLogin: () -> Unit,
    onCodexLogout: () -> Unit,
    onOpenCodexUrl: (String) -> Unit,
    onClaudeLogin: () -> Unit,
    onClaudeLogout: () -> Unit,
    onSubmitClaudeCode: (loginId: String, code: String, onResult: (String?) -> Unit) -> Unit,
    onOpenClaudeUrl: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

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
                "订阅与账号",
                22f,
                V2Colors.ink(.95f),
                FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.WorkspacePremium,
                contentDescription = null,
                tint = V2Colors.ink(.38f),
                modifier = Modifier.size(22.dp),
            )
        }
        Label(
            "在手机发起登录，电脑上的 Agent 直接用这份订阅跑任务。",
            12f,
            V2Colors.ink(.42f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                CodexAccountHeader(account = codexAccount, login = codexLogin, loading = codexLoading)
                ThinDivider(V2Colors.ink(.05f))
                if (codexAccount?.loggedIn == true) {
                    SettingsActionRow(
                        icon = Icons.Outlined.Logout,
                        title = "退出 ChatGPT 账号",
                        description = "退出后 Codex Run 不再使用订阅额度",
                        danger = true,
                        onClick = onCodexLogout,
                    )
                } else {
                    SettingsActionRow(
                        icon = Icons.Outlined.Link,
                        title = if (codexLoading) "正在启动登录…" else "登录 ChatGPT",
                        description = "打开授权页输入设备码，用订阅额度执行任务",
                        danger = false,
                        onClick = onCodexLogin,
                    )
                }
                if (codexLogin != null && (codexLogin.isPending || codexLogin.status == "failed")) {
                    ThinDivider(V2Colors.ink(.05f))
                    CodexLoginPanel(codexLogin, onOpenUrl = onOpenCodexUrl)
                }
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ClaudeAccountHeader(
                    account = claudeAccount,
                    login = claudeLogin,
                    loading = claudeLoading,
                )
                ThinDivider(V2Colors.ink(.05f))
                if (claudeAccount?.loggedIn == true) {
                    SettingsActionRow(
                        icon = Icons.Outlined.Logout,
                        title = "退出 Claude Code 订阅",
                        description = "退出后 Run 不再注入这份订阅；仍可使用密钥库",
                        danger = true,
                        onClick = onClaudeLogout,
                    )
                } else {
                    SettingsActionRow(
                        icon = Icons.Outlined.Link,
                        title = if (claudeLoading) "正在启动登录…" else "登录 Claude Code",
                        description = "官方 setup-token：浏览器授权后把授权码粘回来",
                        danger = false,
                        onClick = onClaudeLogin,
                    )
                }
                if (claudeLogin != null &&
                    (claudeLogin.isPending || claudeLogin.status == "failed")
                ) {
                    ThinDivider(V2Colors.ink(.05f))
                    ClaudeLoginPanel(
                        session = claudeLogin,
                        loading = claudeLoading,
                        onOpenUrl = onOpenClaudeUrl,
                        onSubmitCode = onSubmitClaudeCode,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            WhiteCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = V2Colors.ink(.42f),
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Label(
                        "订阅登录态保存在电脑上的 Flux 隔离目录，Run 时才注入对应 Agent；" +
                            "手机上只是发起授权，不会把令牌留在 App 里。",
                        10.5f,
                        V2Colors.ink(.40f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ChatGPT / Codex

private fun codexPlanLabel(plan: String?): String = when (plan?.lowercase()) {
    "plus" -> "Plus"
    "pro" -> "Pro"
    "team", "business" -> "Team"
    "enterprise" -> "Enterprise"
    "edu", "education" -> "Edu"
    "free" -> "Free"
    null, "" -> "账号"
    else -> plan.replaceFirstChar { it.uppercase() }
}

@Composable
private fun CodexAccountHeader(
    account: CodexAccountStatus?,
    login: CodexLoginSession?,
    loading: Boolean,
) {
    val loggedIn = account?.loggedIn == true
    val plan = codexPlanLabel(account?.plan)
    val subtitle = when {
        loggedIn && account?.plan == "free" -> "Free 套餐 · 本地 Codex 用 GPT-5.6 Luna（额度较少）"
        loggedIn -> account?.email ?: "已连接 ChatGPT 账号"
        login?.isPending == true -> "等待浏览器授权…"
        loading -> "读取账号状态…"
        account == null -> "账号状态暂不可用"
        else -> "未登录 · 登录后使用订阅额度"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.SmartToy,
            contentDescription = null,
            tint = V2Colors.ink(.55f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label("ChatGPT 账号", 13.5f, V2Colors.ink(.90f))
            Spacer(Modifier.height(2.dp))
            Label(subtitle, 10f, V2Colors.ink(.36f), maxLines = 2)
        }
        if (loggedIn) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (account?.plan == "free") {
                            V2Colors.ink(.07f)
                        } else {
                            V2Colors.accent.copy(alpha = .12f)
                        },
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Label(
                    plan,
                    9.5f,
                    if (account?.plan == "free") V2Colors.ink(.42f) else V2Colors.accent,
                    FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CodexLoginPanel(session: CodexLoginSession, onOpenUrl: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(session.code) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (session.status == "failed") {
            Label("登录失败", 11f, V2Colors.rose, FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Label(session.message.ifBlank { "codex 登录进程已退出" }, 9.5f, V2Colors.ink(.38f))
            return@Column
        }
        Label("1. 打开授权页并登录 ChatGPT", 10.5f, V2Colors.ink(.45f))
        if (session.url.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            AuthLinkButton("打开授权页", session.url, onOpenUrl)
        }
        if (session.code.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Label("2. 输入设备码（15 分钟内有效）", 10.5f, V2Colors.ink(.45f))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(
                    session.code,
                    22f,
                    V2Colors.ink(.90f),
                    FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2Colors.ink(.06f))
                        .clickable {
                            clipboard.setText(AnnotatedString(session.code))
                            copied = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Label(if (copied) "已复制" else "复制", 10.5f, V2Colors.ink(.55f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Label("授权完成后这里会自动更新，无需手动刷新", 9.5f, V2Colors.ink(.34f))
    }
}

// ---------------------------------------------------------------- Claude Code

@Composable
private fun ClaudeAccountHeader(
    account: ClaudeAccountStatus?,
    login: ClaudeLoginSession?,
    loading: Boolean,
) {
    val loggedIn = account?.loggedIn == true
    val subtitle = when {
        loggedIn -> "已连接 · 电脑上的 Claude Code 会使用这份订阅"
        login?.isPending == true -> "等待浏览器授权…"
        loading -> "读取账号状态…"
        account == null -> "账号状态暂不可用"
        else -> "未登录 · 登录后使用 Claude 订阅额度"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WorkspacePremium,
            contentDescription = null,
            tint = V2Colors.ink(.55f),
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label("Claude Code", 13.5f, V2Colors.ink(.90f))
            Spacer(Modifier.height(2.dp))
            Label(subtitle, 10f, V2Colors.ink(.36f), maxLines = 2)
        }
        if (loggedIn) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(V2Colors.accent.copy(alpha = .12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Label("订阅", 9.5f, V2Colors.accent, FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ClaudeLoginPanel(
    session: ClaudeLoginSession,
    loading: Boolean,
    onOpenUrl: (String) -> Unit,
    onSubmitCode: (loginId: String, code: String, onResult: (String?) -> Unit) -> Unit,
) {
    var code by remember(session.loginId) { mutableStateOf("") }
    var submitError by remember(session.loginId) { mutableStateOf<String?>(null) }
    var submitting by remember(session.loginId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (session.status == "failed") {
            Label("登录失败", 11f, V2Colors.rose, FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Label(
                session.message.ifBlank { "claude setup-token 已退出" },
                9.5f,
                V2Colors.ink(.38f),
            )
            return@Column
        }
        Label("1. 打开授权页并登录 Claude", 10.5f, V2Colors.ink(.45f))
        if (session.url.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            AuthLinkButton("打开授权页", session.url, onOpenUrl)
        } else if (session.message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Label(session.message, 9.5f, V2Colors.ink(.38f))
        }
        if (session.needsCode) {
            Spacer(Modifier.height(12.dp))
            Label("2. 把页面上显示的授权码粘回这里", 10.5f, V2Colors.ink(.45f))
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.surface(.72f))
                    .border(1.dp, V2Colors.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (code.isEmpty()) {
                    Label("粘贴授权码（形如 code#state）", 11f, V2Colors.ink(.30f), mono = false)
                }
                BasicTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        submitError = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.5.sp, color = V2Colors.ink(.86f)),
                    cursorBrush = SolidColor(V2Colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = when {
                    submitting || loading -> "正在提交…"
                    else -> "提交授权码"
                },
                enabled = code.isNotBlank() && !submitting && !loading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    submitting = true
                    submitError = null
                    onSubmitCode(session.loginId, code.trim()) { error ->
                        submitting = false
                        submitError = error
                    }
                },
            )
        }
        submitError?.let { message ->
            Spacer(Modifier.height(8.dp))
            Label(message, 8.5f, V2Colors.rose700)
        }
        Spacer(Modifier.height(10.dp))
        Label("授权完成后这里会自动更新，无需手动刷新", 9.5f, V2Colors.ink(.34f))
    }
}

@Composable
private fun AuthLinkButton(text: String, url: String, onOpenUrl: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.accent.copy(alpha = .10f))
            .clickable { onOpenUrl(url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Language,
            contentDescription = null,
            tint = V2Colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Label(text, 12f, V2Colors.accent, FontWeight.Medium, modifier = Modifier.weight(1f))
        Label("在内置浏览器打开", 9f, V2Colors.ink(.34f))
    }
}
