package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentParserTest {

    @Test
    fun `fenced code becomes a code block with language`() {
        val blocks = RichContentParser.parse(
            """
            先说明一下：

            ```kotlin
            fun main() {
                println("hi")
            }
            ```

            完。
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
        val code = blocks[1] as RichBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals("fun main() {\n    println(\"hi\")\n}", code.code)
        assertTrue(blocks[2] is RichBlock.Paragraph)
    }

    @Test
    fun `unclosed fence still renders as code while streaming`() {
        val blocks = RichContentParser.parse("```python\nprint('hi')")
        assertEquals(1, blocks.size)
        val code = blocks[0] as RichBlock.Code
        assertEquals("python", code.lang)
        assertEquals("print('hi')", code.code)
    }

    @Test
    fun `pipe table becomes a table block`() {
        val blocks = RichContentParser.parse(
            """
            对比：

            | 名称 | 数值 |
            |:---|---:|
            | a | 1 |
            | b | 2 |
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        val table = blocks[1] as RichBlock.Table
        assertEquals(listOf("名称", "数值"), table.header)
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), table.rows)
    }

    @Test
    fun `line with pipe but no separator stays a paragraph`() {
        val blocks = RichContentParser.parse("这行有 | 竖线但不是表格")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
    }

    @Test
    fun `bare url line becomes a link block`() {
        val blocks = RichContentParser.parse(
            """
            部署地址如下：

            https://159.75.222.60/relay-design/

            其余说明。
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertEquals(
            RichBlock.Link("https://159.75.222.60/relay-design/"),
            blocks[1],
        )
    }

    @Test
    fun `inline code and bold do not split the paragraph`() {
        val blocks = RichContentParser.parse("用 `pytest` 跑一遍，**全部通过**。")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as RichBlock.Paragraph
        assertEquals("用 `pytest` 跑一遍，**全部通过**。", paragraph.text)
    }

    @Test
    fun `mixed answer keeps block order`() {
        val blocks = RichContentParser.parse(
            """
            结果如下：
            ```bash
            ls
            ```
            | 项目 | 状态 |
            | --- | --- |
            | 构建 | 通过 |
            https://example.com/report
            """.trimIndent(),
        )
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
        assertTrue(blocks[1] is RichBlock.Code)
        assertTrue(blocks[2] is RichBlock.Table)
        assertTrue(blocks[3] is RichBlock.Link)
    }

    @Test
    fun `windows line endings are normalized`() {
        val blocks = RichContentParser.parse("第一行\r\n```js\r\nlet a = 1\r\n```")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is RichBlock.Paragraph)
        assertEquals("let a = 1", (blocks[1] as RichBlock.Code).code)
    }
}
