package top.qiuli55.flux.mobile.ui.v2

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import top.qiuli55.flux.mobile.data.Artifact
import top.qiuli55.flux.mobile.data.ArtifactAsset
import top.qiuli55.flux.mobile.data.ArtifactVersion

private const val FILTER_ALL = ""
private const val FILTER_DESIGN = "design"
private const val FILTER_PAGE = "page"
private const val FILTER_DOC = "doc"

private fun artifactTypeLabel(type: String): String = artifactKindUi(type).label

private fun artifactTypeIcon(type: String): ImageVector = when (type) {
    "design" -> Icons.Outlined.Layers
    "doc" -> Icons.Outlined.Description
    else -> Icons.Outlined.Language
}

/** ISO 时间串只展示到分钟；不认识的格式原样返回。 */
private fun prettyTime(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.replace('T', ' ').take(16)
}

/**
 * Artifacts 一级页面：设计稿、页面、文档放在同一个卡片网格里。
 *
 * 设计卡点开进原生画布；页面 / 文档点开详情预览与编辑。「设计工作室」作为设计类
 * 的二级管理页（新建 / 导入 / 重命名 / 删除 / 导出），从筛选行右侧进入。
 */
@Composable
fun ArtifactsScreen(
    state: ArtifactsState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onFilter: (String) -> Unit,
    onOpenArtifact: (String) -> Unit,
    onOpenDesign: (String) -> Unit,
    onCreate: (String, String) -> Unit,
    onPin: (Artifact) -> Unit,
    onOpenDesignStudio: () -> Unit,
    onDismissMessages: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    val visible = state.visible

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2PageTitle(
                title = "创作",
                modifier = Modifier.padding(top = extraTop),
                subtitle = when {
                    state.loading && state.artifacts.isEmpty() -> "正在读取创作…"
                    state.artifacts.isEmpty() -> "对话里产出的页面、设计稿与文档会出现在这里"
                    else -> "${state.artifacts.size} 个创作 · 可交互页面 / 设计稿 / 文档"
                },
                trailing = {
                    if (showBack) {
                        RoundIconButton(
                            Icons.Outlined.ArrowBack,
                            onBack,
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "返回",
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    RoundIconButton(
                        Icons.Outlined.DesignServices,
                        onClick = onOpenDesignStudio,
                        size = 34.dp,
                        iconSize = 17.dp,
                        contentDescription = "设计工作室",
                    )
                },
            )

            ArtifactFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ArtifactFilterChip(
                        "全部",
                        state.countOf(FILTER_ALL),
                        state.filter == FILTER_ALL,
                    ) { onFilter(FILTER_ALL) }
                }
                item {
                    ArtifactFilterChip(
                        "设计稿",
                        state.countOf(FILTER_DESIGN),
                        state.filter == FILTER_DESIGN,
                    ) { onFilter(FILTER_DESIGN) }
                }
                item {
                    ArtifactFilterChip(
                        "页面",
                        state.countOf(FILTER_PAGE),
                        state.filter == FILTER_PAGE,
                    ) { onFilter(FILTER_PAGE) }
                }
                item {
                    ArtifactFilterChip(
                        "文档",
                        state.countOf(FILTER_DOC),
                        state.filter == FILTER_DOC,
                    ) { onFilter(FILTER_DOC) }
                }
            }

            when {
                state.loading && state.artifacts.isEmpty() ->
                    ArtifactEmpty("正在读取创作…") {}
                state.error != null && state.artifacts.isEmpty() -> ArtifactEmpty(
                    "列表加载失败\n${state.error}",
                    onReload,
                )
                visible.isEmpty() -> ArtifactEmpty(
                    when (state.filter) {
                        FILTER_DESIGN -> "还没有设计稿\n视觉稿会出现在这里，点开进画布"
                        FILTER_PAGE -> "还没有可交互页面\n点右下角 + 新建，或让 Agent 发布"
                        FILTER_DOC -> "还没有文档\n点右下角 + 新建 Markdown 文档"
                        else -> "还没有创作\n可交互页面、设计稿与文档都会出现在这里"
                    },
                    onReload,
                )
                else -> V2PullRefresh(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRefresh = {
                        onReload()
                        kotlinx.coroutines.delay(500)
                    },
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.filter == FILTER_DESIGN) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DesignStudioEntry(onClick = onOpenDesignStudio)
                            }
                        }
                        items(visible, key = { it.id }) { artifact ->
                            ArtifactGridCard(
                                artifact = artifact,
                                loading = state.loading,
                                failed = state.error != null,
                                pinning = state.pinningId == artifact.id,
                                onClick = {
                                    val designId = artifact.designId
                                    if (designId != null) onOpenDesign(designId)
                                    else onOpenArtifact(artifact.id)
                                },
                                onRetry = onReload,
                                onPin = { onPin(artifact) },
                            )
                        }
                    }
                }
            }
        }

        V2Fab(
            onClick = { newOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
            contentDescription = "新建创作",
        )

        if (newOpen) {
            ArtifactCreateSheet(
                creating = state.creating,
                onDismiss = { newOpen = false },
                onCreate = { type, title ->
                    onCreate(type, title)
                    newOpen = false
                },
            )
        }
    }
}

@Composable
private fun ArtifactFilterChip(text: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) V2Colors.accent else V2Colors.surface)
            .border(1.dp, if (active) Color.Transparent else V2Colors.lineSoft, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            text,
            11.5f,
            if (active) V2Colors.actionInk else V2Colors.ink(.66f),
            if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
        Spacer(Modifier.width(5.dp))
        Label(
            "$count",
            10f,
            if (active) V2Colors.actionInk.copy(alpha = .62f) else V2Colors.ink(.34f),
        )
    }
}

@Composable
private fun ArtifactGridCard(
    artifact: Artifact,
    loading: Boolean,
    failed: Boolean,
    pinning: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onPin: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box {
        WhiteCard(
            Modifier
                .clip(shape)
                .clickable(onClick = onClick),
        ) {
            ArtifactCover(artifact, loading, failed, onRetry)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Label(
                    artifact.title.ifBlank { "未命名创作" },
                    12.5f,
                    V2Colors.ink(.92f),
                    FontWeight.SemiBold,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Label(artifactMeta(artifact), 9f, V2Colors.ink(.38f), maxLines = 1)
                if (artifact.summary.isBlank() && artifact.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Label(artifact.description, 8.5f, V2Colors.ink(.34f), maxLines = 1)
                } else if (artifact.summary.isBlank() && artifact.description.isBlank()) {
                    // 没有摘要时补一句类型说明，避免用户分不清「能不能点」。
                    Spacer(Modifier.height(3.dp))
                    Label(
                        artifactKindUi(artifact.type).description,
                        8.5f,
                        V2Colors.ink(.34f),
                        maxLines = 1,
                    )
                }
            }
        }
        // 设计稿是只读投影，钉选只对页面 / 文档开放。
        if (artifact.designId == null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (artifact.pinned) {
                            V2Colors.accent.copy(alpha = .92f)
                        } else {
                            V2Colors.surface.copy(alpha = .84f)
                        },
                    )
                    .clickable(enabled = !pinning, onClick = onPin),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = if (artifact.pinned) "取消钉选" else "钉选",
                    tint = if (artifact.pinned) V2Colors.actionInk else V2Colors.ink(.55f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * 卡片封面走哪条渲染分支：设计稿画布 / 文本预览 / 占位图标。
 *
 * 判定独立成纯函数，列表只管画，单测也能直接覆盖「有预览 / 无预览 / 空节点」。
 */
internal enum class ArtifactCoverMode { DESIGN_CANVAS, TEXT_PREVIEW, PLACEHOLDER }

/**
 * 列表封面的四种可区分状态：
 *  - 真实画布 / 文本预览：内容已加载；
 *  - [MISSING]：确实没有可预览内容；
 *  - [LOADING] / [FAILED]：还在读 / 读取失败，都可以通过刷新重试。
 */
internal enum class ArtifactCoverStatus {
    DESIGN_CANVAS, TEXT_PREVIEW, MISSING, LOADING, FAILED
}

internal fun artifactCoverStatus(
    artifact: Artifact,
    loading: Boolean,
    failed: Boolean,
): ArtifactCoverStatus = when (artifactCoverMode(artifact)) {
    ArtifactCoverMode.DESIGN_CANVAS -> ArtifactCoverStatus.DESIGN_CANVAS
    ArtifactCoverMode.TEXT_PREVIEW -> ArtifactCoverStatus.TEXT_PREVIEW
    ArtifactCoverMode.PLACEHOLDER -> when {
        failed -> ArtifactCoverStatus.FAILED
        loading -> ArtifactCoverStatus.LOADING
        else -> ArtifactCoverStatus.MISSING
    }
}

internal fun artifactCoverMode(artifact: Artifact): ArtifactCoverMode = when {
    artifact.type == "design" ->
        if (artifact.preview?.nodes?.isNotEmpty() == true) {
            ArtifactCoverMode.DESIGN_CANVAS
        } else {
            ArtifactCoverMode.PLACEHOLDER
        }
    // page / doc 列表只有 summary：能抽出可读文字就做轻量文本预览，否则退回占位图标。
    artifactCoverText(artifact).isNotEmpty() -> ArtifactCoverMode.TEXT_PREVIEW
    else -> ArtifactCoverMode.PLACEHOLDER
}

/** 封面文本预览的行：page 先去 HTML 标签，doc 去掉 Markdown 标记，最多四行。 */
internal fun artifactCoverText(artifact: Artifact): List<String> =
    artifactCoverText(artifact.type, artifact.summary)

internal fun artifactCoverText(type: String, summary: String): List<String> {
    val plain = if (type == "page") htmlToPlainText(summary) else summary
    return plain
        .split('\n')
        .map { plainMarkdownLine(it) }
        .filter { it.isNotBlank() }
        .take(4)
}

private val htmlBlockBreak = Regex(
    "(?i)<\\s*(br|/p|/div|/h[1-6]|/li|/tr|/section|/article)\\s*/?>",
)
private val htmlTag = Regex("(?s)<[^>]+>")

/** 页面摘要是 HTML 片段：去标签、还原常用实体，保留段落换行。 */
internal fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(htmlBlockBreak, "\n")
        .replace(htmlTag, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .split('\n')
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private val headingMarker = Regex("^#{1,6}\\s+")
private val listMarker = Regex("^([-*+]|\\d+[.)])\\s+")
private val quoteMarker = Regex("^>\\s?")
private val inlineImage = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val inlineLink = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
private val inlineCode = Regex("`([^`]*)`")
private val inlineBold = Regex("\\*\\*(.+?)\\*\\*")
private val inlineItalicStar = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
private val inlineItalicUnder = Regex("(?<![\\w])_([^_]+)_(?![\\w])")

/** 单行 Markdown → 纯文本：去掉标题 / 列表 / 引用记号与行内标记，只留可读文字。 */
internal fun plainMarkdownLine(raw: String): String {
    var line = raw.trim()
    line = line.replace(headingMarker, "")
    line = line.replace(listMarker, "")
    line = line.replace(quoteMarker, "")
    line = line
        .replace(inlineImage, "$1")
        .replace(inlineLink, "$1")
        .replace(inlineCode, "$1")
        .replace(inlineBold, "$1")
        .replace(inlineItalicStar, "$1")
        .replace(inlineItalicUnder, "$1")
    return line.trim()
}

@Composable
private fun ArtifactCover(
    artifact: Artifact,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val type = artifact.type
    val status = artifactCoverStatus(artifact, loading, failed)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        V2Colors.ink(.055f),
                        V2Colors.ink(.015f),
                    ),
                ),
            ),
    ) {
        when (status) {
            ArtifactCoverStatus.DESIGN_CANVAS -> {
                val preview = artifact.preview
                if (preview != null) {
                    DesignCoverPreview(
                        modifier = Modifier.fillMaxSize(),
                        preview = preview,
                        bg = null,
                    )
                } else {
                    ArtifactCoverPlaceholder(type, Modifier.fillMaxSize(), status)
                }
            }
            ArtifactCoverStatus.TEXT_PREVIEW -> ArtifactTextCoverPreview(
                type = type,
                lines = artifactCoverText(artifact),
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(Modifier.fillMaxSize()) {
                ArtifactCoverPlaceholder(type, Modifier.fillMaxSize(), status)
                if (status == ArtifactCoverStatus.FAILED) {
                    GhostButton(
                        "重试",
                        onRetry,
                        Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    )
                }
            }
        }
        ArtifactCoverTypeBadge(
            type,
            Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
        Label(
            artifactKindUi(type).label,
            9f,
            V2Colors.ink(.62f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(V2Colors.surface.copy(alpha = .82f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 占位图标：两张略微错开的圆角页面轮廓，对齐参考图的「空白文档」视觉。 */
@Composable
internal fun ArtifactCoverPlaceholder(
    type: String,
    modifier: Modifier = Modifier,
    status: ArtifactCoverStatus = ArtifactCoverStatus.MISSING,
) {
    val outline = V2Colors.ink(.22f)
    val pageFill = V2Colors.surface
    Box(modifier) {
    Canvas(Modifier.fillMaxSize()) {
        val pageHeight = size.height * .46f
        val pageWidth = pageHeight * .74f
        val overlap = pageWidth * .20f
        val radius = CornerRadius(pageWidth * .10f)
        val stroke = Stroke(width = 1.1.dp.toPx())
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // 后面那张偏右上。
        val back = Offset(
            centerX - pageWidth / 2f + overlap,
            centerY - pageHeight / 2f - overlap * .55f,
        )
        drawRoundRect(
            color = pageFill,
            topLeft = back,
            size = Size(pageWidth, pageHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = outline,
            topLeft = back,
            size = Size(pageWidth, pageHeight),
            cornerRadius = radius,
            style = stroke,
        )

        // 前面那张偏左下，用实底盖住后面的轮廓交叠处。
        val front = Offset(
            centerX - pageWidth / 2f - overlap,
            centerY - pageHeight / 2f + overlap * .55f,
        )
        drawRoundRect(
            color = pageFill,
            topLeft = front,
            size = Size(pageWidth, pageHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = outline,
            topLeft = front,
            size = Size(pageWidth, pageHeight),
            cornerRadius = radius,
            style = stroke,
        )

        // 类型细节：文档偏文字行，页面偏浏览器地址条，设计偏版式块。
        val detail = outline.copy(alpha = outline.alpha * .62f)
        val left = front.x + pageWidth * .16f
        val contentWidth = pageWidth * .68f
        val lineHeight = (pageHeight * .045f).coerceAtLeast(1.2.dp.toPx())
        when (type) {
            "page" -> {
                drawRoundRect(
                    color = detail,
                    topLeft = Offset(left, front.y + pageHeight * .14f),
                    size = Size(contentWidth, lineHeight * 1.4f),
                    cornerRadius = CornerRadius(lineHeight),
                )
                repeat(2) { index ->
                    drawRoundRect(
                        color = detail.copy(alpha = detail.alpha * .72f),
                        topLeft = Offset(left, front.y + pageHeight * (.34f + index * .16f)),
                        size = Size(contentWidth * if (index == 0) .9f else .64f, lineHeight),
                        cornerRadius = CornerRadius(lineHeight / 2f),
                    )
                }
            }
            "doc" -> {
                repeat(3) { index ->
                    drawRoundRect(
                        color = detail.copy(alpha = detail.alpha * if (index == 0) 1f else .7f),
                        topLeft = Offset(left, front.y + pageHeight * (.26f + index * .15f)),
                        size = Size(contentWidth * (1f - index * .14f), lineHeight),
                        cornerRadius = CornerRadius(lineHeight / 2f),
                    )
                }
            }
            else -> {
                drawRoundRect(
                    color = detail,
                    topLeft = Offset(left, front.y + pageHeight * .24f),
                    size = Size(pageWidth * .30f, pageHeight * .26f),
                    cornerRadius = CornerRadius(pageWidth * .05f),
                )
                repeat(2) { index ->
                    drawRoundRect(
                        color = detail.copy(alpha = detail.alpha * .68f),
                        topLeft = Offset(left, front.y + pageHeight * (.58f + index * .13f)),
                        size = Size(contentWidth * if (index == 0) .82f else .55f, lineHeight),
                        cornerRadius = CornerRadius(lineHeight / 2f),
                    )
                }
            }
        }
    }
        val caption = when (status) {
            ArtifactCoverStatus.LOADING -> "正在读取…"
            ArtifactCoverStatus.FAILED -> "封面加载失败"
            else -> null
        }
        if (caption != null) {
            Label(
                caption,
                8.5f,
                V2Colors.ink(.42f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(V2Colors.surface.copy(alpha = .86f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/** doc / page 的轻量文本封面：一页纸 + 真实摘要文字，不挂 WebView。 */
@Composable
private fun ArtifactTextCoverPreview(
    type: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        ArtifactCoverPlaceholder(type, modifier)
        return
    }
    Box(modifier.padding(10.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(V2Colors.surface.copy(alpha = .92f))
                .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            if (type == "page") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(V2Colors.ink(.20f)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .fillMaxWidth(.42f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(V2Colors.ink(.06f)),
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
            Text(
                text = lines.joinToString("\n"),
                color = V2Colors.ink(.72f),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = if (type == "page") 5 else 6,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ArtifactCoverTypeBadge(type: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(V2Colors.surface.copy(alpha = .82f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            artifactTypeIcon(type),
            contentDescription = null,
            tint = V2Colors.ink(.62f),
            modifier = Modifier.size(13.dp),
        )
    }
}

private fun artifactMeta(artifact: Artifact): String {
    val type = artifactTypeLabel(artifact.type)
    val time = prettyTime(artifact.updatedAt)
    return when {
        artifact.type == "design" && time.isNotBlank() -> "$type · $time"
        artifact.type == "design" -> "$type · v${artifact.version}"
        time.isNotBlank() -> "$type · $time"
        else -> "$type · v${artifact.version}"
    }
}

@Composable
private fun ArtifactEmpty(text: String, onReload: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label(text, 12.5f, V2Colors.ink(.40f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        GhostButton("刷新", onReload)
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun DesignStudioEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.DesignServices, null, tint = V2Colors.ink(.62f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label("设计工作室", 12.5f, V2Colors.ink(.88f), FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Label("视觉稿与画布：新建、导入、导出与 AI 改稿", 9.5f, V2Colors.ink(.38f), maxLines = 1)
        }
        Icon(Icons.Outlined.Add, null, tint = V2Colors.ink(.32f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ArtifactCreateSheet(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var type by remember { mutableStateOf("page") }
    var title by remember { mutableStateOf("") }
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .58f) {
        SheetHeader(
            "新建创作",
            "页面是可交互的单文件 HTML；文档是 Markdown。视觉稿请进设计工作室。",
            onClose = onDismiss,
        )
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtifactTypeOption(
                    "页面", Icons.Outlined.Language, type == "page", Modifier.weight(1f),
                ) { type = "page" }
                ArtifactTypeOption(
                    "文档", Icons.Outlined.Description, type == "doc", Modifier.weight(1f),
                ) { type = "doc" }
            }
            Spacer(Modifier.height(14.dp))
            Label("标题", 10f, V2Colors.ink(.42f), FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2Colors.ink(.05f))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = V2Colors.ink(.92f),
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(V2Colors.ink(.72f)),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (title.isEmpty()) {
                            Label("例如：产品落地页 / 发布说明", 12.5f, V2Colors.ink(.28f))
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("取消", onDismiss, Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    AccentButton(
                        text = if (creating) "正在创建…" else "创建",
                        enabled = !creating && title.isNotBlank(),
                        onClick = { onCreate(type, title.trim()) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ArtifactTypeOption(
    text: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (active) V2Colors.ink(.07f) else V2Colors.ink(.03f))
            .border(1.dp, if (active) V2Colors.ink(.22f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = V2Colors.ink(if (active) .82f else .48f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Label(text, 12f, V2Colors.ink(if (active) .88f else .56f), FontWeight.Medium)
    }
}

/**
 * 产物详情：页面用 WebView 预览 HTML，文档用简易 Markdown 渲染，源码模式可直接改。
 *
 * 设计稿不会进这里——点设计卡直接进原生画布。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactDetailScreen(
    artifactId: String,
    state: ArtifactsState,
    extraTop: Dp,
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSourceTask: (String) -> Unit = {},
    onLoadVersions: () -> Unit = {},
    onPreviewVersion: (Int) -> Unit = {},
    onRestoreVersion: (ArtifactVersion) -> Unit = {},
    onCloseVersionPreview: () -> Unit = {},
    onDownloadAsset: suspend (String) -> ByteArray? = { null },
    onDismissMessages: () -> Unit,
) {
    val artifact = state.detail?.takeIf { it.id == artifactId }
    var title by remember(artifactId) { mutableStateOf("") }
    var content by remember(artifactId) { mutableStateOf("") }
    var editing by remember(artifactId) { mutableStateOf(false) }
    var deleteOpen by remember(artifactId) { mutableStateOf(false) }
    var historyOpen by remember(artifactId) { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(historyOpen) {
        if (historyOpen) onLoadVersions()
    }

    // 同步策略：首次打开 / 用历史版本覆盖 / 保存成功时同步本地草稿；
    // 保存冲突刷新版本号时（本地有改动）不覆盖用户正在编辑的内容。
    var syncedTitle by remember(artifactId) { mutableStateOf<String?>(null) }
    var syncedContent by remember(artifactId) { mutableStateOf<String?>(null) }
    LaunchedEffect(artifact?.id, artifact?.version) {
        val loaded = artifact ?: return@LaunchedEffect
        val loadedContent = loaded.content.orEmpty()
        val localDirty = syncedTitle != null &&
            (title != syncedTitle || content != syncedContent)
        if (loaded.title == title && loadedContent == content) {
            syncedTitle = loaded.title
            syncedContent = loadedContent
            return@LaunchedEffect
        }
        if (syncedTitle == null || !localDirty) {
            title = loaded.title
            content = loadedContent
            syncedTitle = loaded.title
            syncedContent = loadedContent
        }
    }

    fun shareArtifact() {
        val current = artifact ?: return
        val doc = current.type == "doc"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (doc) "text/markdown" else "text/html"
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { current.title })
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(
            Intent.createChooser(intent, if (doc) "导出 Markdown" else "导出 HTML"),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2PageTitle(
                title = artifact?.title?.ifBlank { "未命名创作" } ?: "创作详情",
                modifier = Modifier.padding(top = extraTop),
                subtitle = artifact?.let {
                    if (it.summary.isBlank() && it.description.isNotBlank()) {
                        it.description
                    } else {
                        "${artifactTypeLabel(it.type)} · v${it.version}"
                    }
                } ?: "正在读取…",
                trailing = {
                    if (showBack) {
                        RoundIconButton(
                            Icons.Outlined.ArrowBack,
                            onBack,
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "返回",
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (artifact != null) {
                        RoundIconButton(
                            Icons.Outlined.Share,
                            onClick = { shareArtifact() },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = if (artifact.type == "doc") {
                                "导出 Markdown"
                            } else {
                                "导出 HTML"
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.History,
                            onClick = { historyOpen = true },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "版本历史",
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.Delete,
                            onClick = { deleteOpen = true },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = "删除",
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.Save,
                            onClick = { onSave(title, content) },
                            size = 34.dp,
                            iconSize = 16.dp,
                            contentDescription = if (state.saving) "正在保存" else "保存",
                        )
                    }
                },
            )

            ArtifactFlash(state.error ?: state.notice, state.error != null, onDismissMessages)

            when {
                artifact == null && state.detailLoading ->
                    ArtifactEmpty("正在读取创作…") {}
                artifact == null -> ArtifactEmpty("创作不存在或已被删除", {})
                else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    ArtifactModeSwitch(
                        typeLabel = artifactTypeLabel(artifact.type),
                        editing = editing,
                        onToggle = { editing = !editing },
                    )
                    if (state.error != null) {
                        // 保存失败 / 版本冲突后保留本地草稿，给一个明确的“再试一次”入口。
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("这次修改还没有保存", 10.5f, V2Colors.rose, FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            GhostButton(
                                if (state.saving) "正在重试…" else "重试保存",
                                onClick = { if (!state.saving) onSave(title, content) },
                            )
                        }
                    }
                    artifact.sourceTaskId?.let { taskId ->
                        Spacer(Modifier.height(8.dp))
                        SourceConversationRow(
                            taskId = taskId,
                            onOpen = { onOpenSourceTask(taskId) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (editing) {
                        ArtifactEditor(
                            title = title,
                            content = content,
                            onTitleChange = { title = it.take(60) },
                            onContentChange = { content = it },
                            modifier = Modifier.weight(1f).imePadding(),
                        )
                    } else if (artifact.type == "page") {
                        ArtifactHtmlPreview(
                            html = content,
                            versionKey = "${artifact.id}:${artifact.version}",
                            assets = state.assets,
                            onDownloadAsset = onDownloadAsset,
                            onEdit = { editing = true },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        ArtifactDocPreview(
                            content = content,
                            artifactId = artifact.id,
                            assets = state.assets,
                            onDownloadAsset = onDownloadAsset,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }

        if (deleteOpen && artifact != null) {
            SheetFrame(onDismiss = { deleteOpen = false }, maxHeightFraction = .44f) {
                SheetHeader(
                    "删除「${artifact.title}」？",
                    "删除后无法恢复，设计稿的删除仍在设计工作室里。",
                    onClose = { deleteOpen = false },
                )
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("取消", { deleteOpen = false }, Modifier.weight(1f))
                        Box(Modifier.weight(1f)) {
                            AccentButton(
                                text = "删除",
                                enabled = state.deletingId == null,
                                onClick = {
                                    deleteOpen = false
                                    onDelete(artifact.id)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }

        if (historyOpen && artifact != null) {
            ArtifactHistorySheet(
                versions = state.versions,
                loading = state.versionsLoading,
                currentVersion = artifact.version,
                preview = state.versionPreview,
                previewLoading = state.versionPreviewLoading,
                restoringVersion = state.restoringVersion,
                onPreview = onPreviewVersion,
                onClosePreview = onCloseVersionPreview,
                onRestore = onRestoreVersion,
                onDismiss = {
                    historyOpen = false
                    onCloseVersionPreview()
                },
            )
        }
    }
}

/**
 * 版本历史：列表 / 单版预览 / 「用此版本覆盖保存」。
 *
 * 覆盖保存走 update + base_version，会生成新版本；冲突时 VM 保留草稿并要求重试，
 * 不偷偷 force。
 */
@Composable
private fun ArtifactHistorySheet(
    versions: List<ArtifactVersion>,
    loading: Boolean,
    currentVersion: Int,
    preview: ArtifactVersion?,
    previewLoading: Int?,
    restoringVersion: Int?,
    onPreview: (Int) -> Unit,
    onClosePreview: () -> Unit,
    onRestore: (ArtifactVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(onDismiss = onDismiss, maxHeightFraction = .78f) {
        if (preview != null) {
            SheetHeader(
                title = "预览 v${preview.version}",
                subtitle = preview.title.ifBlank { "历史版本" },
                onClose = onDismiss,
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(V2Colors.ink(.035f))
                        .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(14.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    val text = preview.content.orEmpty()
                    if (text.isBlank()) {
                        Label("这一版没有正文", 11.5f, V2Colors.ink(.38f))
                    } else {
                        Label(
                            text.take(6000),
                            10.5f,
                            V2Colors.ink(.72f),
                            mono = true,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("返回列表", onClosePreview, Modifier.weight(1f))
                    Box(Modifier.weight(1f)) {
                        AccentButton(
                            text = if (restoringVersion == preview.version) {
                                "正在恢复…"
                            } else {
                                "用此版本覆盖保存"
                            },
                            enabled = restoringVersion == null,
                            onClick = { onRestore(preview) },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        } else {
        SheetHeader(
            title = "版本历史",
            subtitle = "当前 v$currentVersion · 每次保存自动留档",
            onClose = onDismiss,
        )
        when {
            loading && versions.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp,
                    color = V2Colors.cyan700,
                )
            }
            versions.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label("还没有历史版本，保存一次后就会留档", 10.5f, V2Colors.ink(.42f))
            }
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(versions, key = { it.version }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(
                                "v${item.version} · ${item.title.ifBlank { "未命名" }}",
                                12f,
                                V2Colors.ink(.82f),
                                FontWeight.Medium,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Label(
                                prettyTime(item.createdAt).ifBlank { "时间未知" },
                                9.5f,
                                V2Colors.ink(.36f),
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (previewLoading == item.version) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 1.6.dp,
                                color = V2Colors.cyan700,
                            )
                        } else {
                            GhostButton("预览", { onPreview(item.version) })
                        }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
        if (loading && versions.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.6.dp,
                    color = V2Colors.cyan700,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ArtifactModeSwitch(typeLabel: String, editing: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.ink(.04f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(typeLabel, 10f, V2Colors.ink(.36f), modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.weight(1f))
        ArtifactModeButton("预览", !editing, onToggle)
        Spacer(Modifier.width(4.dp))
        ArtifactModeButton("源码", editing, onToggle)
    }
}

@Composable
private fun ArtifactModeButton(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) V2Colors.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(
            text,
            10.5f,
            if (active) V2Colors.ink(.88f) else V2Colors.ink(.44f),
            if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactHtmlPreview(
    html: String,
    versionKey: String,
    assets: List<ArtifactAsset>,
    onDownloadAsset: suspend (String) -> ByteArray?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var desktop by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val latestAssets = rememberUpdatedStateCompat(assets)
    val latestDownload = rememberUpdatedStateCompat(onDownloadAsset)
    val blank = html.isBlank()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(V2Colors.ink(.03f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("预览", 9.5f, V2Colors.ink(.38f), FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            ArtifactViewportChip("手机", !desktop) { desktop = false }
            Spacer(Modifier.width(6.dp))
            ArtifactViewportChip("桌面", desktop) { desktop = true }
            Spacer(Modifier.width(10.dp))
            GhostButton("重新加载", {
                failed = false
                reload += 1
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (blank) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Label("这个页面还没有内容", 12f, V2Colors.ink(.52f), FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Label("切到「源码」写下第一段 HTML。", 10f, V2Colors.ink(.36f))
                    Spacer(Modifier.height(12.dp))
                    GhostButton("去源码编辑", onEdit)
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                allowFileAccess = false
                                allowContentAccess = false
                            }
                            // 成品预览是同机离线渲染：使用独立的 flux-artifact.local origin，
                            // 且不带第三方 cookie，避免读到内置浏览器里的站点登录态。
                            runCatching {
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? = interceptArtifactRequest(
                                    url = request.url.toString(),
                                    assets = latestAssets.value,
                                    download = latestDownload.value,
                                )

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: android.webkit.WebResourceError,
                                ) {
                                    if (request.isForMainFrame) failed = true
                                }
                            }
                        }
                    },
                    update = { web ->
                        // 资产是异步加载的：数量变化时补一次刷新，相对图片/字体不会一直 403。
                        val key = "$versionKey:${if (desktop) "desktop" else "mobile"}:$reload:${assets.size}"
                        if (web.tag != key) {
                            web.tag = key
                            web.loadDataWithBaseURL(
                                ArtifactWebPolicy.ORIGIN,
                                ArtifactWebPolicy.viewportHtml(html, desktop),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                )
                if (failed) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(V2Colors.surface.copy(alpha = .94f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Label("页面预览加载失败", 12f, V2Colors.rose, FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Label("可能是脚本或外部资源被拦截。", 10f, V2Colors.ink(.38f))
                        Spacer(Modifier.height(12.dp))
                        GhostButton("重试", {
                            failed = false
                            reload += 1
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactViewportChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) V2Colors.accent.copy(alpha = .14f) else Color.Transparent)
            .border(
                1.dp,
                if (active) V2Colors.accent.copy(alpha = .34f) else V2Colors.lineSoft,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Label(
            text,
            9f,
            if (active) V2Colors.cyan800 else V2Colors.ink(.45f),
            if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** `shouldInterceptRequest` 的纯策略外壳：白名单外一律拦截，相对路径走资产库。 */
private fun interceptArtifactRequest(
    url: String,
    assets: List<ArtifactAsset>,
    download: suspend (String) -> ByteArray?,
): WebResourceResponse? {
    if (url.startsWith(ArtifactWebPolicy.ORIGIN, ignoreCase = true)) {
        val path = runCatching { android.net.Uri.parse(url).path.orEmpty() }.getOrDefault("")
        if (path.isEmpty() || path == "/") return null
        val asset = ArtifactWebPolicy.assetForPath(path, assets)
            ?: return blockedArtifactResponse()
        val bytes = runBlocking { download(asset.id) } ?: ByteArray(0)
        return WebResourceResponse(
            asset.contentType.ifBlank { "application/octet-stream" },
            null,
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            java.io.ByteArrayInputStream(bytes),
        )
    }
    if (ArtifactWebPolicy.isLocal(url)) return null
    if (ArtifactWebPolicy.shouldBlock(url)) return blockedArtifactResponse()
    return null
}

private fun blockedArtifactResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked by artifact policy",
    mapOf("Cache-Control" to "no-store"),
    java.io.ByteArrayInputStream(ByteArray(0)),
)

@Composable
private fun <T> rememberUpdatedStateCompat(value: T) =
    androidx.compose.runtime.rememberUpdatedState(value)

/**
 * 文档预览：解析结果来自纯函数 [ArtifactMarkdown.parseMarkdown]，
 * 覆盖标题、段落、引用、代码块（带语言标签与轻量高亮）、嵌套 / 任务列表、
 * 表格、分隔线与图片（data URI / 产物资产）。
 */
@Composable
private fun ArtifactDocPreview(
    content: String,
    artifactId: String,
    assets: List<ArtifactAsset>,
    onDownloadAsset: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val doc = remember(content) { ArtifactMarkdown.parseMarkdown(content) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(V2Colors.surface)
            .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (content.isBlank()) {
            Label("还没有内容，切到「源码」写点什么。", 12f, V2Colors.ink(.35f))
        } else {
        doc.warning?.let { warning ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(V2Colors.ink(.05f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Label(warning, 9.5f, V2Colors.ink(.52f))
            }
            Spacer(Modifier.height(8.dp))
        }
        doc.blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 19f
                        2 -> 16f
                        3 -> 14f
                        4 -> 13f
                        else -> 12f
                    }
                    MarkdownInlineText(
                        text = block.text,
                        size = size,
                        color = V2Colors.ink(.95f),
                        weight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(5.dp))
                }
                is MdBlock.Paragraph -> {
                    MarkdownInlineText(block.text, 12.5f, V2Colors.ink(.78f))
                    Spacer(Modifier.height(5.dp))
                }
                is MdBlock.Quote -> {
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .heightIn(min = 18.dp)
                                .background(V2Colors.ink(.18f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        MarkdownInlineText(
                            text = block.text,
                            size = 12.5f,
                            color = V2Colors.ink(.55f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                is MdBlock.Code -> {
                    CodeBlock(block.language, block.code)
                    Spacer(Modifier.height(6.dp))
                }
                is MdBlock.ListBlock -> {
                    block.items.forEachIndexed { index, item ->
                        MarkdownListItemRow(block, item, index)
                    }
                    Spacer(Modifier.height(5.dp))
                }
                is MdBlock.Table -> {
                    MarkdownTable(block)
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Image -> {
                    MarkdownImage(
                        alt = block.alt,
                        source = block.source,
                        assets = assets,
                        onDownloadAsset = onDownloadAsset,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(V2Colors.ink(.08f)),
                )
            }
        }
        }
    }
}

@Composable
private fun MarkdownListItemRow(block: MdBlock.ListBlock, item: MdListItem, index: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 16).dp, top = 1.dp, bottom = 1.dp),
    ) {
        when {
            item.checked != null -> Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (item.checked) V2Colors.accent else Color.Transparent,
                    )
                    .border(1.dp, V2Colors.ink(.22f), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.checked) {
                    Icon(
                        Icons.Outlined.Check,
                        null,
                        tint = V2Colors.actionInk,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }
            block.ordered -> Label(
                "${item.marker ?: (block.start + index)}.",
                12f,
                V2Colors.ink(.5f),
                modifier = Modifier.width(22.dp),
            )
            else -> Label("•", 12f, V2Colors.ink(.5f), modifier = Modifier.width(16.dp))
        }
        Spacer(Modifier.width(if (item.checked != null) 8.dp else 2.dp))
        MarkdownInlineText(
            item.text,
            12.5f,
            if (item.checked == true) V2Colors.ink(.48f) else V2Colors.ink(.78f),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, V2Colors.ink(.10f), RoundedCornerShape(10.dp)),
    ) {
        MarkdownTableRow(table.header, table.aligns, header = true)
        table.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(V2Colors.ink(.08f)))
            MarkdownTableRow(row, table.aligns, header = false)
        }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, aligns: List<MdAlign>, header: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (header) V2Colors.ink(.04f) else Color.Transparent)) {
        cells.forEachIndexed { index, cell ->
            val align = when (aligns.getOrNull(index)) {
                MdAlign.CENTER -> TextAlign.Center
                MdAlign.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                MarkdownInlineText(
                    text = cell,
                    size = 11f,
                    color = if (header) V2Colors.ink(.82f) else V2Colors.ink(.70f),
                    weight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = align,
                )
            }
        }
    }
}

/** 图片：data URI 直接解码；产物资产走已登录的 FluxApi；缺失时有设计过的占位。 */
@Composable
private fun MarkdownImage(
    alt: String,
    source: String,
    assets: List<ArtifactAsset>,
    onDownloadAsset: suspend (String) -> ByteArray?,
) {
    var bitmap by remember(source, assets) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(source, assets) { mutableStateOf(false) }
    LaunchedEffect(source, assets) {
        bitmap = null
        failed = false
        val bytes = when {
            source.startsWith("data:image/", ignoreCase = true) -> {
                val payload = source.substringAfter("base64,", "")
                if (payload.isBlank()) null else {
                    runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
                }
            }
            else -> {
                val asset = ArtifactWebPolicy.assetForPath(source, assets)
                if (asset == null) null else onDownloadAsset(asset.id)
            }
        }
        val decoded = bytes?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
        if (decoded != null) bitmap = decoded else failed = true
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = alt.ifBlank { null },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(V2Colors.ink(.04f))
                .border(1.dp, V2Colors.ink(.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                tint = V2Colors.ink(.26f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Label(
                when {
                    failed -> "图片无法显示"
                    else -> "正在读取图片…"
                },
                10f,
                V2Colors.ink(.40f),
            )
            if (alt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Label(alt, 9f, V2Colors.ink(.30f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val label = ArtifactCode.label(language)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(V2Colors.ink(.05f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(10.dp)),
    ) {
        if (label.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(V2Colors.ink(.04f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Label(label, 8.5f, V2Colors.ink(.38f), FontWeight.Medium, mono = true)
            }
        }
        Text(
            text = ArtifactCode.highlight(code, language),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = V2Colors.ink(.82f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    size: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = inlineMarkdown(text),
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.55f).sp,
        textAlign = textAlign,
    )
}

@Composable
private fun SourceConversationRow(taskId: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(V2Colors.ink(.04f))
            .border(1.dp, V2Colors.ink(.06f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Chat,
            contentDescription = null,
            tint = V2Colors.ink(.55f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label("打开来源对话", 12f, V2Colors.ink(.82f), FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Label("这段创作由对话发布 · ${taskId.take(8)}", 9.5f, V2Colors.ink(.38f), maxLines = 1)
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = V2Colors.ink(.32f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ArtifactEditor(
    title: String,
    content: String,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(V2Colors.ink(.045f))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                textStyle = TextStyle(color = V2Colors.ink(.92f), fontSize = 13.sp),
                cursorBrush = SolidColor(V2Colors.ink(.72f)),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (title.isEmpty()) Label("标题", 12.5f, V2Colors.ink(.28f))
                    inner()
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(V2Colors.surface)
                .border(1.dp, V2Colors.lineSoft, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = TextStyle(
                    color = V2Colors.ink(.80f),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                ),
                cursorBrush = SolidColor(V2Colors.ink(.72f)),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { inner ->
                    if (content.isEmpty()) {
                        Label("在这里粘贴 HTML 或 Markdown", 11.5f, V2Colors.ink(.28f), mono = true)
                    }
                    inner()
                },
            )
        }
    }
}

/** 页面提示条：错误红、提示绿，与全局 flash 的语义一致。 */
@Composable
private fun ArtifactFlash(message: String?, isError: Boolean, onDismiss: () -> Unit) {
    if (message.isNullOrBlank()) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background((if (isError) V2Colors.rose else V2Colors.emerald).copy(alpha = .10f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Label(
            message,
            10.5f,
            if (isError) V2Colors.rose else V2Colors.emerald700,
            FontWeight.Medium,
            maxLines = 2,
        )
    }
}

@Composable
private fun AccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) V2Colors.accent else V2Colors.ink(.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            12.5f,
            if (enabled) V2Colors.actionInk else V2Colors.ink(.38f),
            FontWeight.SemiBold,
        )
    }
}
