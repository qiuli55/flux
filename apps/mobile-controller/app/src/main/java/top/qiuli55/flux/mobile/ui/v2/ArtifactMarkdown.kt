package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration

/** 表格列对齐；Markdown 的 `:---:` / `---:` / `---` 三种写法。 */
enum class MdAlign { LEFT, CENTER, RIGHT }

/** 任务列表项前面的方框：null 表示普通列表项。 */
data class MdListItem(
    val text: String,
    val depth: Int,
    val checked: Boolean? = null,
    /** 有序列表的真实序号；无序列表为 null。 */
    val marker: String? = null,
)

/** 文档预览的块级结构；解析是纯函数，渲染层只按类型排版。 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<MdListItem>,
    ) : MdBlock
    data class Table(
        val header: List<String>,
        val aligns: List<MdAlign>,
        val rows: List<List<String>>,
    ) : MdBlock
    data class Image(val alt: String, val source: String) : MdBlock
    data object Rule : MdBlock
}

data class MarkdownDoc(
    val blocks: List<MdBlock>,
    /** 非空表示有语法问题（例如代码块没闭合），界面仍会展示已解析部分并提示。 */
    val warning: String? = null,
)

/**
 * 轻量 Markdown 解析：覆盖标题、段落、引用、代码块、有序 / 无序 / 嵌套 / 任务列表、
 * 表格、分隔线与块级图片。
 *
 * 不引入第三方渲染器；解析与渲染分开，[parseMarkdown] 可以直接单测。
 */
object ArtifactMarkdown {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val fence = Regex("^(```+|~~~+)\\s*([^\\s`]*)\\s*$")
    private val rule = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val imageOnly = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val taskItem = Regex("^\\[([ xX])]\\s+(.*)$")
    private val tableDelimiter = Regex("^:?-{1,}:?$")
    private val quoteLine = Regex("^\\s*>\\s?(.*)$")

    fun parseMarkdown(source: String): MarkdownDoc {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return MarkdownDoc(emptyList())

        val lines = text.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var warning: String? = null
        var index = 0

        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trimStart()

            if (raw.isBlank()) {
                index++
                continue
            }

            // 围栏代码块：保留原文缩进，语言标签只取开头第一个词。
            val open = fence.find(trimmed)
            if (open != null) {
                val marker = open.groupValues[1]
                val language = open.groupValues[2]
                val code = mutableListOf<String>()
                index++
                var closed = false
                while (index < lines.size) {
                    val line = lines[index]
                    if (line.trimStart().startsWith(marker)) {
                        closed = true
                        index++
                        break
                    }
                    code.add(line)
                    index++
                }
                if (!closed) warning = "代码块没有闭合，已按当前内容展示"
                blocks += MdBlock.Code(language, code.joinToString("\n"))
                continue
            }

            val headingMatch = heading.find(trimmed)
            if (headingMatch != null) {
                blocks += MdBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                )
                index++
                continue
            }

            if (rule.matches(trimmed)) {
                blocks += MdBlock.Rule
                index++
                continue
            }

            val imageMatch = imageOnly.find(trimmed)
            if (imageMatch != null) {
                blocks += MdBlock.Image(
                    alt = imageMatch.groupValues[1],
                    source = imageMatch.groupValues[2].trim(),
                )
                index++
                continue
            }

            if (isTableStart(lines, index)) {
                val table = readTable(lines, index)
                if (table != null) {
                    blocks += table.block
                    index = table.next
                    continue
                }
            }

            if (quoteLine.matches(raw)) {
                val quoted = mutableListOf<String>()
                while (index < lines.size) {
                    val match = quoteLine.find(lines[index]) ?: break
                    quoted += match.groupValues[1]
                    index++
                }
                blocks += MdBlock.Quote(quoted.joinToString("\n").trim())
                continue
            }

            if (listItem.matches(raw)) {
                val list = readList(lines, index)
                blocks += list.block
                index = list.next
                continue
            }

            // 段落：一直吃到空行或下一个块级语法的开头。
            val paragraph = mutableListOf<String>()
            while (index < lines.size) {
                val line = lines[index]
                if (line.isBlank()) break
                if (paragraph.isNotEmpty() && startsBlock(lines, index)) break
                paragraph += line.trim()
                index++
            }
            if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
        }

        return MarkdownDoc(blocks, warning)
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val raw = lines[index]
        val trimmed = raw.trimStart()
        return fence.containsMatchIn(trimmed) ||
            heading.containsMatchIn(trimmed) ||
            rule.matches(trimmed) ||
            imageOnly.containsMatchIn(trimmed) ||
            quoteLine.matches(raw) ||
            listItem.matches(raw) ||
            isTableStart(lines, index)
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val delimiter = lines[index + 1]
        if (!header.contains('|') || !delimiter.contains('|')) return false
        val cells = splitRow(delimiter)
        return cells.isNotEmpty() && cells.all { tableDelimiter.matches(it.trim()) }
    }

    private data class TableResult(val block: MdBlock.Table, val next: Int)

    private fun readTable(lines: List<String>, index: Int): TableResult? {
        val header = splitRow(lines[index])
        val delimiter = splitRow(lines[index + 1])
        if (header.isEmpty() || header.size != delimiter.size) return null
        val aligns = delimiter.map { cell ->
            val value = cell.trim()
            when {
                value.startsWith(':') && value.endsWith(':') -> MdAlign.CENTER
                value.endsWith(':') -> MdAlign.RIGHT
                else -> MdAlign.LEFT
            }
        }
        val rows = mutableListOf<List<String>>()
        var next = index + 2
        while (next < lines.size) {
            val line = lines[next]
            if (line.isBlank() || !line.contains('|')) break
            val cells = splitRow(line)
            if (cells.isEmpty()) break
            // 行尾多一个空单元格是 Markdown 表格的常见写法，补齐/裁剪到表头列数。
            rows += if (cells.size >= header.size) {
                cells.take(header.size)
            } else {
                cells + List(header.size - cells.size) { "" }
            }
            next++
        }
        return TableResult(MdBlock.Table(header, aligns, rows), next)
    }

    /** 按未转义的 `|` 切分表格行，去掉首尾各一个空单元格。 */
    internal fun splitRow(raw: String): List<String> {
        val placeholder = "\u0000"
        val protected = raw.replace("\\|", placeholder)
        val parts = protected.split('|').map { it.replace(placeholder, "|").trim() }
        val cells = parts.toMutableList()
        if (cells.isNotEmpty() && cells.first().isEmpty()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
        return cells
    }

    private data class ListResult(val block: MdBlock.ListBlock, val next: Int)

    private fun readList(lines: List<String>, index: Int): ListResult {
        val items = mutableListOf<MdListItem>()
        val first = listItem.find(lines[index]) ?: return ListResult(
            MdBlock.ListBlock(false, 1, emptyList()),
            index + 1,
        )
        val ordered = first.groupValues[2].first().isDigit()
        val start = if (ordered) {
            first.groupValues[2].dropLast(1).toIntOrNull()?.coerceAtLeast(1) ?: 1
        } else {
            1
        }
        var next = index
        while (next < lines.size) {
            val match = listItem.find(lines[next]) ?: break
            val indent = match.groupValues[1].replace("\t", "  ").length
            val marker = match.groupValues[2]
            var content = match.groupValues[3].trimEnd()
            var checked: Boolean? = null
            taskItem.find(content)?.let { task ->
                checked = task.groupValues[1].equals("x", ignoreCase = true)
                content = task.groupValues[2]
            }
            items += MdListItem(
                text = content,
                depth = (indent / 2).coerceIn(0, 6),
                checked = checked,
                marker = if (marker.first().isDigit()) marker.dropLast(1) else null,
            )
            next++
        }
        return ListResult(MdBlock.ListBlock(ordered, start, items), next)
    }
}

// ------------------------------------------------------------------ 行内语法

private val inlinePattern = Regex(
    """\*\*(.+?)\*\*|(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)|(?<![\w])_(.+?)_(?![\w])|`(.+?)`|!\[([^\]]*)]\(([^)]+)\)|\[([^\]]+)]\(([^)]+)\)""",
)

/**
 * 行内 Markdown：`**粗体**`、`*斜体*`、`_斜体_`、`` `代码` ``、`![alt](src)`、`[文字](链接)`。
 *
 * 预览里图片按块级渲染；夹在段落中的图片退化成 alt 文字。
 */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in inlinePattern.findAll(text)) {
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val values = match.groupValues
        when {
            values[1].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(values[1]) }
            values[2].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(values[2]) }
            values[3].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(values[3]) }
            values[4].isNotEmpty() ->
                withStyle(
                    SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                ) { append(values[4]) }
            values[5].isNotEmpty() || values[6].isNotEmpty() -> {
                val alt = values[5].ifBlank { "图片" }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(alt) }
            }
            values[7].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium,
                    ),
                ) { append(values[7]) }
                withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF6B7280))) {
                    append(" (${values[8]})")
                }
            }
            else -> append(match.value)
        }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
