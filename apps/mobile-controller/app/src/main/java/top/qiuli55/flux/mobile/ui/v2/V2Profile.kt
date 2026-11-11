package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「我的」一级页面：账号卡 + 三组管理入口。
 *
 * 管理类功能（设备、终端、密钥、额度、记忆、偏好、更新）全部收在这一页，
 * 「对话」页只保留对话本身。
 */
@Composable
fun V2ProfileScreen(
    accountName: String,
    deviceName: String,
    deviceCount: Int,
    memoryCount: Int,
    billingNote: String,
    version: String,
    extraTop: Dp,
    onOpenDevices: () -> Unit,
    onOpenTerminals: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenBilling: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = extraTop, bottom = 24.dp),
    ) {
        V2PageTitle("我的")
        Spacer(Modifier.height(4.dp))
        AccountCard(
            name = accountName,
            deviceName = deviceName,
            onClick = onOpenSettings,
        )

        Spacer(Modifier.height(14.dp))
        V2SettingsCard {
            V2SettingRow(
                icon = Icons.Outlined.Devices,
                title = "设备与连接",
                onClick = onOpenDevices,
                value = "$deviceCount 台",
                showDivider = true,
            )
            V2SettingRow(
                icon = Icons.Outlined.Terminal,
                title = "终端会话",
                onClick = onOpenTerminals,
            )
        }

        Spacer(Modifier.height(14.dp))
        V2SettingsCard {
            V2SettingRow(
                icon = Icons.Outlined.Key,
                title = "模型与密钥",
                onClick = onOpenKeys,
                showDivider = true,
            )
            V2SettingRow(
                icon = Icons.Outlined.Speed,
                title = "套餐额度",
                onClick = onOpenBilling,
                value = billingNote,
                showDivider = true,
            )
            V2SettingRow(
                icon = Icons.Outlined.Description,
                title = "用户记忆",
                onClick = onOpenMemories,
                value = "$memoryCount 条",
            )
        }

        Spacer(Modifier.height(14.dp))
        V2SettingsCard {
            V2SettingRow(
                icon = Icons.Outlined.NotificationsNone,
                title = "通知与偏好",
                onClick = onOpenSettings,
                showDivider = true,
            )
            V2SettingRow(
                icon = Icons.Outlined.Refresh,
                title = "检查更新",
                onClick = onCheckUpdate,
                value = version,
            )
        }
    }
}

@Composable
private fun AccountCard(
    name: String,
    deviceName: String,
    onClick: () -> Unit,
) {
    WhiteCard(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(V2Colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = accountsInitials(name),
                    color = V2Colors.actionInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(name, 18f, V2Colors.ink(.96f), FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Label("当前设备 · $deviceName", 13f, V2Colors.ink(.40f), maxLines = 1)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = V2Colors.ink(.22f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** li qiu → LQ；单段名字取前两个字符。 */
private fun accountsInitials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "FL"
    }
}
