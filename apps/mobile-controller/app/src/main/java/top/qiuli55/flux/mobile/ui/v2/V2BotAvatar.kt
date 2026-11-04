package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bot 头像：形状 × 颜色确定性生成，不支持上传图片。
 *
 * 契约来源是后端 `flux/core/group/avatar.py`：
 * AVATAR_SHAPES / AVATAR_COLORS 的顺序与取值必须与之保持一致，
 * 换形状或颜色只需两边同时改，这里不负责校验（非法值静默回退默认）。
 */
val V2AvatarShapes: List<String> = listOf(
    "circle",
    "squircle",
    "hexagon",
    "triangle",
    "diamond",
    "star",
    "flower",
    "burst",
    "shield",
    "drop",
    "cloud",
    "gear",
    "egg",
    "blob",
)

val V2AvatarColors: List<String> = listOf(
    "violet",
    "indigo",
    "sky",
    "teal",
    "emerald",
    "lime",
    "amber",
    "orange",
    "rose",
    "fuchsia",
    "slate",
    "cyan",
)

const val V2DefaultAvatarShape = "circle"
const val V2DefaultAvatarColor = "violet"

/** 深色底上都能看清的实心色。 */
private val AVATAR_COLOR_HEX: Map<String, Long> = mapOf(
    "violet" to 0xFF8B6CF6,
    "indigo" to 0xFF6D7EF8,
    "sky" to 0xFF4AA8F0,
    "teal" to 0xFF21B3A6,
    "emerald" to 0xFF2ECC8F,
    "lime" to 0xFF9AD84A,
    "amber" to 0xFFE8B25E,
    "orange" to 0xFFF08A4B,
    "rose" to 0xFFF0708F,
    "fuchsia" to 0xFFD464E8,
    "slate" to 0xFF8794A3,
    "cyan" to 0xFF43D6D6,
)

/** 每种形状的眼睛基线（避免在多边形尖角处显得贴边）。 */
private val FACE_Y: Map<String, Float> = mapOf(
    "triangle" to 38f,
    "star" to 34f,
    "shield" to 34f,
    "drop" to 30f,
    "cloud" to 35f,
    "gear" to 33f,
)

/** 颜色的 Compose Color（选择器里的实心色块用）。 */
fun v2AvatarColor(color: String?): Color =
    Color(AVATAR_COLOR_HEX[color] ?: AVATAR_COLOR_HEX.getValue(V2DefaultAvatarColor))

fun v2NormalizeShape(shape: String?): String =
    if (shape != null && shape in V2AvatarShapes) shape else V2DefaultAvatarShape

fun v2NormalizeColor(color: String?): String =
    if (color != null && color in V2AvatarColors) color else V2DefaultAvatarColor

/** 统一的 bot 头像。未知形状/颜色回退到 circle / violet。 */
@Composable
fun BotAvatar(
    shape: String?,
    color: String?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val resolvedShape = v2NormalizeShape(shape)
    val fill = v2AvatarColor(v2NormalizeColor(color))
    val faceY = FACE_Y[resolvedShape] ?: 32f
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 64f
        scale(unit, unit, pivot = Offset.Zero) {
            drawShapeBody(resolvedShape, fill)
            drawFace(faceY)
        }
    }
}

/** 颜色块：创建 bot 时的取色圆点；选中时外侧套一圈描边。 */
@Composable
fun AvatarColorDot(color: String, selected: Boolean, size: Dp = 26.dp) {
    val dot = v2AvatarColor(color)
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(dot)
            .then(
                if (selected) {
                    Modifier.border(2.dp, V2Colors.ink(.85f), CircleShape)
                } else {
                    Modifier
                },
            ),
    )
}

private fun DrawScope.drawShapeBody(shape: String, fill: Color) {
    val stroke = Stroke(width = 4f, join = StrokeJoin.Round)
    when (shape) {
        "squircle" -> {
            drawRoundRect(
                color = fill,
                topLeft = Offset(6f, 6f),
                size = Size(52f, 52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
            )
            drawRoundRect(
                color = fill,
                topLeft = Offset(6f, 6f),
                size = Size(52f, 52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                style = stroke,
            )
        }
        "hexagon" -> fillPath(hexagonPath(), fill, stroke)
        "triangle" -> fillPath(trianglePath(), fill, stroke)
        "diamond" -> fillPath(diamondPath(), fill, stroke)
        "star" -> fillPath(radialPath(32f, 32f, 26f, 11f, 5), fill, stroke)
        "burst" -> fillPath(radialPath(32f, 32f, 29f, 20f, 12), fill, stroke)
        "shield" -> fillPath(shieldPath(), fill, stroke)
        "drop" -> fillPath(dropPath(), fill, stroke)
        "egg" -> fillPath(eggPath(), fill, stroke)
        "blob" -> fillPath(blobPath(), fill, stroke)
        "flower" -> {
            for (degree in 0 until 360 step 60) {
                val angle = degree * PI / 180
                val cx = 32f + cos(angle).toFloat() * 11f
                val cy = 32f + sin(angle).toFloat() * 11f
                drawCircle(fill, 13f, Offset(cx, cy))
                drawCircle(fill, 13f, Offset(cx, cy), style = stroke)
            }
            drawCircle(fill, 15f, Offset(32f, 32f))
            drawCircle(fill, 15f, Offset(32f, 32f), style = stroke)
        }
        "cloud" -> {
            listOf(
                Triple(23f, 36f, 11f),
                Triple(32f, 29f, 13f),
                Triple(42f, 36f, 10f),
            ).forEach { (cx, cy, r) ->
                drawCircle(fill, r, Offset(cx, cy))
                drawCircle(fill, r, Offset(cx, cy), style = stroke)
            }
            drawRoundRect(
                color = fill,
                topLeft = Offset(18f, 36f),
                size = Size(28f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
            drawRoundRect(
                color = fill,
                topLeft = Offset(18f, 36f),
                size = Size(28f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                style = stroke,
            )
        }
        "gear" -> {
            for (degree in 0 until 360 step 45) {
                rotate(degree.toFloat(), pivot = Offset(32f, 32f)) {
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(28.5f, 2f),
                        size = Size(7f, 13f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                    )
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(28.5f, 2f),
                        size = Size(7f, 13f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
                        style = stroke,
                    )
                }
            }
            drawCircle(fill, 19f, Offset(32f, 32f))
            drawCircle(fill, 19f, Offset(32f, 32f), style = stroke)
        }
        else -> {
            drawCircle(fill, 26f, Offset(32f, 32f))
            drawCircle(fill, 26f, Offset(32f, 32f), style = stroke)
        }
    }
}

private fun DrawScope.fillPath(path: Path, fill: Color, stroke: Stroke) {
    drawPath(path, fill)
    drawPath(path, fill, style = stroke)
}

private fun DrawScope.drawFace(faceY: Float) {
    val eye = Color(0x0A0C0E).copy(alpha = .82f)
    val gleam = Color.White.copy(alpha = .5f)
    drawCircle(eye, 3.4f, Offset(24.5f, faceY))
    drawCircle(eye, 3.4f, Offset(39.5f, faceY))
    drawCircle(gleam, 1.1f, Offset(25.6f, faceY - 1.2f))
    drawCircle(gleam, 1.1f, Offset(40.6f, faceY - 1.2f))
}

private fun hexagonPath(): Path = Path().apply {
    moveTo(32f, 5f)
    lineTo(53.7f, 17.5f)
    lineTo(53.7f, 42.5f)
    lineTo(32f, 55f)
    lineTo(10.3f, 42.5f)
    lineTo(10.3f, 17.5f)
    close()
}

private fun trianglePath(): Path = Path().apply {
    moveTo(32f, 8f)
    lineTo(57f, 51f)
    lineTo(7f, 51f)
    close()
}

private fun diamondPath(): Path = Path().apply {
    moveTo(32f, 6f)
    lineTo(58f, 32f)
    lineTo(32f, 58f)
    lineTo(6f, 32f)
    close()
}

private fun shieldPath(): Path = Path().apply {
    moveTo(32f, 5f)
    lineTo(55f, 13f)
    lineTo(55f, 29f)
    cubicTo(55f, 43f, 45f, 52f, 32f, 58f)
    cubicTo(19f, 52f, 9f, 43f, 9f, 29f)
    lineTo(9f, 13f)
    close()
}

private fun dropPath(): Path = Path().apply {
    moveTo(32f, 5f)
    cubicTo(45f, 19f, 52f, 28f, 52f, 37.5f)
    arcTo(
        rect = Rect(Offset(12f, 17.5f), Size(40f, 40f)),
        startAngleDegrees = 0f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false,
    )
    cubicTo(12f, 28f, 19f, 19f, 32f, 5f)
    close()
}

private fun eggPath(): Path = Path().apply {
    moveTo(32f, 6f)
    cubicTo(42f, 6f, 50f, 20f, 50f, 32f)
    cubicTo(50f, 46f, 42f, 58f, 32f, 58f)
    cubicTo(22f, 58f, 14f, 46f, 14f, 32f)
    cubicTo(14f, 20f, 22f, 6f, 32f, 6f)
    close()
}

private fun blobPath(): Path = Path().apply {
    moveTo(34f, 5f)
    cubicTo(48f, 4f, 60f, 15f, 58f, 29f)
    cubicTo(56f, 43f, 46f, 60f, 31f, 59f)
    cubicTo(16f, 58f, 4f, 46f, 6f, 31f)
    cubicTo(8f, 16f, 20f, 6f, 34f, 5f)
    close()
}

/** 极坐标多边形（星星 / 爆炸形）。 */
private fun radialPath(
    cx: Float,
    cy: Float,
    outer: Float,
    inner: Float,
    spikes: Int,
): Path = Path().apply {
    val rotation = -PI / 2
    for (index in 0 until spikes * 2) {
        val radius = if (index % 2 == 0) outer else inner
        val angle = rotation + index * PI / spikes
        val x = cx + cos(angle).toFloat() * radius
        val y = cy + sin(angle).toFloat() * radius
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
