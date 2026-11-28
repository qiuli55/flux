package top.qiuli55.flux.mobile.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 语音状态机单测：权限、随包模型可用性、模型展开失败、录音取消、转写取消 /
 * 失败 / 超时、成功。全部用假录音器与假转写器，不在 JVM 里跑 whisper。
 */
class VoiceInputControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeRecorder(private val wav: File?) : VoiceRecorder {
        var startCalls = 0
        var cancelCalls = 0
        var started = false

        override fun start(): Boolean {
            startCalls++
            started = wav != null
            return wav != null
        }

        override fun stop(): File? {
            started = false
            return wav
        }

        override fun cancel() {
            cancelCalls++
            started = false
        }
    }

    private class FakeModelSource(
        var available: Boolean,
        private val model: File,
        var prepareBlock: suspend () -> File? = { model },
    ) : VoiceModelSource {
        var prepareCalls = 0

        override fun isAvailable(): Boolean = available

        override suspend fun prepare(): File? {
            prepareCalls++
            return prepareBlock()
        }
    }

    private class FakeTranscriber(
        private val block: suspend (File, String) -> String,
    ) : VoiceTranscriber {
        val languages = mutableListOf<String>()
        var calls = 0

        override suspend fun transcribe(wav: File, language: String): String {
            calls++
            languages += language
            return block(wav, language)
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun newController(
        scope: CoroutineScope,
        recorder: VoiceRecorder,
        source: VoiceModelSource,
        transcriber: VoiceTranscriber,
        permission: Boolean = true,
        transcriptionTimeoutMs: Long = 10_000L,
    ) = VoiceInputController(
        scope = scope,
        recorder = recorder,
        modelSource = source,
        transcriberFactory = { transcriber },
        permissionGranted = { permission },
        clock = { 0L },
        ioDispatcher = Dispatchers.Unconfined,
        inferenceDispatcher = Dispatchers.Unconfined,
        transcriptionTimeoutMs = transcriptionTimeoutMs,
        maxRecordingMs = VOICE_MAX_RECORDING_MS,
    )

    private fun wavFile(): File = temp.newFile("voice.wav").apply {
        writeBytes(ByteArray(128))
    }

    @Test
    fun `missing microphone permission never starts recording`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> "不该出现" }
            val controller = newController(
                scope, recorder, source, transcriber, permission = false,
            )

            controller.onMicClick()

            assertEquals(VoiceInputState.NeedPermission(denied = false), controller.state.value)
            assertEquals(0, recorder.startCalls)
            assertEquals(0, transcriber.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `permission denial is reflected in state without recording`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val controller = newController(
                scope, recorder, source, FakeTranscriber { _, _ -> "x" }, permission = false,
            )

            controller.onPermissionResult(granted = false)

            assertEquals(VoiceInputState.NeedPermission(denied = true), controller.state.value)
            assertEquals(0, recorder.startCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bundled model starts recording immediately without any download step`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val controller = newController(
                scope, recorder, source, FakeTranscriber { _, _ -> "你好" },
            )

            controller.onMicClick()

            // 模型随包提供：第一次点麦克风就直接进入录音，不需要等待展开或联网。
            assertTrue(controller.state.value is VoiceInputState.Recording)
            assertEquals(1, recorder.startCalls)
            // 展开推迟到转写前，不阻塞录音入口。
            assertEquals(0, source.prepareCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing model resource fails clearly without recording or blaming network`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = false, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> "不该出现" }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()

            val failed = controller.state.value as VoiceInputState.Failed
            assertEquals(VoiceFailure.ModelUnavailable, failed.failure)
            assertFalse(failed.message.contains("网络"))
            assertFalse(failed.message.contains("下载"))
            assertFalse(failed.message.contains("whisper", ignoreCase = true))
            assertFalse(failed.message.contains("ggml", ignoreCase = true))
            assertFalse(failed.message.contains("base-q5", ignoreCase = true))
            assertEquals(0, recorder.startCalls)
            assertEquals(0, transcriber.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `model that cannot be unpacked from apk fails without transcribing`() {
        val scope = newScope()
        try {
            val wav = wavFile()
            val recorder = FakeRecorder(wav)
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin")).apply {
                prepareBlock = { null }
            }
            val transcriber = FakeTranscriber { _, _ -> "不该出现" }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onMicClick()

            val failed = controller.state.value as VoiceInputState.Failed
            assertEquals(VoiceFailure.ModelUnavailable, failed.failure)
            assertEquals(1, source.prepareCalls)
            assertEquals(0, transcriber.calls)
            // 失败后临时录音必须清理，不留下半段。
            assertFalse(wav.exists())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling recording discards audio and never transcribes`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> "不该出现" }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            assertTrue(controller.state.value is VoiceInputState.Recording)

            controller.cancel()

            assertEquals(VoiceInputState.Cancelled, controller.state.value)
            assertEquals(1, recorder.cancelCalls)
            assertEquals(0, transcriber.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `going to background cancels an active recording`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> "不该出现" }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onBackground()

            assertEquals(VoiceInputState.Cancelled, controller.state.value)
            assertEquals(1, recorder.cancelCalls)
            assertEquals(0, transcriber.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `successful transcription exposes text only as Success and uses zh`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> " 你好，帮我看看这个  " }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onMicClick()

            assertEquals(VoiceInputState.Success("你好，帮我看看这个"), controller.state.value)
            assertEquals(listOf("zh"), transcriber.languages)
            assertEquals(1, source.prepareCalls)

            controller.consumeSuccess()
            assertEquals(VoiceInputState.Idle, controller.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling transcription never exposes partial text`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> awaitCancellation() }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onMicClick()
            assertEquals(VoiceInputState.Transcribing, controller.state.value)

            controller.cancel()

            assertEquals(VoiceInputState.Cancelled, controller.state.value)
            assertFalse(controller.state.value is VoiceInputState.Success)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transcription failure reports failure and never Success`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> throw IllegalStateException("jni") }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onMicClick()

            val failed = controller.state.value as VoiceInputState.Failed
            assertEquals(VoiceFailure.TranscriptionFailed, failed.failure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `blank transcription is treated as no speech`() {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ -> "   " }
            val controller = newController(scope, recorder, source, transcriber)

            controller.onMicClick()
            controller.onMicClick()

            val failed = controller.state.value as VoiceInputState.Failed
            assertEquals(VoiceFailure.NoSpeech, failed.failure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transcription timeout reports timeout instead of partial text`() = runBlocking {
        val scope = newScope()
        try {
            val recorder = FakeRecorder(wavFile())
            val source = FakeModelSource(available = true, model = temp.newFile("model.bin"))
            val transcriber = FakeTranscriber { _, _ ->
                delay(10_000L)
                "迟到的结果"
            }
            val controller = newController(
                scope, recorder, source, transcriber, transcriptionTimeoutMs = 50L,
            )

            controller.onMicClick()
            controller.onMicClick()

            val state = withTimeout(2_000L) {
                controller.state.first { it is VoiceInputState.Failed }
            } as VoiceInputState.Failed
            assertEquals(VoiceFailure.Timeout, state.failure)
            assertFalse(controller.state.value is VoiceInputState.Success)
        } finally {
            scope.cancel()
        }
    }
}
