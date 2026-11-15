package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送入口的连点保护窗口（建议 chip / 气泡「重发」/ 发送按钮共用）。
 *
 * 正常路径：第一次点按立即生效；
 * 误触路径：窗口内的第二次点按被丢弃，窗口过后又能正常发送。
 */
class V2SendGuardTest {

    @Test
    fun `first tap is accepted`() {
        val guard = StopTapGuard(windowMs = 800L)
        assertTrue(guard.tryAccept(10_000L))
    }

    @Test
    fun `rapid second and third taps are dropped`() {
        val guard = StopTapGuard(windowMs = 800L)
        assertTrue(guard.tryAccept(10_000L))
        assertFalse(guard.tryAccept(10_120L))
        assertFalse(guard.tryAccept(10_400L))
    }

    @Test
    fun `tap just before the window closes is still dropped`() {
        val guard = StopTapGuard(windowMs = 800L)
        assertTrue(guard.tryAccept(10_000L))
        assertFalse(guard.tryAccept(10_799L))
    }

    @Test
    fun `tap after the window is accepted again`() {
        val guard = StopTapGuard(windowMs = 800L)
        assertTrue(guard.tryAccept(10_000L))
        assertTrue(guard.tryAccept(10_800L))
    }

    @Test
    fun `clock going backwards does not lock the guard`() {
        val guard = StopTapGuard(windowMs = 800L)
        assertTrue(guard.tryAccept(10_000L))
        assertTrue(guard.tryAccept(9_000L))
    }
}
