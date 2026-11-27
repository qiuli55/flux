package top.qiuli55.flux.mobile.ui.v2

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.UploadedAttachment

/**
 * 输入栏附件芯片的 Compose 回归：
 * 图片缩略图、失败重试、移除、失败/上传中禁止发送、只有附件也能发、分享注入。
 * 跑在 Robolectric（:app:testDebugUnitTest），不依赖模拟器。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ComposerAttachmentUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val chat = V2ChatState(
        conversationId = "task-attachments",
        title = "附件测试",
        device = "本机",
        agentId = "codex-agent",
        model = "deepseek-v4.1-flash",
    )

    private fun uploaded(name: String) = UploadedAttachment(
        id = "up-$name",
        name = name,
        size = 1024,
        mime = "text/plain",
        path = "/tmp/$name",
    )

    private fun readyDoc(id: String = "doc", name: String = "报告.txt") = ComposerAttachmentDraft(
        localId = id,
        uri = Uri.parse("content://test/$id"),
        name = name,
        size = 2048,
        mime = "text/plain",
        status = AttachmentDraftStatus.READY,
        uploaded = uploaded(name),
    )

    private fun readyImage(id: String = "img") = ComposerAttachmentDraft(
        localId = id,
        uri = Uri.parse("content://test/$id"),
        name = "照片.png",
        size = 4096,
        mime = "image/png",
        status = AttachmentDraftStatus.READY,
        uploaded = uploaded("照片.png"),
    )

    private fun failed(id: String = "fail") = ComposerAttachmentDraft(
        localId = id,
        uri = Uri.parse("content://test/$id"),
        name = "失败.pdf",
        size = 4096,
        mime = "application/pdf",
        status = AttachmentDraftStatus.FAILED,
        error = "附件上传失败（网络断了）",
    )

    private fun render(
        drafts: List<ComposerAttachmentDraft>,
        seed: ComposerSeed? = null,
        onAdd: ((List<Uri>) -> Unit)? = null,
        onRemove: (String) -> Unit = {},
        onRetry: (String) -> Unit = {},
        onSeedApplied: (Long) -> Unit = {},
        sent: MutableList<String>? = null,
    ) {
        rule.setContent {
            Box(Modifier.width(400.dp).testTag(TEST_ROOT)) {
                V2ChatScreen(
                    chat = chat,
                    messages = emptyList(),
                    extraTop = 0.dp,
                    onBack = {},
                    onDelete = {},
                    onOpenAgentPicker = {},
                    onSend = { sent?.add(it) },
                    attachmentDrafts = drafts,
                    onAddAttachments = { onAdd?.invoke(it) },
                    onRemoveAttachment = onRemove,
                    onRetryAttachment = onRetry,
                    seed = seed,
                    onSeedApplied = onSeedApplied,
                    suggestions = emptyList(),
                    showHandoff = false,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `image gets a thumbnail and document shows kind and size`() {
        render(drafts = listOf(readyImage(), readyDoc()))

        rule.onNodeWithTag(V2ComposerTags.attachmentThumb("img"), useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithText("照片.png").assertIsDisplayed()
        rule.onNodeWithText("报告.txt").assertIsDisplayed()
        rule.onNodeWithText("文本 · 2.0 KB", substring = true).assertIsDisplayed()
    }

    @Test
    fun `failed draft shows retry and blocks sending`() {
        var retried: String? = null
        render(
            drafts = listOf(failed()),
            onRetry = { retried = it },
            sent = mutableListOf(),
        )

        rule.onNodeWithTag(V2ComposerTags.attachmentRetry("fail")).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.SEND).assertIsNotEnabled()

        rule.onNodeWithTag(V2ComposerTags.attachmentRetry("fail")).performClick()
        rule.waitForIdle()
        assertEquals("fail", retried)
        // 失败原因以芯片副行展示，用户知道为什么发不出去。
        rule.onNodeWithText("附件上传失败", substring = true).assertIsDisplayed()
    }

    @Test
    fun `uploading draft also disables send`() {
        val uploading = readyDoc(id = "loading").copy(
            name = "上传中.txt",
            status = AttachmentDraftStatus.UPLOADING,
            uploaded = null,
        )
        render(drafts = listOf(uploading))
        rule.onNodeWithTag(V2ComposerTags.SEND).assertIsNotEnabled()
        rule.onNodeWithText("上传中…").assertIsDisplayed()
    }

    @Test
    fun `ready attachment alone enables send even with empty input`() {
        val sent = mutableListOf<String>()
        render(drafts = listOf(readyDoc()), sent = sent)

        rule.onNodeWithTag(V2ComposerTags.SEND).assertIsEnabled()
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf(""), sent)
    }

    @Test
    fun `removing one attachment only reports that local id`() {
        var removed: String? = null
        render(
            drafts = listOf(readyImage("one"), readyDoc("two")),
            onRemove = { removed = it },
        )

        rule.onAllNodesWithContentDescription("移除附件").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("移除附件").onFirst().performClick()
        rule.waitForIdle()
        assertEquals("one", removed)
        // 另一个附件仍然在，不会被整体清空。
        rule.onNodeWithText("报告.txt").assertIsDisplayed()
    }

    @Test
    fun `share seed appends text and attachments once`() {
        var seed by mutableStateOf<ComposerSeed?>(
            ComposerSeed(
                token = 7L,
                text = "https://example.com/shared",
                attachmentUris = listOf(Uri.parse("content://share/1")),
            ),
        )
        val added = mutableListOf<List<Uri>>()
        var applied: Long? = null

        rule.setContent {
            Box(Modifier.width(400.dp).testTag(TEST_ROOT)) {
                V2ChatScreen(
                    chat = chat,
                    messages = emptyList(),
                    extraTop = 0.dp,
                    onBack = {},
                    onDelete = {},
                    onOpenAgentPicker = {},
                    onSend = {},
                    attachmentDrafts = emptyList(),
                    onAddAttachments = { added += it },
                    seed = seed,
                    onSeedApplied = { token ->
                        applied = token
                        seed = null
                    },
                    suggestions = emptyList(),
                    showHandoff = false,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("https://example.com/shared")
        assertEquals(listOf(listOf(Uri.parse("content://share/1"))), added)
        assertEquals(7L, applied)
        // 清空 seed 后重组不会把文字再追加一遍。
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("https://example.com/shared")
    }

    @Test
    fun `appendComposerText keeps existing draft`() {
        assertEquals("原来打的字", appendComposerText("原来打的字", ""))
        assertEquals("分享内容", appendComposerText("", "分享内容"))
        assertTrue(appendComposerText("原来打的字", "分享内容").contains("原来打的字"))
        assertTrue(appendComposerText("原来打的字", "分享内容").contains("分享内容"))
        assertEquals("同一段", appendComposerText("同一段", "同一段"))
    }

    private companion object {
        const val TEST_ROOT = "v2_attachment_test_root"
    }
}
