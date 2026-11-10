package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import top.qiuli55.flux.mobile.data.DesignNode
import kotlin.math.roundToInt

/** autoFit 量宽用的「无约束」宽度：Compose Constraints 单边上限 262143 内的安全值。 */
private const val MAX_MEASURE_WIDTH = 100_000

/** 导航里的一个格子：文字已经量好，绘制时只需按格子居中。 */
data class ResolvedNavCell(
    val text: String,
    val active: Boolean,
    val layout: TextLayoutResult,
)

/**
 * 预解析后的节点：颜色 / 圆角 / 文本排版全部算好，绘制阶段只做几何运算。
 *
 * 坐标是**画板绝对坐标**（父节点偏移已累加），子节点也一并展平到 [children] 里。
 */
data class ResolvedNode(
    val id: String,
    val type: String,
    val name: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val alpha: Float,
    /** 四角圆角。 */
    val corners: DesignSpec.Corners,
    val rotation: Float,
    /** 圆形节点（circle / avatar / 正方形 decor）。 */
    val ellipse: Boolean,
    /** null 表示不画底色（纯文本节点）。 */
    val fill: Brush?,
    /** 填充的单色近似（滑杆进度、开关底色、指示条都用它）。 */
    val fillColor: Color,
    /** 滑杆轨道色。 */
    val trackColor: Color,
    /** 节点描边；null 表示不描边。 */
    val stroke: DesignSpec.StrokeSpec?,
    /** 外阴影环，顺序即绘制顺序（底层 → 顶层）。 */
    val shadowRings: List<DesignSpec.ShadowRing>,
    /** 内阴影环，顺序即绘制顺序（底层 → 顶层）。 */
    val insetShadowRings: List<DesignSpec.ShadowRing>,
    val text: String,
    /** 主文本的绘制原点（已含对齐与垂直居中偏移）。 */
    val textOrigin: Offset,
    val textLayout: TextLayoutResult?,
    /** 文字描边（宽度已折算成居中描边）；null 表示不描边。 */
    val textStroke: DesignSpec.TextStroke? = null,
    /** 发光/投影环，顺序即绘制顺序（底层 → 顶层）。 */
    val textRings: List<DesignSpec.TextRing> = emptyList(),
    val navCells: List<ResolvedNavCell>,
    /** 滑杆进度 0..1。 */
    val value: Float,
    val switchOn: Boolean,
    /** 占位符透明度：input 为 0.5，其余为 1；绘制期与 [alpha] 相乘，不并进颜色。 */
    val placeholderAlpha: Float = 1f,
    /** 子节点是否裁剪到本节点圆角框内（对应网页 `overflow:hidden`）。 */
    val clip: Boolean = false,
    val children: List<ResolvedNode>,
) {
    val center: Offset get() = Offset(x + w / 2f, y + h / 2f)

    val right: Float get() = x + w
    val bottom: Float get() = y + h
}

/**
 * 把 [DesignNode] 树解析成可直接绘制的 [ResolvedNode] 树。
 *
 * 文本排版是整条渲染链路最贵的一步（尤其在拖动时每帧都变），这里按
 * 「文字 + 样式 + 可用宽度」做 LRU 缓存，节点坐标变化不会触发重新排版。
 */
class DesignResolver(
    private val measurer: TextMeasurer,
    private val density: Float,
    private val fontScale: Float = 1f,
) {
    private data class TextKey(
        val text: String,
        val color: Color,
        val family: FontFamily,
        val sizePx: Float,
        val weight: Int,
        val lineHeightPx: Float,
        val letterSpacingPx: Float,
        val align: Int,
        val maxWidth: Int,
        val maxLines: Int,
        val singleLine: Boolean,
        /** 原始 `textFill` 串：渐变画笔不进 key 的 equals（Brush 的相等性不稳），用串比较。 */
        val fill: String? = null,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
    )

    private val cache = LinkedHashMap<TextKey, TextLayoutResult>(128, .75f, true)

    fun resolve(
        nodes: List<DesignNode>,
        origin: Offset = Offset.Zero,
        parentAlpha: Float = 1f,
    ): List<ResolvedNode> = nodes.mapNotNull { resolveNode(it, origin, parentAlpha) }

    private fun resolveNode(node: DesignNode, origin: Offset, parentAlpha: Float): ResolvedNode? {
        if (node.hidden == true) return null
        val alpha = (parentAlpha * (node.opacity ?: 1f)).coerceIn(0f, 1f)
        if (alpha <= 0.01f || node.w <= 0f || node.h <= 0f) return null

        val type = DesignSpec.typeOf(node)
        val x = origin.x + node.x
        val y = origin.y + node.y
        val corners = DesignSpec.cornersOf(node)
        val ellipse = type == "circle" || type == "avatar" ||
            (type == "decor" && kotlin.math.abs(node.w - node.h) < .01f)
        // 纯文字节点没有填充时不能落回默认底色；显式 none/transparent 同样不画底色。
        // 旧设计稿把文字色存在 fill（无 color）时，fill 是文字色而不是底色。
        val legacyInk = DesignSpec.legacyTextInk(node)
        val bareText = type == "text" && (node.fill.isNullOrBlank() || legacyInk != null)
        val noFill = bareText || DesignSpec.isNoneFill(node.fill)
        val hasShape = !noFill
        val fillColor = if (type == "switch" && node.on != true) {
            DesignSpec.SWITCH_OFF
        } else {
            DesignSpec.singleColor(node.fill, DesignSpec.defaultFill(type))
        }
        val fill = when {
            !hasShape -> null
            type == "switch" && node.on != true -> Brush.verticalGradient(
                listOf(DesignSpec.SWITCH_OFF, DesignSpec.SWITCH_OFF),
            )
            else -> DesignSpec.brush(node.fill, DesignSpec.defaultFill(type))
        }

        var textOrigin = Offset.Zero
        var textLayout: TextLayoutResult? = null
        val text = node.text.orEmpty()
        val navCells = if (type == "nav") resolveNav(node, x, y) else emptyList()
        if (type != "nav" && text.isNotBlank()) {
            val inset = if (type == "input") {
                (node.padLeft ?: DesignSpec.DEFAULT_INPUT_PAD).coerceAtLeast(0f)
            } else {
                0f
            }
            val boxWidth = (node.w - inset).coerceAtLeast(1f)
            val align = when {
                type in DesignSpec.CENTER_TEXT_TYPES -> TextAlign.Center
                node.align == "center" -> TextAlign.Center
                node.align == "right" -> TextAlign.End
                else -> TextAlign.Start
            }
            val color = legacyInk
                ?: DesignSpec.color(node.color, DesignSpec.defaultTextColor(type))
            val family = DesignSpec.fontFamilyOf(node)
            val weight = DesignSpec.weightOf(node).weight
            val ratio = node.lineHeight ?: DesignSpec.DEFAULT_LINE_HEIGHT
            val letterSpacing = node.letterSpacing ?: 0f
            val autoFit = DesignSpec.autoFitOf(node)
            val size = if (autoFit) {
                fitSize(
                    text = text,
                    color = color,
                    family = family,
                    weight = weight,
                    lineHeightRatio = ratio,
                    letterSpacing = letterSpacing,
                    align = align,
                    boxWidth = boxWidth,
                    base = node.fontSize ?: DesignSpec.DEFAULT_FONT_SIZE,
                )
            } else {
                node.fontSize ?: DesignSpec.DEFAULT_FONT_SIZE
            }
            val layout = layoutText(
                text = text,
                color = color,
                family = family,
                size = size,
                weight = weight,
                lineHeight = size * ratio,
                letterSpacing = letterSpacing,
                align = align,
                maxWidth = boxWidth,
                maxLines = if (autoFit) 1 else DesignSpec.maxLinesOf(node),
                singleLine = autoFit,
                fill = node.textFill,
                italic = node.italic == true,
                underline = node.underline == true,
                strikethrough = node.strikethrough == true,
            )
            textLayout = layout
            val dx = x + inset + when (align) {
                TextAlign.Center -> (boxWidth - layout.size.width) / 2f
                TextAlign.End -> boxWidth - layout.size.width
                else -> 0f
            }
            textOrigin = Offset(dx, y + (node.h - layout.size.height) / 2f)
        }

        return ResolvedNode(
            id = node.id,
            type = type,
            name = node.name,
            x = x,
            y = y,
            w = node.w,
            h = node.h,
            alpha = alpha,
            corners = corners,
            rotation = node.rotation ?: 0f,
            ellipse = ellipse,
            fill = fill,
            fillColor = fillColor,
            trackColor = DesignSpec.color(node.track, DesignSpec.SWITCH_OFF),
            stroke = DesignSpec.strokeOf(node),
            shadowRings = DesignSpec.shadowRings(node),
            insetShadowRings = DesignSpec.insetShadowRings(node),
            text = text,
            textOrigin = textOrigin,
            textLayout = textLayout,
            textStroke = if (textLayout == null) null else DesignSpec.textStrokeOf(node),
            textRings = if (textLayout == null) emptyList() else DesignSpec.textRingsOf(node),
            navCells = navCells,
            value = (node.value ?: .5f).coerceIn(0f, 1f),
            switchOn = node.on == true,
            placeholderAlpha = if (type == "input") DesignSpec.INPUT_PLACEHOLDER_ALPHA else 1f,
            clip = type in DesignSpec.CLIP_TYPES,
            children = resolve(node.children, Offset(x, y), alpha),
        )
    }

    private fun resolveNav(node: DesignNode, x: Float, y: Float): List<ResolvedNavCell> {
        val items = node.items.filter { it.isNotBlank() }
            .ifEmpty { listOf("首页", "任务", "我的") }
        val active = (node.active ?: 0).coerceIn(0, items.lastIndex)
        val cell = node.w / items.size
        val size = node.fontSize ?: DesignSpec.DEFAULT_NAV_FONT_SIZE
        return items.mapIndexed { index, item ->
            val on = index == active
            ResolvedNavCell(
                text = item,
                active = on,
                layout = layoutText(
                    text = item,
                    color = if (on) DesignSpec.ACCENT else DesignSpec.INK.copy(alpha = .42f),
                    // 网页 `.nav-cell span` 不设 font-family，继承 body 的系统栈。
                    family = FontFamily.Default,
                    size = size,
                    weight = if (on) 600 else 400,
                    lineHeight = size * 1.2f,
                    letterSpacing = 0f,
                    align = TextAlign.Center,
                    maxWidth = cell,
                    maxLines = 1,
                    singleLine = true,
                ),
            )
        }
    }

    /** 与网页 `word-break:normal` 对齐：CJK 可逐字断行、拉丁按词断行，不做短语约束。 */
    private val LINE_BREAK = LineBreak(
        strategy = LineBreak.Strategy.Simple,
        strictness = LineBreak.Strictness.Strict,
        wordBreak = LineBreak.WordBreak.Default,
    )

    /** 行高在行内居中分配，且不裁剪上下缘；与 CSS `line-height` 的半个行距完全一致。 */
    private val LINE_HEIGHT_STYLE = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    /** 下划线 / 删除线组合，对齐网页 `text-decoration: underline line-through`。 */
    private fun textDecorationOf(underline: Boolean, strikethrough: Boolean): TextDecoration = when {
        underline && strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
        )
        underline -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> TextDecoration.None
    }

    /**
     * autoFit：单行无约束量宽，放不下就按 0.5 步长缩字号，最低 60%。
     * 判定口径与网页 `fitTexts()` 一致（宽度差 0.5 以内算放得下）。
     */
    private fun fitSize(
        text: String,
        color: Color,
        family: FontFamily,
        weight: Int,
        lineHeightRatio: Float,
        letterSpacing: Float,
        align: TextAlign,
        boxWidth: Float,
        base: Float,
    ): Float {
        val floor = base * DesignSpec.AUTO_FIT_MIN_SCALE
        var size = base
        while (size > floor) {
            val probe = layoutText(
                text = text,
                color = color,
                family = family,
                size = size,
                weight = weight,
                lineHeight = size * lineHeightRatio,
                letterSpacing = letterSpacing,
                align = align,
                maxWidth = Float.POSITIVE_INFINITY,
                maxLines = 1,
                singleLine = true,
            )
            if (probe.size.width <= boxWidth + .5f) break
            size -= DesignSpec.AUTO_FIT_STEP
        }
        return size.coerceAtLeast(floor)
    }

    private fun layoutText(
        text: String,
        color: Color,
        family: FontFamily,
        size: Float,
        weight: Int,
        lineHeight: Float,
        letterSpacing: Float,
        align: TextAlign,
        maxWidth: Float,
        maxLines: Int,
        singleLine: Boolean = false,
        fill: String? = null,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
    ): TextLayoutResult {
        // 设计稿字号是「设计单位」，必须与网页预览逐像素对齐：这里抵消系统字体缩放，
        // 让 1 设计单位恒等于 1 画板单位。
        val unit = density * fontScale
        val brush = DesignSpec.textBrushOf(fill)
        // Compose 的 `TextStyle` 有 color 与 brush 两套构造：brush 构造没有 color 参数，
        // 两者不能同时给（SpanStyle 会要求 color 为 Unspecified），所以这里显式分支。
        val style = if (brush == null) {
            TextStyle(
                color = color,
                fontSize = (size / unit).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight(weight),
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = textDecorationOf(underline, strikethrough),
                fontFamily = family,
                lineHeight = (lineHeight / unit).sp,
                letterSpacing = (letterSpacing / unit).sp,
                textAlign = align,
                lineBreak = LINE_BREAK,
                lineHeightStyle = LINE_HEIGHT_STYLE,
                // Android 默认字体留白会让行盒比 CSS 高几个像素，行盒上下不对齐。
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        } else {
            TextStyle(
                brush = brush,
                fontSize = (size / unit).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight(weight),
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = textDecorationOf(underline, strikethrough),
                fontFamily = family,
                lineHeight = (lineHeight / unit).sp,
                letterSpacing = (letterSpacing / unit).sp,
                textAlign = align,
                lineBreak = LINE_BREAK,
                lineHeightStyle = LINE_HEIGHT_STYLE,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        }
        // 四舍五入而非截断：网页与桌面的约束宽度都是浮点，截断会让换行点比另两端早 1px。
        // 上限取 Constraints 安全值（Compose 限制单边 <= 262143），autoFit 量宽时用它代替无穷大。
        val width = if (maxWidth.isFinite()) {
            maxWidth.roundToInt().coerceIn(1, MAX_MEASURE_WIDTH)
        } else {
            MAX_MEASURE_WIDTH
        }
        val key = TextKey(
            text = text,
            color = color,
            family = family,
            sizePx = size,
            weight = weight,
            lineHeightPx = lineHeight,
            letterSpacingPx = letterSpacing,
            align = align.hashCode(),
            maxWidth = width,
            maxLines = maxLines,
            singleLine = singleLine,
            fill = fill,
            italic = italic,
            underline = underline,
            strikethrough = strikethrough,
        )
        cache[key]?.let { return it }
        val result = measurer.measure(
            AnnotatedString(text),
            style = style,
            maxLines = maxLines,
            softWrap = !singleLine,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = width),
        )
        if (cache.size > 400) cache.clear()
        cache[key] = result
        return result
    }
}
