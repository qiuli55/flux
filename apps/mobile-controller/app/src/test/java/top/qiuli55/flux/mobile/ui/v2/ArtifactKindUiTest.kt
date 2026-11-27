package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「创作」里页面 / 设计稿 / 文档的标识必须一眼分得清：
 * 页面说明能交互，设计稿说明进画布，文档说明可阅读。
 */
class ArtifactKindUiTest {

    @Test
    fun `page is labelled as interactive not just a page`() {
        val kind = artifactKindUi("page")
        assertEquals("可交互页面", kind.label)
        assertEquals("可交互", kind.badge)
        assertTrue(kind.description.contains("点按"))
        assertEquals("点开可交互预览", kind.openHint)
    }

    @Test
    fun `design is labelled as a canvas design`() {
        val kind = artifactKindUi("design")
        assertEquals("设计稿", kind.label)
        assertEquals("设计稿", kind.badge)
        assertTrue(kind.description.contains("画布"))
        assertEquals("点开进画布", kind.openHint)
    }

    @Test
    fun `doc is labelled as markdown and readable`() {
        val kind = artifactKindUi("doc")
        assertEquals("Markdown 文档", kind.label)
        assertEquals("文档", kind.badge)
        assertTrue(kind.description.contains("Markdown"))
        assertEquals("点开阅读", kind.openHint)
    }

    @Test
    fun `the three kinds never share the same label or open hint`() {
        val kinds = listOf("page", "design", "doc").map(::artifactKindUi)
        assertEquals(3, kinds.map { it.label }.toSet().size)
        assertEquals(3, kinds.map { it.openHint }.toSet().size)
        assertNotEquals(artifactKindUi("page").badge, artifactKindUi("design").badge)
    }

    @Test
    fun `chat card badge says what the user can do`() {
        assertEquals("可交互", artifactCardBadge("page"))
        assertEquals("进画布", artifactCardBadge("design"))
        assertEquals("可阅读", artifactCardBadge("doc"))
        assertEquals("查看", artifactCardBadge("future_type"))
    }

    @Test
    fun `design card subtitle always announces it is a design`() {
        assertEquals(
            "设计稿 · Flux 移动端 · 浅色版",
            designCardSubtitle("Flux 移动端", "浅色版"),
        )
        assertEquals("设计稿", designCardSubtitle("", ""))
        assertEquals("设计稿 · 任务中心", designCardSubtitle("任务中心", ""))
    }

    @Test
    fun `unknown backend type falls back without pretending to be a page`() {
        val kind = artifactKindUi("future_type")
        assertEquals("创作", kind.label)
        assertEquals("创作", kind.badge)
        assertNotEquals("可交互页面", kind.label)
        assertEquals("点开查看", kind.openHint)
    }
}
