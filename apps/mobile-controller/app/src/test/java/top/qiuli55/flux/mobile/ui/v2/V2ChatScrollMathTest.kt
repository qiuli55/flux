package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「滚到最后一项底部」的偏移计算。
 *
 * 参考实测值来自 LazyColumn（400x800、上下 contentPadding 各 12px）：
 * viewportStartOffset = -12、viewportEndOffset = 788，可视内容底边为两者之和 776。
 */
class V2ChatScrollMathTest {

    @Test
    fun `tall last item scrolls to its end instead of its start`() {
        // 1500px 的长消息放在视口顶部时，还需要 724px 才能让末尾贴住视口底部。
        val delta = bottomAlignScrollDelta(
            itemOffset = 0,
            itemSize = 1500,
            viewportStartOffset = -12,
            viewportEndOffset = 788,
        )
        assertEquals(724, delta)
        // 贴底后该项底部正好落在可视内容底边，不再向前可滚。
        assertEquals(-12 + 788, 0 + 1500 - delta)
    }

    @Test
    fun `item shorter than the viewport is not pushed past the bottom`() {
        val delta = bottomAlignScrollDelta(
            itemOffset = 0,
            itemSize = 300,
            viewportStartOffset = -12,
            viewportEndOffset = 788,
        )
        assertEquals(0, delta)
    }

    @Test
    fun `already bottom aligned item needs no extra scroll`() {
        val delta = bottomAlignScrollDelta(
            itemOffset = -724,
            itemSize = 1500,
            viewportStartOffset = -12,
            viewportEndOffset = 788,
        )
        assertEquals(0, delta)
    }

    @Test
    fun `partially visible item only scrolls the missing distance`() {
        val delta = bottomAlignScrollDelta(
            itemOffset = -100,
            itemSize = 1500,
            viewportStartOffset = -12,
            viewportEndOffset = 788,
        )
        assertEquals(624, delta)
    }
}
