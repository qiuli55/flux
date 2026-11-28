package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.stt.VoiceInputController
import top.qiuli55.flux.mobile.stt.VoiceModelSource
import top.qiuli55.flux.mobile.stt.VoiceRecorder
import top.qiuli55.flux.mobile.stt.VoiceTranscriber
import java.io.File

/**
 * 输入栏语音入口的 Compose / Robolectric 回归测试。
 *
 * 覆盖：未授权时的说明、失败时保留已录入文字、成功后只追加到输入框且不自动发送。
 * 录音器与转写器都是假实现，不依赖真机麦克风或 whisper JNI。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2VoiceComposerTest {

    @get:Rule
    val rule = createComposeRule()

    private var scope: CoroutineScope? = null
    private val sent = mutableListOf<String>()

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private class FakeRecorder(private val wav: File) : VoiceRecorder {
        var startCalls = 0
        override fun start(): Boolean {
            startCalls++
            return true
        }

        override fun stop(): File = wav
        override fun cancel() = Unit
    }

    private class FakeSource(private val model: File) : VoiceModelSource {
        override fun isAvailable(): Boolean = true
        override suspend fun prepare(): File = model
    }

    private class FakeTranscriber(
        private val block: suspend () -> String,
    ) : VoiceTranscriber {
        val languages = mutableListOf<String>()
        override suspend fun transcribe(wav: File, language: String): String {
            languages += language
            return block()
        }
    }

    private fun controller(
        permission: Boolean = true,
        transcriber: FakeTranscriber = FakeTranscriber { "你好" },
    ): VoiceInputController {
        val wav = File.createTempFile("voice", ".wav").apply {
            writeBytes(ByteArray(128))
            deleteOnExit()
        }
        val model = File.createTempFile("model", ".bin").apply {
            writeBytes(ByteArray(16))
            deleteOnExit()
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope = newScope
        return VoiceInputController(
            scope = newScope,
            recorder = FakeRecorder(wav),
            modelSource = FakeSource(model),
            transcriberFactory = { transcriber },
            permissionGranted = { permission },
            clock = { 0L },
            ioDispatcher = Dispatchers.Unconfined,
            inferenceDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun render(controller: VoiceInputController) {
        sent.clear()
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = "task-voice",
                    title = "新的对话",
                    device = "本机",
                    agentId = "codex-agent",
                    model = "deepseek-v4.1-flash",
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = { sent += it },
                suggestions = emptyList(),
                showHandoff = false,
                voiceController = controller,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `without microphone permission shows explanation and never starts recording`() {
        val voice = controller(permission = false)
        render(voice)

        rule.onNodeWithTag(V2ComposerTags.MIC).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("需要麦克风才能转成文字").assertIsDisplayed()
        rule.onNodeWithText("允许使用麦克风").assertIsDisplayed()
        rule.onNodeWithText("打开系统设置").assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `successful transcription appends to draft and never auto sends`() {
        val fake = FakeTranscriber { "再补一句" }
        val voice = controller(transcriber = fake)
        render(voice)

        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput("先写一半")
        rule.onNodeWithTag(V2ComposerTags.MIC).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("正在录音 00:00").assertIsDisplayed()

        rule.onNodeWithTag(V2ComposerTags.MIC).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("先写一半 再补一句")
        assertEquals(listOf("zh"), fake.languages)
        // 转写成功只写输入框，不自动发送。
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `transcription failure keeps the existing draft and does not send`() {
        val voice = controller(transcriber = FakeTranscriber { throw IllegalStateException("boom") })
        render(voice)

        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput("保留原文")
        rule.onNodeWithTag(V2ComposerTags.MIC).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.MIC).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("转写失败，请再录一次").assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("保留原文")
        assertTrue(sent.isEmpty())
    }
}
