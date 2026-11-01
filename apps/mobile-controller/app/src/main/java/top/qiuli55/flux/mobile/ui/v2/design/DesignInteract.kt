package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 画布上可选中的手柄。 */
enum class DesignHandle {
    TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left, Rotate;

    val isResize: Boolean get() = this != Rotate

    val isCorner: Boolean
        get() = this == TopLeft || this == TopRight || this == BottomRight || this == BottomLeft
}

data class NodeFrame(val x: Float, val y: Float, val w: Float, val h: Float) {
    val center: Offset get() = Offset(x + w / 2f, y + h / 2f)
    val rect: Rect get() = Rect(x, y, x + w, y + h)
}

/** 吸附结果：需要补偿的位移 + 需要画出来的参考线（设计坐标）。 */
data class SnapResult(
    val dx: Float,
    val dy: Float,
    val vertical: List<Float>,
    val horizontal: List<Float>,
)

object DesignInteract {

    /** 手柄在节点自身（未旋转）坐标系里的位置；绘制时外层已经套了 rotate，不要再旋转一次。 */
    fun rawHandlePositions(node: ResolvedNode, rotateOffset: Float): Map<DesignHandle, Offset> = mapOf(
        DesignHandle.TopLeft to Offset(node.x, node.y),
        DesignHandle.Top to Offset(node.x + node.w / 2f, node.y),
        DesignHandle.TopRight to Offset(node.right, node.y),
        DesignHandle.Right to Offset(node.right, node.y + node.h / 2f),
        DesignHandle.BottomRight to Offset(node.right, node.bottom),
        DesignHandle.Bottom to Offset(node.x + node.w / 2f, node.bottom),
        DesignHandle.BottomLeft to Offset(node.x, node.bottom),
        DesignHandle.Left to Offset(node.x, node.y + node.h / 2f),
        DesignHandle.Rotate to Offset(node.x + node.w / 2f, node.y - rotateOffset),
    )

    /** 8 个缩放手柄 + 1 个旋转手柄的坐标（已按旋转角变换到画板坐标系）。 */
    fun handlePositions(node: ResolvedNode, rotateOffset: Float): Map<DesignHandle, Offset> {
        val raw = rawHandlePositions(node, rotateOffset)
        if (node.rotation == 0f) return raw
        return raw.mapValues { (_, point) -> rotatePoint(point, node.center, node.rotation) }
    }

    /** 命中哪个手柄；[radius] 是设计坐标下的热区半径（调用方按缩放反算，保证 44dp）。 */
    fun hitHandle(node: ResolvedNode, point: Offset, radius: Float, rotateOffset: Float): DesignHandle? {
        var best: DesignHandle? = null
        var bestDistance = Float.MAX_VALUE
        handlePositions(node, rotateOffset).forEach { (handle, center) ->
            val distance = distance(point, center)
            if (distance > radius) return@forEach
            if (best == null || distance < bestDistance) {
                best = handle
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * 拖手柄改框：在节点自己的局部坐标里改边，再把中心按旋转角修正回去，
     * 这样旋转过的节点拖角时也是「对角的点钉住」。
     */
    fun resizeFrame(
        frame: NodeFrame,
        rotation: Float,
        handle: DesignHandle,
        pointer: Offset,
        minSize: Float = 12f,
    ): NodeFrame {
        if (!handle.isResize) return frame
        val center = frame.center
        val local = rotatePoint(pointer, center, -rotation)
        var left = frame.x
        var top = frame.y
        var right = frame.x + frame.w
        var bottom = frame.y + frame.h
        when (handle) {
            DesignHandle.TopLeft -> {
                left = min(local.x, right - minSize)
                top = min(local.y, bottom - minSize)
            }
            DesignHandle.Top -> top = min(local.y, bottom - minSize)
            DesignHandle.TopRight -> {
                right = max(local.x, left + minSize)
                top = min(local.y, bottom - minSize)
            }
            DesignHandle.Right -> right = max(local.x, left + minSize)
            DesignHandle.BottomRight -> {
                right = max(local.x, left + minSize)
                bottom = max(local.y, top + minSize)
            }
            DesignHandle.Bottom -> bottom = max(local.y, top + minSize)
            DesignHandle.BottomLeft -> {
                left = min(local.x, right - minSize)
                bottom = max(local.y, top + minSize)
            }
            DesignHandle.Left -> left = min(local.x, right - minSize)
            DesignHandle.Rotate -> return frame
        }
        val nextCenter = Offset((left + right) / 2f, (top + bottom) / 2f)
        val shift = rotateVector(nextCenter - center, rotation)
        val w = right - left
        val h = bottom - top
        val world = center + shift
        return NodeFrame(world.x - w / 2f, world.y - h / 2f, w, h)
    }

    /** 旋转角度：从中心指向手指的夹角，按住时的角度作为起点，避免跳动。 */
    fun rotationDegrees(center: Offset, start: Offset, current: Offset): Float {
        val a = atan2(start.y - center.y, start.x - center.x)
        val b = atan2(current.y - center.y, current.x - center.x)
        return Math.toDegrees((b - a).toDouble()).toFloat()
    }

    /** 旋转吸附：15° 一档，只吸附到 0 / 90 / 180 / 270 附近。 */
    fun snapRotation(degrees: Float, threshold: Float = 4f): Float {
        val normalized = ((degrees % 360f) + 360f) % 360f
        val nearest = (normalized / 90f).roundToInt() * 90f
        val delta = shortestDelta(normalized, nearest)
        return if (abs(delta) <= threshold) degrees + delta else degrees
    }

    /** 移动吸附：左/中/右 与 上/中/下 三对齐。 */
    fun snapMove(rect: Rect, targets: List<Rect>, threshold: Float): SnapResult {
        var bestDx = 0f
        var bestX = threshold
        var guideX = Float.NaN
        var bestDy = 0f
        var bestY = threshold
        var guideY = Float.NaN

        val moveXs = floatArrayOf(rect.left, rect.center.x, rect.right)
        val moveYs = floatArrayOf(rect.top, rect.center.y, rect.bottom)
        targets.forEach { target ->
            val targetXs = floatArrayOf(target.left, target.center.x, target.right)
            val targetYs = floatArrayOf(target.top, target.center.y, target.bottom)
            moveXs.forEach { mx ->
                targetXs.forEach { tx ->
                    val delta = tx - mx
                    if (abs(delta) < bestX) {
                        bestX = abs(delta)
                        bestDx = delta
                        guideX = tx
                    }
                }
            }
            moveYs.forEach { my ->
                targetYs.forEach { ty ->
                    val delta = ty - my
                    if (abs(delta) < bestY) {
                        bestY = abs(delta)
                        bestDy = delta
                        guideY = ty
                    }
                }
            }
        }
        return SnapResult(
            dx = bestDx,
            dy = bestDy,
            vertical = if (guideX.isNaN()) emptyList() else listOf(guideX),
            horizontal = if (guideY.isNaN()) emptyList() else listOf(guideY),
        )
    }

    /** 命中测试：后画的在上，先查子节点；装饰层与几乎透明的层不参与。 */
    fun hitTest(nodes: List<ResolvedNode>, point: Offset): ResolvedNode? {
        for (node in nodes.asReversed()) {
            hitTest(node.children, point)?.let { return it }
            if (node.alpha <= .05f || node.type == "decor") continue
            val local = if (node.rotation == 0f) point else rotatePoint(point, node.center, -node.rotation)
            if (local.x in node.x..node.right && local.y in node.y..node.bottom) return node
        }
        return null
    }

    /** 框选：与选框相交的图层都算选中。 */
    fun marqueeSelection(nodes: List<ResolvedNode>, rect: Rect): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(list: List<ResolvedNode>) {
            list.forEach { node ->
                walk(node.children)
                if (node.alpha <= .05f || node.type == "decor") return@forEach
                if (node.x < rect.right && node.right > rect.left &&
                    node.y < rect.bottom && node.bottom > rect.top
                ) {
                    out.add(node.id)
                }
            }
        }
        walk(nodes)
        return out
    }

    fun DrawScope.drawSelection(
        node: ResolvedNode,
        strokeWidth: Float,
        handleRadius: Float,
        rotateOffset: Float,
        handles: Boolean,
    ) {
        rotate(node.rotation, pivot = node.center) {
            drawRect(
                color = DesignSpec.ACCENT,
                topLeft = Offset(node.x, node.y),
                size = Size(node.w, node.h),
                style = Stroke(width = strokeWidth),
            )
            if (handles) {
                rawHandlePositions(node, rotateOffset).forEach { (handle, center) ->
                    val radius = if (handle == DesignHandle.Rotate) handleRadius * .8f else handleRadius
                    drawCircle(Color.White, radius = radius, center = center)
                    drawCircle(
                        color = DesignSpec.ACCENT,
                        radius = radius,
                        center = center,
                        style = Stroke(width = strokeWidth),
                    )
                }
                drawLine(
                    color = DesignSpec.ACCENT.copy(alpha = .55f),
                    start = Offset(node.x + node.w / 2f, node.y),
                    end = Offset(node.x + node.w / 2f, node.y - rotateOffset),
                    strokeWidth = strokeWidth,
                )
            }
        }
    }

    fun DrawScope.drawSnapGuides(vertical: List<Float>, horizontal: List<Float>, width: Float, height: Float) {
        vertical.forEach { x ->
            drawLine(DesignSpec.ACCENT.copy(alpha = .75f), Offset(x, 0f), Offset(x, height), 1f)
        }
        horizontal.forEach { y ->
            drawLine(DesignSpec.ACCENT.copy(alpha = .75f), Offset(0f, y), Offset(width, y), 1f)
        }
    }

    fun DrawScope.drawMarquee(rect: Rect, strokeWidth: Float) {
        drawRect(DesignSpec.ACCENT.copy(alpha = .10f), topLeft = rect.topLeft, size = rect.size)
        drawRect(
            color = DesignSpec.ACCENT.copy(alpha = .8f),
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = strokeWidth),
        )
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun shortestDelta(from: Float, to: Float): Float {
        var delta = to - from
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}

fun rotateVector(vector: Offset, degrees: Float): Offset {
    if (degrees == 0f) return vector
    val radians = Math.toRadians(degrees.toDouble())
    val cosValue = cos(radians).toFloat()
    val sinValue = sin(radians).toFloat()
    return Offset(
        vector.x * cosValue - vector.y * sinValue,
        vector.x * sinValue + vector.y * cosValue,
    )
}

fun rotatePoint(point: Offset, pivot: Offset, degrees: Float): Offset {
    if (degrees == 0f) return point
    return pivot + rotateVector(point - pivot, degrees)
}
