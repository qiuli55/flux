package top.qiuli55.flux.mobile.ui.v2

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ 解析：正文 → 卡片块

/** 一段助手正文拆出来的块：表格、代码、网址各自成卡，其余按段落显示。 */
internal sealed interface RichBlock {
    data class Paragraph(val text: String) : RichBlock
    data class Code(val lang: String, val code: String) : RichBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichBlock
    data class Link(val url: String) : RichBlock
}

/**
 * Markdown 块解析器（纯函数，可单测）。
 *
 * 只处理流式回复里真正影响可读性的三种结构：代码围栏、管道表格、整行网址。
 * 表格/列表之外的行内语法仍按普通段落显示；未闭合的代码围栏按「正在生成的代码」处理。
 */
internal object RichContentParser {

    private val separatorCell = Regex("^:?-{2,}:?$")
    private val bareUrl = Regex("^<?(https?://[^\\s<>]+)>?$")

    fun parse(raw: String): List<RichBlock> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<RichBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) blocks += RichBlock.Paragraph(text)
            paragraph.clear()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim().takeWhile { !it.isWhitespace() }
                index++
                val code = StringBuilder()
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                if (index < lines.size) index++ // 闭合围栏（未闭合说明还在流式生成）
                blocks += RichBlock.Code(lang, code.toString().trimEnd())
                continue
            }

            if (index + 1 < lines.size && isTableRow(line) && isTableSeparator(lines[index + 1])) {
                flushParagraph()
                val header = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && isTableRow(lines[index])) {
                    rows += tableCells(lines[index])
                    index++
                }
                if (header.isNotEmpty()) blocks += RichBlock.Table(header, rows)
                continue
            }

            val url = bareUrl.matchEntire(line.trim())?.groupValues?.get(1)
            if (url != null) {
                flushParagraph()
                blocks += RichBlock.Link(url)
                index++
                continue
            }

            paragraph.append(line).append('\n')
            index++
        }
        flushParagraph()
        return blocks
    }

    private fun isTableRow(line: String): Boolean = line.contains('|') && line.trim().isNotEmpty()

    private fun isTableSeparator(line: String): Boolean {
        if (!isTableRow(line)) return false
        val cells = tableCells(line)
        return cells.isNotEmpty() && cells.all { separatorCell.matches(it) }
    }

    private fun tableCells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

// ------------------------------------------------------------------ 渲染

/**
 * 助手正文的统一渲染入口：段落 + 代码卡 + 表格卡 + 链接卡。
 *
 * [onOpenUrl] 非空时链接交给 App 内嵌浏览器打开（对话里的网址一律不走外部浏览器）。
 */
@Composable
internal fun RichMessageBody(
    text: String,
    streaming: Boolean = false,
    onOpenUrl: (String) -> Unit = {},
) {
    val blocks = remember(text) { RichContentParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is RichBlock.Paragraph -> {
                    val cursor = streaming && index == blocks.lastIndex
                    val body = if (cursor) block.text + "▍" else block.text
                    Text(
                        text = inlineStyled(body),
                        color = V2Colors.ink(.70f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                is RichBlock.Code -> CodeCard(block.lang, block.code)
                is RichBlock.Table -> TableCard(block.header, block.rows)
                is RichBlock.Link -> LinkCard(block.url, onOpenUrl)
            }
        }
    }
}

/** 行内语法：`代码` 与 **加粗**；其余原样，避免为了极少数语法把整段渲染搞复杂。 */
private fun inlineStyled(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = V2Colors.ink(.86f))) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end in (index + 2)..(index + 80)) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = V2Colors.cyan800,
                            background = V2Colors.ink(.06f),
                        ),
                    ) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}

@Composable
private fun CodeCard(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF101826))
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                (lang.ifBlank { "code" }).uppercase(),
                8f,
                Color.White.copy(alpha = .55f),
                mono = true,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { clipboard.setText(AnnotatedString(code)); copied = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "复制代码",
                    tint = Color.White.copy(alpha = .55f),
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Label(
                    if (copied) "已复制" else "复制",
                    8f,
                    Color.White.copy(alpha = .55f),
                    mono = true,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
        Text(
            text = code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 11.dp, vertical = 8.dp),
            color = Color.White.copy(alpha = .88f),
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

@Composable
private fun TableCard(header: List<String>, rows: List<List<String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(V2Colors.surface(.56f))
            .border(1.dp, V2Colors.ink(.09f), RoundedCornerShape(11.dp)),
    ) {
        TableRow(header, columns = header.size, header = true)
        rows.forEach { row -> TableRow(row, columns = header.size, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<String>, columns: Int, header: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            repeat(columns.coerceAtLeast(1)) { column ->
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (header) V2Colors.ink(.045f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                ) {
                    Label(
                        cells.getOrElse(column) { "" },
                        10f,
                        if (header) V2Colors.ink(.62f) else V2Colors.ink(.58f),
                        weight = if (header) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(V2Colors.ink(.06f)))
    }
}

@Composable
private fun LinkCard(url: String, onOpenUrl: (String) -> Unit) {
    val host = remember(url) {
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(V2Colors.surface(.60f))
            .border(1.dp, V2Colors.ink(.09f), RoundedCornerShape(11.dp))
            .clickable { onOpenUrl(url) }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = null,
            tint = V2Colors.cyan700,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Label(
                host.ifBlank { "网页链接" },
                10f,
                V2Colors.cyan800,
                weight = FontWeight.Medium,
                mono = true,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Label(url, 8.5f, V2Colors.ink(.34f), mono = true, maxLines = 1)
        }
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = "打开链接",
            tint = V2Colors.ink(.34f),
            modifier = Modifier.size(12.dp),
        )
    }
}
