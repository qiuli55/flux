// 对话里设计稿卡片与内置浏览器地址的核心解析用例。
//
// 两处都来自真实链路：design.share 写入的 payload（后端 design_tools.py）与
// 用户/Agent 给的网址。解析错了会得到空卡片或打不开页面，所以单独锁住。
package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2DesignCardAndUrlTest {

    @Test
    fun `design card payload keeps summary and preview nodes`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "design_id": "draft-1dc6ba50",
              "name": "任务中心 · 移动端",
              "project": "Flux 移动端",
              "description": "390×844 任务中心",
              "version": 4,
              "screen_count": 1,
              "layer_count": 58,
              "bg": "#F5F7FA",
              "updated_at": "2026-10-25T11:19:49+00:00",
              "preview": {
                "w": 390,
                "h": 844,
                "bg": "#F5F7FA",
                "nodes": [
                  {"id": "title", "type": "text", "text": "任务中心", "x": 22, "y": 54, "w": 200, "h": 36}
                ]
              }
            }
            """.trimIndent(),
        )

        val card = parseDesignCard(payload)

        assertTrue(card != null)
        assertEquals("draft-1dc6ba50", card!!.id)
        assertEquals("任务中心 · 移动端", card.name)
        assertEquals(4, card.version)
        assertEquals(1, card.screenCount)
        assertEquals(58, card.layerCount)
        assertEquals(390f, card.preview!!.w)
        assertEquals("任务中心", card.preview!!.nodes.first().text)
    }

    @Test
    fun `design card without id is ignored instead of crashing`() {
        val payload = Json.parseToJsonElement("""{"name":"没有 id 的旧消息"}""")
        assertNull(parseDesignCard(payload))
    }

    @Test
    fun `artifact card keeps type title summary and version`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "artifact_id": "8f1c2d3e-0000-4000-8000-000000000001",
              "type": "doc",
              "title": "发布说明",
              "summary": "v2.0 更新内容",
              "version": 2,
              "metadata": {}
            }
            """.trimIndent(),
        )

        val card = parseArtifactCard(payload)

        assertTrue(card != null)
        assertEquals("8f1c2d3e-0000-4000-8000-000000000001", card!!.id)
        assertEquals("doc", card.type)
        assertEquals("发布说明", card.title)
        assertEquals("v2.0 更新内容", card.summary)
        assertEquals(2, card.version)
    }

    @Test
    fun `artifact card without id is ignored instead of crashing`() {
        val payload = Json.parseToJsonElement("""{"type":"page","title":"没有 id"}""")
        assertNull(parseArtifactCard(payload))
    }

    @Test
    fun `browser url gets https when scheme is missing`() {
        assertEquals("https://flux.app.qiuli55.top/x", normalizeUrl("flux.app.qiuli55.top/x"))
    }

    @Test
    fun `browser url keeps explicit http for internal hosts`() {
        assertEquals("http://159.75.222.60/relay-design/", normalizeUrl("http://159.75.222.60/relay-design/"))
    }

    @Test
    fun `blank address falls back to about blank`() {
        assertEquals("about:blank", normalizeUrl("   "))
    }

    @Test
    fun `bare url line drops trailing punctuation before becoming a card`() {
        val blocks = RichContentParser.parse("https://flux.app.qiuli55.top/report。")

        assertEquals(1, blocks.size)
        assertEquals("https://flux.app.qiuli55.top/report", (blocks[0] as RichBlock.Link).url)
    }

    @Test
    fun `url wrapped in common brackets is unwrapped`() {
        assertEquals(
            "https://flux.app.qiuli55.top/a",
            normalizeBareUrl("（https://flux.app.qiuli55.top/a）"),
        )
        assertEquals(
            "https://flux.app.qiuli55.top/a",
            normalizeBareUrl("<https://flux.app.qiuli55.top/a>"),
        )
    }

    @Test
    fun `balanced parentheses inside url are preserved`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            normalizeBareUrl("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `link detail shows path but hides duplicated root url`() {
        assertEquals("/docs/start?from=chat", linkDetail("https://flux.app.qiuli55.top/docs/start?from=chat"))
        assertEquals("", linkDetail("https://flux.app.qiuli55.top/"))
        assertEquals("", linkDetail("https://flux.app.qiuli55.top"))
    }
}
