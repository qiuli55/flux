package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 删除单段对话的面板：正常状态按住圆环 0.8 秒才真正删除；
 * 运行中的对话先给「停止对话」，停止后面板自动切到可删除状态。
 */
@Composable
fun ConversationDeleteSheet(
    title: String,
    running: Boolean,
    deleting: Boolean,
    stopping: Boolean,
    error: String?,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .58f) {
        SheetHeader(
            title = if (running) "对话正在执行" else "删除这段对话",
            subtitle = title,
            onClose = onDismiss,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (running) V2Colors.amber.copy(alpha = .10f) else V2Colors.rose.copy(alpha = .07f))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (running) V2Colors.amber else V2Colors.rose),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Label(
                        if (running) "Agent 还在执行这段对话" else "删除后不可恢复",
                        12f,
                        V2Colors.ink(.78f),
                        FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Label(
                        if (running) {
                            "先停止执行，再删除。停止后聊天记录仍会保留，可随时再删。"
                        } else {
                            "这段对话的消息记录与 Agent 运行记录会一并彻底删除。"
                        },
                        11f,
                        V2Colors.ink(.48f),
                    )
                }
            }

            if (running) {
                Spacer(Modifier.height(18.dp))
                PrimaryButton(
                    text = if (stopping) "正在停止…" else "停止对话",
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Stop,
                    enabled = !stopping,
                )
                Spacer(Modifier.height(8.dp))
                Label("停止后即可删除这段对话", 10.5f, V2Colors.ink(.38f))
            } else if (deleting) {
                Spacer(Modifier.height(28.dp))
                Label("正在删除…", 12f, V2Colors.ink(.55f), FontWeight.Medium)
            } else {
                Spacer(Modifier.height(12.dp))
                HoldToConfirm(
                    durationMs = 800,
                    onConfirmed = onDelete,
                    icon = Icons.Outlined.Delete,
                    holdLabel = "按住删除",
                    hint = "按住 0.8 秒删除 · 松手即取消",
                )
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Label(error, 11f, V2Colors.rose, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            Spacer(Modifier.height(12.dp))
            GhostButton("取消", onDismiss, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}
