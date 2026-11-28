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
import kotlin.math.hypot
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
    val right: Float get() = x + w
    val bottom: Float get() = y + h
}

/** 一条空隙参考线：连接两个相邻对象之间的空档，[start] / [end] 是设计坐标。 */
data class GapGuide(val start: Offset, val end: Offset)

/** 吸附结果：需要补偿的位移 + 需要画出来的参考线（设计坐标）。 */
data class SnapResult(
    val dx: Float,
    val dy: Float,
    val vertical: List<Float>,
    val horizontal: List<Float>,
    /** 间距吸附的参考线；与对齐参考线可能同时出现。 */
    val gaps: List<GapGuide> = emptyList(),
)

/** 缩放手柄拖动时的吸附结果。 */
data class ResizeSnap(
    val frame: NodeFrame,
    val vertical: List<Float> = emptyList(),
    val horizontal: List<Float> = emptyList(),
)

/** 双指手势的意图：不确定 → 平移或缩放；一旦进入缩放，本次手势保持缩放。 */
enum class TwoFingerIntent { Undecided, Pan, Zoom }

/**
 * 画布相机：屏幕坐标 ↔ 设计坐标的唯一换算入口。
 *
 * 渲染、命中测试和双指缩放都走这套换算，避免「画在这里、点在那里」。
 * [zoom] 是相对适配视图的倍数（1 = 整屏适配），[pan] 是屏幕像素平移。
 */
data class DesignCamera(
    val viewW: Float,
    val viewH: Float,
    val boardW: Float,
    val boardH: Float,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    val fitPadding: Float = .92f,
) {
    companion object {
        const val MIN_ZOOM = .6f
        const val MAX_ZOOM = 4f
    }

    /** 适配视图的基准缩放。 */
    val fitScale: Float
        get() = if (boardW <= 0f || boardH <= 0f) {
            1f
        } else {
            min(viewW / boardW, viewH / boardH) * fitPadding
        }

    val scale: Float get() = fitScale * zoom

    val origin: Offset
        get() = Offset(
            (viewW - boardW * scale) / 2f + pan.x,
            (viewH - boardH * scale) / 2f + pan.y,
        )

    fun toDesign(position: Offset): Offset = Offset(
        (position.x - origin.x) / scale,
        (position.y - origin.y) / scale,
    )

    fun toScreen(point: Offset): Offset = Offset(
        point.x * scale + origin.x,
        point.y * scale + origin.y,
    )

    fun panBy(delta: Offset): DesignCamera = copy(pan = pan + delta)

    /**
     * 绕屏幕上的 [anchor] 缩放：锚点下的设计坐标保持不动。
     * 这是「不绕画布原点缩放」的核心，双指与滚轮共用。
     */
    fun zoomAround(anchor: Offset, factor: Float): DesignCamera {
        if (factor <= 0f || factor.isNaN()) return this
        val nextZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nextZoom == zoom) return this
        val oldOrigin = origin
        val ratio = nextZoom / zoom
        // 让 anchor 下的设计点固定：origin 按同一比例远离 / 靠近 anchor。
        val nextOrigin = anchor - (anchor - oldOrigin) * ratio
        val centered = Offset(
            (viewW - boardW * fitScale * nextZoom) / 2f,
            (viewH - boardH * fitScale * nextZoom) / 2f,
        )
        return copy(zoom = nextZoom, pan = nextOrigin - centered)
    }

    fun reset(): DesignCamera = copy(zoom = 1f, pan = Offset.Zero)
}

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

    /**
     * 手柄热区半径：调用方给「希望的手柄半径（设计坐标）」，这里按节点大小收窄。
     *
     * 大节点上保持 44dp 的触摸直径；小节点上 8 个手柄会互相重叠、把整个节点盖住，
     * 所以最多收到短边的 1/2.2，既保留可点性又不会吞掉旁边对象的点击。
     */
    fun handleHitRadius(desired: Float, frame: NodeFrame): Float {
        if (desired <= 0f) return 0f
        val minSide = min(frame.w, frame.h)
        if (minSide <= 0f) return desired
        return min(desired, max(minSide / 2.2f, desired * .35f))
    }

    /** 命中哪个手柄；[radius] 是设计坐标下的热区半径（调用方按缩放反算，保证约 44dp）。 */
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
     *
     * [lockAspect] 为真时保持原始宽高比：角点沿对角线等比缩放，边中点则另一边绕中心等比变化。
     */
    fun resizeFrame(
        frame: NodeFrame,
        rotation: Float,
        handle: DesignHandle,
        pointer: Offset,
        minSize: Float = 12f,
        lockAspect: Boolean = false,
    ): NodeFrame {
        if (!handle.isResize) return frame
        if (lockAspect) return resizeFrameLocked(frame, rotation, handle, pointer, minSize)

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

    /** 等比缩放：所有计算仍在局部坐标里完成，最后只把中心位移转回世界坐标。 */
    private fun resizeFrameLocked(
        frame: NodeFrame,
        rotation: Float,
        handle: DesignHandle,
        pointer: Offset,
        minSize: Float,
    ): NodeFrame {
        val center = frame.center
        val local = rotatePoint(pointer, center, -rotation)
        val ratio = if (frame.h > .0001f) frame.w / frame.h else 1f
        var left = frame.x
        var top = frame.y
        var right = frame.right
        var bottom = frame.bottom
        when (handle) {
            DesignHandle.TopLeft,
            DesignHandle.TopRight,
            DesignHandle.BottomRight,
            DesignHandle.BottomLeft,
            -> {
                // 对角钉住：把指针向量投影到原始对角线，避免两个轴互相打架。
                val anchorX = if (handle == DesignHandle.TopRight ||
                    handle == DesignHandle.BottomRight
                ) {
                    left
                } else {
                    right
                }
                val anchorY = if (handle == DesignHandle.BottomLeft ||
                    handle == DesignHandle.BottomRight
                ) {
                    top
                } else {
                    bottom
                }
                val signX = if (anchorX == left) 1f else -1f
                val signY = if (anchorY == top) 1f else -1f
                val vx = (local.x - anchorX) * signX
                val vy = (local.y - anchorY) * signY
                val denom = frame.w * frame.w + frame.h * frame.h
                var factor = if (denom <= .0001f) 1f else {
                    (vx * frame.w + vy * frame.h) / denom
                }
                if (factor.isNaN()) factor = 1f
                factor = max(factor, minSize / max(frame.w, .0001f))
                factor = max(factor, minSize / max(frame.h, .0001f))
                val w = frame.w * factor
                val h = frame.h * factor
                left = if (signX > 0f) anchorX else anchorX - w
                right = left + w
                top = if (signY > 0f) anchorY else anchorY - h
                bottom = top + h
            }
            DesignHandle.Left,
            DesignHandle.Right,
            -> {
                val anchorX = if (handle == DesignHandle.Right) frame.x else frame.right
                val signX = if (handle == DesignHandle.Right) 1f else -1f
                val w = ((local.x - anchorX) * signX).coerceAtLeast(minSize)
                val h = (w / max(ratio, .0001f)).coerceAtLeast(minSize)
                // 边手柄只改一个方向，另一个方向绕原中心线变化。
                left = if (signX > 0f) anchorX else anchorX - w
                right = left + w
                top = center.y - h / 2f
                bottom = top + h
            }
            DesignHandle.Top,
            DesignHandle.Bottom,
            -> {
                val anchorY = if (handle == DesignHandle.Bottom) frame.y else frame.bottom
                val signY = if (handle == DesignHandle.Bottom) 1f else -1f
                val h = ((local.y - anchorY) * signY).coerceAtLeast(minSize)
                val w = (h * ratio).coerceAtLeast(minSize)
                top = if (signY > 0f) anchorY else anchorY - h
                bottom = top + h
                left = center.x - w / 2f
                right = left + w
            }
            DesignHandle.Rotate -> return frame
        }
        val nextLocalCenter = Offset((left + right) / 2f, (top + bottom) / 2f)
        val shift = rotateVector(nextLocalCenter - center, rotation)
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

    /** 旋转吸附：默认 15° 一档，其中 0 / 90 / 180 / 270 是更优先的直角档。 */
    fun snapRotation(degrees: Float, threshold: Float = 4f, step: Float = 15f): Float {
        val rightAngle = snapRotationToStep(degrees, 90f, threshold)
        if (rightAngle != degrees) return rightAngle
        return snapRotationToStep(degrees, step, threshold)
    }

    /** 吸附到任意 [step] 的整数倍；超过 [threshold] 保持原值。 */
    fun snapRotationToStep(degrees: Float, step: Float, threshold: Float = 4f): Float {
        if (step <= 0f) return degrees
        val nearest = (degrees / step).roundToInt() * step
        val delta = shortestDelta(degrees, nearest)
        return if (abs(delta) <= threshold) nearest else degrees
    }

    /** 强制按 [step]（默认 15°）取整；对应桌面按住 Shift 的约束旋转。 */
    fun constrainRotation(degrees: Float, step: Float = 15f): Float {
        if (step <= 0f) return degrees
        return (degrees / step).roundToInt() * step
    }

    /**
     * 移动吸附：左/中/右 与 上/中/下 三对齐，并补上等间距吸附。
     *
     * 对齐命中时会把**所有**与当前位移吻合的参考线一起返回，而不是只留最近的一条；
     * 某一轴没有对齐命中时才尝试空隙吸附（间距相等时给出间隙线）。
     */
    fun snapMove(rect: Rect, targets: List<Rect>, threshold: Float): SnapResult {
        var bestDx = 0f
        var bestX = threshold
        var snappedX = false
        var bestDy = 0f
        var bestY = threshold
        var snappedY = false

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
                        snappedX = true
                    }
                }
            }
            moveYs.forEach { my ->
                targetYs.forEach { ty ->
                    val delta = ty - my
                    if (abs(delta) < bestY) {
                        bestY = abs(delta)
                        bestDy = delta
                        snappedY = true
                    }
                }
            }
        }

        val gaps = mutableListOf<GapGuide>()
        if (!snappedX) {
            snapGapAxis(rect, targets, threshold, horizontalAxis = true)?.let { gap ->
                bestDx = gap.delta
                gaps += gap.guides
                snappedX = true
            }
        }
        if (!snappedY) {
            snapGapAxis(rect, targets, threshold, horizontalAxis = false)?.let { gap ->
                bestDy = gap.delta
                gaps += gap.guides
                snappedY = true
            }
        }

        val moved = Rect(
            rect.left + bestDx,
            rect.top + bestDy,
            rect.right + bestDx,
            rect.bottom + bestDy,
        )
        return SnapResult(
            dx = bestDx,
            dy = bestDy,
            vertical = if (snappedX) alignGuides(moved, targets, horizontalAxis = true) else emptyList(),
            horizontal = if (snappedY) alignGuides(moved, targets, horizontalAxis = false) else emptyList(),
            gaps = gaps,
        )
    }

    private data class AxisGap(val delta: Float, val guides: List<GapGuide>)

    /**
     * 单轴空隙吸附：只和该轴上有重叠的邻居算间距。
     *
     * 两个来源：
     *  1. 邻居之间已有的空隙 g，把当前对象摆到「同样留出 g」的位置；
     *  2. 左右（上下）各有一个邻居时，调整到两侧间距相等。
     */
    private fun snapGapAxis(
        rect: Rect,
        targets: List<Rect>,
        threshold: Float,
        horizontalAxis: Boolean,
    ): AxisGap? {
        val overlapping = targets.filter { target ->
            if (horizontalAxis) {
                target.top < rect.bottom && target.bottom > rect.top
            } else {
                target.left < rect.right && target.right > rect.left
            }
        }
        if (overlapping.isEmpty()) return null

        val known = knownGaps(overlapping, horizontalAxis)
        var bestDelta = 0f
        var bestDistance = threshold
        val guides = mutableListOf<GapGuide>()

        fun consider(delta: Float, lines: List<GapGuide>) {
            if (abs(delta) > threshold) return
            if (abs(delta) <= bestDistance) {
                bestDistance = abs(delta)
                bestDelta = delta
                guides.clear()
                guides += lines
            }
        }

        overlapping.forEach { target ->
            val targetStart = if (horizontalAxis) target.left else target.top
            val targetEnd = if (horizontalAxis) target.right else target.bottom
            val rectStart = if (horizontalAxis) rect.left else rect.top
            val rectEnd = if (horizontalAxis) rect.right else rect.bottom
            val across = if (horizontalAxis) overlapCenterY(rect, target) else overlapCenterX(rect, target)

            // 当前对象在邻居之前（左 / 上）：间距 = targetStart - rectEnd
            if (rectEnd <= targetStart + .01f) {
                val currentGap = targetStart - rectEnd
                known.forEach { gap ->
                    val delta = gap - currentGap
                    consider(delta, listOf(gapLine(rectEnd + delta, targetStart, across, horizontalAxis)))
                }
            }
            // 当前对象在邻居之后（右 / 下）：间距 = rectStart - targetEnd
            if (rectStart >= targetEnd - .01f) {
                val currentGap = rectStart - targetEnd
                known.forEach { gap ->
                    val delta = targetEnd + gap - rectStart
                    consider(delta, listOf(gapLine(targetEnd, rectStart + delta, across, horizontalAxis)))
                }
            }
        }

        val before = overlapping.filter { target ->
            if (horizontalAxis) target.right <= rect.left + .01f else target.bottom <= rect.top + .01f
        }
        val after = overlapping.filter { target ->
            if (horizontalAxis) target.left >= rect.right - .01f else target.top >= rect.bottom - .01f
        }
        before.forEach { first ->
            after.forEach { second ->
                val startGap = if (horizontalAxis) {
                    rect.left - first.right
                } else {
                    rect.top - first.bottom
                }
                val endGap = if (horizontalAxis) {
                    second.left - rect.right
                } else {
                    second.top - rect.bottom
                }
                val delta = (endGap - startGap) / 2f
                val across = if (horizontalAxis) {
                    (overlapCenterY(rect, first) + overlapCenterY(rect, second)) / 2f
                } else {
                    (overlapCenterX(rect, first) + overlapCenterX(rect, second)) / 2f
                }
                val firstEnd = if (horizontalAxis) first.right else first.bottom
                val rectStart = if (horizontalAxis) rect.left + delta else rect.top + delta
                val rectEnd = if (horizontalAxis) rect.right + delta else rect.bottom + delta
                val secondStart = if (horizontalAxis) second.left else second.top
                consider(
                    delta,
                    listOf(
                        gapLine(firstEnd, rectStart, across, horizontalAxis),
                        gapLine(rectEnd, secondStart, across, horizontalAxis),
                    ),
                )
            }
        }

        if (guides.isEmpty()) return null
        return AxisGap(bestDelta, guides)
    }

    /** 邻居之间已经存在的空隙（只算真正分开、且投影重叠的一对）。 */
    private fun knownGaps(targets: List<Rect>, horizontalAxis: Boolean): List<Float> {
        val gaps = mutableListOf<Float>()
        targets.forEachIndexed { index, first ->
            targets.forEachIndexed { otherIndex, second ->
                if (index == otherIndex) return@forEachIndexed
                val overlaps = if (horizontalAxis) {
                    first.top < second.bottom && first.bottom > second.top
                } else {
                    first.left < second.right && first.right > second.left
                }
                if (!overlaps) return@forEachIndexed
                val gap = if (horizontalAxis) {
                    second.left - first.right
                } else {
                    second.top - first.bottom
                }
                if (gap > .5f) gaps += gap
            }
        }
        return gaps.distinct()
    }

    private fun gapLine(
        from: Float,
        to: Float,
        across: Float,
        horizontalAxis: Boolean,
    ): GapGuide = if (horizontalAxis) {
        GapGuide(Offset(from, across), Offset(to, across))
    } else {
        GapGuide(Offset(across, from), Offset(across, to))
    }

    private fun overlapCenterY(a: Rect, b: Rect): Float =
        (max(a.top, b.top) + min(a.bottom, b.bottom)) / 2f

    private fun overlapCenterX(a: Rect, b: Rect): Float =
        (max(a.left, b.left) + min(a.right, b.right)) / 2f

    /** 位移落定后，所有真正重合的边 / 中线都算参考线（可能同时多条）。 */
    private fun alignGuides(moved: Rect, targets: List<Rect>, horizontalAxis: Boolean): List<Float> {
        val out = linkedSetOf<Float>()
        val moveValues = if (horizontalAxis) {
            floatArrayOf(moved.left, moved.center.x, moved.right)
        } else {
            floatArrayOf(moved.top, moved.center.y, moved.bottom)
        }
        targets.forEach { target ->
            val targetValues = if (horizontalAxis) {
                floatArrayOf(target.left, target.center.x, target.right)
            } else {
                floatArrayOf(target.top, target.center.y, target.bottom)
            }
            moveValues.forEach { movedValue ->
                targetValues.forEach { targetValue ->
                    if (abs(movedValue - targetValue) <= .01f) out += targetValue
                }
            }
        }
        return out.sorted()
    }

    /**
     * 缩放手柄吸附：只吸附当前被拖动的那条 / 两条边到其它对象的边。
     * 旋转过的节点在屏幕坐标里拖边不是轴对齐的，这里不做吸附，避免把框拉歪。
     */
    fun snapResize(
        frame: NodeFrame,
        rotation: Float,
        handle: DesignHandle,
        targets: List<Rect>,
        threshold: Float,
        minSize: Float = 12f,
    ): ResizeSnap {
        if (!handle.isResize || rotation != 0f || targets.isEmpty()) return ResizeSnap(frame)
        var left = frame.x
        var top = frame.y
        var right = frame.right
        var bottom = frame.bottom

        val movesLeft = handle == DesignHandle.TopLeft ||
            handle == DesignHandle.Left || handle == DesignHandle.BottomLeft
        val movesRight = handle == DesignHandle.TopRight ||
            handle == DesignHandle.Right || handle == DesignHandle.BottomRight
        val movesTop = handle == DesignHandle.TopLeft ||
            handle == DesignHandle.Top || handle == DesignHandle.TopRight
        val movesBottom = handle == DesignHandle.BottomLeft ||
            handle == DesignHandle.Bottom || handle == DesignHandle.BottomRight

        fun nearest(values: List<Float>, current: Float): Float? {
            var best: Float? = null
            values.forEach { value ->
                val delta = value - current
                if (abs(delta) <= threshold && (best == null || abs(delta) < abs(best!!))) {
                    best = delta
                }
            }
            return best
        }

        val xs = targets.flatMap { listOf(it.left, it.right) }
        val ys = targets.flatMap { listOf(it.top, it.bottom) }
        // 只有真正把边吸过去（位移非 0）才出参考线，避免拖动时满屏常驻对齐线。
        var snappedLeft = false
        var snappedRight = false
        var snappedTop = false
        var snappedBottom = false
        if (movesLeft) {
            nearest(xs, left)?.let {
                left += it
                snappedLeft = abs(it) > .001f
            }
        }
        if (movesRight) {
            nearest(xs, right)?.let {
                right += it
                snappedRight = abs(it) > .001f
            }
        }
        if (movesTop) {
            nearest(ys, top)?.let {
                top += it
                snappedTop = abs(it) > .001f
            }
        }
        if (movesBottom) {
            nearest(ys, bottom)?.let {
                bottom += it
                snappedBottom = abs(it) > .001f
            }
        }

        if (right - left < minSize) {
            if (movesLeft) left = right - minSize else right = left + minSize
        }
        if (bottom - top < minSize) {
            if (movesTop) top = bottom - minSize else bottom = top + minSize
        }
        val next = NodeFrame(left, top, right - left, bottom - top)
        val vertical = buildList {
            if (snappedLeft) add(left)
            if (snappedRight) add(right)
        }
        val horizontal = buildList {
            if (snappedTop) add(top)
            if (snappedBottom) add(bottom)
        }
        return ResizeSnap(frame = next, vertical = vertical, horizontal = horizontal)
    }

    /**
     * 命中测试：后画的在上，先查子节点；装饰层与几乎透明的层不参与。
     *
     * 椭圆按真实椭圆判定，圆角矩形仍按矩形（视觉误差小于手指），旋转节点先把点转回局部坐标。
     * 裁剪容器外的子节点不可命中，避免「点到画外」。
     */
    fun hitTest(nodes: List<ResolvedNode>, point: Offset, tolerance: Float = 0f): ResolvedNode? {
        for (node in nodes.asReversed()) {
            if (node.alpha <= .05f) continue
            val local = if (node.rotation == 0f) {
                point
            } else {
                rotatePoint(point, node.center, -node.rotation)
            }
            val inside = inside(node, local, tolerance)
            // 可裁剪容器：点落在容器外时，子节点也不可见，不再参与命中。
            if (!node.clip || inside) {
                hitTest(node.children, point, tolerance)?.let { return it }
            }
            if (inside && node.type != "decor") return node
        }
        return null
    }

    private fun inside(node: ResolvedNode, local: Offset, tolerance: Float): Boolean {
        if (node.ellipse) {
            val rx = node.w / 2f + tolerance
            val ry = node.h / 2f + tolerance
            if (rx <= 0f || ry <= 0f) return false
            val nx = (local.x - node.center.x) / rx
            val ny = (local.y - node.center.y) / ry
            return nx * nx + ny * ny <= 1f
        }
        return local.x >= node.x - tolerance &&
            local.x <= node.right + tolerance &&
            local.y >= node.y - tolerance &&
            local.y <= node.bottom + tolerance
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
                val radius = handleHitRadius(handleRadius, NodeFrame(node.x, node.y, node.w, node.h))
                rawHandlePositions(node, rotateOffset).forEach { (handle, center) ->
                    val drawRadius = if (handle == DesignHandle.Rotate) radius * .8f else radius
                    drawCircle(Color.White, radius = drawRadius, center = center)
                    drawCircle(
                        color = DesignSpec.ACCENT,
                        radius = drawRadius,
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

    fun DrawScope.drawSnapGuides(
        vertical: List<Float>,
        horizontal: List<Float>,
        width: Float,
        height: Float,
        gaps: List<GapGuide> = emptyList(),
    ) {
        vertical.forEach { x ->
            drawLine(DesignSpec.ACCENT.copy(alpha = .75f), Offset(x, 0f), Offset(x, height), 1f)
        }
        horizontal.forEach { y ->
            drawLine(DesignSpec.ACCENT.copy(alpha = .75f), Offset(0f, y), Offset(width, y), 1f)
        }
        // 间距参考线：两端加短竖线，和普通对齐线区分开。
        gaps.forEach { gap ->
            drawLine(
                color = DesignSpec.ACCENT.copy(alpha = .9f),
                start = gap.start,
                end = gap.end,
                strokeWidth = 1.4f,
            )
            val horizontalGap = if (gap.start.y == gap.end.y) {
                true
            } else {
                gap.start.x == gap.end.x
            }
            if (horizontalGap) {
                drawLine(
                    DesignSpec.ACCENT.copy(alpha = .9f),
                    Offset(gap.start.x, gap.start.y - 3f),
                    Offset(gap.start.x, gap.start.y + 3f),
                    1.2f,
                )
                drawLine(
                    DesignSpec.ACCENT.copy(alpha = .9f),
                    Offset(gap.end.x, gap.end.y - 3f),
                    Offset(gap.end.x, gap.end.y + 3f),
                    1.2f,
                )
            } else {
                drawLine(
                    DesignSpec.ACCENT.copy(alpha = .9f),
                    Offset(gap.start.x - 3f, gap.start.y),
                    Offset(gap.start.x + 3f, gap.start.y),
                    1.2f,
                )
                drawLine(
                    DesignSpec.ACCENT.copy(alpha = .9f),
                    Offset(gap.end.x - 3f, gap.end.y),
                    Offset(gap.end.x + 3f, gap.end.y),
                    1.2f,
                )
            }
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

    /** 拖动阈值：按下后移动超过 slop 才算拖动，单击选中不会变成微移。 */
    fun dragExceeded(start: Offset, current: Offset, slop: Float): Boolean =
        distance(start, current) > slop

    /**
     * 双指意图判定。
     *
     * 不确定阶段：两指距离变化明显 → 缩放；中心移动明显 → 平移。
     * 一旦进入缩放，本次手势保持缩放（后续中心移动按平移叠加），避免边捏边切模式。
     */
    fun twoFingerIntent(
        current: TwoFingerIntent,
        startSpan: Float,
        startCentroid: Offset,
        span: Float,
        centroid: Offset,
        slop: Float,
        zoomRatioThreshold: Float = .06f,
    ): TwoFingerIntent {
        if (current != TwoFingerIntent.Undecided) return current
        if (startSpan <= .5f) return TwoFingerIntent.Undecided
        val spanChange = abs(span - startSpan)
        val centroidMove = distance(centroid, startCentroid)
        val ratioChange = abs(span / startSpan - 1f)
        val zooming = ratioChange > zoomRatioThreshold || spanChange > slop
        return when {
            zooming && spanChange >= centroidMove -> TwoFingerIntent.Zoom
            centroidMove > slop -> TwoFingerIntent.Pan
            else -> TwoFingerIntent.Undecided
        }
    }

    /** 两个指针之间的距离；不足两个返回 0。 */
    fun pointerSpan(points: List<Offset>): Float =
        if (points.size < 2) 0f else distance(points[0], points[1])

    private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

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
