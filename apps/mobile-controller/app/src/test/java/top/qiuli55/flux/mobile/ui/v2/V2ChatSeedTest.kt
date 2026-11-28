package top.qiuli55.flux.mobile.ui.v2

import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import top.qiuli55.flux.mobile.data.UploadedAttachment

/**
 * 系统分享注入输入栏：Plan 模式与「+」按钮规则一致，只进文字、不收附件。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2ChatSeedTest {

    @get:Rule
    val rule = createComposeRule()

    private val added = mutableListOf<List<Uri>>()
    private val applied = mutableListOf<Long>()
    private val sent = mutableListOf<String>()

    private fun render(
        permissionMode: String,
        seed: ComposerSeed?,
        drafts: List<ComposerAttachmentDraft> = emptyList(),
    ) {
        added.clear()
        applied.clear()
        sent.clear()
        rule.setContent {
            V2ChatScreen(
                chat = V2ChatState(
                    conversationId = null,
                    title = "新的对话",
                    device = "本机",
                    agentId = "arch",
                    model = "deepseek-v4.1-flash",
                    permissionMode = permissionMode,
                ),
                messages = emptyList(),
                extraTop = 0.dp,
                onBack = {},
                onDelete = {},
                onOpenAgentPicker = {},
                onSend = { sent += it },
                attachmentDrafts = drafts,
                onAddAttachments = { added += it },
                seed = seed,
                onSeedApplied = { applied += it },
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `plan mode keeps share text but rejects attachments with a notice`() {
        val uri = Uri.parse("content://share/photo-1")
        render(
            permissionMode = "plan",
            seed = ComposerSeed(
                token = 7L,
                text = "分享进来的文字",
                attachmentUris = listOf(uri),
            ),
        )

        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("分享进来的文字")
        assertTrue("Plan 模式不应把附件交给草稿", added.isEmpty())
        assertEquals(listOf(7L), applied)
        assertEquals(
            "Plan 模式不支持附件，已只把文字放进输入框",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `normal mode seed still adds attachments to the draft`() {
        val uri = Uri.parse("content://share/photo-2")
        render(
            permissionMode = "normal",
            seed = ComposerSeed(
                token = 8L,
                text = "带附件",
                attachmentUris = listOf(uri),
            ),
        )

        assertEquals(listOf(listOf(uri)), added)
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("带附件")
        assertEquals(listOf(8L), applied)
    }

    @Test
    fun `plan mode does not send a leftover attachment draft`() {
        val ready = ComposerAttachmentDraft(
            localId = "draft-1",
            uri = Uri.parse("content://share/photo-3"),
            name = "photo.png",
            size = 12,
            mime = "image/png",
            status = AttachmentDraftStatus.READY,
            uploaded = UploadedAttachment(id = "up-1", name = "photo.png", size = 12),
        )
        render(permissionMode = "plan", seed = null, drafts = listOf(ready))

        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput("还是想发")
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()

        assertTrue("Plan 模式不能把附件发出去", sent.isEmpty())
        assertEquals(
            "Plan 模式不支持附件，请先移除附件或切换到普通模式",
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
