// 画布交互的纯几何回归用例：缩放、旋转、吸附、命中、框选。
//
// 这些计算不依赖 Compose 组合环境，出错时表现为「手柄拖歪」「吸附错位」
// 「点不中图层」，所以单独锁住。
package top.qiuli55.flux.mobile.ui.v2.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignInteractTest {

    private fun node(
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        type: String = "text",
        alpha: Float = 1f,
        rotation: Float = 0f,
        ellipse: Boolean = false,
        clip: Boolean = false,
        children: List<ResolvedNode> = emptyList(),
    ) = ResolvedNode(
        id = id,
        type = type,
        name = id,
        x = x,
        y = y,
        w = w,
        h = h,
        alpha = alpha,
        corners = DesignSpec.Corners(0f, 0f, 0f, 0f),
        rotation = rotation,
        ellipse = ellipse,
        fill = null,
        fillColor = Color.Black,
        trackColor = Color.Black,
        stroke = null,
        shadows = emptyList(),
        insetShadows = emptyList(),
        text = "",
        textOrigin = Offset.Zero,
        textLayout = null,
        navCells = emptyList(),
        value = 0f,
        switchOn = false,
        clip = clip,
        children = children,
    )

    @Test
    fun `drag bottom right grows frame`() {
        val next = DesignInteract.resizeFrame(
            NodeFrame(0f, 0f, 100f, 50f),
            rotation = 0f,
            handle = DesignHandle.BottomRight,
            pointer = Offset(160f, 90f),
        )
        assertEquals(NodeFrame(0f, 0f, 160f, 90f), next)
    }

    @Test
    fun `drag top left moves origin`() {
        val next = DesignInteract.resizeFrame(
            NodeFrame(20f, 30f, 100f, 50f),
            rotation = 0f,
            handle = DesignHandle.TopLeft,
            pointer = Offset(40f, 40f),
        )
        assertEquals(NodeFrame(40f, 40f, 80f, 40f), next)
    }

    @Test
    fun `resize clamps to min size`() {
        val next = DesignInteract.resizeFrame(
            NodeFrame(0f, 0f, 100f, 50f),
            rotation = 0f,
            handle = DesignHandle.Right,
            pointer = Offset(-80f, 0f),
            minSize = 12f,
        )
        assertEquals(12f, next.w, 0.01f)
        assertEquals(0f, next.x, 0.01f)
    }

    @Test
    fun `rotated resize keeps opposite corner pinned`() {
        val frame = NodeFrame(0f, 0f, 100f, 50f)
        val rotation = 90f
        val center = frame.center
        val handle = rotatePoint(Offset(frame.x + frame.w, frame.y + frame.h), center, rotation)

        val next = DesignInteract.resizeFrame(frame, rotation, DesignHandle.BottomRight, handle)

        // 对角（左上角）在世界坐标里不应移动
        val before = rotatePoint(Offset(frame.x, frame.y), center, rotation)
        val after = rotatePoint(Offset(next.x, next.y), next.center, rotation)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
        assertEquals(100f, next.w, 0.5f)
        assertEquals(50f, next.h, 0.5f)
    }

    @Test
    fun `snap move pulls a near edge`() {
        val moved = Rect(0f, 0f, 100f, 50f)
        val target = Rect(104f, 0f, 204f, 50f)
        val result = DesignInteract.snapMove(moved, listOf(target), threshold = 8f)
        assertEquals(4f, result.dx, 0.01f)
        assertEquals(listOf(104f), result.vertical)
    }

    @Test
    fun `snap move ignores far targets`() {
        val moved = Rect(0f, 0f, 100f, 50f)
        val target = Rect(200f, 200f, 300f, 250f)
        val result = DesignInteract.snapMove(moved, listOf(target), threshold = 4f)
        assertEquals(0f, result.dx, 0.01f)
        assertEquals(0f, result.dy, 0.01f)
        assertTrue(result.vertical.isEmpty())
        assertTrue(result.horizontal.isEmpty())
    }

    @Test
    fun `rotation snaps to right angle`() {
        assertEquals(90f, DesignInteract.snapRotation(87f, threshold = 4f), 0.01f)
        assertEquals(83f, DesignInteract.snapRotation(83f, threshold = 4f), 0.01f)
        assertEquals(0f, DesignInteract.snapRotation(-2f, threshold = 4f), 0.01f)
    }

    @Test
    fun `rotation degrees follows pointer`() {
        val center = Offset(50f, 50f)
        val degrees = DesignInteract.rotationDegrees(center, Offset(50f, 0f), Offset(100f, 50f))
        assertEquals(90f, degrees, 0.01f)
    }

    @Test
    fun `hit test prefers topmost and skips decor`() {
        val nodes = listOf(
            node("card", 0f, 0f, 100f, 100f, type = "card"),
            node("decor", 0f, 0f, 100f, 100f, type = "decor"),
            node("title", 10f, 10f, 40f, 20f),
        )
        assertEquals("title", DesignInteract.hitTest(nodes, Offset(20f, 15f))?.id)
        assertEquals("card", DesignInteract.hitTest(nodes, Offset(90f, 90f))?.id)
        assertNull(DesignInteract.hitTest(nodes, Offset(150f, 150f)))
    }

    @Test
    fun `hit test respects rotation`() {
        // 90° 旋转后节点变成中心处的竖条：原点不再命中，中心上方命中
        val rotated = listOf(node("card", 0f, 0f, 100f, 20f, type = "card", rotation = 90f))
        assertNull(DesignInteract.hitTest(rotated, Offset(0f, 0f)))
        assertNotNull(DesignInteract.hitTest(rotated, Offset(50f, 0f)))
    }

    @Test
    fun `marquee selects intersecting nodes`() {
        val nodes = listOf(
            node("a", 0f, 0f, 50f, 50f),
            node("b", 100f, 100f, 50f, 50f),
            node("decor", 0f, 0f, 50f, 50f, type = "decor"),
        )
        assertEquals(setOf("a"), DesignInteract.marqueeSelection(nodes, Rect(-10f, -10f, 60f, 60f)))
    }

    @Test
    fun `handle hit uses radius`() {
        val target = node("card", 0f, 0f, 100f, 100f, type = "card")
        assertEquals(
            DesignHandle.BottomRight,
            DesignInteract.hitHandle(target, Offset(101f, 99f), radius = 12f, rotateOffset = 20f),
        )
        assertNull(DesignInteract.hitHandle(target, Offset(50f, 50f), radius = 12f, rotateOffset = 20f))
    }

    // ------------------------------------------------------------ 相机 / 双指

    @Test
    fun `camera round trips screen and design points`() {
        val camera = DesignCamera(1000f, 800f, 390f, 844f)
        val screen = Offset(712f, 345f)
        val design = camera.toDesign(screen)
        val back = camera.toScreen(design)
        assertEquals(screen.x, back.x, .01f)
        assertEquals(screen.y, back.y, .01f)
    }

    @Test
    fun `zoom keeps anchor point fixed`() {
        val camera = DesignCamera(1000f, 800f, 390f, 844f)
        val anchor = Offset(700f, 300f)
        val before = camera.toDesign(anchor)
        val next = camera.zoomAround(anchor, 2f)
        val after = next.toDesign(anchor)
        assertEquals(before.x, after.x, .01f)
        assertEquals(before.y, after.y, .01f)
        assertEquals(2f, next.zoom, .001f)
    }

    @Test
    fun `zoom clamps to allowed range`() {
        val camera = DesignCamera(1000f, 800f, 390f, 844f)
        assertEquals(DesignCamera.MAX_ZOOM, camera.zoomAround(Offset.Zero, 99f).zoom, .001f)
        assertEquals(DesignCamera.MIN_ZOOM, camera.zoomAround(Offset.Zero, .01f).zoom, .001f)
    }

    @Test
    fun `two finger decides pan or zoom and locks zoom`() {
        val startSpan = 100f
        val start = Offset(200f, 200f)
        // 两指只移动中心：平移
        assertEquals(
            TwoFingerIntent.Pan,
            DesignInteract.twoFingerIntent(
                TwoFingerIntent.Undecided, startSpan, start,
                span = 101f, centroid = Offset(260f, 200f), slop = 8f,
            ),
        )
        // 两指间距变化明显：缩放
        assertEquals(
            TwoFingerIntent.Zoom,
            DesignInteract.twoFingerIntent(
                TwoFingerIntent.Undecided, startSpan, start,
                span = 160f, centroid = start, slop = 8f,
            ),
        )
        // 一旦进入缩放，后续即使中心移动也保持缩放
        assertEquals(
            TwoFingerIntent.Zoom,
            DesignInteract.twoFingerIntent(
                TwoFingerIntent.Zoom, startSpan, start,
                span = 100f, centroid = Offset(400f, 400f), slop = 8f,
            ),
        )
        // 抖动不超过阈值时保持未定，不会误平移
        assertEquals(
            TwoFingerIntent.Undecided,
            DesignInteract.twoFingerIntent(
                TwoFingerIntent.Undecided, startSpan, start,
                span = 102f, centroid = Offset(203f, 202f), slop = 8f,
            ),
        )
    }

    @Test
    fun `drag threshold ignores small movement`() {
        assertEquals(false, DesignInteract.dragExceeded(Offset(10f, 10f), Offset(12f, 12f), 8f))
        assertEquals(true, DesignInteract.dragExceeded(Offset(10f, 10f), Offset(20f, 10f), 8f))
    }

    // ------------------------------------------------------------ 吸附

    @Test
    fun `snap move reports every matching guide`() {
        val moved = Rect(0f, 0f, 100f, 50f)
        val targets = listOf(
            Rect(4f, 100f, 54f, 150f),
            Rect(104f, 0f, 204f, 50f),
        )
        val result = DesignInteract.snapMove(moved, targets, threshold = 8f)
        assertEquals(4f, result.dx, .01f)
        // 左边缘对齐 4、中心对齐 54、右边缘对齐 104，命中几条就画几条
        assertEquals(listOf(4f, 54f, 104f), result.vertical)
    }

    @Test
    fun `snap move matches an existing gap`() {
        val a = Rect(0f, 0f, 50f, 50f)
        val b = Rect(100f, 0f, 150f, 50f)
        // 与 a/b 之间已有的 50 空隙对齐：190 → 200
        val moved = Rect(190f, 0f, 240f, 50f)
        val result = DesignInteract.snapMove(moved, listOf(a, b), threshold = 12f)
        assertEquals(10f, result.dx, .01f)
        assertEquals(1, result.gaps.size)
        assertEquals(150f, result.gaps[0].start.x, .01f)
        assertEquals(200f, result.gaps[0].end.x, .01f)
       // 间距吸附时不再额外报等距对齐线
        assertTrue(result.vertical.isEmpty())
    }

    @Test
    fun `snap move equalizes gaps on both sides`() {
        val a = Rect(0f, 0f, 50f, 50f)
        val b = Rect(200f, 0f, 250f, 50f)
        // 左缝 60、右缝 40 → 各让 10，居中到 100
        val moved = Rect(110f, 0f, 160f, 50f)
        val result = DesignInteract.snapMove(moved, listOf(a, b), threshold = 12f)
        assertEquals(-10f, result.dx, .01f)
        assertEquals(2, result.gaps.size)
    }

    @Test
    fun `snap resize pulls moving edge only`() {
        val frame = NodeFrame(0f, 0f, 100f, 50f)
        // 底边 50 距目标顶边 56 只有 6：吸到 56
        val target = Rect(0f, 56f, 100f, 100f)
        val result = DesignInteract.snapResize(
            frame, rotation = 0f, handle = DesignHandle.BottomRight,
            targets = listOf(target), threshold = 8f,
        )
        assertEquals(56f, result.frame.h, .01f)
        assertEquals(100f, result.frame.w, .01f)
        assertEquals(listOf(56f), result.horizontal)
        assertEquals(emptyList<Float>(), result.vertical)
    }

    @Test
    fun `snap resize skips rotated nodes`() {
        val frame = NodeFrame(0f, 0f, 100f, 50f)
        val target = Rect(0f, 56f, 100f, 100f)
        val result = DesignInteract.snapResize(
            frame, rotation = 30f, handle = DesignHandle.BottomRight,
            targets = listOf(target), threshold = 8f,
        )
        assertEquals(frame, result.frame)
        assertTrue(result.vertical.isEmpty() && result.horizontal.isEmpty())
    }

    // ------------------------------------------------------------ 等比 / 旋转

    @Test
    fun `locked corner resize keeps aspect ratio`() {
        val frame = NodeFrame(0f, 0f, 100f, 50f)
        val next = DesignInteract.resizeFrame(
            frame, rotation = 0f, handle = DesignHandle.BottomRight,
            pointer = Offset(200f, 200f), lockAspect = true,
        )
        assertEquals(2f, next.w / next.h, .01f)
        assertEquals(0f, next.x, .01f)
        assertEquals(0f, next.y, .01f)
    }

    @Test
    fun `locked rotated resize pins opposite corner`() {
        val frame = NodeFrame(0f, 0f, 100f, 50f)
        val rotation = 90f
        val center = frame.center
        val handle = rotatePoint(
            Offset(frame.x + frame.w, frame.y + frame.h), center, rotation,
        )
        val next = DesignInteract.resizeFrame(
            frame, rotation, DesignHandle.BottomRight, handle, lockAspect = true,
        )
        val before = rotatePoint(Offset(frame.x, frame.y), center, rotation)
        val after = rotatePoint(Offset(next.x, next.y), next.center, rotation)
        assertEquals(before.x, after.x, .01f)
        assertEquals(before.y, after.y, .01f)
        assertEquals(next.w / next.h, 2f, .02f)
    }

    @Test
    fun `rotation snaps to fifteen degree steps`() {
        assertEquals(45f, DesignInteract.snapRotation(43f, threshold = 4f), .01f)
        assertEquals(52f, DesignInteract.snapRotation(52f, threshold = 4f), .01f)
        assertEquals(90f, DesignInteract.snapRotation(87f, threshold = 4f), .01f)
        assertEquals(105f, DesignInteract.constrainRotation(100f, 15f), .01f)
        assertEquals(45f, DesignInteract.constrainRotation(43f, 15f), .01f)
    }

    // ------------------------------------------------------------ 命中精度

    @Test
    fun `hit test uses ellipse geometry`() {
        val oval = listOf(node("oval", 0f, 0f, 100f, 50f, type = "circle", ellipse = true))
        // 外接框的角落在椭圆外
        assertNull(DesignInteract.hitTest(oval, Offset(2f, 2f)))
        assertNotNull(DesignInteract.hitTest(oval, Offset(50f, 25f)))
        assertNull(DesignInteract.hitTest(oval, Offset(50f, 60f)))
    }

    @Test
    fun `hit test ignores children clipped outside parent`() {
        val child = node("child", 150f, 10f, 50f, 20f)
        val parent = node(
            "card", 0f, 0f, 100f, 100f, type = "card", clip = true,
            children = listOf(child),
        )
        // 子节点在容器外，被裁剪后不应命中
        assertNull(DesignInteract.hitTest(listOf(parent), Offset(160f, 15f)))
        assertNotNull(DesignInteract.hitTest(listOf(parent), Offset(50f, 50f)))
    }

    @Test
    fun `hit test prefers tiny rotated node`() {
        val thin = listOf(node("thin", 0f, 40f, 200f, 4f, type = "rect", rotation = 90f))
        // 旋转 90° 后变成中心竖条：x≈100、y∈[0,200]
        assertNotNull(DesignInteract.hitTest(thin, Offset(100f, 10f)))
        assertNull(DesignInteract.hitTest(thin, Offset(10f, 100f)))
    }

    @Test
    fun `handle radius shrinks on tiny nodes`() {
        assertEquals(22f, DesignInteract.handleHitRadius(22f, NodeFrame(0f, 0f, 200f, 200f)), .01f)
        assertTrue(
            DesignInteract.handleHitRadius(22f, NodeFrame(0f, 0f, 20f, 20f)) < 22f,
        )
    }
}
