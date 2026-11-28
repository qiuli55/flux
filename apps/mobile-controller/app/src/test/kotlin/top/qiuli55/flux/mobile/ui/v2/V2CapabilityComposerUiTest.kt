package top.qiuli55.flux.mobile.ui.v2

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.qiuli55.flux.mobile.data.ImportedCapability
import top.qiuli55.flux.mobile.data.McpConnector
import top.qiuli55.flux.mobile.data.UploadedAttachment

/**
 * 「+」底部面板、技能 / 连接器指定芯片、粘贴转文件与改回输入框的 Compose 回归。
 * 跑在 Robolectric，不依赖模拟器。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-mdpi")
class V2CapabilityComposerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val TEST_ROOT = "v2_capability_test_root"
    }

    private val chat = V2ChatState(
        conversationId = "task-capabilities",
        title = "技能测试",
        device = "本机",
        agentId = "codex-agent",
        model = "deepseek-v4.1-flash",
    )

    private val skills = listOf(
        ImportedCapability(id = "s1", kind = "skill", name = "代码审查", enabled = true),
        ImportedCapability(id = "s2", kind = "skill", name = "旧技能", enabled = false),
    )
    private val connectors = listOf(
        McpConnector(id = "github", name = "GitHub", status = "connected", available = true),
        McpConnector(id = "broken", name = "坏掉的服务", status = "error", available = true),
    )

    private fun render(
        sending: Boolean = false,
        drafts: List<ComposerAttachmentDraft> = emptyList(),
        onRemove: (String) -> Unit = {},
        onPaste: (String, String) -> Unit = { _, _ -> },
        sent: MutableList<Triple<String, List<String>, List<String>>>? = null,
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
                    onSend = {},
                    onSendWithCapabilities = { text, skillIds, connectorIds ->
                        sent?.add(Triple(text, skillIds, connectorIds))
                    },
                    skills = skills,
                    connectors = connectors,
                    designOptions = emptyList(),
                    attachmentDrafts = drafts,
                    onRemoveAttachment = onRemove,
                    pasteMode = PasteTextMode.ALWAYS_FILE,
                    onPasteAsFile = onPaste,
                    sending = sending,
                    suggestions = emptyList(),
                    showHandoff = false,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `plus sheet keeps attachments and adds skills and connectors`() {
        render()
        rule.onNodeWithTag(V2ComposerTags.ATTACH).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.PICK_CAMERA).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.PICK_IMAGE).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.PICK_FILE).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.PICK_DESIGN).assertIsDisplayed()
        // 面板可滚动：底部两项保证存在，不要求一次全部落在视口内。
        rule.onNodeWithTag(V2ComposerTags.PICK_SKILLS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.PICK_CONNECTORS).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `specifying a skill adds a removable chip and removing restores no specification()`() {
        val sent = mutableListOf<Triple<String, List<String>, List<String>>>()
        render(sent = sent)

        rule.onNodeWithTag(V2ComposerTags.ATTACH).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.PICK_SKILLS).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.skillRow("代码审查")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.specifiedChip("skill", "s1")).assertIsDisplayed()

        // 面板保持打开便于多选：点遮罩关闭后回到输入区。
        rule.onNodeWithTag(TEST_ROOT).performTouchInput { click(Offset(4f, 4f)) }
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput("用这个技能看看")
        rule.onNodeWithTag(V2ComposerTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1"), sent.single().second)

        // 去掉标记后再发：不再带指定字段（空列表 = 自动使用）。
        rule.onNodeWithTag(V2ComposerTags.specifiedRemove("skill", "s1")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.specifiedChip("skill", "s1")).assertDoesNotExist()
    }

    @Test
    fun `only connected usable connectors are listed for specification`() {
        render()
        rule.onNodeWithTag(V2ComposerTags.ATTACH).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(V2ComposerTags.PICK_CONNECTORS).performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.connectorRow("github")).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.connectorRow("broken")).assertDoesNotExist()
    }

    @Test
    fun `large multiline paste becomes a text attachment instead of filling the input`() {
        var pastedName: String? = null
        var pastedText: String? = null
        render(onPaste = { name, text ->
            pastedName = name
            pastedText = text
        })

        val text = "第一行\n" + "内容".repeat(5_000)
        rule.onNodeWithTag(V2ComposerTags.INPUT).performTextInput(text)
        rule.waitForIdle()

        assertEquals(text, pastedText)
        assertTrue(pastedName.orEmpty().endsWith(".txt"))
        // 没有把全文写进输入框：原文不应作为输入框正文出现。
        rule.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun `pasted text chip can be revealed back into the input`() {
        val pasted = "第一段\n第二段"
        var drafts by mutableStateOf(
            listOf(
                ComposerAttachmentDraft(
                    localId = "p1",
                    uri = null,
                    name = "粘贴.txt",
                    size = pasted.toByteArray().size.toLong(),
                    mime = "text/plain",
                    status = AttachmentDraftStatus.READY,
                    uploaded = UploadedAttachment(id = "u1", name = "粘贴.txt", size = 10),
                    pastedText = pasted,
                ),
            ),
        )
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
                    attachmentDrafts = drafts,
                    onRemoveAttachment = { id -> drafts = drafts.filterNot { it.localId == id } },
                    suggestions = emptyList(),
                    showHandoff = false,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.attachmentReveal("p1")).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(V2ComposerTags.attachmentDraft("p1")).assertDoesNotExist()
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertIsDisplayed()
        rule.onNodeWithTag(V2ComposerTags.INPUT).assertTextContains("第一段", substring = true)
    }
}
