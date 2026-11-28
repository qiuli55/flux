package top.qiuli55.flux.mobile.ui.v2

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.ui.tasks.PickedAttachment
import top.qiuli55.flux.mobile.ui.tasks.readPickedAttachment
import java.util.UUID

/**
 * 输入栏里的一份附件草稿。
 *
 * 与 [UploadedAttachment] 的区别：草稿还带本地 Uri（图片缩略图要用）和上传状态。
 * 本地 Uri 只存在于页面/ViewModel 内存里，绝不进网络模型。
 */
enum class AttachmentDraftStatus { UPLOADING, READY, FAILED }

data class ComposerAttachmentDraft(
    /** 页面内唯一 id：移除 / 重试都按它定位，避免服务端 id 回来前的空窗。 */
    val localId: String,
    /** 本地预览来源；设计稿导出的 SVG 没有本地文件，为 null。 */
    val uri: Uri?,
    val name: String,
    val size: Long,
    val mime: String?,
    val status: AttachmentDraftStatus,
    /** 上传成功后的服务端结果；发送消息时只用它。 */
    val uploaded: UploadedAttachment? = null,
    /** 上传失败的给用户看的原因。 */
    val error: String? = null,
    /** 粘贴转成的文本附件原文；非空时芯片提供「显示到输入框」。 */
    val pastedText: String? = null,
) {
    val isImage: Boolean get() = isImageAttachment(name, mime)
}

/** 图片判定：MIME 优先，拿不到 MIME 时看扩展名（分享/SAF 常见 MIME 为空）。 */
internal fun isImageAttachment(name: String, mime: String?): Boolean {
    // 设计稿导出的 SVG 没有位图预览，按普通文件展示类型更准确。
    if (mime.equals("image/svg+xml", ignoreCase = true)) return false
    if (mime?.startsWith("image/", ignoreCase = true) == true) return true
    return name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/** 芯片上给非图片附件显示的短类型：PDF / 压缩包 / 文档 / 表格 / 演示 / 文本 / JSON… */
internal fun attachmentKindLabel(name: String, mime: String?): String {
    val type = mime.orEmpty().lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        isImageAttachment(name, mime) -> "图片"
        type == "application/pdf" || ext == "pdf" -> "PDF"
        type.contains("zip") || type.contains("compressed") || ext in ZIP_EXTENSIONS -> "压缩包"
        type.contains("json") || ext == "json" -> "JSON"
        type.contains("word") || type.contains("opendocument.text") || ext in DOC_EXTENSIONS -> "文档"
        type.contains("sheet") || type.contains("excel") || ext in SHEET_EXTENSIONS -> "表格"
        type.contains("presentation") || type.contains("powerpoint") || ext in SLIDE_EXTENSIONS -> "演示"
        type.startsWith("text/") || ext in TEXT_EXTENSIONS -> "文本"
        type.startsWith("audio/") -> "音频"
        type.startsWith("video/") -> "视频"
        else -> ext.uppercase().takeIf { it.isNotBlank() } ?: "文件"
    }
}

private val ZIP_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
private val DOC_EXTENSIONS = setOf("doc", "docx", "odt", "rtf")
private val SHEET_EXTENSIONS = setOf("xls", "xlsx", "ods", "csv")
private val SLIDE_EXTENSIONS = setOf("ppt", "pptx", "odp")
private val TEXT_EXTENSIONS = setOf("txt", "md", "log", "xml", "html", "htm", "yaml", "yml")

/**
 * 有失败 / 上传中的附件时禁止发送：不能把成功的先发出去、留下失败项让用户误以为都发了。
 * 返回 null 表示可以发送。
 */
internal fun attachmentSendBlocker(drafts: List<ComposerAttachmentDraft>): String? = when {
    drafts.any { it.status == AttachmentDraftStatus.FAILED } ->
        "有附件未上传成功，请重试或移除后再发送"
    drafts.any { it.status == AttachmentDraftStatus.UPLOADING } ->
        "有附件还在上传，请稍候再发送"
    else -> null
}

/** 发送时真正带给后端的附件：只取已就绪的。 */
internal fun readyAttachments(drafts: List<ComposerAttachmentDraft>): List<UploadedAttachment> =
    drafts.filter { it.status == AttachmentDraftStatus.READY }.mapNotNull { it.uploaded }

/** 发送按钮是否可点：文字或已就绪附件至少有一个，且没有失败/上传中的草稿。 */
internal fun composerCanSend(text: String, drafts: List<ComposerAttachmentDraft>): Boolean =
    attachmentSendBlocker(drafts) == null && (text.isNotBlank() || drafts.any {
        it.status == AttachmentDraftStatus.READY
    })

/**
 * 附件草稿的上传状态机，新对话与已有对话共用一份实现。
 *
 * 同一个 Uri 不会被重复上传；上限按「草稿列表长度」算（失败项也占名额，用户需先移除或重试）。
 * 上传失败的草稿保留本地 Uri，点重试会重新走同一条上传通道。
 */
internal class ComposerAttachmentController(
    private val scope: CoroutineScope,
    private val upload: suspend (Uri, PickedAttachment, ContentResolver) -> UploadedAttachment,
    /** 读取本地元信息；测试可注入，生产走 ContentResolver。 */
    private val readPicked: suspend (ContentResolver, Uri) -> PickedAttachment? = { resolver, uri ->
        withContext(Dispatchers.IO) { readPickedAttachment(resolver, uri) }
    },
    /** 限流 / 超大 / 读取失败等批量问题：交给页面提示。 */
    private val onProblem: (String) -> Unit = {},
) {
    private val _drafts = MutableStateFlow<List<ComposerAttachmentDraft>>(emptyList())
    val drafts: StateFlow<List<ComposerAttachmentDraft>> = _drafts.asStateFlow()

    // 加号、系统分享可能同时到达：串行上传，避免竞态下超过 5 个名额或重复上传。
    private val mutex = Mutex()
    private val retryActions = mutableMapOf<String, suspend () -> UploadedAttachment>()

    fun add(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        scope.launch { addNow(uris, resolver) }
    }

    /** 可等待版本：测试与内部调用用，保证返回时本批已处理完。 */
    internal suspend fun addNow(uris: List<Uri>, resolver: ContentResolver) = mutex.withLock {
        if (uris.isEmpty()) return@withLock
        val seen = _drafts.value.mapNotNull { it.uri?.toString() }.toMutableSet()
        // 去掉本批重复，也去掉已经在草稿里的同一个 Uri。
        val incoming = uris.filter { seen.add(it.toString()) }
        var ignoredByLimit = 0
        val problems = mutableListOf<String>()

        incoming.forEach { uri ->
            if (_drafts.value.size >= UploadedAttachment.MAX_FILES) {
                ignoredByLimit += 1
                return@forEach
            }
            val picked = runCatching { readPicked(resolver, uri) }.getOrNull()
            if (picked == null) {
                problems += "无法读取所选文件"
                return@forEach
            }
            if (picked.size == 0L) {
                problems += "「${picked.name}」是空文件，已跳过"
                return@forEach
            }
            if (picked.size > UploadedAttachment.MAX_BYTES) {
                problems += "「${picked.name}」超过 50MB，已跳过"
                return@forEach
            }
            val id = UUID.randomUUID().toString()
            val draft = ComposerAttachmentDraft(
                localId = id,
                uri = uri,
                name = picked.name,
                size = picked.size,
                mime = picked.mime,
                status = AttachmentDraftStatus.UPLOADING,
            )
            // 先占位再上传：同时到达的第二批会看到这个名额。
            _drafts.update { it + draft }
            retryActions[id] = { upload(uri, picked, resolver) }
            runUpload(id, retryActions.getValue(id))
        }

        if (ignoredByLimit > 0) {
            problems += "一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件，" +
                "已忽略多选的 $ignoredByLimit 个"
        }
        report(problems)
    }

    /** 设计稿导出 SVG 也走同一个草稿列表；失败后同样可重试。 */
    fun addDesign(name: String, uploader: suspend () -> UploadedAttachment) {
        scope.launch { addDesignNow(name, uploader) }
    }

    /** 大文本粘贴转成的文本附件：与设计稿一样走虚拟上传，不写临时文件。 */
    fun addText(name: String, text: String, uploader: suspend () -> UploadedAttachment) {
        scope.launch { addTextNow(name, text, uploader) }
    }

    internal suspend fun addTextNow(
        name: String,
        text: String,
        uploader: suspend () -> UploadedAttachment,
    ) = mutex.withLock {
        if (_drafts.value.size >= UploadedAttachment.MAX_FILES) {
            onProblem("一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件")
            return@withLock
        }
        val id = UUID.randomUUID().toString()
        val draft = ComposerAttachmentDraft(
            localId = id,
            uri = null,
            name = name,
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            mime = "text/plain",
            status = AttachmentDraftStatus.UPLOADING,
            pastedText = text,
        )
        _drafts.update { it + draft }
        retryActions[id] = uploader
        runUpload(id, uploader)
    }

    internal suspend fun addDesignNow(name: String, uploader: suspend () -> UploadedAttachment) =
        mutex.withLock {
            if (_drafts.value.size >= UploadedAttachment.MAX_FILES) {
                onProblem("一条消息最多带 ${UploadedAttachment.MAX_FILES} 个附件")
                return@withLock
            }
            val id = UUID.randomUUID().toString()
            val draft = ComposerAttachmentDraft(
                localId = id,
                uri = null,
                name = "${name.ifBlank { "design" }}.svg",
                size = 0L,
                mime = "image/svg+xml",
                status = AttachmentDraftStatus.UPLOADING,
            )
            _drafts.update { it + draft }
            retryActions[id] = uploader
            runUpload(id, uploader)
        }

    fun retry(localId: String) {
        retryActions[localId]?.let { action -> scope.launch { retryNow(localId, action) } }
    }

    /** 可等待的重试：测试直接调用，确保返回时状态已经落定。 */
    internal suspend fun retryNow(localId: String) {
        retryActions[localId]?.let { action -> retryNow(localId, action) }
    }

    internal suspend fun retryNow(localId: String, action: suspend () -> UploadedAttachment) =
        mutex.withLock {
            val current = _drafts.value.firstOrNull { it.localId == localId } ?: return@withLock
            if (current.status != AttachmentDraftStatus.FAILED) return@withLock
            _drafts.update { list ->
                list.map {
                    if (it.localId == localId) {
                        it.copy(status = AttachmentDraftStatus.UPLOADING, error = null)
                    } else {
                        it
                    }
                }
            }
            runUpload(localId, action)
        }

    fun remove(localId: String) {
        retryActions.remove(localId)
        _drafts.update { list -> list.filterNot { it.localId == localId } }
    }

    fun clear() {
        retryActions.clear()
        _drafts.value = emptyList()
    }

    private suspend fun runUpload(localId: String, action: suspend () -> UploadedAttachment) {
        // 取消不是「上传失败」：页面退出时不要把草稿改写成可重试的错误态。
        val result = try {
            Result.success(action())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        _drafts.update { list ->
            list.map { draft ->
                if (draft.localId != localId) return@map draft
                result.fold(
                    onSuccess = { uploaded ->
                        draft.copy(
                            status = AttachmentDraftStatus.READY,
                            uploaded = uploaded,
                            // 设计稿 / 大小未知的文件在上传后才拿到真实字节数。
                            size = uploaded.size.takeIf { it > 0 } ?: draft.size,
                            error = null,
                        )
                    },
                    onFailure = { error ->
                        draft.copy(
                            status = AttachmentDraftStatus.FAILED,
                            error = uploadErrorMessage(error),
                        )
                    },
                )
            }
        }
    }

    private fun report(problems: List<String>) {
        if (problems.isEmpty()) return
        onProblem(problems.take(2).joinToString("；"))
    }
}

/** 附件读不到 / 权限被拒时统一说人话，不把系统英文异常直接摆给用户。 */
internal fun uploadErrorMessage(error: Throwable): String = when (error) {
    is SecurityException -> "无法读取所选文件"
    is java.io.FileNotFoundException -> "无法读取所选文件"
    else -> readable(error as? Exception ?: Exception(error.message))
}

/** 把系统分享 / 加号选出的 Uri 上传成服务端附件（两条对话路径共用）。 */
internal suspend fun uploadPickedAttachment(
    apiProvider: suspend () -> FluxApi,
    uri: Uri,
    picked: PickedAttachment,
    resolver: ContentResolver,
): UploadedAttachment = apiProvider().uploadAttachment(
    name = picked.name,
    mime = picked.mime,
    size = picked.size,
) {
    resolver.openInputStream(uri)
        ?: throw FluxApiException("attachment_unreadable", "无法读取「${picked.name}」")
}
