package top.qiuli55.flux.mobile.stt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 录音器：真实实现走 AudioRecord；单测用假实现。 */
interface VoiceRecorder {
    /** 开始录音；返回 false 表示麦克风不可用。 */
    fun start(): Boolean

    /** 停止并返回本机临时 WAV；失败返回 null。 */
    fun stop(): File?

    /** 丢弃当前录音并删除临时文件。 */
    fun cancel()
}

/** 本机转写器：真实实现走 whisper.cpp JNI；单测用假实现。 */
interface VoiceTranscriber {
    suspend fun transcribe(wav: File, language: String): String
}

/**
 * 语音模型来源：真实实现从 APK assets 展开官方量化权重并校验 SHA-256；
 * 单测用假实现控制「可用 / 缺失 / 展开失败」。全程不访问网络。
 */
interface VoiceModelSource {
    /** 模型是否可用（已展开且完整，或可从安装包展开）。只做本地查询。 */
    fun isAvailable(): Boolean

    /** 确保模型在本机可用并返回文件；只从安装包展开，不联网。失败返回 null。 */
    suspend fun prepare(): File?
}

/**
 * 语音转文字控制器：把权限、模型准备、录音、转写串成一条状态机。
 *
 * 关键约束：
 *  - 本类没有任何「发送消息」的能力，转写成功只是进入 Success，由 UI 写入输入框；
 *  - 取消 / 失败 / 超时都不会写入半段文本；
 *  - 模型随安装包提供，首次点麦克风直接录音，转写前才在本机展开，不依赖网络；
 *  - 转写在本机后台线程执行，不依赖电脑或云端。
 */
class VoiceInputController internal constructor(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    private val modelSource: VoiceModelSource,
    private val transcriberFactory: (File) -> VoiceTranscriber,
    private val permissionGranted: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val transcriptionTimeoutMs: Long = VOICE_TRANSCRIPTION_TIMEOUT_MS,
    private val maxRecordingMs: Long = VOICE_MAX_RECORDING_MS,
) {
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private var recordingStartedAt = 0L
    private var tickerJob: Job? = null
    private var operationJob: Job? = null
    private var inferenceJob: Job? = null
    private var activeWav: File? = null
    private var session: Long = 0L
    private var disposed = false

    /** 麦克风按钮的唯一入口：空闲开始，录音中结束并转写，转写中取消。 */
    fun onMicClick() {
        if (disposed) return
        when (_state.value) {
            is VoiceInputState.Recording -> finishRecording()
            is VoiceInputState.Transcribing -> cancel()
            is VoiceInputState.NeedPermission -> Unit
            else -> start()
        }
    }

    /** 权限面板里的「允许使用麦克风」通过后由 UI 调用。 */
    fun onPermissionResult(granted: Boolean) {
        if (disposed) return
        if (granted) {
            start()
        } else {
            _state.value = VoiceInputState.NeedPermission(denied = true)
        }
    }

    /** 失败后点「重试」。 */
    fun retry() {
        if (disposed) return
        if (_state.value is VoiceInputState.Failed || _state.value is VoiceInputState.Cancelled) {
            start()
        }
    }

    /** 成功文本已经写入输入框后调用，状态回到 Idle。 */
    fun consumeSuccess() {
        if (_state.value is VoiceInputState.Success) {
            _state.value = VoiceInputState.Idle
        }
    }

    /** 关闭失败 / 权限提示，不改输入框。 */
    fun dismiss() {
        when (_state.value) {
            is VoiceInputState.Failed,
            is VoiceInputState.Cancelled,
            is VoiceInputState.NeedPermission,
            -> _state.value = VoiceInputState.Idle
            else -> Unit
        }
    }

    /** 取消当前录音 / 转写；不写输入框、不发送消息。 */
    fun cancel() {
        when (_state.value) {
            is VoiceInputState.Recording -> {
                tickerJob?.cancel()
                tickerJob = null
                session++
                recorder.cancel()
                _state.value = VoiceInputState.Cancelled
            }
            is VoiceInputState.Transcribing -> {
                session++
                operationJob?.cancel()
                operationJob = null
                inferenceJob?.cancel()
                inferenceJob = null
                // 推理若尚未真正开始，finally 不会执行；这里兜底删掉临时 WAV。
                activeWav?.delete()
                activeWav = null
                _state.value = VoiceInputState.Cancelled
            }
            else -> Unit
        }
    }

    private fun start() {
        if (disposed || _state.value is VoiceInputState.Recording) return
        when (
            decideVoiceStart(
                hasPermission = permissionGranted(),
                modelAvailable = modelSource.isAvailable(),
            )
        ) {
            VoiceStartDecision.NeedPermission -> {
                _state.value = VoiceInputState.NeedPermission(denied = false)
            }
            VoiceStartDecision.ModelUnavailable -> fail(VoiceFailure.ModelUnavailable)
            VoiceStartDecision.Record -> beginRecording()
        }
    }

    private fun beginRecording() {
        if (!permissionGranted()) {
            _state.value = VoiceInputState.NeedPermission(denied = false)
            return
        }
        if (!recorder.start()) {
            fail(VoiceFailure.MicrophoneUnavailable)
            return
        }
        recordingStartedAt = clock()
        _state.value = VoiceInputState.Recording(0L)
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(200L)
                if (_state.value !is VoiceInputState.Recording) break
                val elapsed = (clock() - recordingStartedAt).coerceAtLeast(0L)
                _state.value = VoiceInputState.Recording(elapsed)
                if (elapsed >= maxRecordingMs) {
                    finishRecording()
                    break
                }
            }
        }
    }

    private fun finishRecording() {
        if (_state.value !is VoiceInputState.Recording) return
        tickerJob?.cancel()
        tickerJob = null
        // 先显示「正在转成文字」，停止录音与写 WAV 头放到 IO 线程。
        _state.value = VoiceInputState.Transcribing
        val id = ++session
        operationJob?.cancel()
        operationJob = scope.launch {
            val wav = withContext(ioDispatcher) { recorder.stop() }
            if (id != session || !isActive) {
                wav?.delete()
                return@launch
            }
            if (wav == null || !wav.isFile || wav.length() <= 44L) {
                wav?.delete()
                fail(VoiceFailure.RecordingFailed)
                return@launch
            }
            // 首次使用时在这里把模型从 APK assets 展开到私有目录；只读本地，
            // 不访问网络。展开失败说明安装包里的模型缺失 / 损坏。
            val model = withContext(ioDispatcher) { modelSource.prepare() }
            if (id != session || !isActive) {
                wav.delete()
                return@launch
            }
            if (model == null || !model.isFile) {
                wav.delete()
                fail(VoiceFailure.ModelUnavailable)
                return@launch
            }
            runInference(wav, model, id)
        }
    }

    private suspend fun runInference(wav: File, model: File, id: Long) {
        activeWav = wav
        val deferred = scope.async(inferenceDispatcher) {
            try {
                transcriberFactory(model).transcribe(wav, VOICE_TRANSCRIPTION_LANGUAGE)
            } finally {
                // 转写成功、失败、取消、超时后都清理临时录音。
                wav.delete()
                if (activeWav === wav) activeWav = null
            }
        }
        inferenceJob = deferred
        val outcome = withTimeoutOrNull(transcriptionTimeoutMs) {
            runCatching { deferred.await() }
        }
        if (id != session) return
        when {
            outcome == null -> {
                // 看门狗先返回：结果作废，JNI 即使稍后返回也不会写入输入框。
                session++
                deferred.cancel()
                fail(VoiceFailure.Timeout)
            }
            outcome.isSuccess -> {
                val text = outcome.getOrNull().orEmpty().trim()
                if (text.isEmpty()) {
                    fail(VoiceFailure.NoSpeech)
                } else {
                    _state.value = VoiceInputState.Success(text)
                }
            }
            else -> fail(VoiceFailure.TranscriptionFailed)
        }
    }

    private fun fail(failure: VoiceFailure) {
        _state.value = VoiceInputState.Failed(failure, voiceFailureMessage(failure))
    }

    /**
     * App 退到后台：立刻丢弃正在进行的录音（避免在后台持续占用麦克风 / 耗电）。
     * 已经开始的转写允许自然结束，结果仍只写入输入框。
     */
    fun onBackground() {
        if (_state.value is VoiceInputState.Recording) cancel()
    }

    /** 页面退出 / 控制器销毁：停止录音并取消所有后台工作。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        session++
        tickerJob?.cancel()
        tickerJob = null
        operationJob?.cancel()
        operationJob = null
        inferenceJob?.cancel()
        inferenceJob = null
        activeWav?.delete()
        activeWav = null
        runCatching { recorder.cancel() }
        _state.value = VoiceInputState.Idle
    }
}
