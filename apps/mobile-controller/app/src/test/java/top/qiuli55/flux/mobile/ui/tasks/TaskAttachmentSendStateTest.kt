package top.qiuli55.flux.mobile.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Task
import top.qiuli55.flux.mobile.data.UploadedAttachment
import top.qiuli55.flux.mobile.ui.v2.AttachmentDraftStatus
import top.qiuli55.flux.mobile.ui.v2.ComposerAttachmentDraft

/**
 * 发送成功 / 失败时附件草稿的去留：
 * - 成功才清空；
 * - 失败保留已就绪附件与输入文字，用户可以直接重试。
 */
class TaskAttachmentSendStateTest {

    private fun readyDraft(): ComposerAttachmentDraft = ComposerAttachmentDraft(
        localId = "a",
        uri = null,
        name = "报告.txt",
        size = 10,
        mime = "text/plain",
        status = AttachmentDraftStatus.READY,
        uploaded = UploadedAttachment(id = "up-a", name = "报告.txt", size = 10),
    )

    @Test
    fun `failed send keeps text and ready attachments`() {
        val before = TaskDetailUiState(
            draft = "这份报告有问题吗",
            attachmentDrafts = listOf(readyDraft()),
        )

        val failed = before
            .withSendStarted()
            .withSendFailed("这份报告有问题吗", "网络错误")

        assertEquals("这份报告有问题吗", failed.draft)
        assertEquals("网络错误", failed.error)
        assertEquals(1, failed.attachmentDrafts.size)
    }

    @Test
    fun `starting a send does not clear attachments`() {
        val started = TaskDetailUiState(attachmentDrafts = listOf(readyDraft())).withSendStarted()
        assertTrue(started.sending)
        assertEquals("", started.draft)
        assertEquals(1, started.attachmentDrafts.size)
    }

    @Test
    fun `successful send clears attachments`() {
        val before = TaskDetailUiState(
            draft = "发出去了",
            attachmentDrafts = listOf(readyDraft()),
        )

        val succeeded = before.withSendSucceeded(Task(), emptyList())

        assertNull(succeeded.error)
        assertTrue(succeeded.attachmentDrafts.isEmpty())
    }
}
