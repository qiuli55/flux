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
        ellipse = false,
        fill = null,
        fillColor = Color.Black,
        trackColor = Color.Black,
        stroke = null,
        shadowRings = emptyList(),
        insetShadowRings = emptyList(),
        text = "",
        textOrigin = Offset.Zero,
        textLayout = null,
        navCells = emptyList(),
        value = 0f,
        switchOn = false,
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
}
