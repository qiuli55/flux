package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「创作」三类交付物的文案锁：对话卡片、创作列表、详情页必须同一套说法，
 * 且不能再出现 Artifacts / 作品 / 产物 作为业务名。
 */
class ArtifactKindUiTest {

    @Test
    fun `page says interactive page not artifact`() {
        val kind = artifactKindUi("page")
        assertEquals("可交互页面", kind.label)
        assertEquals("可交互", kind.badge)
        assertEquals("可交互", artifactCardBadge("page"))
        assertFalse(kind.description.contains("产物"))
    }

    @Test
    fun `design says design mockup and opens canvas`() {
        val kind = artifactKindUi("design")
        assertEquals("设计稿", kind.label)
        assertEquals("进画布", artifactCardBadge("design"))
        assertEquals("设计稿", designCardSubtitle("项目 A", "首页").substringBefore(" · "))
    }

    @Test
    fun `doc says document and is readable`() {
        val kind = artifactKindUi("doc")
        assertEquals("文档", kind.label)
        assertEquals("可阅读", artifactCardBadge("doc"))
    }

    @Test
    fun `unknown type falls back to creation not page`() {
        val kind = artifactKindUi("video")
        assertEquals("创作", kind.label)
        assertEquals("创作", kind.badge)
    }

    @Test
    fun `organize prompt keeps the user's own words`() {
        assertEquals("整理成文档", organizeAttachmentPrompt("doc", ""))
        assertEquals("整理成页面", organizeAttachmentPrompt("page", "  "))
        assertEquals(
            "这是会议照片\n\n整理成文档",
            organizeAttachmentPrompt("doc", " 这是会议照片 "),
        )
        assertEquals(
            "做成能点的\n\n整理成页面",
            organizeAttachmentPrompt("page", "做成能点的"),
        )
    }

    @Test
    fun `bottom tab label is creation`() {
        assertEquals("对话", V2TopTab.CHATS.label)
        assertEquals("创作", V2TopTab.ARTIFACTS.label)
        assertEquals("我的", V2TopTab.PROFILE.label)
    }

    @Test
    fun `no user visible artifact business name`() {
        val labels = listOf("page", "design", "doc", "video").flatMap { type ->
            val kind = artifactKindUi(type)
            listOf(kind.label, kind.badge, kind.description, kind.openHint)
        } + listOf(V2TopTab.ARTIFACTS.label)
        labels.forEach { text ->
            assertFalse(text, text.contains("Artifacts"))
            assertFalse(text, text.contains("作品"))
            assertFalse(text, text.contains("产物"))
        }
    }
}
