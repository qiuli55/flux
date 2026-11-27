package top.qiuli55.flux.mobile.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 系统分享进来的一个文件：先保留原始 Uri，进入主界面后再走同一条上传通道。 */
data class SharedAttachmentRef(
    val uri: Uri,
    /** 分享方给的名字（可能为空，上传时会再从 ContentResolver 读一次）。 */
    val name: String = "",
)

/**
 * 一次系统分享解析后的结果。
 *
 * 文字与附件可以同时存在（例如浏览器分享「标题 + 链接 + 图片」）。
 * [errors] 是需要告诉用户的跳过原因（空文件、读不到等），不影响其它项。
 */
data class IncomingShare(
    val text: String = "",
    val attachments: List<SharedAttachmentRef> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** 总线里排队的一条分享：id 用于页面消费后精确移除。 */
data class PendingSharedIntake(val id: Long, val share: IncomingShare)

/**
 * 系统分享入口。
 *
 * 用 StateFlow 而不是 SharedFlow：冷启动时 Compose 还没开始收集，
 * 分享内容也必须等主界面准备好后再落地，不能丢。
 */
object ShareIntakeBus {
    private val _pending = MutableStateFlow<List<PendingSharedIntake>>(emptyList())
    val pending: StateFlow<List<PendingSharedIntake>> = _pending.asStateFlow()
    private val nextId = AtomicLong(0L)

    fun publish(share: IncomingShare) {
        _pending.update { it + PendingSharedIntake(nextId.incrementAndGet(), share) }
    }

    fun consume(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _pending.update { list -> list.filterNot { it.id in ids } }
    }

    /** 只给测试用：避免单例在用例之间串数据。 */
    internal fun resetForTest() {
        _pending.value = emptyList()
    }
}

/**
 * 解析 SEND / SEND_MULTIPLE：把文字、链接、图片、文件拼成一份输入草稿。
 *
 * 纯函数（只读 Intent，不碰磁盘），方便在 Robolectric 里单测。
 * 非分享 intent（MAIN / VIEW / 空 intent）一律返回 null，不影响 OAuth 回跳。
 */
fun parseShareIntent(intent: Intent?): IncomingShare? {
    if (intent == null) return null
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

    val textParts = mutableListOf<String>()
    fun addText(raw: String?) {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return
        // EXTRA_TEXT 与 ClipData、HTML 经常是同一段内容（HTML 只是多了段落/实体）：
        // 已有片段被新内容完整包含时直接替换，避免「正文」出现两次。
        if (textParts.any { it == value || it.contains(value) }) return
        textParts.removeAll { value.contains(it) }
        textParts += value
    }

    addText(intent.getStringExtra(Intent.EXTRA_SUBJECT))
    // 有些 App 的 EXTRA_TEXT 在 text/html 下本身就是一段 HTML，先去标签再参与去重。
    val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
    addText(
        if (intent.type.equals("text/html", ignoreCase = true)) {
            rawText?.let(::htmlToPlainText)
        } else {
            rawText
        },
    )
    addText(intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let(::htmlToPlainText))

    val attachments = mutableListOf<SharedAttachmentRef>()
    val errors = mutableListOf<String>()
    val seenUris = mutableSetOf<String>()

    fun addAttachment(uri: Uri?) {
        if (uri == null || uri.toString().isBlank()) {
            errors += "有一个附件无法读取，已跳过"
            return
        }
        val scheme = uri.scheme.orEmpty().lowercase()
        // text/uri-list 里的 http 链接是正文，不是待上传文件。
        if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
            addText(uri.toString())
            return
        }
        if (!seenUris.add(uri.toString())) return
        attachments += SharedAttachmentRef(uri = uri, name = shareName(uri))
    }

    // ClipData 既可能带文字（浏览器分享标题/链接），也可能带 Uri。
    val clip = intent.clipData
    if (clip != null) {
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index) ?: continue
            addText(item.text?.toString())
            addText(item.htmlText?.let(::htmlToPlainText))
            item.uri?.let(::addAttachment)
        }
    }

    // 单文件 / 多文件走 EXTRA_STREAM；部分 App 只放在 ClipData 里。
    @Suppress("DEPRECATION")
    val single = runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
    if (single != null) addAttachment(single)
    @Suppress("DEPRECATION")
    val multiple = runCatching {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }.getOrNull()
    multiple?.forEach { addAttachment(it) }

    if (textParts.isEmpty() && attachments.isEmpty()) {
        // 只有错误（例如空 Uri）也返回一份空分享，让页面能提示一句。
        return if (errors.isEmpty()) null else IncomingShare(errors = errors)
    }
    return IncomingShare(
        text = textParts.joinToString("\n\n"),
        attachments = attachments,
        errors = errors,
    )
}

/** 分享方没给名字时，从 Uri 尾部兜底；真正的文件名上传时还会再从 ContentResolver 读。 */
private fun shareName(uri: Uri): String =
    runCatching { Uri.decode(uri.lastPathSegment.orEmpty()) }
        .getOrDefault(uri.lastPathSegment.orEmpty())
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
        ?: "附件"

/** 极简 HTML → 文本：保留链接文字与换行，去标签、解码常见实体。 */
internal fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    var text = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|tr|h[1-6])\\s*>"), "\n")
        .replace(Regex("(?i)<li\\s*>"), "· ")
        .replace(Regex("<[^>]+>"), " ")
    text = decodeHtmlEntities(text)
    return text
        .lines()
        .joinToString("\n") { line -> line.replace(Regex("[\\t ]+"), " ").trim() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun decodeHtmlEntities(value: String): String {
    var text = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    text = Regex("&#x([0-9a-fA-F]+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
    }
    text = Regex("&#(\\d+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
    }
    return text
}

/**
 * 分享进来的 content Uri 授权很短：先尽量申请持久授权，再立刻复制到应用 cache。
 *
 * 复制成功后的 Uri 指向应用自己的文件，登录墙停留再久、进程前后台切换都不会失效。
 * 单个文件读不到只跳过并记录原因，不影响同一批里的其它文件。
 */
internal fun materializeSharedAttachments(context: Context, share: IncomingShare): IncomingShare {
    if (share.attachments.isEmpty()) return share
    val resolver = context.contentResolver
    val inbox = File(context.cacheDir, SHARED_INBOX_DIR).apply { mkdirs() }
    cleanupSharedInbox(inbox)
    val errors = share.errors.toMutableList()
    var sequence = 0
    val copied = share.attachments.mapNotNull { ref ->
        val uri = ref.uri
        if (uri.scheme.orEmpty().lowercase() != "content") return@mapNotNull ref
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = ref.name.ifBlank { shareName(uri) }
        val target = File(inbox, "${System.currentTimeMillis()}-${sequence++}-${safeShareName(displayName)}")
        val success = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("openInputStream returned null")
        }.isSuccess
        if (!success) {
            runCatching { target.delete() }
            errors += "「$displayName」无法读取，已跳过"
            return@mapNotNull null
        }
        if (target.length() == 0L) {
            runCatching { target.delete() }
            errors += "「$displayName」是空文件，已跳过"
            return@mapNotNull null
        }
        SharedAttachmentRef(uri = Uri.fromFile(target), name = displayName)
    }
    return share.copy(attachments = copied, errors = errors)
}

private const val SHARED_INBOX_DIR = "shared-inbox"

/** 只保留一层安全文件名，避免分享方带路径分隔符。 */
private fun safeShareName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().trim('.')
    return (cleaned.ifBlank { "shared-file" }).take(80)
}

/** 顺手清掉一天前的分享缓存，避免长期使用后 cache 里堆满临时文件。 */
private fun cleanupSharedInbox(inbox: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    runCatching {
        inbox.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
