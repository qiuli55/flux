package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.Artifact

/** 文档预览的轻量 Markdown 行内解析 + 产物来源 task_id 解析。 */
class V2MarkdownTest {

    @Test
    fun `inline markdown removes markers and keeps readable text`() {
        val result = inlineMarkdown("**粗体** *斜体* `code` [官网](https://example.com)")
        assertEquals("粗体 斜体 code 官网 (https://example.com)", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `snake_case is not mistaken for italic`() {
        val result = inlineMarkdown("artifact_publish 不会变斜体")
        assertEquals("artifact_publish 不会变斜体", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `artifact metadata exposes source task id`() {
        val json = Json { ignoreUnknownKeys = true }
        val withSource = json.decodeFromString<Artifact>(
            """{"id":"a1","type":"doc","title":"周报","metadata":{"task_id":"t-123"}}""",
        )
        assertEquals("t-123", withSource.sourceTaskId)

        val manual = json.decodeFromString<Artifact>(
            """{"id":"a2","type":"page","title":"页面","metadata":{}}""",
        )
        assertNull(manual.sourceTaskId)
    }
}
