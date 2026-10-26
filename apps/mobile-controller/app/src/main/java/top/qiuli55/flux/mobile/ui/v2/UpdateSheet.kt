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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.qiuli55.flux.mobile.BuildConfig
import top.qiuli55.flux.mobile.update.UpdateKind
import top.qiuli55.flux.mobile.update.UpdateManifest
import top.qiuli55.flux.mobile.update.UpdateState
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

/**
 * 应用内更新弹层。
 *
 * 五个阶段共用一个壳：可更新 / 下载中 / 等安装授权 / 已下载待安装 / 下载失败。
 * 视觉对齐设计稿（浅灰渐变、30dp 大圆角、青色主按钮、底部「跳过此版本」）。
 */
@Composable
fun UpdateSheet(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermission: () -> Unit,
    onCancelDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val manifest = state.manifest ?: return
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .86f) {
        UpdateHeader(manifest = manifest, onClose = onDismiss)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            UpdateVersionRow(manifest)
            Label("本次更新", 11f, V2Colors.ink(.60f), FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val notes = manifest.notes.ifEmpty { listOf("稳定性改进与问题修复") }
            notes.forEach { note ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(5.dp)
                            .background(V2Colors.cyan500, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Label(note, 12f, V2Colors.ink(.90f), modifier = Modifier.weight(1f))
                }
            }
            if (state.kind == UpdateKind.DOWNLOADING || state.progress > 0 && state.kind == UpdateKind.READY) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(state.progress)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label("${state.progress}%", 10.5f, V2Colors.cyan700, FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Label(
                        if (state.kind == UpdateKind.READY) {
                            "下载完成，点击「安装更新」继续"
                        } else {
                            "正在下载 ${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                        },
                        10.5f,
                        V2Colors.ink(.40f),
                        modifier = Modifier.weight(1f),
                    )
                    if (state.kind == UpdateKind.DOWNLOADING) {
                        Label(
                            "取消",
                            10.5f,
                            V2Colors.ink(.40f),
                            modifier = Modifier.clickable(onClick = onCancelDownload).padding(4.dp),
                        )
                    }
                }
            }
            if (state.kind == UpdateKind.PERMISSION) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(V2Colors.amber.copy(alpha = .08f))
                        .border(1.dp, V2Colors.amber.copy(alpha = .18f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    Label(
                        "需要允许 Flux 安装应用：打开系统设置 →「安装未知应用」→ 允许 Flux。返回后会自动继续安装。",
                        11f,
                        Color(0xFF92400E),
                    )
                    Spacer(Modifier.height(6.dp))
                    Label(
                        "去设置授权",
                        11f,
                        Color(0xFF92400E),
                        FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onOpenPermission).padding(vertical = 2.dp),
                    )
                }
            }
            state.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Label(message, 11f, V2Colors.rose)
            }
            Spacer(Modifier.height(12.dp))
        }
        UpdateFooter(
            state = state,
            onDownload = onDownload,
            onInstall = onInstall,
            onLater = onLater,
            onSkip = onSkip,
        )
    }
}

@Composable
private fun UpdateHeader(manifest: UpdateManifest, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(V2Colors.cyan500.copy(alpha = .12f))
                .border(1.dp, V2Colors.cyan700.copy(alpha = .18f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ArrowUpward, null, tint = V2Colors.cyan700, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label("发现新版本", 17f, V2Colors.ink(.95f), FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Label("Flux Mobile · ${publishedLabel(manifest.publishedAt)}", 11f, V2Colors.ink(.40f))
        }
        RoundIconButton(
            icon = Icons.Outlined.Close,
            onClick = onClose,
            size = 32.dp,
            iconSize = 14.dp,
            background = V2Colors.surface,
        )
    }
}

@Composable
private fun UpdateVersionRow(manifest: UpdateManifest) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Label("v${manifest.versionName}", 26f, V2Colors.ink(.95f), FontWeight.SemiBold)
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(V2Colors.ink(.05f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Label("当前 v${BuildConfig.VERSION_NAME}", 10f, V2Colors.ink(.40f))
        }
        Spacer(Modifier.weight(1f))
        Label(formatBytes(manifest.sizeBytes), 10.5f, V2Colors.ink(.40f))
    }
    ThinDivider(V2Colors.ink(.06f))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun UpdateFooter(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val downloading = state.kind == UpdateKind.DOWNLOADING
    val ready = state.kind == UpdateKind.READY || state.kind == UpdateKind.PERMISSION
    val failed = state.kind == UpdateKind.FAILED
    val text = when {
        downloading -> "正在下载 ${state.progress}%"
        ready -> "安装更新"
        failed -> "重新下载"
        else -> "立即更新"
    }
    val icon = when {
        downloading -> null
        ready -> Icons.Outlined.Check
        else -> Icons.Outlined.ArrowDownward
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = text,
                onClick = when {
                    downloading -> onDownload
                    ready -> onInstall
                    else -> onDownload
                },
                modifier = Modifier.weight(1f),
                icon = icon,
                enabled = !downloading,
            )
            if (!state.forced) {
                GhostButton("稍后", onLater, Modifier.width(96.dp))
            }
        }
        if (!state.forced) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSkip)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label("跳过此版本", 10.5f, V2Colors.ink(.40f))
            }
        }
        Label(
            "下载在后台进行；安装包来自 B 机分发节点",
            9f,
            V2Colors.ink(.28f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun publishedLabel(raw: String): String {
    if (raw.isBlank()) return "刚刚发布"
    val at = runCatching { OffsetDateTime.parse(raw) }.getOrNull() ?: return raw
    val date = at.toLocalDate()
    val time = String.format(Locale.US, "%02d:%02d", at.hour, at.minute)
    return if (date == LocalDate.now()) "今天 $time 发布" else "$date 发布"
}
