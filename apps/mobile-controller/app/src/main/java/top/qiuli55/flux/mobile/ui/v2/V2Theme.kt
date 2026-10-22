package top.qiuli55.flux.mobile.ui.v2

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Flux 移动控制台 v2 设计令牌。
 *
 * 全部色值来自 A 机 demo `flux-mobile-v2` 的 CSS（Tailwind 类 + 自定义变量），
 * 不是目测近似：白底、青绿主色、发丝线、浅色玻璃面板。
 */
object V2Colors {
    val bg = Color(0xFFEEF1F5)
    val shellTop = Color(0xFFF4F6F9)
    val shellBottom = Color(0xFFF0F3F7)
    val white = Color(0xFFFFFFFF)

    /** demo 把 Tailwind 的 text-white/X 全部重映射成 rgba(15,23,42,X)。 */
    val ink = Color(0xFF0F172A)
    fun ink(alpha: Float): Color = Color(0xFF0F172A).copy(alpha = alpha)

    val line = Color(0x170F172A)      // rgba(15,23,42,.09)
    val lineSoft = Color(0x0F0F172A)  // rgba(15,23,42,.06)

    val accent = Color(0xFF0891B2)    // cyan-600
    val cyan500 = Color(0xFF06B6D4)
    val cyan700 = Color(0xFF0E7490)
    val cyan800 = Color(0xFF155E75)
    val cyan400 = Color(0xFF22D3EE)
    val avatarBlue = Color(0xFF4FB3E8)
    val avatarInk = Color(0xFF0B2B3D)
    val actionInk = Color(0xFF04222A)

    val amber = Color(0xFFD97706)     // amber-600
    val amber500 = Color(0xFFF59E0B)
    val amber700 = Color(0xFFB45309)
    val rose = Color(0xFFE11D48)      // rose-600
    val rose500 = Color(0xFFF43F5E)
    val rose700 = Color(0xFFBE123C)
    val emerald = Color(0xFF10B981)   // emerald-500
    val emerald700 = Color(0xFF047857)
    val indigo = Color(0xFF4F46E5)    // indigo-600
    val violet = Color(0xFF7C3AED)
    val scrim = Color(0x590F172A)     // slate-900/35
}

private val V2LightScheme = lightColorScheme(
    primary = V2Colors.accent,
    onPrimary = Color.White,
    secondary = V2Colors.cyan700,
    background = V2Colors.shellTop,
    onBackground = V2Colors.ink,
    surface = Color.White,
    onSurface = V2Colors.ink,
    outline = V2Colors.line,
    error = V2Colors.rose,
)

private val V2Typography = Typography(
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
)

val V2SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

@Composable
fun FluxV2Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = V2LightScheme,
        typography = V2Typography,
        content = content,
    )
}
