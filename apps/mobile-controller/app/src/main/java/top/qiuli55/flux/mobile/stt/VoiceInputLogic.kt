package top.qiuli55.flux.mobile.stt

import java.util.Locale

/**
 * 语音转文字的纯逻辑：状态机、文案、追加规则。
 *
 * 这里不碰 Android API，也不包含 Compose / 协程调度，便于在 JVM 单测里
 * 覆盖「权限、模型可用性、取消、失败、成功」这些分支。
 */

/** Whisper 固定用中文，避免短句被误判成英文或其他语言。 */
const val VOICE_TRANSCRIPTION_LANGUAGE: String = "zh"

/** 单段录音上限：2 分钟，避免长时间占用麦克风与电量。 */
const val VOICE_MAX_RECORDING_MS: Long = 120_000L

/** 单次转写看门狗：超时后报「转写超时」，不写入半段结果。 */
const val VOICE_TRANSCRIPTION_TIMEOUT_MS: Long = 90_000L

/** 手机 CPU 推理线程数上限（JNI 内还会再夹到 1..4）。 */
const val VOICE_INFERENCE_THREADS: Int = 4

/** 语音转文字界面状态。Success 只停留一帧，文本写入输入框后回到 Idle。 */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    /** 还没有麦克风权限；[denied] 表示系统已经拒绝过一次，引导去设置。 */
    data class NeedPermission(val denied: Boolean = false) : VoiceInputState

    data class Recording(val elapsedMs: Long) : VoiceInputState

    data object Transcribing : VoiceInputState

    /** 转写结果只交给输入框，绝不自动发送。 */
    data class Success(val text: String) : VoiceInputState

    data class Failed(val failure: VoiceFailure, val message: String) : VoiceInputState

    /** 用户主动取消；不写输入框、不发消息。 */
    data object Cancelled : VoiceInputState
}

/** 失败原因；文案由 [voiceFailureMessage] 统一生成。 */
enum class VoiceFailure {
    /** 安装包里的模型缺失或损坏，且无法从 APK 重新展开。与网络无关。 */
    ModelUnavailable,
    MicrophoneUnavailable,
    RecordingFailed,
    NoSpeech,
    TranscriptionFailed,
    Timeout,
}

/** 点麦克风后该做什么：纯函数，单测直接覆盖。 */
enum class VoiceStartDecision {
    NeedPermission,
    Record,
    ModelUnavailable,
}

/**
 * 模型随安装包提供，因此只需看权限与「模型是否可用（已展开或 APK 内自带）」，
 * 不再有任何联网 / 下载分支。
 */
fun decideVoiceStart(
    hasPermission: Boolean,
    modelAvailable: Boolean,
): VoiceStartDecision = when {
    !hasPermission -> VoiceStartDecision.NeedPermission
    modelAvailable -> VoiceStartDecision.Record
    else -> VoiceStartDecision.ModelUnavailable
}

fun voiceFailureMessage(failure: VoiceFailure): String = when (failure) {
    VoiceFailure.ModelUnavailable -> "语音转文字组件不可用，请重新安装应用后再试"
    VoiceFailure.MicrophoneUnavailable -> "无法开始录音，请检查麦克风是否被占用"
    VoiceFailure.RecordingFailed -> "录音失败，请再录一次"
    VoiceFailure.NoSpeech -> "没有听清，请再录一次"
    VoiceFailure.TranscriptionFailed -> "转写失败，请再录一次"
    VoiceFailure.Timeout -> "转写超时，请再录一次"
}

/**
 * 把转写文本追加到已有草稿：
 *  - 空输入（或只有空白）直接写入；
 *  - 已有文本非空时补一个空格，避免两段粘成一团；
 *  - 已有文本本身以空白结尾时不重复补空格。
 */
fun appendTranscript(existing: String, transcript: String): String {
    val addition = transcript.trim()
    if (addition.isEmpty()) return existing
    if (existing.isBlank()) return addition
    return if (existing.last().isWhitespace()) existing + addition else "$existing $addition"
}

/** 录音时长显示：12 秒 → "00:12"，超过 1 分钟 → "01:05"。 */
fun formatVoiceDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

/** 麦克风权限被拒绝后的引导文案。 */
const val VOICE_PERMISSION_TITLE: String = "需要麦克风才能转成文字"
