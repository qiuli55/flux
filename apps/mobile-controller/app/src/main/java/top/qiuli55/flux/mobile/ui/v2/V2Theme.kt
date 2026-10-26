package top.qiuli55.flux.mobile.ui.v2

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Flux 移动控制台 v2 设计令牌。
 *
 * 全部色值来自 A 机 demo `flux-mobile-v2` 的 CSS（Tailwind 类 + 自定义变量），
 * 不是目测近似：白底、青绿主色、发丝线、浅色玻璃面板。
 *
 * 深浅色由 [FluxV2Theme] 按系统设置注入：浅色沿用原色值，深色把画布压到近黑、
 * 文字反转为浅灰、卡片改成低透白叠加（玻璃面板在深底上依然成立）。
 */
object V2Colors {
    private var dark by mutableStateOf(false)

    /** 当前是否深色，供需要分支取色的组件判断。 */
    val isDark: Boolean get() = dark

    internal fun applyDark(value: Boolean) {
        dark = value
    }

    val bg: Color get() = if (dark) Color(0xFF060A11) else Color(0xFFEEF1F5)
    val shellTop: Color get() = if (dark) Color(0xFF0B121C) else Color(0xFFF4F6F9)
    val shellBottom: Color get() = if (dark) Color(0xFF080E17) else Color(0xFFF0F3F7)

    /** 纯白，只用于彩色底上的前景文字/图标，不跟随主题。 */
    val white = Color(0xFFFFFFFF)

    /** 卡片、弹层的实底。 */
    val surface: Color get() = if (dark) Color(0xFF131C2A) else Color(0xFFFFFFFF)

    /**
     * 半透明玻璃面板：浅色是白底叠层，深色改成更淡的白叠层，叠加后同样是「比背景亮一点」。
     */
    fun surface(alpha: Float): Color =
        if (dark) Color(0xFFE8EEF7).copy(alpha = alpha * .09f) else Color.White.copy(alpha = alpha)

    /** demo 把 Tailwind 的 text-white/X 全部重映射成 rgba(15,23,42,X)，深色下换成浅灰同透明度。 */
    val ink: Color get() = if (dark) Color(0xFFE8EEF7) else Color(0xFF0F172A)

    fun ink(alpha: Float): Color = ink.copy(alpha = alpha)

    val line: Color get() = if (dark) Color(0x24E8EEF7) else Color(0x170F172A)
    val lineSoft: Color get() = if (dark) Color(0x12E8EEF7) else Color(0x0F0F172A)

    val accent = Color(0xFF0891B2)    // cyan-600
    val cyan500 = Color(0xFF06B6D4)
    val cyan700: Color get() = if (dark) Color(0xFF22D3EE) else Color(0xFF0E7490)
    val cyan800: Color get() = if (dark) Color(0xFF67E8F9) else Color(0xFF155E75)
    val cyan400 = Color(0xFF22D3EE)
    val avatarBlue = Color(0xFF4FB3E8)
    val avatarInk = Color(0xFF0B2B3D)
    val actionInk = Color(0xFF04222A)

    val amber: Color get() = if (dark) Color(0xFFF59E0B) else Color(0xFFD97706)
    val amber500 = Color(0xFFF59E0B)
    val amber700: Color get() = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val rose: Color get() = if (dark) Color(0xFFF43F5E) else Color(0xFFE11D48)
    val rose500 = Color(0xFFF43F5E)
    val rose700: Color get() = if (dark) Color(0xFFFDA4AF) else Color(0xFFBE123C)
    val emerald: Color get() = Color(0xFF10B981)
    val emerald700: Color get() = if (dark) Color(0xFF34D399) else Color(0xFF047857)
    val indigo = Color(0xFF4F46E5)
    val violet = Color(0xFF7C3AED)
    val scrim: Color get() = if (dark) Color(0x99000000) else Color(0x590F172A)
}

private fun v2ColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = V2Colors.cyan400,
        onPrimary = V2Colors.actionInk,
        secondary = V2Colors.cyan500,
        background = V2Colors.shellTop,
        onBackground = V2Colors.ink,
        surface = V2Colors.surface,
        onSurface = V2Colors.ink,
        outline = V2Colors.line,
        error = V2Colors.rose,
    )
} else {
    lightColorScheme(
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
}

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
    val dark = isSystemInDarkTheme()
    // 先落令牌再组合子树：子组件同一帧就能取到正确色值，不会闪一帧浅色。
    if (V2Colors.isDark != dark) V2Colors.applyDark(dark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = v2ColorScheme(dark),
        typography = V2Typography,
        content = content,
    )
}
