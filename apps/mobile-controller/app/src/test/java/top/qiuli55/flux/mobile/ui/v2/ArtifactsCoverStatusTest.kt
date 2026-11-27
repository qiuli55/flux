package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Test
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignPreview

/** 列表封面的四种状态 + 钉选排序。 */
class ArtifactsCoverStatusTest {

    private val design = Artifact(
        id = "design:d1",
        type = "design",
        title = "登录页",
        preview = DesignPreview(nodes = listOf(DesignNode(id = "n1", type = "rect"))),
    )
    private val doc = Artifact(id = "a1", type = "doc", title = "周报", summary = "# 本周")
    private val emptyDoc = Artifact(id = "a2", type = "doc", title = "空文档", summary = "")

    @Test
    fun `loaded cover states are distinguishable`() {
        assertEquals(
            ArtifactCoverStatus.DESIGN_CANVAS,
            artifactCoverStatus(design, loading = false, failed = false),
        )
        assertEquals(
            ArtifactCoverStatus.TEXT_PREVIEW,
            artifactCoverStatus(doc, loading = false, failed = false),
        )
    }

    @Test
    fun `missing loading and failed are distinct`() {
        assertEquals(
            ArtifactCoverStatus.MISSING,
            artifactCoverStatus(emptyDoc, loading = false, failed = false),
        )
        assertEquals(
            ArtifactCoverStatus.LOADING,
            artifactCoverStatus(emptyDoc, loading = true, failed = false),
        )
        assertEquals(
            ArtifactCoverStatus.FAILED,
            artifactCoverStatus(emptyDoc, loading = false, failed = true),
        )
    }

    @Test
    fun `pinned artifacts sort first but keep two column grid`() {
        val state = ArtifactsState(
            artifacts = listOf(
                Artifact(id = "1", type = "page", title = "普通", pinned = false),
                Artifact(id = "2", type = "doc", title = "钉选", pinned = true),
                Artifact(id = "3", type = "page", title = "普通二", pinned = false),
            ),
        )
        assertEquals(listOf("2", "1", "3"), state.visible.map { it.id })
        assertEquals(3, state.countOf(""))
        assertEquals(2, state.countOf("page"))
    }
}
