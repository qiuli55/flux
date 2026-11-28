package top.qiuli55.flux.mobile.ui.v2

/**
 * 「粘贴大文本自动作为文件」的纯规则。
 *
 * 手机 / 桌面 / Web 共用同一套判断：一次粘贴达到 [LARGE_PASTE_THRESHOLD] 个字符，
 * 并且包含换行（避免一条超长链接被误判）时，直接生成文本附件，不弹询问。
 * 不到阈值仍直接进输入框。图片粘贴不走这段规则。
 */
enum class PasteTextMode(val storageValue: String, val label: String) {
    /** 默认：达到阈值就作为文件。 */
    ALWAYS_FILE("always_file", "始终作为文件"),

    /** 用户明确选择：再长也直接粘贴进输入框。 */
    ALWAYS_TEXT("always_text", "始终直接粘贴"),
    ;

    companion object {
        val DEFAULT: PasteTextMode = ALWAYS_FILE

        fun fromStorage(value: String?): PasteTextMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** 按字符计，约 1 万字；与桌面 / Web 的常量保持一致。 */
const val LARGE_PASTE_THRESHOLD: Int = 10_000

/** 后端单条任务消息正文上限（不含附件）：改回输入框前要守住。 */
const val COMPOSER_TEXT_MAX: Int = 8_000

/** 文本附件的文件名最多保留多少个字符（不含 .txt）。 */
private const val PASTE_FILE_NAME_CHARS = 24

private val SAFE_FILE_NAME = Regex("""[\\/:*?"<>|\x00-\x1f\x7f]""")

/**
 * 找出 [oldText] → [newText] 之间新插入的片段。
 *
 * 输入框的 onValueChange 只给最终文本；用公共前后缀还原插入内容，
 * 这样长按粘贴、快捷键粘贴、输入法整段上屏都能走同一套判断。
 */
fun pastedSegment(oldText: String, newText: String): String? {
    if (newText.length <= oldText.length) return null
    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix += 1
    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix += 1
    }
    val end = newText.length - suffix
    if (end <= prefix) return null
    return newText.substring(prefix, end)
}

/** 是否属于「大文本粘贴」：达到阈值、含换行，且模式为始终作为文件。 */
fun isLargePaste(text: String, mode: PasteTextMode): Boolean =
    mode == PasteTextMode.ALWAYS_FILE &&
        text.length >= LARGE_PASTE_THRESHOLD &&
        (text.contains('\n') || text.contains('\r'))

/**
 * 从一次输入变化里判断要不要转成文件。
 *
 * @return 需要转成附件的文本；null 表示照常写入输入框。
 */
fun largePasteToAttach(
    oldText: String,
    newText: String,
    mode: PasteTextMode,
    hasImage: Boolean = false,
): String? {
    if (hasImage) return null
    val inserted = pastedSegment(oldText, newText) ?: return null
    return inserted.takeIf { isLargePaste(it, mode) }
}

/** 粘贴图片不走文本规则（图片仍按图片附件处理）。 */
fun isImagePaste(mimeTypes: List<String>?): Boolean =
    mimeTypes.orEmpty().any { it.startsWith("image/", ignoreCase = true) }

/** 用粘贴内容的首行做安全文件名；空白时退回「粘贴.txt」。 */
fun pastedFileName(text: String): String {
    val firstLine = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val cleaned = SAFE_FILE_NAME.replace(firstLine, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(PASTE_FILE_NAME_CHARS)
    return if (cleaned.isEmpty()) "粘贴.txt" else "$cleaned.txt"
}

/** 把文本附件改回输入框的结果：失败时给用户可读的中文原因。 */
data class RevealPasteResult(
    val text: String? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * 「在输入框中显示」：把附件正文并回当前草稿。
 *
 * 超过后端正文上限时明确拒绝，不静默截断，也不静默失败。
 */
fun revealPastedText(currentText: String, pastedText: String): RevealPasteResult {
    val merged = when {
        currentText.isBlank() -> pastedText
        currentText.trimEnd().endsWith(pastedText.trim()) -> currentText
        else -> currentText.trimEnd() + "\n\n" + pastedText
    }
    if (merged.length > COMPOSER_TEXT_MAX) {
        return RevealPasteResult(
            error = "改回输入框会超过 $COMPOSER_TEXT_MAX 字上限，请先精简当前输入",
        )
    }
    return RevealPasteResult(text = merged)
}
