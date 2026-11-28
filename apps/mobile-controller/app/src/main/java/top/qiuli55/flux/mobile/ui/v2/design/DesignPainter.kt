package top.qiuli55.flux.mobile.ui.v2.design

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 画板 / 节点绘制：所有「设计稿长什么样」的规则都收在这里。 */
object DesignPainter {

    /**
     * 离屏模糊位图缓存。
     *
     * 阴影 / 文字效果按「形状 + blur + 颜色」缓存：拖动画布（相机变化）时几何不变，
     * 每帧只是把同一张位图按新相机贴上去，不会反复做模糊。位图在**设计坐标系**
     * 生成，缩放交给 GPU 双线性采样；阴影本身是低频信号，放大不会出现硬边。
     */
    private data class ShadowKey(
        val w: Int,
        val h: Int,
        val corners: Int,
        val ellipse: Boolean,
        val blur: Int,
        val color: Color,
    )

    private data class InsetKey(
        val w: Int,
        val h: Int,
        val corners: Int,
        val ellipse: Boolean,
        val dx: Int,
        val dy: Int,
        val blur: Int,
        val color: Color,
    )

    private data class TextFxKey(
        val layout: Int,
        val width: Int,
        val height: Int,
        val blur: Int,
        val color: Color,
    )

    private val shadowCache = LinkedHashMap<ShadowKey, ImageBitmap>(32, .75f, true)
    private val insetCache = LinkedHashMap<InsetKey, ImageBitmap>(16, .75f, true)
    private val textFxCache = LinkedHashMap<TextFxKey, ImageBitmap>(32, .75f, true)

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
        /** 当前相机 / 预览的「设计单位 → 设备像素」比例，用于整数倍缩放时的像素吸附。 */
        pixelScale: Float = 1f,
    ) {
        if (device) drawDeviceShadow(width, height, radius)
        if (device) {
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, width, height, CornerRadius(radius)))
            }
            clipPath(path) {
                drawRect(bg, size = Size(width, height))
                drawResolvedNodes(nodes, pixelScale)
            }
        } else {
            drawRect(bg, size = Size(width, height))
            drawResolvedNodes(nodes, pixelScale)
        }
    }

    /**
     * 设备外框投影 `0 26px 60px rgba(0,0,0,.55)`：一次性离屏高斯模糊。
     *
     * 不再用「扩张圆角矩形」叠层，边缘没有一圈圈的台阶；与网页 CSS box-shadow
     * 和桌面 Skia BoxShadow 的模糊半径口径一致（σ = blur / 2）。
     */
    fun DrawScope.drawDeviceShadow(width: Float, height: Float, radius: Float) {
        val color = Color.Black.copy(alpha = DesignSpec.DEVICE_SHADOW_ALPHA / 255f)
        val image = cachedOuterShadow(
            width = width,
            height = height,
            corners = DesignSpec.Corners(radius, radius, radius, radius),
            ellipse = false,
            blur = DesignSpec.DEVICE_SHADOW_BLUR,
            color = color,
        ) ?: return
        val pad = DesignSpec.blurPadding(DesignSpec.DEVICE_SHADOW_BLUR)
        drawImage(
            image = image,
            topLeft = Offset(-pad, -pad + DesignSpec.DEVICE_SHADOW_DY),
        )
    }

    fun DrawScope.drawResolvedNodes(nodes: List<ResolvedNode>, pixelScale: Float = 1f) {
        nodes.forEach { node ->
            if (node.rotation == 0f) {
                drawNodeWithChildren(node, pixelScale)
            } else {
                rotate(node.rotation, pivot = node.center) {
                    drawNodeWithChildren(node, pixelScale)
                }
            }
        }
    }

    /**
     * 画节点自身再画子层。`text/button/pill/input/nav` 在网页里是 `overflow:hidden`，
     * 子层必须裁剪到父框圆角内；`card` 是 `overflow:visible`，子层可以溢出。
     *
     * `blur > 0` 的节点走「整棵子树离屏 + 像素高斯」：这是真正的图层模糊，
     * 而不是给边缘描一圈虚化。
     */
    private fun DrawScope.drawNodeWithChildren(
        node: ResolvedNode,
        pixelScale: Float,
        skipOwnBlur: Boolean = false,
    ) {
        if (node.blur > .0001f && !skipOwnBlur) {
            drawBlurredSubtree(node, pixelScale)
            return
        }
        paintNode(node, pixelScale)
        if (node.children.isEmpty()) return
        if (node.clip) {
            clipPath(shapePath(node, node.x, node.y, node.right, node.bottom, 0f)) {
                drawResolvedNodes(node.children, pixelScale)
            }
        } else {
            drawResolvedNodes(node.children, pixelScale)
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

    private fun DrawScope.paintNode(node: ResolvedNode, pixelScale: Float) {
        // 对齐 CSS 合成顺序：外阴影 → 底色/控件 → 内阴影 → 描边 → 文字。
        node.shadows.forEach { drawShadow(node, it, pixelScale) }

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
        if (node.insetShadows.isNotEmpty()) drawInsetShadows(node, pixelScale)

        node.stroke?.let { drawNodeStroke(node, it) }

        if (node.navCells.isNotEmpty()) {
            drawNavCells(node)
        } else {
            val layout = node.textLayout ?: return
            clipRect(node.x, node.y, node.right, node.bottom) {
                drawTextNode(node, layout, pixelScale)
            }
        }
    }

    /**
     * 节点外形路径：椭圆直接 addOval，其余用 [DesignSpec.Corners] 的四角圆角。
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

    // ------------------------------------------------------------ 阴影（真实模糊）

    private fun DrawScope.drawShadow(
        node: ResolvedNode,
        spec: DesignSpec.ShadowSpec,
        pixelScale: Float,
    ) {
        val color = spec.color.copy(alpha = spec.color.alpha * node.alpha)
        if (color.alpha <= .002f) return
        if (spec.blur <= .0001f) {
            // blur=0：CSS 硬阴影，直接画偏移实心形状，避免位图采样把边缘磨虚。
            val left = node.x + spec.dx
            val top = node.y + spec.dy
            if (node.ellipse) {
                drawOval(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(node.w, node.h),
                )
            } else {
                drawPath(
                    path = shapePath(node, left, top, left + node.w, top + node.h, 0f),
                    color = color,
                )
            }
            return
        }
        val image = cachedOuterShadow(
            width = node.w,
            height = node.h,
            corners = node.corners,
            ellipse = node.ellipse,
            blur = spec.blur,
            color = spec.color,
        ) ?: return
        val pad = DesignSpec.blurPadding(spec.blur)
        val x = DesignSpec.snapToPixel(node.x + spec.dx - pad, pixelScale)
        val y = DesignSpec.snapToPixel(node.y + spec.dy - pad, pixelScale)
        drawImage(image = image, topLeft = Offset(x, y), alpha = node.alpha)
    }

    /**
     * 内阴影：外框减去「同尺寸、偏移 (dx, dy)」的洞，模糊后裁剪在盒内。
     * blur=0 时就是硬边内投影。
     */
    private fun DrawScope.drawInsetShadows(node: ResolvedNode, pixelScale: Float) {
        node.insetShadows.forEach { spec ->
            if (spec.blur <= .0001f) {
                val outer = shapePath(node, node.x, node.y, node.right, node.bottom, 0f)
                clipPath(outer) {
                    val hole = shapePath(
                        node,
                        node.x + spec.dx,
                        node.y + spec.dy,
                        node.right + spec.dx,
                        node.bottom + spec.dy,
                        0f,
                    )
                    val color = spec.color.copy(alpha = spec.color.alpha * node.alpha)
                    val ring = Path().apply { op(outer, hole, androidx.compose.ui.graphics.PathOperation.Difference) }
                    drawPath(ring, color = color)
                }
                return@forEach
            }
            val image = cachedInsetShadow(node, spec) ?: return@forEach
            val x = DesignSpec.snapToPixel(node.x, pixelScale)
            val y = DesignSpec.snapToPixel(node.y, pixelScale)
            drawImage(image = image, topLeft = Offset(x, y), alpha = node.alpha)
        }
    }

    /**
     * 外阴影离屏位图：形状画进透明 bitmap，再用平台 `BlurMaskFilter` 做真实高斯。
     * 位图四周按 3σ 留边，模糊不会被画布边缘截断。
     */
    private fun cachedOuterShadow(
        width: Float,
        height: Float,
        corners: DesignSpec.Corners,
        ellipse: Boolean,
        blur: Float,
        color: Color,
    ): ImageBitmap? {
        if (width <= 0f || height <= 0f || color.alpha <= .002f) return null
        val key = ShadowKey(
            w = q(width),
            h = q(height),
            corners = cornersKey(corners),
            ellipse = ellipse,
            blur = q(blur),
            color = color,
        )
        shadowCache[key]?.let { return it }
        val pad = ceil(DesignSpec.blurPadding(blur))
        val wPx = (width + pad * 2f).roundToInt().coerceAtLeast(1)
        val hPx = (height + pad * 2f).roundToInt().coerceAtLeast(1)
        val image = ImageBitmap(wPx, hPx)
        val canvas = android.graphics.Canvas(image.asAndroidBitmap())
        canvas.translate(pad, pad)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            if (blur > .0001f) {
                maskFilter = BlurMaskFilter(DesignSpec.blurMaskRadius(blur), BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.drawPath(androidShapePath(ellipse, corners, 0f, 0f, width, height), paint)
        if (shadowCache.size > 96) shadowCache.clear()
        shadowCache[key] = image
        return image
    }

    private fun cachedInsetShadow(node: ResolvedNode, spec: DesignSpec.ShadowSpec): ImageBitmap? {
        if (node.w <= 0f || node.h <= 0f || spec.color.alpha <= .002f) return null
        val key = InsetKey(
            w = q(node.w),
            h = q(node.h),
            corners = cornersKey(node.corners),
            ellipse = node.ellipse,
            dx = q(spec.dx),
            dy = q(spec.dy),
            blur = q(spec.blur),
            color = spec.color,
        )
        insetCache[key]?.let { return it }
        val wPx = ceil(node.w).toInt().coerceAtLeast(1)
        val hPx = ceil(node.h).toInt().coerceAtLeast(1)
        val image = ImageBitmap(wPx, hPx)
        val canvas = android.graphics.Canvas(image.asAndroidBitmap())
        val outer = androidShapePath(node.ellipse, node.corners, 0f, 0f, node.w, node.h)
        val hole = androidShapePath(
            node.ellipse,
            node.corners,
            spec.dx,
            spec.dy,
            node.w + spec.dx,
            node.h + spec.dy,
        )
        val ring = android.graphics.Path().apply {
            addRect(-wPx.toFloat(), -hPx.toFloat(), wPx * 2f, hPx * 2f, android.graphics.Path.Direction.CW)
            op(hole, android.graphics.Path.Op.DIFFERENCE)
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = spec.color.toArgb()
            if (spec.blur > .0001f) {
                maskFilter = BlurMaskFilter(
                    DesignSpec.blurMaskRadius(spec.blur),
                    BlurMaskFilter.Blur.NORMAL,
                )
            }
        }
        canvas.save()
        canvas.clipPath(outer)
        canvas.drawPath(ring, paint)
        canvas.restore()
        if (insetCache.size > 48) insetCache.clear()
        insetCache[key] = image
        return image
    }

    private fun androidShapePath(
        ellipse: Boolean,
        corners: DesignSpec.Corners,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): android.graphics.Path {
        val path = android.graphics.Path()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return path
        if (ellipse) {
            path.addOval(left, top, right, bottom, android.graphics.Path.Direction.CW)
            return path
        }
        val limit = min(w, h) / 2f
        fun corner(value: Float) = value.coerceIn(0f, limit)
        val radii = floatArrayOf(
            corner(corners.tl), corner(corners.tl),
            corner(corners.tr), corner(corners.tr),
            corner(corners.br), corner(corners.br),
            corner(corners.bl), corner(corners.bl),
        )
        path.addRoundRect(left, top, right, bottom, radii, android.graphics.Path.Direction.CW)
        return path
    }

    // ------------------------------------------------------------ 图层模糊

    /**
     * 节点级 `filter: blur()`：把节点 + 子层整棵子树画进离屏位图，再做 3 遍盒式
     * 模糊（近似高斯）。`BlurMaskFilter` 只模糊 alpha，不能用于彩色图层。
     */
    private fun DrawScope.drawBlurredSubtree(node: ResolvedNode, pixelScale: Float) {
        val sigma = DesignSpec.layerSigma(node.blur)
        if (sigma <= .01f) {
            drawNodeWithChildren(node, pixelScale, skipOwnBlur = true)
            return
        }
        val pad = DesignSpec.layerBlurPadding(node.blur)
        // 离屏位图是「未旋转」的：根节点的旋转由外层 rotate 施加，
        // 因此这里不能把根节点的包围盒先转一次；子节点的旋转则要烘进位图。
        val bounds = subtreeBounds(node, includeOwnRotation = false)
        val left = floor(bounds.left - pad)
        val top = floor(bounds.top - pad)
        val right = ceil(bounds.right + pad)
        val bottom = ceil(bounds.bottom + pad)
        val wPx = (right - left).toInt().coerceAtLeast(1)
        val hPx = (bottom - top).toInt().coerceAtLeast(1)
        // 防止异常大的隐藏图层把内存打爆；超过 8MP 时退化为不模糊。
        if (wPx.toLong() * hPx.toLong() > 8_000_000L) {
            drawNodeWithChildren(node, pixelScale, skipOwnBlur = true)
            return
        }
        val image = ImageBitmap(wPx, hPx)
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            this,
            layoutDirection,
            ComposeCanvas(image),
            Size(wPx.toFloat(), hPx.toFloat()),
        ) {
            translate(-left, -top) {
                drawNodeWithChildren(node, 1f, skipOwnBlur = true)
            }
        }
        val bitmap = image.asAndroidBitmap()
        val pixels = IntArray(wPx * hPx)
        bitmap.getPixels(pixels, 0, wPx, 0, 0, wPx, hPx)
        DesignSpec.boxBlurArgb(pixels, wPx, hPx, sigma)
        bitmap.setPixels(pixels, 0, wPx, 0, 0, wPx, hPx)
        drawImage(image = image, topLeft = Offset(left, top))
    }

    /** 子树绘制范围：节点框 + 阴影/文字效果外扩 + 所有子层。 */
    private fun subtreeBounds(node: ResolvedNode, includeOwnRotation: Boolean = true): Rect {
        var left = node.x
        var top = node.y
        var right = node.right
        var bottom = node.bottom
        node.shadows.forEach { spec ->
            val pad = DesignSpec.blurPadding(spec.blur)
            left = min(left, node.x + spec.dx - pad)
            top = min(top, node.y + spec.dy - pad)
            right = max(right, node.right + spec.dx + pad)
            bottom = max(bottom, node.bottom + spec.dy + pad)
        }
        node.textEffects.forEach { effect ->
            val pad = DesignSpec.blurPadding(effect.blur)
            left = min(left, node.textOrigin.x + effect.dx - pad)
            top = min(top, node.textOrigin.y + effect.dy - pad)
            right = max(right, node.textOrigin.x + effect.dx + pad)
            bottom = max(bottom, node.textOrigin.y + effect.dy + pad)
        }
        node.children.forEach { child ->
            val childBounds = subtreeBounds(child)
            left = min(left, childBounds.left)
            top = min(top, childBounds.top)
            right = max(right, childBounds.right)
            bottom = max(bottom, childBounds.bottom)
        }
        val bounds = Rect(left, top, right, bottom)
        if (!includeOwnRotation || abs(node.rotation) <= .01f) return bounds
        // 子层旋转后可能超出未旋转包围盒；取旋转后四角的 AABB（保守但正确）。
        return rotateBounds(bounds, node.center.x, node.center.y, node.rotation)
    }

    private fun rotateBounds(bounds: Rect, cx: Float, cy: Float, degrees: Float): Rect {
        val radians = degrees * PI.toFloat() / 180f
        val cos = cos(radians)
        val sin = sin(radians)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        listOf(
            Offset(bounds.left, bounds.top),
            Offset(bounds.right, bounds.top),
            Offset(bounds.right, bounds.bottom),
            Offset(bounds.left, bounds.bottom),
        ).forEach { point ->
            val dx = point.x - cx
            val dy = point.y - cy
            val x = cx + dx * cos - dy * sin
            val y = cy + dx * sin + dy * cos
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        return Rect(left, top, right, bottom)
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
     * 文字绘制顺序对齐网页的合成结果：投影（真实模糊）→ 发光（真实模糊）→
     * 描边 → 填充。
     *
     * 模糊走字形轮廓的离屏高斯。整数倍缩放时文字原点吸附到像素网格，
     * 避免半像素发虚；任意缩放时保留亚像素定位，防止平移抖动。
     */
    private fun DrawScope.drawTextNode(
        node: ResolvedNode,
        layout: TextLayoutResult,
        pixelScale: Float,
    ) {
        val alpha = node.alpha * node.placeholderAlpha
        val origin = Offset(
            DesignSpec.snapToPixel(node.textOrigin.x, pixelScale),
            DesignSpec.snapToPixel(node.textOrigin.y, pixelScale),
        )
        drawTextEffects(node, origin, alpha)
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

    private fun DrawScope.drawTextEffects(node: ResolvedNode, origin: Offset, alpha: Float) {
        if (node.textEffects.isEmpty()) return
        val path = node.textPath ?: return
        node.textEffects.forEach { effect ->
            val color = effect.color
            if (color.alpha <= .002f) return@forEach
            if (effect.blur <= .0001f) {
                translate(origin.x + effect.dx, origin.y + effect.dy) {
                    drawPath(path = path, color = color, alpha = alpha)
                }
                return@forEach
            }
            val image = cachedTextEffect(node, effect) ?: return@forEach
            val pad = DesignSpec.blurPadding(effect.blur)
            val bounds = path.getBounds()
            drawImage(
                image = image,
                topLeft = Offset(
                    origin.x + effect.dx + bounds.left - pad,
                    origin.y + effect.dy + bounds.top - pad,
                ),
                alpha = alpha,
            )
        }
    }

    private fun cachedTextEffect(node: ResolvedNode, effect: DesignSpec.TextEffect): ImageBitmap? {
        val layout = node.textLayout ?: return null
        val path = node.textPath ?: return null
        val bounds = path.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f || effect.color.alpha <= .002f) return null
        val key = TextFxKey(
            layout = System.identityHashCode(layout),
            width = q(bounds.width),
            height = q(bounds.height),
            blur = q(effect.blur),
            color = effect.color,
        )
        textFxCache[key]?.let { return it }
        val pad = ceil(DesignSpec.blurPadding(effect.blur))
        val wPx = (bounds.width + pad * 2f).roundToInt().coerceAtLeast(1)
        val hPx = (bounds.height + pad * 2f).roundToInt().coerceAtLeast(1)
        val image = ImageBitmap(wPx, hPx)
        val canvas = android.graphics.Canvas(image.asAndroidBitmap())
        canvas.translate(pad - bounds.left, pad - bounds.top)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = effect.color.toArgb()
            maskFilter = BlurMaskFilter(
                DesignSpec.blurMaskRadius(effect.blur),
                BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.drawPath(path.asAndroidPath(), paint)
        if (textFxCache.size > 64) textFxCache.clear()
        textFxCache[key] = image
        return image
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

    private fun cornersKey(corners: DesignSpec.Corners): Int =
        (q(corners.tl) * 31 + q(corners.tr)) * 31 * 31 + q(corners.br) * 31 + q(corners.bl)

    private fun q(value: Float): Int = (value * 10f).roundToInt()
}
