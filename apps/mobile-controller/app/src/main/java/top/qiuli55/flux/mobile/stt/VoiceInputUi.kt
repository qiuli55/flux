package top.qiuli55.flux.mobile.stt

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.qiuli55.flux.mobile.ui.v2.Label
import top.qiuli55.flux.mobile.ui.v2.V2Colors
import top.qiuli55.flux.mobile.ui.v2.V2ComposerTags

/** 输入区是否被录音 / 转写 / 权限状态占位（失败时仍保留可编辑的输入框）。 */
fun voiceReplacesInput(state: VoiceInputState): Boolean = when (state) {
    is VoiceInputState.Recording,
    is VoiceInputState.Transcribing,
    is VoiceInputState.NeedPermission,
    -> true
    else -> false
}

/**
 * 创建页面级语音控制器。录音器 / 随包模型都跟随页面上下文；
 * 页面销毁时 [VoiceInputController.dispose] 会停止录音并取消后台任务。
 */
@Composable
internal fun rememberVoiceInputController(
    context: Context,
    conversationKey: Any?,
): VoiceInputController {
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext
    val controller = remember(appContext, conversationKey) {
        VoiceInputController(
            scope = scope,
            recorder = AndroidVoiceRecorder(appContext),
            modelSource = BundledVoiceModelSource(appContext),
            transcriberFactory = { model -> WhisperCppTranscriber(model) },
            permissionGranted = { hasRecordAudioPermission(appContext) },
            clock = { SystemClock.elapsedRealtime() },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    // 退到后台时丢弃录音；已经开始的转写允许结束，不会无限耗电。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return controller
}

/**
 * 麦克风按钮：48dp 触摸目标、34dp 视觉圆钮，与发送钮同一套黑白风格。
 *
 * 录音中变成本机停止方块；转写中显示细进度圈且不可重复点击。
 */
@Composable
internal fun VoiceMicButton(
    state: VoiceInputState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state is VoiceInputState.Recording
    val busy = state is VoiceInputState.Transcribing
    val clickable = enabled && !busy
    val description = when {
        recording -> "结束录音并转成文字"
        state is VoiceInputState.Transcribing -> "正在转成文字"
        else -> "语音转文字"
    }
    Box(
        modifier
            .size(48.dp)
            .testTag(V2ComposerTags.MIC)
            .clip(CircleShape)
            .clickable(enabled = clickable, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (recording) V2Colors.accent else V2Colors.ink(.05f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    color = V2Colors.ink(.45f),
                    strokeWidth = 1.6.dp,
                    modifier = Modifier.size(14.dp),
                )
                recording -> Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(V2Colors.actionInk),
                )
                else -> Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = if (enabled) V2Colors.ink(.62f) else V2Colors.ink(.20f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 输入框位置上的录音 / 转写 / 权限状态。 */
@Composable
internal fun VoiceStatusContent(
    state: VoiceInputState,
    onCancel: () -> Unit,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(V2ComposerTags.VOICE_STATUS)) {
        when (state) {
            is VoiceInputState.Recording -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(V2Colors.rose),
                )
                Spacer(Modifier.width(8.dp))
                Label(
                    "正在录音 ${formatVoiceDuration(state.elapsedMs)}",
                    14.5f,
                    V2Colors.ink(.90f),
                    modifier = Modifier.weight(1f),
                )
                VoiceTextAction("取消", onClick = onCancel)
            }

            is VoiceInputState.Transcribing -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = V2Colors.ink(.45f),
                    strokeWidth = 1.6.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Label(
                    "正在转成文字…",
                    14.5f,
                    V2Colors.ink(.90f),
                    modifier = Modifier.weight(1f),
                )
                VoiceTextAction("取消", onClick = onCancel)
            }

            is VoiceInputState.NeedPermission -> Column(Modifier.fillMaxWidth()) {
                Label(VOICE_PERMISSION_TITLE, 14.5f, V2Colors.ink(.90f), FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Label(
                    if (state.denied) {
                        "麦克风权限被拒绝。你可以到系统设置里允许 Flux 使用麦克风。"
                    } else {
                        "录音只在本机转成文字，不会上传到电脑或云端。"
                    },
                    10f,
                    V2Colors.ink(.42f),
                )
                Spacer(Modifier.height(7.dp))
                // 分成两行，窄屏 + 大字号下三个按钮不会被挤掉。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceTextAction(
                        if (state.denied) "重新授权" else "允许使用麦克风",
                        primary = true,
                        onClick = onAllow,
                    )
                    VoiceTextAction("取消", onClick = onCancel)
                }
                Spacer(Modifier.height(5.dp))
                VoiceTextAction("打开系统设置", onClick = onOpenSettings)
            }

            else -> Unit
        }
    }
}

/** 失败提示条：输入框仍可编辑，只在上方补一条原因与重试入口。 */
@Composable
internal fun VoiceFailureNotice(
    state: VoiceInputState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag(V2ComposerTags.VOICE_STATUS)
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.rose.copy(alpha = .07f))
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(state.message, 10.5f, V2Colors.rose700, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        VoiceTextAction("重试", onClick = onRetry)
        VoiceTextAction("关闭", onClick = onDismiss)
    }
}

@Composable
private fun VoiceTextAction(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape)
            .then(
                if (primary) {
                    Modifier.background(V2Colors.accent)
                } else {
                    Modifier.border(1.dp, V2Colors.line, shape)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            10.5f,
            if (primary) V2Colors.actionInk else V2Colors.ink(.62f),
            FontWeight.Medium,
        )
    }
}
