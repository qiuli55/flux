package top.qiuli55.flux.mobile.ui.devices

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.data.DeviceInfo
import top.qiuli55.flux.mobile.ui.EmptyHint
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.FluxCard
import top.qiuli55.flux.mobile.ui.LoadingRow
import top.qiuli55.flux.mobile.ui.SectionTitle
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/** 同账号下的设备列表：在线设备可直接进入，离线设备给出明确原因。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    onDeviceSelected: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = FluxColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("选择设备", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { viewModel.load(manual = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = FluxColors.text2)
                    }
                    TextButton(onClick = { viewModel.logout(onLoggedOut) }) {
                        Text("退出", color = FluxColors.text3)
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
            val account = state.account
            if (account != null) {
                Text(
                    "已登录 ${account.displayName.ifBlank { account.username }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FluxColors.text3,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.error != null) {
                ErrorBanner(state.error.orEmpty())
                Spacer(Modifier.height(12.dp))
            }
            if (state.loading) {
                LoadingRow("读取设备…")
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceCard(device) { viewModel.select(device, onDeviceSelected) }
                }
                if (!state.loading && state.devices.isEmpty()) {
                    item {
                        FluxCard {
                            SectionTitle("还没有设备")
                            Spacer(Modifier.height(6.dp))
                            EmptyHint(
                                "在电脑或服务器上打开 Flux 并登录同一个账号，" +
                                    "它会自动出现在这里。设备端需要保持在线（不需要公网 IP）。",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo, onClick: () -> Unit) {
    FluxCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = FluxColors.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${device.platformLabel} · ${if (device.isOnline) "在线" else "离线"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.isOnline) FluxColors.green else FluxColors.text3,
                )
            }
            Text(
                device.deviceId,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = FluxColors.text3,
            )
        }
    }
}
