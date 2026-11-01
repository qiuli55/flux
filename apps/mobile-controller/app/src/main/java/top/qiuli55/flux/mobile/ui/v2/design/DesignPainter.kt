package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.min

/** 画板 / 节点绘制：所有「设计稿长什么样」的规则都收在这里。 */
object DesignPainter {

    /**
     * 设备投影 `0 26px 60px rgba(0,0,0,.55)`，用系统阴影层实现，比叠十几层圆角便宜。
     *
     * 颜色必须是**不透明**的：硬件加速画布上给完全透明的绘制加阴影层不会出图，
     * 这里用白色画出圆角矩形，阴影留在外圈，随后 [drawScreen] 会用画板底色把白底盖掉。
     */
    private val deviceShadowPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        setShadowLayer(
            DesignSpec.DEVICE_SHADOW_BLUR,
            0f,
            DesignSpec.DEVICE_SHADOW_DY,
            android.graphics.Color.argb(DesignSpec.DEVICE_SHADOW_ALPHA, 0, 0, 0),
        )
    }

    /** 渐变网格点缓存：尺寸不变时复用同一批点，避免每帧重建几万个 Offset。 */
    private var gridKey: Long = Long.MIN_VALUE
    private var gridPoints: List<Offset> = emptyList()

    fun DrawScope.drawScreen(
        nodes: List<ResolvedNode>,
        width: Float,
        height: Float,
        bg: Color,
        device: Boolean = true,
        radius: Float = DesignSpec.DEVICE_RADIUS,
    ) {
        if (device) drawDeviceShadow(width, height, radius)
        if (device) {
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, width, height, CornerRadius(radius)))
            }
            clipPath(path) {
                drawRect(bg, size = Size(width, height))
                drawResolvedNodes(nodes)
            }
        } else {
            drawRect(bg, size = Size(width, height))
            drawResolvedNodes(nodes)
        }
    }

    fun DrawScope.drawDeviceShadow(width: Float, height: Float, radius: Float) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRoundRect(
                0f,
                0f,
                width,
                height,
                radius,
                radius,
                deviceShadowPaint,
            )
        }
    }

    fun DrawScope.drawResolvedNodes(nodes: List<ResolvedNode>) {
        nodes.forEach { node ->
            if (node.rotation == 0f) {
                drawNodeWithChildren(node)
            } else {
                rotate(node.rotation, pivot = node.center) {
                    drawNodeWithChildren(node)
                }
            }
        }
    }

    /**
     * 画节点自身再画子层。`text/button/pill/input/nav` 在网页里是 `overflow:hidden`，
     * 子层必须裁剪到父框圆角内；`card` 是 `overflow:visible`，子层可以溢出。
     */
    private fun DrawScope.drawNodeWithChildren(node: ResolvedNode) {
        paintNode(node)
        if (node.children.isEmpty()) return
        if (node.clip) {
            clipPath(shapePath(node, node.x, node.y, node.right, node.bottom, 0f)) {
                drawResolvedNodes(node.children)
            }
        } else {
            drawResolvedNodes(node.children)
        }
    }

    /** 编辑网格：一次 drawPoints 画完整屏。 */
    fun DrawScope.drawDotGrid(color: Color, step: Float = 22f, dotRadius: Float = 1.1f) {
        if (step <= 0f) return
        val points = gridPoints(step, dotRadius)
        if (points.isEmpty()) return
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = color,
            strokeWidth = dotRadius * 2f,
            cap = StrokeCap.Round,
        )
    }

    private fun DrawScope.gridPoints(step: Float, dotRadius: Float): List<Offset> {
        val cols = (size.width / step).toInt().coerceAtLeast(0)
        val rows = (size.height / step).toInt().coerceAtLeast(0)
        val key = (cols.toLong() shl 40) or (rows.toLong() shl 16) or
            (step.toRawBits().toLong() and 0xFFFF)
        if (key == gridKey && gridPoints.isNotEmpty()) return gridPoints
        val points = ArrayList<Offset>(cols * rows)
        var y = step
        while (y < size.height) {
            var x = step
            while (x < size.width) {
                points.add(Offset(x, y))
                x += step
            }
            y += step
        }
        gridKey = key
        gridPoints = points
        return points
    }

    private fun DrawScope.paintNode(node: ResolvedNode) {
        // 对齐 CSS 合成顺序：外阴影 → 底色/控件 → 内阴影 → 描边 → 文字。
        node.shadowRings.forEach { drawShadowRing(node, it) }

        when (node.type) {
            "slider" -> drawSlider(node)
            "switch" -> drawSwitch(node)
            "progress" -> drawProgress(node)
            else -> {
                val fill = node.fill
                if (fill != null) {
                    if (node.ellipse) {
                        drawOval(
                            brush = fill,
                            topLeft = Offset(node.x, node.y),
                            size = Size(node.w, node.h),
                            alpha = node.alpha,
                        )
                    } else {
                        drawPath(
                            path = shapePath(node, node.x, node.y, node.right, node.bottom, 0f),
                            brush = fill,
                            alpha = node.alpha,
                        )
                    }
                }
            }
        }

        // CSS 的 inset box-shadow 画在背景之上、内容之下。
        if (node.insetShadowRings.isNotEmpty()) drawInsetShadows(node)

        node.stroke?.let { drawNodeStroke(node, it) }

        if (node.navCells.isNotEmpty()) {
            drawNavCells(node)
        } else {
            val layout = node.textLayout ?: return
            clipRect(node.x, node.y, node.right, node.bottom) {
                drawTextNode(node, layout)
            }
        }
    }

    /**
     * 节点外形路径：椭圆直接 addOval，其余用 [DesignSpec.Corners] 的四角圆角。
     *
     * [radiusDelta] 给阴影环用：外扩 [spread] 时圆角同样加 [spread]，与 CSS 的
     * `border-radius + spread` 一致；内缩时传负值。
     */
    private fun shapePath(
        node: ResolvedNode,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusDelta: Float,
    ): Path {
        val path = Path()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return path
        if (node.ellipse) {
            path.addOval(Rect(left, top, right, bottom))
            return path
        }
        val limit = min(w, h) / 2f
        fun corner(value: Float): CornerRadius =
            CornerRadius((value + radiusDelta).coerceIn(0f, limit))
        val corners = node.corners
        path.addRoundRect(
            RoundRect(
                left,
                top,
                right,
                bottom,
                corner(corners.tl),
                corner(corners.tr),
                corner(corners.br),
                corner(corners.bl),
            ),
        )
        return path
    }

    /** 外阴影环：环已按「大 spread 在下、小 spread 在上」排好序。 */
    private fun DrawScope.drawShadowRing(node: ResolvedNode, ring: DesignSpec.ShadowRing) {
        val spread = ring.spread
        val left = node.x - spread + ring.dx
        val top = node.y - spread + ring.dy
        val right = node.right + spread + ring.dx
        val bottom = node.bottom + spread + ring.dy
        val color = ring.color.copy(alpha = ring.color.alpha * node.alpha)
        if (node.ellipse) {
            drawOval(color = color, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
        } else {
            drawPath(shapePath(node, left, top, right, bottom, spread), color = color)
        }
    }

    /**
     * 内阴影：外框减去「内缩 spread 并偏移 (dx, dy)」的洞，得到一圈贴边的阴影。
     * blur 已由 [DesignSpec.insetShadowRings] 拆成多层 spread，逐层叠加即高斯近似。
     */
    private fun DrawScope.drawInsetShadows(node: ResolvedNode) {
        val outer = shapePath(node, node.x, node.y, node.right, node.bottom, 0f)
        clipPath(outer) {
            node.insetShadowRings.forEach { ring ->
                val inset = ring.spread
                val hole = shapePath(
                    node,
                    node.x + inset + ring.dx,
                    node.y + inset + ring.dy,
                    node.right - inset + ring.dx,
                    node.bottom - inset + ring.dy,
                    -inset,
                )
                val color = ring.color.copy(alpha = ring.color.alpha * node.alpha)
                if (hole.isEmpty) {
                    drawPath(outer, color = color)
                } else {
                    val ringPath = Path().apply { op(outer, hole, PathOperation.Difference) }
                    drawPath(ringPath, color = color)
                }
            }
        }
    }

    /**
     * 节点描边：网页 `border` 恒在盒内，这里把线宽的一半内缩；
     * `dash` 按契约统一成 `[w×3, w×2]`；`sides` 只画列出的边。
     */
    private fun DrawScope.drawNodeStroke(node: ResolvedNode, spec: DesignSpec.StrokeSpec) {
        val width = spec.width
        val color = spec.color.copy(alpha = spec.color.alpha * node.alpha)
        val effect = if (spec.dash) {
            PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f))
        } else {
            null
        }
        val half = width / 2f
        if (node.ellipse) {
            val w = node.w - width
            val h = node.h - width
            if (w <= 0f || h <= 0f) return
            drawOval(
                color = color,
                topLeft = Offset(node.x + half, node.y + half),
                size = Size(w, h),
                style = Stroke(width = width, pathEffect = effect),
            )
            return
        }
        val sides = spec.sides
        if (sides == null) {
            drawPath(
                path = shapePath(
                    node,
                    node.x + half,
                    node.y + half,
                    node.right - half,
                    node.bottom - half,
                    -half,
                ),
                color = color,
                style = Stroke(width = width, pathEffect = effect),
            )
            return
        }
        clipPath(shapePath(node, node.x, node.y, node.right, node.bottom, 0f)) {
            if (spec.has("top")) {
                drawLine(
                    color = color,
                    start = Offset(node.x, node.y + half),
                    end = Offset(node.right, node.y + half),
                    strokeWidth = width,
                    pathEffect = effect,
                )
            }
            if (spec.has("bottom")) {
                drawLine(
                    color = color,
                    start = Offset(node.x, node.bottom - half),
                    end = Offset(node.right, node.bottom - half),
                    strokeWidth = width,
                    pathEffect = effect,
                )
            }
            if (spec.has("left")) {
                drawLine(
                    color = color,
                    start = Offset(node.x + half, node.y),
                    end = Offset(node.x + half, node.bottom),
                    strokeWidth = width,
                    pathEffect = effect,
                )
            }
            if (spec.has("right")) {
                drawLine(
                    color = color,
                    start = Offset(node.right - half, node.y),
                    end = Offset(node.right - half, node.bottom),
                    strokeWidth = width,
                    pathEffect = effect,
                )
            }
        }
    }

    /**
     * 文字绘制顺序对齐网页的合成结果：投影环 → 发光环 → 描边 → 填充。
     *
     * 环与描边都用「字形轮廓描边」近似：居中描边的外半圈就是网页高斯模糊的外缘，
     * 因此描边宽度取外扩距离的两倍；blur=0 时退化成同形偏移副本（CSS 硬阴影）。
     */
    private fun DrawScope.drawTextNode(node: ResolvedNode, layout: TextLayoutResult) {
        val alpha = node.alpha * node.placeholderAlpha
        val origin = node.textOrigin
        node.textRings.forEach { ring ->
            val at = origin + Offset(ring.dx, ring.dy)
            val spread = ring.spread * 2f
            if (spread <= 0f) {
                drawText(layout, color = ring.color, topLeft = at, alpha = alpha)
            } else {
                drawText(
                    layout,
                    color = ring.color,
                    topLeft = at,
                    alpha = alpha,
                    drawStyle = Stroke(width = spread),
                )
            }
        }
        node.textStroke?.let { stroke ->
            drawText(
                layout,
                color = stroke.color,
                topLeft = origin,
                alpha = alpha,
                drawStyle = Stroke(width = stroke.width),
            )
        }
        drawText(layout, topLeft = origin, alpha = alpha)
    }

    private fun DrawScope.drawNavCells(node: ResolvedNode) {
        val cell = node.w / node.navCells.size
        node.navCells.forEachIndexed { index, item ->
            val left = node.x + cell * index
            val dx = left + (cell - item.layout.size.width) / 2f
            val dy = node.y + (node.h - item.layout.size.height) / 2f - 2f
            clipRect(left, node.y, left + cell, node.bottom) {
                drawText(item.layout, topLeft = Offset(dx, dy), alpha = node.alpha)
            }
            if (item.active) {
                drawRoundRect(
                    color = DesignSpec.ACCENT.copy(alpha = .9f * node.alpha),
                    topLeft = Offset(left + cell / 2f - 6f, node.bottom - 6f),
                    size = Size(12f, 2f),
                    cornerRadius = CornerRadius(1f),
                )
            }
        }
    }

    private fun DrawScope.drawSlider(node: ResolvedNode) {
        val barHeight = 4f
        val cy = node.y + node.h / 2f
        val thumbX = node.x + node.w * node.value
        drawRoundRect(
            color = node.trackColor.copy(alpha = node.trackColor.alpha * node.alpha),
            topLeft = Offset(node.x, cy - barHeight / 2f),
            size = Size(node.w, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
        drawRoundRect(
            color = node.fillColor.copy(alpha = node.fillColor.alpha * node.alpha),
            topLeft = Offset(node.x, cy - barHeight / 2f),
            size = Size(node.w * node.value, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
        drawCircle(
            color = Color.White.copy(alpha = node.alpha),
            radius = 8f,
            center = Offset(thumbX, cy),
        )
        drawCircle(
            color = node.fillColor.copy(alpha = .22f * node.alpha),
            radius = 10f,
            center = Offset(thumbX, cy),
        )
    }

    /** 进度条：轨道铺满 + 前景按 value 裁宽，圆角继承节点（与 `.pg-*` 一致）。 */
    private fun DrawScope.drawProgress(node: ResolvedNode) {
        drawPath(
            path = shapePath(node, node.x, node.y, node.right, node.bottom, 0f),
            color = node.trackColor.copy(alpha = node.trackColor.alpha * node.alpha),
        )
        val value = node.value
        if (value <= 0f) return
        val fillWidth = (node.w * value).coerceAtMost(node.w)
        val color = node.fillColor.copy(alpha = node.fillColor.alpha * node.alpha)
        if (node.ellipse) {
            drawOval(
                color = color,
                topLeft = Offset(node.x, node.y),
                size = Size(fillWidth, node.h),
            )
        } else {
            drawPath(
                path = shapePath(node, node.x, node.y, node.x + fillWidth, node.bottom, 0f),
                color = color,
            )
        }
    }

    private fun DrawScope.drawSwitch(node: ResolvedNode) {
        val knob = (node.h - 6f).coerceAtLeast(1f)
        drawRoundRect(
            color = if (node.switchOn) {
                node.fillColor.copy(alpha = node.fillColor.alpha * node.alpha)
            } else {
                DesignSpec.SWITCH_OFF.copy(alpha = node.alpha)
            },
            topLeft = Offset(node.x, node.y),
            size = Size(node.w, node.h),
            cornerRadius = CornerRadius(node.h / 2f),
        )
        val knobCenterX = if (node.switchOn) {
            node.right - 3f - knob / 2f
        } else {
            node.x + 3f + knob / 2f
        }
        // 网页 `.sw-knob` 的 `0 1px 2px rgba(0,0,0,.18)`
        drawCircle(
            color = Color.Black.copy(alpha = .18f * node.alpha),
            radius = knob / 2f,
            center = Offset(knobCenterX, node.y + node.h / 2f + 1f),
        )
        drawCircle(
            color = Color.White.copy(alpha = node.alpha),
            radius = knob / 2f,
            center = Offset(knobCenterX, node.y + node.h / 2f),
        )
    }
}
