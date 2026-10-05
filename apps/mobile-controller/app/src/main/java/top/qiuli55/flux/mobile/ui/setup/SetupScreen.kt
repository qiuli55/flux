package top.qiuli55.flux.mobile.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.ui.EmptyHint
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.KeyValueRow
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.SectionTitle
import top.qiuli55.flux.mobile.ui.ThinDivider
import top.qiuli55.flux.mobile.ui.fluxFieldColors
import top.qiuli55.flux.mobile.ui.theme.FluxColors
import top.qiuli55.flux.mobile.ui.theme.FluxMono

/**
 * 连接设置（同时是设置页）。
 *
 * 首次进入必须先过这一页：服务器地址有默认值（交付的实例），令牌需要粘贴一次——
 * 令牌只在本地保存，界面里默认打码显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onBack: (() -> Unit)? = null,
    onSaved: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var showToken by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (onBack == null) "连接 Flux" else "设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = FluxColors.text2)
                        }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "填服务端地址与访问令牌。令牌由部署者在服务器的 flux-server.env 里生成（FLUX_AUTH_TOKEN）。",
                style = MaterialTheme.typography.bodySmall,
                color = FluxColors.text2,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("服务器地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = fluxFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::onTokenChange,
                label = { Text("访问令牌") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showToken) "隐藏令牌" else "显示令牌",
                            tint = FluxColors.text3,
                        )
                    }
                },
                colors = fluxFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::test,
                    enabled = !state.testing && !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
                Button(
                    onClick = { viewModel.save(onSaved) },
                    enabled = !state.saving && !state.testing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.saving) "保存中…" else "保存")
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                ErrorBanner(state.error!!)
            }
            if (state.savedHint != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.savedHint!!, style = MaterialTheme.typography.bodySmall, color = FluxColors.green)
            }

            Spacer(Modifier.height(18.dp))
            if (state.testing) {
                LoadingRow("正在测试连接…")
            }

            val health = state.health
            if (health != null) {
                SectionTitle("服务端")
                FluxCard {
                    KeyValueRow("应用", "${health.app}（${health.status}）")
                    KeyValueRow("环境", health.env, mono = true)
                }
                Spacer(Modifier.height(12.dp))
            }

            val dsh = state.dsh
            if (dsh != null) {
                SectionTitle("Agent Runtime")
                FluxCard {
                    KeyValueRow("状态", if (dsh.enabled) "已启用" else "未启用")
                    KeyValueRow("模型", "${dsh.provider ?: "—"} / ${dsh.model ?: "—"}", mono = true)
                    KeyValueRow("工作区", dsh.workspace ?: "—", mono = true)
                    if (state.agentCount != null) {
                        ThinDivider(Modifier.padding(vertical = 6.dp))
                        KeyValueRow("Agent 数", state.agentCount.toString())
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (health == null && !state.testing) {
                EmptyHint("点「测试连接」可以确认地址与令牌是否真的可用（会真实请求一次服务端）。")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 等宽字体的输入框（终端命令用）。 */
val MonoFieldStyle = androidx.compose.ui.text.TextStyle(fontFamily = FluxMono)