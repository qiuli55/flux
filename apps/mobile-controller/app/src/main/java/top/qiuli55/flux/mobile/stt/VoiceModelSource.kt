package top.qiuli55.flux.mobile.stt

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 语音模型说明（随 APK 提供，不提交进 git，也不在运行时联网下载）。
 *
 * 使用 whisper.cpp 官方仓库预转换 / 量化的 OpenAI Whisper base 模型：
 *  - 文件名：ggml-base-q5_1.bin
 *  - 来源：https://huggingface.co/ggerganov/whisper.cpp
 *  - 大小：59,707,625 字节
 *  - SHA-256：422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
 *    （与 Hugging Face 上该文件的 LFS oid 一致；仓库 README 另列有 git blob SHA-1
 *    a3733eda680ef76256db5fc5dd9de8629e62c5e7，供法务交叉核对）
 *
 * 选择 base-q5_1 的取舍：体积约 57 MiB、手机 CPU 上速度可接受，
 * 中文可用性明显好于 tiny；没有改用 small / medium / large。
 *
 * 构建脚本 `fetchWhisperModel` 会把校验过的权重放到 APK 的
 * `assets/voice-models/ggml-base-q5_1.bin`。运行时第一次需要模型时从这里
 * 展开到应用私有目录（仍是本地拷贝，不访问网络），展开后校验大小与 SHA-256。
 * 这里的 URL / 大小 / SHA-256 必须与 app/build.gradle.kts 保持一致。
 */
const val VOICE_MODEL_FILE_NAME: String = "ggml-base-q5_1.bin"
const val VOICE_MODEL_URL: String =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
const val VOICE_MODEL_SOURCE_REPO: String = "https://huggingface.co/ggerganov/whisper.cpp"
const val VOICE_MODEL_SIZE_BYTES: Long = 59_707_625L
const val VOICE_MODEL_SHA256: String =
    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"

/** APK assets 里的模型路径（相对 assets 根目录）。 */
const val VOICE_MODEL_ASSET_PATH: String = "voice-models/$VOICE_MODEL_FILE_NAME"

/**
 * 随安装包提供的模型：优先复用已展开且校验通过的文件，否则从 APK assets
 * 展开到应用私有目录。整个过程不访问网络；安装后第一次点麦克风即可转写。
 */
internal class BundledVoiceModelSource(
    private val context: Context,
) : VoiceModelSource {

    private val directory: File
        get() = File(context.filesDir, "voice-models").apply { mkdirs() }

    private val targetFile: File
        get() = File(directory, VOICE_MODEL_FILE_NAME)

    /** 已展开且大小正确，或 APK 里确实带有模型，才算可用。 */
    override fun isAvailable(): Boolean =
        hasExpectedSize(targetFile) || assetPresent()

    override suspend fun prepare(): File? = withContext(Dispatchers.IO) {
        val target = targetFile
        if (isVerified(target)) return@withContext target

        // 坏文件 / 旧文件先删掉，重新从 APK 展开（仍然不走网络）。
        target.delete()
        val part = File(directory, "$VOICE_MODEL_FILE_NAME.part")
        part.delete()

        val ok = try {
            context.assets.open(VOICE_MODEL_ASSET_PATH).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            when {
                part.length() != VOICE_MODEL_SIZE_BYTES -> false
                !sha256(part).equals(VOICE_MODEL_SHA256, ignoreCase = true) -> false
                else -> {
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    isVerified(target)
                }
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            false
        }

        if (!ok) {
            part.delete()
            target.delete()
            null
        } else {
            target
        }
    }

    private fun assetPresent(): Boolean = runCatching {
        context.assets.open(VOICE_MODEL_ASSET_PATH).close()
    }.isSuccess

    private fun hasExpectedSize(file: File): Boolean =
        file.isFile && file.length() == VOICE_MODEL_SIZE_BYTES

    private fun isVerified(file: File): Boolean =
        hasExpectedSize(file) && sha256(file).equals(VOICE_MODEL_SHA256, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
