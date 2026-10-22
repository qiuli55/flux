package top.qiuli55.flux.mobile.ui.v2

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.qiuli55.flux.mobile.BuildConfig
import top.qiuli55.flux.mobile.R
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudAuthBus
import top.qiuli55.flux.mobile.data.DeviceInfo
import top.qiuli55.flux.mobile.ui.auth.LoginViewModel
import top.qiuli55.flux.mobile.ui.setup.SetupViewModel

/**
 * v2 登录页：邮箱密码 + GitHub OAuth + 手动连接三路入口。
 *
 * OAuth 完成后浏览器回跳 `flux://auth?code=...`，由 MainActivity 投递到 [CloudAuthBus]。
 */
@Composable
fun V2LoginScreen(
    onSignedIn: (AccountSession) -> Unit,
    onManualConnected: () -> Unit,
    hint: String? = null,
) {
    val vm: LoginViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var manualOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CloudAuthBus.codes.collect { code -> vm.exchangeCode(code, onSignedIn) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().shellBackground()) {
        val tablet = maxWidth >= 600.dp
        Column(
            Modifier
                .align(Alignment.Center)
                .then(Modifier.statusBarsPadding().navigationBarsPadding())
                .widthIn(max = if (tablet) 460.dp else 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(V2Colors.accent.copy(alpha = .12f))
                    .border(1.dp, V2Colors.accent.copy(alpha = .18f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flux_mark),
                    contentDescription = "Flux",
                    tint = V2Colors.cyan700,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Label("登录 Flux", 24f, V2Colors.ink(.95f), FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Label(
                "登录后，同一个账号下的电脑和服务器都会出现在设备列表里。",
                11.5f,
                V2Colors.ink(.45f),
            )

            if (hint != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(V2Colors.rose.copy(alpha = .08f))
                        .border(1.dp, V2Colors.rose.copy(alpha = .18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Label(hint, 11f, V2Colors.rose700)
                }
            }

            Spacer(Modifier.height(20.dp))
            WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    V2Field(
                        label = "账号",
                        value = state.identifier,
                        placeholder = "邮箱或用户名",
                        onValueChange = vm::onIdentifierChange,
                        keyboardType = KeyboardType.Email,
                    )
                    Spacer(Modifier.height(12.dp))
                    V2Field(
                        label = "密码",
                        value = state.password,
                        placeholder = "登录密码",
                        onValueChange = vm::onPasswordChange,
                        password = true,
                        keyboardType = KeyboardType.Password,
                    )
                    if (state.error != null) {
                        Spacer(Modifier.height(10.dp))
                        Label(state.error.orEmpty(), 11f, V2Colors.rose700)
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = if (state.busy) "登录中…" else "登录",
                        onClick = { vm.login(onSignedIn) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(V2Colors.line))
                Label("或", 10f, V2Colors.ink(.30f), modifier = Modifier.padding(horizontal = 10.dp))
                Box(Modifier.weight(1f).height(1.dp).background(V2Colors.line))
            }
            Spacer(Modifier.height(18.dp))

            GhostButton(
                text = "使用 GitHub 登录",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.githubUrl())))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "手动连接服务器",
                onClick = { manualOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            Label(
                "Flux Mobile v${BuildConfig.VERSION_NAME}",
                10f,
                V2Colors.ink(.28f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (manualOpen) {
        ManualConnectSheet(
            onDismiss = { manualOpen = false },
            onConnected = {
                manualOpen = false
                onManualConnected()
            },
        )
    }
}

/** v2 设备列表：账号下在线设备可直接切入，离线设备给出提示。 */
@Composable
fun V2DeviceScreen(
    state: V2AppState,
    onReload: () -> Unit,
    onSelect: (DeviceInfo) -> Unit,
    onLogout: () -> Unit,
    onManualConnect: () -> Unit,
) {
    val account = state.account
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("选择设备", 24f, V2Colors.ink(.95f), FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Label(
                    if (account != null) {
                        listOf(account.displayName.ifBlank { account.username }, account.email)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    } else {
                        "手动连接模式"
                    },
                    11f,
                    V2Colors.ink(.42f),
                )
            }
            RoundIconButton(
                icon = Icons.Outlined.Refresh,
                onClick = onReload,
                size = 36.dp,
                iconSize = 16.dp,
                background = Color.White.copy(alpha = .72f),
            )
        }
        Spacer(Modifier.height(16.dp))

        if (state.loadingDevices && state.devices.isEmpty()) {
            Label("正在读取设备列表…", 12f, V2Colors.ink(.42f))
            Spacer(Modifier.height(8.dp))
        }
        val error = state.error
        if (error != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.rose.copy(alpha = .08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Label(error, 11f, V2Colors.rose700)
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (state.devices.isEmpty() && !state.loadingDevices) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                        Label("这个账号下还没有设备", 13f, V2Colors.ink(.72f), FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Label(
                            "在电脑上打开 Flux 桌面端并登录同一账号，设备就会出现在这里。",
                            11f,
                            V2Colors.ink(.40f),
                        )
                    }
                }
            }
            items(state.devices, key = { it.deviceId }) { device ->
                DeviceRow(
                    device = device,
                    connecting = state.busyAction == "device:${device.deviceId}",
                    onClick = { onSelect(device) },
                )
            }
        }

        GhostButton(
            text = "使用其他账号登录",
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(
            text = "手动连接服务器",
            onClick = onManualConnect,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    WhiteCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(V2Colors.accent.copy(alpha = .10f)),
                contentAlignment = Alignment.Center,
            ) {
                DeviceIcon(deviceKind(device.name), V2Colors.cyan700, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(
                        device.name.ifBlank { device.deviceId.take(8) },
                        13.5f,
                        V2Colors.ink(.92f),
                        FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    V2Chip(
                        text = if (device.isOnline) "在线" else "离线",
                        color = if (device.isOnline) V2Colors.emerald700 else V2Colors.ink(.42f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Label(
                    listOfNotNull(
                        device.platformLabel,
                        device.version?.takeIf { it.isNotBlank() },
                        device.lastSeenAt?.let { "最近 ${relativeTime(it)}" },
                    ).joinToString(" · "),
                    10.5f,
                    V2Colors.ink(.40f),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (connecting) {
                Label("连接中…", 10.5f, V2Colors.accent)
            } else {
                RoundIconButton(
                    icon = Icons.Outlined.ArrowForward,
                    onClick = onClick,
                    size = 28.dp,
                    iconSize = 14.dp,
                )
            }
        }
    }
}

/** 手动连接：服务器地址 + 访问令牌，支持先测试再保存。 */
@Composable
fun ManualConnectSheet(
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
) {
    val vm: SetupViewModel = viewModel()
    val state by vm.state.collectAsState()

    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .9f) {
        SheetHeader(
            title = "手动连接",
            subtitle = "填写 Flux 服务地址与访问令牌",
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            V2Field(
                label = "服务地址",
                value = state.baseUrl,
                placeholder = "https://flux.example.com/mobile",
                onValueChange = vm::onBaseUrlChange,
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(12.dp))
            V2Field(
                label = "访问令牌",
                value = state.token,
                placeholder = "Bearer Token",
                onValueChange = vm::onTokenChange,
                password = true,
            )

            val health = state.health
            if (health != null) {
                Spacer(Modifier.height(14.dp))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    Label("连接正常 · ${health.status}", 11f, V2Colors.emerald700, FontWeight.Medium)
                    if (state.dsh?.model != null) {
                        Spacer(Modifier.height(4.dp))
                        Label("模型 ${state.dsh?.model}", 10.5f, V2Colors.ink(.45f))
                    }
                    if (state.agentCount != null) {
                        Spacer(Modifier.height(2.dp))
                        Label("已配置 ${state.agentCount} 个 Agent", 10.5f, V2Colors.ink(.45f))
                    }
                }
            }
            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Label(state.error.orEmpty(), 11f, V2Colors.rose700)
            }
            if (state.savedHint != null) {
                Spacer(Modifier.height(12.dp))
                Label(state.savedHint.orEmpty(), 11f, V2Colors.emerald700)
            }
            Spacer(Modifier.height(18.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            GhostButton(
                text = if (state.testing) "测试中…" else "测试连接",
                onClick = { vm.test() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.testing && !state.saving,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (state.saving) "保存中…" else "保存并连接",
                onClick = { vm.save { onConnected() } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.testing && !state.saving,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** v2 输入框：白底 46dp 圆角 12dp，占位符内联。 */
@Composable
internal fun V2Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier.fillMaxWidth()) {
        Label(label, 10.5f, V2Colors.ink(.42f), FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, V2Colors.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Label(placeholder, 12.5f, V2Colors.ink(.28f), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = V2Colors.ink),
                cursorBrush = SolidColor(V2Colors.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        }
    }
}
