package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.ArtifactAsset

/** 产物页面的 CSP / 相对资产 / 视口策略。 */
class ArtifactWebPolicyTest {

    private val assets = listOf(
        ArtifactAsset(id = "a".repeat(32), fileName = "logo.png", contentType = "image/png"),
        ArtifactAsset(id = "b".repeat(32), fileName = "字体.woff2", contentType = "font/woff2"),
    )

    @Test
    fun `google fonts and cdn whitelist are allowed`() {
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://fonts.googleapis.com/css2?family=Inter"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://fonts.gstatic.com/s/inter/x.woff2"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://cdnjs.cloudflare.com/ajax/libs/x.js"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://unpkg.com/react@18/umd/react.js"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://cdn.tailwindcss.com/3.4"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://code.jquery.com/jquery-3.7.min.js"))
        assertTrue(ArtifactWebPolicy.isAllowedExternal("https://cdn.jsdelivr.net/npm/chart.js"))
    }

    @Test
    fun `jsdelivr non npm paths and other hosts are blocked`() {
        assertFalse(ArtifactWebPolicy.isAllowedExternal("https://cdn.jsdelivr.net/gh/user/repo/x.js"))
        assertFalse(ArtifactWebPolicy.isAllowedExternal("https://example.com/app.js"))
        assertFalse(ArtifactWebPolicy.isAllowedExternal("http://cdnjs.cloudflare.com/x.js"))
        assertTrue(ArtifactWebPolicy.shouldBlock("https://example.com/a.png"))
    }

    @Test
    fun `local origin and inline resources are not blocked`() {
        assertTrue(ArtifactWebPolicy.isLocal("https://flux-artifact.local/index.html"))
        assertTrue(ArtifactWebPolicy.isLocal("data:image/png;base64,AAAA"))
        assertFalse(ArtifactWebPolicy.shouldBlock("https://flux-artifact.local/app.js"))
        assertFalse(ArtifactWebPolicy.shouldBlock("about:blank"))
    }

    @Test
    fun `relative path resolves by file name case insensitively`() {
        assertEquals("a".repeat(32), ArtifactWebPolicy.assetForPath("./Logo.PNG?v=2", assets)?.id)
        assertEquals("b".repeat(32), ArtifactWebPolicy.assetForPath("assets/字体.woff2", assets)?.id)
        assertEquals("a".repeat(32), ArtifactWebPolicy.assetForPath("a".repeat(32), assets)?.id)
        assertNull(ArtifactWebPolicy.assetForPath("missing.png", assets))
        assertNull(ArtifactWebPolicy.assetForPath("https://example.com/logo.png", assets))
    }

    @Test
    fun `relative asset detection rejects absolute and anchors`() {
        assertTrue(ArtifactWebPolicy.isRelativeAsset("assets/a.png"))
        assertFalse(ArtifactWebPolicy.isRelativeAsset("https://x/a.png"))
        assertFalse(ArtifactWebPolicy.isRelativeAsset("//cdn/a.png"))
        assertFalse(ArtifactWebPolicy.isRelativeAsset("#top"))
        assertFalse(ArtifactWebPolicy.isRelativeAsset(""))
    }

    @Test
    fun `viewport html replaces or injects meta`() {
        val replaced = ArtifactWebPolicy.viewportHtml(
            "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head></html>",
            desktop = true,
        )
        assertTrue(replaced.contains("width=1280"))
        assertFalse(replaced.contains("width=device-width"))
        assertTrue(ArtifactWebPolicy.viewportHtml("<html><head></head></html>", false)
            .contains("width=device-width"))
    }
}
