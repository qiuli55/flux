package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.ArtifactAsset
import top.qiuli55.flux.mobile.data.ArtifactVersion

/** 版本历史 / 钉选 / 资产字段与后端 snake_case 的映射。 */
class ArtifactVersionsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `artifact version maps snake case fields`() {
        val version = json.decodeFromString<ArtifactVersion>(
            """{"artifact_id":"a1","version":3,"title":"周报","created_at":"2026-11-28T10:00:00Z","content":"# 内容"}""",
        )
        assertEquals("a1", version.artifactId)
        assertEquals(3, version.version)
        assertEquals("2026-11-28T10:00:00Z", version.createdAt)
        assertEquals("# 内容", version.content)
    }

    @Test
    fun `artifact pinned flag maps and defaults false`() {
        val pinned = json.decodeFromString<Artifact>("""{"id":"a1","type":"doc","title":"x","pinned":true}""")
        val plain = json.decodeFromString<Artifact>("""{"id":"a2","type":"doc","title":"y"}""")
        assertTrue(pinned.pinned)
        assertFalse(plain.pinned)
    }

    @Test
    fun `asset path key uses last file name segment`() {
        val asset = ArtifactAsset(id = "x", fileName = "images/logo.PNG")
        assertEquals("logo.png", asset.pathKey)
    }
}
