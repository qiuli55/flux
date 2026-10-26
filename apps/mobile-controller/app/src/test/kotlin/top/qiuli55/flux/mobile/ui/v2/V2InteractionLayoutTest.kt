package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/** 长按气泡菜单的横向定位：贴边、越界、窄屏三种情况都要落在内容区里。 */
class V2InteractionLayoutTest {

    private val rootWidth = 1000f
    private val menuWidth = 400f
    private val margin = 16f

    @Test
    fun `menu aligns with bubble when there is room`() {
        assertEquals(240f, v2ClampMenuLeftPx(240f, rootWidth, menuWidth, margin), .01f)
    }

    @Test
    fun `menu is pulled inward near right edge`() {
        assertEquals(584f, v2ClampMenuLeftPx(900f, rootWidth, menuWidth, margin), .01f)
    }

    @Test
    fun `menu stays at margin when bubble is off screen left`() {
        assertEquals(margin, v2ClampMenuLeftPx(-120f, rootWidth, menuWidth, margin), .01f)
    }

    @Test
    fun `narrow screen keeps menu at margin`() {
        assertEquals(16f, v2ClampMenuLeftPx(0f, 300f, menuWidth, margin), .01f)
    }
}
