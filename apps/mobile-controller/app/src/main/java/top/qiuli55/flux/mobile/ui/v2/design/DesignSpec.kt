package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import top.qiuli55.flux.mobile.data.DesignNode
import top.qiuli55.flux.mobile.data.DesignShadow
import top.qiuli55.flux.mobile.data.DesignTextEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 设计稿渲染规范：唯一事实来源。
 *
 * 数值与后端 `backend/flux/core/design_compile.py` + `design_tokens.py`、桌面端
 * `DesignCanvas.cs` 一一对应，改这里之前先确认三端是否要一起改。
 *
 * 注意：设计稿里所有颜色都是**固定色**，不跟随 App 深浅色主题，否则「网页预览」与 App
 * 内的画布会长得不一样。
 */
object DesignSpec {
    val ACCENT: Color = Color(0xFF0891B2)
    val INK: Color = Color(0xFF0F172A)
    val SHELL_TOP: Color = Color(0xFFF4F6F9)
    val SWITCH_OFF: Color = Color(0xFFE2E8F0)
    val INPUT_FILL: Color = Color(0xFFF8FAFC)

    /** 设备外框圆角，对齐 `.device{border-radius:26px}`。 */
    const val DEVICE_RADIUS = 26f
    const val DEVICE_SHADOW_DY = 26f
    const val DEVICE_SHADOW_BLUR = 60f
    const val DEVICE_SHADOW_ALPHA = 140

    const val DEFAULT_FONT_SIZE = 13f
    const val DEFAULT_LINE_HEIGHT = 1.35f
    const val DEFAULT_NAV_FONT_SIZE = 10.5f
    const val DEFAULT_INPUT_PAD = 12f
    const val MAX_TEXT_LINES = 3
    const val TEXT_LINES_LIMIT = 64

    /** autoFit 的下限：字号最多缩到 60%（与网页 `fitTexts` 的 `base * 0.6` 一致）。 */
    const val AUTO_FIT_MIN_SCALE = .6f

    /** autoFit 的缩字步长，设计单位。 */
    const val AUTO_FIT_STEP = .5f
    const val CARD_SHADOW_OFFSET = 6f
    const val CARD_SHADOW_ALPHA = .05f

    /** Web `.t-input input::placeholder{opacity:.5}`：设计稿里的输入框文字按占位符绘制。 */
    const val INPUT_PLACEHOLDER_ALPHA = .5f

    /** 阴影模糊的环近似层数，与后端 `design_compile.SHADOW_RINGS` 一致。 */
    const val SHADOW_RINGS = 10

    /** shadow 未给颜色时的回落值，与后端 `DEFAULT_SHADOW_ALPHA` 一致。 */
    val DEFAULT_SHADOW: Color = Color.Black.copy(alpha = .25f)

    /** 会裁掉溢出子节点的类型，对齐后端编译层 `CLIP_TYPES`。 */
    val CLIP_TYPES =
        setOf("text", "button", "pill", "input", "nav", "badge", "chip", "avatar")

    /** 文字恒居中、不跟随 `align` 的类型，对齐后端 `CENTER_TEXT_TYPES`。 */
    val CENTER_TEXT_TYPES = setOf("button", "pill", "nav", "badge", "chip", "avatar")

    /**
     * 发光/投影的固定模糊环 `(半径倍率, alpha 系数)`，与后端 `design_compile.FX_RINGS` 逐项一致。
     *
     * 三端都没有高斯模糊：统一用「字形轮廓外扩一圈」近似，外扩距离 = blur × 倍率，
     * 这样三端只差轮廓算法，不差模糊算法。
     */
    val FX_RINGS: List<Pair<Float, Float>> =
        listOf(.35f to .55f, .7f to .35f, 1f to .18f)

    private val TOKEN_RE =
        Regex("""#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\)""")
    private val HEX_RE = Regex("""^#([0-9a-fA-F]{3,8})$""")
    private val RGB_RE = Regex("""^rgba?\(([^)]*)\)$""", RegexOption.IGNORE_CASE)
    private val HSL_RE = Regex("""^hsla?\(([^)]*)\)$""", RegexOption.IGNORE_CASE)
    private val ARG_SPLIT_RE = Regex("""[,\s/]+""")

    /**
     * CSS 标准具名色（含 transparent），与后端 `design_tokens.NAMED_COLORS` 完全一致。
     * 用「名字=rrggbb」紧凑串保存，避免 150 行 map 字面量出现抄写错误。
     */
    private const val NAMED_SOURCE = """
        aliceblue=f0f8ff,antiquewhite=faebd7,aqua=00ffff,aquamarine=7fffd4,azure=f0ffff,beige=f5f5dc,
        bisque=ffe4c4,black=000000,blanchedalmond=ffebcd,blue=0000ff,blueviolet=8a2be2,brown=a52a2a,
        burlywood=deb887,cadetblue=5f9ea0,chartreuse=7fff00,chocolate=d2691e,coral=ff7f50,
        cornflowerblue=6495ed,cornsilk=fff8dc,crimson=dc143c,cyan=00ffff,darkblue=00008b,
        darkcyan=008b8b,darkgoldenrod=b8860b,darkgray=a9a9a9,darkgreen=006400,darkgrey=a9a9a9,
        darkkhaki=bdb76b,darkmagenta=8b008b,darkolivegreen=556b2f,darkorange=ff8c00,
        darkorchid=9932cc,darkred=8b0000,darksalmon=e9967a,darkseagreen=8fbc8f,
        darkslateblue=483d8b,darkslategray=2f4f4f,darkslategrey=2f4f4f,darkturquoise=00ced1,
        darkviolet=9400d3,deeppink=ff1493,deepskyblue=00bfff,dimgray=696969,dimgrey=696969,
        dodgerblue=1e90ff,firebrick=b22222,floralwhite=fffaf0,forestgreen=228b22,fuchsia=ff00ff,
        gainsboro=dcdcdc,ghostwhite=f8f8ff,gold=ffd700,goldenrod=daa520,gray=808080,green=008000,
        greenyellow=adff2f,grey=808080,honeydew=f0fff0,hotpink=ff69b4,indianred=cd5c5c,
        indigo=4b0082,ivory=fffff0,khaki=f0e68c,lavender=e6e6fa,lavenderblush=fff0f5,
        lawngreen=7cfc00,lemonchiffon=fffacd,lightblue=add8e6,lightcoral=f08080,lightcyan=e0ffff,
        lightgoldenrodyellow=fafad2,lightgray=d3d3d3,lightgreen=90ee90,lightgrey=d3d3d3,
        lightpink=ffb6c1,lightsalmon=ffa07a,lightseagreen=20b2aa,lightskyblue=87cefa,
        lightslategray=778899,lightslategrey=778899,lightsteelblue=b0c4de,lightyellow=ffffe0,
        lime=00ff00,limegreen=32cd32,linen=faf0e6,magenta=ff00ff,maroon=800000,
        mediumaquamarine=66cdaa,mediumblue=0000cd,mediumorchid=ba55d3,mediumpurple=9370db,
        mediumseagreen=3cb371,mediumslateblue=7b68ee,mediumspringgreen=00fa9a,
        mediumturquoise=48d1cc,mediumvioletred=c71585,midnightblue=191970,mintcream=f5fffa,
        mistyrose=ffe4e1,moccasin=ffe4b5,navajowhite=ffdead,navy=000080,oldlace=fdf5e6,
        olive=808000,olivedrab=6b8e23,orange=ffa500,orangered=ff4500,orchid=da70d6,
        palegoldenrod=eee8aa,palegreen=98fb98,paleturquoise=afeeee,palevioletred=db7093,
        papayawhip=ffefd5,peachpuff=ffdab9,peru=cd853f,pink=ffc0cb,plum=dda0dd,
        powderblue=b0e0e6,purple=800080,rebeccapurple=663399,red=ff0000,rosybrown=bc8f8f,
        royalblue=4169e1,saddlebrown=8b4513,salmon=fa8072,sandybrown=f4a460,seagreen=2e8b57,
        seashell=fff5ee,sienna=a0522d,silver=c0c0c0,skyblue=87ceeb,slateblue=6a5acd,
        slategray=708090,slategrey=708090,snow=fffafa,springgreen=00ff7f,steelblue=4682b4,
        tan=d2b48c,teal=008080,thistle=d8bfd8,tomato=ff6347,turquoise=40e0d0,violet=ee82ee,
        wheat=f5deb3,white=ffffff,whitesmoke=f5f5f5,yellow=ffff00,yellowgreen=9acd32,
        transparent=00000000
    """

    private val NAMED: Map<String, String> by lazy {
        NAMED_SOURCE.filterNot { it.isWhitespace() }
            .split(',')
            .filter { it.isNotEmpty() }
            .associate { item ->
                val index = item.indexOf('=')
                item.substring(0, index) to "#" + item.substring(index + 1)
            }
    }

    /** 节点类型；缺省一律按 text 处理（与后端 `normalize_type` 的 trim + lower 一致）。 */
    fun typeOf(node: DesignNode): String =
        node.type.trim().lowercase().ifEmpty { "text" }

    fun defaultRadius(type: String, w: Float, h: Float): Float = when (type) {
        "button", "pill", "input", "slider", "switch", "progress", "badge", "chip" -> h / 2f
        "avatar", "circle" -> w / 2f
        "card", "nav" -> 18f
        else -> 0f
    }

    /** 四角圆角，单位与节点一致；对齐后端 `radius_of`。 */
    data class Corners(val tl: Float, val tr: Float, val br: Float, val bl: Float) {
        val uniform: Boolean get() = tl == tr && tr == br && br == bl
    }

    /**
     * 四角圆角：显式值优先，负值按 0，上限 `min(w, h) / 2`。
     * 显式值里只给了部分角时，未给的角保持默认圆角（与后端 `radius_of` 一致）。
     */
    fun cornersOf(node: DesignNode): Corners {
        val limit = (min(node.w, node.h) / 2f).coerceAtLeast(0f)
        val fallback = defaultRadius(typeOf(node), node.w, node.h).coerceIn(0f, limit)
        val raw = node.radius
        if (raw == null) return Corners(fallback, fallback, fallback, fallback)
        fun corner(value: Float?) = (value ?: fallback).coerceIn(0f, limit)
        return Corners(corner(raw.tl), corner(raw.tr), corner(raw.br), corner(raw.bl))
    }

    /** 编辑器只有单值滑杆，取左上角作为代表值。 */
    fun radiusOf(node: DesignNode): Float = cornersOf(node).tl

    fun defaultFill(type: String): Color = when (type) {
        "button", "switch", "slider", "progress", "badge" -> ACCENT
        "pill", "decor", "chip", "avatar" -> ACCENT.copy(alpha = .12f)
        "card", "nav" -> Color.White
        "input" -> INPUT_FILL
        else -> INK.copy(alpha = .08f)
    }

    /** 按类型推默认文字色，对齐后端 `default_text_color`。 */
    fun defaultTextColor(type: String): Color = when (type) {
        "badge" -> Color.White
        "chip", "avatar" -> ACCENT
        else -> INK
    }

    fun hasGradient(raw: String?): Boolean =
        raw != null && raw.contains("gradient", ignoreCase = true)

    /** `none` / `transparent` 作为填充表示「不画底色」（与后端 `compile_fill` 一致）。 */
    fun isNoneFill(raw: String?): Boolean =
        raw?.trim()?.lowercase() in setOf("none", "transparent")

    /**
     * 旧设计稿把文字色存在 `fill`（没有 `color`）。对齐 Web `paint.js`：
     * 仅 text 节点、`color` 为空、`fill` 是纯色时成立；渐变仍按底色处理。
     */
    fun legacyTextInk(node: DesignNode): Color? {
        if (typeOf(node) != "text") return null
        if (!node.color.isNullOrBlank()) return null
        if (isNoneFill(node.fill)) return null
        return colorOrNull(node.fill)
    }

    fun tokens(value: String): List<String> =
        TOKEN_RE.findAll(value).map { it.value }.toList()

    /**
     * 等价于后端 `_single_color`：支持 #rgb / #rgba / #rrggbb / #aarrggbb（ARGB）、
     * `rgb()` / `rgba()` / `hsl()` / `hsla()`、CSS 具名色。
     */
    fun color(raw: String?, fallback: Color): Color {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return fallback
        parse(value)?.let { return it }
        if (hasGradient(value)) {
            val stops = tokens(value)
            if (stops.isNotEmpty()) return color(stops.last(), fallback)
        }
        return fallback
    }

    /**
     * 单色近似：填充可能是渐变，但描边 / 滑杆底座 / 指示条只能吃单色，
     * 此时取最后一个色标（与后端 `_single_color` 一致）。
     */
    fun singleColor(raw: String?, fallback: Color): Color = color(raw, fallback)

    fun brush(raw: String?, fallback: Color): Brush {
        gradientOf(raw)?.let { return DesignGradientBrush(it) }
        return SolidColor(color(raw, fallback))
    }

    // ------------------------------------------------------------ 渐变几何

    data class GradientStop(val color: Color, val offset: Float)

    /**
     * CSS 渐变解析结果。[angle] 与 CSS 同义（0deg 向上、90deg 向右、顺时针）；
     * [cx] / [cy] 是径向渐变的圆心比例。
     */
    data class Gradient(
        val kind: String,
        val angle: Float,
        val cx: Float,
        val cy: Float,
        val stops: List<GradientStop>,
    )

    private val GRADIENT_RE =
        Regex("""^(linear|radial)-gradient\((.*)\)$""", RegexOption.IGNORE_CASE)
    private val ANGLE_RE = Regex("""^(-?\d+(?:\.\d+)?)(deg|turn|rad)?$""", RegexOption.IGNORE_CASE)
    private val OFFSET_RE = Regex("""^(-?\d+(?:\.\d+)?)%$""")
    private val DIRECTION_ANGLES = mapOf(
        "to top" to 0f,
        "to top right" to 45f,
        "to right" to 90f,
        "to bottom right" to 135f,
        "to bottom" to 180f,
        "to bottom left" to 225f,
        "to left" to 270f,
        "to top left" to 315f,
    )
    private val POSITION_KEYWORDS =
        mapOf("left" to 0f, "top" to 0f, "center" to .5f, "right" to 1f, "bottom" to 1f)

    /** 解析 `linear-gradient(...)` / `radial-gradient(circle at x y, ...)`；失败返回 null。 */
    fun gradientOf(raw: String?): Gradient? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = GRADIENT_RE.matchEntire(value) ?: return null
        val kind = match.groupValues[1].lowercase()
        var segments = splitTopLevel(match.groupValues[2])
        if (segments.isEmpty()) return null

        var angle = 180f
        var cx = .5f
        var cy = .5f
        if (kind == "linear") {
            val head = segments[0].trim().lowercase()
            val resolved = angleValue(head)
            if (resolved != null) {
                angle = resolved
                segments = segments.drop(1)
            } else if (DIRECTION_ANGLES.containsKey(head)) {
                angle = DIRECTION_ANGLES.getValue(head)
                segments = segments.drop(1)
            }
        } else if (segments.isNotEmpty()) {
            val head = segments[0].trim().lowercase()
            if (head.contains("at") ||
                head.startsWith("circle") ||
                head.startsWith("ellipse") ||
                head.startsWith("closest") ||
                head.startsWith("farthest")
            ) {
                radialCenter(head)?.let { (x, y) ->
                    cx = x
                    cy = y
                }
                segments = segments.drop(1)
            }
        }

        val rawStops = segments.mapNotNull { segment ->
            val tokens = segment.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val stopColor = tokens.firstOrNull()?.let { colorOrNull(it) } ?: return@mapNotNull null
            val offset = tokens.drop(1).firstNotNullOfOrNull { token ->
                OFFSET_RE.matchEntire(token)?.groupValues?.get(1)?.toFloatOrNull()
                    ?.div(100f)?.coerceIn(0f, 1f)
            }
            stopColor to offset
        }
        if (rawStops.isEmpty()) return null
        return Gradient(kind, angle, cx, cy, resolveStops(rawStops))
    }

    /** 未标位置的色标按 CSS 规则在相邻已标位置之间均分；与后端 `parse_gradient` 同算法。 */
    private fun resolveStops(raw: List<Pair<Color, Float?>>): List<GradientStop> {
        val offsets = raw.map { it.second }.toMutableList()
        val firstFixed = offsets.indexOfFirst { it != null }
        if (firstFixed < 0) {
            val count = raw.size
            return raw.mapIndexed { index, item ->
                GradientStop(item.first, if (count > 1) index.toFloat() / (count - 1) else 0f)
            }
        }
        for (index in 0 until firstFixed) offsets[index] = 0f
        val lastFixed = offsets.indexOfLast { it != null }
        for (index in lastFixed + 1 until offsets.size) offsets[index] = 1f
        var index = firstFixed
        while (index <= lastFixed) {
            if (offsets[index] == null) {
                var next = index
                while (next <= lastFixed && offsets[next] == null) next++
                val start = offsets[index - 1] ?: 0f
                val end = offsets[next] ?: 0f
                val step = (end - start) / (next - index + 1)
                for (fillIndex in index until next) {
                    offsets[fillIndex] = start + step * (fillIndex - index + 1)
                }
                index = next
            }
            index++
        }
        return raw.mapIndexed { stopIndex, item ->
            GradientStop(item.first, (offsets[stopIndex] ?: 0f).coerceIn(0f, 1f))
        }
    }

    private fun splitTopLevel(value: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        value.forEach { char ->
            when (char) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (char == ',' && depth == 0) {
                if (current.isNotBlank()) parts += current.toString().trim()
                current.setLength(0)
            } else {
                current.append(char)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }

    private fun angleValue(token: String): Float? {
        val match = ANGLE_RE.matchEntire(token) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val unit = match.groupValues[2].ifEmpty { "deg" }.lowercase()
        val degrees = when (unit) {
            "turn" -> value * 360f
            "rad" -> (value * 180.0 / PI).toFloat()
            else -> value
        }
        return ((degrees % 360f) + 360f) % 360f
    }

    private fun positionValue(token: String): Float? {
        val value = token.trim().lowercase()
        POSITION_KEYWORDS[value]?.let { return it }
        if (value.endsWith("%")) {
            return value.dropLast(1).toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
        }
        return null
    }

    /** `circle at 30% 70%` → (0.3, 0.7)；单值 top/bottom 定 y，其余定 x。 */
    private fun radialCenter(head: String): Pair<Float, Float>? {
        val parts = head.split(Regex("""\bat\b"""), 2)
        if (parts.size < 2) return null
        val tokens = parts[1].trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) {
            val token = tokens[0].lowercase()
            val value = positionValue(token) ?: return null
            return if (token == "top" || token == "bottom") .5f to value else value to .5f
        }
        val x = positionValue(tokens[0]) ?: return null
        val y = positionValue(tokens[1]) ?: return null
        return x to y
    }

    /**
     * 按绘制尺寸生成着色器的渐变画笔：节点填充与文字渐变共用，
     * 因此文字渐变也按真实布局尺寸走 CSS 几何，而不是固定竖向。
     */
    private class DesignGradientBrush(
        private val gradient: Gradient,
    ) : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val stops = if (gradient.stops.size == 1) {
                listOf(gradient.stops[0], gradient.stops[0])
            } else {
                gradient.stops
            }
            // android.graphics 的渐变要求色标位置非降序，CSS 允许乱序书写，这里排一下。
            val ordered = stops.sortedBy { it.offset }
            val colors = ordered.map { it.color.toArgb() }.toIntArray()
            val positions = ordered.map { it.offset }.toFloatArray()
            if (gradient.kind == "radial") {
                val cx = size.width * gradient.cx
                val cy = size.height * gradient.cy
                val radius = listOf(
                    sqrt(cx * cx + cy * cy),
                    sqrt((size.width - cx) * (size.width - cx) + cy * cy),
                    sqrt(cx * cx + (size.height - cy) * (size.height - cy)),
                    sqrt(
                        (size.width - cx) * (size.width - cx) +
                            (size.height - cy) * (size.height - cy),
                    ),
                ).maxOrNull() ?: 0f
                return android.graphics.RadialGradient(
                    cx,
                    cy,
                    radius.coerceAtLeast(.01f),
                    colors,
                    positions,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            // CSS 渐变线过中心、按角度方向；长度 = |w·sin| + |h·cos|。
            val radians = gradient.angle * PI.toFloat() / 180f
            val dx = sin(radians)
            val dy = -cos(radians)
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            return android.graphics.LinearGradient(
                cx - dx * half,
                cy - dy * half,
                cx + dx * half,
                cy + dy * half,
                colors,
                positions,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
    }

    /** 解析后的文字描边：[width] 已是居中描边宽度（outside 时翻倍），可直接喂给 `Stroke`。 */
    data class TextStroke(val width: Float, val color: Color)

    /** 一个发光/投影环；[spread] 为轮廓外扩距离，0 表示同形偏移副本（对应 blur=0 的 CSS 阴影）。 */
    data class TextRing(val dx: Float, val dy: Float, val spread: Float, val color: Color)

    /**
     * 文字渐变画笔，只看 `textFill`（与 IR `fill` 同源）。
     * 几何按文字实际布局尺寸生成，角度/径向与网页 `background-clip:text` 一致。
     */
    fun textBrushOf(raw: String?): Brush? {
        val gradient = gradientOf(raw) ?: return null
        return DesignGradientBrush(gradient)
    }

    fun textStrokeOf(node: DesignNode): TextStroke? {
        val raw = node.textStroke ?: return null
        // 与后端 `_compile_text_stroke` 一致：颜色解析失败整段丢弃。
        val strokeColor = colorOrNull(raw.color) ?: return null
        val width = (raw.width ?: 1f).coerceAtLeast(0f)
        if (width <= 0f) return null
        // 网页 `-webkit-text-stroke` 是居中描边，填充盖住内侧一半；outside 时宽度翻倍、
        // inside 时保持原值，三端字形外缘才落在同一位置。
        val centered = if (raw.align?.trim()?.lowercase() == "inside") width else width * 2f
        return TextStroke(centered, strokeColor)
    }

    /**
     * 发光/投影环，返回**绘制顺序（底层 → 顶层）**：先投影环，再发光环。
     *
     * 网页 `text-shadow` 列表里「先列在上层」，后端又是先写发光再写投影，
     * 所以投影整组在下、发光整组在上；每组内部按半径从大到小画。
     */
    fun textRingsOf(node: DesignNode): List<TextRing> =
        ringsOf(node.textShadow, glow = false) + ringsOf(node.textGlow, glow = true)

    private fun ringsOf(effect: DesignTextEffect?, glow: Boolean): List<TextRing> {
        if (effect == null) return emptyList()
        val base = colorOrNull(effect.color) ?: return emptyList()
        val intensity = (effect.intensity ?: 1f).coerceIn(0f, 1f)
        // 与后端 `_compile_fx` 一致：blur 缺省时回退到 radius（设计稿里发光习惯写 radius）。
        val blur = (effect.blur ?: effect.radius ?: 0f).coerceAtLeast(0f)
        // 发光不认偏移，投影才用 dx/dy（对齐 `_text_shadow_css`）。
        val dx = if (glow) 0f else (effect.dx ?: 0f)
        val dy = if (glow) 0f else (effect.dy ?: 0f)
        return FX_RINGS.asReversed().mapNotNull { (radius, ringAlpha) ->
            val alpha = (base.alpha * intensity * ringAlpha).coerceIn(0f, 1f)
            if (alpha <= .004f) null else TextRing(dx, dy, blur * radius, base.copy(alpha = alpha))
        }
    }

    // ------------------------------------------------------------ 节点描边 / 阴影

    /**
     * 节点描边；[sides] 为 null 表示四边都画，[dash] 非空即虚线。
     * 三端统一按 `align=inside` 落笔（网页 border 恒在盒内）。
     */
    data class StrokeSpec(
        val color: Color,
        val width: Float,
        val dash: Boolean,
        val sides: Set<String>?,
    ) {
        fun has(side: String): Boolean = sides == null || side in sides
    }

    private val STROKE_SIDES = setOf("top", "right", "bottom", "left")

    fun strokeOf(node: DesignNode): StrokeSpec? {
        val raw = node.stroke ?: return null
        val strokeColor = colorOrNull(raw.color) ?: return null
        val width = raw.width.coerceAtLeast(0f)
        if (width <= 0f) return null
        val sides = raw.sides.map { it.trim().lowercase() }.filter { it in STROKE_SIDES }.toSet()
        return StrokeSpec(strokeColor, width, raw.dash.isNotEmpty(), sides.ifEmpty { null })
    }

    /** 一个阴影环；[spread] 是向外扩张（inset 时向内收）的距离。 */
    data class ShadowRing(
        val dx: Float,
        val dy: Float,
        val spread: Float,
        val color: Color,
        val inset: Boolean,
    )

    /** 外阴影环（大 spread 在下、小 spread 在上）；card 无显式 shadow 时补默认投影。 */
    fun shadowRings(node: DesignNode): List<ShadowRing> {
        if (node.shadow == null && typeOf(node) == "card") {
            return listOf(
                ShadowRing(
                    dx = 0f,
                    dy = CARD_SHADOW_OFFSET,
                    spread = 0f,
                    color = Color.Black.copy(alpha = CARD_SHADOW_ALPHA),
                    inset = false,
                ),
            )
        }
        return shadowRingLayers(node.shadow, forceInset = false).filterNot { it.inset }
    }

    /** 内阴影环：`insetShadow` 恒在盒内绘制，`shadow` 里 inset=true 的层也算内阴影。 */
    fun insetShadowRings(node: DesignNode): List<ShadowRing> =
        (shadowRingLayers(node.shadow, forceInset = false) +
            shadowRingLayers(node.insetShadow, forceInset = true)).filter { it.inset }

    /**
     * blur>0 时按 N=10 环近似：spread_i = blur × i / N，
     * alpha_i = color.alpha × 3/N × (1 − i/N)²；blur=0 退化为单段。
     */
    private fun shadowRingLayers(shadow: DesignShadow?, forceInset: Boolean): List<ShadowRing> {
        if (shadow == null) return emptyList()
        val base = color(shadow.color, DEFAULT_SHADOW)
        if (base.alpha <= .004f) return emptyList()
        val dx = shadow.dx
        val dy = shadow.dy
        val blur = shadow.blur.coerceAtLeast(0f)
        val inset = forceInset || shadow.inset
        if (blur <= .0001f) return listOf(ShadowRing(dx, dy, 0f, base, inset))
        val rings = ArrayList<ShadowRing>(SHADOW_RINGS)
        for (index in SHADOW_RINGS downTo 1) {
            val ratio = index.toFloat() / SHADOW_RINGS
            val alpha = base.alpha * 3f / SHADOW_RINGS * (1f - ratio) * (1f - ratio)
            if (alpha <= .004f) continue
            rings += ShadowRing(dx, dy, blur * ratio, base.copy(alpha = alpha), inset)
        }
        return rings
    }

    /** 可解析返回颜色，否则 null；对应后端 `ir_color(x)` 不带默认值时「整段丢弃」的语义。 */
    fun colorOrNull(raw: String?): Color? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return parse(value)
    }

    fun weightOf(node: DesignNode): FontWeight =
        node.fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) } ?: FontWeight.Normal

    /**
     * 字族别名归一，与后端 `design_compile.font_family` 的别名表逐条一致。
     * 空格和下划线先归一成连字符，所以 `Noto Sans SC` / `noto_sans_sc` 都能落进枚举。
     */
    fun normalizeFamily(raw: String?): String {
        val value = (raw ?: "").trim().lowercase().replace(Regex("[\\s_]+"), "-")
        return FAMILY_ALIASES[value] ?: "system"
    }

    private val FAMILY_ALIASES = mapOf(
        "system" to "system",
        "" to "system",
        "sans" to "system",
        "sans-serif" to "system",
        "noto-sans" to "noto-sans-sc",
        "noto-sans-sc" to "noto-sans-sc",
        "source-han-sans" to "noto-sans-sc",
        "serif" to "noto-serif-sc",
        "noto-serif" to "noto-serif-sc",
        "noto-serif-sc" to "noto-serif-sc",
        "source-han-serif" to "noto-serif-sc",
        "inter" to "inter",
        "mono" to "mono",
        "jetbrains-mono" to "mono",
        "monospace" to "mono",
    )

    /**
     * 枚举 → Android 字族。Android 的 `sans-serif` / `serif` / `monospace` 分别落到
     * Noto Sans CJK / Noto Serif CJK / Droid Sans Mono，正是网页回退链在移动端的终点。
     * `inter` 没有内置，按无衬线回退（与网页 `"Inter",...,sans-serif` 的最终落点相同）。
     */
    fun fontFamilyOf(node: DesignNode): FontFamily = when (normalizeFamily(node.fontFamily)) {
        "noto-serif-sc" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "noto-sans-sc", "inter" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }

    /** 行数上限：1..64，缺省 3（与后端 `text_style` 的 clamp 一致）。 */
    fun maxLinesOf(node: DesignNode): Int =
        (node.maxLines ?: MAX_TEXT_LINES).coerceIn(1, TEXT_LINES_LIMIT)

    fun autoFitOf(node: DesignNode): Boolean = node.autoFit == true

    private fun parse(value: String): Color? {
        var raw = value.trim().lowercase()
        if (raw.isEmpty()) return null
        NAMED[raw]?.let { raw = it }

        HEX_RE.matchEntire(raw)?.let { match ->
            var hex = match.groupValues[1]
            if (hex.length !in setOf(3, 4, 6, 8)) return null
            if (hex.length == 3 || hex.length == 4) {
                hex = hex.map { "$it$it" }.joinToString("")
            }
            return runCatching {
                if (hex.length == 8) {
                    // #aarrggbb：Android ARGB 顺序，正好是 Compose Color(Long) 的语义
                    Color(hex.toLong(16))
                } else {
                    Color(0xFF000000L or hex.toLong(16))
                }
            }.getOrNull()
        }

        RGB_RE.matchEntire(raw)?.let { match ->
            val parts = splitArgs(match.groupValues[1])
            if (parts.size < 3) return null
            val r = channel(parts[0], 255f) ?: return null
            val g = channel(parts[1], 255f) ?: return null
            val b = channel(parts[2], 255f) ?: return null
            val a = if (parts.size >= 4) alphaChannel(parts[3]) ?: return null else 1f
            // Compose 的 Float 重载要求 0..1，这里显式转成 0..255 的 ARGB 整数通道。
            return Color(
                r.roundToInt().coerceIn(0, 255),
                g.roundToInt().coerceIn(0, 255),
                b.roundToInt().coerceIn(0, 255),
                (a * 255f).roundToInt().coerceIn(0, 255),
            )
        }

        HSL_RE.matchEntire(raw)?.let { match ->
            val parts = splitArgs(match.groupValues[1])
            if (parts.size < 3) return null
            val hue = parts[0].removeSuffix("deg").toFloatOrNull() ?: return null
            val saturation = channel(parts[1], 1f) ?: return null
            val lightness = channel(parts[2], 1f) ?: return null
            val a = if (parts.size >= 4) alphaChannel(parts[3]) ?: return null else 1f
            val rgb = hslToRgb(hue, saturation, lightness)
            return Color(
                rgb[0],
                rgb[1],
                rgb[2],
                (a * 255f).roundToInt().coerceIn(0, 255),
            )
        }

        return null
    }

    private fun splitArgs(body: String): List<String> =
        body.trim().split(ARG_SPLIT_RE).filter { it.isNotEmpty() }

    /** 颜色通道：`50%` 按比例换算，其余按 0..scale 截断（对齐后端 `_parse_channel`）。 */
    private fun channel(token: String, scale: Float): Float? {
        val value = token.trim()
        return if (value.endsWith("%")) {
            value.dropLast(1).toFloatOrNull()?.div(100f)?.times(scale)?.coerceIn(0f, scale)
        } else {
            value.toFloatOrNull()?.coerceIn(0f, scale)
        }
    }

    /** 透明度：`50%` 视作 0.5，其余按 0..1 截断。 */
    private fun alphaChannel(token: String): Float? {
        val value = token.trim()
        return if (value.endsWith("%")) {
            value.dropLast(1).toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
        } else {
            value.toFloatOrNull()?.coerceIn(0f, 1f)
        }
    }

    /** HSL → RGB，与后端 `design_tokens._hsl_to_rgb` 同算法。 */
    private fun hslToRgb(hue: Float, saturation: Float, lightness: Float): IntArray {
        val h = ((hue % 360f) + 360f) % 360f / 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        if (s == 0f) {
            val value = (l * 255f).roundToInt().coerceIn(0, 255)
            return intArrayOf(value, value, value)
        }

        fun channel(p: Float, q: Float, t0: Float): Float {
            var t = t0
            while (t < 0f) t += 1f
            while (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }

        val q = if (l < .5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return intArrayOf(
            (channel(p, q, h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255),
            (channel(p, q, h) * 255f).roundToInt().coerceIn(0, 255),
            (channel(p, q, h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255),
        )
    }
}
