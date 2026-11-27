package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 解析：表格、任务列表、嵌套列表、代码块语言与图片。 */
class ArtifactMarkdownTest {

    @Test
    fun `table parses header alignment and rows`() {
        val doc = ArtifactMarkdown.parseMarkdown(
            """
            | 项目 | 状态 |
            | :--- | ---: |
            | 登录 | 完成 |
            | 导出 | 进行中 |
            """.trimIndent(),
        )
        val table = doc.blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("项目", "状态"), table.header)
        assertEquals(listOf(MdAlign.LEFT, MdAlign.RIGHT), table.aligns)
        assertEquals(listOf(listOf("登录", "完成"), listOf("导出", "进行中")), table.rows)
    }

    @Test
    fun `task list keeps checked state and nesting`() {
        val doc = ArtifactMarkdown.parseMarkdown(
            """
            - [x] 已完成
              - [ ] 子任务
            - [ ] 待办
            """.trimIndent(),
        )
        val list = doc.blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(false, list.ordered)
        assertEquals(3, list.items.size)
        assertEquals(true, list.items[0].checked)
        assertEquals(0, list.items[0].depth)
        assertEquals(false, list.items[1].checked)
        assertEquals(1, list.items[1].depth)
        assertEquals("待办", list.items[2].text)
    }

    @Test
    fun `ordered list keeps real markers and start`() {
        val doc = ArtifactMarkdown.parseMarkdown(
            """
            3. 第三
            4. 第四
            """.trimIndent(),
        )
        val list = doc.blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(listOf("3", "4"), list.items.map { it.marker })
    }

    @Test
    fun `fenced code keeps language and reports unclosed block`() {
        val doc = ArtifactMarkdown.parseMarkdown(
            """
            ```kotlin
            val a = 1
            """.trimIndent(),
        )
        val code = doc.blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1", code.code)
        assertNotNull(doc.warning)
    }

    @Test
    fun `standalone image becomes image block`() {
        val doc = ArtifactMarkdown.parseMarkdown("![架构图](assets/arch.png)")
        val image = doc.blocks.filterIsInstance<MdBlock.Image>().single()
        assertEquals("架构图", image.alt)
        assertEquals("assets/arch.png", image.source)
    }

    @Test
    fun `headings paragraphs and rules are separated`() {
        val doc = ArtifactMarkdown.parseMarkdown(
            """
            # 标题

            一段正文。
            仍是同一段。

            ---
            """.trimIndent(),
        )
        assertEquals(3, doc.blocks.size)
        assertEquals(MdBlock.Heading(1, "标题"), doc.blocks[0])
        assertEquals(MdBlock.Paragraph("一段正文。 仍是同一段。"), doc.blocks[1])
        assertEquals(MdBlock.Rule, doc.blocks[2])
    }

    @Test
    fun `blank document yields no blocks`() {
        assertTrue(ArtifactMarkdown.parseMarkdown("  \n\n").blocks.isEmpty())
    }

    @Test
    fun `split row ignores escaped pipes`() {
        assertEquals(listOf("a|b", "c"), ArtifactMarkdown.splitRow("| a\\|b | c |"))
    }

    @Test
    fun `code language label and light highlight keep text intact`() {
        assertEquals("KOTLIN", ArtifactCode.label("kotlin"))
        assertEquals("PY", ArtifactCode.label("py"))
        assertEquals("", ArtifactCode.label(""))
        val source = "val answer = 42 // 注释"
        val styled = ArtifactCode.highlight(source, "kotlin")
        assertEquals(source, styled.text)
        assertTrue(styled.spanStyles.isNotEmpty())
    }

    @Test
    fun `plain code still renders without spans`() {
        val styled = ArtifactCode.highlight("hello world", "")
        assertEquals("hello world", styled.text)
    }
}
