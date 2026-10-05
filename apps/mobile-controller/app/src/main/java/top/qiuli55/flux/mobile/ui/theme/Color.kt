package top.qiuli55.flux.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Flux 设计令牌（深色）。
 *
 * 色值与桌面端 `apps/web-dashboard/src/styles/design-v2.css` 的 `:root` 逐项对齐，
 * 手机端与桌面端是同一个产品的两种形态，不能各调一套颜色。
 * 设计约束（用户已确认的降噪要求）：纯深色底、少颜色、少字号层级、留白优先。
 */
object FluxColors {
    val bg = Color(0xFF08090F)          // --bg
    val bg2 = Color(0xFF0B0D16)         // --bg-2
    val panel = Color(0xFF10121C)       // --panel
    val panel2 = Color(0xFF141726)      // --panel-2
    val panel3 = Color(0xFF181C2E)      // --panel-3
    val line = Color(0xFF232741)        // --line
    val lineSoft = Color(0xFF1C2033)    // --line-soft
    val text = Color(0xFFE8EAF3)        // --text
    val text2 = Color(0xFF9AA3B8)       // --text-2
    val text3 = Color(0xFF7F88A0)       // --text-3（在最亮面板上仍 ≥4.5:1）
    val accent = Color(0xFF7C5CFF)      // --accent
    val accent2 = Color(0xFF5B3DF5)     // --accent-2
    val blue = Color(0xFF4D7CFE)        // --blue
    val green = Color(0xFF3DDC97)       // --green
    val green2 = Color(0xFF22C55E)      // --green-2
    val orange = Color(0xFFF5A623)      // --orange
    val red = Color(0xFFF16A6A)         // --red
}