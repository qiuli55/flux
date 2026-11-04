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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ------------------------------------------------------------------ 解析：正文 → 卡片块

/** 一段助手正文拆出来的块：标题、列表、表格、代码、网址各自成一屏，其余按段落显示。 */
internal sealed interface RichBlock {
    data class Paragraph(val text: String) : RichBlock
    data class Heading(val level: Int, val text: String) : RichBlock
    data class Bullets(val items: List<BulletItem>) : RichBlock
    data class Code(val lang: String, val code: String) : RichBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichBlock
    data class Link(val url: String) : RichBlock
    /** ```ui 围栏里的结构化卡片：标题/清单/进度/动作按钮，可直接点按钮续聊。 */
    data class Generative(val card: RichCard) : RichBlock
}

internal data class BulletItem(val text: String, val marker: String = "•")

/** 生成式卡片的动作按钮：点一下等于把 [prompt] 当作用户消息发出去。 */
internal data class RichCardAction(
    val label: String,
    val prompt: String,
    val primary: Boolean = true,
)

internal data class RichCardItem(
    val label: String,
    val detail: String = "",
    val done: Boolean = false,
)

internal data class RichMetric(
    val label: String,
    val value: String,
    val percent: Int? = null,
)

internal data class RichCard(
    val kind: String = "card",
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
    val progress: Int? = null,
    val items: List<RichCardItem> = emptyList(),
    val metrics: List<RichMetric> = emptyList(),
    val actions: List<RichCardAction> = emptyList(),
)

/**
 * Markdown 块解析器（纯函数，可单测）。
 *
 * 支持：标题、有序/无序列表、代码围栏、管道表格、整行网址，以及 ```ui 生成式卡片。
 * 表格/列表之外的行内语法仍按普通段落处理；未闭合的代码围栏按「正在生成的代码」处理。
 */
internal object RichContentParser {

    private val separatorCell = Regex("^:?-{2,}:?$")
    private val bareUrl = Regex("^<?(https?://[^\\s<>]+)>?$")
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val unorderedBullet = Regex("^[-*•]\\s+(.+)$")
    private val orderedBullet = Regex("^(\\d{1,3})[.)]\\s+(.+)$")

    /** 这些围栏语言按结构化卡片解析，解析失败再退回普通代码卡。 */
    private val generativeLangs = setOf("ui", "a2ui", "ag-ui", "agui", "openui", "ui-card")

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
                val body = code.toString().trimEnd()
                val card = if (lang.lowercase() in generativeLangs) parseGenerative(body) else null
                blocks += if (card != null) RichBlock.Generative(card) else RichBlock.Code(lang, body)
                continue
            }

            val headingMatch = heading.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                blocks += RichBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                )
                index++
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

            val bullet = bulletOf(trimmed)
            if (bullet != null) {
                flushParagraph()
                val items = mutableListOf<BulletItem>()
                while (index < lines.size) {
                    val next = bulletOf(lines[index].trimStart()) ?: break
                    items += next
                    index++
                }
                blocks += RichBlock.Bullets(items)
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

    private fun bulletOf(line: String): BulletItem? {
        unorderedBullet.matchEntire(line)?.let { match ->
            return BulletItem(match.groupValues[1].trim())
        }
        orderedBullet.matchEntire(line)?.let { match ->
            return BulletItem(match.groupValues[2].trim(), marker = "${match.groupValues[1]}.")
        }
        return null
    }

    /**
     * ```ui 卡片：字段全部可选，认不出来的结构直接返回 null 交给代码卡渲染，
     * 这样模型写错 JSON 时用户看到的是一段代码，而不是一张空卡。
     */
    private fun parseGenerative(raw: String): RichCard? {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        fun text(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        val items = obj.array("items").mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = item.string("label").ifBlank { item.string("text") }
            if (label.isBlank()) return@mapNotNull null
            RichCardItem(
                label = label,
                detail = item.string("detail").ifBlank { item.string("value") },
                done = item.bool("done") || item.bool("checked"),
            )
        }
        val metrics = obj.array("metrics").mapNotNull { element ->
            val metric = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = metric.string("label").ifBlank { metric.string("name") }
            if (label.isBlank()) return@mapNotNull null
            RichMetric(
                label = label,
                value = metric.string("value").ifBlank { metric.string("text") },
                percent = metric["percent"]?.jsonPrimitive?.intOrNull,
            )
        }
        val actions = obj.array("actions").mapNotNull { element ->
            val action = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val label = action.string("label")
            val prompt = action.string("prompt").ifBlank {
                action.string("value").ifBlank { action.string("send") }
            }.ifBlank { label }
            if (label.isBlank()) return@mapNotNull null
            RichCardAction(
                label = label,
                prompt = prompt,
                primary = action.string("tone") != "ghost",
            )
        }
        val card = RichCard(
            kind = text("type").ifBlank { "card" },
            title = text("title"),
            subtitle = text("subtitle"),
            body = text("body").ifBlank { text("text") },
            progress = obj["progress"]?.jsonPrimitive?.intOrNull,
            items = items,
            metrics = metrics,
            actions = actions,
        )
        val empty = card.title.isBlank() && card.subtitle.isBlank() && card.body.isBlank() &&
            card.items.isEmpty() && card.metrics.isEmpty() && card.actions.isEmpty() &&
            card.progress == null
        return if (empty) null else card
    }

    private fun JsonObject.array(key: String) =
        runCatching { get(key)?.jsonArray }.getOrNull()?.toList().orEmpty()

    private fun JsonObject.string(key: String): String =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull == "true" }.getOrDefault(false)

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
 * 助手正文的统一渲染入口：标题 + 段落 + 列表 + 代码卡 + 表格卡 + 链接卡 + 生成式卡片。
 *
 * [onOpenUrl] 非空时链接交给 App 内嵌浏览器打开（对话里的网址一律不走外部浏览器）。
 * [onAction] 生成式卡片的动作按钮被点中时，把按钮预置的 prompt 当用户消息发出去。
 */
@Composable
internal fun RichMessageBody(
    text: String,
    streaming: Boolean = false,
    onOpenUrl: (String) -> Unit = {},
    onAction: (String) -> Unit = {},
) {
    val blocks = remember(text) { RichContentParser.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is RichBlock.Paragraph -> {
                    val cursor = streaming && index == blocks.lastIndex
                    val body = if (cursor) block.text + "▍" else block.text
                    Text(
                        text = inlineStyled(body),
                        color = V2Colors.ink(.82f),
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                    )
                }
                is RichBlock.Heading -> HeadingBlock(block.level, block.text)
                is RichBlock.Bullets -> BulletsBlock(block.items)
                is RichBlock.Code -> CodeCard(block.lang, block.code)
                is RichBlock.Table -> TableCard(block.header, block.rows)
                is RichBlock.Link -> LinkCard(block.url, onOpenUrl)
                is RichBlock.Generative -> GenerativeCard(block.card, onAction)
            }
        }
    }
}

/** 标题对齐截图里「总结」「证据」的层级：一级最粗最大，往下逐级收。 */
@Composable
private fun HeadingBlock(level: Int, text: String) {
    val size = when (level) {
        1 -> 21f
        2 -> 18f
        else -> 16.5f
    }
    Text(
        text = inlineStyled(text),
        modifier = Modifier.padding(top = 2.dp),
        color = V2Colors.ink(.95f),
        fontSize = size.sp,
        lineHeight = (size * 1.4f).sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BulletsBlock(items: List<BulletItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            Row {
                Text(
                    text = item.marker,
                    modifier = Modifier.width(20.dp),
                    color = V2Colors.cyan700,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = inlineStyled(item.text),
                    modifier = Modifier.weight(1f),
                    color = V2Colors.ink(.82f),
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                )
            }
        }
    }
}

/**
 * ```ui 生成式卡片：标题 + 进度 + 正文 + 清单 + 指标 + 动作胶囊。
 * 动作按钮不联网、不猜意图，直接把预置 prompt 回发给 Agent，交互链路最短。
 */
@Composable
private fun GenerativeCard(card: RichCard, onAction: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(V2Colors.surface(.92f), V2Colors.surface(.66f)),
                ),
            )
            .innerGlow(shape)
            .border(1.dp, V2Colors.ink(.10f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (card.title.isNotBlank() || card.subtitle.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(listOf(V2Colors.cyan400, V2Colors.cyan700)),
                        ),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    if (card.title.isNotBlank()) {
                        Label(card.title, 16f, V2Colors.ink(.94f), FontWeight.SemiBold, maxLines = 2)
                    }
                    if (card.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Label(card.subtitle, 12f, V2Colors.ink(.50f), maxLines = 2)
                    }
                }
            }
        }

        card.progress?.let { raw ->
            val percent = raw.coerceIn(0, 100)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("进度", 11.5f, V2Colors.ink(.52f))
                    Spacer(Modifier.weight(1f))
                    Label("$percent%", 11.5f, V2Colors.cyan800, FontWeight.SemiBold, mono = true)
                }
                ProgressBar(percent, height = 4.dp)
            }
        }

        if (card.body.isNotBlank()) {
            Text(
                text = inlineStyled(card.body),
                color = V2Colors.ink(.76f),
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )
        }

        if (card.items.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(17.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item.done) V2Colors.cyan500.copy(alpha = .16f) else V2Colors.ink(.06f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.done) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = V2Colors.cyan700,
                                    modifier = Modifier.size(11.dp),
                                )
                            } else {
                                Box(Modifier.size(4.dp).background(V2Colors.ink(.30f), CircleShape))
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = inlineStyled(item.label),
                                color = V2Colors.ink(if (item.done) .50f else .84f),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                            if (item.detail.isNotBlank()) {
                                Label(item.detail, 11.5f, V2Colors.ink(.42f), maxLines = 2)
                            }
                        }
                    }
                }
            }
        }

        if (card.metrics.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.metrics.take(3).forEach { metric ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(V2Colors.ink(.045f))
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    ) {
                        Label(metric.label, 10.5f, V2Colors.ink(.46f), maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Label(metric.value, 16f, V2Colors.ink(.92f), FontWeight.SemiBold, maxLines = 1)
                        metric.percent?.let { percent ->
                            Spacer(Modifier.height(6.dp))
                            ProgressBar(percent.coerceIn(0, 100), height = 3.dp)
                        }
                    }
                }
            }
        }

        if (card.actions.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.actions.forEach { action ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (action.primary) V2Colors.cyan500.copy(alpha = .14f) else Color.Transparent,
                            )
                            .border(
                                1.dp,
                                if (action.primary) V2Colors.cyan500.copy(alpha = .42f) else V2Colors.ink(.14f),
                                RoundedCornerShape(999.dp),
                            )
                            .clickable { onAction(action.prompt) }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Label(
                            action.label,
                            13f,
                            if (action.primary) V2Colors.cyan800 else V2Colors.ink(.62f),
                            if (action.primary) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
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
            .background(Color(0xFF101012))
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                (lang.ifBlank { "code" }).uppercase(),
                9.5f,
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
                    9.5f,
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
            fontSize = 12.sp,
            lineHeight = 18.sp,
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
                        12f,
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
                11.5f,
                V2Colors.cyan800,
                weight = FontWeight.Medium,
                mono = true,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Label(url, 10f, V2Colors.ink(.34f), mono = true, maxLines = 1)
        }
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = "打开链接",
            tint = V2Colors.ink(.34f),
            modifier = Modifier.size(12.dp),
        )
    }
}
