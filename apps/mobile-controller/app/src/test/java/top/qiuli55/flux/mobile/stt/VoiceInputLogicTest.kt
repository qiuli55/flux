package top.qiuli55.flux.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 语音转文字的纯逻辑单测（JVM，不需要模拟器 / 麦克风 / 模型）。 */
class VoiceInputLogicTest {

    @Test
    fun `empty input writes transcript directly`() {
        assertEquals("你好世界", appendTranscript("", "  你好世界  "))
        assertEquals("你好世界", appendTranscript("   ", "你好世界"))
    }

    @Test
    fun `existing text gets a separator before transcript`() {
        assertEquals("先写一半 再补一句", appendTranscript("先写一半", "再补一句"))
        // 已有文本以空白结尾时不重复补空格。
        assertEquals("先写一半 再补一句", appendTranscript("先写一半 ", "再补一句"))
        assertEquals("先写一半\n再补一句", appendTranscript("先写一半\n", "再补一句"))
    }

    @Test
    fun `blank transcript never changes input`() {
        assertEquals("保留原文", appendTranscript("保留原文", "   "))
        assertEquals("", appendTranscript("", ""))
    }

    @Test
    fun `start decision only depends on permission and bundled model availability`() {
        assertEquals(
            VoiceStartDecision.NeedPermission,
            decideVoiceStart(hasPermission = false, modelAvailable = true),
        )
        assertEquals(
            VoiceStartDecision.Record,
            decideVoiceStart(hasPermission = true, modelAvailable = true),
        )
        assertEquals(
            VoiceStartDecision.ModelUnavailable,
            decideVoiceStart(hasPermission = true, modelAvailable = false),
        )
        // 没有网络参数：模型随包提供，离线与联网的启动路径完全一致。
    }

    @Test
    fun `model unavailable message never blames network or mentions engine internals`() {
        val message = voiceFailureMessage(VoiceFailure.ModelUnavailable)
        assertFalse(message.contains("网络"))
        assertFalse(message.contains("下载"))
        assertFalse(message.contains("whisper", ignoreCase = true))
        assertFalse(message.contains("ggml", ignoreCase = true))
        assertFalse(message.contains("base-q5", ignoreCase = true))
        assertTrue(message.contains("重新安装"))
    }

    @Test
    fun `no failure message asks the user to go online or download`() {
        VoiceFailure.entries.forEach { failure ->
            val message = voiceFailureMessage(failure)
            assertFalse("$failure → $message", message.contains("网络"))
            assertFalse("$failure → $message", message.contains("下载"))
        }
    }

    @Test
    fun `transcription language is always zh`() {
        assertEquals("zh", VOICE_TRANSCRIPTION_LANGUAGE)
    }

    @Test
    fun `model spec pins official file url size and sha256`() {
        assertEquals("ggml-base-q5_1.bin", VOICE_MODEL_FILE_NAME)
        assertTrue(VOICE_MODEL_URL.startsWith("https://huggingface.co/ggerganov/whisper.cpp/"))
        assertTrue(VOICE_MODEL_URL.endsWith(VOICE_MODEL_FILE_NAME))
        assertEquals(59_707_625L, VOICE_MODEL_SIZE_BYTES)
        assertEquals(64, VOICE_MODEL_SHA256.length)
        assertTrue(VOICE_MODEL_SHA256.matches(Regex("[0-9a-f]{64}")))
        // 与 Hugging Face LFS oid 一致。
        assertEquals(
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            VOICE_MODEL_SHA256,
        )
        // 构建脚本打包的 assets 路径。
        assertEquals("voice-models/ggml-base-q5_1.bin", VOICE_MODEL_ASSET_PATH)
    }

    @Test
    fun `build script pins the same model spec and bundles it into assets`() {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        )
        val script = candidates.firstOrNull { it.isFile }?.readText()
        assertNotNull("找不到 app/build.gradle.kts", script)
        val text = script!!
        assertTrue(text.contains(VOICE_MODEL_FILE_NAME))
        assertTrue(text.contains(VOICE_MODEL_URL))
        // 构建脚本里数字写作 59_707_625L，去掉下划线后比对。
        assertTrue(text.replace("_", "").contains(VOICE_MODEL_SIZE_BYTES.toString()))
        assertTrue(text.contains(VOICE_MODEL_SHA256))
        // 构建时任务把模型放进 assets，而不是运行时下载。
        assertTrue(text.contains("fetchWhisperModel"))
        assertTrue(text.contains("src/main/assets/voice-models"))
        assertTrue(text.contains("preBuild"))
    }

    @Test
    fun `duration formats as minutes and seconds`() {
        assertEquals("00:00", formatVoiceDuration(0))
        assertEquals("00:12", formatVoiceDuration(12_900))
        assertEquals("01:05", formatVoiceDuration(65_000))
        assertEquals("02:00", formatVoiceDuration(VOICE_MAX_RECORDING_MS))
    }

    @Test
    fun `busy stages replace the input area while failure keeps editing`() {
        assertTrue(voiceReplacesInput(VoiceInputState.Recording(0)))
        assertTrue(voiceReplacesInput(VoiceInputState.Transcribing))
        assertTrue(voiceReplacesInput(VoiceInputState.NeedPermission()))
        assertFalse(voiceReplacesInput(VoiceInputState.Idle))
        assertFalse(voiceReplacesInput(VoiceInputState.Failed(VoiceFailure.NoSpeech, "没有听清")))
        assertFalse(voiceReplacesInput(VoiceInputState.Cancelled))
    }
}
