package top.qiuli55.flux.mobile.stt

import java.io.File

/**
 * whisper.cpp 的极薄 JNI 接口。
 *
 * native 库只在真正要转写时加载：JVM 单测（Robolectric）里不会触发 System.loadLibrary，
 * 单测用 [VoiceTranscriber] 假实现，不跑真实推理。
 */
internal object WhisperBridge {
    @Volatile
    private var loaded = false

    fun transcribe(modelPath: String, wavPath: String, language: String, threads: Int): String {
        ensureLoaded()
        return nativeTranscribe(modelPath, wavPath, language, threads)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("fluxwhisper")
                loaded = true
            }
        }
    }

    private external fun nativeTranscribe(
        modelPath: String,
        wavPath: String,
        language: String,
        threads: Int,
    ): String
}

/** 真实转写器：调用本机 whisper.cpp，语言固定中文。 */
internal class WhisperCppTranscriber(
    private val model: File,
) : VoiceTranscriber {

    override suspend fun transcribe(wav: File, language: String): String =
        WhisperBridge.transcribe(
            modelPath = model.absolutePath,
            wavPath = wav.absolutePath,
            language = language,
            threads = VOICE_INFERENCE_THREADS,
        )
}
