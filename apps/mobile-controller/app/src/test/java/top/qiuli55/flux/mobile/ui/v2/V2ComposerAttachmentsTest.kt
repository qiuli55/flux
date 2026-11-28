package top.qiuli55.flux.mobile.ui.v2

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.ui.tasks.PickedAttachment

/**
 * 附件草稿状态机：上限 / 大小 / 空文件 / 去重 / 失败重试 / 发送拦截。
 *
 * 直接调 [ComposerAttachmentController.addNow] 等可等待版本，
 * 不上传真实文件、不依赖网络。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class V2ComposerAttachmentsTest {

    private val resolver: ContentResolver = RuntimeEnvironment.getApplication().contentResolver

    private fun uri(index: Int): Uri = Uri.parse("content://test/file-$index")

    private fun controller(
        problems: MutableList<String> = mutableListOf(),
        read: suspend (ContentResolver, Uri) -> PickedAttachment? = { _, _ ->
            PickedAttachment("file.txt", 10L, "text/plain")
        },
        upload: suspend (Uri, PickedAttachment, ContentResolver) -> UploadedAttachment = { uri, picked, _ ->
            UploadedAttachment(id = "up-${uri.lastPathSegment}", name = picked.name, size = picked.size)
        },
    ): ComposerAttachmentController = ComposerAttachmentController(
        scope = CoroutineScope(Job()),
        upload = upload,
        readPicked = read,
        onProblem = { problems += it },
    )

    @Test
    fun `more than five picks keep the first five and explain the rest`() = runBlocking {
        val problems = mutableListOf<String>()
        val controller = controller(problems = problems)
        controller.addNow((1..7).map(::uri), resolver)

        assertEquals(UploadedAttachment.MAX_FILES, controller.drafts.value.size)
        assertTrue(controller.drafts.value.all { it.status == AttachmentDraftStatus.READY })
        assertTrue(problems.joinToString().contains("已忽略多选的 2 个"))
    }

    @Test
    fun `oversize and empty files are skipped while the rest continue`() = runBlocking {
        val problems = mutableListOf<String>()
        val big = uri(1)
        val empty = uri(2)
        val ok = uri(3)
        val controller = controller(
            problems = problems,
            read = { _, target ->
                when (target) {
                    big -> PickedAttachment("大文件.bin", UploadedAttachment.MAX_BYTES + 1, "*/*")
                    empty -> PickedAttachment("空.txt", 0L, "text/plain")
                    else -> PickedAttachment("正常.txt", 12L, "text/plain")
                }
            },
        )
        controller.addNow(listOf(big, empty, ok), resolver)

        assertEquals(listOf("正常.txt"), controller.drafts.value.map { it.name })
        val text = problems.joinToString("；")
        assertTrue(text.contains("「大文件.bin」超过 50MB"))
        assertTrue(text.contains("「空.txt」是空文件"))
    }

    @Test
    fun `same uri is never uploaded twice`() = runBlocking {
        var uploads = 0
        val controller = controller(
            upload = { uri, picked, _ ->
                uploads += 1
                UploadedAttachment(id = "u$uploads", name = picked.name, size = picked.size)
            },
        )
        controller.addNow(listOf(uri(1), uri(1)), resolver)
        controller.addNow(listOf(uri(1)), resolver)

        assertEquals(1, controller.drafts.value.size)
        assertEquals(1, uploads)
    }

    @Test
    fun `failed draft blocks sending and retries through uploading back to ready`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val controller = controller(
            upload = { _, picked, _ ->
                attempts += 1
                if (attempts == 1) throw FluxApiException("network_error", "附件上传失败（网络断了）")
                gate.await()
                UploadedAttachment(id = "ok", name = picked.name, size = picked.size)
            },
        )
        controller.addNow(listOf(uri(1)), resolver)

        val failed = controller.drafts.value.single()
        assertEquals(AttachmentDraftStatus.FAILED, failed.status)
        assertTrue(failed.error.orEmpty().contains("附件上传失败"))
        assertNotNull(attachmentSendBlocker(controller.drafts.value))
        assertFalse(composerCanSend("有文字也不行", controller.drafts.value))

        val retryJob = launch { controller.retryNow(failed.localId) }
        yield()
        // 重试进行中：状态必须立刻变成 uploading，期间仍禁止发送。
        assertEquals(AttachmentDraftStatus.UPLOADING, controller.drafts.value.single().status)
        assertNotNull(attachmentSendBlocker(controller.drafts.value))

        gate.complete(Unit)
        retryJob.join()

        val ready = controller.drafts.value.single()
        assertEquals(AttachmentDraftStatus.READY, ready.status)
        assertNull(attachmentSendBlocker(controller.drafts.value))
        assertEquals(listOf("ok"), readyAttachments(controller.drafts.value).map { it.id })
    }

    @Test
    fun `uploading draft also blocks sending`() {
        val uploading = ComposerAttachmentDraft(
            localId = "a",
            uri = uri(1),
            name = "a.png",
            size = 10,
            mime = "image/png",
            status = AttachmentDraftStatus.UPLOADING,
        )
        assertNotNull(attachmentSendBlocker(listOf(uploading)))
        assertFalse(composerCanSend("文字", listOf(uploading)))
    }

    @Test
    fun `removing one attachment keeps the others`() = runBlocking {
        val controller = controller()
        controller.addNow(listOf(uri(1), uri(2)), resolver)
        val first = controller.drafts.value.first()

        controller.remove(first.localId)

        assertEquals(1, controller.drafts.value.size)
        assertFalse(controller.drafts.value.any { it.localId == first.localId })
    }

    @Test
    fun `only attachments can be sent without text`() = runBlocking {
        val controller = controller()
        controller.addNow(listOf(uri(1)), resolver)

        assertTrue(composerCanSend("", controller.drafts.value))
        assertFalse(composerCanSend("", emptyList()))
    }

    @Test
    fun `image and file labels are derived from mime or extension`() {
        assertTrue(isImageAttachment("photo", "image/jpeg"))
        assertTrue(isImageAttachment("photo.HEIC", null))
        assertEquals("PDF", attachmentKindLabel("合同.pdf", null))
        assertEquals("压缩包", attachmentKindLabel("归档", "application/zip"))
        assertEquals("表格", attachmentKindLabel("报表.xlsx", null))
        assertEquals("文本", attachmentKindLabel("说明.txt", null))
        // 设计稿 SVG 没有位图缩略图，不按图片处理。
        assertFalse(isImageAttachment("design.svg", "image/svg+xml"))
        assertEquals("SVG", attachmentKindLabel("design.svg", "image/svg+xml"))
    }
}
