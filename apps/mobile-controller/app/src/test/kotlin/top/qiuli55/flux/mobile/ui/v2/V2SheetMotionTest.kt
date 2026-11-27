package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部弹层手势/动画纯函数：
 * 橡胶带、松手判定、以及回位/展开（.9 / 700）与关闭（临界阻尼）的动画 spec。
 */
class V2SheetMotionTest {

    private val height = 1000f

    // ------------------------------------------------------------------ 橡胶带

    @Test
    fun `rubber band is zero without overscroll`() {
        assertEquals(0f, v2SheetRubberBand(0f, height), .0001f)
        assertEquals(0f, v2SheetRubberBand(-50f, height), .0001f)
    }

    @Test
    fun `rubber band increases monotonically`() {
        val offsets = listOf(1f, 10f, 50f, 100f, 250f, 500f, 1000f)
        val values = offsets.map { v2SheetRubberBand(it, height) }
        for (i in 1 until values.size) {
            assertTrue(
                "rubber band must keep increasing at ${offsets[i]}: $values",
                values[i] > values[i - 1],
            )
        }
    }

    @Test
    fun `rubber band gain decreases as overscroll grows`() {
        val step = 50f
        val starts = listOf(0f, 100f, 400f, 1000f)
        val gains = starts.map { start ->
            (v2SheetRubberBand(start + step, height) - v2SheetRubberBand(start, height)) / step
        }
        for (i in 1 until gains.size) {
            assertTrue(
                "gain must decay: $gains",
                gains[i] < gains[i - 1],
            )
        }
        // 起始增益就是系数，且永远小于 1。
        assertTrue(gains.first() < 1f)
    }

    @Test
    fun `rubber band is bounded by the dimension`() {
        assertTrue(v2SheetRubberBand(100_000f, height) < height)
    }

    // ------------------------------------------------------------------ 拖动映射

    @Test
    fun `drag follows finger one to one within the limit`() {
        assertEquals(-120f, v2SheetDragOffset(-120f, height, expanded = false), .001f)
        assertEquals(300f, v2SheetDragOffset(300f, height, expanded = false), .001f)
        assertEquals(-40f, v2SheetDragOffset(-40f, height, expanded = true), .001f)
    }

    @Test
    fun `dragging past the upper limit turns into rubber band`() {
        val upperHalf = v2SheetUpperDragLimit(height, expanded = false)
        assertEquals(-250f, upperHalf, .001f)
        val resistedHalf = v2SheetDragOffset(-400f, height, expanded = false)
        assertTrue("must resist, not clamp at raw drag", resistedHalf > -400f)
        assertTrue("must still pass the hard limit", resistedHalf < upperHalf)
        assertTrue("must be near the limit, not 1:1", resistedHalf > upperHalf - 100f)

        val upperExpanded = v2SheetUpperDragLimit(height, expanded = true)
        assertEquals(-100f, upperExpanded, .001f)
        val resistedExpanded = v2SheetDragOffset(-300f, height, expanded = true)
        assertTrue(resistedExpanded > -300f)
        assertTrue(resistedExpanded < upperExpanded)
    }

    @Test
    fun `downward drag is capped at the sheet height`() {
        assertEquals(height, v2SheetDragOffset(height * 2f, height, expanded = false), .001f)
    }

    // ------------------------------------------------------------ nested scroll 接手

    @Test
    fun `content at top drag moves the sheet one to one and consumes it`() {
        val result = v2SheetNestedDrag(
            availableDownPx = 120f,
            currentOffsetPx = 0f,
            heightPx = height,
            expanded = false,
        )
        assertEquals(120f, result.newOffsetPx, .001f)
        assertEquals("向下剩余位移必须全部由弹层消费", 120f, result.consumedPx, .001f)
    }

    @Test
    fun `nested drag accumulates and never leaks downward leftover`() {
        var offset = 0f
        for (delta in listOf(40f, 80f, 160f)) {
            val result = v2SheetNestedDrag(delta, offset, height, expanded = false)
            assertEquals("弹层要吃掉全部增量", delta, result.consumedPx, .001f)
            offset = result.newOffsetPx
        }
        assertEquals(280f, offset, .001f)
    }

    @Test
    fun `upward scroll first returns the displaced sheet then leaves the rest`() {
        val result = v2SheetNestedDrag(
            availableDownPx = -80f,
            currentOffsetPx = 50f,
            heightPx = height,
            expanded = false,
        )
        assertEquals(0f, result.newOffsetPx, .001f)
        assertEquals("只消费回位所需的 50px", -50f, result.consumedPx, .001f)
    }

    @Test
    fun `upward scroll at rest is left to the content`() {
        val result = v2SheetNestedDrag(
            availableDownPx = -80f,
            currentOffsetPx = 0f,
            heightPx = height,
            expanded = false,
        )
        assertEquals(0f, result.newOffsetPx, .001f)
        assertEquals(0f, result.consumedPx, .001f)
    }

    @Test
    fun `upward nested drag at rest does not move the sheet`() {
        val result = v2SheetNestedDrag(
            availableDownPx = -400f,
            currentOffsetPx = 0f,
            heightPx = height,
            expanded = false,
        )
        // 在顶部继续上推不属于 nested 的下拉路径，不应产生位移。
        assertEquals(0f, result.newOffsetPx, .001f)
        assertEquals(0f, result.consumedPx, .001f)
    }

    @Test
    fun `release velocity is consumed by the sheet only while it is displaced`() {
        // 弹层被拖出：向下关闭、向上回位的速度都不能外漏。
        assertEquals(900f, v2SheetConsumedVelocityY(900f, 120f), .001f)
        assertEquals(-700f, v2SheetConsumedVelocityY(-700f, 120f), .001f)
        // 弹层在原位：那是内容自己的 fling，弹层不抢。
        assertEquals(0f, v2SheetConsumedVelocityY(900f, 0f), .001f)
        assertEquals(0f, v2SheetConsumedVelocityY(-700f, 0f), .001f)
    }

    // ------------------------------------------------------------------ 松手判定

    @Test
    fun `expanded drag down collapses to half height`() {
        assertEquals(
            V2SheetRelease.Collapse,
            v2SheetReleaseAction(100f, 0f, height, expanded = true, 90f, 56f),
        )
    }

    @Test
    fun `expanded fling down collapses`() {
        assertEquals(
            V2SheetRelease.Collapse,
            v2SheetReleaseAction(10f, 800f, height, expanded = true, 90f, 56f),
        )
    }

    @Test
    fun `half height drag down past threshold dismisses`() {
        assertEquals(
            V2SheetRelease.Dismiss,
            v2SheetReleaseAction(350f, 0f, height, expanded = false, 90f, 56f),
        )
    }

    @Test
    fun `half height fling down dismisses`() {
        assertEquals(
            V2SheetRelease.Dismiss,
            v2SheetReleaseAction(50f, 700f, height, expanded = false, 90f, 56f),
        )
    }

    @Test
    fun `half height drag up expands`() {
        assertEquals(
            V2SheetRelease.Expand,
            v2SheetReleaseAction(-60f, 0f, height, expanded = false, 90f, 56f),
        )
    }

    @Test
    fun `half height fling up expands`() {
        assertEquals(
            V2SheetRelease.Expand,
            v2SheetReleaseAction(-10f, -700f, height, expanded = false, 90f, 56f),
        )
    }

    @Test
    fun `short drag below threshold settles back in place`() {
        assertEquals(
            V2SheetRelease.Settle,
            v2SheetReleaseAction(100f, 100f, height, expanded = false, 90f, 56f),
        )
        assertEquals(
            V2SheetRelease.Settle,
            v2SheetReleaseAction(-20f, -100f, height, expanded = false, 90f, 56f),
        )
        assertEquals(
            V2SheetRelease.Settle,
            v2SheetReleaseAction(50f, 100f, height, expanded = true, 90f, 56f),
        )
    }

    @Test
    fun `expanded upward drag settles instead of toggling twice`() {
        assertEquals(
            V2SheetRelease.Settle,
            v2SheetReleaseAction(-300f, -900f, height, expanded = true, 90f, 56f),
        )
    }

    // ------------------------------------------------------------------ 动画 spec

    @Test
    fun `settle and expand use the standard spatial spring with release velocity`() {
        for (release in listOf(V2SheetRelease.Settle, V2SheetRelease.Expand, V2SheetRelease.Collapse)) {
            val plan = v2SheetMotionPlan(
                release = release,
                heightPx = height,
                velocityY = 420f,
                reduceMotion = false,
            )
            assertEquals(0f, plan.targetOffsetPx, .001f)
            assertEquals(420f, plan.initialVelocity, .001f)
            val spec = plan.spec as SpringSpec<Float>
            assertEquals("$release damping", .9f, spec.dampingRatio, .0001f)
            assertEquals("$release stiffness", 700f, spec.stiffness, .0001f)
        }
    }

    @Test
    fun `dismiss is critically damped and carries release velocity`() {
        val plan = v2SheetMotionPlan(
            release = V2SheetRelease.Dismiss,
            heightPx = height,
            velocityY = 900f,
            reduceMotion = false,
        )
        assertEquals(height, plan.targetOffsetPx, .001f)
        assertEquals(900f, plan.initialVelocity, .001f)
        val spec = plan.spec as SpringSpec<Float>
        assertEquals(1f, spec.dampingRatio, .0001f)
        assertEquals(700f, spec.stiffness, .0001f)
    }

    @Test
    fun `reduce motion removes springs and velocity so nothing overshoots`() {
        for (release in V2SheetRelease.values()) {
            val plan = v2SheetMotionPlan(
                release = release,
                heightPx = height,
                velocityY = 900f,
                reduceMotion = true,
            )
            assertTrue("$release must not use a spring", plan.spec is TweenSpec<Float>)
            assertEquals(
                V2_SHEET_REDUCE_MOTION_MS,
                (plan.spec as TweenSpec<Float>).durationMillis,
            )
            assertEquals("$release must not inject velocity", 0f, plan.initialVelocity, .001f)
        }
    }
}
