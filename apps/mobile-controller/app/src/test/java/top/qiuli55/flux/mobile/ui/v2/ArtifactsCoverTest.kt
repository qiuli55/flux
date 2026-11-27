package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignPreview

/** Artifacts 卡片封面：有预览走画布 / 有摘要走文本 / 都没有走占位图标。 */
class ArtifactsCoverTest {

    private fun design(preview: DesignPreview?): Artifact = Artifact(
        id = "design:d1",
        type = "design",
        title = "登录页",
        preview = preview,
    )

    private val node = DesignNode(id = "n1", type = "rect", w = 390f, h = 844f)

    @Test
    fun `design with preview nodes uses the canvas`() {
        val artifact = design(DesignPreview(nodes = listOf(node)))
        assertEquals(ArtifactCoverMode.DESIGN_CANVAS, artifactCoverMode(artifact))
    }

    @Test
    fun `design without preview falls back to placeholder`() {
        assertEquals(ArtifactCoverMode.PLACEHOLDER, artifactCoverMode(design(null)))
    }

    @Test
    fun `design with empty preview nodes falls back to placeholder`() {
        val artifact = design(DesignPreview(nodes = emptyList()))
        assertEquals(ArtifactCoverMode.PLACEHOLDER, artifactCoverMode(artifact))
    }

    @Test
    fun `doc with summary uses text preview and strips markdown`() {
        val artifact = Artifact(
            id = "d1",
            type = "doc",
            title = "周报",
            summary = "## 本周进展 **完成** [详情](https://example.com)",
        )
        assertEquals(ArtifactCoverMode.TEXT_PREVIEW, artifactCoverMode(artifact))
        assertEquals(listOf("本周进展 完成 详情"), artifactCoverText(artifact))
    }

    @Test
    fun `doc without summary falls back to placeholder`() {
        val artifact = Artifact(id = "d2", type = "doc", title = "空文档", summary = "")
        assertEquals(ArtifactCoverMode.PLACEHOLDER, artifactCoverMode(artifact))
        assertTrue(artifactCoverText(artifact).isEmpty())
    }

    @Test
    fun `page summary is stripped of html tags for text preview`() {
        val artifact = Artifact(
            id = "p1",
            type = "page",
            title = "落地页",
            summary = "<h1>Flux Bot</h1><p>Hello <b>world</b>&nbsp;!</p>",
        )
        assertEquals(ArtifactCoverMode.TEXT_PREVIEW, artifactCoverMode(artifact))
        assertEquals(listOf("Flux Bot", "Hello world !"), artifactCoverText(artifact))
    }

    @Test
    fun `page with markup only falls back to placeholder`() {
        val artifact = Artifact(
            id = "p2",
            type = "page",
            title = "空页面",
            summary = "<div> </div><p></p>",
        )
        assertEquals(ArtifactCoverMode.PLACEHOLDER, artifactCoverMode(artifact))
        assertTrue(artifactCoverText(artifact).isEmpty())
    }

    @Test
    fun `cover text keeps at most four lines`() {
        val summary = (1..8).joinToString("\n") { "line $it" }
        assertEquals(4, artifactCoverText("doc", summary).size)
    }

    @Test
    fun `placeholder paths never yield letter glyph text`() {
        // 占位图标是纯 Canvas 轮廓，不再用「Aa」「#」「</>」这类字符；这里锁定空摘要的返回值。
        val modes = listOf(
            design(null),
            Artifact(id = "d3", type = "doc", title = "空", summary = ""),
            Artifact(id = "p3", type = "page", title = "空", summary = "<p></p>"),
        )
        modes.forEach { artifact ->
            assertEquals(ArtifactCoverMode.PLACEHOLDER, artifactCoverMode(artifact))
            assertTrue(artifactCoverText(artifact).isEmpty())
        }
    }
}
